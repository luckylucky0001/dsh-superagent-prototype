package dev.orca.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orca.agent.core.AgentEventSink;
import dev.orca.agent.core.AgentProvider;
import dev.orca.agent.core.AgentSession;
import dev.orca.agent.core.Capability;
import dev.orca.agent.core.Interaction;
import dev.orca.agent.core.SessionSpec;
import dev.orca.agent.core.TurnResult;
import dev.orca.agent.core.TurnSpec;
import dev.orca.protocol.AgentEvent;
import dev.orca.protocol.ProviderStatus;
import dev.orca.protocol.Wire;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** How the daemon drives a session: cancels, messages during reviews, and task-wide approvals. */
class TaskRunnerTest {

  @TempDir
  Path root;

  /** A session whose turns are scripted by the test. */
  interface Script {
    TurnResult turn(TurnSpec turn, Interaction.Handler handler) throws Exception;
  }

  static final class Recorder implements Link {
    final List<Wire.Frame> frames = new CopyOnWriteArrayList<>();

    public boolean send(Wire.Frame frame) {
      frames.add(frame);
      return true;
    }

    public boolean connected() {
      return true;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> events(String type) {
      return frames.stream().filter(f -> f.type().equals(Wire.TASK_EVENTS))
          .flatMap(f -> ((List<Map<String, Object>>) f.data().get("events")).stream())
          .filter(e -> type.equals(e.get("type"))).toList();
    }

    Map<String, Object> completion() {
      return frames.stream().filter(f -> f.type().equals(Wire.TASK_COMPLETED)).findFirst().orElseThrow().data();
    }
  }

  private static AgentProvider provider(Set<String> caps, Script script, AtomicInteger turns) {
    return new AgentProvider() {
      public String id() {
        return "codex";
      }

      public ProviderStatus probe() {
        return null;
      }

      public AgentSession open(SessionSpec spec, Interaction.Handler handler) {
        return new AgentSession() {
          public String sessionRef() {
            return "thr_1";
          }

          public String model() {
            return "gpt-6-luna";
          }

          public boolean resumed() {
            return false;
          }

          public Set<String> capabilities() {
            return caps;
          }

          public TurnResult runTurn(TurnSpec turn, AgentEventSink sink) {
            turns.incrementAndGet();
            try {
              return script.turn(turn, handler);
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }

          public void steer(String text) {
            throw new UnsupportedOperationException("cannot steer a review turn");
          }

          public void interrupt() {
            // the scripted turn decides how it ends
          }

          public void close() {
            // nothing to release
          }
        };
      }
    };
  }

  private TaskRunner runner(AgentProvider p, Recorder link, Map<String, Object> review, boolean escalate) {
    Wire.AgentSpec agent = new Wire.AgentSpec("agt_1", "Builder", "gpt-6-luna", null, null, "workspace", List.of(),
        List.of(), Map.of(), escalate, false);
    Wire.TaskAssign task = new Wire.TaskAssign("tsk_1", "codex", agent, "adhoc/t-1", null, "do it", null, null, null,
        60, null, List.of(), null, review, null);
    return new TaskRunner(task, p, new Workspaces(root), link, false, false, false, "", () -> {});
  }

  private static TurnResult done(String text) {
    return new TurnResult(TurnResult.Status.COMPLETED, text, null, null, null, Map.of());
  }

  @Test
  void aMessageDuringAReviewIsRefusedAndTheReviewIsKept() throws Exception {
    CountDownLatch inTurn = new CountDownLatch(1);
    CountDownLatch steered = new CountDownLatch(1);
    AtomicInteger turns = new AtomicInteger();
    Recorder link = new Recorder();
    TaskRunner r = runner(provider(Set.of(Capability.REVIEW, Capability.STEER), (t, h) -> {
      inTurn.countDown();
      steered.await(10, TimeUnit.SECONDS);
      return done("REVIEW: [P1] rounding");
    }, turns), link, Map.of("type", "uncommittedChanges"), false);
    Thread t = Thread.ofVirtual().start(r::run);
    assertTrue(inTurn.await(10, TimeUnit.SECONDS));
    r.steer("also check the tests");
    steered.countDown();
    t.join(10_000);
    assertEquals("REVIEW: [P1] rounding", link.completion().get("finalText"), "a follow-up must not replace the review");
    assertEquals(1, turns.get());
    assertTrue(link.events(AgentEvent.NOTICE).stream()
        .anyMatch(e -> String.valueOf(((Map<?, ?>) e.get("data")).get("message")).contains("review cannot take messages")));
  }

  @Test
  void aCancelThatArrivesAsTheTurnFinishesStillCancelsTheTask() throws Exception {
    CountDownLatch inTurn = new CountDownLatch(1);
    CountDownLatch cancelled = new CountDownLatch(1);
    Recorder link = new Recorder();
    TaskRunner r = runner(provider(Set.of(), (t, h) -> {
      inTurn.countDown();
      cancelled.await(10, TimeUnit.SECONDS);
      return done("finished anyway");
    }, new AtomicInteger()), link, null, false);
    Thread t = Thread.ofVirtual().start(r::run);
    assertTrue(inTurn.await(10, TimeUnit.SECONDS));
    r.cancel("user");
    cancelled.countDown();
    t.join(10_000);
    assertEquals("cancelled", link.completion().get("status"));
  }

  @Test
  void allowForThisTaskIsRememberedByTheDaemonNotTheCli() throws Exception {
    Recorder link = new Recorder();
    List<Interaction.Decision> decisions = new CopyOnWriteArrayList<>();
    Interaction.ApprovalRequest curl = new Interaction.ApprovalRequest("a1", Interaction.ApprovalKind.COMMAND,
        "curl", "curl -fsS https://registry.example/pkg", null, List.of(), "needs the network", Map.of());
    TaskRunner r = runner(provider(Set.of(), (t, h) -> {
      decisions.add(h.approve(curl));
      decisions.add(h.approve(new Interaction.ApprovalRequest("a2", curl.kind(), curl.title(), curl.command(), null,
          List.of(), null, Map.of())));
      return done("ok");
    }, new AtomicInteger()), link, null, true);
    Thread t = Thread.ofVirtual().start(r::run);
    String requestId = null;
    for (int i = 0; i < 200 && requestId == null; i++) {
      List<Map<String, Object>> asked = link.events(AgentEvent.INPUT_REQUESTED);
      if (!asked.isEmpty()) {
        requestId = String.valueOf(((Map<?, ?>) asked.get(0).get("data")).get("requestId"));
      } else {
        Thread.sleep(50);
      }
    }
    assertTrue(requestId != null, "the person was asked");
    r.input(requestId, Map.of("decision", List.of(TaskRunner.APPROVE_TASK)));
    t.join(10_000);
    assertEquals(List.of(Interaction.Decision.ACCEPT_FOR_SESSION, Interaction.Decision.ACCEPT), decisions,
        "the same command again is approved once by the daemon, without asking");
    assertEquals(1, link.events(AgentEvent.INPUT_REQUESTED).size());
  }
}
