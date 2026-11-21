package dev.fellowloop.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fellowloop.agent.codex.CodexConfig;
import dev.fellowloop.agent.codex.CodexProvider;
import dev.fellowloop.agent.core.AgentException;
import dev.fellowloop.agent.core.AgentSession;
import dev.fellowloop.agent.core.Interaction;
import dev.fellowloop.agent.core.SessionSpec;
import dev.fellowloop.agent.core.TurnResult;
import dev.fellowloop.agent.core.TurnSpec;
import dev.fellowloop.agent.fake.FakeCodexAppServer;
import dev.fellowloop.protocol.AgentEvent;
import dev.fellowloop.protocol.ErrorCodes;
import dev.fellowloop.protocol.ProviderStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Exercises the Codex driver against {@link FakeCodexAppServer}; no network, no account. */
class CodexDriverTest {

  private static CodexProvider provider(String scenario) {
    return new CodexProvider(new CodexConfig(FakeCodexAppServer.command(), Map.of("FAKE_SCENARIO", scenario),
        "fellowloop_test", "0", Duration.ofSeconds(20), null));
  }

  static final class RecordingHandler implements Interaction.Handler {
    final AtomicReference<Interaction.ApprovalRequest> approval = new AtomicReference<>();
    final AtomicReference<Interaction.InputRequest> input = new AtomicReference<>();
    Interaction.Decision decision = Interaction.Decision.DECLINE;

    public Interaction.Decision approve(Interaction.ApprovalRequest r) {
      approval.set(r);
      return decision;
    }

    public Interaction.InputReply input(Interaction.InputRequest r) {
      input.set(r);
      return new Interaction.InputReply(Map.of("db", List.of("postgres")), false);
    }
  }

  private static SessionSpec spec() throws Exception {
    return SessionSpec.simple(Files.createTempDirectory("fellowloop-codex"), "gpt-6-luna");
  }

  @Test
  void probeReadsVersionAccountAndModels() {
    ProviderStatus s = provider("echo").probe();
    assertTrue(s.healthy(), String.valueOf(s.error()));
    assertEquals("0.171.0", s.version());
    assertEquals("chatgpt", s.authMode());
    assertEquals("plus", s.plan());
    assertEquals(List.of("gpt-6-luna", "gpt-6.2-sol"), s.models().stream().map(ProviderStatus.ModelInfo::id).toList());
  }

  @Test
  void mapsEventsAndParsesStructuredOutput() throws Exception {
    List<AgentEvent> events = new CopyOnWriteArrayList<>();
    try (AgentSession s = provider("echo").open(spec(), new RecordingHandler())) {
      assertEquals("thr_fake_1", s.sessionRef());
      assertEquals("gpt-6-luna", s.model());
      TurnResult r = s.runTurn(TurnSpec.text("hello"), events::add);
      assertEquals(TurnResult.Status.COMPLETED, r.status());
      assertEquals("ECHO: hello", r.finalText());
      assertEquals(120L, ((Number) r.usage().get("totalTokens")).longValue());

      List<String> types = events.stream().map(AgentEvent::type).toList();
      assertTrue(types.containsAll(List.of(AgentEvent.TURN_STARTED, AgentEvent.PLAN, AgentEvent.TOOL_STARTED,
          AgentEvent.TOOL_COMPLETED, AgentEvent.MESSAGE_DELTA, AgentEvent.MESSAGE, AgentEvent.USAGE, AgentEvent.TURN_COMPLETED)), types.toString());
      AgentEvent change = events.stream().filter(e -> e.type().equals(AgentEvent.TOOL_COMPLETED)).findFirst().orElseThrow();
      assertEquals("file_change", change.data().get("kind"));
      assertEquals("a.txt", change.data().get("title"));

      TurnResult r2 = s.runTurn(new TurnSpec("x", List.of(), Map.of("type", "object"), null), events::add);
      assertEquals(Map.of("ok", true, "echo", "x"), r2.structured(), "fenced JSON is accepted");
    }
  }

  @Test
  void approvalsGoThroughTheHandler() throws Exception {
    for (Interaction.Decision d : List.of(Interaction.Decision.ACCEPT, Interaction.Decision.DECLINE)) {
      RecordingHandler h = new RecordingHandler();
      h.decision = d;
      List<AgentEvent> events = new CopyOnWriteArrayList<>();
      try (AgentSession s = provider("approval").open(spec(), h)) {
        TurnResult r = s.runTurn(TurnSpec.text("clean"), events::add);
        assertEquals(Interaction.ApprovalKind.COMMAND, h.approval.get().kind());
        assertEquals("rm -rf build", h.approval.get().command());
        assertEquals("decision=" + (d == Interaction.Decision.ACCEPT ? "accept" : "decline"), r.finalText());
        AgentEvent done = events.stream().filter(e -> e.type().equals(AgentEvent.TOOL_COMPLETED)).findFirst().orElseThrow();
        assertEquals(d == Interaction.Decision.ACCEPT ? "completed" : "declined", done.data().get("status"));
        assertEquals("rm -rf build", done.data().get("title"), "the shell wrapper is stripped from the title");
        assertTrue(events.stream().anyMatch(e -> e.type().equals(AgentEvent.APPROVAL_RESOLVED)));
      }
    }
  }

  @Test
  void userInputIsForwarded() throws Exception {
    RecordingHandler h = new RecordingHandler();
    try (AgentSession s = provider("question").open(spec(), h)) {
      TurnResult r = s.runTurn(TurnSpec.text("ask me"), e -> {});
      assertEquals("Database", h.input.get().title());
      assertEquals("answer=[postgres]", r.finalText());
    }
  }

  @Test
  void failuresCarryErrorCodes() throws Exception {
    try (AgentSession s = provider("fail_auth").open(spec(), new RecordingHandler())) {
      TurnResult r = s.runTurn(TurnSpec.text("x"), e -> {});
      assertEquals(TurnResult.Status.FAILED, r.status());
      assertEquals(ErrorCodes.AUTH_REQUIRED, r.errorCode());
    }
    AgentException e = assertThrows(AgentException.class,
        () -> provider("echo").open(spec().withResume("missing"), new RecordingHandler()));
    assertEquals(ErrorCodes.SESSION_NOT_FOUND, e.code());
  }

  @Test
  void steerAndInterruptReachTheRunningTurn() throws Exception {
    try (AgentSession s = provider("hang").open(spec(), new RecordingHandler())) {
      List<AgentEvent> events = new CopyOnWriteArrayList<>();
      Thread.ofVirtual().start(() -> {
        try {
          waitFor(events, AgentEvent.TOOL_STARTED);
          s.steer("focus on tests");
        } catch (Exception ignored) {
          // asserted below
        }
      });
      TurnResult r = s.runTurn(TurnSpec.text("work"), events::add);
      assertEquals("steered: focus on tests", r.finalText());

      List<AgentEvent> events2 = new CopyOnWriteArrayList<>();
      Thread.ofVirtual().start(() -> {
        waitFor(events2, AgentEvent.TOOL_STARTED);
        s.interrupt();
      });
      TurnResult r2 = s.runTurn(TurnSpec.text("work"), events2::add);
      assertEquals(TurnResult.Status.INTERRUPTED, r2.status());
    }
  }

  private static void waitFor(List<AgentEvent> events, String type) {
    for (int i = 0; i < 200 && events.stream().noneMatch(e -> e.type().equals(type)); i++) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  @Test
  void startupFailureIsReported() {
    CodexProvider broken = new CodexProvider(CodexConfig.defaults().withCommand(List.of(Path.of("/nonexistent/codex").toString())));
    assertThrows(AgentException.class, () -> broken.open(spec(), new RecordingHandler()));
    assertTrue(!broken.probe().installed());
  }

  @Test
  void nativeReviewUsesReviewStartAndReturnsTheReview() throws Exception {
    try (AgentSession s = provider("echo").open(spec(), new RecordingHandler())) {
      List<dev.fellowloop.protocol.AgentEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
      TurnResult r = s.runTurn(new TurnSpec("", List.of(), null, null,
          Map.of("type", "baseBranch", "branch", "main")), events::add);
      assertEquals(TurnResult.Status.COMPLETED, r.status());
      assertEquals("reviewed changes against main: no issues found", r.finalText());
      TurnResult placeholder = s.runTurn(new TurnSpec(TurnSpec.DEFAULT_REVIEW_PROMPT, List.of(), null, null,
          Map.of("type", "baseBranch", "branch", "main")), events::add);
      assertEquals("reviewed changes against main: no issues found", placeholder.finalText(),
          "the server's stand-in prompt keeps codex's own diff of the branch");
      // only a custom target carries text: a review with a prompt names the same target in words
      TurnResult withText = s.runTurn(new TurnSpec("focus on rounding", List.of(), null, null,
          Map.of("type", "baseBranch", "branch", "main", "instructions", "check the tests")), events::add);
      assertEquals("reviewed custom: no issues found", withText.finalText());
      assertTrue(events.stream().anyMatch(e -> e.type().equals(dev.fellowloop.protocol.AgentEvent.NOTICE)
          && String.valueOf(e.data().get("message")).contains("review started")));
    }
  }

  @Test
  void cancellingAReviewInterruptsTheRunningSubTurn() throws Exception {
    try (AgentSession s = provider("review_hang").open(spec(), new RecordingHandler())) {
      Thread.ofVirtual().start(() -> {
        try {
          Thread.sleep(500);
        } catch (InterruptedException ignored) {
          // test thread
        }
        s.interrupt();
      });
      TurnResult r = s.runTurn(new TurnSpec("", List.of(), null, Duration.ofSeconds(20),
          Map.of("type", "uncommittedChanges")), e -> {});
      assertEquals(TurnResult.Status.INTERRUPTED, r.status(), "codex only accepts the sub-turn's id");
    }
  }

  @Test
  void aCancelWhileTheTurnIsStartingIsNotLost() throws Exception {
    try (AgentSession s = provider("slow_start").open(spec(), new RecordingHandler())) {
      Thread.ofVirtual().start(() -> {
        try {
          Thread.sleep(300);
        } catch (InterruptedException ignored) {
          // test thread
        }
        s.interrupt();
      });
      TurnResult r = s.runTurn(TurnSpec.text("do it"), e -> {});
      assertEquals(TurnResult.Status.INTERRUPTED, r.status());
      assertEquals(dev.fellowloop.protocol.ErrorCodes.CANCELLED, r.errorCode());
    }
  }

  @Test
  void aResumedThreadCountsOnlyThisTasksTokens() throws Exception {
    var resumed = new dev.fellowloop.agent.core.SessionSpec(java.nio.file.Files.createTempDirectory("fellowloop-res"), null, null,
        null, null, "thr_fake_1", null, null, null, null, null, false);
    try (AgentSession s = provider("echo").open(resumed, new RecordingHandler())) {
      Thread.sleep(200); // the replayed totals arrive just after thread/resume answers
      TurnResult r = s.runTurn(TurnSpec.text("hi"), e -> {});
      // the fake replays 1000 tokens of earlier work; this turn takes the thread total to 1120
      assertEquals(120L, ((Number) r.usage().get("taskTokens")).longValue(), String.valueOf(r.usage()));
    }
  }

  @Test
  void aReviewIsShownOnce() throws Exception {
    try (AgentSession s = provider("echo").open(spec(), new RecordingHandler())) {
      List<dev.fellowloop.protocol.AgentEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
      s.runTurn(new TurnSpec("", List.of(), null, null, Map.of("type", "uncommittedChanges")), events::add);
      assertEquals(1, events.stream().filter(e -> e.type().equals(dev.fellowloop.protocol.AgentEvent.MESSAGE)).count());
      assertEquals(dev.fellowloop.protocol.AgentEvent.TURN_STARTED, events.stream()
          .filter(e -> !e.type().equals(dev.fellowloop.protocol.AgentEvent.NOTICE)).findFirst().orElseThrow().type(),
          "the turn starts before its items");
    }
  }

  @Test
  void inputForARunningCommandIsNotJudgedAsTheCommand() throws Exception {
    RecordingHandler h = new RecordingHandler();
    try (AgentSession s = provider("stdin").open(spec(), h)) {
      s.runTurn(TurnSpec.text("go"), e -> {});
      assertEquals(dev.fellowloop.agent.core.Interaction.ApprovalKind.OTHER, h.approval.get().kind());
      assertEquals(null, h.approval.get().command(), "no command for the policy to match");
      assertTrue(h.approval.get().title().startsWith("input to a running command"));
    }
  }

  @Test
  void codexErrorsKeepTheirMeaning() {
    assertEquals(dev.fellowloop.protocol.ErrorCodes.MODEL_NOT_FOUND, dev.fellowloop.agent.codex.CodexSessionAccess.errorCode("modelNotFound"));
    assertEquals(dev.fellowloop.protocol.ErrorCodes.USAGE_LIMIT, dev.fellowloop.agent.codex.CodexSessionAccess.errorCode(Map.of("rateLimitExceeded", Map.of())));
    assertEquals(dev.fellowloop.protocol.ErrorCodes.USAGE_LIMIT, dev.fellowloop.agent.codex.CodexSessionAccess.errorCode("sessionBudgetExceeded"));
    assertEquals(dev.fellowloop.protocol.ErrorCodes.PROVIDER_UNAVAILABLE,
        dev.fellowloop.agent.codex.CodexSessionAccess.errorCode("responseTooManyFailedAttempts"));
  }

  @Test
  void networkAccessIsRequestedOnlyForTheWorkspaceTier() throws Exception {
    java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("fellowloop-net");
    var withNet = new dev.fellowloop.agent.core.SessionSpec(dir, null, null, null, dev.fellowloop.agent.core.Permission.WORKSPACE,
        null, null, null, null, null, null, true);
    assertEquals(Map.of("network_access", true),
        dev.fellowloop.agent.codex.CodexSessionAccess.threadConfig(withNet).get("sandbox_workspace_write"));
    var readOnly = new dev.fellowloop.agent.core.SessionSpec(dir, null, null, null, dev.fellowloop.agent.core.Permission.READ_ONLY,
        null, null, null, null, null, null, true);
    assertTrue(dev.fellowloop.agent.codex.CodexSessionAccess.threadConfig(readOnly).isEmpty());
    var withModel = new dev.fellowloop.agent.core.SessionSpec(dir, "gpt-6-luna", null, null,
        dev.fellowloop.agent.core.Permission.READ_ONLY, null, null, null, null, null, null, false);
    assertEquals("gpt-6-luna", dev.fellowloop.agent.codex.CodexSessionAccess.threadConfig(withModel).get("review_model"),
        "native reviews run on the agent's model");
  }
}
