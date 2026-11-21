package dev.fellowloop.server.workflow.components;

import static dev.fellowloop.server.workflow.components.Fields.d;
import static dev.fellowloop.server.workflow.components.Fields.f;
import static dev.fellowloop.server.workflow.components.Fields.out;
import static dev.fellowloop.server.workflow.components.Fields.req;

import dev.fellowloop.protocol.Json;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Implement → review → fix, until the reviewer passes the work or the round limit is reached. The
 * implementer keeps its session across rounds; the reviewer starts fresh each round and answers with a
 * structured verdict. The loop lives inside one node, so workflows stay acyclic.
 */
@Component
public class ReviewLoopComponent implements NodeComponent {

  static final Map<String, Object> VERDICT = Map.of("type", "object", "additionalProperties", false,
      "required", List.of("verdict", "summary", "issues"),
      "properties", Map.of("verdict", Map.of("type", "string", "enum", List.of("pass", "changes_requested")),
          "summary", Map.of("type", "string"),
          "issues", Map.of("type", "array", "items", Map.of("type", "string"))));

  private final TaskService tasks;
  private final AgentRepo agents;

  public ReviewLoopComponent(@Lazy TaskService tasks, AgentRepo agents) {
    this.tasks = tasks;
    this.agents = agents;
  }

  @Override
  public NodeDescriptor descriptor() {
    return d("agent.review_loop", "agent", "Implement & review loop",
        "One agent implements, another reviews with a structured verdict; requested changes go back to the "
            + "implementer until the review passes or the round limit is reached.", false,
        List.of(req("implementer", "Implementer", FieldKind.AGENT), req("reviewer", "Reviewer", FieldKind.AGENT),
            req("prompt", "Task for the implementer", FieldKind.TEMPLATE),
            f("reviewPrompt", "Review instructions", FieldKind.TEMPLATE).withDefault(
                "Review the change in the current directory: if it is a git repository, inspect the commits on this "
                    + "branch (git log -p) and any uncommitted diff; otherwise read the files. Do not rely on the "
                    + "implementer's summary alone. Run the tests if you can. Pass only if it does what the issue asks."),
            f("issue", "Issue", FieldKind.STRING).withDefault("{{trigger.issue.id}}"),
            f("maxRounds", "Max rounds", FieldKind.NUMBER).withDefault(3)),
        List.of("passed", "failed"),
        List.of(out("verdict", "string", "pass or changes_requested"), out("summary", "string", "last review summary"),
            out("issues", "array", "open review points"), out("rounds", "number", "rounds used"),
            out("text", "string", "the implementer's last reply"), out("history", "array", "every round")));
  }

  @Override
  public NodeResult execute(NodeContext ctx) {
    Agent impl = agent(ctx.string("implementer"));
    Agent rev = agent(ctx.string("reviewer"));
    if (impl == null || rev == null || impl.archived || rev.archived) {
      return NodeResult.fatal("implementer and reviewer must be existing, active agents");
    }
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("phase", "implement");
    state.put("round", 1);
    state.put("history", new ArrayList<>());
    return enqueue(ctx, state, impl, ctx.string("prompt", ""), "continue", null);
  }

  @Override
  @SuppressWarnings("unchecked")
  public NodeResult onSignal(NodeContext ctx, Map<String, Object> p) {
    Map<String, Object> state = new LinkedHashMap<>(selfOutput(ctx));
    List<Object> history = new ArrayList<>((List<Object>) state.getOrDefault("history", List.of()));
    int round = ((Number) state.getOrDefault("round", 1)).intValue();
    int max = (int) Math.max(1, ctx.number("maxRounds", 3));
    if (!"succeeded".equals(p.get("status"))) {
      return new NodeResult.Failed(String.valueOf(p.getOrDefault("error", "agent run " + p.get("status"))), false, state);
    }
    if ("implement".equals(state.get("phase"))) {
      state.put("text", p.get("text"));
      String review = ctx.string("reviewPrompt", "") + "\n\nThe implementer's summary:\n\n" + p.get("text");
      state.put("phase", "review");
      state.put("history", history);
      return enqueue(ctx, state, agent(ctx.string("reviewer")), review, "new", VERDICT);
    }
    Map<String, Object> verdict = p.get("structured") instanceof Map<?, ?> m ? Json.toMap(m) : Map.of();
    // a CLI may return the verdict loosely (TRAE does not validate against the schema): "PASS", issues as a
    // string or null; read it forgivingly instead of failing the loop or burning a round
    String v = normalizeVerdict(verdict.get("verdict"));
    String summary = verdict.get("summary") == null ? "" : String.valueOf(verdict.get("summary"));
    List<String> issues = issueList(verdict.get("issues"));
    history.add(Map.of("round", round, "verdict", v, "summary", summary, "issues", issues));
    state.put("history", history);
    state.put("verdict", v);
    state.put("summary", summary);
    state.put("issues", issues);
    state.put("rounds", round);
    if ("pass".equals(v)) {
      state.remove("phase");
      return NodeResult.done(state, "passed");
    }
    if (round >= max) {
      state.remove("phase");
      return NodeResult.done(state, "failed");
    }
    state.put("round", round + 1);
    state.put("phase", "implement");
    List<String> points = new ArrayList<>();
    for (String o : issues) {
      points.add("- " + o);
    }
    String fix = "The reviewer requested changes (round " + round + " of " + max + "):\n\n" + state.get("summary") + "\n"
        + String.join("\n", points) + "\n\nAddress them, then reply with what you changed.";
    return enqueue(ctx, state, agent(ctx.string("implementer")), fix, "continue", null);
  }

  @Override
  public void cancel(NodeContext ctx) {
    if (ctx.waitKey() != null) {
      tasks.cancel(ctx.waitKey(), "workflow run cancelled");
    }
  }

  private NodeResult enqueue(NodeContext ctx, Map<String, Object> state, Agent agent, String prompt, String session,
      Map<String, Object> schema) {
    String issue = ctx.string("issue");
    if (issue != null && (issue.isBlank() || issue.equals("null"))) {
      issue = null;
    }
    Map<String, Object> origin = Map.of("runId", ctx.runId(), "workflowId", ctx.workflowId(), "nodeId", ctx.node().id(),
        "phase", String.valueOf(state.get("phase")), "round", state.get("round"));
    Task t = tasks.enqueue(new TaskRequest(agent.id, issue, prompt, schema == null ? "run" : "structured", schema, session,
        origin, null));
    state.put("taskId", t.id);
    return new NodeResult.Waiting(t.id, state);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> selfOutput(NodeContext ctx) {
    Object out = Json.get(ctx.variables(), "nodes." + ctx.node().id() + ".output");
    return out instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  private Agent agent(String ref) {
    if (ref == null || ref.isBlank()) {
      return null;
    }
    return agents.findById(ref).or(() -> agents.findByNameIgnoreCase(ref)).orElse(null);
  }

  /** "pass" in any letter case or spacing (also "passed", "approved"); anything else asks for changes. */
  static String normalizeVerdict(Object raw) {
    String v = raw == null ? "" : String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
    return v.equals("pass") || v.equals("passed") || v.equals("approved") ? "pass" : "changes_requested";
  }

  /** The reviewer's points as strings: a list, a single string, or nothing. */
  static List<String> issueList(Object raw) {
    List<String> out = new ArrayList<>();
    if (raw instanceof java.util.Collection<?> c) {
      for (Object o : c) {
        if (o != null && !String.valueOf(o).isBlank()) {
          out.add(o instanceof Map<?, ?> m ? Json.write(m) : String.valueOf(o));
        }
      }
    } else if (raw != null && !String.valueOf(raw).isBlank()) {
      out.add(String.valueOf(raw));
    }
    return out;
  }

}
