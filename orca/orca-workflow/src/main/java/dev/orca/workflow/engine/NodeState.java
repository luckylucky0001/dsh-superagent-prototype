package dev.orca.workflow.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mutable execution state of one node; serialised as part of {@link RunState}. */
public class NodeState {
  public NodeStatus status = NodeStatus.PENDING;
  public int attempts;
  public Map<String, Object> output = new LinkedHashMap<>();
  public List<String> ports = new ArrayList<>();
  public String error;
  public String waitKey;
  /** Epoch millis after which a waiting node times out; 0 means no deadline. */
  public long deadlineAt;
  public long startedAt;
  public long finishedAt;

  public NodeState() {}

  public NodeState copy() {
    NodeState c = new NodeState();
    c.status = status;
    c.attempts = attempts;
    c.output = new LinkedHashMap<>(output);
    c.ports = new ArrayList<>(ports);
    c.error = error;
    c.waitKey = waitKey;
    c.deadlineAt = deadlineAt;
    c.startedAt = startedAt;
    c.finishedAt = finishedAt;
    return c;
  }
}
