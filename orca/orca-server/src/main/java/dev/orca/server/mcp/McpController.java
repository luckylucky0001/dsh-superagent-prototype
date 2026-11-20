package dev.orca.server.mcp;

import dev.orca.protocol.Json;
import dev.orca.server.auth.AuthService;
import dev.orca.server.issue.IssueService;
import dev.orca.server.model.ApiToken;
import dev.orca.server.model.Comment;
import dev.orca.server.model.Issue;
import dev.orca.server.model.Task;
import dev.orca.server.repo.AgentRepo;
import dev.orca.server.repo.TaskRepo;
import dev.orca.server.support.Views;
import dev.orca.server.task.TaskStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A minimal MCP server (Streamable HTTP, JSON responses) through which a running agent talks back to
 * Orca. Each task gets its own bearer token, valid while the task runs and only for its issue.
 */
@RestController
public class McpController {

  static final String PROTOCOL = "2025-06-18";
  static final Set<String> SETTABLE = Set.of("in_progress", "in_review", "done");

  private final AuthService auth;
  private final TaskRepo tasks;
  private final AgentRepo agents;
  private final IssueService issues;

  public McpController(AuthService auth, TaskRepo tasks, AgentRepo agents, IssueService issues) {
    this.auth = auth;
    this.tasks = tasks;
    this.agents = agents;
    this.issues = issues;
  }

  @GetMapping("/api/mcp")
  public ResponseEntity<Void> stream() {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
  }

  @PostMapping(path = "/api/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Object> rpc(HttpServletRequest req, @RequestBody Object body) {
    Optional<Task> task = taskFor(req);
    if (task.isEmpty()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid or expired task token"));
    }
    if (body instanceof List<?> batch) {
      List<Object> out = new ArrayList<>();
      for (Object m : batch) {
        Object r = handle(task.get(), Json.toMap(m));
        if (r != null) {
          out.add(r);
        }
      }
      return out.isEmpty() ? ResponseEntity.accepted().build() : ResponseEntity.ok(out);
    }
    Object r = handle(task.get(), Json.toMap(body));
    return r == null ? ResponseEntity.accepted().build() : ResponseEntity.ok(r);
  }

  private Optional<Task> taskFor(HttpServletRequest req) {
    String h = req.getHeader("Authorization");
    String token = h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null;
    return auth.verify(token, AuthService.TASK).map(ApiToken::getLabel).flatMap(tasks::findById)
        .filter(t -> !TaskStatus.TERMINAL.contains(t.status));
  }

  private Object handle(Task task, Map<String, Object> msg) {
    Object id = msg.get("id");
    String method = Json.str(msg, "method");
    if (id == null) {
      return null; // notifications such as notifications/initialized
    }
    try {
      Object result = switch (String.valueOf(method)) {
        case "initialize" -> Map.of("protocolVersion", Json.str(msg, "params.protocolVersion") == null ? PROTOCOL
                : Json.str(msg, "params.protocolVersion"),
            "capabilities", Map.of("tools", Map.of("listChanged", false)),
            "serverInfo", Map.of("name", "orca", "version", "1.0.0"),
            "instructions", "Tools for the Orca issue this task works on. Your final reply is posted automatically.");
        case "ping" -> Map.of();
        case "tools/list" -> Map.of("tools", tools());
        case "tools/call" -> call(task, Json.str(msg, "params.name"), Json.obj(Json.obj(msg, "params"), "arguments"));
        default -> throw new IllegalArgumentException("method not found: " + method);
      };
      return Map.of("jsonrpc", "2.0", "id", id, "result", result);
    } catch (IllegalArgumentException e) {
      return Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", -32601, "message", String.valueOf(e.getMessage())));
    }
  }

  static List<Map<String, Object>> tools() {
    return List.of(
        tool("orca_get_issue", "Read the issue this task works on, with its recent comments.", Map.of(), List.of(), true),
        tool("orca_post_comment", "Post a short progress note or question on the issue. Do not post your final "
            + "answer with this tool; it is posted automatically.",
            Map.of("body", Map.of("type", "string", "description", "Markdown text")), List.of("body"), false),
        tool("orca_set_issue_status", "Move the issue to in_progress, in_review or done.",
            Map.of("status", Map.of("type", "string", "enum", List.copyOf(SETTABLE))), List.of("status"), false));
  }

  private static Map<String, Object> tool(String name, String description, Map<String, Object> props, List<String> required,
      boolean readOnly) {
    return Map.of("name", name, "description", description,
        "inputSchema", Map.of("type", "object", "properties", props, "required", required, "additionalProperties", false),
        "annotations", Map.of("readOnlyHint", readOnly, "destructiveHint", false, "idempotentHint", readOnly,
            "openWorldHint", false));
  }

  private Map<String, Object> call(Task task, String name, Map<String, Object> args) {
    if (task.issueId == null) {
      return text("This task is not attached to an issue.", true);
    }
    Issue issue = issues.get(task.issueId);
    return switch (String.valueOf(name)) {
      case "orca_get_issue" -> {
        Map<String, Object> view = new LinkedHashMap<>(Json.toMap(Views.IssueView.of(issue)));
        List<Map<String, Object>> recent = new ArrayList<>();
        List<Comment> all = issues.comments(issue.id);
        for (Comment c : all.subList(Math.max(0, all.size() - 15), all.size())) {
          recent.add(Map.of("author", c.authorType, "body", c.body, "createdAt", c.createdAt));
        }
        view.put("comments", recent);
        yield text(Json.write(view), false);
      }
      case "orca_post_comment" -> {
        String body = Json.str(args, "body");
        if (body == null || body.isBlank()) {
          yield text("body is required", true);
        }
        Comment c = issues.comment(issue.id, body, "agent", task.agentId, task.id);
        yield text("posted comment " + c.id, false);
      }
      case "orca_set_issue_status" -> {
        String status = Json.str(args, "status");
        if (!SETTABLE.contains(status)) {
          yield text("status must be one of " + SETTABLE, true);
        }
        issues.update(issue.id, new IssueService.IssuePatch(null, null, status, null, null, null, null, null),
            "agent", task.agentId);
        yield text("issue " + issue.issueKey + " is now " + status, false);
      }
      default -> text("unknown tool " + name, true);
    };
  }

  private static Map<String, Object> text(String s, boolean error) {
    return Map.of("content", List.of(Map.of("type", "text", "text", s)), "isError", error);
  }
}
