package dev.fellowloop.server.workflow.components;

import static dev.fellowloop.server.workflow.components.Fields.d;
import static dev.fellowloop.server.workflow.components.Fields.f;
import static dev.fellowloop.server.workflow.components.Fields.out;
import static dev.fellowloop.server.workflow.components.Fields.req;

import dev.fellowloop.protocol.ErrorCodes;
import dev.fellowloop.server.model.Agent;
import dev.fellowloop.server.model.Task;
import dev.fellowloop.server.repo.AgentRepo;
import dev.fellowloop.server.task.TaskRequest;
import dev.fellowloop.server.task.TaskService;
import dev.fellowloop.workflow.spi.FieldKind;
import dev.fellowloop.workflow.spi.NodeComponent;
import dev.fellowloop.workflow.spi.NodeContext;
import dev.fellowloop.workflow.spi.NodeDescriptor;
import dev.fellowloop.workflow.spi.NodeResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Pushes an issue's branch ({@code fellowloop/<key>}) from the machine that worked on it, with that machine's
 * git credentials. No agent turn is involved; the daemon refuses anything but the issue's own branch.
 */
@Component
public class RepoPushComponent implements NodeComponent {

  private final TaskService tasks;
  private final AgentRepo agents;

  public RepoPushComponent(@Lazy TaskService tasks, AgentRepo agents) {
    this.tasks = tasks;
    this.agents = agents;
  }

  @Override
  public NodeDescriptor descriptor() {
    return d("repo.push", "action", "Push issue branch",
        "Pushes the issue's branch to the project's remote from the runtime that worked on it. Git hosts answer "
            + "with a link to open a merge request, which is returned.", false,
        List.of(req("issue", "Issue", FieldKind.STRING).withDefault("{{trigger.issue.id}}"),
            req("agent", "On the runtime of", FieldKind.AGENT)
                .withHelp("The agent whose machine holds the work and the git credentials, e.g. the implementer.")),
        List.of(),
        List.of(out("text", "string", "what happened"), out("branch", "string", "the pushed branch"),
            out("links", "array", "links the git host printed, e.g. to open a merge request"),
            out("taskId", "string", "the push task, for its log")));
  }

  @Override
  public NodeResult execute(NodeContext ctx) {
    String issue = ctx.string("issue");
    if (issue == null || issue.isBlank() || issue.equals("null")) {
      return NodeResult.fatal("no issue given");
    }
    String ref = ctx.string("agent");
    Agent agent = ref == null ? null : agents.findById(ref).or(() -> agents.findByNameIgnoreCase(ref)).orElse(null);
    if (agent == null || agent.archived) {
      return NodeResult.fatal("agent '" + ref + "' " + (agent == null ? "does not exist" : "is archived"));
    }
    Map<String, Object> origin = new LinkedHashMap<>();
    origin.put("runId", ctx.runId());
    origin.put("workflowId", ctx.workflowId());
    origin.put("nodeId", ctx.node().id());
    origin.put("attempt", ctx.attempt());
    Task t = tasks.enqueue(new TaskRequest(agent.id, issue, "Push the issue branch.", "push", null, "new", origin, 10));
    return new NodeResult.Waiting(t.id, Map.of("taskId", t.id));
  }

  @Override
  public NodeResult onSignal(NodeContext ctx, Map<String, Object> p) {
    Map<String, Object> output = new LinkedHashMap<>();
    output.put("taskId", p.get("taskId"));
    output.put("text", p.getOrDefault("text", ""));
    if (p.get("structured") instanceof Map<?, ?> s) {
      output.put("branch", s.get("branch"));
      output.put("links", s.get("links") == null ? List.of() : s.get("links"));
    }
    if ("succeeded".equals(String.valueOf(p.get("status")))) {
      return NodeResult.done(output);
    }
    String code = String.valueOf(p.get("errorCode"));
    output.put("error", p.get("error"));
    return new NodeResult.Failed(p.get("error") == null ? "the push failed" : String.valueOf(p.get("error")),
        !"cancelled".equals(String.valueOf(p.get("status"))) && ErrorCodes.retryable(code), output);
  }

  @Override
  public void cancel(NodeContext ctx) {
    if (ctx.waitKey() != null) {
      tasks.cancel(ctx.waitKey(), "workflow run cancelled");
    }
  }
}
