package dev.fellowloop.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fellowloop.protocol.AgentEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventBufferTest {

  private static AgentEvent chunk(String item, String text) {
    return AgentEvent.of(AgentEvent.TOOL_OUTPUT, "itemId", item, "text", text);
  }

  @Test
  void chunksOfOneItemMergeInOrder() {
    EventBuffer b = new EventBuffer();
    b.add(chunk("t1", "a"));
    b.add(chunk("t1", "b"));
    b.add(chunk("t2", "c"));
    b.add(chunk("t1", "d"));
    List<AgentEvent> out = b.drain();
    assertEquals(List.of("ab", "c", "d"), out.stream().map(e -> e.data().get("text")).toList());
    assertEquals(List.of(1L, 2L, 3L), out.stream().map(AgentEvent::seq).toList());
  }

  @Test
  void aHugeOutputKeepsItsStartAndEnd() {
    EventBuffer b = new EventBuffer();
    String line = "x".repeat(999) + "\n";
    b.add(chunk("t1", "START\n"));
    for (int i = 0; i < 2_000; i++) {
      b.add(chunk("t1", line)); // 2 MB of build log
    }
    b.add(chunk("t1", "END"));
    String text = (String) b.drain().get(0).data().get("text");
    assertTrue(text.startsWith("START"));
    assertTrue(text.endsWith("END"));
    assertTrue(text.contains("characters omitted"));
    assertTrue(text.length() < 2 * EventBuffer.ITEM_KEEP + 70_000, "bounded: " + text.length());
  }

  @Test
  void aLongOutageDropsStreamedChunksButKeepsTheRest() {
    EventBuffer b = new EventBuffer();
    for (int i = 0; i < EventBuffer.MAX_PENDING; i++) {
      b.add(chunk("t" + i, "x")); // distinct items: nothing merges
    }
    b.add(chunk("late", "dropped"));
    b.add(AgentEvent.of(AgentEvent.TOOL_COMPLETED, "itemId", "late"));
    List<AgentEvent> out = b.drain();
    assertEquals(EventBuffer.MAX_PENDING + 2, out.size(), "the completion is kept, plus a notice");
    assertTrue(String.valueOf(out.get(out.size() - 1).data().get("message")).contains("1 streamed output chunks were dropped"));
  }
}
