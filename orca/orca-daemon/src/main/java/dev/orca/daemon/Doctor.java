package dev.orca.daemon;

import dev.orca.agent.core.Executables;
import dev.orca.protocol.ProviderStatus;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * {@code orca-daemon doctor}: checks that this machine can run agent tasks — Java, git, the work folder,
 * the server and token, and each agent CLI's installation and login — and says how to fix what is missing.
 */
final class Doctor {

  private final DaemonConfig cfg;
  private final PrintStream out;
  private int failures;
  private int warnings;
  private final boolean unicode;

  Doctor(DaemonConfig cfg, PrintStream out) {
    this.cfg = cfg;
    this.out = out;
    this.unicode = out.charset().name().toUpperCase().startsWith("UTF");
  }

  /** Returns 0 when nothing failed (warnings allowed), 1 otherwise. */
  int run() {
    out.println("orca-daemon " + Daemon.VERSION + " doctor - settings " + DaemonConfig.file());
    out.println();
    int java = Runtime.version().feature();
    check(java >= 21, "Java " + Runtime.version(), "Java 21 or later is required (use the bundled runtime or install a JDK 21)");

    Executables.Output git = Executables.run(List.of("git", "--version"), Map.of(), 15);
    check(git.exitCode() == 0, git.exitCode() == 0 ? git.stdout().trim() : "git", "install git and put it on PATH");
    if (git.exitCode() == 0) {
      String name = Executables.run(List.of("git", "config", "--global", "user.name"), Map.of(), 10).stdout().trim();
      warn(!name.isEmpty(), "git identity: " + (name.isEmpty() ? "not set" : name),
          "set git config --global user.name / user.email; agent commits use it as committer");
    }

    Path work = cfg.workRootPath();
    try {
      Files.createDirectories(work);
      Path probe = Files.createTempFile(work, ".doctor", ".tmp");
      Files.delete(probe);
      long free = Files.getFileStore(work).getUsableSpace() / (1024 * 1024 * 1024);
      check(true, "work folder " + work + " is writable", null);
      warn(free >= 2, "free disk space: " + free + " GB", "keep at least 2 GB free for clones and builds");
    } catch (Exception e) {
      check(false, "work folder " + work, "make it writable or choose another with --work-root DIR (" + e.getMessage() + ")");
    }

    out.println();
    boolean registered = cfg.server() != null && cfg.token() != null;
    check(registered, registered ? "registered with " + cfg.server() : "not registered",
        "run orca-daemon register --server URL --code CODE (Runtimes > Add runtime)");
    if (registered) {
      HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
      String base = cfg.server().replaceAll("/+$", "");
      try {
        HttpResponse<String> h = http.send(HttpRequest.newBuilder(URI.create(base + "/api/health"))
            .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        check(h.statusCode() == 200, "server reachable (" + base + ")", "the server answered " + h.statusCode());
        URI ws = URI.create(base.replaceFirst("^http", "ws") + "/api/daemon/ws");
        try {
          WebSocket socket = http.newWebSocketBuilder().header("Authorization", "Bearer " + cfg.token())
              .connectTimeout(Duration.ofSeconds(10)).buildAsync(ws, new WebSocket.Listener() {})
              .get(15, TimeUnit.SECONDS);
          socket.sendClose(WebSocket.NORMAL_CLOSURE, "doctor");
          check(true, "runtime token accepted", null);
        } catch (Exception e) {
          String why = String.valueOf(e instanceof CompletionException || e.getCause() != null ? e.getCause() : e);
          check(false, "runtime token", why.contains("401")
              ? "the server rejected the token; if the runtime was removed, register again"
              : "could not open the daemon connection: " + why);
        }
      } catch (Exception e) {
        check(false, "server " + base, "cannot reach it: " + e.getMessage() + " (proxy, firewall or URL?)");
      }
    }

    out.println();
    for (ProviderStatus p : new Daemon(cfg, Daemon.defaultProviders(cfg, Providers::trae)).probe()) {
      String name = "codex".equals(p.provider()) ? "Codex CLI" : "TRAE CLI";
      if (!p.installed()) {
        warn(false, name + ": not installed", "codex".equals(p.provider())
            ? "npm install -g @openai/codex (or see scripts/install-runtime.sh)"
            : "install TRAE CLI 2.x from TRAE (curl -fsSL https://trae.cn/trae-cli/install_v2.sh | bash)");
        continue;
      }
      check(true, name + " " + p.version() + " at " + p.path(), null);
      warn(p.loggedIn(), name + (p.loggedIn() ? ": logged in" + (p.plan() != null ? " (" + p.plan() + ")" : "") : ": not logged in"),
          "codex".equals(p.provider()) ? "run codex login (headless: codex login --device-auth)"
              : "run traecli login, or TRAECLI_PERSONAL_ACCESS_TOKEN=<pat> traecli login --with-trae-pat");
    }

    out.println();
    warn(true, "sandbox network for agents: " + (cfg.allowNetwork() ? "allowed (--allow-network)"
        : "blocked (start with --allow-network to let agents that ask for it install packages)"), null);
    warn(true, "full-access tier: " + (cfg.isolated() ? "allowed (--isolated)"
        : "downgraded to workspace (only pass --isolated on a disposable VM or container)"), null);
    try (FileChannel ch = FileChannel.open(DaemonConfig.file().resolveSibling("daemon.lock"), StandardOpenOption.CREATE,
        StandardOpenOption.WRITE)) {
      FileLock lock = ch.tryLock();
      warn(true, lock == null ? "a daemon is running with these settings" : "no daemon is running with these settings", null);
      if (lock != null) {
        lock.release();
      }
    } catch (Exception e) {
      // the lock file is advisory
    }

    out.println();
    out.println(failures == 0 ? (warnings == 0 ? "All good." : "Ready, with " + warnings + " warning(s).")
        : failures + " problem(s) to fix before this machine can run tasks.");
    return failures == 0 ? 0 : 1;
  }

  private String mark(String symbol, String ascii) {
    return unicode ? "  " + symbol + " " : "  [" + ascii + "] ";
  }

  private String arrow() {
    return unicode ? "\n      → " : "\n       -> ";
  }

  private void check(boolean ok, String what, String fix) {
    out.println(mark(ok ? "✓" : "✗", ok ? "ok" : "XX") + what + (ok || fix == null ? "" : arrow() + fix));
    if (!ok) {
      failures++;
    }
  }

  private void warn(boolean ok, String what, String fix) {
    out.println(mark(ok ? "✓" : "!", ok ? "ok" : "!!") + what + (ok || fix == null ? "" : arrow() + fix));
    if (!ok) {
      warnings++;
    }
  }
}
