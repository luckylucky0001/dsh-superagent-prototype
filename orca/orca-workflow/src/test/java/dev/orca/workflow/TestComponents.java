package dev.orca.workflow;

import dev.orca.workflow.spi.FieldKind;
import dev.orca.workflow.spi.FieldSpec;
import dev.orca.workflow.spi.NodeComponent;
import dev.orca.workflow.spi.NodeContext;
import dev.orca.workflow.spi.NodeDescriptor;
import dev.orca.workflow.spi.NodeResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class TestComponents {
  static final List<String> cancelled = new ArrayList<>();

  static NodeComponent trigger() {
    return new NodeComponent() {
      public NodeDescriptor descriptor() {
        return new NodeDescriptor("t.manual", "trigger", "Manual", "", true, List.of(), List.of(), List.of());
      }

      public NodeResult execute(NodeContext ctx) {
        throw new UnsupportedOperationException();
      }
    };
  }

  /** Completes with its resolved config as output. */
  static NodeComponent echo() {
    return new NodeComponent() {
      public NodeDescriptor descriptor() {
        return new NodeDescriptor("t.echo", "action", "Echo", "", false,
            List.of(FieldSpec.of("text", "Text", FieldKind.TEMPLATE, false)), List.of(), List.of());
      }

      public NodeResult execute(NodeContext ctx) {
        return NodeResult.done(new LinkedHashMap<>(ctx.config()));
      }
    };
  }

  static NodeComponent branch() {
    return new NodeComponent() {
      public NodeDescriptor descriptor() {
        return new NodeDescriptor("t.if", "logic", "If", "", false,
            List.of(FieldSpec.of("when", "When", FieldKind.EXPRESSION, true)), List.of("true", "false"), List.of());
      }

      public NodeResult execute(NodeContext ctx) {
        boolean v = dev.orca.workflow.expr.Values.truthy(ctx.config().get("when"));
        return NodeResult.done(Map.of("value", v), v ? "true" : "false");
      }
    };
  }

  static NodeComponent async() {
    return new NodeComponent() {
      public NodeDescriptor descriptor() {
        return new NodeDescriptor("t.async", "agent", "Async", "", false, List.of(), List.of(), List.of());
      }

      public NodeResult execute(NodeContext ctx) {
        return NodeResult.waiting(ctx.node().id() + "#" + ctx.attempt());
      }

      @Override
      public NodeResult onSignal(NodeContext ctx, Map<String, Object> payload) {
        if ("failed".equals(payload.get("status"))) {
          return NodeResult.failed("task failed");
        }
        return NodeResult.done(payload);
      }

      @Override
      public void cancel(NodeContext ctx) {
        cancelled.add(ctx.waitKey());
      }
    };
  }

  static NodeComponent flaky(AtomicInteger failuresLeft) {
    return new NodeComponent() {
      public NodeDescriptor descriptor() {
        return new NodeDescriptor("t.flaky", "action", "Flaky", "", false, List.of(), List.of(), List.of());
      }

      public NodeResult execute(NodeContext ctx) {
        if (failuresLeft.getAndDecrement() > 0) {
          throw new IllegalStateException("boom " + ctx.attempt());
        }
        return NodeResult.done(Map.of("attempt", ctx.attempt()));
      }
    };
  }
}
