package dev.fellowloop.workflow.engine;

public enum NodeStatus {
  PENDING,
  READY,
  RUNNING,
  WAITING,
  SUCCEEDED,
  FAILED,
  SKIPPED,
  CANCELLED;

  public boolean terminal() {
    return this == SUCCEEDED || this == FAILED || this == SKIPPED || this == CANCELLED;
  }
}
