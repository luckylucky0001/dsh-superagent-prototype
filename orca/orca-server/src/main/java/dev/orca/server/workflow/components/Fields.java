package dev.orca.server.workflow.components;

import dev.orca.workflow.spi.FieldKind;
import dev.orca.workflow.spi.FieldSpec;
import dev.orca.workflow.spi.NodeDescriptor;
import dev.orca.workflow.spi.OutputSpec;
import java.util.Arrays;
import java.util.List;

/** Small builders that keep component descriptors readable. */
final class Fields {
  private Fields() {}

  static FieldSpec f(String name, String label, FieldKind kind) {
    return FieldSpec.of(name, label, kind, false);
  }

  static FieldSpec req(String name, String label, FieldKind kind) {
    return FieldSpec.of(name, label, kind, true);
  }

  static FieldSpec select(String name, String label, boolean required, Object def, String... values) {
    return FieldSpec.of(name, label, FieldKind.SELECT, required).withDefault(def)
        .withOptions(Arrays.stream(values).map(v -> new FieldSpec.Option(v, v)).toList());
  }

  static OutputSpec out(String name, String type, String description) {
    return new OutputSpec(name, type, description);
  }

  static NodeDescriptor d(String type, String category, String label, String description, boolean trigger,
      List<FieldSpec> fields, List<String> ports, List<OutputSpec> outputs) {
    return new NodeDescriptor(type, category, label, description, trigger, fields, ports, outputs);
  }
}
