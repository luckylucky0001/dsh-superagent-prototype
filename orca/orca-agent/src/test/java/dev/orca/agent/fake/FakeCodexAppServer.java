package dev.orca.agent.fake;

import dev.orca.protocol.Json;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A scripted stand-in for {@code codex app-server}, shaped after recorded traffic of codex-cli 0.171.
 * The scenario comes from the {@code FAKE_SCENARIO} environment variable:
 * {@code echo} (default), {@code approval}, {@code question}, {@code fail_auth}, {@code hang}.
 */
public final class FakeCodexAppServer {

  private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);
  private static final AtomicLong SERVER_IDS = new AtomicLong(1000);
  private static final Map<Long, CompletableFuture<Map<String, Object>>> PENDING = new ConcurrentHashMap<>();
  private static final String THREAD = "thr_fake_1";
  private static volatile String activeTurn;
  private static volatile CompletableFuture<String> hangRelease = new CompletableFuture<>();
  private static int turnCounter;
  private static volatile String instructions = "";

  public static void main(String[] args) throws Exception {
    if (List.of(args).contains("--version")) {
      OUT.println("codex-cli 0.171.0");
      return;
    }
    String scenario = System.getenv().getOrDefault("FAKE_SCENARIO", "echo");
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String line;
    while ((line = in.readLine()) != null) {
      if (line.isBlank()) {
        continue;
      }
      Map<String, Object> msg = Json.readMap(line);
      Object id = msg.get("id");
      String method = (String) msg.get("method");
      Map<String, Object> params = Json.obj(msg, "params");
      if (method == null) {
        CompletableFuture<Map<String, Object>> f = PENDING.remove(((Number) id).longValue());
        if (f != null) {
          f.complete(Json.toMap(msg.get("result")));
        }
        continue;
      }
      switch (method) {
        case "initialize" -> reply(id, Map.of("userAgent", "fake", "platformOs", "linux"));
        case "initialized" -> { }
        case "account/read" -> reply(id, Map.of("account", Map.of("type", "chatgpt", "planType", "plus")));
        case "model/list" -> reply(id, Map.of("data", List.of(
            Map.of("id", "gpt-6-luna", "displayName", "GPT-6 Luna", "description", "cheap", "isDefault", false,
                "hidden", false, "supportedReasoningEfforts", List.of("low", "medium"), "defaultReasoningEffort", "medium"),
            Map.of("id", "gpt-6.2-sol", "displayName", "GPT-6.2 Sol", "description", "default", "isDefault", true,
                "hidden", false, "supportedReasoningEfforts", List.of("low"), "defaultReasoningEffort", "low"))));
        case "thread/start" -> {
          instructions = String.valueOf(params.getOrDefault("developerInstructions", ""));
          reply(id, Map.of("thread", Map.of("id", THREAD, "model", String.valueOf(params.getOrDefault("model", "gpt-6.2-sol")))));
          notify("thread/started", Map.of("thread", Map.of("id", THREAD)));
        }
        case "thread/resume" -> {
          if ("missing".equals(params.get("threadId"))) {
            error(id, -32600, "thread not found: missing");
          } else {
            reply(id, Map.of("thread", Map.of("id", params.get("threadId"), "model", "gpt-6.2-sol")));
          }
        }
        case "turn/start" -> {
          String turnId = "turn_" + (++turnCounter);
          activeTurn = turnId;
          reply(id, Map.of("turn", Map.of("id", turnId, "status", "inProgress", "items", List.of())));
          List<?> inputs = (List<?>) params.get("input");
          long imgs = inputs.stream().filter(x -> "localImage".equals(((Map<?, ?>) x).get("type"))
              && java.nio.file.Files.exists(java.nio.file.Path.of(String.valueOf(((Map<?, ?>) x).get("path"))))).count();
          String text = String.valueOf(((Map<?, ?>) inputs.get(0)).get("text")) + (imgs > 0 ? " [images:" + imgs + "]" : "");
          boolean structured = params.containsKey("outputSchema");
          Thread.ofVirtual().start(() -> {
            try {
              runTurn(scenario, turnId, text, structured);
            } catch (Exception e) {
              e.printStackTrace();
            }
          });
        }
        case "turn/interrupt" -> {
          reply(id, Map.of());
          hangRelease.complete("__interrupt__");
        }
        case "turn/steer" -> {
          reply(id, Map.of("turnId", params.get("expectedTurnId")));
          String text = String.valueOf(((Map<?, ?>) ((List<?>) params.get("input")).get(0)).get("text"));
          hangRelease.complete(text);
        }
        default -> error(id, -32601, "unknown method " + method);
      }
    }
  }

  private static void runTurn(String scenario, String turnId, String text, boolean structured) throws Exception {
    notify("turn/started", Map.of("threadId", THREAD, "turn", Map.of("id", turnId, "status", "inProgress")));
    switch (scenario) {
      case "approval" -> {
        notify("item/started", item(turnId, Map.of("type", "commandExecution", "id", "exec-1",
            "command", "/bin/bash -lc 'rm -rf build'", "cwd", "/w", "status", "inProgress",
            "commandActions", List.of(Map.of("type", "unknown", "command", "rm -rf build")))));
        Map<String, Object> res = request("item/commandExecution/requestApproval", Map.of("threadId", THREAD,
            "turnId", turnId, "itemId", "exec-1", "command", "rm -rf build", "cwd", "/w", "reason", "outside sandbox"));
        String decision = String.valueOf(res.get("decision"));
        boolean ok = decision.startsWith("accept");
        notify("item/completed", item(turnId, Map.of("type", "commandExecution", "id", "exec-1",
            "command", "/bin/bash -lc 'rm -rf build'", "cwd", "/w", "status", ok ? "completed" : "declined",
            "exitCode", ok ? 0 : -1, "aggregatedOutput", ok ? "removed\n" : "",
            "commandActions", List.of(Map.of("type", "unknown", "command", "rm -rf build")))));
        message(turnId, "decision=" + decision);
      }
      case "question" -> {
        Map<String, Object> res = request("item/tool/requestUserInput", Map.of("threadId", THREAD, "turnId", turnId,
            "itemId", "q-1", "isBlocking", true, "questions", List.of(Map.of("id", "db", "header", "Database",
                "question", "Which database?", "options", List.of(Map.of("label", "postgres", "description", "PG"))))));
        Object answers = Json.get(res, "answers.db.answers");
        message(turnId, "answer=" + answers);
      }
      case "fail_auth" -> {
        notify("error", Map.of("threadId", THREAD, "turnId", turnId, "willRetry", false,
            "error", Map.of("message", "401 Unauthorized", "codexErrorInfo", "unauthorized")));
        notify("turn/completed", Map.of("threadId", THREAD, "turn", Map.of("id", turnId, "status", "failed",
            "error", Map.of("message", "401 Unauthorized", "codexErrorInfo", "unauthorized"))));
        return;
      }
      case "hang" -> {
        notify("item/started", item(turnId, Map.of("type", "commandExecution", "id", "exec-h", "command", "sleep 600",
            "cwd", "/w", "status", "inProgress")));
        String got = hangRelease.get(30, TimeUnit.SECONDS);
        hangRelease = new CompletableFuture<>();
        if ("__interrupt__".equals(got)) {
          notify("turn/completed", Map.of("threadId", THREAD, "turn", Map.of("id", turnId, "status", "interrupted")));
          return;
        }
        message(turnId, "steered: " + got);
      }
      default -> {
        notify("turn/plan/updated", Map.of("turnId", turnId, "plan", List.of(Map.of("step", "reply", "status", "inProgress"))));
        notify("item/started", item(turnId, Map.of("type", "fileChange", "id", "fc-1", "status", "inProgress",
            "changes", List.of(Map.of("path", "a.txt", "kind", Map.of("type", "add"), "diff", "+hi")))));
        notify("item/completed", item(turnId, Map.of("type", "fileChange", "id", "fc-1", "status", "completed",
            "changes", List.of(Map.of("path", "a.txt", "kind", Map.of("type", "add"), "diff", "+hi")))));
        message(turnId, structured ? "```json\n{\"ok\": true, \"echo\": " + Json.write(text) + "}\n```"
            : text.contains("show instructions") ? "INSTRUCTIONS: " + instructions : "ECHO: " + text);
      }
    }
    notify("thread/tokenUsage/updated", Map.of("threadId", THREAD, "turnId", turnId, "tokenUsage", Map.of(
        "total", Map.of("totalTokens", 120, "inputTokens", 100, "cachedInputTokens", 80, "outputTokens", 20,
            "reasoningOutputTokens", 0), "last", Map.of("totalTokens", 120), "modelContextWindow", 258400)));
    notify("turn/completed", Map.of("threadId", THREAD, "turn", Map.of("id", turnId, "status", "completed")));
  }

  private static void message(String turnId, String text) {
    String itemId = "msg_" + turnId;
    notify("item/started", item(turnId, Map.of("type", "agentMessage", "id", itemId, "text", "", "phase", "final_answer")));
    int half = text.length() / 2;
    notify("item/agentMessage/delta", Map.of("threadId", THREAD, "turnId", turnId, "itemId", itemId, "delta", text.substring(0, half)));
    notify("item/agentMessage/delta", Map.of("threadId", THREAD, "turnId", turnId, "itemId", itemId, "delta", text.substring(half)));
    notify("item/completed", item(turnId, Map.of("type", "agentMessage", "id", itemId, "text", text, "phase", "final_answer")));
  }

  private static Map<String, Object> item(String turnId, Map<String, Object> item) {
    return Map.of("threadId", THREAD, "turnId", turnId, "item", item);
  }

  private static Map<String, Object> request(String method, Map<String, Object> params) throws Exception {
    long id = SERVER_IDS.incrementAndGet();
    CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
    PENDING.put(id, f);
    write(Map.of("id", id, "method", method, "params", params));
    return f.get(30, TimeUnit.SECONDS);
  }

  private static void reply(Object id, Object result) {
    write(Map.of("id", id, "result", result));
  }

  private static void error(Object id, int code, String message) {
    write(Map.of("id", id, "error", Map.of("code", code, "message", message)));
  }

  private static void notify(String method, Object params) {
    write(Map.of("method", method, "params", params));
  }

  private static synchronized void write(Object msg) {
    OUT.println(Json.write(msg));
  }

  /** The command that starts this fake, for {@code CodexConfig#command}. */
  public static List<String> command() {
    List<String> cmd = new ArrayList<>();
    cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    cmd.add("-cp");
    cmd.add(System.getProperty("java.class.path"));
    cmd.add(FakeCodexAppServer.class.getName());
    return cmd;
  }

  private static final class Path {
    static java.nio.file.Path of(String first, String... more) {
      return java.nio.file.Path.of(first, more);
    }
  }

  static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put(String.valueOf(kv[i]), kv[i + 1]);
    }
    return m;
  }
}
