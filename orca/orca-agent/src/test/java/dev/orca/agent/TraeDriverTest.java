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
    return new SessionSpec(Files.createTempDirectory("orca-trae"), "fake-model", null, "Be brief.",
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
    assertEquals("fake-model", s.models().get(0).id());
  }

  @Test
  void streamsMessagesToolsAndUsage() throws Exception {
    List<AgentEvent> events = new CopyOnWriteArrayList<>();
    try (AgentSession s = provider("echo").open(spec(), new Handler())) {
      assertEquals("ses_fake", s.sessionRef());
      assertEquals("fake-model", s.model());
      TurnResult r = s.runTurn(TurnSpec.text("hello"), events::add);
      assertEquals(TurnResult.Status.COMPLETED, r.status());
      assertEquals("ECHO: hello", r.finalText(), "only text after the last tool call is the deliverable");
      List<String> types = events.stream().map(AgentEvent::type).toList();
      assertTrue(types.containsAll(List.of(AgentEvent.PLAN, AgentEvent.TOOL_STARTED, AgentEvent.TOOL_COMPLETED,
          AgentEvent.MESSAGE, AgentEvent.USAGE, AgentEvent.TURN_COMPLETED)), types.toString());
      AgentEvent edit = events.stream().filter(e -> e.type().equals(AgentEvent.TOOL_COMPLETED)).findFirst().orElseThrow();
      assertEquals("file_change", edit.data().get("kind"));

      TurnResult r2 = s.runTurn(new TurnSpec("x", List.of(), Map.of("type", "object"), null), e -> {});
      assertEquals(Map.of("ok", true), r2.structured());
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
        assertEquals("chosen=" + (d == Interaction.Decision.ACCEPT ? "allow" : "deny"), r.finalText());
      }
    }
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
