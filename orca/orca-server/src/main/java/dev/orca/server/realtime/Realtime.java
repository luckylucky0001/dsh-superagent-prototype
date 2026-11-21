package dev.orca.server.realtime;

import dev.orca.protocol.Json;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-sent events to browsers. Events published inside a transaction are delivered after it
 * commits, so a client that refetches on an event always sees the change.
 */
@Service
public class Realtime {

  private final Set<SseEmitter> emitters = new CopyOnWriteArraySet<>();
  /** The token hash each stream was opened with: the stream ends when that token stops being valid. */
  private final Map<SseEmitter, String> owners = new java.util.concurrent.ConcurrentHashMap<>();
  private final AtomicLong ids = new AtomicLong();
  private final dev.orca.server.repo.ApiTokenRepo tokens;

  /** Streams one token may hold open at a time (browser tabs). */
  static final int PER_TOKEN = 20;

  public Realtime(dev.orca.server.repo.ApiTokenRepo tokens) {
    this.tokens = tokens;
  }

  public SseEmitter subscribe() {
    return subscribe(null);
  }

  /** {@code tokenHash}: the session or personal token the stream was opened with. */
  public SseEmitter subscribe(String tokenHash) {
    SseEmitter e = new SseEmitter(0L);
    if (tokenHash != null && owners.values().stream().filter(tokenHash::equals).count() >= PER_TOKEN) {
      e.complete();
      return e;
    }
    emitters.add(e);
    if (tokenHash != null) {
      owners.put(e, tokenHash);
    }
    e.onCompletion(() -> drop(e));
    e.onTimeout(() -> drop(e));
    e.onError(x -> drop(e));
    send(e, "hello", Map.of("ok", true));
    return e;
  }

  public void publish(String type, Object data) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          broadcast(type, data);
        }
      });
    } else {
      broadcast(type, data);
    }
  }

  public static Map<String, Object> ids(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      if (kv[i + 1] != null) {
        m.put(String.valueOf(kv[i]), kv[i + 1]);
      }
    }
    return m;
  }

  /** One virtual thread per client, so a stalled browser never blocks the thread that published. */
  private void broadcast(String type, Object data) {
    String json = Json.write(data);
    for (SseEmitter e : emitters) {
      Thread.ofVirtual().start(() -> sendRaw(e, type, json));
    }
  }

  private void sendRaw(SseEmitter e, String type, String json) {
    try {
      synchronized (e) {
        e.send(SseEmitter.event().id(Long.toString(ids.incrementAndGet())).name(type).data(json));
      }
    } catch (IOException | IllegalStateException ex) {
      emitters.remove(e);
    }
  }

  private void send(SseEmitter e, String type, Object data) {
    try {
      e.send(SseEmitter.event().id(Long.toString(ids.incrementAndGet())).name(type).data(Json.write(data)));
    } catch (IOException | IllegalStateException ex) {
      emitters.remove(e);
    }
  }

  private void drop(SseEmitter e) {
    emitters.remove(e);
    owners.remove(e);
  }

  @Scheduled(fixedDelay = 20_000)
  void keepAlive() {
    long now = System.currentTimeMillis();
    for (SseEmitter e : emitters) {
      String owner = owners.get(e);
      // signed out, password changed, member removed or token expired: stop streaming to it
      if (owner != null && tokens.findByTokenHash(owner).filter(t -> t.expiresAt == null || t.expiresAt > now).isEmpty()) {
        drop(e);
        e.complete();
        continue;
      }
      try {
        e.send(SseEmitter.event().comment("ping"));
      } catch (IOException | IllegalStateException ex) {
        emitters.remove(e);
      }
    }
  }
}
