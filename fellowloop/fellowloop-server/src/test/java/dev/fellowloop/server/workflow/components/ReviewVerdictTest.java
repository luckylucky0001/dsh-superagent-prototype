package dev.fellowloop.server.workflow.components;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewVerdictTest {

  @Test
  void looseVerdictsAreReadForgivingly() {
    assertEquals("pass", ReviewLoopComponent.normalizeVerdict("PASS"));
    assertEquals("pass", ReviewLoopComponent.normalizeVerdict(" Passed "));
    assertEquals("changes_requested", ReviewLoopComponent.normalizeVerdict("Changes requested"));
    assertEquals("changes_requested", ReviewLoopComponent.normalizeVerdict(null));
    assertEquals(List.of(), ReviewLoopComponent.issueList(null), "issues: null used to throw even on a pass");
    assertEquals(List.of("fix the tests"), ReviewLoopComponent.issueList("fix the tests"));
    assertEquals(List.of("a", "b"), ReviewLoopComponent.issueList(Arrays.asList("a", null, " ", "b")));
  }
}
