package dev.orca.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orca.workflow.expr.Expression;
import dev.orca.workflow.expr.ExpressionException;
import dev.orca.workflow.expr.Template;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExpressionTest {
  private final Map<String, Object> vars = Map.of(
      "trigger", Map.of("issue", Map.of("title", "Fix Login", "labels", List.of("bug", "p1"), "number", 42)),
      "nodes", Map.of("review", Map.of("output", Map.of("verdict", "pass", "score", 8.5))));

  @Test
  void pathsComparisonsAndLogic() {
    assertEquals(true, Expression.eval("nodes.review.output.verdict == 'pass' && nodes.review.output.score >= 8", vars));
    assertEquals("bug", Expression.eval("trigger.issue.labels[0]", vars));
    assertEquals("p1", Expression.eval("trigger.issue.labels[-1]", vars));
    assertEquals(null, Expression.eval("trigger.missing.deep.path", vars));
    assertEquals(true, Expression.eval("contains(trigger.issue.labels, 'bug')", vars));
    assertEquals(true, Expression.eval("!empty(trigger.issue.title) && lower(trigger.issue.title) == 'fix login'", vars));
    assertEquals(true, Expression.eval("trigger.issue.number == '42'", vars), "numbers compare with numeric strings");
    assertEquals("big", Expression.eval("trigger.issue.number > 10 ? 'big' : 'small'", vars));
    assertEquals(2, Expression.eval("len(trigger.issue.labels)", vars));
    assertEquals("x", Expression.eval("default(trigger.none, 'x')", vars));
    assertEquals(true, Expression.eval("matchesAny(trigger.issue.title, ['login', 'auth'])", vars));
    assertEquals(7.0, Expression.eval("1 + 2 * 3", vars));
  }

  @Test
  void rejectsUnsafeOrBrokenInput() {
    assertThrows(ExpressionException.class, () -> Expression.parse("Runtime.getRuntime().exec('x')"));
    assertThrows(ExpressionException.class, () -> Expression.parse("a ==="));
    assertThrows(ExpressionException.class, () -> Expression.parse("'open"));
  }

  @Test
  void templates() {
    assertEquals("Issue #42: Fix Login", Template.render("Issue #{{ trigger.issue.number }}: {{ trigger.issue.title }}", vars));
    assertEquals(List.of("bug", "p1"), Template.render(" {{ trigger.issue.labels }} ", vars));
    assertEquals("labels=[\"bug\",\"p1\"]", Template.render("labels={{ trigger.issue.labels }}", vars));
    Object nested = Template.resolve(Map.of("a", List.of("{{ trigger.issue.number }}")), vars);
    assertTrue(nested.toString().contains("42"));
  }
}
