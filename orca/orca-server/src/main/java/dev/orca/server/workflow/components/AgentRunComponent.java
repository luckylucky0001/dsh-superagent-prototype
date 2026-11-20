package dev.orca.server.workflow.components;

import static dev.orca.server.workflow.components.Fields.d;
import static dev.orca.server.workflow.components.Fields.f;
import static dev.orca.server.workflow.components.Fields.out;
import static dev.orca.server.workflow.components.Fields.req;
import static dev.orca.server.workflow.components.Fields.select;

import dev.orca.protocol.ErrorCodes;
import dev.orca.protocol.Json;
import dev.orca.server.model.Agent;
import dev.orca.server.model.Task;
import dev.orca.server.repo.AgentRepo;
import dev.orca.server.task.TaskRequest;
import dev.orca.server.task.TaskService;
import dev.orca.workflow.spi.FieldKind;
import dev.orca.workflow.spi.NodeComponent;
import dev.orca.workflow.spi.NodeContext;
import dev.orca.workflow.spi.NodeDescriptor;
import dev.orca.workflow.spi.NodeResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Runs an agent (Codex or TRAE CLI, per the agent's provider) and waits for its result. */
@Component
public class AgentRunComponent implements NodeComponent {

  private final TaskService tasks;
  private final AgentRepo agents;

  public AgentRunComponent(@Lazy TaskService tasks, AgentRepo agents) {
    this.tasks = tasks;
    this.agents = agents;
  }

  @Override
  public NodeDescriptor descriptor() {
    return d("agent.run", "agent", "Run agent",
        "Gives a task to an agent and waits for it. With an output schema the result is structured JSON.", false,
        List.of(req("agent", "Agent", FieldKind.AGENT).withHelp("An agent, or a template such as {{trigger.agentId}}"),
            select("mode", "Mode", false, "work", "work", "review")
                .withHelp("'review' runs a code review instead of a task: Codex uses its built-in reviewer "
                    + "(review/start); other CLIs get an equivalent prompt."),
            f("prompt", "Prompt", FieldKind.TEMPLATE)
                .withHelp("The task. In review mode it is used by the custom target and by CLIs without a built-in reviewer."),
            select("reviewTarget", "Review what", false, "baseBranch", "baseBranch", "uncommittedChanges", "custom")
                .withHelp("baseBranch: the issue branch against the project's default branch.")
                .visibleWhen("mode", "review"),
            f("baseBranch", "Base branch", FieldKind.STRING)
                .withHelp("Defaults to the project's default branch (main).")
                .visibleWhen("reviewTarget", "baseBranch"),
            f("issue", "Issue", FieldKind.STRING).withDefault("{{trigger.issue.id}}")
                .withHelp("Ties the run to an issue: same workspace, and the session can continue."),
            select("session", "Session", false, "continue", "continue", "new")
                .withHelp("'continue' resumes the agent's previous session on the same issue.")
                .visibleWhen("mode", "work"),
            f("outputSchema", "Output schema (JSON Schema)", FieldKind.JSON).visibleWhen("mode", "work"),
            f("timeoutMinutes", "Timeout (minutes)", FieldKind.NUMBER)),
        List.of("out"),
        List.of(out("text", "string", "the agent's final answer"),
            out("structured", "object", "parsed answer when an output schema is set"),
            out("status", "string", "succeeded, failed, cancelled or timed_out"),
            out("taskId", "string", "the task, for its transcript"),
            out("usage", "object", "token usage")));
  }

  @Override
  public NodeResult execute(NodeContext ctx) {
    String ref = ctx.string("agent");
    if (ref == null || ref.isBlank()) {
      return NodeResult.fatal("no agent given");
    }
    Agent agent = agents.findById(ref).or(() -> agents.findByNameIgnoreCase(ref)).orElse(null);
    if (agent == null || agent.archived) {
      return NodeResult.fatal("agent '" + ref + "' " + (agent == null ? "does not exist" : "is archived"));
    }
    String issue = ctx.string("issue");
    if (issue != null && (issue.isBlank() || issue.equals("null"))) {
      issue = null;
    }
    Map<String, Object> schema = null;
    Object raw = ctx.config().get("outputSchema");
    if (raw instanceof Map<?, ?> m && !m.isEmpty()) {
      schema = Json.toMap(m);
    } else if (raw instanceof String s && !s.isBlank()) {
      try {
        schema = Json.readMap(s);
      } catch (RuntimeException e) {
        return NodeResult.fatal("output schema is not valid JSON");
      }
    }
    Map<String, Object> review = null;
    String prompt = ctx.string("prompt", "");
    if ("review".equals(ctx.string("mode", "work"))) {
      review = new LinkedHashMap<>();
      String target = ctx.string("reviewTarget", "baseBranch");
      review.put("type", target);
      if ("baseBranch".equals(target)) {
        String base = ctx.string("baseBranch", "");
        review.put("branch", base == null || base.isBlank() ? tasks.defaultBranch(issue) : base);
      }
      // review mode returns the reviewer's text: a schema or session setting left over from work mode is ignored
      schema = null;
    } else if (prompt == null || prompt.isBlank()) {
      return NodeResult.fatal("a prompt is required");
    }
    Map<String, Object> origin = new LinkedHashMap<>();
    origin.put("runId", ctx.runId());
    origin.put("workflowId", ctx.workflowId());
    origin.put("nodeId", ctx.node().id());
    origin.put("attempt", ctx.attempt());
    long timeout = ctx.number("timeoutMinutes", 0);
    Task t = tasks.enqueue(new TaskRequest(agent.id, issue, review != null && (prompt == null || prompt.isBlank())
        ? "Review the changes." : prompt, review != null ? "review" : schema == null ? "run" : "structured",
        schema, review != null ? "new" : ctx.string("session", "continue"), origin, timeout > 0 ? (int) timeout : null,
        review));
    return new NodeResult.Waiting(t.id, Map.of("taskId", t.id, "agent", agent.name, "provider", agent.provider));
  }

  @Override
  public NodeResult onSignal(NodeContext ctx, Map<String, Object> p) {
    Map<String, Object> output = new LinkedHashMap<>(p);
    output.putIfAbsent("text", "");
    String status = String.valueOf(p.get("status"));
    if ("succeeded".equals(status)) {
      return NodeResult.done(output);
    }
    String code = String.valueOf(p.get("errorCode"));
    String error = p.get("error") == null ? "the agent run " + status : String.valueOf(p.get("error"));
    boolean retryable = !"cancelled".equals(status) && ErrorCodes.retryable(code);
    return new NodeResult.Failed(error, retryable, output);
  }

  @Override
  public void cancel(NodeContext ctx) {
    if (ctx.waitKey() != null) {
      tasks.cancel(ctx.waitKey(), "workflow run cancelled");
    }
  }
}
