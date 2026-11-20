package dev.orca.daemon;

import dev.orca.protocol.Json;
import dev.orca.protocol.Wire;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A WebSocket to the server that reconnects with exponential backoff. */
final class ServerLink implements Link {

  private static final Logger log = LoggerFactory.getLogger(ServerLink.class);

  private final URI uri;
  private final String token;
  private final Consumer<Wire.Frame> onFrame;
  private final Runnable onOpen;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private volatile WebSocket socket;
  /** True once HELLO and the replay of unacknowledged events went out; new events wait until then. */
  private volatile boolean ready;
  private volatile boolean stopped;
  private final Object sendLock = new Object();

  ServerLink(String server, String token, Consumer<Wire.Frame> onFrame, Runnable onOpen) {
    String base = server.endsWith("/") ? server.substring(0, server.length() - 1) : server;
    this.uri = URI.create(base.replaceFirst("^http", "ws") + "/api/daemon/ws");
    this.token = token;
    this.onFrame = onFrame;
    this.onOpen = onOpen;
  }

  /** Connects and stays connected until {@link #stop()}. Blocks the calling thread. */
  void runForever() {
    long backoff = 1000;
    while (!stopped) {
      CompletableFuture<Void> closed = new CompletableFuture<>();
      try {
        socket = http.newWebSocketBuilder()
            .header("Authorization", "Bearer " + token)
            .connectTimeout(Duration.ofSeconds(15))
            .buildAsync(uri, new Listener(closed))
            .get(20, TimeUnit.SECONDS);
        log.info("connected to {}", uri);
        backoff = 1000;
        onOpen.run();
        ready = true;
        closed.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        log.warn("connection to {} failed: {}", uri, rootMessage(e));
      }
      ready = false;
      socket = null;
      if (stopped) {
        return;
      }
      try {
        Thread.sleep(backoff + (long) (Math.random() * 500));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      backoff = Math.min(backoff * 2, 30_000);
    }
  }

  void stop() {
    stopped = true;
    WebSocket ws = socket;
    if (ws != null) {
      ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
    }
  }

  @Override
  public boolean connected() {
    WebSocket ws = socket;
    return ready && ws != null && !ws.isOutputClosed();
  }

  @Override
  public boolean send(Wire.Frame frame) {
    WebSocket ws = socket;
    if (ws == null) {
      return false;
    }
    String text = Json.write(frame);
    synchronized (sendLock) {
      try {
        ws.sendText(text, true).get(30, TimeUnit.SECONDS);
        return true;
      } catch (Exception e) {
        log.debug("send failed: {}", rootMessage(e));
        ready = false;
        ws.abort();
        return false;
      }
    }
  }

  private final class Listener implements WebSocket.Listener {
    private final CompletableFuture<Void> closed;
    private final StringBuilder partial = new StringBuilder();

    Listener(CompletableFuture<Void> closed) {
      this.closed = closed;
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        String text = partial.toString();
        partial.setLength(0);
        try {
          Map<String, Object> m = Json.readMap(text);
          onFrame.accept(new Wire.Frame(Json.str(m, "type"), Json.obj(m, "data")));
        } catch (RuntimeException e) {
          log.warn("bad frame from server: {}", e.getMessage());
        }
      }
      ws.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
      log.info("server closed the connection: {} {}", statusCode, reason);
      closed.complete(null);
      return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
      log.warn("connection error: {}", rootMessage(error));
      closed.complete(null);
    }
  }

  private static String rootMessage(Throwable e) {
    Throwable t = e;
    while (t.getCause() != null) {
      t = t.getCause();
    }
    return t.getClass().getSimpleName() + ": " + t.getMessage();
  }
}
