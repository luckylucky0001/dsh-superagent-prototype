package dev.orca.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.orca.agent.core.Executables;
import org.junit.jupiter.api.Test;

class ExecutablesTest {

  @Test
  void unwrapsShellWrappersWithPosixQuoting() {
    assertEquals("npm test", Executables.unwrapShell("/bin/bash -lc 'npm test'"));
    assertEquals("git status", Executables.unwrapShell("bash -c \"git status\""));
    // mixed quotes are concatenated the way the shell does it
    assertEquals("echo \"a\" $HOME", Executables.unwrapShell("/bin/bash -lc \"echo \\\"a\\\" \"'$HOME'"));
    assertEquals("printf 'x'", Executables.unwrapShell("/usr/bin/zsh -lc \"printf 'x'\""));
  }

  @Test
  void leavesOtherCommandsAlone() {
    assertEquals("ls -la", Executables.unwrapShell("  ls -la "));
    assertEquals("python -c 'print(1)'", Executables.unwrapShell("python -c 'print(1)'"));
    assertEquals("bash -lc 'a' extra", Executables.unwrapShell("bash -lc 'a' extra"));
    assertEquals("bash -lc 'unbalanced", Executables.unwrapShell("bash -lc 'unbalanced"));
  }
}
