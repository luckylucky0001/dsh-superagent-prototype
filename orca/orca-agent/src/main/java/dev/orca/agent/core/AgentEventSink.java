package dev.orca.agent.core;

import dev.orca.protocol.AgentEvent;

/** Receives normalised events as the agent works. Called from driver threads; must not block long. */
@FunctionalInterface
public interface AgentEventSink {
  void accept(AgentEvent event);
}
