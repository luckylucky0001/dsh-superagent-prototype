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

  @Test
  void doesNotUnwrapWhatTheOuterShellWouldExpandOrUntrustedShells() {
    // an unquoted glob after the script would be expanded by the outer shell
    assertEquals("bash -c 'git log '*", Executables.unwrapShell("bash -c 'git log '*"));
    // only system shells: an agent-written ./bash is not a shell wrapper
    assertEquals("./bash -lc 'git status'", Executables.unwrapShell("./bash -lc 'git status'"));
    assertEquals("/tmp/x/sh -c 'npm test'", Executables.unwrapShell("/tmp/x/sh -c 'npm test'"));
    assertEquals("git status", Executables.unwrapShell("/usr/bin/bash -lc 'git status'"));
  }

  @Test
  void detectsUnquotedExpansion() {
    org.junit.jupiter.api.Assertions.assertTrue(Executables.hasUnquotedExpansion("git log *"));
    org.junit.jupiter.api.Assertions.assertTrue(Executables.hasUnquotedExpansion("cat ~/.ssh/id_rsa"));
    org.junit.jupiter.api.Assertions.assertTrue(Executables.hasUnquotedExpansion("git add {a,b}"));
    org.junit.jupiter.api.Assertions.assertFalse(Executables.hasUnquotedExpansion("git commit -m 'Is it *done*?'"));
    org.junit.jupiter.api.Assertions.assertFalse(Executables.hasUnquotedExpansion("git status --short"));
  }
}
