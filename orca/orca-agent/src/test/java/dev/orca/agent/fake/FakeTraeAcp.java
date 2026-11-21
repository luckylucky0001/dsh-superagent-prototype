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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A scripted stand-in for {@code traecli acp serve}, following what TRAE CLI 0.216.0 did in live probes:
 * {@code initialize} capabilities, model ids differing from display names in {@code configOptions},
 * {@code systemPrompt {mode, content[]}}, {@code session/set_config_option}, the three permission options
 * where "abort" ends the turn as {@code cancelled}, and token usage in the prompt result.
 * Scenarios ({@code FAKE_SCENARIO}): {@code echo}, {@code permission}, {@code stubborn}
 * (asks again after every decline), {@code unauth}, {@code hang}.
 */
public final class FakeTraeAcp {

  private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);
  private static final AtomicLong IDS = new AtomicLong(500);
  private static final Map<Long, CompletableFuture<Map<String, Object>>> PENDING = new ConcurrentHashMap<>();
  private static volatile CompletableFuture<Void> cancelled = new CompletableFuture<>();
  private static volatile String systemPrompt;
  private static volatile String model = "fake_model";
  private static volatile int turns;

  public static void main(String[] args) throws Exception {
    if (List.of(args).contains("-V")) {
      OUT.println("traecli 0.216.0");
      return;
    }
    if (List.of(args).contains("login")) {
      OUT.println("Logged in as fake@example.com");
      return;
    }
    if (List.of(args).contains("models")) {
      OUT.println("* fake-model (default)");
      OUT.println("  fake-mini");
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
        case "initialize" -> reply(id, Map.of("protocolVersion", 1,
            "agentCapabilities", Map.of("loadSession", true,
                "promptCapabilities", Map.of("image", true, "audio", false, "embeddedContext", true),
                "mcpCapabilities", Map.of("http", true, "sse", false),
                "sessionCapabilities", Map.of("list", Map.of(), "resume", Map.of(), "fork", Map.of(),
                    "systemPrompt", Map.of(), "close", Map.of())),
            "agentInfo", Map.of("name", "traecli-acp", "version", "0.216.0")));
        case "session/new" -> {
          if ("unauth".equals(scenario)) {
            error(id, -32000, "Authentication required");
          } else {
            Object sp = Json.get(params, "systemPrompt");
            if (sp != null) {
              if (!"append".equals(Json.str(sp, "mode")) || !(Json.get(sp, "content") instanceof List<?>)) {
                error(id, -32602, "Invalid params: systemPrompt wants {mode, content: [ContentBlock]}");
                continue;
              }
              systemPrompt = Json.str(((List<?>) Json.get(sp, "content")).get(0), "text");
            }
            reply(id, Map.of("sessionId", "ses_fake", "configOptions", List.of(Map.of("id", "model", "type", "select",
                "currentValue", model, "options", List.of(
                    Map.of("value", "fake_model", "name", "Fake-Model", "description", "200K context window"),
                    Map.of("value", "fake_mini_2", "name", "Fake-Mini", "description", "fast"))))));
          }
        }
        case "session/load" -> {
          if ("gone".equals(params.get("sessionId"))) {
            error(id, -32002, "session not found");
          } else {
            update(Map.of("sessionUpdate", "user_message_chunk", "content", Map.of("type", "text", "text", "old")));
            update(Map.of("sessionUpdate", "agent_message_chunk", "content", Map.of("type", "text", "text", "OLD HISTORY")));
            reply(id, Map.of());
          }
        }
        case "session/cancel" -> cancelled.complete(null);
        case "session/set_config_option" -> {
          model = String.valueOf(params.get("value"));
          reply(id, Map.of("configOptions", List.of()));
        }
        case "session/close" -> reply(id, Map.of());
        case "session/prompt" -> {
          String text = String.valueOf(((Map<?, ?>) ((List<?>) params.get("prompt")).get(0)).get("text"));
          Thread.ofVirtual().start(() -> {
            try {
              prompt(scenario, id, text);
            } catch (Exception e) {
              e.printStackTrace();
            }
          });
        }
        default -> error(id, -32601, "unknown " + method);
      }
    }
  }

  private static void prompt(String scenario, Object id, String text) throws Exception {
    update(Map.of("sessionUpdate", "plan", "entries", List.of(Map.of("content", "answer", "priority", "high",
        "status", "in_progress"))));
    update(Map.of("sessionUpdate", "agent_message_chunk", "content", Map.of("type", "text", "text", "Let me check. ")));
    switch (scenario) {
      case "permission", "stubborn" -> {
        update(Map.of("sessionUpdate", "tool_call", "toolCallId", "call_1", "title", "Run npm test", "kind", "execute",
            "status", "pending", "rawInput", Map.of("command", "npm test")));
        if (text.contains("was declined") && "permission".equals(scenario)) {
          update(Map.of("sessionUpdate", "agent_message_chunk", "content", Map.of("type", "text",
              "text", "continued without it")));
          break;
        }
        Map<String, Object> res = request("session/request_permission", Map.of("sessionId", "ses_fake",
            "toolCall", Map.of("toolCallId", "call_1", "title", "npm test", "kind", "execute", "status", "pending",
                "rawInput", Map.of("command", "npm test", "cwd", "/work/app", "reason", "needs the network")),
            "options", List.of(Map.of("optionId", "approved", "name", "Yes, proceed", "kind", "allow_once"),
                Map.of("optionId", "approved-execpolicy-amendment", "name", "Yes, and don't ask again", "kind", "allow_always"),
                Map.of("optionId", "abort", "name", "No, and tell TRAE CLI what to do differently", "kind", "reject_once"))));
        String chosen = String.valueOf(Json.get(res, "outcome.optionId"));
        if ("abort".equals(chosen)) {
          // TRAE ends the whole turn when a request is declined
          reply(id, Map.of("stopReason", "cancelled"));
          return;
        }
        update(Map.of("sessionUpdate", "tool_call_update", "toolCallId", "call_1",
            "status", "allow".equals(chosen) ? "completed" : "failed",
            "content", List.of(Map.of("type", "content", "content", Map.of("type", "text", "text", "3 passing")))));
        update(Map.of("sessionUpdate", "agent_message_chunk", "content", Map.of("type", "text", "text", "chosen=" + chosen)));
      }
      case "hang" -> {
        update(Map.of("sessionUpdate", "tool_call", "toolCallId", "call_h", "title", "sleep", "kind", "execute",
            "status", "in_progress"));
        cancelled.get(30, TimeUnit.SECONDS);
        cancelled = new CompletableFuture<>();
        reply(id, Map.of("stopReason", "cancelled"));
        return;
      }
      default -> {
        if (text.startsWith("/review")) {
          String verdict = "The change breaks add(). " + text;
          update(Map.of("sessionUpdate", "agent_message_chunk", "content", Map.of("type", "text",
              "text", verdict + "\n\n- [P0] add() subtracts — calc.py:2")));
          // TRAE repeats its overall verdict as a second message
          update(Map.of("sessionUpdate", "agent_message_chunk", "content", Map.of("type", "text", "text", verdict)));
          break;
        }
        // like TRAE: the diff arrives with the tool call; the completion only says {"success":true}
        Map<String, Object> diff = new java.util.HashMap<>(Map.of("type", "diff", "path", "a.txt", "newText", "hi\nthere\n"));
        diff.put("oldText", "hi\n");
        update(Map.of("sessionUpdate", "tool_call", "toolCallId", "call_e", "title", "Editing files", "kind", "edit",
            "status", "in_progress", "locations", List.of(Map.of("path", "a.txt")), "content", List.of(diff)));
        update(Map.of("sessionUpdate", "tool_call_update", "toolCallId", "call_e", "status", "completed",
            "content", List.of(Map.of("type", "content", "content", Map.of("type", "text", "text", "{\"success\":true}")))));
        boolean json = text.contains("JSON Schema");
        String echo = text.equals("system?") ? "SYSTEM: " + systemPrompt + " MODEL: " + model
            : "ECHO: " + text.substring(Math.max(0, text.lastIndexOf('\n') + 1));
        update(Map.of("sessionUpdate", "agent_message_chunk", "content", Map.of("type", "text",
            "text", json ? "{\"ok\": true}" : echo)));
      }
    }
    update(Map.of("sessionUpdate", "usage_update", "used", 1200, "size", 200000));
    turns++;
    Map<String, Object> turn = Map.of("totalTokens", 1000, "inputTokens", 900, "outputTokens", 100, "thoughtTokens", 20,
        "cachedReadTokens", 300);
    Map<String, Object> total = Map.of("totalTokens", 5000 + 1000 * turns, "inputTokens", 4500, "outputTokens", 500,
        "thoughtTokens", 80, "cachedReadTokens", 1200);
    reply(id, Map.of("stopReason", "end_turn", "usage", turn, "_meta", Map.of("_trae/tokenUsage", Map.of(
        "scope", "turn", "turn", turn, "sessionTotal", total, "contextWindow", Map.of("used", 1200, "size", 190000)))));
  }

  private static void update(Map<String, Object> u) {
    write(Map.of("jsonrpc", "2.0", "method", "session/update", "params", Map.of("sessionId", "ses_fake", "update", u)));
  }

  private static Map<String, Object> request(String method, Object params) throws Exception {
    long id = IDS.incrementAndGet();
    CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
    PENDING.put(id, f);
    write(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
    return f.get(30, TimeUnit.SECONDS);
  }

  private static void reply(Object id, Object result) {
    write(Map.of("jsonrpc", "2.0", "id", id, "result", result));
  }

  private static void error(Object id, int code, String message) {
    write(Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", code, "message", message)));
  }

  private static synchronized void write(Object m) {
    OUT.println(Json.write(m));
  }

  public static List<String> command() {
    List<String> cmd = new ArrayList<>();
    cmd.add(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString());
    cmd.add("-cp");
    cmd.add(System.getProperty("java.class.path"));
    cmd.add(FakeTraeAcp.class.getName());
    return cmd;
  }

  static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put(String.valueOf(kv[i]), kv[i + 1]);
    }
    return m;
  }
}
