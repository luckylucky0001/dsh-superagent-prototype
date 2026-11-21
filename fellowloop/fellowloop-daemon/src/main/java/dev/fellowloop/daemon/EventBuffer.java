package dev.fellowloop.daemon;

import dev.fellowloop.protocol.AgentEvent;
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
  /** Events waiting to be sent (a long outage): beyond this, streamed text and output chunks are dropped. */
  static final int MAX_PENDING = 50_000;
  /** Merged text of one streamed item keeps this much of its start and of its end. */
  static final int ITEM_KEEP = 128_000;

  private final List<AgentEvent> pending = new ArrayList<>();
  private final List<AgentEvent> unacked = new ArrayList<>();
  private long seq;
  private long acked;
  /** Text of the last pending event while deltas keep merging into it (appending, not copying, each chunk). */
  private StringBuilder open;
  private long openOmitted;
  private long droppedDeltas;

  synchronized void add(AgentEvent e) {
    if (e.isDelta() && !pending.isEmpty()) {
      AgentEvent last = pending.get(pending.size() - 1);
      if (last.type().equals(e.type()) && Objects.equals(last.data().get("itemId"), e.data().get("itemId"))) {
        if (open == null) {
          open = new StringBuilder(String.valueOf(last.data().getOrDefault("text", "")));
        }
        open.append(e.data().getOrDefault("text", ""));
        if (open.length() > 2L * ITEM_KEEP + 65_536) {
          // a verbose command (a build log, `cat` of a big file): keep its start and its end
          int cut = open.length() - 2 * ITEM_KEEP;
          openOmitted += cut;
          open.delete(ITEM_KEEP, ITEM_KEEP + cut);
        }
        return;
      }
    }
    if (e.isDelta() && pending.size() >= MAX_PENDING) {
      droppedDeltas++;
      return;
    }
    close();
    pending.add(e);
  }

  /** Writes the merged text back into the last pending event. */
  private void close() {
    if (open == null) {
      return;
    }
    AgentEvent last = pending.get(pending.size() - 1);
    Map<String, Object> merged = new LinkedHashMap<>(last.data());
    String text = open.toString();
    if (openOmitted > 0) {
      text = text.substring(0, ITEM_KEEP) + "\n… [" + openOmitted + " characters omitted] …\n" + text.substring(ITEM_KEEP);
    }
    merged.put("text", text);
    pending.set(pending.size() - 1, new AgentEvent(0, last.ts(), last.type(), merged));
    open = null;
    openOmitted = 0;
  }

  /** Assigns sequence numbers to pending events and returns them; empty when nothing is pending. */
  synchronized List<AgentEvent> drain() {
    close();
    if (droppedDeltas > 0) {
      pending.add(AgentEvent.of(AgentEvent.NOTICE, "level", "warn", "message",
          droppedDeltas + " streamed output chunks were dropped while the server was out of reach"));
      droppedDeltas = 0;
    }
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
