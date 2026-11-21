package dev.fellowloop.agent.core;

/** Keeps event payloads bounded; the head and tail of long output are what people read. */
public final class Truncate {
  public static final int TOOL_OUTPUT = 32_000;

  private Truncate() {}

  public static String middle(String s, int max) {
    if (s == null || s.length() <= max) {
      return s;
    }
    int half = max / 2;
    return s.substring(0, half) + "\n… [" + (s.length() - max) + " characters omitted] …\n" + s.substring(s.length() - half);
  }
}
