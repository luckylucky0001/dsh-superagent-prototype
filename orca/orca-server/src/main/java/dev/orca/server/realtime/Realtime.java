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
  private final AtomicLong ids = new AtomicLong();

  public SseEmitter subscribe() {
    SseEmitter e = new SseEmitter(0L);
    emitters.add(e);
    e.onCompletion(() -> emitters.remove(e));
    e.onTimeout(() -> emitters.remove(e));
    e.onError(x -> emitters.remove(e));
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

  private void broadcast(String type, Object data) {
    for (SseEmitter e : emitters) {
      send(e, type, data);
    }
  }

  private void send(SseEmitter e, String type, Object data) {
    try {
      e.send(SseEmitter.event().id(Long.toString(ids.incrementAndGet())).name(type).data(Json.write(data)));
    } catch (IOException | IllegalStateException ex) {
      emitters.remove(e);
    }
  }

  @Scheduled(fixedDelay = 20_000)
  void keepAlive() {
    for (SseEmitter e : emitters) {
      try {
        e.send(SseEmitter.event().comment("ping"));
      } catch (IOException | IllegalStateException ex) {
        emitters.remove(e);
      }
    }
  }
}
