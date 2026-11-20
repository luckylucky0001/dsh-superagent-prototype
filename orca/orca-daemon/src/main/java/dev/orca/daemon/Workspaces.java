package dev.orca.daemon;

import dev.orca.agent.core.AgentException;
import dev.orca.agent.core.Executables;
import dev.orca.protocol.ErrorCodes;
import dev.orca.protocol.Wire;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One directory per workspace key (usually agent + issue), so a resumed session finds the same cwd.
 * With a repository, the directory is a clone on a task branch.
 */
public final class Workspaces {

  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*");

  private final Path root;

  public Workspaces(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  static boolean sameRepo(String a, String b) {
    return repoKey(a).equals(repoKey(b));
  }

  /**
   * A repository's identity regardless of how it is written: credentials, scheme and port, {@code .git}
   * and trailing slashes do not matter, and {@code git@host:org/repo} equals {@code https://host/org/repo}.
   * Rotating a token in an https URL or moving to SSH therefore keeps existing checkouts usable.
   */
  static String repoKey(String url) {
    String u = url.trim();
    u = u.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");   // scheme
    u = u.replaceFirst("^[^/@]*@", "");                        // user info
    u = u.replaceFirst("^([^/:]+):\\d+/", "$1/");              // port
    if (!u.startsWith("/") && u.matches("^[^/:]+:[^/].*")) {   // scp-like host:path
      u = u.replaceFirst(":", "/");
    }
    return u.replaceAll("(\\.git)?/*$", "").toLowerCase(java.util.Locale.ROOT);
  }

  public Path prepare(String key, Wire.RepoSpec repo) throws AgentException {
    if (key == null || !KEY.matcher(key).matches() || key.contains("..")) {
      throw new AgentException(ErrorCodes.PROTOCOL, "invalid workspace key: " + key);
    }
    Path dir = root.resolve(key).normalize();
    if (!dir.startsWith(root)) {
      throw new AgentException(ErrorCodes.PROTOCOL, "workspace escapes the work root: " + key);
    }
    try {
      if (repo == null || repo.url() == null || repo.url().isBlank()) {
        Files.createDirectories(dir);
        return touched(dir);
      }
      if (!Files.isDirectory(dir.resolve(".git"))) {
        if (Files.isDirectory(dir)) {
          try (var entries = Files.list(dir)) {
            if (entries.findAny().isPresent()) {
              throw new AgentException(ErrorCodes.AGENT_ERROR, "workspace " + key + " already holds files that are not a "
                  + "checkout of " + repo.url());
            }
          }
        }
        Files.createDirectories(dir.getParent());
        git(dir.getParent(), "clone", "--quiet", repo.url(), dir.getFileName().toString());
        String base = repo.baseBranch();
        if (base != null && !base.isBlank()) {
          git(dir, "checkout", "--quiet", base);
        }
        if (repo.branch() != null && !repo.branch().isBlank()) {
          git(dir, "checkout", "--quiet", "-B", repo.branch());
        }
        excludeCaches(dir);
      } else {
        Executables.Output origin = Executables.run(List.of("git", "-C", dir.toString(), "remote", "get-url", "origin"),
            Map.of(), 30);
        if (origin.exitCode() != 0 || !sameRepo(origin.stdout().trim(), repo.url())) {
          throw new AgentException(ErrorCodes.AGENT_ERROR, "workspace " + key + " is a checkout of another repository ("
              + Redacted.url(origin.stdout().trim()) + ", not " + Redacted.url(repo.url()) + "); remove the folder on this"
              + " machine to start the issue afresh");
        }
        if (!origin.stdout().trim().equals(repo.url())) {
          // same repository written differently (new token, https to ssh): follow the project's setting
          Executables.run(List.of("git", "-C", dir.toString(), "remote", "set-url", "origin", repo.url()), Map.of(), 30);
        }
        // keep local work; just refresh remote refs so the agent can rebase if it wants to
        Executables.run(List.of("git", "-C", dir.toString(), "fetch", "--quiet", "--all"), Map.of(), 120);
      }
      return touched(dir);
    } catch (IOException e) {
      throw new AgentException(ErrorCodes.AGENT_ERROR, "could not prepare workspace: " + e.getMessage(), e);
    }
  }

  /** Marks a workspace as used now, so cleanup (which looks at the folder's time) keeps it for another week. */
  private static Path touched(Path dir) throws IOException {
    Files.setLastModifiedTime(dir, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
    return dir;
  }

  /**
   * Removes one-off workspaces ({@code runs/*}, {@code adhoc/*}) untouched for {@code maxAgeDays}. Issue
   * workspaces stay: a later comment resumes work in them.
   */
  public int cleanup(int maxAgeDays, java.util.Set<Path> inUse) {
    long cutoff = System.currentTimeMillis() - maxAgeDays * 86_400_000L;
    int removed = 0;
    for (String group : java.util.List.of("runs", "adhoc")) {
      Path dir = root.resolve(group);
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (var children = Files.list(dir)) {
        for (Path p : children.toList()) {
          if (inUse.contains(p.toAbsolutePath().normalize()) || Files.getLastModifiedTime(p).toMillis() > cutoff) {
            continue;
          }
          try (var walk = Files.walk(p)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
          }
          removed++;
        }
      } catch (IOException ignored) {
        // best effort
      }
    }
    return removed;
  }

  Path root() {
    return root;
  }

  /** Local-only ignores so build caches never end up in commits; the repository itself is untouched. */
  static void excludeCaches(Path dir) throws IOException {
    Path exclude = dir.resolve(".git/info/exclude");
    Files.createDirectories(exclude.getParent());
    String existing = Files.exists(exclude) ? Files.readString(exclude) : "";
    if (!existing.contains("# orca")) {
      Files.writeString(exclude, existing + "\n# orca: build and tool caches\n.orca/\n__pycache__/\n*.pyc\n.pytest_cache/\n"
          + "node_modules/\n.venv/\n.mypy_cache/\n.ruff_cache/\n.gradle/\n.DS_Store\n");
    }
  }

  private static void git(Path cwd, String... args) throws AgentException {
    List<String> cmd = new java.util.ArrayList<>();
    cmd.add("git");
    cmd.add("-C");
    cmd.add(cwd.toString());
    cmd.addAll(List.of(args));
    Executables.Output out = Executables.run(cmd, Map.of("GIT_TERMINAL_PROMPT", "0"), 600);
    if (out.exitCode() != 0) {
      throw new AgentException(ErrorCodes.AGENT_ERROR, "git " + args[0] + " failed: " + Executables.plain(out.stderr()));
    }
  }

  /** Repository URLs in messages, without credentials. */
  static final class Redacted {
    private Redacted() {}

    static String url(String u) {
      return u == null ? null : u.replaceFirst("(://)[^/@]+@", "$1***@");
    }
  }
}
