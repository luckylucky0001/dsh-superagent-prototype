package dev.fellowloop.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One normalised step of an agent run. Every provider driver maps its own protocol to these types,
 * so the server stores and the UI renders one format.
 *
 * @param seq  per-task sequence number assigned by the daemon, starting at 1
 * @param ts   epoch millis
 * @param type one of the constants below
 * @param data type-specific fields, documented next to each constant
 */
public record AgentEvent(long seq, long ts, String type, Map<String, Object> data) {

  /** {@code text}: the prompt the platform sent. */
  public static final String USER_MESSAGE = "user.message";
  /** {@code sessionRef, model, provider, resumed}. */
  public static final String SESSION = "session";
  /** {@code turnId}. */
  public static final String TURN_STARTED = "turn.started";
  /** {@code itemId, text}: a chunk of the agent's reply. */
  public static final String MESSAGE_DELTA = "message.delta";
  /** {@code itemId, text, phase}: a complete agent message; {@code phase=final_answer} marks the result. */
  public static final String MESSAGE = "message";
  /** {@code itemId, text}: reasoning summary chunk. */
  public static final String REASONING_DELTA = "reasoning.delta";
  /** {@code itemId, text}. */
  public static final String REASONING = "reasoning";
  /** {@code itemId, kind (command|file_change|mcp|web_search|other), title, input}. */
  public static final String TOOL_STARTED = "tool.started";
  /** {@code itemId, text}: streamed tool output, e.g. a command's stdout. */
  public static final String TOOL_OUTPUT = "tool.output";
  /** {@code itemId, kind, title, status (completed|failed|declined), output, exitCode, changes}. */
  public static final String TOOL_COMPLETED = "tool.completed";
  /** {@code entries: [{step, status}], explanation}. */
  public static final String PLAN = "plan";
  /** {@code diff}: unified diff of the turn so far. */
  public static final String DIFF = "diff";
  /**
   * {@code inputTokens, cachedInputTokens, outputTokens, reasoningTokens, totalTokens} (session totals),
   * {@code taskTokens} (this task only, when known), {@code contextWindow}, {@code lastTotalTokens}.
   */
  public static final String USAGE = "usage";
  /** {@code requestId, kind (command|file_change|permissions|other), title, detail}. */
  public static final String APPROVAL_REQUESTED = "approval.requested";
  /** {@code requestId, decision, by (policy|human)}. */
  public static final String APPROVAL_RESOLVED = "approval.resolved";
  /** {@code requestId, title, questions|schema}: the agent asks a person. */
  public static final String INPUT_REQUESTED = "input.requested";
  /** {@code requestId, answer}. */
  public static final String INPUT_RESOLVED = "input.resolved";
  /** {@code turnId, status (completed|interrupted|failed), errorCode, error}. */
  public static final String TURN_COMPLETED = "turn.completed";
  /** {@code level (info|warn|error), message, code}. */
  public static final String NOTICE = "notice";

  public AgentEvent {
    data = data == null ? new LinkedHashMap<>() : data;
  }

  public static AgentEvent of(String type, Object... kv) {
    Map<String, Object> d = new LinkedHashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      if (kv[i + 1] != null) {
        d.put(String.valueOf(kv[i]), kv[i + 1]);
      }
    }
    return new AgentEvent(0, System.currentTimeMillis(), type, d);
  }

  public AgentEvent withSeq(long s) {
    return new AgentEvent(s, ts, type, data);
  }

  public boolean isDelta() {
    return type.endsWith(".delta") || type.equals(TOOL_OUTPUT);
  }
}
