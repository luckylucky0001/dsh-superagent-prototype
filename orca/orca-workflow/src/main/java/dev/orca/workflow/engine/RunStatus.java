package dev.orca.workflow.engine;

public enum RunStatus {
  RUNNING,
  WAITING,
  SUCCEEDED,
  FAILED,
  CANCELLED;

  public boolean terminal() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED;
  }
}
