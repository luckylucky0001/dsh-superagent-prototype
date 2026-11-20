package dev.orca.daemon;

import dev.orca.protocol.AgentEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Collects a task's events between flushes, merging consecutive text deltas of the same item so a
 * streamed answer costs a handful of frames instead of one per token. Keeps sent events until the
 * server acknowledges them, so they can be replayed after a reconnect.
 */
final class EventBuffer {

  private static final int MAX_UNACKED = 20_000;

  private final List<AgentEvent> pending = new ArrayList<>();
  private final List<AgentEvent> unacked = new ArrayList<>();
  private long seq;
  private long acked;

  synchronized void add(AgentEvent e) {
    if (e.isDelta() && !pending.isEmpty()) {
      AgentEvent last = pending.get(pending.size() - 1);
      if (last.type().equals(e.type()) && Objects.equals(last.data().get("itemId"), e.data().get("itemId"))) {
        Map<String, Object> merged = new LinkedHashMap<>(last.data());
        merged.put("text", String.valueOf(last.data().getOrDefault("text", "")) + e.data().getOrDefault("text", ""));
        pending.set(pending.size() - 1, new AgentEvent(0, last.ts(), last.type(), merged));
        return;
      }
    }
    pending.add(e);
  }

  /** Assigns sequence numbers to pending events and returns them; empty when nothing is pending. */
  synchronized List<AgentEvent> drain() {
    if (pending.isEmpty()) {
      return List.of();
    }
    List<AgentEvent> out = new ArrayList<>(pending.size());
    for (AgentEvent e : pending) {
      AgentEvent s = e.withSeq(++seq);
      out.add(s);
      unacked.add(s);
    }
    pending.clear();
    while (unacked.size() > MAX_UNACKED) {
      unacked.remove(0);
    }
    return out;
  }

  synchronized void ack(long upTo) {
    acked = Math.max(acked, upTo);
    unacked.removeIf(e -> e.seq() <= upTo);
  }

  /** Events sent but not acknowledged, for replay after a reconnect. */
  synchronized List<AgentEvent> unacked() {
    return List.copyOf(unacked);
  }

  synchronized long lastSeq() {
    return seq;
  }
}
