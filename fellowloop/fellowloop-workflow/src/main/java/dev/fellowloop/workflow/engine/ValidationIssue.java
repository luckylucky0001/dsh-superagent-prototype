package dev.fellowloop.workflow.engine;

/** One problem in a workflow definition; {@code nodeId} or {@code edgeId} locate it in the editor. */
public record ValidationIssue(String nodeId, String edgeId, String message) {
  public static ValidationIssue node(String nodeId, String message) {
    return new ValidationIssue(nodeId, null, message);
  }

  public static ValidationIssue edge(String edgeId, String message) {
    return new ValidationIssue(null, edgeId, message);
  }

  public static ValidationIssue graph(String message) {
    return new ValidationIssue(null, null, message);
  }
}
