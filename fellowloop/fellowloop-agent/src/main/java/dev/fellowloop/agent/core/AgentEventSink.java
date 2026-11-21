package dev.fellowloop.agent.core;

import dev.fellowloop.protocol.AgentEvent;

/** Receives normalised events as the agent works. Called from driver threads; must not block long. */
@FunctionalInterface
public interface AgentEventSink {
  void accept(AgentEvent event);
}
