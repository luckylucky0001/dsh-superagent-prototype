package dev.orca.agent.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * One request to the agent.
 *
 * @param outputSchema JSON Schema for the final answer, or null for free text
 * @param review       a native code review target instead of a prompt, e.g. {@code {type: baseBranch, branch:
 *                     main}}; drivers without review support turn it into a prompt
 */
public record TurnSpec(String text, List<Path> images, Map<String, Object> outputSchema, Duration timeout,
    Map<String, Object> review) {

  /** What the server sends as the prompt of a review that has none; not an instruction for the reviewer. */
  public static final String DEFAULT_REVIEW_PROMPT = "Review the changes.";

  public TurnSpec {
    images = images == null ? List.of() : List.copyOf(images);
    timeout = timeout == null ? Duration.ofMinutes(60) : timeout;
    review = review == null || review.isEmpty() ? null : Map.copyOf(review);
  }

  public TurnSpec(String text, List<Path> images, Map<String, Object> outputSchema, Duration timeout) {
    this(text, images, outputSchema, timeout, null);
  }

  /** A prompt that asks for the same review, for agents without a native review mode. */
  public static String reviewPrompt(Map<String, Object> review, String extra) {
    String type = String.valueOf(review.get("type"));
    String what = switch (type) {
      case "baseBranch" -> "the changes on the current branch compared with `" + review.getOrDefault("branch", "main")
          + "` (use git diff " + review.getOrDefault("branch", "main") + "...HEAD)";
      case "uncommittedChanges" -> "the uncommitted changes in the working tree (git status, git diff)";
      case "commit" -> "commit " + review.get("sha");
      default -> "the code as described below";
    };
    String instructions = review.get("instructions") instanceof String s && !s.isBlank() ? s : null;
    StringBuilder b = new StringBuilder("Review ").append(what).append(". Look for bugs, missing tests, security ")
        .append("problems and unclear code. Do not change any files. Reply with a prioritised list of findings, ")
        .append("each with file and line, or say that you found nothing to fix.");
    if (instructions != null) {
      b.append("\n\n").append(instructions);
    }
    if (extra != null && !extra.isBlank()) {
      b.append("\n\n").append(extra);
    }
    return b.toString();
  }

  public static TurnSpec text(String text) {
    return new TurnSpec(text, List.of(), null, null);
  }
}
