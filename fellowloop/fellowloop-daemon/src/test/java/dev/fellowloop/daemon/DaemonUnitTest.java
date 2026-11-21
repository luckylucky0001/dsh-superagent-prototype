package dev.fellowloop.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fellowloop.agent.core.AgentException;
import dev.fellowloop.protocol.AgentEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DaemonUnitTest {

  @Test
  void bufferMergesDeltasAndTracksAcks() {
    EventBuffer b = new EventBuffer();
    b.add(AgentEvent.of(AgentEvent.MESSAGE_DELTA, "itemId", "m1", "text", "Hel"));
    b.add(AgentEvent.of(AgentEvent.MESSAGE_DELTA, "itemId", "m1", "text", "lo"));
    b.add(AgentEvent.of(AgentEvent.TOOL_STARTED, "itemId", "t1"));
    b.add(AgentEvent.of(AgentEvent.MESSAGE_DELTA, "itemId", "m1", "text", "!"));
    List<AgentEvent> out = b.drain();
    assertEquals(3, out.size());
    assertEquals("Hello", out.get(0).data().get("text"));
    assertEquals(List.of(1L, 2L, 3L), out.stream().map(AgentEvent::seq).toList());
    b.ack(2);
    assertEquals(List.of(3L), b.unacked().stream().map(AgentEvent::seq).toList());
    assertTrue(b.drain().isEmpty());
  }

  @Test
  void workspacesStayInsideTheRoot() throws Exception {
    Path root = Files.createTempDirectory("fellowloop-ws");
    Workspaces ws = new Workspaces(root);
    Path p = ws.prepare("Builder/FELLOWLOOP-1", null);
    assertTrue(Files.isDirectory(p) && p.startsWith(root));
    assertThrows(AgentException.class, () -> ws.prepare("../escape", null));
    assertThrows(AgentException.class, () -> ws.prepare("/abs", null));
    assertThrows(AgentException.class, () -> ws.prepare("a/../../b", null));
    Path old = ws.prepare("runs/run_old", null);
    Path fresh = ws.prepare("runs/run_new", null);
    Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 10 * 86_400_000L));
    Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 10 * 86_400_000L));
    assertEquals(1, ws.cleanup(7, java.util.Set.of()));
    assertTrue(!Files.exists(old) && Files.exists(fresh) && Files.exists(p), "issue workspaces are kept");
  }

  @Test
  void repositoryWorkspacesAreClonedOnTheIssueBranch() throws Exception {
    Path src = Files.createTempDirectory("fellowloop-src");
    run(src, "git", "init", "-q", "-b", "main");
    Files.writeString(src.resolve("a.txt"), "x");
    run(src, "git", "add", ".");
    run(src, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init");
    Workspaces ws = new Workspaces(Files.createTempDirectory("fellowloop-ws"));
    Path p = ws.prepare("issues/APP-1", new dev.fellowloop.protocol.Wire.RepoSpec(src.toUri().toString(), "fellowloop/app-1", "main"));
    assertEquals("fellowloop/app-1", dev.fellowloop.agent.core.Executables.run(
        List.of("git", "-C", p.toString(), "branch", "--show-current"), Map.of(), 10).stdout().trim());
    assertTrue(Files.exists(p.resolve("a.txt")));
    Files.createDirectories(p.resolve("__pycache__"));
    Files.writeString(p.resolve("__pycache__/x.pyc"), "x");
    assertEquals("", dev.fellowloop.agent.core.Executables.run(List.of("git", "-C", p.toString(), "status", "--porcelain"),
        Map.of(), 10).stdout().trim(), "caches are excluded locally");
    assertEquals(p, ws.prepare("issues/APP-1", new dev.fellowloop.protocol.Wire.RepoSpec(src.toUri().toString(), "fellowloop/app-1", "main")),
        "a second task reuses the clone");
  }

  private static void run(Path dir, String... cmd) throws Exception {
    Process p = new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO().start();
    assertEquals(0, p.waitFor());
  }

  @Test
  void extraArgumentsCannotWeakenTheSandbox() {
    List<String> args = List.of("-c", "model_reasoning_summary=\"concise\"", "-c", "sandbox_mode=\"danger-full-access\"",
        "--yolo", "--sandbox", "danger-full-access", "-c", "approval_policy=never", "--enable", "web_search", "--add-dir", "/");
    assertEquals(List.of(), ExtraArgs.filter(args, false));
    assertEquals(List.of("-c", "model_reasoning_summary=\"concise\"", "--enable", "web_search"), ExtraArgs.filter(args, true));
    assertEquals(List.of(), ExtraArgs.filter(List.of("-c", "notify=[\"bash\",\"-c\",\"x\"]", "-c",
        "model_providers.x.base_url=\"https://e.vil\""), true), "only known-safe config keys pass");
  }

  @org.junit.jupiter.api.Test
  void fileChangesAreReportedRelativeToTheWorkspace() {
    java.nio.file.Path dir = java.nio.file.Path.of("/work/issues/INV-1");
    dev.fellowloop.protocol.AgentEvent e = dev.fellowloop.protocol.AgentEvent.of(dev.fellowloop.protocol.AgentEvent.TOOL_COMPLETED,
        "kind", "file_change", "title", "/work/issues/INV-1/invoice/totals.py",
        "changes", java.util.List.of(java.util.Map.of("path", "/work/issues/INV-1/invoice/totals.py", "kind", "update"),
            java.util.Map.of("path", "/etc/hosts", "kind", "update")));
    dev.fellowloop.protocol.AgentEvent r = TaskRunner.relativize(e, dir);
    org.junit.jupiter.api.Assertions.assertEquals("invoice/totals.py", r.data().get("title"));
    java.util.List<?> changes = (java.util.List<?>) r.data().get("changes");
    org.junit.jupiter.api.Assertions.assertEquals("invoice/totals.py", ((java.util.Map<?, ?>) changes.get(0)).get("path"));
    org.junit.jupiter.api.Assertions.assertEquals("/etc/hosts", ((java.util.Map<?, ?>) changes.get(1)).get("path"));
  }

  @org.junit.jupiter.api.Test
  void escalatedApprovalAnswersMapToDecisions() {
    var once = new dev.fellowloop.agent.core.Interaction.InputReply(java.util.Map.of("decision", java.util.List.of(TaskRunner.APPROVE_ONCE)), false);
    var task = new dev.fellowloop.agent.core.Interaction.InputReply(java.util.Map.of("decision", java.util.List.of(TaskRunner.APPROVE_TASK)), false);
    var deny = new dev.fellowloop.agent.core.Interaction.InputReply(java.util.Map.of("decision", java.util.List.of(TaskRunner.DENY)), false);
    org.junit.jupiter.api.Assertions.assertEquals(dev.fellowloop.agent.core.Interaction.Decision.ACCEPT, TaskRunner.decisionFrom(once));
    org.junit.jupiter.api.Assertions.assertEquals(dev.fellowloop.agent.core.Interaction.Decision.ACCEPT_FOR_SESSION, TaskRunner.decisionFrom(task));
    org.junit.jupiter.api.Assertions.assertEquals(dev.fellowloop.agent.core.Interaction.Decision.DECLINE, TaskRunner.decisionFrom(deny));
    org.junit.jupiter.api.Assertions.assertEquals(dev.fellowloop.agent.core.Interaction.Decision.DECLINE,
        TaskRunner.decisionFrom(dev.fellowloop.agent.core.Interaction.InputReply.decline()));
    org.junit.jupiter.api.Assertions.assertEquals(dev.fellowloop.agent.core.Interaction.Decision.DECLINE, TaskRunner.decisionFrom(null));
  }

  @org.junit.jupiter.api.Test
  void existingCheckoutsMustComeFromTheSameRepository() {
    org.junit.jupiter.api.Assertions.assertTrue(Workspaces.sameRepo("git@host:a/b.git", "git@host:a/b"));
    org.junit.jupiter.api.Assertions.assertTrue(Workspaces.sameRepo("/srv/git/wms/", "/srv/git/wms"));
    org.junit.jupiter.api.Assertions.assertFalse(Workspaces.sameRepo("git@host:a/b.git", "git@host:a/c.git"));
    org.junit.jupiter.api.Assertions.assertTrue(Workspaces.sameRepo("https://ci:old-token@git.example.com/wms/core.git",
        "https://ci:new-token@git.example.com/wms/core"), "a rotated token is the same repository");
    org.junit.jupiter.api.Assertions.assertTrue(Workspaces.sameRepo("git@git.example.com:wms/core.git",
        "https://git.example.com/wms/core"), "ssh and https of one repository");
    org.junit.jupiter.api.Assertions.assertTrue(Workspaces.sameRepo("ssh://git@git.example.com/wms/core.git",
        "git@git.example.com:wms/core"));
    org.junit.jupiter.api.Assertions.assertFalse(Workspaces.sameRepo("ssh://git@git.example.com:2222/wms/core.git",
        "git@git.example.com:wms/core"), "port 2222 is not the default ssh port");
    org.junit.jupiter.api.Assertions.assertFalse(Workspaces.sameRepo("https://git.example.com/wms/core",
        "https://git.example.com/wms/other"));
    org.junit.jupiter.api.Assertions.assertTrue(Workspaces.sameRepo("https://GIT.Example.com:443/wms/core",
        "ssh://git@git.example.com:22/wms/core"), "default ports and the host's case do not matter");
    org.junit.jupiter.api.Assertions.assertFalse(Workspaces.sameRepo("https://git.example.com:8443/wms/core",
        "https://git.example.com/wms/core"), "another port can be another server");
    org.junit.jupiter.api.Assertions.assertFalse(Workspaces.sameRepo("/srv/git/Repo", "/srv/git/repo"),
        "paths keep their case");
    org.junit.jupiter.api.Assertions.assertTrue(Workspaces.sameRepo("https://github.com/Acme/WMS.git",
        "git@github.com:acme/wms"), "GitHub ignores case in owner and repository names");
    org.junit.jupiter.api.Assertions.assertEquals("https://***@h/r", Workspaces.Redacted.url("https://u:t@h/r"));
  }

  @Test
  void doctorExplainsWhatIsMissingAndFails(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    var out = new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8);
    DaemonConfig cfg = new DaemonConfig("http://127.0.0.1:1", "fellowloop_r_x", "t", 1, dir.resolve("work").toString(),
        List.of(dir.resolve("no-codex").toString()), List.of(dir.resolve("no-trae").toString()), Map.of(), false, null, false);
    assertEquals(1, new Doctor(cfg, out, dir.resolve("daemon.json")).run());
    String text = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(text.contains("is writable"), text);
    assertTrue(text.contains("cannot reach it"), text);
    assertTrue(text.contains("Codex CLI: not installed"), text);
    assertTrue(text.contains("problem(s) to fix"), text);
  }

  @Test
  void doctorFindsSavedRulesThatSkipTheSandbox(@org.junit.jupiter.api.io.TempDir Path home) throws Exception {
    Path work = home.resolve(".fellowloop/work");
    Path rules = Files.createDirectories(home.resolve(".trae/cli/rules"));
    Files.writeString(rules.resolve("default.rules"), "prefix_rule(pattern=[\"cd\", \"" + work.resolve("issues/WMS-5")
        + "\"], decision=\"allow\")\nprefix_rule(pattern=[\"npm\", \"test\"], decision=\"allow\")\n");
    assertEquals(1, Doctor.savedRules(home, work));
    assertEquals(0, Doctor.savedRules(home.resolve("nobody"), work));
  }

  @Test
  void toolTitlesShowWorkspacePathsRelative() {
    Path dir = Path.of("/work/issues/WMS-5");
    AgentEvent e = AgentEvent.of(AgentEvent.TOOL_STARTED, "title", "Search for '**/*.py' in /work/issues/WMS-5",
        "command", "cd /work/issues/WMS-5 && python3 -m unittest /work/issues/WMS-5/tests/test_x.py");
    AgentEvent r = TaskRunner.relativize(e, dir);
    assertEquals("Search for '**/*.py' in .", r.data().get("title"));
    assertEquals("cd . && python3 -m unittest tests/test_x.py", r.data().get("command"));
    AgentEvent msg = AgentEvent.of(AgentEvent.MESSAGE, "text", "- [P2] Fix it — /work/issues/WMS-5/wms/counting.py:72-76");
    assertEquals("- [P2] Fix it — wms/counting.py:72-76", TaskRunner.relativize(msg, dir).data().get("text"));
    AgentEvent other = AgentEvent.of(AgentEvent.TOOL_STARTED, "title", "Read /work/issues/WMS-50/a.py");
    assertEquals("Read /work/issues/WMS-50/a.py", TaskRunner.relativize(other, dir).data().get("title"),
        "a sibling folder with a longer name is not touched");
  }

  @Test
  void autoCommitMessagesSayWhatChanged() {
    String m = TaskRunner.autoCommitMessage("WMS-5", " M wms/model.py\n?? wms/counting.py\n?? tests/test_counting.py\n",
        "All 16 tests pass. Here's what was changed:\n- counting", "TraeBuilder", "tsk_1");
    assertEquals("WMS-5: update wms/model.py, wms/counting.py and 1 more", m.lines().findFirst().orElseThrow());
    assertTrue(m.contains("All 16 tests pass") && m.endsWith("Committed by Fellowloop for TraeBuilder (task tsk_1)"), m);
  }
}
