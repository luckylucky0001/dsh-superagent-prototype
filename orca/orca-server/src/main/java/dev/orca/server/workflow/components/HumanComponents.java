package dev.orca.server.workflow.components;

import static dev.orca.server.workflow.components.Fields.d;
import static dev.orca.server.workflow.components.Fields.f;
import static dev.orca.server.workflow.components.Fields.out;
import static dev.orca.server.workflow.components.Fields.req;

import dev.orca.server.inbox.InboxService;
import dev.orca.server.support.Ids;
import dev.orca.workflow.expr.Values;
import dev.orca.workflow.spi.FieldKind;
import dev.orca.workflow.spi.NodeComponent;
import dev.orca.workflow.spi.NodeContext;
import dev.orca.workflow.spi.NodeDescriptor;
import dev.orca.workflow.spi.NodeResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Steps that wait for a person, through the inbox. */
@Configuration
public class HumanComponents {

  static String issueOf(NodeContext ctx) {
    String v = ctx.string("issue");
    if (v == null || v.isBlank() || v.equals("null")) {
      Object id = dev.orca.protocol.Json.get(ctx.variables(), "trigger.issue.id");
      return id == null ? null : String.valueOf(id);
    }
    return v;
  }

  @Bean
  NodeComponent approval(InboxService inbox) {
    return new NodeComponent() {
      public NodeDescriptor descriptor() {
        return d("human.approval", "human", "Approval", "Waits until someone approves or rejects in the inbox.", false,
            List.of(req("title", "Title", FieldKind.TEMPLATE), f("body", "Details", FieldKind.TEMPLATE),
                f("issue", "Issue", FieldKind.STRING)),
            List.of("approved", "rejected"),
            List.of(out("decision", "string", "approved or rejected"), out("comment", "string", "the reviewer's note"),
                out("by", "string", "member id")));
      }

      public NodeResult execute(NodeContext ctx) {
        String key = Ids.next("inb");
        inbox.approval(ctx.runId(), ctx.node().id(), key, ctx.string("title", "Approval needed"), ctx.string("body"),
            issueOf(ctx));
        return NodeResult.waiting(key);
      }

      @Override
      public void cancel(NodeContext ctx) {
        // a timed-out or cancelled approval must not stay answerable in the inbox
        inbox.closeWait(ctx.waitKey());
      }

      @Override
      public NodeResult onSignal(NodeContext ctx, Map<String, Object> p) {
        boolean ok = "approved".equals(p.get("decision"));
        return NodeResult.done(p, ok ? "approved" : "rejected");
      }
    };
  }

  @Bean
  NodeComponent humanInput(InboxService inbox) {
    return new NodeComponent() {
      public NodeDescriptor descriptor() {
        return d("human.input", "human", "Ask a person", "Asks questions in the inbox and waits for the answers.", false,
            List.of(req("title", "Title", FieldKind.TEMPLATE), f("body", "Details", FieldKind.TEMPLATE),
                req("questions", "Questions (one per line)", FieldKind.TEXT), f("issue", "Issue", FieldKind.STRING)),
            List.of(), List.of(out("answers", "object", "answer per question id (q1, q2, …)")));
      }

      public NodeResult execute(NodeContext ctx) {
        List<Map<String, Object>> questions = new ArrayList<>();
        int i = 0;
        for (String line : Values.stringify(ctx.config().get("questions")).split("\n")) {
          if (!line.isBlank()) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("id", "q" + (++i));
            q.put("header", line.trim());
            q.put("question", line.trim());
            questions.add(q);
          }
        }
        String key = Ids.next("inb");
        inbox.input(ctx.runId(), ctx.node().id(), key, ctx.string("title", "Input needed"), ctx.string("body"),
            questions, issueOf(ctx));
        return NodeResult.waiting(key);
      }

      @Override
      public void cancel(NodeContext ctx) {
        inbox.closeWait(ctx.waitKey());
      }

      @Override
      public NodeResult onSignal(NodeContext ctx, Map<String, Object> p) {
        return NodeResult.done(Map.of("answers", p.getOrDefault("answers", Map.of()), "by", String.valueOf(p.get("by"))));
      }
    };
  }
}
