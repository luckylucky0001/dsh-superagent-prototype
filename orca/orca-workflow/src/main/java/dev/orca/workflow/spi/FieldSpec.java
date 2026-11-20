package dev.orca.workflow.spi;

import java.util.List;

/** Describes one configuration field of a component; the editor builds its form from these. */
public record FieldSpec(
    String name,
    String label,
    FieldKind kind,
    boolean required,
    Object defaultValue,
    List<Option> options,
    String help) {

  public FieldSpec {
    options = options == null ? List.of() : List.copyOf(options);
  }

  public static FieldSpec of(String name, String label, FieldKind kind, boolean required) {
    return new FieldSpec(name, label, kind, required, null, List.of(), null);
  }

  public FieldSpec withDefault(Object value) {
    return new FieldSpec(name, label, kind, required, value, options, help);
  }

  public FieldSpec withHelp(String text) {
    return new FieldSpec(name, label, kind, required, defaultValue, options, text);
  }

  public FieldSpec withOptions(List<Option> opts) {
    return new FieldSpec(name, label, kind, required, defaultValue, opts, help);
  }

  public record Option(String value, String label) {}
}
