package dev.orca.server.task;

import dev.orca.protocol.AgentEvent;
import dev.orca.protocol.ErrorCodes;
import dev.orca.protocol.Json;
import dev.orca.protocol.ProviderStatus;
import dev.orca.protocol.Wire;
import dev.orca.server.inbox.InboxService;
import dev.orca.server.model.Agent;
import dev.orca.server.model.Comment;
import dev.orca.server.model.Issue;
import dev.orca.server.model.Project;
import dev.orca.server.model.RuntimeHost;
import dev.orca.server.model.Task;
import dev.orca.server.model.TaskEvent;
import dev.orca.server.realtime.Realtime;
import dev.orca.server.repo.AgentRepo;
import dev.orca.server.repo.CommentRepo;
import dev.orca.server.repo.IssueRepo;
import dev.orca.server.repo.ProjectRepo;
import dev.orca.server.repo.TaskEventRepo;
import dev.orca.server.repo.TaskRepo;
import dev.orca.server.runtime.DaemonGateway;
import dev.orca.server.runtime.RuntimeService;
import dev.orca.server.support.ApiException;
import dev.orca.server.support.Ids;
import dev.orca.server.support.JsonCols;
import dev.orca.server.support.OrcaProperties;
import dev.orca.server.support.Views;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Agent tasks: queueing, routing to runtimes, leases, and everything daemons report back.
 */
@Service
public class TaskService {

  private static final Logger log = LoggerFactory.getLogger(TaskService.class);

  static final String PLATFORM_GUIDE = """
      You are %s, an AI teammate working through Orca for a software team.
      - Work directly in the current directory; it belongs to this task.
      - Your final reply is posted to the team, so make it a short, concrete summary of what you did and what is left.
      - If you are blocked or need a decision, say exactly what you need in your final reply.
      - Refer to files by paths relative to the working directory; do not use absolute paths or file:// links.
      - If the orca tools are available, you may read the issue with orca_get_issue and, for long tasks, post a brief
        progress note with orca_post_comment. Do not post your final reply with it; it is posted for you.
      - If the directory is a git repository, commit your work on the current branch with a clear message
        (git add + git commit). If the sandbox blocks the commit, request approval to run it; it is allowed.
        Do not push or open pull requests unless the task asks for it.
      - Never print secrets such as tokens or passwords.""";

  private final TaskRepo tasks;
  private final TaskEventRepo events;
  private final AgentRepo agents;
  private final IssueRepo issues;
  private final ProjectRepo projects;
  private final CommentRepo comments;
  private final RuntimeService runtimes;
  private final DaemonGateway gateway;
  private final InboxService inbox;
  private final Realtime realtime;
  private final ApplicationEventPublisher publisher;
  private final OrcaProperties props;
  private final dev.orca.server.auth.AuthService auth;
  private final boolean mcpEnabled;

  public TaskService(TaskRepo tasks, TaskEventRepo events, AgentRepo agents, IssueRepo issues, ProjectRepo projects,
      CommentRepo comments, RuntimeService runtimes, DaemonGateway gateway, InboxService inbox, Realtime realtime,
      ApplicationEventPublisher publisher, OrcaProperties props, dev.orca.server.auth.AuthService auth,
      @org.springframework.beans.factory.annotation.Value("${orca.mcp.enabled:true}") boolean mcpEnabled) {
    this.auth = auth;
    this.mcpEnabled = mcpEnabled;
    this.tasks = tasks;
    this.events = events;
    this.agents = agents;
    this.issues = issues;
    this.projects = projects;
    this.comments = comments;
    this.runtimes = runtimes;
    this.gateway = gateway;
    this.inbox = inbox;
    this.realtime = realtime;
    this.publisher = publisher;
    this.props = props;
  }

  // ---------------------------------------------------------------- creating work

  @Transactional
  public Task enqueue(TaskRequest req) {
    Agent agent = agents.findById(req.agentId()).orElseThrow(() -> ApiException.notFound("agent " + req.agentId()));
    if (agent.archived) {
      throw ApiException.bad("agent " + agent.name + " is archived");
    }
    Issue issue = req.issueId() == null || req.issueId().isBlank() ? null
        : issues.findById(req.issueId()).or(() -> issues.findByIssueKeyIgnoreCase(req.issueId())).orElse(null);
    Task t = new Task();
    t.id = Ids.next("tsk");
    t.agentId = agent.id;
    t.issueId = issue == null ? null : issue.id;
    t.provider = agent.provider;
    t.kind = req.kind() == null ? "run" : req.kind();
    t.originJson = JsonCols.write(req.origin());
    t.prompt = req.prompt();
    t.outputSchemaJson = JsonCols.write(req.outputSchema());
    t.status = TaskStatus.QUEUED;
    t.queuedAt = Ids.now();
    t.workspaceKey = workspaceKey(agent, issue, req.origin());
    if ("continue".equals(req.sessionMode()) && issue != null) {
      for (Task prev : tasks.findByAgentIdAndIssueIdAndStatusOrderByFinishedAtDesc(agent.id, issue.id, TaskStatus.SUCCEEDED)) {
        if (prev.sessionRef != null && prev.provider.equals(agent.provider)
            && (agent.runtimeId == null || agent.runtimeId.equals(prev.runtimeId))) {
          t.resumeRef = prev.sessionRef;
          break;
        }
      }
    }
    if (issue != null) {
      t.recap = recap(issue);
      // a fresh session must know what it works on, whatever the workflow's prompt says
      if (t.resumeRef == null && (t.prompt == null || !t.prompt.contains(issue.issueKey))) {
        t.prompt = t.recap + "\n---\n\n" + (t.prompt == null ? "" : t.prompt);
      }
    }
    tasks.save(t);
    changed(t);
    return t;
  }

  /**
   * One directory per issue, shared by every agent that works on it (one at a time), so a reviewer
   * sees the implementer's changes. Work without an issue shares a directory per workflow run.
   */
  static String workspaceKey(Agent agent, Issue issue, Map<String, Object> origin) {
    if (issue != null) {
      return "issues/" + issue.issueKey.replaceAll("[^A-Za-z0-9._-]", "-");
    }
    Object run = origin == null ? null : origin.get("runId");
    return run == null ? "adhoc/" + agent.name.replaceAll("[^A-Za-z0-9._-]", "-") + "-" + Ids.random(6)
        : "runs/" + run;
  }

  /** Context for a new session when the previous one cannot be resumed. */
  String recap(Issue issue) {
    StringBuilder sb = new StringBuilder();
    sb.append("Context: you are working on issue ").append(issue.issueKey).append(" \"").append(issue.title).append("\".\n");
    if (issue.description != null && !issue.description.isBlank()) {
      sb.append("\nDescription:\n").append(trim(issue.description, 4000)).append('\n');
    }
    // workflow notices (system comments) are left out: stale automated conclusions mislead a new session
    List<Comment> all = comments.findByIssueIdOrderByCreatedAtAsc(issue.id).stream()
        .filter(c -> !"system".equals(c.authorType)).toList();
    if (!all.isEmpty()) {
      sb.append("\nRecent discussion (oldest first):\n");
      for (Comment c : all.subList(Math.max(0, all.size() - 12), all.size())) {
        sb.append("- ").append(author(c)).append(": ").append(trim(c.body, 1500).replace("\n", "\n  ")).append('\n');
      }
    }
    return sb.toString();
  }

  private String author(Comment c) {
    if ("agent".equals(c.authorType)) {
      return agents.findById(String.valueOf(c.authorId)).map(a -> a.name + " (agent)").orElse("agent");
    }
    return "member".equals(c.authorType) ? "teammate" : c.authorType;
  }

  private static String trim(String s, int max) {
    return s == null ? "" : s.length() <= max ? s : s.substring(0, max) + " …";
  }

  // ---------------------------------------------------------------- dispatch

  /** Hands queued tasks to connected runtimes that can run them. */
  @Scheduled(fixedDelay = 1000, initialDelay = 2000)
  @Transactional
  public synchronized void dispatch() {
    List<Task> queued = tasks.findByStatusOrderByQueuedAtAsc(TaskStatus.QUEUED);
    if (queued.isEmpty()) {
      return;
    }
    Set<String> busyPairs = new HashSet<>();
    for (Task t : queued) {
      Agent agent = agents.findById(t.agentId).orElse(null);
      if (agent == null) {
        finish(t, TaskStatus.FAILED, ErrorCodes.AGENT_ERROR, "agent was deleted", null);
        continue;
      }
      String detail = route(t, agent, busyPairs);
      if (detail != null) {
        if (!detail.equals(t.statusDetail)) {
          t.statusDetail = detail;
          tasks.save(t);
          changed(t);
        }
        continue;
      }
      RuntimeHost r = runtimes.find(agent.runtimeId).orElseThrow();
      t.runtimeId = r.id;
      t.status = TaskStatus.DISPATCHED;
      t.statusDetail = null;
      t.leaseUntil = Ids.now() + props.leaseSeconds() * 1000L;
      tasks.save(t);
      changed(t);
      Wire.Frame frame = Wire.frame(Wire.TASK_ASSIGN, assignment(t, agent));
      afterCommit(() -> {
        if (!gateway.send(r.id, frame)) {
          log.warn("could not send task {} to runtime {}; it will be retried when the lease expires", t.id, r.id);
        }
      });
    }
  }

  /** Returns why the task cannot be dispatched now, or null if it can. */
  private String route(Task t, Agent agent, Set<String> busyPairs) {
    if (agent.runtimeId == null) {
      return "the agent has no runtime";
    }
    Optional<RuntimeHost> rt = runtimes.find(agent.runtimeId);
    if (rt.isEmpty()) {
      return "the agent's runtime was removed";
    }
    RuntimeHost r = rt.get();
    if (!gateway.connected(r.id)) {
      return "waiting for runtime " + r.name + " to come online";
    }
    Optional<ProviderStatus> ps = runtimes.provider(r, agent.provider);
    if (ps.isEmpty() || !ps.get().installed()) {
      return agent.provider + " is not installed on " + r.name;
    }
    if (!ps.get().loggedIn()) {
      return agent.provider + " is not logged in on " + r.name;
    }
    if (tasks.countByRuntimeIdAndStatusIn(r.id, TaskStatus.ACTIVE) >= r.capacity) {
      return "runtime " + r.name + " is busy";
    }
    if (tasks.countByAgentIdAndStatusIn(agent.id, TaskStatus.ACTIVE) >= Math.max(1, agent.maxConcurrency)) {
      return agent.name + " is busy with other work";
    }
    if (busyPairs.contains(t.workspaceKey)
        || tasks.countByWorkspaceKeyAndStatusIn(t.workspaceKey, TaskStatus.ACTIVE) > 0) {
      busyPairs.add(t.workspaceKey);
      return "waiting for another agent to finish in this workspace";
    }
    busyPairs.add(t.workspaceKey);
    return null;
  }

  private Wire.TaskAssign assignment(Task t, Agent a) {
    String guide = PLATFORM_GUIDE.formatted(a.name);
    String instructions = a.instructions == null || a.instructions.isBlank() ? guide : a.instructions + "\n\n" + guide;
    Wire.AgentSpec spec = new Wire.AgentSpec(a.id, a.name, a.model, a.effort, instructions, a.permission,
        JsonCols.strings(a.allowedCommandsJson), JsonCols.strings(a.extraArgsJson), Map.of());
    Wire.RepoSpec repo = null;
    if (t.issueId != null) {
      Issue issue = issues.findById(t.issueId).orElse(null);
      Project p = issue == null ? null : projects.findById(issue.projectId).orElse(null);
      if (p != null && p.repoUrl != null && !p.repoUrl.isBlank()) {
        repo = new Wire.RepoSpec(p.repoUrl, "orca/" + issue.issueKey.toLowerCase(), p.defaultBranch);
      }
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> schema = t.outputSchemaJson == null ? null : JsonCols.map(t.outputSchemaJson);
    Wire.McpSpec mcp = null;
    if (mcpEnabled && t.issueId != null) {
      String token = auth.issueTaskToken(t.id, java.time.Duration.ofMinutes(Math.max(60, a.timeoutMinutes) + 60L));
      mcp = new Wire.McpSpec(props.publicUrl() + "/api/mcp", token);
    }
    return new Wire.TaskAssign(t.id, t.provider, spec, t.workspaceKey, repo, t.prompt, t.recap, t.resumeRef, schema,
        Math.max(60, a.timeoutMinutes * 60), mcp);
  }

  // ---------------------------------------------------------------- daemon reports

  @Transactional
  public void accepted(String runtimeId, Wire.TaskAccepted m) {
    tasks.findById(m.taskId()).filter(t -> runtimeId.equals(t.runtimeId)).ifPresent(t -> {
      t.leaseUntil = Ids.now() + props.leaseSeconds() * 1000L;
      tasks.save(t);
    });
  }

  @Transactional
  public void started(String runtimeId, Wire.TaskStarted m) {
    tasks.findById(m.taskId()).filter(t -> runtimeId.equals(t.runtimeId)).ifPresent(t -> {
      if (TaskStatus.TERMINAL.contains(t.status)) {
        return;
      }
      t.status = TaskStatus.RUNNING;
      t.sessionRef = m.sessionRef();
      t.model = m.model();
      t.startedAt = Ids.now();
      t.leaseUntil = Ids.now() + props.leaseSeconds() * 1000L;
      tasks.save(t);
      changed(t);
    });
  }

  @Transactional
  public void events(String runtimeId, Wire.TaskEvents m) {
    Task t = tasks.findById(m.taskId()).orElse(null);
    if (t == null || !runtimeId.equals(t.runtimeId) || m.events() == null || m.events().isEmpty()) {
      return;
    }
    List<Map<String, Object>> fresh = new ArrayList<>();
    long max = t.lastSeq;
    for (AgentEvent e : m.events()) {
      if (e.seq() <= t.lastSeq || events.existsByTaskIdAndSeq(t.id, e.seq())) {
        continue;
      }
      TaskEvent row = new TaskEvent();
      row.taskId = t.id;
      row.seq = e.seq();
      row.type = e.type();
      row.ts = e.ts();
      row.dataJson = JsonCols.write(e.data());
      events.save(row);
      max = Math.max(max, e.seq());
      fresh.add(Map.of("seq", e.seq(), "ts", e.ts(), "type", e.type(), "data", e.data()));
      onEvent(t, e);
    }
    t.lastSeq = max;
    t.leaseUntil = Ids.now() + props.leaseSeconds() * 1000L;
    tasks.save(t);
    long ack = max;
    afterCommit(() -> gateway.send(runtimeId, Wire.frame(Wire.ACK, new Wire.Ack(t.id, ack))));
    if (!fresh.isEmpty()) {
      realtime.publish("task.events", Map.of("taskId", t.id, "issueId", String.valueOf(t.issueId), "events", fresh));
    }
  }

  private void onEvent(Task t, AgentEvent e) {
    switch (e.type()) {
      case AgentEvent.INPUT_REQUESTED -> {
        inbox.question(t, String.valueOf(e.data().get("requestId")), String.valueOf(e.data().getOrDefault("title",
            "The agent has a question")), e.data());
        t.status = TaskStatus.WAITING_INPUT;
        changed(t);
      }
      case AgentEvent.INPUT_RESOLVED -> {
        if (TaskStatus.WAITING_INPUT.equals(t.status)) {
          t.status = TaskStatus.RUNNING;
          changed(t);
        }
        inbox.closeQuestion(t.id, String.valueOf(e.data().get("requestId")));
      }
      case AgentEvent.USAGE -> t.usageJson = JsonCols.write(e.data());
      default -> {
        // stored only
      }
    }
  }

  @Transactional
  public void completed(String runtimeId, Wire.TaskCompleted m) {
    Task t = tasks.findById(m.taskId()).orElse(null);
    if (t == null) {
      return;
    }
    afterCommit(() -> gateway.send(runtimeId, Wire.frame(Wire.ACK, new Wire.Ack(m.taskId(), m.lastSeq()))));
    if (TaskStatus.TERMINAL.contains(t.status)) {
      return;
    }
    String status = switch (String.valueOf(m.status())) {
      case "succeeded" -> TaskStatus.SUCCEEDED;
      case "cancelled" -> TaskStatus.CANCELLED;
      case "timed_out" -> TaskStatus.TIMED_OUT;
      default -> TaskStatus.FAILED;
    };
    if (m.sessionRef() != null) {
      t.sessionRef = m.sessionRef();
    }
    t.finalText = m.finalText();
    t.structuredJson = JsonCols.write(m.structured());
    if (m.usage() != null && !m.usage().isEmpty()) {
      t.usageJson = JsonCols.write(m.usage());
    }
    finish(t, status, m.errorCode(), m.error(), null);
  }

  /** Ends a task once; publishes {@link TaskFinished} so workflows can resume. */
  private void finish(Task t, String status, String code, String error, String detail) {
    if (TaskStatus.TERMINAL.contains(t.status)) {
      return;
    }
    t.status = status;
    t.errorCode = code;
    t.error = error;
    t.statusDetail = detail;
    t.finishedAt = Ids.now();
    t.leaseUntil = null;
    tasks.save(t);
    inbox.closeTask(t.id);
    auth.revokeTaskTokens(t.id);
    changed(t);
    publisher.publishEvent(new TaskFinished(t));
  }

  @Transactional
  public void heartbeat(String runtimeId, Wire.Heartbeat h) {
    long lease = Ids.now() + props.leaseSeconds() * 1000L;
    for (String id : h.running() == null ? List.<String>of() : h.running()) {
      tasks.findById(id).filter(t -> runtimeId.equals(t.runtimeId) && !TaskStatus.TERMINAL.contains(t.status))
          .ifPresent(t -> {
            t.leaseUntil = lease;
            tasks.save(t);
          });
    }
  }

  /** After a reconnect: tasks the server thinks run there but the daemon does not know are lost. */
  @Transactional
  public List<String> reconcile(String runtimeId, List<Wire.RunningTask> running) {
    Set<String> known = new HashSet<>();
    for (Wire.RunningTask r : running == null ? List.<Wire.RunningTask>of() : running) {
      known.add(r.taskId());
    }
    List<String> lost = new ArrayList<>();
    for (Task t : tasks.findByStatusIn(TaskStatus.ACTIVE)) {
      if (runtimeId.equals(t.runtimeId) && !known.contains(t.id) && TaskStatus.RUNNING.equals(t.status)) {
        finish(t, TaskStatus.FAILED, ErrorCodes.RUNTIME_LOST, "the runtime restarted while the task was running", null);
        lost.add(t.id);
      }
    }
    return lost;
  }

  @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
  @Transactional
  public void expireLeases() {
    long now = Ids.now();
    for (Task t : tasks.findByStatusIn(TaskStatus.ACTIVE)) {
      if (t.leaseUntil != null && t.leaseUntil < now) {
        if (TaskStatus.DISPATCHED.equals(t.status) && t.startedAt == null) {
          // never started: put it back in the queue for another attempt
          t.status = TaskStatus.QUEUED;
          t.runtimeId = null;
          t.leaseUntil = null;
          t.statusDetail = "re-queued: the runtime did not start it";
          tasks.save(t);
          changed(t);
        } else {
          finish(t, TaskStatus.FAILED, ErrorCodes.RUNTIME_LOST, "lost contact with the runtime", null);
        }
      }
    }
  }

  // ---------------------------------------------------------------- control

  @Transactional
  public Task cancel(String taskId, String reason) {
    Task t = tasks.findById(taskId).orElseThrow(() -> ApiException.notFound("task"));
    if (TaskStatus.TERMINAL.contains(t.status)) {
      return t;
    }
    if (TaskStatus.QUEUED.equals(t.status) || !gateway.connected(t.runtimeId)) {
      finish(t, TaskStatus.CANCELLED, ErrorCodes.CANCELLED, reason, null);
      return t;
    }
    t.statusDetail = "cancelling";
    tasks.save(t);
    changed(t);
    String rt = t.runtimeId;
    afterCommit(() -> gateway.send(rt, Wire.frame(Wire.TASK_CANCEL, new Wire.TaskCancel(taskId, reason))));
    return t;
  }

  public void steer(String taskId, String text) {
    Task t = tasks.findById(taskId).orElseThrow(() -> ApiException.notFound("task"));
    if (!TaskStatus.ACTIVE.contains(t.status)) {
      throw ApiException.bad("the task is not running");
    }
    if (!gateway.send(t.runtimeId, Wire.frame(Wire.TASK_STEER, new Wire.TaskSteer(taskId, text)))) {
      throw ApiException.bad("the runtime is not connected");
    }
  }

  public boolean answer(String taskId, String requestId, Map<String, Object> answer) {
    Task t = tasks.findById(taskId).orElse(null);
    return t != null && gateway.send(t.runtimeId, Wire.frame(Wire.TASK_INPUT, new Wire.TaskInput(taskId, requestId, answer)));
  }

  // ---------------------------------------------------------------- queries

  public Task get(String id) {
    return tasks.findById(id).orElseThrow(() -> ApiException.notFound("task"));
  }

  public List<TaskEvent> events(String taskId, long after) {
    return events.findByTaskIdAndSeqGreaterThanOrderBySeqAsc(taskId, after);
  }

  public List<Task> forIssue(String issueId) {
    return tasks.findByIssueIdOrderByQueuedAtAsc(issueId);
  }

  public List<Task> recent() {
    return tasks.findTop100ByOrderByQueuedAtDesc();
  }

  public List<Task> forAgent(String agentId) {
    return tasks.findByAgentIdOrderByQueuedAtDesc(agentId);
  }

  public long active(String runtimeId) {
    return tasks.countByRuntimeIdAndStatusIn(runtimeId, TaskStatus.ACTIVE);
  }

  private void changed(Task t) {
    realtime.publish("task.updated", Realtime.ids("id", t.id, "issueId", t.issueId, "status", t.status,
        "agentId", t.agentId));
  }

  static void afterCommit(Runnable r) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          r.run();
        }
      });
    } else {
      r.run();
    }
  }

  public static Map<String, Object> view(Task t) {
    return Json.toMap(Views.TaskView.of(t));
  }

  static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      m.put(String.valueOf(kv[i]), kv[i + 1]);
    }
    return m;
  }
}
