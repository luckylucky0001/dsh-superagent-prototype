package dev.orca.daemon;

import dev.orca.protocol.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code orca-daemon register --server URL --code CODE [--name NAME]} exchanges a one-time code for a
 * runtime token and saves it; {@code orca-daemon start} runs the daemon; {@code orca-daemon probe}
 * prints what this machine can run.
 */
public final class Main {

  private Main() {}

  public static void main(String[] argv) throws Exception {
    if (argv.length == 0) {
      usage();
      return;
    }
    String cmd = argv[0];
    if (List.of(argv).contains("--help") || List.of(argv).contains("-h")) {
      usage();
      return;
    }
    Map<String, String> flags = parse(Arrays.copyOfRange(argv, 1, argv.length));
    DaemonConfig cfg = DaemonConfig.load()
        .with(flags.get("server"), flags.get("token"), flags.get("name"),
            flags.containsKey("capacity") ? Integer.parseInt(flags.get("capacity")) : null, flags.get("work-root"))
        .withCommands(split(flags.get("codex")), split(flags.get("trae")));
    if (flags.containsKey("isolated")) {
      cfg = cfg.withIsolated(true);
    }
    if (flags.containsKey("allow-extra-args")) {
      cfg = cfg.withAllowExtraArgs(true);
    }
    switch (cmd) {
      case "register" -> {
        String code = require(flags, "code");
        String server = require(flags, "server");
        HttpRequest req = HttpRequest.newBuilder(URI.create(server.replaceAll("/$", "") + "/api/daemon/register"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("code", code))))
            .build();
        HttpResponse<String> res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
          System.err.println("registration failed: " + res.statusCode() + " " + res.body());
          System.exit(1);
        }
        String token = Json.str(Json.readMap(res.body()), "token");
        cfg.with(server, token, null, null, null).save();
        System.out.println("registered; settings saved to " + DaemonConfig.file());
      }
      case "start" -> {
        if (flags.containsKey("save")) {
          cfg.save();
        }
        if (cfg.server() == null || cfg.token() == null) {
          System.err.println("not registered: run `orca-daemon register --server URL --code CODE` first"
              + " (settings are read from " + DaemonConfig.file() + ")");
          System.exit(2);
        }
        // one daemon per configuration: two processes with the same token would keep displacing each other
        java.nio.file.Files.createDirectories(DaemonConfig.file().getParent());
        java.nio.channels.FileChannel lockFile = java.nio.channels.FileChannel.open(
            DaemonConfig.file().resolveSibling("daemon.lock"), java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE);
        java.nio.channels.FileLock lock = lockFile.tryLock();
        if (lock == null) {
          System.err.println("another orca-daemon is already running with " + DaemonConfig.file()
              + "; stop it first, or use a different ORCA_HOME for a second runtime");
          System.exit(3);
        }
        new Daemon(cfg, Daemon.defaultProviders(cfg, Providers::trae)).run();
      }
      case "probe" -> System.out.println(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
          new Daemon(cfg, Daemon.defaultProviders(cfg, Providers::trae)).probe()));
      case "version" -> System.out.println("orca-daemon " + Daemon.VERSION);
      default -> usage();
    }
  }

  private static Map<String, String> parse(String[] args) {
    Map<String, String> m = new HashMap<>();
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      if (!a.startsWith("--")) {
        continue;
      }
      String key = a.substring(2);
      int eq = key.indexOf('=');
      if (eq >= 0) {
        m.put(key.substring(0, eq), key.substring(eq + 1));
      } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
        m.put(key, args[++i]);
      } else {
        m.put(key, "true");
      }
    }
    return m;
  }

  private static List<String> split(String s) {
    if (s == null || s.isBlank()) {
      return null;
    }
    return new ArrayList<>(List.of(s.trim().split("\\s+")));
  }

  private static String require(Map<String, String> flags, String key) {
    String v = flags.get(key);
    if (v == null || v.isBlank()) {
      System.err.println("--" + key + " is required");
      System.exit(2);
    }
    return v;
  }

  private static void usage() {
    System.out.println("""
        orca-daemon register --server URL --code CODE
        orca-daemon start [--server URL --token TOKEN] [--name NAME] [--capacity N] [--work-root DIR]
                          [--codex "codex"] [--trae "traecli"] [--isolated] [--allow-extra-args] [--save]
        orca-daemon probe
        orca-daemon version""");
  }
}
