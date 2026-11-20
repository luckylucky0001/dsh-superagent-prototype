package dev.orca.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orca.agent.codex.CodexConfig;
import dev.orca.agent.codex.CodexProvider;
import dev.orca.agent.core.AgentSession;
import dev.orca.agent.core.Interaction;
import dev.orca.agent.core.Permission;
import dev.orca.agent.core.SessionSpec;
import dev.orca.agent.core.TurnResult;
import dev.orca.agent.core.TurnSpec;
import dev.orca.protocol.AgentEvent;
import dev.orca.protocol.ProviderStatus;
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
 * {@code ORCA_CODEX_BIN=/path/to/codex mvn test -pl orca-agent -Dgroups=live}.
 * Uses the cheapest model by default ({@code ORCA_TEST_CODEX_MODEL}, default gpt-6-luna).
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ORCA_CODEX_BIN", matches = ".+")
class CodexLiveTest {

  private static final String MODEL = System.getenv().getOrDefault("ORCA_TEST_CODEX_MODEL", "gpt-6-luna");

  private static CodexProvider provider() {
    String record = System.getenv("ORCA_RECORD_DIR");
    return new CodexProvider(new CodexConfig(List.of(System.getenv("ORCA_CODEX_BIN")), Map.of(), "orca_test", "0.1.0",
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
    Path dir = Files.createTempDirectory("orca-live");
    List<AgentEvent> events = new CopyOnWriteArrayList<>();
    SessionSpec spec = new SessionSpec(dir, MODEL, "low",
        "You are an automated coding agent working for the Orca platform. Do the task directly; keep replies short.",
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
      System.out.println("turn3: " + r3.finalText());
      assertTrue(r3.finalText().contains("hello.txt"));
    }
  }

  @Test
  void interruptStopsARunningTurn() throws Exception {
    Path dir = Files.createTempDirectory("orca-live-int");
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
}
