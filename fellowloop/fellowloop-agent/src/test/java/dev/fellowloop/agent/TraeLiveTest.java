package dev.fellowloop.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fellowloop.agent.core.AgentSession;
import dev.fellowloop.agent.core.Interaction;
import dev.fellowloop.agent.core.Permission;
import dev.fellowloop.agent.core.SessionSpec;
import dev.fellowloop.agent.core.TurnResult;
import dev.fellowloop.agent.core.TurnSpec;
import dev.fellowloop.agent.trae.TraeConfig;
import dev.fellowloop.agent.trae.TraeProvider;
import dev.fellowloop.protocol.AgentEvent;
import dev.fellowloop.protocol.ProviderStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Talks to a real, logged-in TRAE CLI 2.x over ACP. Run with
 * {@code FELLOWLOOP_TRAE_BIN=$(which traecli) mvn test -pl fellowloop-agent -Dgroups=live -Dfellowloop.excludedGroups=none}.
 * Uses a fast model by default ({@code FELLOWLOOP_TEST_TRAE_MODEL}, default GLM-5.3-Flash, given by display name
 * on purpose: the driver resolves it to TRAE's id).
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FELLOWLOOP_TRAE_BIN", matches = ".+")
class TraeLiveTest {

  private static final String MODEL = System.getenv().getOrDefault("FELLOWLOOP_TEST_TRAE_MODEL", "GLM-5.3-Flash");

  private static TraeProvider provider() {
    return new TraeProvider(new TraeConfig(List.of(System.getenv("FELLOWLOOP_TRAE_BIN")), Map.of(), "fellowloop_test", "1.0.0",
        Duration.ofSeconds(90), null, null));
  }

  private static SessionSpec spec(Path dir, Permission permission, boolean network) {
    return new SessionSpec(dir, MODEL, null,
        "You are an automated coding agent working for the Fellowloop platform. Do the task directly; keep replies short."
            + " End every answer with the word FELLOWLOOP-OK.",
        permission, null, null, null, List.of(), List.of(), Map.of(), network);
  }

  private static final class Handler implements Interaction.Handler {
    final AtomicReference<Interaction.ApprovalRequest> seen = new AtomicReference<>();
    Interaction.Decision decision = Interaction.Decision.DECLINE;

    public Interaction.Decision approve(Interaction.ApprovalRequest r) {
      seen.set(r);
      return decision;
    }

    public Interaction.InputReply input(Interaction.InputRequest r) {
      return Interaction.InputReply.decline();
    }
  }

  @Test
  void probeListsModelIdsWithDisplayNames() {
    ProviderStatus s = provider().probe();
    System.out.println("probe: version=" + s.version() + " loggedIn=" + s.loggedIn() + " models="
        + s.models().stream().map(m -> m.id() + "=" + m.displayName()).toList());
    assertTrue(s.loggedIn(), String.valueOf(s.error()));
    assertTrue(s.models().stream().anyMatch(m -> MODEL.equalsIgnoreCase(m.displayName())), "model list has " + MODEL);
    assertTrue(s.models().stream().anyMatch(m -> !m.id().equals(m.displayName())), "ids differ from display names");
  }

  @Test
  void workspaceTierWritesInsideOnlyWithoutNetworkAndUsesTheSystemPrompt() throws Exception {
    Path dir = Files.createTempDirectory("fellowloop-trae-live");
    Path outside = Path.of(System.getProperty("user.home"), "fellowloop-trae-live-" + System.nanoTime() + ".txt");
    List<AgentEvent> events = new CopyOnWriteArrayList<>();
    Handler h = new Handler();
    try (AgentSession s = provider().open(spec(dir, Permission.WORKSPACE, false), h)) {
      TurnResult r = s.runTurn(TurnSpec.text("Using your shell tool, run these one at a time and report each result in one line:\n"
          + "1. echo hi > inside.txt\n2. echo hi > " + outside + "\n"
          + "3. python3 -c \"import socket;socket.create_connection(('1.1.1.1',443),timeout=5);print('net-ok')\"\n"
          + "If something is blocked, do not ask for approval; just report it."), events::add);
      System.out.println("workspace: " + r.status() + " | " + r.finalText().replace('\n', ' '));
      assertEquals(TurnResult.Status.COMPLETED, r.status(), String.valueOf(r.error()));
      assertTrue(Files.exists(dir.resolve("inside.txt")), "writes inside the workspace");
      assertFalse(Files.exists(outside), "no writes outside the workspace");
      assertTrue(r.finalText().contains("FELLOWLOOP-OK"), "the agent instructions arrive as the system prompt");
      assertTrue(events.stream().filter(e -> e.type().equals(AgentEvent.TOOL_COMPLETED))
          .noneMatch(e -> String.valueOf(e.data().get("output")).lines().anyMatch(l -> l.trim().equals("net-ok"))),
          "no network by default (the output, not the echoed command)");
      assertTrue(((Number) r.usage().getOrDefault("taskTokens", 0)).longValue() > 0, "token usage: " + r.usage());
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void aDeclinedRequestDoesNotEndTheTaskAndSessionsResume() throws Exception {
    Path dir = Files.createTempDirectory("fellowloop-trae-live");
    Path outside = Path.of(System.getProperty("user.home"), "fellowloop-trae-escalate-" + System.nanoTime() + ".txt");
    Handler h = new Handler();
    String ref;
    try (AgentSession s = provider().open(spec(dir, Permission.WORKSPACE, false), h)) {
      TurnResult r = s.runTurn(TurnSpec.text("Write the text x to " + outside + " with your shell tool. If the sandbox"
          + " blocks it, request permission to run it outside the sandbox. Remember the code word: KESTREL."), e -> {});
      System.out.println("declined: " + r.status() + " | " + r.finalText().replace('\n', ' '));
      assertTrue(h.seen.get() != null, "TRAE asked for approval");
      assertTrue(String.valueOf(h.seen.get().command()).contains("fellowloop-trae-escalate"), String.valueOf(h.seen.get()));
      assertEquals(TurnResult.Status.COMPLETED, r.status(), "the driver continued after the decline: " + r.error());
      assertFalse(Files.exists(outside));
      ref = s.sessionRef();
    } finally {
      Files.deleteIfExists(outside);
    }
    try (AgentSession s = provider().open(spec(dir, Permission.WORKSPACE, false).withResume(ref), new Handler())) {
      assertTrue(s.resumed());
      TurnResult r = s.runTurn(TurnSpec.text("What was the code word I gave you? Answer with the word only."), e -> {});
      System.out.println("resumed: " + r.finalText().replace('\n', ' '));
      assertTrue(r.finalText().toUpperCase().contains("KESTREL"), r.finalText());
    }
  }

  @Test
  void anApprovalForTheTaskLeavesNoSavedRuleInTrae() throws Exception {
    Path dir = Files.createTempDirectory("fellowloop-trae-grant");
    Path home = Path.of(System.getProperty("user.home"));
    Path outside = home.resolve("fellowloop-live-grant-" + System.nanoTime() + ".txt");
    Handler h = new Handler();
    h.decision = Interaction.Decision.ACCEPT_FOR_SESSION; // a person chose "Allow for this task"
    try (AgentSession s = provider().open(spec(dir, Permission.WORKSPACE, false), h)) {
      TurnResult r = s.runTurn(TurnSpec.text("Run exactly this shell command: echo grant > " + outside
          + "   If the sandbox blocks it, ask to run it outside the sandbox once. Then reply done."), e -> {});
      System.out.println("grant: " + r.status() + " asked=" + (h.seen.get() != null) + " written=" + Files.exists(outside));
      assertNotNull(h.seen.get(), "TRAE asked to leave the sandbox");
      assertTrue(Files.exists(outside), "the approved command ran");
    } finally {
      Files.deleteIfExists(outside);
    }
    Path rules = home.resolve(".trae/cli/rules");
    if (Files.isDirectory(rules)) {
      try (var files = Files.list(rules)) {
        for (Path f : files.toList()) {
          assertFalse(Files.readString(f).contains(outside.getFileName().toString()),
              "TRAE saved a don't-ask-again rule in " + f);
        }
      }
    }
  }

  @Test
  void readOnlyCannotWrite() throws Exception {
    Path dir = Files.createTempDirectory("fellowloop-trae-live");
    try (AgentSession s = provider().open(spec(dir, Permission.READ_ONLY, false), new Handler())) {
      TurnResult r = s.runTurn(TurnSpec.text("Run `echo hi > ro.txt` with your shell tool and report the result."), e -> {});
      System.out.println("read-only: " + r.finalText().replace('\n', ' '));
      assertFalse(Files.exists(dir.resolve("ro.txt")));
    }
  }

  @Test
  void reviewsABranchWithTraesOwnReviewer() throws Exception {
    Path dir = Files.createTempDirectory("fellowloop-trae-review");
    run(dir, "git", "init", "-q", "-b", "main");
    Files.writeString(dir.resolve("calc.py"), "def add(a, b):\n    return a + b\n");
    run(dir, "git", "add", ".");
    run(dir, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init");
    run(dir, "git", "checkout", "-qb", "feature");
    Files.writeString(dir.resolve("calc.py"), "def add(a, b):\n    return a - b\n");
    run(dir, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qam", "tweak add");
    try (AgentSession s = provider().open(spec(dir, Permission.READ_ONLY, false), new Handler())) {
      TurnResult r = s.runTurn(new TurnSpec("", List.of(), null, null, Map.of("type", "baseBranch", "branch", "main")), e -> {});
      System.out.println("review: " + r.finalText().replace('\n', ' '));
      assertEquals(TurnResult.Status.COMPLETED, r.status(), String.valueOf(r.error()));
      assertTrue(r.finalText().contains("calc.py"), "a finding with file and line");
    }
  }

  private static void run(Path dir, String... cmd) throws Exception {
    assertEquals(0, new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO().start().waitFor());
  }
}
