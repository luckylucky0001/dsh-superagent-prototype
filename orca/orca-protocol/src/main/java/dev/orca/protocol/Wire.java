package dev.orca.protocol;

import java.util.List;
import java.util.Map;

/**
 * Messages on the daemon's WebSocket. Each frame is {@code {"type": ..., "data": {...}}}; the records
 * below are the {@code data} of each type. The daemon always initiates the connection.
 */
public final class Wire {

  private Wire() {}

  public record Frame(String type, Map<String, Object> data) {}

  public static Frame frame(String type, Object data) {
    return new Frame(type, Json.toMap(data));
  }

  // ---------------------------------------------------------------- daemon -> server
  public static final String HELLO = "hello";
  public static final String HEARTBEAT = "heartbeat";
  public static final String PROBE_RESULT = "probe.result";
  public static final String TASK_ACCEPTED = "task.accepted";
  public static final String TASK_STARTED = "task.started";
  public static final String TASK_EVENTS = "task.events";
  public static final String TASK_COMPLETED = "task.completed";

  // ---------------------------------------------------------------- server -> daemon
  public static final String WELCOME = "welcome";
  public static final String TASK_ASSIGN = "task.assign";
  public static final String TASK_CANCEL = "task.cancel";
  public static final String TASK_STEER = "task.steer";
  public static final String TASK_INPUT = "task.input";
  public static final String PROBE = "probe";
  public static final String ACK = "ack";
  public static final String ERROR = "error";

  /** First frame after connecting. {@code running} lists tasks still executing after a reconnect. */
  public record Hello(String name, String hostname, String os, String daemonVersion, int capacity,
      boolean isolated, List<ProviderStatus> providers, List<RunningTask> running) {}

  public record RunningTask(String taskId, long lastSeq) {}

  public record Heartbeat(List<String> running, int freeSlots) {}

  public record ProbeResult(List<ProviderStatus> providers) {}

  public record Welcome(String runtimeId, int heartbeatSeconds, List<String> unknownTasks) {}

  /**
   * Work for the daemon.
   *
   * @param workspaceKey stable directory key, e.g. {@code <agentId>/<issueKey>}; sessions resume in it
   * @param sessionRef   provider session to continue, or null for a new one
   * @param recap        context for a new session when {@code sessionRef} cannot be resumed
   * @param outputSchema JSON Schema the final answer must satisfy, or null
   */
  public record TaskAssign(
      String taskId,
      String provider,
      AgentSpec agent,
      String workspaceKey,
      RepoSpec repo,
      String prompt,
      String recap,
      String sessionRef,
      Map<String, Object> outputSchema,
      int timeoutSeconds,
      McpSpec mcp,
      List<FileRef> images,
      String taskToken,
      Map<String, Object> review) {}

  /** A file the daemon downloads (with the task token) before the first turn, e.g. a screenshot on the issue. */
  public record FileRef(String id, String name, String url) {}

  public record AgentSpec(String id, String name, String model, String effort, String instructions,
      String permission, List<String> allowedCommands, List<String> extraArgs, Map<String, String> env,
      boolean escalateApprovals) {}

  public record RepoSpec(String url, String branch, String baseBranch) {}

  public record McpSpec(String url, String token) {}

  public record TaskAccepted(String taskId) {}

  public record TaskStarted(String taskId, String sessionRef, String model, boolean resumed) {}

  public record TaskEvents(String taskId, List<AgentEvent> events) {}

  /**
   * @param status {@code succeeded}, {@code failed}, {@code cancelled} or {@code timed_out}
   */
  public record TaskCompleted(String taskId, String status, String finalText, Object structured,
      String sessionRef, String errorCode, String error, Map<String, Object> usage, long lastSeq) {}

  public record TaskCancel(String taskId, String reason) {}

  public record TaskSteer(String taskId, String text) {}

  public record TaskInput(String taskId, String requestId, Map<String, Object> answer) {}

  /** {@code completed}: the server stored the task's completion, so the daemon may forget the task. */
  public record Ack(String taskId, long seq, boolean completed) {}

  public record Error(String message) {}
}
