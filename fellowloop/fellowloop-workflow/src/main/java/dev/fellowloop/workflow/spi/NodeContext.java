package dev.fellowloop.workflow.spi;

import dev.fellowloop.workflow.model.NodeDef;
import java.util.Map;

/** What a component sees while it executes. */
public interface NodeContext {

  String runId();

  String workflowId();

  NodeDef node();

  /** Configuration with all templates resolved against {@link #variables()}. */
  Map<String, Object> config();

  /** {@code trigger}, {@code nodes} and {@code run}, as expressions see them. */
  Map<String, Object> variables();

  /** 1 for the first execution, 2 for the first retry, and so on. */
  int attempt();

  /** The wait key the node returned when it started waiting, or {@code null}. */
  String waitKey();

  default String string(String key) {
    Object v = config().get(key);
    return v == null ? null : String.valueOf(v);
  }

  default String string(String key, String fallback) {
    String v = string(key);
    return v == null || v.isBlank() ? fallback : v;
  }

  default boolean bool(String key, boolean fallback) {
    Object v = config().get(key);
    if (v instanceof Boolean b) {
      return b;
    }
    return v == null ? fallback : Boolean.parseBoolean(String.valueOf(v));
  }

  default long number(String key, long fallback) {
    Object v = config().get(key);
    if (v instanceof Number n) {
      return n.longValue();
    }
    try {
      return v == null ? fallback : Long.parseLong(String.valueOf(v).trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
