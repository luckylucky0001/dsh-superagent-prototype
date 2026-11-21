package dev.fellowloop.agent.core;

import dev.fellowloop.protocol.Json;

/** Parses a final answer that should be JSON, tolerating a Markdown code fence around it. */
public final class StructuredOutput {

  private StructuredOutput() {}

  public static Object parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("empty answer");
    }
    String t = text.trim();
    if (t.startsWith("```")) {
      int nl = t.indexOf('\n');
      int end = t.lastIndexOf("```");
      if (nl > 0 && end > nl) {
        t = t.substring(nl + 1, end).trim();
      }
    }
    try {
      return Json.MAPPER.readValue(t, Object.class);
    } catch (RuntimeException e) {
      int start = Math.min(indexOrMax(t, '{'), indexOrMax(t, '['));
      int end = Math.max(t.lastIndexOf('}'), t.lastIndexOf(']'));
      if (start < end) {
        try {
          return Json.MAPPER.readValue(t.substring(start, end + 1), Object.class);
        } catch (RuntimeException ignored) {
          // fall through
        }
      }
      throw new IllegalArgumentException(e.getMessage() == null ? "not JSON" : e.getMessage().split("\n")[0]);
    }
  }

  private static int indexOrMax(String s, char c) {
    int i = s.indexOf(c);
    return i < 0 ? Integer.MAX_VALUE : i;
  }
}
