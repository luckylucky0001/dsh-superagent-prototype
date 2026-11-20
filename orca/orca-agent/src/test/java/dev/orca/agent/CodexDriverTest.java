package dev.orca.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orca.agent.codex.CodexConfig;
import dev.orca.agent.codex.CodexProvider;
import dev.orca.agent.core.AgentException;
import dev.orca.agent.core.AgentSession;
import dev.orca.agent.core.Interaction;
import dev.orca.agent.core.SessionSpec;
import dev.orca.agent.core.TurnResult;
import dev.orca.agent.core.TurnSpec;
import dev.orca.agent.fake.FakeCodexAppServer;
import dev.orca.protocol.AgentEvent;
import dev.orca.protocol.ErrorCodes;
import dev.orca.protocol.ProviderStatus;
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
        "orca_test", "0", Duration.ofSeconds(20), null));
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
    return SessionSpec.simple(Files.createTempDirectory("orca-codex"), "gpt-6-luna");
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
}
