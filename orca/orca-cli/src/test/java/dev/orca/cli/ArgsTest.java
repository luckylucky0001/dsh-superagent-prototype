package dev.orca.cli;

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
}
