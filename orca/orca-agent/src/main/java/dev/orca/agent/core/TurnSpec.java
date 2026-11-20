package dev.orca.agent.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * One request to the agent.
 *
 * @param outputSchema JSON Schema for the final answer, or null for free text
 */
public record TurnSpec(String text, List<Path> images, Map<String, Object> outputSchema, Duration timeout) {
  public TurnSpec {
    images = images == null ? List.of() : List.copyOf(images);
    timeout = timeout == null ? Duration.ofMinutes(60) : timeout;
  }

  public static TurnSpec text(String text) {
    return new TurnSpec(text, List.of(), null, null);
  }
}
