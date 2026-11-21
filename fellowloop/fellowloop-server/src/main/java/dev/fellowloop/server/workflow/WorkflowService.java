package dev.fellowloop.server.workflow;

import dev.fellowloop.protocol.Json;
import dev.fellowloop.server.inbox.InboxService;
import dev.fellowloop.server.model.Task;
import dev.fellowloop.server.model.Workflow;
import dev.fellowloop.server.model.WorkflowRun;
import dev.fellowloop.server.model.WorkflowVersion;
import dev.fellowloop.server.realtime.Realtime;
import dev.fellowloop.server.repo.WorkflowRepo;
import dev.fellowloop.server.repo.WorkflowRunRepo;
import dev.fellowloop.server.repo.WorkflowVersionRepo;
import dev.fellowloop.server.support.ApiException;
import dev.fellowloop.server.support.Ids;
import dev.fellowloop.server.support.JsonCols;
import dev.fellowloop.server.task.TaskFinished;
import dev.fellowloop.server.task.TaskStatus;
import dev.fellowloop.workflow.engine.NodeState;
import dev.fellowloop.workflow.engine.NodeStatus;
import dev.fellowloop.workflow.engine.RunListener;
import dev.fellowloop.workflow.engine.RunState;
import dev.fellowloop.workflow.engine.RunStatus;
import dev.fellowloop.workflow.engine.ValidationIssue;
import dev.fellowloop.workflow.engine.WorkflowEngine;
import dev.fellowloop.workflow.engine.WorkflowValidator;
import dev.fellowloop.workflow.model.WorkflowDefinition;
import dev.fellowloop.workflow.spi.ComponentRegistry;
import dev.fellowloop.workflow.spi.NodeComponent;
import dev.fellowloop.workflow.spi.NodeDescriptor;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hosts the workflow engine: stores definitions as immutable versions, persists run state, and
 * serialises every state change of a run.
 */
@Service
public class WorkflowService {

  private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

  private final WorkflowRepo workflows;
  private final WorkflowVersionRepo versions;
  private final WorkflowRunRepo runs;
  private final InboxService inbox;
  private final Realtime realtime;
  private final ComponentRegistry registry;
  private final WorkflowEngine engine;
  private final WorkflowValidator validator;
  private final org.springframework.transaction.support.TransactionTemplate isolated;
  private final dev.fellowloop.server.repo.TaskRepo taskRepo;

  public WorkflowService(WorkflowRepo workflows, WorkflowVersionRepo versions, WorkflowRunRepo runs,
      InboxService inbox, Realtime realtime, List<NodeComponent> components,
      org.springframework.transaction.PlatformTransactionManager txm, dev.fellowloop.server.repo.TaskRepo taskRepo) {
    this.taskRepo = taskRepo;
    this.isolated = new org.springframework.transaction.support.TransactionTemplate(txm);
    this.isolated.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.workflows = workflows;
    this.versions = versions;
    this.runs = runs;
    this.inbox = inbox;
    this.realtime = realtime;
    this.registry = new ComponentRegistry().registerAll(components).loadPlugins(getClass().getClassLoader());
    RunListener listener = new RunListener() {
      @Override
      public void nodeChanged(RunState run, String nodeId, NodeState node) {
        realtime.publish("run.node", Realtime.ids("runId", run.runId, "nodeId", nodeId,
            "status", node.status.name().toLowerCase()));
      }

      @Override
      public void runChanged(RunState run) {
        realtime.publish("run.updated", Realtime.ids("id", run.runId, "workflowId", run.workflowId,
            "status", run.status.name().toLowerCase()));
      }
    };
    this.engine = new WorkflowEngine(registry, listener, Clock.systemUTC());
    this.validator = new WorkflowValidator(registry);
  }

  public ComponentRegistry registry() {
    return registry;
  }

  public List<NodeDescriptor> components() {
    return registry.descriptors();
  }

  // ---------------------------------------------------------------- definitions

  public static WorkflowDefinition parse(String json) {
    return Json.read(json, WorkflowDefinition.class);
  }

  public List<ValidationIssue> validate(WorkflowDefinition def) {
    return validator.validate(def);
  }

  @Transactional
  public Workflow save(String id, String name, String description, Boolean enabled, Object definition,
      boolean builtIn, String memberId) {
    WorkflowDefinition def = Json.convert(definition, WorkflowDefinition.class);
    List<ValidationIssue> issues = validate(def);
    boolean wantEnabled = enabled == null || enabled;
    if (!issues.isEmpty() && wantEnabled) {
      throw new ApiException(org.springframework.http.HttpStatus.valueOf(422),
          "the workflow has problems; fix them or save it disabled", issues);
    }
    Workflow w = id == null ? null : workflows.findById(id).orElse(null);
    long now = Ids.now();
    if (w == null) {
      w = new Workflow();
      w.id = id == null ? Ids.next("wf") : id;
      w.createdAt = now;
      w.builtIn = builtIn;
      // a re-created id continues its version history instead of colliding with it
      w.currentVersion = versions.findByWorkflowIdOrderByVersionDesc(w.id).stream().mapToInt(v -> v.version).max().orElse(0);
    }
    w.name = name == null || name.isBlank() ? "Untitled workflow" : name;
    w.description = description;
    w.enabled = wantEnabled && issues.isEmpty();
    String json = Json.write(def);
    if (!json.equals(w.definitionJson)) {
      w.currentVersion++;
      w.definitionJson = json;
      WorkflowVersion v = new WorkflowVersion();
      v.id = Ids.next("wfv");
      v.workflowId = w.id;
      v.version = w.currentVersion;
      v.definitionJson = json;
      v.createdBy = memberId;
      v.createdAt = now;
      versions.save(v);
    }
    w.updatedAt = now;
    workflows.save(w);
    realtime.publish("workflow.updated", Realtime.ids("id", w.id));
    return w;
  }

  @Transactional
  public Workflow setEnabled(String id, boolean enabled) {
    Workflow w = get(id);
    if (enabled && !validate(parse(w.definitionJson)).isEmpty()) {
      throw ApiException.bad("fix the workflow's problems before enabling it");
    }
    w.enabled = enabled;
    w.updatedAt = Ids.now();
    workflows.save(w);
    realtime.publish("workflow.updated", Realtime.ids("id", w.id));
    return w;
  }

  @Transactional
  public void delete(String id) {
    Workflow w = get(id);
    if (w.builtIn) {
      throw ApiException.bad("built-in workflows can be disabled but not deleted");
    }
    workflows.delete(w);
    realtime.publish("workflow.updated", Realtime.ids("id", id, "deleted", true));
  }

  public Workflow get(String id) {
    return workflows.findById(id).orElseThrow(() -> ApiException.notFound("workflow"));
  }

  public List<Workflow> all() {
    return workflows.findAllByOrderByCreatedAtAsc();
  }

  public List<Workflow> enabled() {
    return workflows.findByEnabledTrue();
  }

  public WorkflowDefinition definition(WorkflowRun run) {
    return versions.findByWorkflowIdAndVersion(run.workflowId, run.version)
        .map(v -> parse(v.definitionJson))
        .orElseGet(() -> parse(get(run.workflowId).definitionJson));
  }

  /** Display name, also for runs of deleted workflows. */
  public String name(String workflowId) {
    return workflows.findById(workflowId).map(w -> w.name).orElse("(deleted workflow)");
  }

  public List<WorkflowVersion> versions(String workflowId) {
    return versions.findByWorkflowIdOrderByVersionDesc(workflowId);
  }

  // ---------------------------------------------------------------- runs

  /** Starts a run of the workflow's current version from one of its trigger nodes. */
  @Transactional(propagation = Propagation.REQUIRED)
  public WorkflowRun start(Workflow w, String triggerNodeId, Map<String, Object> payload, String issueId) {
    return start(w, triggerNodeId, payload, issueId, null);
  }

  /**
   * {@code deliveryKey} is unique: the row is flushed before the engine runs anything, so a concurrent
   * redelivery fails here, before any step had an effect.
   */
  public WorkflowRun start(Workflow w, String triggerNodeId, Map<String, Object> payload, String issueId,
      String deliveryKey) {
    WorkflowDefinition def = parse(w.definitionJson);
    WorkflowRun run = new WorkflowRun();
    run.id = Ids.next("run");
    run.workflowId = w.id;
    run.version = w.currentVersion;
    run.triggerNodeId = triggerNodeId;
    run.issueId = issueId;
    run.startedAt = Ids.now();
    run.status = "running";
    run.stateJson = "{}";
    run.deliveryKey = deliveryKey;
    runs.saveAndFlush(run);
    log.info("run {} of '{}' started from {}", run.id, w.name, triggerNodeId);
    runs.lockById(run.id);
    RunState state = engine.start(run.id, w.id, def, triggerNodeId, payload);
    return store(run, state);
  }

  @Transactional(propagation = Propagation.REQUIRED)
  public void signal(String runId, String nodeId, String waitKey, Map<String, Object> payload) {
    mutate(runId, (run) -> engine.signal(state(run), definition(run), nodeId, waitKey, payload));
  }

  @Transactional
  public WorkflowRun cancel(String runId, String reason) {
    return mutate(runId, run -> engine.cancel(state(run), definition(run), reason));
  }

  @Transactional
  public WorkflowRun retry(String runId, String nodeId) {
    return mutate(runId, run -> engine.retry(state(run), definition(run), nodeId));
  }

  private WorkflowRun mutate(String runId, Function<WorkflowRun, RunState> change) {
    // The row lock (not a JVM lock) is held until commit, so a second signal always reads the first one's result.
    WorkflowRun run = runs.lockById(runId).orElseThrow(() -> ApiException.notFound("run"));
    RunState after = change.apply(run);
    return store(run, after);
  }

  private WorkflowRun store(WorkflowRun run, RunState s) {
    run.stateJson = Json.write(s);
    run.status = s.status.name().toLowerCase();
    run.error = s.error;
    if (s.status.terminal()) {
      run.finishedAt = s.finishedAt;
      inbox.closeRun(run.id);
      if (s.status == RunStatus.FAILED) {
        Workflow w = workflows.findById(run.workflowId).orElse(null);
        inbox.alert("Workflow failed: " + (w == null ? run.workflowId : w.name), s.error, run.id, run.issueId, null);
      }
    }
    WorkflowRun saved = runs.save(run);
    realtime.publish("run.updated", Realtime.ids("id", run.id, "workflowId", run.workflowId, "status", run.status,
        "issueId", run.issueId));
    return saved;
  }

  public RunState state(WorkflowRun run) {
    return Json.read(run.stateJson, RunState.class);
  }

  public java.util.Optional<WorkflowRun> byDelivery(String deliveryKey) {
    return runs.findByDeliveryKey(deliveryKey);
  }

  public WorkflowRun run(String id) {
    return runs.findById(id).orElseThrow(() -> ApiException.notFound("run"));
  }

  public List<WorkflowRun> recentRuns(String workflowId) {
    return workflowId == null ? runs.findTop100ByOrderByStartedAtDesc() : runs.findTop100ByWorkflowIdOrderByStartedAtDesc(workflowId);
  }

  public List<WorkflowRun> runsForIssue(String issueId) {
    return runs.findByIssueIdOrderByStartedAtAsc(issueId);
  }

  // ---------------------------------------------------------------- task results and deadlines

  /**
   * After the task's end has committed, resume its node in a transaction of its own: a failure here
   * must not roll back (and so lose) the agent's result, and a cancel that finishes a task while this
   * run is being changed must not re-enter the run.
   */
  @org.springframework.transaction.event.TransactionalEventListener(fallbackExecution = true)
  public void onTaskFinished(TaskFinished e) {
    Task t = e.task();
    Map<String, Object> origin = JsonCols.map(t.originJson);
    Object runId = origin.get("runId");
    Object nodeId = origin.get("nodeId");
    if (runId == null || nodeId == null) {
      return;
    }
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("taskId", t.id);
    payload.put("status", t.status);
    payload.put("text", t.finalText == null ? "" : t.finalText);
    payload.put("structured", JsonCols.any(t.structuredJson));
    payload.put("errorCode", t.errorCode);
    payload.put("error", t.error);
    payload.put("sessionRef", t.sessionRef);
    payload.put("model", t.model);
    payload.put("usage", JsonCols.map(t.usageJson));
    try {
      isolated.executeWithoutResult(st -> signal(String.valueOf(runId), String.valueOf(nodeId), t.id, payload));
    } catch (RuntimeException ex) {
      log.error("could not resume run {} after task {}", runId, t.id, ex);
    }
  }

  /** Each run in its own transaction: one that cannot be advanced must not hold up the others' timeouts. */
  @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
  public void expireDeadlines() {
    long now = Ids.now();
    for (WorkflowRun run : runs.findByStatusIn(List.of("waiting", "running"))) {
      RunState s = state(run);
      s.nodes.forEach((nodeId, ns) -> {
        if (ns.status == NodeStatus.WAITING && ns.deadlineAt > 0 && ns.deadlineAt < now) {
          String key = ns.waitKey;
          try {
            isolated.executeWithoutResult(st ->
                mutate(run.id, r -> engine.failWaiting(state(r), definition(r), nodeId, key, "timed out")));
          } catch (RuntimeException ex) {
            log.error("could not time out node {} of run {}", nodeId, run.id, ex);
          }
        }
      });
    }
  }

  /**
   * A task's result reaches its run by an event after the task is saved; if that was lost (a crash in
   * between, an error while resuming), the node would wait forever. Nodes still waiting on a task that
   * finished more than a minute ago get its result again.
   */
  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  public void redeliverFinishedTasks() {
    long before = Ids.now() - 60_000;
    for (WorkflowRun run : runs.findByStatusIn(List.of("waiting"))) {
      state(run).nodes.forEach((nodeId, ns) -> {
        if (ns.status != NodeStatus.WAITING || ns.waitKey == null || !ns.waitKey.startsWith("tsk_")) {
          return;
        }
        taskRepo.findById(ns.waitKey).filter(t -> TaskStatus.TERMINAL.contains(t.status)
            && t.finishedAt != null && t.finishedAt < before).ifPresent(t -> {
              log.warn("run {} node {} was still waiting for finished task {}; delivering its result", run.id, nodeId, t.id);
              onTaskFinished(new TaskFinished(t));
            });
      });
    }
  }
}
