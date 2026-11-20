package dev.orca.workflow.model;

/**
 * A directed connection between two nodes.
 *
 * @param id         unique within the definition
 * @param source     source node id
 * @param sourcePort output port of the source node; {@code null} means {@link #DEFAULT_PORT}
 * @param target     target node id
 * @param condition  optional expression; the edge is taken only when it evaluates truthy
 */
public record EdgeDef(String id, String source, String sourcePort, String target, String condition) {

  public static final String DEFAULT_PORT = "out";
  public static final String ERROR_PORT = "error";

  public EdgeDef {
    if (sourcePort == null || sourcePort.isBlank()) {
      sourcePort = DEFAULT_PORT;
    }
    if (condition != null && condition.isBlank()) {
      condition = null;
    }
  }

  public EdgeDef(String id, String source, String target) {
    this(id, source, DEFAULT_PORT, target, null);
  }
}
