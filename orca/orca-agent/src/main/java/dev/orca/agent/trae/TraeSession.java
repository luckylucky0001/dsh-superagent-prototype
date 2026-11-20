package dev.orca.agent.trae;

import dev.orca.agent.core.AgentEventSink;
import dev.orca.agent.core.AgentException;
import dev.orca.agent.core.AgentSession;
import dev.orca.agent.core.Capability;
import dev.orca.agent.core.Interaction;
import dev.orca.agent.core.Interaction.ApprovalKind;
import dev.orca.agent.core.Interaction.Decision;
import dev.orca.agent.core.JsonRpcConnection;
import dev.orca.agent.core.JsonRpcConnection.RpcError;
import dev.orca.agent.core.SessionSpec;
import dev.orca.agent.core.StructuredOutput;
import dev.orca.agent.core.Truncate;
import dev.orca.agent.core.TurnResult;
import dev.orca.agent.core.TurnSpec;
import dev.orca.protocol.AgentEvent;
import dev.orca.protocol.ErrorCodes;
import dev.orca.protocol.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** One {@code traecli acp serve} process holding one ACP session. */
final class TraeSession implements AgentSession {

  /** Stops while the agent waits for a person, so that time does not count against the turn. */
  private final dev.orca.agent.core.TurnClock clock = new dev.orca.agent.core.TurnClock();
  private final JsonRpcConnection conn;
  private final Interaction.Handler handler;
  private final SessionSpec spec;
  private final Duration timeout;
  private final Set<String> capabilities;
  private final Map<String, String> toolTitles = new ConcurrentHashMap<>();
  private final Map<String, String> toolKinds = new ConcurrentHashMap<>();
  private final AtomicInteger requestIds = new AtomicInteger();

  private String sessionId;
  private String model;
  private boolean resumed;
  private boolean instructionsSent;
  private volatile boolean userCancelled;
  private volatile String declinedInTurn;
  private long taskTokens;
  private String effortNotice;

  private volatile boolean streaming;
  private volatile AgentEventSink sink = e -> {};
  private final StringBuilder segment = new StringBuilder();
  private volatile int segmentNo;
  private volatile Map<String, Object> usage = Map.of();

  private TraeSession(JsonRpcConnection conn, Interaction.Handler handler, SessionSpec spec, Duration timeout,
      Set<String> capabilities) {
    this.conn = conn;
    this.handler = handler;
    this.spec = spec;
    this.timeout = timeout;
    this.capabilities = capabilities;
  }

  /** Name of the permission profile Orca defines for the workspace tier. */
  static final String WORKSPACE_PROFILE = "orca_workspace";

  /** Internal capability: {@code session/new} accepts {@code systemPrompt}. */
  static final String SYSTEM_PROMPT = "system_prompt";

  /** How often a turn that TRAE ended because a request was declined is continued automatically. */
  private static final int MAX_DECLINE_CONTINUATIONS = 3;

  static TraeSession open(TraeProvider provider, SessionSpec spec, Interaction.Handler handler) throws AgentException {
    List<String> args = new ArrayList<>();
    String cwd = spec.cwd().toAbsolutePath().toString();
    args.add("acp");
    args.add("serve");
    if (spec.model() != null && !spec.model().isBlank()) {
      args.add("-c");
      args.add("model=\"" + spec.model().replace("\"", "") + "\"");
    }
    if (spec.effort() != null && !spec.effort().isBlank()) {
      args.add("-c");
      args.add("model_reasoning_effort=\"" + spec.effort().replace("\"", "") + "\"");
    }
    args.add("-c");
    args.add("projects.\"" + cwd.replace("\"", "") + "\".trust_level=\"trusted\"");
    switch (spec.permission()) {
      case READ_ONLY -> {
        // verified: no writes anywhere, no network
        args.add("-c");
        args.add("default_permissions=\":read-only\"");
      }
      case WORKSPACE -> {
        // TRAE's built-in :workspace profile allows network; a profile of our own extends it and turns
        // the network off unless the agent asks for it and the machine owner allows it (verified live)
        args.add("-c");
        args.add("default_permissions=\"" + WORKSPACE_PROFILE + "\"");
        args.add("-c");
        args.add("permissions." + WORKSPACE_PROFILE + ".extends=\":workspace\"");
        args.add("-c");
        args.add("permissions." + WORKSPACE_PROFILE + ".network.enabled=" + spec.network());
        for (String allowed : spec.allowedCommands()) {
          args.add("--allowed-tool");
          args.add("Bash(" + allowed.replace(")", "") + ")");
        }
      }
      case FULL -> args.add("--yolo");
    }
    for (String a : spec.extraArgs()) {
      if (a.equals("acp") || a.equals("serve") || a.equals("--yolo") || a.startsWith("--dangerously")) {
        throw new AgentException(ErrorCodes.PROTOCOL, "argument not allowed for traecli: " + a);
      }
      args.add(a);
    }
    Process process;
    try {
      Files.createDirectories(spec.cwd());
      process = provider.start(provider.baseCommand(), spec.cwd(), spec.env(), args);
    } catch (IOException e) {
      throw new AgentException(ErrorCodes.PROVIDER_NOT_INSTALLED, "could not start traecli: " + e.getMessage(), e);
    }
    JsonRpcConnection conn = new JsonRpcConnection(process, "traecli", true);
    try {
      Object init = provider.initialize(conn, provider.config().requestTimeout());
      Set<String> caps = new HashSet<>(TraeProvider.capabilities(init));
      caps.add(Capability.STRUCTURED_OUTPUT);
      caps.add(Capability.USAGE);
      TraeSession s = new TraeSession(conn, handler, spec, provider.config().requestTimeout(), caps);
      conn.onNotification(s::onNotification);
      conn.onRequest(s::onRequest);
      s.startSession();
      return s;
    } catch (AgentException e) {
      String tail = conn.stderrTail();
      conn.close();
      if (e.getMessage() != null && e.getMessage().contains("Authentication required")) {
        throw new AgentException(ErrorCodes.AUTH_REQUIRED, "TRAE CLI is not logged in on this runtime", e);
      }
      if (e.getMessage() != null && e.getMessage().contains("is unavailable because its metadata")) {
        throw new AgentException(ErrorCodes.MODEL_NOT_FOUND, "TRAE CLI cannot use model " + spec.model()
            + " with this account; pick one from the runtime's model list", e);
      }
      throw new AgentException(e.code(), e.getMessage() + (tail.isBlank() ? "" : "\n" + tail), e);
    }
  }

  private void startSession() throws AgentException {
    List<Object> mcp = new ArrayList<>();
    if (spec.mcpUrl() != null && capabilities.contains(Capability.MCP_HTTP)) {
      Map<String, Object> server = new LinkedHashMap<>();
      server.put("type", "http");
      server.put("name", "orca");
      server.put("url", spec.mcpUrl());
      server.put("headers", spec.mcpToken() == null ? List.of()
          : List.of(Map.of("name", "Authorization", "value", "Bearer " + spec.mcpToken())));
      mcp.add(server);
    }
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("cwd", spec.cwd().toAbsolutePath().toString());
    params.put("mcpServers", mcp);
    Object result;
    if (spec.resumeRef() != null) {
      if (!capabilities.contains(Capability.RESUME)) {
        throw new AgentException(ErrorCodes.SESSION_NOT_FOUND, "this TRAE CLI cannot resume sessions");
      }
      params.put("sessionId", spec.resumeRef());
      try {
        result = conn.call("session/load", params, timeout);
      } catch (AgentException e) {
        throw new AgentException(ErrorCodes.SESSION_NOT_FOUND, "traecli could not load session " + spec.resumeRef()
            + ": " + e.getMessage(), e);
      }
      sessionId = spec.resumeRef();
      resumed = true;
      instructionsSent = true;
    } else {
      if (capabilities.contains(SYSTEM_PROMPT) && spec.instructions() != null && !spec.instructions().isBlank()) {
        // the agent's instructions and skills become part of the system prompt (verified live)
        params.put("systemPrompt", Map.of("mode", "append",
            "content", List.of(Map.of("type", "text", "text", spec.instructions()))));
        instructionsSent = true;
      }
      result = conn.call("session/new", params, timeout);
      sessionId = Json.str(result, "sessionId");
    }
    if (sessionId == null) {
      throw new AgentException(ErrorCodes.PROTOCOL, "traecli returned no session id");
    }
    model = selectModel(result);
  }

  /**
   * Picks the agent's model through {@code session/set_config_option}. TRAE model ids differ from their
   * display names ({@code Doubao_1_6} is "Doubao-Seed-Code"); either is accepted here.
   */
  private String selectModel(Object result) throws AgentException {
    Map<String, Object> option = null;
    for (Object o : Json.list(result, "configOptions")) {
      if ("model".equals(Json.str(o, "id")) && o instanceof Map<?, ?>) {
        option = Json.obj(Map.of("o", o), "o");
      }
    }
    String current = option == null ? Json.str(result, "models.currentModelId") : Json.str(option, "currentValue");
    String wanted = spec.model();
    if (wanted == null || wanted.isBlank() || option == null) {
      return current != null ? current : wanted;
    }
    String id = null;
    for (Object o : Json.list(option, "options")) {
      String value = Json.str(o, "value");
      if (wanted.equalsIgnoreCase(value) || wanted.equalsIgnoreCase(Json.str(o, "name"))) {
        id = value;
      }
    }
    if (id == null) {
      List<String> names = new ArrayList<>();
      for (Object o : Json.list(option, "options")) {
        names.add(Json.str(o, "name"));
      }
      throw new AgentException(ErrorCodes.MODEL_NOT_FOUND, "TRAE CLI has no model " + wanted + "; available: "
          + String.join(", ", names));
    }
    Object options = result;
    if (!id.equals(current)) {
      options = conn.call("session/set_config_option", Map.of("sessionId", sessionId, "configId", "model", "value", id),
          timeout);
    }
    selectEffort(options);
    return id;
  }

  /**
   * Reasoning effort is a per-model config option in TRAE ({@code low/high/xhigh} for GLM-5.3, none for
   * some fast models); a value the model does not offer would be ignored silently, so it is checked here.
   */
  private void selectEffort(Object configResult) throws AgentException {
    String wanted = spec.effort();
    if (wanted == null || wanted.isBlank()) {
      return;
    }
    for (Object o : Json.list(configResult, "configOptions")) {
      if (!"reasoning_effort".equals(Json.str(o, "id"))) {
        continue;
      }
      for (Object v : Json.list(o, "options")) {
        if (wanted.equalsIgnoreCase(Json.str(v, "value"))) {
          if (!wanted.equalsIgnoreCase(Json.str(o, "currentValue"))) {
            conn.call("session/set_config_option", Map.of("sessionId", sessionId, "configId", "reasoning_effort",
                "value", Json.str(v, "value")), timeout);
          }
          return;
        }
      }
      effortNotice = "reasoning effort " + wanted + " is not offered by this model; it uses "
          + Json.str(o, "currentValue");
      return;
    }
    effortNotice = "this model has no reasoning-effort setting; " + wanted + " is ignored";
  }

  @Override
  public String sessionRef() {
    return sessionId;
  }

  @Override
  public String model() {
    return model;
  }

  @Override
  public boolean resumed() {
    return resumed;
  }

  @Override
  public Set<String> capabilities() {
    return capabilities;
  }

  @Override
  public synchronized TurnResult runTurn(TurnSpec turn, AgentEventSink eventSink) throws AgentException {
    sink = eventSink;
    usage = Map.of();
    synchronized (segment) {
      segment.setLength(0);
    }
    segmentNo++;
    StringBuilder text = new StringBuilder();
    if (!instructionsSent && spec.instructions() != null && !spec.instructions().isBlank()) {
      text.append(spec.instructions()).append("\n\n---\n\n");
    }
    instructionsSent = true;
    text.append(turn.text());
    if (turn.outputSchema() != null) {
      text.append("\n\nReply with only a JSON value (no prose, no code fence) that validates against this JSON Schema:\n")
          .append(Json.write(turn.outputSchema()));
    }
    List<Map<String, Object>> prompt = new ArrayList<>();
    prompt.add(Map.of("type", "text", "text", text.toString()));
    if (capabilities.contains(Capability.IMAGES)) {
      for (Path image : turn.images()) {
        try {
          prompt.add(Map.of("type", "image", "mimeType", mime(image),
              "data", Base64.getEncoder().encodeToString(Files.readAllBytes(image))));
        } catch (IOException e) {
          eventSink.accept(AgentEvent.of(AgentEvent.NOTICE, "level", "warn", "message", "could not attach " + image));
        }
      }
    }
    String turnId = "turn-" + segmentNo;
    eventSink.accept(AgentEvent.of(AgentEvent.TURN_STARTED, "turnId", turnId));
    if (effortNotice != null) {
      eventSink.accept(AgentEvent.of(AgentEvent.NOTICE, "level", "warn", "message", effortNotice));
      effortNotice = null;
    }
    streaming = true;
    clock.reset();
    TurnResult.Status status = TurnResult.Status.COMPLETED;
    String code = null;
    String error = null;
    userCancelled = false;
    try {
      Object result = null;
      String stop = null;
      for (int attempt = 0; ; attempt++) {
        declinedInTurn = null;
        CompletableFuture<Object> f = conn.request("session/prompt", Map.of("sessionId", sessionId, "prompt", prompt));
        result = clock.await(f, turn.timeout());
        stop = Json.str(result, "stopReason");
        recordUsage(result);
        // TRAE ends the whole turn when a request is declined ("No, and tell TRAE CLI what to do
        // differently"); Codex keeps going. Continue the same session so both behave alike.
        if ("cancelled".equals(stop) && declinedInTurn != null && !userCancelled && attempt < MAX_DECLINE_CONTINUATIONS) {
          eventSink.accept(AgentEvent.of(AgentEvent.NOTICE, "level", "info", "message",
              "the request was declined; asking TRAE CLI to continue without it"));
          prompt = List.of(Map.of("type", "text", "text", "Your request to run `" + declinedInTurn
              + "` was declined and will not be approved. Continue the task without it: find another way within your"
              + " permissions, or finish and explain clearly what you could not do and why."));
          continue;
        }
        break;
      }
      if ("cancelled".equals(stop)) {
        status = TurnResult.Status.INTERRUPTED;
        code = ErrorCodes.CANCELLED;
      } else if ("refusal".equals(stop)) {
        status = TurnResult.Status.FAILED;
        code = ErrorCodes.AGENT_ERROR;
        error = "the agent refused the request";
      } else if ("max_tokens".equals(stop)) {
        status = TurnResult.Status.FAILED;
        code = ErrorCodes.CONTEXT_EXCEEDED;
        error = "the agent ran out of tokens";
      }
    } catch (TimeoutException e) {
      interrupt();
      status = TurnResult.Status.FAILED;
      code = ErrorCodes.TIMEOUT;
      error = "turn timed out after " + turn.timeout();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      interrupt();
      status = TurnResult.Status.INTERRUPTED;
      code = ErrorCodes.CANCELLED;
    } catch (Exception e) {
      Throwable c = e.getCause() == null ? e : e.getCause();
      status = TurnResult.Status.FAILED;
      String msg = String.valueOf(c.getMessage());
      code = msg.contains("Authentication required") ? ErrorCodes.AUTH_REQUIRED
          : c instanceof RpcError ? ErrorCodes.AGENT_ERROR : ErrorCodes.PROVIDER_UNAVAILABLE;
      error = msg + (conn.alive() ? "" : "\n" + conn.stderrTail());
    }
    streaming = false;
    String finalText;
    synchronized (segment) {
      finalText = segment.toString().trim();
    }
    if (!finalText.isEmpty()) {
      eventSink.accept(AgentEvent.of(AgentEvent.MESSAGE, "itemId", "msg-" + segmentNo, "text", finalText,
          "phase", "final_answer"));
    }
    Object structured = null;
    if (status == TurnResult.Status.COMPLETED && turn.outputSchema() != null) {
      try {
        structured = StructuredOutput.parse(finalText);
      } catch (IllegalArgumentException e) {
        status = TurnResult.Status.FAILED;
        code = ErrorCodes.INVALID_OUTPUT;
        error = "the final answer is not valid JSON: " + e.getMessage();
      }
    }
    if (!usage.isEmpty()) {
      eventSink.accept(new AgentEvent(0, System.currentTimeMillis(), AgentEvent.USAGE, usage));
    }
    eventSink.accept(AgentEvent.of(AgentEvent.TURN_COMPLETED, "turnId", turnId, "status",
        status.name().toLowerCase(), "errorCode", code, "error", error));
    return new TurnResult(status, finalText, structured, code, error, usage);
  }

  /**
   * Token usage from a prompt result ({@code _meta._trae/tokenUsage}): session totals in the Codex field
   * names, plus {@code taskTokens}, the tokens of this task's turns only (a resumed session's earlier
   * turns are not counted again).
   */
  private void recordUsage(Object result) {
    Map<String, Object> meta = Json.obj(Json.obj(result, "_meta"), "_trae/tokenUsage");
    Map<String, Object> total = Json.obj(meta, "sessionTotal");
    Map<String, Object> turnUsage = meta.isEmpty() ? Json.obj(result, "usage") : Json.obj(meta, "turn");
    if (total.isEmpty()) {
      total = Json.obj(result, "usage");
    }
    if (total.isEmpty()) {
      return;
    }
    taskTokens += Json.num(turnUsage, "totalTokens", 0);
    Map<String, Object> u = new LinkedHashMap<>();
    u.put("inputTokens", Json.num(total, "inputTokens", 0));
    u.put("cachedInputTokens", Json.num(total, "cachedReadTokens", 0));
    u.put("outputTokens", Json.num(total, "outputTokens", 0));
    u.put("reasoningTokens", Json.num(total, "thoughtTokens", 0));
    u.put("totalTokens", Json.num(total, "totalTokens", 0));
    u.put("taskTokens", taskTokens);
    long window = Json.num(Json.obj(meta, "contextWindow"), "size", 0);
    if (window > 0) {
      u.put("contextWindow", window);
      u.put("contextUsed", Json.num(Json.obj(meta, "contextWindow"), "used", 0));
    }
    usage = u;
  }

  /**
   * Not available: TRAE CLI 0.216 declares {@code steering.supported} but has no ACP method for it
   * ({@code _session/steer} is "Method not found") and refuses a second {@code session/prompt} while one
   * runs. Orca therefore sends steering text with the next turn (verified live).
   */
  @Override
  public void steer(String text) {
    throw new UnsupportedOperationException("TRAE CLI does not accept steering over ACP; the text is sent next turn");
  }

  @Override
  public void interrupt() {
    userCancelled = true;
    if (sessionId != null && conn.alive()) {
      try {
        conn.notify("session/cancel", Map.of("sessionId", sessionId));
      } catch (IOException ignored) {
        // process already gone
      }
    }
  }

  @Override
  public void close() {
    conn.close();
  }

  // ---------------------------------------------------------------- protocol callbacks

  private void onNotification(String method, Map<String, Object> params) {
    if (!"session/update".equals(method) || !streaming) {
      return; // history replayed by session/load arrives before the prompt and is not re-emitted
    }
    Map<String, Object> u = Json.obj(params, "update");
    String kind = Json.str(u, "sessionUpdate");
    if (kind == null) {
      return;
    }
    switch (kind) {
      case "agent_message_chunk" -> {
        String t = Json.str(u, "content.text");
        if (t != null) {
          synchronized (segment) {
            segment.append(t);
          }
          sink.accept(AgentEvent.of(AgentEvent.MESSAGE_DELTA, "itemId", "msg-" + segmentNo, "text", t));
        }
      }
      case "agent_thought_chunk" -> {
        String t = Json.str(u, "content.text");
        if (t != null) {
          sink.accept(AgentEvent.of(AgentEvent.REASONING_DELTA, "itemId", "thought-" + segmentNo, "text", t));
        }
      }
      case "tool_call" -> {
        String id = Json.str(u, "toolCallId");
        String title = Json.str(u, "title");
        String k = toolKind(Json.str(u, "kind"));
        toolTitles.put(String.valueOf(id), String.valueOf(title));
        toolKinds.put(String.valueOf(id), k);
        flushSegment();
        sink.accept(AgentEvent.of(AgentEvent.TOOL_STARTED, "itemId", id, "kind", k, "title", title,
            "input", u.get("rawInput")));
      }
      case "tool_call_update" -> {
        String id = Json.str(u, "toolCallId");
        String status = Json.str(u, "status");
        String output = contentText(u);
        if ("completed".equals(status) || "failed".equals(status)) {
          String title = Json.str(u, "title");
          sink.accept(AgentEvent.of(AgentEvent.TOOL_COMPLETED, "itemId", id,
              "kind", toolKinds.getOrDefault(String.valueOf(id), "other"),
              "title", title != null ? title : toolTitles.get(String.valueOf(id)),
              "status", status, "output", Truncate.middle(output, Truncate.TOOL_OUTPUT), "changes", diffs(u)));
        } else if (output != null && !output.isBlank()) {
          sink.accept(AgentEvent.of(AgentEvent.TOOL_OUTPUT, "itemId", id, "text", output));
        }
      }
      case "plan" -> {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object e : Json.list(u, "entries")) {
          String st = Json.str(e, "status");
          entries.add(Map.of("step", String.valueOf(Json.str(e, "content")),
              "status", "in_progress".equals(st) ? "in_progress" : st == null ? "pending" : st));
        }
        sink.accept(AgentEvent.of(AgentEvent.PLAN, "entries", entries));
      }
      case "usage_update" -> {
        // context-window occupancy while the turn runs; token counts arrive with the prompt result
        long used = Json.num(u, "used", -1);
        long size = Json.num(u, "size", 0);
        if (used >= 0 && size > 0) {
          Map<String, Object> m = new LinkedHashMap<>(usage);
          m.put("contextUsed", used);
          m.put("contextWindow", size);
          usage = m;
        }
      }
      default -> {
        // available_commands_update, current_mode_update, session_info_update, …
      }
    }
  }

  private void flushSegment() {
    synchronized (segment) {
      if (segment.length() > 0) {
        sink.accept(AgentEvent.of(AgentEvent.MESSAGE, "itemId", "msg-" + segmentNo, "text", segment.toString(),
            "phase", "commentary"));
        segment.setLength(0);
      }
    }
    segmentNo++;
  }

  private Object onRequest(String method, Map<String, Object> p) throws RpcError {
    return switch (method) {
      case "session/request_permission" -> {
        Map<String, Object> call = Json.obj(p, "toolCall");
        String kind = toolKind(Json.str(call, "kind"));
        ApprovalKind ak = switch (kind) {
          case "command" -> ApprovalKind.COMMAND;
          case "file_change" -> ApprovalKind.FILE_CHANGE;
          default -> ApprovalKind.TOOL;
        };
        Object raw = call.get("rawInput");
        String command = ak == ApprovalKind.COMMAND ? firstString(raw, "command", "cmd") : null;
        String cwd = firstString(raw, "cwd");
        String reason = firstString(raw, "reason");
        List<String> paths = new ArrayList<>();
        for (Object l : Json.list(call, "locations")) {
          if (Json.str(l, "path") != null) {
            paths.add(Json.str(l, "path"));
          }
        }
        String title = Json.str(call, "title");
        Interaction.ApprovalRequest r = new Interaction.ApprovalRequest("req-" + requestIds.incrementAndGet(), ak,
            title, command, cwd != null ? cwd : spec.cwd().toString(), paths, reason, p);
        sink.accept(AgentEvent.of(AgentEvent.APPROVAL_REQUESTED, "requestId", r.requestId(), "kind",
            ak.name().toLowerCase(), "title", title, "command", command));
        Decision d;
        try {
          d = clock.paused(() -> handler.approve(r));
        } catch (RuntimeException e) {
          d = Decision.DECLINE;
        }
        sink.accept(AgentEvent.of(AgentEvent.APPROVAL_RESOLVED, "requestId", r.requestId(), "decision",
            d.name().toLowerCase(), "by", "policy"));
        if (d == Decision.DECLINE || d == Decision.CANCEL) {
          declinedInTurn = command != null ? command : title;
        }
        yield permissionOutcome(Json.list(p, "options"), d);
      }
      case "elicitation/create" -> {
        Interaction.InputRequest r = new Interaction.InputRequest("req-" + requestIds.incrementAndGet(),
            String.valueOf(Json.str(p, "message")), List.of(), Json.obj(p, "requestedSchema"));
        sink.accept(AgentEvent.of(AgentEvent.INPUT_REQUESTED, "requestId", r.requestId(), "title", r.title(),
            "schema", r.schema()));
        Interaction.InputReply reply;
        try {
          reply = clock.paused(() -> handler.input(r));
        } catch (RuntimeException e) {
          reply = Interaction.InputReply.decline();
        }
        sink.accept(AgentEvent.of(AgentEvent.INPUT_RESOLVED, "requestId", r.requestId(), "declined", reply.declined()));
        if (reply.declined()) {
          yield Map.of("action", "decline");
        }
        Map<String, Object> content = new LinkedHashMap<>();
        reply.answers().forEach((k, v) -> content.put(k, v.size() == 1 ? v.get(0) : v));
        yield Map.of("action", "accept", "content", content);
      }
      default -> throw new RpcError(-32601, "orca does not support " + method, null);
    };
  }

  static Map<String, Object> permissionOutcome(List<Object> options, Decision d) {
    List<String> wanted = switch (d) {
      case ACCEPT -> List.of("allow_once", "allow_always");
      case ACCEPT_FOR_SESSION -> List.of("allow_always", "allow_once");
      case DECLINE -> List.of("reject_once", "reject_always");
      case CANCEL -> List.of();
    };
    for (String w : wanted) {
      for (Object o : options) {
        if (w.equals(Json.str(o, "kind"))) {
          return Map.of("outcome", Map.of("outcome", "selected", "optionId", Json.str(o, "optionId")));
        }
      }
    }
    return Map.of("outcome", Map.of("outcome", "cancelled"));
  }

  static String toolKind(String acpKind) {
    if (acpKind == null) {
      return "other";
    }
    return switch (acpKind) {
      case "execute" -> "command";
      case "edit", "delete", "move" -> "file_change";
      case "fetch" -> "web_search";
      default -> "other";
    };
  }

  private static String contentText(Map<String, Object> u) {
    StringBuilder sb = new StringBuilder();
    for (Object c : Json.list(u, "content")) {
      String t = Json.str(c, "content.text");
      if (t == null) {
        t = Json.str(c, "text");
      }
      if (t != null) {
        sb.append(t);
      }
    }
    if (sb.isEmpty() && u.get("rawOutput") != null) {
      Object raw = u.get("rawOutput");
      sb.append(raw instanceof String s ? s : Json.write(raw));
    }
    return sb.isEmpty() ? null : sb.toString();
  }

  private static List<Map<String, Object>> diffs(Map<String, Object> u) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object c : Json.list(u, "content")) {
      if ("diff".equals(Json.str(c, "type"))) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", Json.str(c, "path"));
        m.put("kind", Json.str(c, "oldText") == null ? "add" : "update");
        m.put("diff", Truncate.middle(Json.str(c, "newText"), Truncate.TOOL_OUTPUT));
        out.add(m);
      }
    }
    return out.isEmpty() ? null : out;
  }

  private static String firstString(Object map, String... keys) {
    for (String k : keys) {
      Object v = Json.get(map, k);
      if (v instanceof String s) {
        return s;
      }
      if (v instanceof List<?> l) {
        return String.join(" ", l.stream().map(String::valueOf).toList());
      }
    }
    return null;
  }

  private static String mime(Path p) {
    String n = p.getFileName().toString().toLowerCase();
    return n.endsWith(".jpg") || n.endsWith(".jpeg") ? "image/jpeg" : n.endsWith(".gif") ? "image/gif"
        : n.endsWith(".webp") ? "image/webp" : "image/png";
  }
}
