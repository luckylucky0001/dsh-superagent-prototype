package dev.orca.daemon;

import dev.orca.agent.core.Executables;
import dev.orca.protocol.Json;
import dev.orca.protocol.ProviderStatus;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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

  private final Path settings;

  Doctor(DaemonConfig cfg, PrintStream out) {
    this(cfg, out, DaemonConfig.file());
  }

  /** {@code settings}: the daemon.json in use; the lock file sits beside it. */
  Doctor(DaemonConfig cfg, PrintStream out, Path settings) {
    this.settings = settings;
    this.cfg = cfg;
    this.out = out;
    this.unicode = out.charset().name().toUpperCase().startsWith("UTF");
  }

  /** Returns 0 when nothing failed (warnings allowed), 1 otherwise. */
  int run() {
    out.println("orca-daemon " + Daemon.VERSION + " doctor - settings " + settings);
    out.println();
    int java = Runtime.version().feature();
    check(java >= 21, "Java " + Runtime.version(), "Java 21 or later is required (use the bundled runtime or install a JDK 21)");

    Executables.Output git = Executables.run(List.of("git", "--version"), Map.of(), 15);
    check(git.exitCode() == 0, git.exitCode() == 0 ? git.stdout().trim() : "git", "install git and put it on PATH");
    if (git.exitCode() == 0) {
      String name = Executables.run(List.of("git", "config", "--global", "user.name"), Map.of(), 10).stdout().trim();
      String email = Executables.run(List.of("git", "config", "--global", "user.email"), Map.of(), 10).stdout().trim();
      warn(!name.isEmpty() && !email.isEmpty(), "git identity: " + (name.isEmpty() || email.isEmpty() ? "incomplete" : name),
          "set git config --global user.name and user.email; agent commits use them as committer");
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
        String serverVersion = h.statusCode() == 200 ? Json.str(Json.readMap(h.body()), "version") : null;
        check(h.statusCode() == 200, "server reachable (" + base + (serverVersion != null ? ", Orca " + serverVersion : "") + ")",
            "the server answered " + h.statusCode());
        if (serverVersion != null && !"dev".equals(serverVersion)
            && Executables.compareVersions(serverVersion, Daemon.VERSION) < 0) {
          warn(false, "this daemon (" + Daemon.VERSION + ") is newer than the server (" + serverVersion + ")",
              "upgrade the server first; newer daemon features stay off until then");
        }
        // a plain HTTP check: opening the daemon WebSocket here would displace a running daemon's connection
        HttpResponse<String> who = http.send(HttpRequest.newBuilder(URI.create(base + "/api/daemon/whoami"))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + cfg.token()).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        if (who.statusCode() == 200) {
          check(true, "runtime token accepted (listed as " + Json.str(Json.readMap(who.body()), "name") + ")", null);
        } else if (who.statusCode() == 401) {
          check(false, "runtime token rejected", "the runtime was removed or its token revoked: get a new code under "
              + "Runtimes > Add runtime and run orca-daemon register again");
        } else if (who.statusCode() == 404) {
          warn(false, "runtime token not checked", "the server is older than this daemon; upgrade the server first");
        } else {
          check(false, "runtime token", "the server answered " + who.statusCode());
        }
      } catch (Exception e) {
        check(false, "server " + base, "cannot reach it: " + e.getMessage() + " (proxy, firewall or URL?)");
      }
    }

    out.println();
    boolean usable = false;
    for (ProviderStatus p : new Daemon(cfg, Daemon.defaultProviders(cfg, Providers::trae)).probe()) {
      usable |= p.installed() && p.loggedIn();
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

    check(usable, usable ? "at least one agent CLI is ready" : "no agent CLI is installed and logged in",
        "install Codex CLI or TRAE CLI on this machine and log in (see above)");

    out.println();
    warn(true, "sandbox network for agents: " + (cfg.allowNetwork() ? "allowed (--allow-network)"
        : "blocked (start with --allow-network to let agents that ask for it install packages)"), null);
    warn(true, "full-access tier: " + (cfg.isolated() ? "allowed (--isolated)"
        : "downgraded to workspace (only pass --isolated on a disposable VM or container)"), null);
    try (FileChannel ch = FileChannel.open(settings.resolveSibling("daemon.lock"), StandardOpenOption.CREATE,
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
