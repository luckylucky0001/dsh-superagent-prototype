package dev.orca.workflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orca.workflow.spi.FieldKind;
import dev.orca.workflow.spi.FieldSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FieldSpecTest {

  @Test
  void visibilityFollowsTheControllingFieldOrItsDefault() {
    FieldSpec mode = FieldSpec.of("mode", "Mode", FieldKind.SELECT, false).withDefault("work");
    FieldSpec target = FieldSpec.of("target", "Target", FieldKind.SELECT, true).visibleWhen("mode", "review");
    List<FieldSpec> all = List.of(mode, target);
    assertFalse(target.visible(Map.of(), all), "default mode is work");
    assertTrue(target.visible(Map.of("mode", "review"), all));
    assertFalse(target.visible(Map.of("mode", "work"), all));
    assertTrue(mode.visible(Map.of(), all), "fields without a condition are always shown");
    FieldSpec branch = FieldSpec.of("branch", "Branch", FieldKind.STRING, false).visibleWhen("target", "base");
    List<FieldSpec> chain = List.of(mode, target.withDefault("base"), branch);
    assertFalse(branch.visible(Map.of(), chain), "hidden because its controlling field is hidden");
    assertTrue(branch.visible(Map.of("mode", "review"), chain));
  }
}
