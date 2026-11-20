package dev.orca.server.workflow.components;

import static dev.orca.server.workflow.components.Fields.d;
import static dev.orca.server.workflow.components.Fields.f;
import static dev.orca.server.workflow.components.Fields.out;
import static dev.orca.server.workflow.components.Fields.req;
import static dev.orca.server.workflow.components.Fields.select;

import dev.orca.protocol.Json;
import dev.orca.server.issue.IssueService;
import dev.orca.server.model.Agent;
import dev.orca.server.model.Comment;
import dev.orca.server.model.Issue;
import dev.orca.server.repo.AgentRepo;
import dev.orca.server.support.Views;
import dev.orca.workflow.expr.Values;
import dev.orca.workflow.spi.FieldKind;
import dev.orca.workflow.spi.NodeComponent;
import dev.orca.workflow.spi.NodeContext;
import dev.orca.workflow.spi.NodeDescriptor;
import dev.orca.workflow.spi.NodeResult;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class IssueComponents {

  private static Optional<Agent> agent(AgentRepo agents, String ref) {
    if (ref == null || ref.isBlank() || ref.equals("null")) {
      return Optional.empty();
    }
    return agents.findById(ref).or(() -> agents.findByNameIgnoreCase(ref));
  }

  @Bean
  NodeComponent issueComment(@Lazy IssueService issues, AgentRepo agents) {
    return new NodeComponent() {
      public NodeDescriptor descriptor() {
        return d("issue.comment", "action", "Comment on issue", "Posts a comment, as an agent or as Orca.", false,
            List.of(f("issue", "Issue", FieldKind.STRING).withDefault("{{trigger.issue.id}}"),
                req("body", "Comment", FieldKind.TEMPLATE),
                f("author", "Post as agent", FieldKind.AGENT).withHelp("Empty posts as Orca."),
                f("taskId", "Link to task", FieldKind.STRING)),
            List.of(), List.of(out("commentId", "string", "the new comment")));
      }

      public NodeResult execute(NodeContext ctx) {
        String issue = HumanComponents.issueOf(ctx);
        if (issue == null) {
          return NodeResult.fatal("no issue to comment on");
        }
        String body = ctx.string("body", "");
        if (body.isBlank()) {
          body = "_(the agent returned no text)_";
        }
        Optional<Agent> a = agent(agents, ctx.string("author"));
        String task = ctx.string("taskId");
        Comment c = issues.comment(issue, body, a.isPresent() ? "agent" : "system", a.map(x -> x.id).orElse(null),
            task == null || task.isBlank() || task.equals("null") ? null : task);
        return NodeResult.done(Map.of("commentId", c.id));
      }
    };
  }

  @Bean
  NodeComponent issueUpdate(@Lazy IssueService issues, AgentRepo agents) {
    return new NodeComponent() {
      public NodeDescriptor descriptor() {
        return d("issue.update", "action", "Update issue", "Changes status, assignee or labels.", false,
            List.of(f("issue", "Issue", FieldKind.STRING).withDefault("{{trigger.issue.id}}"),
                select("status", "Status", false, null, "backlog", "todo", "in_progress", "in_review", "done", "cancelled"),
                f("assignee", "Assign to agent", FieldKind.AGENT),
                f("addLabels", "Add labels (comma separated)", FieldKind.STRING)),
            List.of(), List.of(out("issue", "object", "the updated issue")));
      }

      public NodeResult execute(NodeContext ctx) {
        String id = HumanComponents.issueOf(ctx);
        if (id == null) {
          return NodeResult.fatal("no issue to update");
        }
        Issue current = issues.get(id);
        String status = ctx.string("status");
        Optional<Agent> assignee = agent(agents, ctx.string("assignee"));
        List<String> labels = null;
        String add = ctx.string("addLabels");
        if (add != null && !add.isBlank()) {
          java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>(Views.IssueView.of(current).labels());
          Arrays.stream(add.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(set::add);
          labels = List.copyOf(set);
        }
        Issue i = issues.update(id, new IssueService.IssuePatch(null, null, status == null || status.isBlank() ? null : status,
            null, assignee.isPresent() ? "agent" : null, assignee.map(a -> a.id).orElse(null), labels, null),
            "workflow", ctx.runId());
        return NodeResult.done(Map.of("issue", Json.toMap(Views.IssueView.of(i))));
      }
    };
  }

  @Bean
  NodeComponent issueCreate(@Lazy IssueService issues, AgentRepo agents) {
    return new NodeComponent() {
      public NodeDescriptor descriptor() {
        return d("issue.create", "action", "Create issue", "Creates an issue, optionally assigned to an agent.", false,
            List.of(req("title", "Title", FieldKind.TEMPLATE), f("description", "Description", FieldKind.TEMPLATE),
                f("assignee", "Assign to agent", FieldKind.AGENT), f("labels", "Labels (comma separated)", FieldKind.STRING),
                f("project", "Project id", FieldKind.STRING)),
            List.of(), List.of(out("issue", "object", "the new issue")));
      }

      public NodeResult execute(NodeContext ctx) {
        Optional<Agent> a = agent(agents, ctx.string("assignee"));
        String labels = ctx.string("labels");
        String project = ctx.string("project");
        Issue i = issues.create(new IssueService.IssueInput(project == null || project.isBlank() ? null : project,
            ctx.string("title"), ctx.string("description"), "todo", null, a.isPresent() ? "agent" : null,
            a.map(x -> x.id).orElse(null), labels == null || labels.isBlank() ? List.of()
                : Arrays.stream(labels.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()),
            "workflow", ctx.runId());
        return NodeResult.done(Map.of("issue", Json.toMap(Views.IssueView.of(i))));
      }
    };
  }

  static String text(Object v) {
    return Values.stringify(v);
  }
}
