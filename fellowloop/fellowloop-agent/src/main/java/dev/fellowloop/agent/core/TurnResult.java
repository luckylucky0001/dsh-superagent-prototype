package dev.fellowloop.agent.core;

import java.util.Map;

/**
 * Outcome of one turn.
 *
 * @param finalText  the agent's final answer
 * @param structured parsed final answer when the turn had an output schema
 */
public record TurnResult(Status status, String finalText, Object structured, String errorCode, String error,
    Map<String, Object> usage) {

  public enum Status {
    COMPLETED,
    INTERRUPTED,
    FAILED
  }

  public TurnResult {
    usage = usage == null ? Map.of() : usage;
  }

  public boolean ok() {
    return status == Status.COMPLETED;
  }
}
