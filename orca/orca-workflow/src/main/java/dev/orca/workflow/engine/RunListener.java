package dev.orca.workflow.engine;

/** Observes state changes, e.g. to persist node records and publish realtime events. */
public interface RunListener {
  RunListener NONE = new RunListener() {};

  default void nodeChanged(RunState run, String nodeId, NodeState node) {}

  default void runChanged(RunState run) {}
}
