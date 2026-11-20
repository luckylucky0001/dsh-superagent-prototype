package dev.orca.agent.codex;

import dev.orca.agent.core.AgentEventSink;
import dev.orca.agent.core.AgentException;
import dev.orca.agent.core.AgentSession;
import dev.orca.agent.core.Interaction;
import dev.orca.agent.core.Interaction.ApprovalKind;
import dev.orca.agent.core.Interaction.ApprovalRequest;
import dev.orca.agent.core.Interaction.Decision;
import dev.orca.agent.core.JsonRpcConnection;
import dev.orca.agent.core.JsonRpcConnection.RpcError;
import dev.orca.agent.core.Permission;
import dev.orca.agent.core.SessionSpec;
import dev.orca.agent.core.StructuredOutput;
import dev.orca.agent.core.TurnResult;
import dev.orca.agent.core.TurnSpec;
import dev.orca.protocol.AgentEvent;
import dev.orca.protocol.ErrorCodes;
import dev.orca.protocol.Json;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One {@code codex app-server} process holding one thread. */
final class CodexSession implements AgentSession {

  private static final Logger log = LoggerFactory.getLogger(CodexSession.class);
  private static final String TOKEN_ENV = "ORCA_TASK_TOKEN";
  private static final Set<String> BLOCKED_ARGS = Set.of("app-server", "--listen", "exec", "login", "logout");

  private final JsonRpcConnection conn;
  private final Interaction.Handler handler;
  private final SessionSpec spec;
  private final Duration timeout;
  private final BufferedWriter recorder;
  private final Map<String, List<String>> filePaths = new ConcurrentHashMap<>();
  private final Map<String, Map<String, Object>> completedTurns = new ConcurrentHashMap<>();
  private final AtomicInteger requestIds = new AtomicInteger();

  private String threadId;
  private String model;
  private boolean resumed;

  // state of the running turn, guarded by "this"
  private volatile AgentEventSink sink = e -> {};
  private volatile String turnId;
  private volatile CompletableFuture<Map<String, Object>> turnDone;
  private volatile String finalText;
  private volatile String lastFinalEmitted;
  private volatile String lastMessage;
  private volatile Map<String, Object> usage = Map.of();
  private long baseline = -1;

  private CodexSession(JsonRpcConnection conn, Interaction.Handler handler, SessionSpec spec, Duration timeout,
      BufferedWriter recorder) {
    this.conn = conn;
    this.handler = handler;
    this.spec = spec;
    this.timeout = timeout;
    this.recorder = recorder;
  }

  static CodexSession open(CodexProvider provider, SessionSpec spec, Interaction.Handler handler) throws AgentException {
    List<String> base = provider.baseCommand();
    Map<String, String> env = new LinkedHashMap<>(spec.env());
    if (spec.mcpUrl() != null && spec.mcpToken() != null) {
      env.put(TOKEN_ENV, spec.mcpToken());
    }
    List<String> extra = new ArrayList<>();
    for (String a : spec.extraArgs()) {
      if (BLOCKED_ARGS.contains(a)) {
        throw new AgentException(ErrorCodes.PROTOCOL, "argument not allowed for codex: " + a);
      }
      extra.add(a);
    }
    Process process;
    try {
      Files.createDirectories(spec.cwd());
      process = provider.start(base, spec.cwd(), env, extra);
    } catch (IOException e) {
      throw new AgentException(ErrorCodes.PROVIDER_NOT_INSTALLED, "could not start codex: " + e.getMessage(), e);
    }
    JsonRpcConnection conn = new JsonRpcConnection(process, "codex", false);
    BufferedWriter rec = recorder(provider.config().recordDir());
    CodexSession s = new CodexSession(conn, handler, spec, provider.config().requestTimeout(), rec);
    if (rec != null) {
      conn.onRaw(s::record);
    }
    conn.onNotification(s::onNotification);
    conn.onRequest(s::onRequest);
    try {
      provider.initialize(conn, s.timeout);
      s.startThread();
      return s;
    } catch (AgentException | IOException e) {
      String tail = conn.stderrTail();
      s.close();
      if (e instanceof AgentException ae) {
        if (ErrorCodes.SESSION_NOT_FOUND.equals(ae.code())) {
          throw ae;
        }
        throw new AgentException(ae.code(), ae.getMessage() + (tail.isBlank() ? "" : "\n" + tail), ae);
      }
      throw new AgentException(ErrorCodes.PROVIDER_UNAVAILABLE, "codex failed to start: " + e.getMessage(), e);
    }
  }

  private void startThread() throws AgentException {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("cwd", spec.cwd().toString());
    if (spec.model() != null && !spec.model().isBlank()) {
      params.put("model", spec.model());
    }
    switch (spec.permission()) {
      case READ_ONLY -> {
        // "on-request" keeps the model aware that it may run read-only commands; the daemon's policy
        // declines every escalation for this tier. With "never" small models tend to assume no tools.
        params.put("sandbox", "read-only");
        params.put("approvalPolicy", "on-request");
      }
      case WORKSPACE -> {
        params.put("sandbox", "workspace-write");
        params.put("approvalPolicy", "on-request");
      }
      case FULL -> {
        params.put("sandbox", "danger-full-access");
        params.put("approvalPolicy", "never");
      }
    }
    if (spec.instructions() != null && !spec.instructions().isBlank()) {
      params.put("developerInstructions", spec.instructions());
    }
    if (spec.mcpUrl() != null) {
      Map<String, Object> server = new LinkedHashMap<>();
      server.put("url", spec.mcpUrl());
      if (spec.mcpToken() != null) {
        server.put("bearer_token_env_var", TOKEN_ENV);
      }
      params.put("config", Map.of("mcp_servers", Map.of("orca", server)));
    }
    params.put("serviceName", "orca");
    Object result;
    if (spec.resumeRef() != null) {
      params.put("threadId", spec.resumeRef());
      params.put("excludeTurns", true);
      try {
        result = conn.call("thread/resume", params, timeout);
      } catch (AgentException e) {
        throw new AgentException(ErrorCodes.SESSION_NOT_FOUND, "codex could not resume thread " + spec.resumeRef()
            + ": " + e.getMessage(), e);
      }
      resumed = true;
    } else {
      result = conn.call("thread/start", params, timeout);
    }
    Map<String, Object> thread = Json.obj(result, "thread");
    threadId = Json.str(thread, "id");
    if (threadId == null) {
      throw new AgentException(ErrorCodes.PROTOCOL, "codex returned no thread id");
    }
    model = Json.str(thread, "model");
    if (model == null) {
      model = Json.str(result, "model");
    }
    if (model == null) {
      model = spec.model();
    }
  }

  @Override
  public String sessionRef() {
    return threadId;
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
    return CodexProvider.CAPABILITIES;
  }

  @Override
  public synchronized TurnResult runTurn(TurnSpec turn, AgentEventSink eventSink) throws AgentException {
    if (!conn.alive()) {
      throw new AgentException(ErrorCodes.PROVIDER_UNAVAILABLE, "codex process is not running\n" + conn.stderrTail());
    }
    this.sink = eventSink;
    this.finalText = null;
    this.lastMessage = null;
    this.lastFinalEmitted = null;
    this.usage = Map.of();
    this.turnId = null;
    this.turnDone = new CompletableFuture<>();
    completedTurns.clear();

    List<Map<String, Object>> input = new ArrayList<>();
    input.add(Map.of("type", "text", "text", turn.text()));
    for (Path image : turn.images()) {
      input.add(Map.of("type", "localImage", "path", image.toAbsolutePath().toString()));
    }
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("threadId", threadId);
    params.put("input", input);
    if (spec.effort() != null && !spec.effort().isBlank()) {
      params.put("effort", spec.effort());
    }
    if (turn.outputSchema() != null) {
      params.put("outputSchema", turn.outputSchema());
    }
    Object started;
    if (turn.review() != null) {
      // native review: the reviewer reads the diff itself; the text, if any, travels as custom instructions
      Map<String, Object> target = new LinkedHashMap<>(turn.review());
      target.remove("instructions");
      if ("custom".equals(target.get("type"))) {
        target.put("instructions", TurnSpec.reviewPrompt(turn.review(), turn.text()));
      }
      started = conn.call("review/start", Map.of("threadId", threadId, "delivery", "inline", "target", target), timeout);
    } else {
      started = conn.call("turn/start", params, timeout);
    }
    String id = Json.str(Json.obj(started, "turn"), "id");
    turnId = id;
    eventSink.accept(AgentEvent.of(AgentEvent.TURN_STARTED, "turnId", id));
    Map<String, Object> early = id == null ? null : completedTurns.remove(id);
    if (early != null) {
      turnDone.complete(early);
    }

    Map<String, Object> completed;
    try {
      completed = CompletableFuture.anyOf(turnDone, conn.closedFuture())
          .thenApply(x -> turnDone.getNow(null))
          .get(turn.timeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      interrupt();
      awaitQuietly(turnDone, Duration.ofSeconds(15));
      return finish(TurnResult.Status.FAILED, ErrorCodes.TIMEOUT, "turn timed out after " + turn.timeout(), turn);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      interrupt();
      return finish(TurnResult.Status.INTERRUPTED, ErrorCodes.CANCELLED, "interrupted", turn);
    } catch (ExecutionException e) {
      completed = null;
    }
    if (completed == null) {
      return finish(TurnResult.Status.FAILED, ErrorCodes.PROVIDER_UNAVAILABLE,
          "codex exited during the turn\n" + conn.stderrTail(), turn);
    }
    String status = Json.str(completed, "status");
    if ("interrupted".equals(status)) {
      return finish(TurnResult.Status.INTERRUPTED, ErrorCodes.CANCELLED, "turn interrupted", turn);
    }
    if ("failed".equals(status)) {
      Map<String, Object> err = Json.obj(completed, "error");
      return finish(TurnResult.Status.FAILED, CodexEvents.errorCode(err.get("codexErrorInfo")),
          Json.str(err, "message"), turn);
    }
    return finish(TurnResult.Status.COMPLETED, null, null, turn);
  }

  private TurnResult finish(TurnResult.Status status, String code, String error, TurnSpec turn) {
    String text = finalText != null ? finalText : lastMessage;
    Object structured = null;
    if (status == TurnResult.Status.COMPLETED && turn.outputSchema() != null) {
      try {
        structured = StructuredOutput.parse(text);
      } catch (IllegalArgumentException e) {
        status = TurnResult.Status.FAILED;
        code = ErrorCodes.INVALID_OUTPUT;
        error = "the final answer is not valid JSON: " + e.getMessage();
      }
    }
    sink.accept(AgentEvent.of(AgentEvent.TURN_COMPLETED, "turnId", turnId,
        "status", status.name().toLowerCase(), "errorCode", code, "error", error));
    turnId = null;
    return new TurnResult(status, text, structured, code, error, usage);
  }

  @Override
  public void steer(String text) throws AgentException {
    String current = turnId;
    if (current == null) {
      throw new AgentException(ErrorCodes.PROTOCOL, "no turn is running");
    }
    conn.call("turn/steer", Map.of("threadId", threadId, "expectedTurnId", current,
        "input", List.of(Map.of("type", "text", "text", text))), timeout);
  }

  @Override
  public void interrupt() {
    String current = turnId;
    if (current != null && conn.alive()) {
      conn.request("turn/interrupt", Map.of("threadId", threadId, "turnId", current));
    }
  }

  @Override
  public void close() {
    conn.close();
    if (recorder != null) {
      try {
        recorder.close();
      } catch (IOException ignored) {
        // best effort
      }
    }
  }

  // ---------------------------------------------------------------- protocol callbacks

  private void onNotification(String method, Map<String, Object> params) {
    switch (method) {
      case "turn/completed" -> {
        Map<String, Object> turn = Json.obj(params, "turn");
        String id = Json.str(turn, "id");
        CompletableFuture<Map<String, Object>> done = turnDone;
        if (id != null && id.equals(turnId) && done != null) {
          done.complete(turn);
        } else if (id != null) {
          completedTurns.put(id, turn);
        }
        return;
      }
      case "thread/tokenUsage/updated" -> {
        // Codex reports thread totals; a resumed thread already carries earlier tasks' tokens, so the
        // first report of this session fixes a baseline and taskTokens counts only this session's work.
        Map<String, Object> tu = Json.obj(params, "tokenUsage");
        Map<String, Object> u = CodexEvents.usage(tu);
        long total = ((Number) u.get("totalTokens")).longValue();
        if (baseline < 0) {
          baseline = Math.max(0, total - Json.num(Json.obj(tu, "last"), "totalTokens", total));
        }
        u.put("taskTokens", total - baseline);
        usage = u;
        sink.accept(new AgentEvent(0, System.currentTimeMillis(), AgentEvent.USAGE, u));
        return;
      }
      case "item/started" -> {
        Map<String, Object> item = Json.obj(params, "item");
        if ("fileChange".equals(Json.str(item, "type"))) {
          List<String> paths = new ArrayList<>();
          for (Object c : Json.list(item, "changes")) {
            paths.add(Json.str(c, "path"));
          }
          filePaths.put(String.valueOf(Json.str(item, "id")), paths);
        }
      }
      case "item/completed" -> {
        Map<String, Object> item = Json.obj(params, "item");
        if ("agentMessage".equals(Json.str(item, "type"))) {
          String text = Json.str(item, "text");
          lastMessage = text;
          if ("final_answer".equals(Json.str(item, "phase"))) {
            finalText = text;
          }
        } else if ("exitedReviewMode".equals(Json.str(item, "type"))) {
          finalText = Json.str(item, "review");
        }
      }
      default -> {
        // mapped below
      }
    }
    for (AgentEvent e : CodexEvents.map(method, params)) {
      if (AgentEvent.MESSAGE.equals(e.type()) && "final_answer".equals(e.data().get("phase"))) {
        // a review ends with both an agent message and an exitedReviewMode item carrying the same text
        String text = String.valueOf(e.data().get("text"));
        if (text.equals(lastFinalEmitted)) {
          continue;
        }
        lastFinalEmitted = text;
      }
      sink.accept(e);
    }
  }

  private Object onRequest(String method, Map<String, Object> p) throws RpcError {
    return switch (method) {
      case "item/commandExecution/requestApproval" -> {
        boolean network = !Json.obj(p, "networkApprovalContext").isEmpty();
        List<String> actions = new ArrayList<>();
        for (Object a : Json.list(p, "commandActions")) {
          if (Json.str(a, "command") != null) {
            actions.add(Json.str(a, "command"));
          }
        }
        // the policy judges the command that will actually run, not Codex's display summary of it
        String rawCommand = Json.str(p, "command");
        String command = rawCommand != null ? dev.orca.agent.core.Executables.unwrapShell(rawCommand)
            : actions.isEmpty() ? null : String.join(" && ", actions);
        ApprovalRequest r = new ApprovalRequest(nextRequestId(), network ? ApprovalKind.NETWORK : ApprovalKind.COMMAND,
            network ? "network access to " + Json.str(p, "networkApprovalContext.host") : command, command,
            Json.str(p, "cwd"), List.of(), Json.str(p, "reason"), p);
        yield Map.of("decision", commandDecision(decide(r)));
      }
      case "item/fileChange/requestApproval" -> {
        List<String> paths = filePaths.getOrDefault(String.valueOf(Json.str(p, "itemId")), List.of());
        ApprovalRequest r = new ApprovalRequest(nextRequestId(), ApprovalKind.FILE_CHANGE,
            paths.isEmpty() ? "file change" : String.join(", ", paths), null, Json.str(p, "grantRoot"), paths,
            Json.str(p, "reason"), p);
        yield Map.of("decision", commandDecision(decide(r)));
      }
      case "item/permissions/requestApproval" -> {
        ApprovalRequest r = new ApprovalRequest(nextRequestId(), ApprovalKind.PERMISSIONS, "additional permissions",
            null, Json.str(p, "cwd"), List.of(), Json.str(p, "reason"), p);
        Decision d = decide(r);
        boolean grant = d == Decision.ACCEPT || d == Decision.ACCEPT_FOR_SESSION;
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("permissions", grant ? p.getOrDefault("permissions", Map.of()) : Map.of());
        reply.put("scope", d == Decision.ACCEPT_FOR_SESSION ? "session" : "turn");
        yield reply;
      }
      case "item/tool/requestUserInput" -> {
        List<Map<String, Object>> questions = new ArrayList<>();
        for (Object q : Json.list(p, "questions")) {
          questions.add(Json.toMap(q));
        }
        String title = questions.isEmpty() ? "The agent has a question" : Json.str(questions.get(0), "header");
        Interaction.InputReply reply = ask(new Interaction.InputRequest(nextRequestId(), title, questions, null));
        Map<String, Object> answers = new LinkedHashMap<>();
        if (!reply.declined()) {
          reply.answers().forEach((k, v) -> answers.put(k, Map.of("answers", v)));
        }
        yield Map.of("answers", answers);
      }
      case "mcpServer/elicitation/request" -> {
        String mode = Json.str(p, "mode");
        if ("orca".equals(Json.str(p, "serverName"))) {
          // Orca's own MCP tools are scoped to this task's issue by their token; confirming them would only
          // park the task in the inbox.
          String id = nextRequestId();
          sink.accept(AgentEvent.of(AgentEvent.APPROVAL_REQUESTED, "requestId", id, "kind", "tool",
              "title", String.valueOf(Json.str(p, "message"))));
          sink.accept(AgentEvent.of(AgentEvent.APPROVAL_RESOLVED, "requestId", id, "decision", "accept", "by", "policy"));
          yield Map.of("action", "accept", "content", Map.of());
        }
        if (mode == null || !mode.endsWith("form")) {
          yield Map.of("action", "decline");
        }
        Map<String, Object> schema = Json.obj(p, "requestedSchema");
        Interaction.InputReply reply = ask(new Interaction.InputRequest(nextRequestId(),
            String.valueOf(Json.str(p, "message")), List.of(), schema));
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

  private Decision decide(ApprovalRequest r) {
    sink.accept(AgentEvent.of(AgentEvent.APPROVAL_REQUESTED, "requestId", r.requestId(),
        "kind", r.kind().name().toLowerCase(), "title", r.title(), "detail", r.reason(), "command", r.command(),
        "paths", r.paths().isEmpty() ? null : r.paths()));
    Decision d;
    try {
      d = handler.approve(r);
    } catch (RuntimeException e) {
      log.warn("approval handler failed; declining", e);
      d = Decision.DECLINE;
    }
    sink.accept(AgentEvent.of(AgentEvent.APPROVAL_RESOLVED, "requestId", r.requestId(),
        "decision", d.name().toLowerCase(), "by", "policy"));
    return d;
  }

  private Interaction.InputReply ask(Interaction.InputRequest r) {
    sink.accept(AgentEvent.of(AgentEvent.INPUT_REQUESTED, "requestId", r.requestId(), "title", r.title(),
        "questions", r.questions().isEmpty() ? null : r.questions(), "schema", r.schema()));
    Interaction.InputReply reply;
    try {
      reply = handler.input(r);
    } catch (RuntimeException e) {
      log.warn("input handler failed; declining", e);
      reply = Interaction.InputReply.decline();
    }
    sink.accept(AgentEvent.of(AgentEvent.INPUT_RESOLVED, "requestId", r.requestId(),
        "declined", reply.declined(), "answer", reply.declined() ? null : reply.answers()));
    return reply;
  }

  private static String commandDecision(Decision d) {
    return switch (d) {
      case ACCEPT -> "accept";
      case ACCEPT_FOR_SESSION -> "acceptForSession";
      case DECLINE -> "decline";
      case CANCEL -> "cancel";
    };
  }

  private String nextRequestId() {
    return "req-" + requestIds.incrementAndGet();
  }

  private static void awaitQuietly(CompletableFuture<?> f, Duration d) {
    try {
      f.get(d.toMillis(), TimeUnit.MILLISECONDS);
    } catch (Exception ignored) {
      // best effort
    }
  }

  private void record(String line) {
    synchronized (recorder) {
      try {
        recorder.write(line);
        recorder.newLine();
        recorder.flush();
      } catch (IOException ignored) {
        // recording is diagnostic only
      }
    }
  }

  private static BufferedWriter recorder(Path dir) {
    if (dir == null) {
      return null;
    }
    try {
      Files.createDirectories(dir);
      return Files.newBufferedWriter(dir.resolve("codex-" + System.currentTimeMillis() + ".jsonl"), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return null;
    }
  }

  static Permission permissionOf(SessionSpec s) {
    return s.permission();
  }
}
