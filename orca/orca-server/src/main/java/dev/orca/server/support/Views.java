package dev.orca.server.support;

import dev.orca.protocol.Json;
import dev.orca.protocol.ProviderStatus;
import dev.orca.server.model.Agent;
import dev.orca.server.model.Comment;
import dev.orca.server.model.InboxItem;
import dev.orca.server.model.Issue;
import dev.orca.server.model.Project;
import dev.orca.server.model.RuntimeHost;
import dev.orca.server.model.Task;
import dev.orca.server.model.TaskEvent;
import dev.orca.server.model.Workflow;
import dev.orca.server.model.WorkflowRun;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;

/** JSON shapes returned by the API; JSON columns are expanded into objects. */
public final class Views {
  private Views() {}

  public record ProjectView(String id, String key, String name, String description, String repoUrl,
      String defaultBranch, long createdAt) {
    public static ProjectView of(Project p) {
      return new ProjectView(p.id, p.keyPrefix, p.name, p.description, p.repoUrl, p.defaultBranch, p.createdAt);
    }
  }

  public record IssueView(String id, String key, String projectId, int number, String title, String description,
      String status, String priority, String assigneeType, String assigneeId, List<String> labels, String createdBy,
      long createdAt, long updatedAt) {
    public static IssueView of(Issue i) {
      return new IssueView(i.id, i.issueKey, i.projectId, i.number, i.title, i.description, i.status, i.priority,
          i.assigneeType, i.assigneeId, JsonCols.strings(i.labelsJson), i.createdBy, i.createdAt, i.updatedAt);
    }
  }

  public record CommentView(String id, String issueId, String authorType, String authorId, String body, String taskId,
      long createdAt) {
    public static CommentView of(Comment c) {
      return new CommentView(c.id, c.issueId, c.authorType, c.authorId, c.body, c.taskId, c.createdAt);
    }
  }

  public record AgentView(String id, String name, String description, String provider, String runtimeId, String model,
      String effort, String instructions, String permission, List<String> allowedCommands, List<String> extraArgs,
      int maxConcurrency, int timeoutMinutes, boolean archived, long createdAt, long updatedAt) {
    public static AgentView of(Agent a) {
      return new AgentView(a.id, a.name, a.description, a.provider, a.runtimeId, a.model, a.effort, a.instructions,
          a.permission, JsonCols.strings(a.allowedCommandsJson), JsonCols.strings(a.extraArgsJson), a.maxConcurrency,
          a.timeoutMinutes, a.archived, a.createdAt, a.updatedAt);
    }
  }

  public record RuntimeView(String id, String name, String hostname, String os, String daemonVersion, int capacity,
      boolean isolated, String status, Long lastSeenAt, List<ProviderStatus> providers, long activeTasks, long createdAt) {
    public static RuntimeView of(RuntimeHost r, long active) {
      return new RuntimeView(r.id, r.name, r.hostname, r.os, r.daemonVersion, r.capacity, r.isolated, r.status,
          r.lastSeenAt, Views.providers(r), active, r.createdAt);
    }
  }

  public static List<ProviderStatus> providers(RuntimeHost r) {
    if (r.providersJson == null || r.providersJson.isBlank()) {
      return List.of();
    }
    return Json.MAPPER.readValue(r.providersJson, new TypeReference<List<ProviderStatus>>() {});
  }

  public record TaskView(String id, String agentId, String runtimeId, String issueId, String provider, String kind,
      Map<String, Object> origin, String prompt, String status, String statusDetail, String sessionRef, String resumeRef,
      String model, String finalText, Object structured, String errorCode, String error, Map<String, Object> usage,
      long lastSeq, long queuedAt, Long startedAt, Long finishedAt) {
    public static TaskView of(Task t) {
      return new TaskView(t.id, t.agentId, t.runtimeId, t.issueId, t.provider, t.kind, JsonCols.map(t.originJson),
          t.prompt, t.status, t.statusDetail, t.sessionRef, t.resumeRef, t.model, t.finalText,
          JsonCols.any(t.structuredJson), t.errorCode, t.error, JsonCols.map(t.usageJson), t.lastSeq, t.queuedAt,
          t.startedAt, t.finishedAt);
    }
  }

  public record EventView(long seq, long ts, String type, Map<String, Object> data) {
    public static EventView of(TaskEvent e) {
      return new EventView(e.seq, e.ts, e.type, JsonCols.map(e.dataJson));
    }
  }

  public record WorkflowView(String id, String name, String description, boolean enabled, boolean builtIn,
      int currentVersion, Object definition, long createdAt, long updatedAt) {
    public static WorkflowView of(Workflow w) {
      return new WorkflowView(w.id, w.name, w.description, w.enabled, w.builtIn, w.currentVersion,
          JsonCols.any(w.definitionJson), w.createdAt, w.updatedAt);
    }
  }

  public record RunView(String id, String workflowId, String workflowName, int version, String status,
      String triggerNodeId, String issueId, String error, long startedAt, Long finishedAt, Object state, Object definition) {
    public static RunView of(WorkflowRun r, String name, Object definition, boolean withState) {
      return new RunView(r.id, r.workflowId, name, r.version, r.status, r.triggerNodeId, r.issueId, r.error,
          r.startedAt, r.finishedAt, withState ? JsonCols.any(r.stateJson) : null, definition);
    }
  }

  public record InboxView(String id, String kind, String status, String title, String body, Object form, Object answer,
      String taskId, String requestId, String runId, String nodeId, String issueId, long createdAt, Long resolvedAt,
      String resolvedBy) {
    public static InboxView of(InboxItem i) {
      return new InboxView(i.id, i.kind, i.status, i.title, i.body, JsonCols.any(i.formJson), JsonCols.any(i.answerJson),
          i.taskId, i.requestId, i.runId, i.nodeId, i.issueId, i.createdAt, i.resolvedAt, i.resolvedBy);
    }
  }
}
