package dev.orca.daemon;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * git invocations made by the daemon itself, outside any sandbox, in a folder an agent has worked in.
 * The agent cannot change the checkout's own configuration (the sandboxes keep {@code .git} read-only),
 * but it can leave nested repositories or attributes behind; these settings keep git from running
 * hooks, a file-system monitor or external diff programs on the daemon's behalf.
 */
final class SafeGit {

  private SafeGit() {}

  static final List<String> FLAGS = List.of("-c", "core.fsmonitor=false", "-c", "core.hooksPath=/dev/null",
      "-c", "core.untrackedCache=false", "-c", "diff.external=", "-c", "submodule.recurse=false",
      "-c", "protocol.file.allow=user");

  /** {@code git -C dir <safe flags> args…} */
  static List<String> cmd(Path dir, String... args) {
    List<String> cmd = new ArrayList<>();
    cmd.add("git");
    cmd.add("-C");
    cmd.add(dir.toString());
    cmd.addAll(FLAGS);
    cmd.addAll(List.of(args));
    return cmd;
  }
}
