package dev.orca.workflow.model;

import java.util.List;
import java.util.Optional;

/** An immutable workflow graph. Cycles are rejected by the validator. */
public record WorkflowDefinition(List<NodeDef> nodes, List<EdgeDef> edges) {

  public WorkflowDefinition {
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
    edges = edges == null ? List.of() : List.copyOf(edges);
  }

  public Optional<NodeDef> node(String id) {
    return nodes.stream().filter(n -> n.id().equals(id)).findFirst();
  }

  public NodeDef requireNode(String id) {
    return node(id).orElseThrow(() -> new IllegalArgumentException("unknown node: " + id));
  }

  public List<EdgeDef> outgoing(String nodeId) {
    return edges.stream().filter(e -> e.source().equals(nodeId)).toList();
  }

  public List<EdgeDef> incoming(String nodeId) {
    return edges.stream().filter(e -> e.target().equals(nodeId)).toList();
  }
}
