package dev.fellowloop.workflow.spi;

import java.util.List;

/**
 * Static metadata of a component.
 *
 * @param type     unique type id, e.g. {@code agent.run}
 * @param category {@code trigger}, {@code agent}, {@code logic}, {@code human} or {@code action}
 * @param trigger  trigger components start runs and must have no incoming edges
 * @param ports    output ports; edges leave from one of these
 */
public record NodeDescriptor(
    String type,
    String category,
    String label,
    String description,
    boolean trigger,
    List<FieldSpec> fields,
    List<String> ports,
    List<OutputSpec> outputs) {

  public NodeDescriptor {
    fields = fields == null ? List.of() : List.copyOf(fields);
    ports = ports == null || ports.isEmpty() ? List.of("out") : List.copyOf(ports);
    outputs = outputs == null ? List.of() : List.copyOf(outputs);
  }
}
