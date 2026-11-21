package dev.orca.agent.codex;

import dev.orca.agent.core.Truncate;
import dev.orca.protocol.AgentEvent;
import dev.orca.protocol.ErrorCodes;
import dev.orca.protocol.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps Codex app-server notifications to {@link AgentEvent}s. Stateless apart from the inputs. */
final class CodexEvents {

  private CodexEvents() {}

  /** Returns the events for one notification; empty when it carries nothing worth showing. */
  static List<AgentEvent> map(String method, Map<String, Object> p) {
    List<AgentEvent> out = new ArrayList<>(1);
    switch (method) {
      case "item/agentMessage/delta" -> out.add(AgentEvent.of(AgentEvent.MESSAGE_DELTA,
          "itemId", Json.str(p, "itemId"), "text", Json.str(p, "delta")));
      case "item/reasoning/summaryTextDelta", "item/reasoning/textDelta" -> out.add(AgentEvent.of(AgentEvent.REASONING_DELTA,
          "itemId", Json.str(p, "itemId"), "text", Json.str(p, "delta")));
      case "item/commandExecution/outputDelta" -> out.add(AgentEvent.of(AgentEvent.TOOL_OUTPUT,
          "itemId", Json.str(p, "itemId"), "text", Json.str(p, "delta")));
      case "item/started" -> {
        AgentEvent e = itemStarted(Json.obj(p, "item"));
        if (e != null) {
          out.add(e);
        }
      }
      case "item/completed" -> {
        AgentEvent e = itemCompleted(Json.obj(p, "item"));
        if (e != null) {
          out.add(e);
        }
      }
      case "turn/plan/updated" -> {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object o : Json.list(p, "plan")) {
          entries.add(Map.of("step", String.valueOf(Json.str(o, "step")), "status", normaliseStatus(Json.str(o, "status"))));
        }
        out.add(AgentEvent.of(AgentEvent.PLAN, "entries", entries, "explanation", Json.str(p, "explanation")));
      }
      case "turn/diff/updated" -> out.add(AgentEvent.of(AgentEvent.DIFF, "diff", Truncate.middle(Json.str(p, "diff"), 200_000)));
      case "thread/tokenUsage/updated" -> out.add(new AgentEvent(0, System.currentTimeMillis(), AgentEvent.USAGE,
          usage(Json.obj(p, "tokenUsage"))));
      case "error" -> {
        Map<String, Object> err = Json.obj(p, "error");
        boolean retry = Json.bool(p, "willRetry");
        out.add(AgentEvent.of(AgentEvent.NOTICE, "level", retry ? "warn" : "error",
            "message", Json.str(err, "message"), "code", errorCode(err.get("codexErrorInfo"))));
      }
      case "warning", "configWarning", "guardianWarning" -> out.add(AgentEvent.of(AgentEvent.NOTICE, "level", "warn",
          "message", firstNonNull(Json.str(p, "message"), Json.write(p))));
      case "model/rerouted" -> out.add(AgentEvent.of(AgentEvent.NOTICE, "level", "info",
          "message", "model rerouted from " + Json.str(p, "fromModel") + " to " + Json.str(p, "toModel")));
      case "mcpServer/startupStatus/updated" -> {
        if ("failed".equals(Json.str(p, "status"))) {
          out.add(AgentEvent.of(AgentEvent.NOTICE, "level", "warn",
              "message", "MCP server '" + Json.str(p, "name") + "' failed to start: " + Json.str(p, "error")));
        }
      }
      default -> {
        // everything else (status changes, rate limits, realtime audio, …) is not part of the transcript
      }
    }
    return out;
  }

  static Map<String, Object> usage(Map<String, Object> tokenUsage) {
    Map<String, Object> total = Json.obj(tokenUsage, "total");
    Map<String, Object> u = new LinkedHashMap<>();
    u.put("inputTokens", Json.num(total, "inputTokens", 0));
    u.put("cachedInputTokens", Json.num(total, "cachedInputTokens", 0));
    u.put("outputTokens", Json.num(total, "outputTokens", 0));
    u.put("reasoningTokens", Json.num(total, "reasoningOutputTokens", 0));
    u.put("totalTokens", Json.num(total, "totalTokens", 0));
    long window = Json.num(tokenUsage, "modelContextWindow", 0);
    if (window > 0) {
      u.put("contextWindow", window);
    }
    Map<String, Object> last = Json.obj(tokenUsage, "last");
    if (!last.isEmpty()) {
      u.put("lastTotalTokens", Json.num(last, "totalTokens", 0));
    }
    return u;
  }

  private static AgentEvent itemStarted(Map<String, Object> item) {
    String type = Json.str(item, "type");
    String id = Json.str(item, "id");
    if (type == null) {
      return null;
    }
    return switch (type) {
      case "commandExecution" -> AgentEvent.of(AgentEvent.TOOL_STARTED, "itemId", id, "kind", "command",
          "title", commandTitle(item), "input", Map.of("command", String.valueOf(Json.str(item, "command")),
              "cwd", String.valueOf(Json.str(item, "cwd"))));
      case "fileChange" -> AgentEvent.of(AgentEvent.TOOL_STARTED, "itemId", id, "kind", "file_change",
          "title", changeTitle(item), "changes", changes(item));
      case "mcpToolCall" -> AgentEvent.of(AgentEvent.TOOL_STARTED, "itemId", id, "kind", "mcp",
          "title", Json.str(item, "server") + "." + Json.str(item, "tool"), "input", item.get("arguments"));
      case "webSearch" -> AgentEvent.of(AgentEvent.TOOL_STARTED, "itemId", id, "kind", "web_search",
          "title", Json.str(item, "query"));
      case "dynamicToolCall", "collabToolCall", "imageView" -> AgentEvent.of(AgentEvent.TOOL_STARTED, "itemId", id,
          "kind", "other", "title", firstNonNull(Json.str(item, "tool"), type));
      case "enteredReviewMode" -> AgentEvent.of(AgentEvent.NOTICE, "level", "info",
          "message", "Codex review started: " + Json.str(item, "review"));
      default -> null;
    };
  }

  private static AgentEvent itemCompleted(Map<String, Object> item) {
    String type = Json.str(item, "type");
    String id = Json.str(item, "id");
    if (type == null) {
      return null;
    }
    return switch (type) {
      case "agentMessage" -> AgentEvent.of(AgentEvent.MESSAGE, "itemId", id, "text", Json.str(item, "text"),
          "phase", Json.str(item, "phase"));
      case "reasoning" -> {
        List<String> parts = new ArrayList<>();
        for (Object s : Json.list(item, "summary")) {
          parts.add(s instanceof String str ? str : firstNonNull(Json.str(s, "text"), ""));
        }
        String text = String.join("\n\n", parts).trim();
        yield text.isEmpty() ? null : AgentEvent.of(AgentEvent.REASONING, "itemId", id, "text", text);
      }
      case "commandExecution" -> AgentEvent.of(AgentEvent.TOOL_COMPLETED, "itemId", id, "kind", "command",
          "title", commandTitle(item), "status", normaliseStatus(Json.str(item, "status")),
          "exitCode", item.get("exitCode"), "durationMs", item.get("durationMs"),
          "output", Truncate.middle(Json.str(item, "aggregatedOutput"), Truncate.TOOL_OUTPUT));
      case "fileChange" -> AgentEvent.of(AgentEvent.TOOL_COMPLETED, "itemId", id, "kind", "file_change",
          "title", changeTitle(item), "status", normaliseStatus(Json.str(item, "status")), "changes", changes(item));
      case "mcpToolCall" -> AgentEvent.of(AgentEvent.TOOL_COMPLETED, "itemId", id, "kind", "mcp",
          "title", Json.str(item, "server") + "." + Json.str(item, "tool"), "status", normaliseStatus(Json.str(item, "status")),
          "output", item.get("result") == null ? null : Truncate.middle(Json.write(item.get("result")), Truncate.TOOL_OUTPUT),
          "error", item.get("error") == null ? null : Json.write(item.get("error")));
      case "webSearch" -> AgentEvent.of(AgentEvent.TOOL_COMPLETED, "itemId", id, "kind", "web_search",
          "title", Json.str(item, "query"), "status", "completed");
      case "dynamicToolCall", "collabToolCall", "imageView" -> AgentEvent.of(AgentEvent.TOOL_COMPLETED, "itemId", id,
          "kind", "other", "title", firstNonNull(Json.str(item, "tool"), type), "status", normaliseStatus(Json.str(item, "status")));
      case "contextCompaction" -> AgentEvent.of(AgentEvent.NOTICE, "level", "info", "message", "context compacted");
      case "exitedReviewMode" -> AgentEvent.of(AgentEvent.MESSAGE, "itemId", id, "text", Json.str(item, "review"),
          "phase", "final_answer");
      default -> null;
    };
  }

  /** Prefers the parsed command over the shell wrapper ({@code /bin/bash -lc '...'}). */
  static String commandTitle(Map<String, Object> item) {
    List<String> parts = new ArrayList<>();
    for (Object a : Json.list(item, "commandActions")) {
      String c = Json.str(a, "command");
      if (c != null) {
        parts.add(c);
      }
    }
    return parts.isEmpty() ? Json.str(item, "command") : String.join(" && ", parts);
  }

  private static List<Map<String, Object>> changes(Map<String, Object> item) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object c : Json.list(item, "changes")) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("path", Json.str(c, "path"));
      Object kind = Json.get(c, "kind");
      m.put("kind", kind instanceof Map<?, ?> km ? firstNonNull(Json.str(km, "type"), Json.write(km)) : String.valueOf(kind));
      String diff = Json.str(c, "diff");
      if (diff != null) {
        m.put("diff", Truncate.middle(diff, Truncate.TOOL_OUTPUT));
      }
      out.add(m);
    }
    return out;
  }

  private static String changeTitle(Map<String, Object> item) {
    List<String> paths = new ArrayList<>();
    for (Object c : Json.list(item, "changes")) {
      paths.add(Json.str(c, "path"));
    }
    if (paths.isEmpty()) {
      return "file change";
    }
    return paths.size() == 1 ? paths.get(0) : paths.get(0) + " (+" + (paths.size() - 1) + ")";
  }

  static String normaliseStatus(String s) {
    if (s == null) {
      return "completed";
    }
    return switch (s) {
      case "inProgress", "in_progress", "pending" -> s.equals("pending") ? "pending" : "in_progress";
      case "completed", "success", "succeeded" -> "completed";
      case "failed", "error" -> "failed";
      case "declined", "rejected" -> "declined";
      default -> s;
    };
  }

  /** Maps {@code codexErrorInfo} (a string or a single-key object) to an {@link ErrorCodes} value. */
  static String errorCode(Object info) {
    String key = info instanceof Map<?, ?> m && !m.isEmpty() ? String.valueOf(m.keySet().iterator().next())
        : info == null ? "" : String.valueOf(info);
    return switch (key) {
      case "unauthorized" -> ErrorCodes.AUTH_REQUIRED;
      case "usageLimitExceeded", "rateLimitExceeded", "sessionBudgetExceeded" -> ErrorCodes.USAGE_LIMIT;
      case "modelNotFound" -> ErrorCodes.MODEL_NOT_FOUND;
      case "contextWindowExceeded" -> ErrorCodes.CONTEXT_EXCEEDED;
      case "sandboxError" -> ErrorCodes.SANDBOX;
      case "httpConnectionFailed", "responseStreamConnectionFailed", "responseStreamDisconnected", "internalServerError",
          "serverOverloaded", "responseTooManyFailedAttempts" -> ErrorCodes.PROVIDER_UNAVAILABLE;
      default -> ErrorCodes.AGENT_ERROR;
    };
  }

  private static String firstNonNull(String a, String b) {
    return a != null ? a : b;
  }
}
