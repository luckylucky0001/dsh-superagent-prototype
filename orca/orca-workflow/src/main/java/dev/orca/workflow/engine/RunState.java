package dev.orca.workflow.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The complete, serialisable state of one workflow run. The engine is stateless: hosts load this,
 * pass it to {@link WorkflowEngine}, and store the result.
 */
public class RunState {
  public String runId;
  public String workflowId;
  public RunStatus status = RunStatus.RUNNING;
  public String triggerNodeId;
  public Map<String, Object> trigger = new LinkedHashMap<>();
  public Map<String, NodeState> nodes = new LinkedHashMap<>();
  public Map<String, EdgeStatus> edges = new LinkedHashMap<>();
  public String error;
  public long startedAt;
  public long finishedAt;

  public RunState() {}

  public NodeState node(String id) {
    NodeState n = nodes.get(id);
    if (n == null) {
      throw new IllegalArgumentException("unknown node: " + id);
    }
    return n;
  }
}
