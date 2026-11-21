package dev.fellowloop.server.workflow.components;

import static dev.fellowloop.server.workflow.components.Fields.d;
import static dev.fellowloop.server.workflow.components.Fields.f;
import static dev.fellowloop.server.workflow.components.Fields.out;
import static dev.fellowloop.server.workflow.components.Fields.req;

import dev.fellowloop.workflow.expr.Expression;
import dev.fellowloop.workflow.expr.Values;
import dev.fellowloop.workflow.spi.FieldKind;
import dev.fellowloop.workflow.spi.NodeComponent;
import dev.fellowloop.workflow.spi.NodeContext;
import dev.fellowloop.workflow.spi.NodeDescriptor;
import dev.fellowloop.workflow.spi.NodeResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LogicComponents {

  /**
   * Evaluates the field as written. A {{ }} template's output is used as a value and never parsed
   * again, so text from issues or comments cannot inject expression syntax.
   */
  static Object evaluate(NodeContext ctx, String field) {
    Object raw = ctx.node().config().get(field);
    if (raw instanceof String s && !dev.fellowloop.workflow.expr.Template.hasPlaceholders(s)) {
      String t = s.trim();
      if (t.equals("true") || t.equals("false")) {
        return Boolean.parseBoolean(t);
      }
      return Expression.eval(t, ctx.variables());
    }
    Object v = ctx.config().get(field);
    if (v instanceof String s && (s.trim().equals("true") || s.trim().equals("false"))) {
      return Boolean.parseBoolean(s.trim());
    }
    return v;
  }

  @Bean
  NodeComponent condition() {
    return new NodeComponent() {
      public NodeDescriptor descriptor() {
        return d("logic.condition", "logic", "Condition", "Routes to 'true' or 'false'.", false,
            List.of(req("when", "Condition", FieldKind.EXPRESSION)
                .withHelp("e.g. nodes.review.output.structured.verdict == 'pass'")),
            List.of("true", "false"), List.of(out("value", "boolean", "the result")));
      }

      public NodeResult execute(NodeContext ctx) {
        boolean v = Values.truthy(evaluate(ctx, "when"));
        return NodeResult.done(Map.of("value", v), v ? "true" : "false");
      }
    };
  }

  @Bean
  NodeComponent setValues() {
    return new NodeComponent() {
      public NodeDescriptor descriptor() {
        return d("logic.set", "logic", "Set values", "Computes values from templates for later nodes.", false,
            List.of(req("values", "Values (JSON object, templates allowed)", FieldKind.JSON)),
            List.of(), List.of(out("*", "any", "each key of the object")));
      }

      @SuppressWarnings("unchecked")
      public NodeResult execute(NodeContext ctx) {
        Object v = ctx.config().get("values");
        if (v instanceof Map<?, ?> m) {
          return NodeResult.done(new LinkedHashMap<>((Map<String, Object>) m));
        }
        return NodeResult.fatal("values must be a JSON object");
      }
    };
  }

  @Bean
  NodeComponent join() {
    return new NodeComponent() {
      public NodeDescriptor descriptor() {
        return d("logic.join", "logic", "Join", "Waits for all incoming branches, then continues.", false,
            List.of(f("note", "Note", FieldKind.STRING)), List.of(), List.of());
      }

      public NodeResult execute(NodeContext ctx) {
        return NodeResult.done(Map.of());
      }
    };
  }
}
