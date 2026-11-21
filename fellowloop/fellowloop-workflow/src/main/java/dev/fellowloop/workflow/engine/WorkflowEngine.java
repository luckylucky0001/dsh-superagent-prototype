package dev.fellowloop.workflow.engine;

import dev.fellowloop.workflow.expr.Expression;
import dev.fellowloop.workflow.expr.ExpressionException;
import dev.fellowloop.workflow.expr.Template;
import dev.fellowloop.workflow.model.EdgeDef;
import dev.fellowloop.workflow.model.NodeDef;
import dev.fellowloop.workflow.model.WorkflowDefinition;
import dev.fellowloop.workflow.spi.ComponentRegistry;
import dev.fellowloop.workflow.spi.NodeComponent;
import dev.fellowloop.workflow.spi.NodeContext;
import dev.fellowloop.workflow.spi.NodeResult;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes workflow runs. The engine holds no run state; every method takes a {@link RunState},
 * advances it as far as possible without blocking, and returns it. Callers must serialise calls for
 * the same run (for example with a row lock) and persist the state afterwards.
 *
 * <p>Semantics: a node becomes ready once every incoming edge is resolved and at least one is active;
 * if all are dead, the node is skipped and its outgoing edges die too (dead-path elimination). This
 * makes branches that split with a condition and join later behave as expected.
 */
public final class WorkflowEngine {

  private static final int MAX_STEPS = 10_000;

  private final ComponentRegistry registry;
  private final RunListener listener;
  private final Clock clock;

  public WorkflowEngine(ComponentRegistry registry, RunListener listener, Clock clock) {
    this.registry = registry;
    this.listener = listener == null ? RunListener.NONE : listener;
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  public WorkflowEngine(ComponentRegistry registry) {
    this(registry, RunListener.NONE, Clock.systemUTC());
  }

  /** Starts a run from one trigger node; other trigger nodes are skipped. */
  public RunState start(String runId, String workflowId, WorkflowDefinition def, String triggerNodeId,
      Map<String, Object> payload) {
    NodeDef trigger = def.requireNode(triggerNodeId);
    if (!registry.require(trigger.type()).descriptor().trigger()) {
      throw new IllegalArgumentException(triggerNodeId + " is not a trigger");
    }
    RunState s = new RunState();
    s.runId = runId;
    s.workflowId = workflowId;
    s.triggerNodeId = triggerNodeId;
    s.trigger = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
    s.startedAt = clock.millis();
    for (NodeDef n : def.nodes()) {
      s.nodes.put(n.id(), new NodeState());
    }
    for (EdgeDef e : def.edges()) {
      s.edges.put(e.id(), EdgeStatus.PENDING);
    }
    Deque<String> ready = new ArrayDeque<>();
    for (NodeDef n : def.nodes()) {
      if (registry.require(n.type()).descriptor().trigger()) {
        NodeState ns = s.node(n.id());
        if (n.id().equals(triggerNodeId)) {
          ns.status = NodeStatus.SUCCEEDED;
          ns.attempts = 1;
          ns.output = new LinkedHashMap<>(s.trigger);
          ns.ports = List.of(EdgeDef.DEFAULT_PORT);
          ns.startedAt = ns.finishedAt = s.startedAt;
        } else {
          ns.status = NodeStatus.SKIPPED;
        }
        listener.nodeChanged(s, n.id(), ns);
      }
    }
    for (NodeDef n : def.nodes()) {
      NodeState ns = s.node(n.id());
      if (ns.status == NodeStatus.SUCCEEDED) {
        propagate(s, def, n.id(), ready);
      } else if (ns.status == NodeStatus.SKIPPED) {
        killOutgoing(s, def, n.id(), ready);
      }
    }
    drain(s, def, ready);
    return s;
  }

  /**
   * Delivers an external signal to a waiting node. Signals whose wait key does not match the node's
   * current one (for example from an earlier attempt) are ignored.
   */
  public RunState signal(RunState s, WorkflowDefinition def, String nodeId, String waitKey,
      Map<String, Object> payload) {
    if (s.status.terminal()) {
      return s;
    }
    NodeState ns = s.node(nodeId);
    if (ns.status != NodeStatus.WAITING || (waitKey != null && !waitKey.equals(ns.waitKey))) {
      return s;
    }
    NodeDef node = def.requireNode(nodeId);
    NodeComponent c = registry.require(node.type());
    NodeResult r;
    try {
      r = c.onSignal(context(s, def, node, ns, resolveQuietly(s, node)), payload == null ? Map.of() : payload);
    } catch (RuntimeException e) {
      r = NodeResult.failed(message(e));
    }
    Deque<String> ready = new ArrayDeque<>();
    apply(s, def, node, ns, r, ready);
    drain(s, def, ready);
    return s;
  }

  /** Fails a waiting node, e.g. when its deadline passed; retries still apply. */
  public RunState failWaiting(RunState s, WorkflowDefinition def, String nodeId, String waitKey, String error) {
    if (s.status.terminal()) {
      return s;
    }
    NodeState ns = s.node(nodeId);
    if (ns.status != NodeStatus.WAITING || (waitKey != null && !waitKey.equals(ns.waitKey))) {
      return s;
    }
    NodeDef node = def.requireNode(nodeId);
    cancelComponent(s, def, node, ns);
    Deque<String> ready = new ArrayDeque<>();
    apply(s, def, node, ns, NodeResult.failed(error), ready);
    drain(s, def, ready);
    return s;
  }

  /** Re-executes a failed node of a failed run, keeping everything that already succeeded. */
  public RunState retry(RunState s, WorkflowDefinition def, String nodeId) {
    NodeState ns = s.node(nodeId);
    if (ns.status != NodeStatus.FAILED || s.status != RunStatus.FAILED) {
      throw new IllegalStateException("only a failed node of a failed run can be retried");
    }
    // a node whose failure was handled (onError: continue) already took its error path; only the node that
    // failed the run can be retried, or the run would end "succeeded" while that node is still failed
    if (def.requireNode(nodeId).onError() == NodeDef.OnError.CONTINUE) {
      throw new IllegalStateException("node " + nodeId + " handled its failure; retry the node that failed the run");
    }
    s.status = RunStatus.RUNNING;
    s.error = null;
    s.finishedAt = 0;
    ns.status = NodeStatus.READY;
    ns.attempts = 0;
    ns.error = null;
    listener.runChanged(s);
    Deque<String> ready = new ArrayDeque<>();
    ready.add(nodeId);
    // branches the failure cancelled, or that were ready when it happened, resume as well
    for (NodeDef n : def.nodes()) {
      NodeState other = s.node(n.id());
      if (!n.id().equals(nodeId) && (other.status == NodeStatus.CANCELLED || other.status == NodeStatus.READY)) {
        other.status = NodeStatus.READY;
        other.waitKey = null;
        other.finishedAt = 0;
        listener.nodeChanged(s, n.id(), other);
        ready.add(n.id());
      }
    }
    drain(s, def, ready);
    return s;
  }

  public RunState cancel(RunState s, WorkflowDefinition def, String reason) {
    if (s.status.terminal()) {
      return s;
    }
    for (NodeDef n : def.nodes()) {
      NodeState ns = s.node(n.id());
      if (ns.status == NodeStatus.WAITING || ns.status == NodeStatus.RUNNING) {
        cancelComponent(s, def, n, ns);
      }
      if (!ns.status.terminal()) {
        ns.status = NodeStatus.CANCELLED;
        ns.finishedAt = clock.millis();
        listener.nodeChanged(s, n.id(), ns);
      }
    }
    finish(s, RunStatus.CANCELLED, reason);
    return s;
  }

  /** The variables expressions and templates see for this run. */
  public static Map<String, Object> variables(RunState s) {
    Map<String, Object> nodes = new LinkedHashMap<>();
    s.nodes.forEach((id, ns) -> {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("status", ns.status.name().toLowerCase());
      view.put("output", ns.output);
      view.put("error", ns.error);
      view.put("attempts", ns.attempts);
      nodes.put(id, view);
    });
    Map<String, Object> run = new LinkedHashMap<>();
    run.put("id", s.runId);
    run.put("workflowId", s.workflowId);
    run.put("startedAt", s.startedAt);
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("trigger", s.trigger);
    vars.put("nodes", nodes);
    vars.put("run", run);
    return vars;
  }

  // ---------------------------------------------------------------- internals

  private void drain(RunState s, WorkflowDefinition def, Deque<String> ready) {
    int steps = 0;
    while (!ready.isEmpty() && !s.status.terminal()) {
      if (++steps > MAX_STEPS) {
        finish(s, RunStatus.FAILED, "step limit exceeded");
        return;
      }
      String id = ready.poll();
      NodeDef node = def.requireNode(id);
      NodeState ns = s.node(id);
      if (ns.status != NodeStatus.READY) {
        continue;
      }
      execute(s, def, node, ns, ready);
    }
    if (!s.status.terminal()) {
      settle(s);
    }
  }

  private void execute(RunState s, WorkflowDefinition def, NodeDef node, NodeState ns, Deque<String> ready) {
    ns.status = NodeStatus.RUNNING;
    ns.attempts++;
    ns.error = null;
    ns.waitKey = null;
    ns.deadlineAt = 0;
    if (ns.startedAt == 0) {
      ns.startedAt = clock.millis();
    }
    listener.nodeChanged(s, node.id(), ns);
    NodeComponent c = registry.require(node.type());
    NodeResult r;
    Map<String, Object> config;
    try {
      config = resolve(s, node);
    } catch (ExpressionException e) {
      apply(s, def, node, ns, NodeResult.fatal("configuration: " + e.getMessage()), ready);
      return;
    }
    try {
      r = c.execute(context(s, def, node, ns, config));
      if (r == null) {
        r = NodeResult.fatal("component returned no result");
      }
    } catch (Exception e) {
      r = NodeResult.failed(message(e));
    }
    apply(s, def, node, ns, r, ready);
  }

  private void apply(RunState s, WorkflowDefinition def, NodeDef node, NodeState ns, NodeResult r, Deque<String> ready) {
    switch (r) {
      case NodeResult.Completed done -> {
        ns.status = NodeStatus.SUCCEEDED;
        ns.output = new LinkedHashMap<>(done.output());
        ns.ports = done.ports();
        ns.waitKey = null;
        ns.deadlineAt = 0;
        ns.finishedAt = clock.millis();
        listener.nodeChanged(s, node.id(), ns);
        propagate(s, def, node.id(), ready);
      }
      case NodeResult.Waiting w -> {
        ns.status = NodeStatus.WAITING;
        ns.waitKey = w.waitKey();
        if (!w.output().isEmpty()) {
          ns.output = new LinkedHashMap<>(w.output());
        }
        ns.deadlineAt = node.timeoutSeconds() > 0 ? clock.millis() + node.timeoutSeconds() * 1000L : 0;
        listener.nodeChanged(s, node.id(), ns);
      }
      case NodeResult.Failed f -> {
        ns.error = f.error();
        if (!f.output().isEmpty()) {
          ns.output = new LinkedHashMap<>(f.output());
        }
        if (f.retryable() && ns.attempts <= node.retries()) {
          ns.status = NodeStatus.READY;
          ns.waitKey = null;
          listener.nodeChanged(s, node.id(), ns);
          ready.addFirst(node.id());
          return;
        }
        ns.status = NodeStatus.FAILED;
        ns.waitKey = null;
        ns.deadlineAt = 0;
        ns.finishedAt = clock.millis();
        listener.nodeChanged(s, node.id(), ns);
        if (node.onError() == NodeDef.OnError.CONTINUE) {
          ns.output.put("error", f.error());
          ns.ports = List.of(EdgeDef.ERROR_PORT);
          propagate(s, def, node.id(), ready);
        } else {
          for (NodeDef other : def.nodes()) {
            NodeState os = s.node(other.id());
            if (os.status == NodeStatus.WAITING) {
              cancelComponent(s, def, other, os);
              os.status = NodeStatus.CANCELLED;
              os.finishedAt = clock.millis();
              listener.nodeChanged(s, other.id(), os);
            }
          }
          finish(s, RunStatus.FAILED, node.displayName() + ": " + f.error());
        }
      }
    }
  }

  private void propagate(RunState s, WorkflowDefinition def, String nodeId, Deque<String> ready) {
    NodeState ns = s.node(nodeId);
    Map<String, Object> vars = null;
    for (EdgeDef e : def.outgoing(nodeId)) {
      boolean active = ns.ports.contains(e.sourcePort());
      if (active && e.condition() != null) {
        if (vars == null) {
          vars = variables(s);
        }
        try {
          active = Expression.parse(e.condition()).test(vars);
        } catch (ExpressionException ex) {
          active = false;
          ns.output.put("_conditionError:" + e.id(), ex.getMessage());
        }
      }
      s.edges.put(e.id(), active ? EdgeStatus.ACTIVE : EdgeStatus.DEAD);
    }
    for (EdgeDef e : def.outgoing(nodeId)) {
      evaluate(s, def, e.target(), ready);
    }
  }

  private void killOutgoing(RunState s, WorkflowDefinition def, String nodeId, Deque<String> ready) {
    for (EdgeDef e : def.outgoing(nodeId)) {
      s.edges.put(e.id(), EdgeStatus.DEAD);
    }
    for (EdgeDef e : def.outgoing(nodeId)) {
      evaluate(s, def, e.target(), ready);
    }
  }

  private void evaluate(RunState s, WorkflowDefinition def, String nodeId, Deque<String> ready) {
    NodeState ns = s.node(nodeId);
    if (ns.status != NodeStatus.PENDING) {
      return;
    }
    boolean anyActive = false;
    for (EdgeDef in : def.incoming(nodeId)) {
      EdgeStatus st = s.edges.getOrDefault(in.id(), EdgeStatus.PENDING);
      if (st == EdgeStatus.PENDING) {
        return;
      }
      anyActive |= st == EdgeStatus.ACTIVE;
    }
    if (anyActive) {
      ns.status = NodeStatus.READY;
      ready.add(nodeId);
    } else {
      ns.status = NodeStatus.SKIPPED;
      ns.finishedAt = clock.millis();
      listener.nodeChanged(s, nodeId, ns);
      killOutgoing(s, def, nodeId, ready);
    }
  }

  private void settle(RunState s) {
    boolean waiting = false;
    boolean open = false;
    for (NodeState ns : s.nodes.values()) {
      if (ns.status == NodeStatus.WAITING) {
        waiting = true;
      } else if (!ns.status.terminal()) {
        open = true;
      }
    }
    if (waiting) {
      if (s.status != RunStatus.WAITING) {
        s.status = RunStatus.WAITING;
        listener.runChanged(s);
      }
    } else if (!open) {
      finish(s, RunStatus.SUCCEEDED, null);
    } else {
      // Should not happen for a validated DAG: nodes remain that nothing can reach.
      finish(s, RunStatus.FAILED, "the run stalled with unreachable nodes");
    }
  }

  private void finish(RunState s, RunStatus status, String error) {
    s.status = status;
    s.error = error;
    s.finishedAt = clock.millis();
    listener.runChanged(s);
  }

  private void cancelComponent(RunState s, WorkflowDefinition def, NodeDef node, NodeState ns) {
    try {
      registry.require(node.type()).cancel(context(s, def, node, ns, resolveQuietly(s, node)));
    } catch (RuntimeException ignored) {
      // best effort: cancelling must never block the run from ending
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> resolve(RunState s, NodeDef node) {
    return (Map<String, Object>) Template.resolve(node.config(), variables(s));
  }

  private Map<String, Object> resolveQuietly(RunState s, NodeDef node) {
    try {
      return resolve(s, node);
    } catch (ExpressionException e) {
      return node.config();
    }
  }

  private NodeContext context(RunState s, WorkflowDefinition def, NodeDef node, NodeState ns, Map<String, Object> config) {
    Map<String, Object> vars = variables(s);
    return new NodeContext() {
      public String runId() {
        return s.runId;
      }

      public String workflowId() {
        return s.workflowId;
      }

      public NodeDef node() {
        return node;
      }

      public Map<String, Object> config() {
        return config;
      }

      public Map<String, Object> variables() {
        return vars;
      }

      public int attempt() {
        return ns.attempts;
      }

      public String waitKey() {
        return ns.waitKey;
      }
    };
  }

  private static String message(Throwable e) {
    String m = e.getMessage();
    return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
  }
}
