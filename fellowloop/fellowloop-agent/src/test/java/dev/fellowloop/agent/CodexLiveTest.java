package dev.fellowloop.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fellowloop.agent.codex.CodexConfig;
import dev.fellowloop.agent.codex.CodexProvider;
import dev.fellowloop.agent.core.AgentSession;
import dev.fellowloop.agent.core.Executables;
import dev.fellowloop.agent.core.Interaction;
import dev.fellowloop.agent.core.Permission;
import dev.fellowloop.agent.core.SessionSpec;
import dev.fellowloop.agent.core.TurnResult;
import dev.fellowloop.agent.core.TurnSpec;
import dev.fellowloop.protocol.AgentEvent;
import dev.fellowloop.protocol.ProviderStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Talks to a real, logged-in Codex CLI. Run with
 * {@code FELLOWLOOP_CODEX_BIN=/path/to/codex mvn test -pl fellowloop-agent -Dgroups=live -Dfellowloop.excludedGroups=none}.
 * Uses the cheapest model by default ({@code FELLOWLOOP_TEST_CODEX_MODEL}, default gpt-6-luna).
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FELLOWLOOP_CODEX_BIN", matches = ".+")
class CodexLiveTest {

  private static final String MODEL = System.getenv().getOrDefault("FELLOWLOOP_TEST_CODEX_MODEL", "gpt-6-luna");

  private static CodexProvider provider() {
    String record = System.getenv("FELLOWLOOP_RECORD_DIR");
    return new CodexProvider(new CodexConfig(List.of(System.getenv("FELLOWLOOP_CODEX_BIN")), Map.of(), "fellowloop_test", "0.1.0",
        Duration.ofSeconds(90), record == null ? null : Path.of(record)));
  }

  private static final Interaction.Handler ACCEPT_ALL = new Interaction.Handler() {
    public Interaction.Decision approve(Interaction.ApprovalRequest r) {
      return Interaction.Decision.ACCEPT;
    }

    public Interaction.InputReply input(Interaction.InputRequest r) {
      return Interaction.InputReply.decline();
    }
  };

  @Test
  void probeReportsLoginAndModels() {
    ProviderStatus s = provider().probe();
    System.out.println("probe: version=" + s.version() + " loggedIn=" + s.loggedIn() + " auth=" + s.authMode()
        + " plan=" + s.plan() + " models=" + s.models().stream().map(ProviderStatus.ModelInfo::id).toList());
    assertTrue(s.installed());
    assertTrue(s.loggedIn(), String.valueOf(s.error()));
    assertTrue(s.models().stream().anyMatch(m -> m.id().equals(MODEL)));
  }

  @Test
  void editsFilesReturnsStructuredOutputAndResumes() throws Exception {
    Path dir = Files.createTempDirectory("fellowloop-live");
    List<AgentEvent> events = new CopyOnWriteArrayList<>();
    SessionSpec spec = new SessionSpec(dir, MODEL, "low",
        "You are an automated coding agent working for the Fellowloop platform. Do the task directly; keep replies short.",
        Permission.WORKSPACE, null, null, null, null, null, null);
    String ref;
    try (AgentSession s = provider().open(spec, ACCEPT_ALL)) {
      ref = s.sessionRef();
      assertNotNull(ref);
      TurnResult r1 = s.runTurn(TurnSpec.text("Create a file named hello.txt containing exactly the word hi. Then reply with DONE."), events::add);
      System.out.println("turn1: " + r1.status() + " text=" + r1.finalText() + " usage=" + r1.usage());
      assertEquals(TurnResult.Status.COMPLETED, r1.status(), r1.error());
      assertEquals("hi", Files.readString(dir.resolve("hello.txt")).trim());
      assertTrue(events.stream().anyMatch(e -> e.type().equals(AgentEvent.TOOL_COMPLETED)), "expected a tool event");
      assertTrue(events.stream().anyMatch(e -> e.type().equals(AgentEvent.USAGE)));

      TurnResult r2 = s.runTurn(new TurnSpec("List the files in the current directory.", List.of(),
          Map.of("type", "object", "properties", Map.of("files", Map.of("type", "array", "items", Map.of("type", "string"))),
              "required", List.of("files"), "additionalProperties", false), Duration.ofMinutes(5)), events::add);
      System.out.println("turn2: " + r2.status() + " structured=" + r2.structured());
      assertEquals(TurnResult.Status.COMPLETED, r2.status(), r2.error());
      assertTrue(String.valueOf(r2.structured()).contains("hello.txt"));
    }
    try (AgentSession s = provider().open(spec.withResume(ref), ACCEPT_ALL)) {
      assertTrue(s.resumed());
      assertEquals(ref, s.sessionRef());
      TurnResult r3 = s.runTurn(TurnSpec.text("What is the name of the file you created earlier? Reply with the file name only."), events::add);
      System.out.println("turn3: " + r3.finalText() + " usage=" + r3.usage());
      assertTrue(r3.finalText().contains("hello.txt"));
      // codex replays the thread's totals on resume: this task counts only its own turn
      long task = ((Number) r3.usage().get("taskTokens")).longValue();
      long total = ((Number) r3.usage().get("totalTokens")).longValue();
      assertTrue(task > 0 && task < total, "taskTokens " + task + " must exclude the earlier turns (total " + total + ")");
    }
  }

  @Test
  void interruptStopsARunningTurn() throws Exception {
    Path dir = Files.createTempDirectory("fellowloop-live-int");
    List<AgentEvent> events = new CopyOnWriteArrayList<>();
    try (AgentSession s = provider().open(new SessionSpec(dir, MODEL, "low", null, Permission.WORKSPACE, null, null, null,
        null, null, null), ACCEPT_ALL)) {
      Thread killer = Thread.ofVirtual().start(() -> {
        try {
          for (int i = 0; i < 600 && events.stream().noneMatch(e -> e.type().equals(AgentEvent.TOOL_STARTED)); i++) {
            Thread.sleep(100);
          }
          s.interrupt();
        } catch (InterruptedException ignored) {
          // test ends
        }
      });
      TurnResult r = s.runTurn(TurnSpec.text("Run the shell command `sleep 120` and then reply OK."), events::add);
      killer.join();
      System.out.println("interrupt: " + r.status() + " " + r.errorCode());
      assertEquals(TurnResult.Status.INTERRUPTED, r.status());
      assertFalse(events.isEmpty());
    }
  }

  @Test
  void cancellingANativeReviewStopsIt() throws Exception {
    // codex runs a review as a sub-turn with its own id; interrupting the review/start id was ignored before
    Path dir = Files.createTempDirectory("fellowloop-live-review");
    Executables.run(List.of("git", "init", "-q", dir.toString()), Map.of(), 30);
    Files.writeString(dir.resolve("m.py"), "def add(a, b):\n    return a + b\n");
    Executables.run(List.of("git", "-C", dir.toString(), "add", "-A"), Map.of(), 30);
    Executables.run(List.of("git", "-C", dir.toString(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init"),
        Map.of(), 30);
    Files.writeString(dir.resolve("m.py"), "def add(a, b):\n    return a - b\n");
    List<AgentEvent> events = new CopyOnWriteArrayList<>();
    try (AgentSession s = provider().open(new SessionSpec(dir, MODEL, "low", null, Permission.READ_ONLY, null, null, null,
        null, null, null), ACCEPT_ALL)) {
      Thread killer = Thread.ofVirtual().start(() -> {
        try {
          for (int i = 0; i < 600 && events.stream().noneMatch(e -> e.type().equals(AgentEvent.TURN_STARTED)); i++) {
            Thread.sleep(100);
          }
          Thread.sleep(1500);
          s.interrupt();
        } catch (InterruptedException ignored) {
          // test ends
        }
      });
      TurnResult r = s.runTurn(new TurnSpec("", List.of(), null, Duration.ofMinutes(5), Map.of("type", "uncommittedChanges")),
          events::add);
      killer.join();
      System.out.println("review interrupt: " + r.status() + " " + r.errorCode());
      assertEquals(TurnResult.Status.INTERRUPTED, r.status());
    }
  }

  @Test
  void steerReachesTheRunningTurn() throws Exception {
    Path dir = Files.createTempDirectory("fellowloop-live-steer");
    List<AgentEvent> events = new CopyOnWriteArrayList<>();
    try (AgentSession s = provider().open(new SessionSpec(dir, MODEL, "low", null, Permission.WORKSPACE, null, null, null,
        null, null, null), ACCEPT_ALL)) {
      Thread steerer = Thread.ofVirtual().start(() -> {
        try {
          for (int i = 0; i < 600 && events.stream().noneMatch(e -> e.type().equals(AgentEvent.TOOL_STARTED)); i++) {
            Thread.sleep(100);
          }
          s.steer("Change of plan: do not create any file. Reply with exactly the word STEERED.");
        } catch (Exception e) {
          System.out.println("steer failed: " + e);
        }
      });
      TurnResult r = s.runTurn(TurnSpec.text("Run `sleep 8`, then create notes.txt containing hello, then reply DONE."), events::add);
      steerer.join();
      System.out.println("steer: " + r.status() + " text=" + r.finalText() + " notes=" + Files.exists(dir.resolve("notes.txt")));
      assertEquals(TurnResult.Status.COMPLETED, r.status());
      assertTrue(r.finalText().contains("STEERED"), r.finalText());
    }
  }

  @Test
  void escalationsReachTheApprovalHandler() throws Exception {
    Path dir = Files.createTempDirectory("fellowloop-live-approve");
    List<Interaction.ApprovalRequest> seen = new CopyOnWriteArrayList<>();
    Interaction.Handler declineAll = new Interaction.Handler() {
      public Interaction.Decision approve(Interaction.ApprovalRequest r) {
        seen.add(r);
        return Interaction.Decision.DECLINE;
      }

      public Interaction.InputReply input(Interaction.InputRequest r) {
        return Interaction.InputReply.decline();
      }
    };
    try (AgentSession s = provider().open(new SessionSpec(dir, MODEL, "low", null, Permission.WORKSPACE, null, null, null,
        null, null, null), declineAll)) {
      TurnResult r = s.runTurn(TurnSpec.text("Create the file " + System.getProperty("user.home") + "/fellowloop-outside-" + System.nanoTime()
          + ".txt (outside the workspace) containing hi. If it is blocked, request approval to run the command "
          + "outside the sandbox. Then reply DONE or BLOCKED."), e -> {});
      System.out.println("approvals: " + seen.stream().map(a -> a.kind() + ":" + a.command()).toList() + " -> " + r.finalText());
      assertEquals(TurnResult.Status.COMPLETED, r.status());
      assertFalse(seen.isEmpty(), "expected an escalation request");
    }
  }
}
