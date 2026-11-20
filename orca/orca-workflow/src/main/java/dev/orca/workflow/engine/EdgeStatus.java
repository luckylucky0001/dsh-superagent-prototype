package dev.orca.workflow.engine;

/** Whether an edge carried control: {@code ACTIVE} edges were taken, {@code DEAD} ones were not. */
public enum EdgeStatus {
  PENDING,
  ACTIVE,
  DEAD
}
