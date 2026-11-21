package dev.orca.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orca.agent.core.AgentException;
import dev.orca.agent.core.AgentSession;
import dev.orca.agent.core.Capability;
import dev.orca.agent.core.Interaction;
import dev.orca.agent.core.SessionSpec;
import dev.orca.agent.core.TurnResult;
import dev.orca.agent.core.TurnSpec;
import dev.orca.agent.fake.FakeTraeAcp;
import dev.orca.agent.trae.TraeConfig;
import dev.orca.agent.trae.TraeProvider;
import dev.orca.protocol.AgentEvent;
import dev.orca.protocol.ErrorCodes;
import dev.orca.protocol.ProviderStatus;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Exercises the TRAE CLI ACP driver against {@link FakeTraeAcp}. */
class TraeDriverTest {

  private static TraeProvider provider(String scenario) {
    return new TraeProvider(new TraeConfig(FakeTraeAcp.command(), Map.of("FAKE_SCENARIO", scenario), "orca_test", "0",
        Duration.ofSeconds(20), null, null));
  }

  private static final class Handler implements Interaction.Handler {
    final AtomicReference<Interaction.ApprovalRequest> seen = new AtomicReference<>();
    Interaction.Decision decision = Interaction.Decision.ACCEPT;

    public Interaction.Decision approve(Interaction.ApprovalRequest r) {
      seen.set(r);
      return decision;
    }

    public Interaction.InputReply input(Interaction.InputRequest r) {
      return Interaction.InputReply.decline();
    }
  }

  private static SessionSpec spec() throws Exception {
    return spec("fake-model");
  }

  private static SessionSpec spec(String model) throws Exception {
    return new SessionSpec(Files.createTempDirectory("orca-trae"), model, null, "Be brief.",
        null, null, null, null, null, null, null);
  }

  @Test
  void probeUsesInitializeCapabilities() {
    ProviderStatus s = provider("echo").probe();
    assertTrue(s.healthy(), String.valueOf(s.error()));
    assertEquals("0.216.0", s.version());
    assertTrue(s.capabilities().containsAll(List.of(Capability.RESUME, Capability.FORK, Capability.IMAGES,
        Capability.MCP_HTTP)), s.capabilities().toString());
    assertFalse(s.capabilities().contains(Capability.STEER), "steering is not claimed until verified");
    assertEquals("fake_model", s.models().get(0).id(), "model ids come from the ACP model option");
    assertEquals("Fake-Model", s.models().get(0).displayName());
  }

  @Test
  void streamsMessagesToolsAndUsage() throws Exception {
    List<AgentEvent> events = new CopyOnWriteArrayList<>();
    try (AgentSession s = provider("echo").open(spec(), new Handler())) {
      assertEquals("ses_fake", s.sessionRef());
      assertEquals("fake_model", s.model(), "a display-name-like model resolves to TRAE's id");
      TurnResult r = s.runTurn(TurnSpec.text("hello"), events::add);
      assertEquals(TurnResult.Status.COMPLETED, r.status());
      assertEquals("ECHO: hello", r.finalText(), "only text after the last tool call is the deliverable");
      List<String> types = events.stream().map(AgentEvent::type).toList();
      assertTrue(types.containsAll(List.of(AgentEvent.PLAN, AgentEvent.TOOL_STARTED, AgentEvent.TOOL_COMPLETED,
          AgentEvent.MESSAGE, AgentEvent.USAGE, AgentEvent.TURN_COMPLETED)), types.toString());
      AgentEvent edit = events.stream().filter(e -> e.type().equals(AgentEvent.TOOL_COMPLETED)).findFirst().orElseThrow();
      assertEquals("file_change", edit.data().get("kind"));
      String diff = String.valueOf(((List<?>) edit.data().get("changes")).get(0));
      assertTrue(diff.contains("+there") && diff.contains("kind=update"), "the diff from the tool call: " + diff);

      TurnResult r2 = s.runTurn(new TurnSpec("x", List.of(), Map.of("type", "object"), null), e -> {});
      assertEquals(Map.of("ok", true), r2.structured());
      assertEquals(2000L, ((Number) r2.usage().get("taskTokens")).longValue(), "two turns of 1000 tokens");
      assertEquals(190000L, ((Number) r2.usage().get("contextWindow")).longValue());
      assertEquals(1200L, ((Number) r2.usage().get("cachedInputTokens")).longValue(), "session totals, Codex field names");
    }
  }

  @Test
  void permissionRequestsUseTheHandler() throws Exception {
    for (Interaction.Decision d : List.of(Interaction.Decision.ACCEPT, Interaction.Decision.DECLINE)) {
      Handler h = new Handler();
      h.decision = d;
      try (AgentSession s = provider("permission").open(spec(), h)) {
        TurnResult r = s.runTurn(TurnSpec.text("test"), e -> {});
        assertEquals(Interaction.ApprovalKind.COMMAND, h.seen.get().kind());
        assertEquals("npm test", h.seen.get().command());
        assertEquals("/work/app", h.seen.get().cwd(), "the command's cwd comes from rawInput");
        assertEquals("needs the network", h.seen.get().reason());
        assertEquals(TurnResult.Status.COMPLETED, r.status());
        // TRAE ends the turn when a request is declined; the driver asks it to carry on without it
        assertEquals(d == Interaction.Decision.ACCEPT ? "chosen=approved" : "continued without it", r.finalText());
      }
    }
  }

  @Test
  void anAgentThatKeepsAskingFailsInsteadOfLookingCancelled() throws Exception {
    Handler h = new Handler();
    h.decision = Interaction.Decision.DECLINE;
    try (AgentSession s = provider("stubborn").open(spec(), h)) {
      TurnResult r = s.runTurn(TurnSpec.text("test"), e -> {});
      assertEquals(TurnResult.Status.FAILED, r.status());
      assertEquals(ErrorCodes.AGENT_ERROR, r.errorCode());
      assertTrue(r.error().contains("declined requests") && r.error().contains("npm test"), r.error());
    }
  }

  @Test
  void reviewsUseTraesOwnReviewerOnce() throws Exception {
    try (AgentSession s = provider("echo").open(spec(), new Handler())) {
      assertTrue(s.capabilities().contains(Capability.REVIEW));
      TurnResult r = s.runTurn(new TurnSpec("", List.of(), null, null, Map.of("type", "baseBranch", "branch", "main")), e -> {});
      assertTrue(r.finalText().contains("/review Review the changes on this branch compared with `main` (git diff main...HEAD)."),
          r.finalText());
      assertEquals(1, r.finalText().split("The change breaks add").length - 1, "the repeated verdict is dropped");
    }
  }

  @Test
  void instructionsGoToTheSystemPromptAndModelsResolveByName() throws Exception {
    try (AgentSession s = provider("echo").open(spec("Fake-Mini"), new Handler())) {
      assertEquals("fake_mini_2", s.model());
      TurnResult r = s.runTurn(TurnSpec.text("system?"), e -> {});
      // workspace sessions first learn how commits work here, then get the agent's own instructions
      assertTrue(r.finalText().startsWith("SYSTEM: You work in a git checkout managed by Orca."), r.finalText());
      assertTrue(r.finalText().endsWith("the task ends.\n\nBe brief. MODEL: fake_mini_2"), r.finalText());
    }
    AgentException unknown = assertThrows(AgentException.class, () -> provider("echo").open(spec("nope"), new Handler()));
    assertEquals(ErrorCodes.MODEL_NOT_FOUND, unknown.code());
    assertTrue(unknown.getMessage().contains("Fake-Mini"), unknown.getMessage());
  }

  @Test
  void resumeIgnoresReplayedHistoryAndFailuresAreClassified() throws Exception {
    List<AgentEvent> events = new CopyOnWriteArrayList<>();
    try (AgentSession s = provider("echo").open(spec().withResume("ses_old"), new Handler())) {
      assertTrue(s.resumed());
      TurnResult r = s.runTurn(TurnSpec.text("again"), events::add);
      assertEquals("ECHO: again", r.finalText());
      assertTrue(events.stream().noneMatch(e -> String.valueOf(e.data().get("text")).contains("OLD HISTORY")));
    }
    AgentException gone = assertThrows(AgentException.class, () -> provider("echo").open(spec().withResume("gone"), new Handler()));
    assertEquals(ErrorCodes.SESSION_NOT_FOUND, gone.code());
    AgentException auth = assertThrows(AgentException.class, () -> provider("unauth").open(spec(), new Handler()));
    assertEquals(ErrorCodes.AUTH_REQUIRED, auth.code());
  }

  @Test
  void interruptCancelsThePrompt() throws Exception {
    try (AgentSession s = provider("hang").open(spec(), new Handler())) {
      List<AgentEvent> events = new CopyOnWriteArrayList<>();
      Thread.ofVirtual().start(() -> {
        for (int i = 0; i < 200 && events.stream().noneMatch(e -> e.type().equals(AgentEvent.TOOL_STARTED)); i++) {
          try {
            Thread.sleep(50);
          } catch (InterruptedException e) {
            return;
          }
        }
        s.interrupt();
      });
      TurnResult r = s.runTurn(TurnSpec.text("work"), events::add);
      assertEquals(TurnResult.Status.INTERRUPTED, r.status());
      assertThrows(UnsupportedOperationException.class, () -> s.steer("x"));
    }
  }
}
