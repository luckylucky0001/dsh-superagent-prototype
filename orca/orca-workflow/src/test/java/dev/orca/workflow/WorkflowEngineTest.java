package dev.orca.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orca.workflow.engine.NodeStatus;
import dev.orca.workflow.engine.RunState;
import dev.orca.workflow.engine.RunStatus;
import dev.orca.workflow.engine.ValidationIssue;
import dev.orca.workflow.engine.WorkflowEngine;
import dev.orca.workflow.engine.WorkflowValidator;
import dev.orca.workflow.model.EdgeDef;
import dev.orca.workflow.model.NodeDef;
import dev.orca.workflow.model.WorkflowDefinition;
import dev.orca.workflow.spi.ComponentRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkflowEngineTest {

  private final AtomicInteger failures = new AtomicInteger();
  private ComponentRegistry registry;
  private WorkflowEngine engine;

  @BeforeEach
  void setUp() {
    TestComponents.cancelled.clear();
    registry = new ComponentRegistry()
        .register(TestComponents.trigger())
        .register(TestComponents.echo())
        .register(TestComponents.branch())
        .register(TestComponents.async())
        .register(TestComponents.flaky(failures));
    engine = new WorkflowEngine(registry);
  }

  private static NodeDef node(String id, String type, Map<String, Object> config) {
    return new NodeDef(id, type, config);
  }

  @Test
  void linearRunPassesDataThroughTemplates() {
    var def = new WorkflowDefinition(
        List.of(node("start", "t.manual", Map.of()),
            node("a", "t.echo", Map.of("text", "hello {{ trigger.name }}")),
            node("b", "t.echo", Map.of("text", "{{ nodes.a.output.text }}!", "raw", "{{ trigger.n }}"))),
        List.of(new EdgeDef("e1", "start", "a"), new EdgeDef("e2", "a", "b")));
    assertTrue(new WorkflowValidator(registry).validate(def).isEmpty());

    RunState s = engine.start("r1", "w1", def, "start", Map.of("name", "orca", "n", 3));

    assertEquals(RunStatus.SUCCEEDED, s.status);
    assertEquals("hello orca!", s.node("b").output.get("text"));
    assertEquals(3, s.node("b").output.get("raw"), "a whole-placeholder template keeps the value's type");
  }

  @Test
  void conditionBranchesJoinWithDeadPathElimination() {
    var def = new WorkflowDefinition(
        List.of(node("start", "t.manual", Map.of()),
            node("check", "t.if", Map.of("when", "{{ trigger.n > 2 }}")),
            node("big", "t.echo", Map.of("text", "big")),
            node("small", "t.echo", Map.of("text", "small")),
            node("join", "t.echo", Map.of("text", "{{ nodes.big.status }}/{{ nodes.small.status }}"))),
        List.of(new EdgeDef("e1", "start", "check"),
            new EdgeDef("e2", "check", "true", "big", null),
            new EdgeDef("e3", "check", "false", "small", null),
            new EdgeDef("e4", "big", "join"),
            new EdgeDef("e5", "small", "join")));

    RunState s = engine.start("r", "w", def, "start", Map.of("n", 5));

    assertEquals(RunStatus.SUCCEEDED, s.status);
    assertEquals(NodeStatus.SKIPPED, s.node("small").status);
    assertEquals("succeeded/skipped", s.node("join").output.get("text"));
  }

  @Test
  void edgeConditionsGateTargets() {
    var def = new WorkflowDefinition(
        List.of(node("start", "t.manual", Map.of()),
            node("a", "t.echo", Map.of()),
            node("b", "t.echo", Map.of())),
        List.of(new EdgeDef("e1", "start", "out", "a", "trigger.go == true"),
            new EdgeDef("e2", "start", "out", "b", "!trigger.go")));
    RunState s = engine.start("r", "w", def, "start", Map.of("go", false));
    assertEquals(NodeStatus.SKIPPED, s.node("a").status);
    assertEquals(NodeStatus.SUCCEEDED, s.node("b").status);
  }

  @Test
  void asyncNodesWaitAndResumeWithSignals() {
    var def = new WorkflowDefinition(
        List.of(node("start", "t.manual", Map.of()),
            node("agent", "t.async", Map.of()),
            node("after", "t.echo", Map.of("text", "{{ nodes.agent.output.text }}"))),
        List.of(new EdgeDef("e1", "start", "agent"), new EdgeDef("e2", "agent", "after")));

    RunState s = engine.start("r", "w", def, "start", Map.of());
    assertEquals(RunStatus.WAITING, s.status);
    assertEquals("agent#1", s.node("agent").waitKey);

    engine.signal(s, def, "agent", "stale-key", Map.of("text", "ignored"));
    assertEquals(RunStatus.WAITING, s.status, "a signal for another wait key is ignored");

    engine.signal(s, def, "agent", "agent#1", Map.of("text", "done"));
    assertEquals(RunStatus.SUCCEEDED, s.status);
    assertEquals("done", s.node("after").output.get("text"));
  }

  @Test
  void retriesApplyToSyncAndAsyncFailures() {
    failures.set(2);
    var flaky = new NodeDef("f", "t.flaky", "Flaky", Map.of(), 2, 0, NodeDef.OnError.FAIL, Map.of());
    var asyncRetry = new NodeDef("agent", "t.async", "Agent", Map.of(), 1, 0, NodeDef.OnError.FAIL, Map.of());
    var def = new WorkflowDefinition(
        List.of(node("start", "t.manual", Map.of()), flaky, asyncRetry),
        List.of(new EdgeDef("e1", "start", "f"), new EdgeDef("e2", "f", "agent")));

    RunState s = engine.start("r", "w", def, "start", Map.of());
    assertEquals(3, s.node("f").attempts);
    assertEquals(NodeStatus.SUCCEEDED, s.node("f").status);

    engine.signal(s, def, "agent", "agent#1", Map.of("status", "failed"));
    assertEquals(NodeStatus.WAITING, s.node("agent").status);
    assertEquals("agent#2", s.node("agent").waitKey, "the retry waits on a new key");

    engine.signal(s, def, "agent", "agent#2", Map.of("status", "failed"));
    assertEquals(RunStatus.FAILED, s.status);
  }

  @Test
  void onErrorContinueRoutesThroughErrorPort() {
    failures.set(5);
    var flaky = new NodeDef("f", "t.flaky", "Flaky", Map.of(), 0, 0, NodeDef.OnError.CONTINUE, Map.of());
    var def = new WorkflowDefinition(
        List.of(node("start", "t.manual", Map.of()), flaky,
            node("ok", "t.echo", Map.of()), node("handler", "t.echo", Map.of("text", "{{ nodes.f.output.error }}"))),
        List.of(new EdgeDef("e1", "start", "f"),
            new EdgeDef("e2", "f", "ok"),
            new EdgeDef("e3", "f", "error", "handler", null)));
    assertTrue(new WorkflowValidator(registry).validate(def).isEmpty());

    RunState s = engine.start("r", "w", def, "start", Map.of());
    assertEquals(RunStatus.SUCCEEDED, s.status);
    assertEquals(NodeStatus.SKIPPED, s.node("ok").status);
    assertEquals("boom 1", s.node("handler").output.get("text"));
  }

  @Test
  void failureCancelsParallelWaitingBranches() {
    failures.set(1);
    var def = new WorkflowDefinition(
        List.of(node("start", "t.manual", Map.of()), node("agent", "t.async", Map.of()), node("f", "t.flaky", Map.of())),
        List.of(new EdgeDef("e1", "start", "agent"), new EdgeDef("e2", "start", "f")));
    RunState s = engine.start("r", "w", def, "start", Map.of());
    assertEquals(RunStatus.FAILED, s.status);
    assertEquals(NodeStatus.CANCELLED, s.node("agent").status);
    assertEquals(List.of("agent#1"), TestComponents.cancelled);
  }

  @Test
  void cancelAndManualRetry() {
    var def = new WorkflowDefinition(
        List.of(node("start", "t.manual", Map.of()), node("agent", "t.async", Map.of())),
        List.of(new EdgeDef("e1", "start", "agent")));
    RunState s = engine.start("r", "w", def, "start", Map.of());
    engine.cancel(s, def, "user");
    assertEquals(RunStatus.CANCELLED, s.status);
    assertEquals(List.of("agent#1"), TestComponents.cancelled);

    failures.set(1);
    var def2 = new WorkflowDefinition(
        List.of(node("start", "t.manual", Map.of()), node("f", "t.flaky", Map.of()), node("after", "t.echo", Map.of())),
        List.of(new EdgeDef("e1", "start", "f"), new EdgeDef("e2", "f", "after")));
    RunState s2 = engine.start("r2", "w", def2, "start", Map.of());
    assertEquals(RunStatus.FAILED, s2.status);
    engine.retry(s2, def2, "f");
    assertEquals(RunStatus.SUCCEEDED, s2.status);
    assertEquals(NodeStatus.SUCCEEDED, s2.node("after").status);
  }

  @Test
  void validatorReportsStructuralProblems() {
    var def = new WorkflowDefinition(
        List.of(node("a", "t.echo", Map.of("text", "{{ broken ")),
            node("b", "t.echo", Map.of()),
            node("c", "t.nope", Map.of()),
            node("d", "t.if", Map.of())),
        List.of(new EdgeDef("e1", "a", "b"), new EdgeDef("e2", "b", "a"), new EdgeDef("e3", "a", "bogus", "b", null)));
    List<String> messages = new WorkflowValidator(registry).validate(def).stream().map(ValidationIssue::message).toList();
    assertTrue(messages.stream().anyMatch(m -> m.contains("unclosed")), messages.toString());
    assertTrue(messages.stream().anyMatch(m -> m.contains("unknown component type")), messages.toString());
    assertTrue(messages.stream().anyMatch(m -> m.contains("is required")), messages.toString());
    assertTrue(messages.stream().anyMatch(m -> m.contains("no output port 'bogus'")), messages.toString());
    assertTrue(messages.stream().anyMatch(m -> m.contains("at least one trigger")), messages.toString());
    assertTrue(messages.stream().anyMatch(m -> m.contains("cycle")), messages.toString());
  }

  @Test
  void retryResumesBranchesTheFailureCancelled() {
    failures.set(1);
    var def = new WorkflowDefinition(
        List.of(node("start", "t.manual", Map.of()), node("agent", "t.async", Map.of()), node("f", "t.flaky", Map.of()),
            node("join", "t.echo", Map.of())),
        List.of(new EdgeDef("e1", "start", "agent"), new EdgeDef("e2", "start", "f"),
            new EdgeDef("e3", "agent", "join"), new EdgeDef("e4", "f", "join")));
    RunState s = engine.start("r", "w", def, "start", Map.of());
    assertEquals(RunStatus.FAILED, s.status);
    engine.retry(s, def, "f");
    assertEquals(RunStatus.WAITING, s.status, "the cancelled agent branch runs again");
    engine.signal(s, def, "agent", s.node("agent").waitKey, Map.of("text", "ok"));
    assertEquals(RunStatus.SUCCEEDED, s.status);
    assertEquals(NodeStatus.SUCCEEDED, s.node("join").status);
  }

  @Test
  void onlyTheNodeThatFailedTheRunCanBeRetried() {
    var handled = new NodeDef("f", "t.flaky", "Flaky", Map.of(), 0, 0, NodeDef.OnError.CONTINUE, Map.of());
    var def = new WorkflowDefinition(
        List.of(node("start", "t.manual", Map.of()), handled, node("g", "t.flaky", Map.of())),
        List.of(new EdgeDef("e1", "start", "f"), new EdgeDef("e2", "start", "g")));
    failures.set(2); // both f (failure handled) and g (failure fails the run) fail once
    RunState s = engine.start("r", "w", def, "start", Map.of());
    assertEquals(RunStatus.FAILED, s.status);
    assertEquals(NodeStatus.FAILED, s.node("f").status);
    assertEquals(NodeStatus.FAILED, s.node("g").status);
    org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> engine.retry(s, def, "f"),
        "retrying the handled failure would report the run succeeded while g is still failed");
    engine.retry(s, def, "g");
    assertEquals(RunStatus.SUCCEEDED, s.status);
  }

}
