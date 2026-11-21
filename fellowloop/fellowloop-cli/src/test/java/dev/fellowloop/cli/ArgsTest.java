package dev.fellowloop.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ArgsTest {

  @Test
  void parsesOptionsFlagsAndRepeatedKeys() {
    Main.Args a = Main.Args.parse(new String[] {"issue", "create", "--title", "Fix putaway", "--label", "wms",
        "--label=urgent", "--json", "--assign", "Builder", "--", "--not-an-option"});
    assertEquals(List.of("issue", "create", "--not-an-option"), a.positional);
    assertEquals("Fix putaway", a.opt("title", null));
    assertEquals(List.of("wms", "urgent"), a.all("label"));
    assertTrue(a.flag("json"));
    assertEquals("Builder", a.opt("assign", null));
  }

  @Test
  void flagsDoNotSwallowTheNextWord() {
    Main.Args a = Main.Args.parse(new String[] {"run", "--wait", "Nightly check"});
    assertTrue(a.flag("wait"));
    assertEquals(List.of("run", "Nightly check"), a.positional);
  }

  @org.junit.jupiter.api.Test
  void columnTitlesAreReadable() {
    org.junit.jupiter.api.Assertions.assertEquals("VERSION", Main.header("daemonVersion"));
    org.junit.jupiter.api.Assertions.assertEquals("WORKFLOW", Main.header("workflowName"));
    org.junit.jupiter.api.Assertions.assertEquals("STARTED", Main.header("startedAt"));
    org.junit.jupiter.api.Assertions.assertEquals("ISSUE", Main.header("issueId"));
    org.junit.jupiter.api.Assertions.assertEquals("ID", Main.header("id"));
    org.junit.jupiter.api.Assertions.assertEquals("NAME", Main.header("name"));
  }

  @org.junit.jupiter.api.Test
  void longCommandsShowAsOneLine() {
    org.junit.jupiter.api.Assertions.assertEquals("npm test", Main.oneLine("npm test\n"));
    org.junit.jupiter.api.Assertions.assertEquals("python3 - <<'PY'  (+2 lines)", Main.oneLine("python3 - <<'PY'\nprint(1)\nPY"));
    org.junit.jupiter.api.Assertions.assertEquals(160, Main.oneLine("x".repeat(400)).length());
  }
}
