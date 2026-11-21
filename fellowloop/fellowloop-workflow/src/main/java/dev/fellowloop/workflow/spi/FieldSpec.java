package dev.fellowloop.workflow.spi;

import java.util.List;

/** Describes one configuration field of a component; the editor builds its form from these. */
public record FieldSpec(
    String name,
    String label,
    FieldKind kind,
    boolean required,
    Object defaultValue,
    List<Option> options,
    String help,
    VisibleWhen visibleWhen) {

  public FieldSpec {
    options = options == null ? List.of() : List.copyOf(options);
  }

  public FieldSpec(String name, String label, FieldKind kind, boolean required, Object defaultValue,
      List<Option> options, String help) {
    this(name, label, kind, required, defaultValue, options, help, null);
  }

  public static FieldSpec of(String name, String label, FieldKind kind, boolean required) {
    return new FieldSpec(name, label, kind, required, null, List.of(), null);
  }

  public FieldSpec withDefault(Object value) {
    return new FieldSpec(name, label, kind, required, value, options, help, visibleWhen);
  }

  public FieldSpec withHelp(String text) {
    return new FieldSpec(name, label, kind, required, defaultValue, options, text, visibleWhen);
  }

  public FieldSpec withOptions(List<Option> opts) {
    return new FieldSpec(name, label, kind, required, defaultValue, opts, help, visibleWhen);
  }

  /** Shows the field only while another field has one of these values (its default counts when unset). */
  public FieldSpec visibleWhen(String field, String... values) {
    return new FieldSpec(name, label, kind, required, defaultValue, options, help, new VisibleWhen(field, List.of(values)));
  }

  /** Whether the field applies, given a node's configuration and the other fields' defaults. */
  public boolean visible(java.util.Map<String, Object> config, List<FieldSpec> all) {
    return visible(config, all, 0);
  }

  private boolean visible(java.util.Map<String, Object> config, List<FieldSpec> all, int depth) {
    if (visibleWhen == null) {
      return true;
    }
    // a field that depends on a hidden field is hidden too
    FieldSpec parent = all.stream().filter(f -> f.name().equals(visibleWhen.field())).findFirst().orElse(null);
    if (depth > 8 || (parent != null && !parent.visible(config, all, depth + 1))) {
      return false;
    }
    Object v = config == null ? null : config.get(visibleWhen.field());
    if (v == null || (v instanceof String s && s.isBlank())) {
      v = all.stream().filter(f -> f.name().equals(visibleWhen.field())).map(FieldSpec::defaultValue)
          .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }
    return v != null && visibleWhen.values().contains(String.valueOf(v));
  }

  public record VisibleWhen(String field, List<String> values) {}

  public record Option(String value, String label) {}
}
