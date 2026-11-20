package dev.orca.workflow.engine;

import dev.orca.workflow.expr.Expression;
import dev.orca.workflow.expr.ExpressionException;
import dev.orca.workflow.expr.Template;
import dev.orca.workflow.model.EdgeDef;
import dev.orca.workflow.model.NodeDef;
import dev.orca.workflow.model.WorkflowDefinition;
import dev.orca.workflow.spi.ComponentRegistry;
import dev.orca.workflow.spi.FieldKind;
import dev.orca.workflow.spi.FieldSpec;
import dev.orca.workflow.spi.NodeComponent;
import dev.orca.workflow.spi.NodeDescriptor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Static checks run before a definition is saved or started. */
public final class WorkflowValidator {

  private static final Pattern ID = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

  private final ComponentRegistry registry;

  public WorkflowValidator(ComponentRegistry registry) {
    this.registry = registry;
  }

  public List<ValidationIssue> validate(WorkflowDefinition def) {
    List<ValidationIssue> issues = new ArrayList<>();
    Map<String, NodeDef> byId = new HashMap<>();
    Map<String, NodeDescriptor> descriptors = new HashMap<>();

    if (def.nodes().isEmpty()) {
      issues.add(ValidationIssue.graph("the workflow has no nodes"));
      return issues;
    }
    for (NodeDef n : def.nodes()) {
      if (n.id() == null || !ID.matcher(n.id()).matches()) {
        issues.add(ValidationIssue.node(n.id(), "node id must be letters, digits or '_' and start with a letter"));
        continue;
      }
      if (byId.put(n.id(), n) != null) {
        issues.add(ValidationIssue.node(n.id(), "duplicate node id"));
      }
      NodeComponent c = n.type() == null ? null : registry.find(n.type()).orElse(null);
      if (c == null) {
        issues.add(ValidationIssue.node(n.id(), "unknown component type: " + n.type()));
        continue;
      }
      NodeDescriptor d = c.descriptor();
      descriptors.put(n.id(), d);
      for (FieldSpec f : d.fields()) {
        if (!f.visible(n.config(), d.fields())) {
          continue;
        }
        Object v = n.config().get(f.name());
        if (f.required() && (v == null || (v instanceof String s && s.isBlank())) && f.defaultValue() == null) {
          issues.add(ValidationIssue.node(n.id(), "'" + f.label() + "' is required"));
        }
        if (v instanceof String s && !s.isBlank()) {
          try {
            if (f.kind() == FieldKind.EXPRESSION && !Template.hasPlaceholders(s)) {
              Expression.parse(s);
            } else {
              Template.check(s);
            }
          } catch (ExpressionException e) {
            issues.add(ValidationIssue.node(n.id(), "'" + f.label() + "': " + e.getMessage()));
          }
        }
      }
    }

    Set<String> edgeIds = new HashSet<>();
    Map<String, Integer> indegree = new HashMap<>();
    for (NodeDef n : def.nodes()) {
      indegree.put(n.id(), 0);
    }
    for (EdgeDef e : def.edges()) {
      if (e.id() == null || !edgeIds.add(e.id())) {
        issues.add(ValidationIssue.edge(e.id(), "edge id missing or duplicated"));
      }
      NodeDef src = byId.get(e.source());
      NodeDef dst = byId.get(e.target());
      if (src == null || dst == null) {
        issues.add(ValidationIssue.edge(e.id(), "edge connects a missing node"));
        continue;
      }
      if (e.source().equals(e.target())) {
        issues.add(ValidationIssue.edge(e.id(), "a node cannot connect to itself"));
      }
      NodeDescriptor sd = descriptors.get(src.id());
      if (sd != null) {
        boolean errorPort = EdgeDef.ERROR_PORT.equals(e.sourcePort()) && src.onError() == NodeDef.OnError.CONTINUE;
        if (!sd.ports().contains(e.sourcePort()) && !errorPort) {
          issues.add(ValidationIssue.edge(e.id(), "'" + src.displayName() + "' has no output port '" + e.sourcePort() + "'"));
        }
      }
      NodeDescriptor td = descriptors.get(dst.id());
      if (td != null && td.trigger()) {
        issues.add(ValidationIssue.edge(e.id(), "a trigger cannot have incoming edges"));
      }
      if (e.condition() != null) {
        try {
          Expression.parse(e.condition());
        } catch (ExpressionException ex) {
          issues.add(ValidationIssue.edge(e.id(), "condition: " + ex.getMessage()));
        }
      }
      indegree.merge(e.target(), 1, Integer::sum);
    }

    boolean anyTrigger = false;
    for (NodeDef n : def.nodes()) {
      NodeDescriptor d = descriptors.get(n.id());
      if (d == null) {
        continue;
      }
      if (d.trigger()) {
        anyTrigger = true;
      } else if (indegree.getOrDefault(n.id(), 0) == 0) {
        issues.add(ValidationIssue.node(n.id(), "'" + n.displayName() + "' is not connected to any trigger"));
      }
    }
    if (!anyTrigger) {
      issues.add(ValidationIssue.graph("the workflow needs at least one trigger"));
    }
    if (hasCycle(def, byId.keySet())) {
      issues.add(ValidationIssue.graph("the workflow contains a cycle; loops are not supported"));
    }
    return issues;
  }

  private static boolean hasCycle(WorkflowDefinition def, Set<String> ids) {
    Map<String, Integer> indeg = new HashMap<>();
    Map<String, List<String>> out = new HashMap<>();
    for (String id : ids) {
      indeg.put(id, 0);
      out.put(id, new ArrayList<>());
    }
    for (EdgeDef e : def.edges()) {
      if (ids.contains(e.source()) && ids.contains(e.target())) {
        out.get(e.source()).add(e.target());
        indeg.merge(e.target(), 1, Integer::sum);
      }
    }
    Deque<String> q = new ArrayDeque<>();
    indeg.forEach((k, v) -> {
      if (v == 0) {
        q.add(k);
      }
    });
    int seen = 0;
    while (!q.isEmpty()) {
      String n = q.poll();
      seen++;
      for (String t : out.get(n)) {
        if (indeg.merge(t, -1, Integer::sum) == 0) {
          q.add(t);
        }
      }
    }
    return seen != ids.size();
  }
}
