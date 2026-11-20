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
 * A scripted stand-in for {@code traecli acp serve}. {@code initialize} mirrors what TRAE CLI 0.216.0
 * returned in a real probe. Scenarios ({@code FAKE_SCENARIO}): {@code echo}, {@code permission},
 * {@code unauth}, {@code hang}.
 */
public final class FakeTraeAcp {

  private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);
  private static final AtomicLong IDS = new AtomicLong(500);
  private static final Map<Long, CompletableFuture<Map<String, Object>>> PENDING = new ConcurrentHashMap<>();
  private static volatile CompletableFuture<Void> cancelled = new CompletableFuture<>();

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
                "sessionCapabilities", Map.of("list", Map.of(), "resume", Map.of(), "fork", Map.of())),
            "agentInfo", Map.of("name", "traecli-acp", "version", "0.216.0")));
        case "session/new" -> {
          if ("unauth".equals(scenario)) {
            error(id, -32000, "Authentication required");
          } else {
            reply(id, Map.of("sessionId", "ses_fake", "models", Map.of("currentModelId", "fake-model")));
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
      case "permission" -> {
        update(Map.of("sessionUpdate", "tool_call", "toolCallId", "call_1", "title", "Run npm test", "kind", "execute",
            "status", "pending", "rawInput", Map.of("command", "npm test")));
        Map<String, Object> res = request("session/request_permission", Map.of("sessionId", "ses_fake",
            "toolCall", Map.of("toolCallId", "call_1", "title", "Run npm test", "kind", "execute",
                "rawInput", Map.of("command", "npm test")),
            "options", List.of(Map.of("optionId", "allow", "name", "Allow", "kind", "allow_once"),
                Map.of("optionId", "deny", "name", "Deny", "kind", "reject_once"))));
        String chosen = String.valueOf(Json.get(res, "outcome.optionId"));
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
        update(Map.of("sessionUpdate", "tool_call", "toolCallId", "call_e", "title", "Edit a.txt", "kind", "edit",
            "status", "pending", "locations", List.of(Map.of("path", "a.txt"))));
        update(Map.of("sessionUpdate", "tool_call_update", "toolCallId", "call_e", "status", "completed",
            "content", List.of(Map.of("type", "diff", "path", "a.txt", "newText", "hi"))));
        boolean json = text.contains("JSON Schema");
        update(Map.of("sessionUpdate", "agent_message_chunk", "content", Map.of("type", "text",
            "text", json ? "{\"ok\": true}" : "ECHO: " + text.substring(Math.max(0, text.lastIndexOf('\n') + 1)))));
      }
    }
    update(Map.of("sessionUpdate", "usage_update", "used", 1200, "size", 200000));
    reply(id, Map.of("stopReason", "end_turn"));
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
