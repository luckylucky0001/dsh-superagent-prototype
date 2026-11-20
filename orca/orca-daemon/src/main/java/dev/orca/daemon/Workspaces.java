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
        return dir;
      }
      if (!Files.isDirectory(dir.resolve(".git"))) {
        Files.createDirectories(dir.getParent());
        git(dir.getParent(), "clone", "--quiet", repo.url(), dir.getFileName().toString());
        String base = repo.baseBranch();
        if (base != null && !base.isBlank()) {
          git(dir, "checkout", "--quiet", base);
        }
        if (repo.branch() != null && !repo.branch().isBlank()) {
          git(dir, "checkout", "--quiet", "-B", repo.branch());
        }
      } else {
        // keep local work; just refresh remote refs so the agent can rebase if it wants to
        Executables.run(List.of("git", "-C", dir.toString(), "fetch", "--quiet", "--all"), Map.of(), 120);
      }
      return dir;
    } catch (IOException e) {
      throw new AgentException(ErrorCodes.AGENT_ERROR, "could not prepare workspace: " + e.getMessage(), e);
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
}
