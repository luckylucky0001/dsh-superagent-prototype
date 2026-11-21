package dev.fellowloop.agent.core;

import dev.fellowloop.protocol.ProviderStatus;

/** One agent CLI: how to find it, check it and open sessions with it. */
public interface AgentProvider {

  /** {@code codex} or {@code trae}. */
  String id();

  /** Version, login state, models and capabilities. Never throws. */
  ProviderStatus probe();

  AgentSession open(SessionSpec spec, Interaction.Handler handler) throws AgentException;
}
