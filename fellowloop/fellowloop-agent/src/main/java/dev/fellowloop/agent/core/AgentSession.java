package dev.fellowloop.agent.core;

import java.util.Set;

/**
 * A live conversation with one agent CLI process. {@link #runTurn} blocks; {@link #steer} and
 * {@link #interrupt} may be called from other threads while a turn runs.
 */
public interface AgentSession extends AutoCloseable {

  /** Provider session id (Codex thread id, Trae ACP session id); resumable on the same machine. */
  String sessionRef();

  String model();

  boolean resumed();

  /** Capabilities of this session, e.g. {@code steer}, {@code structured_output}. */
  Set<String> capabilities();

  TurnResult runTurn(TurnSpec turn, AgentEventSink sink) throws AgentException;

  /** Adds input to the running turn. Throws {@link UnsupportedOperationException} when unsupported. */
  void steer(String text) throws AgentException;

  void interrupt();

  @Override
  void close();
}
