package dev.fellowloop.server.runtime;

import dev.fellowloop.protocol.Json;
import dev.fellowloop.protocol.Wire;
import dev.fellowloop.server.auth.AuthService;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** The server end of daemon WebSockets: authenticates the handshake and routes frames. */
@Component
public class DaemonGateway extends TextWebSocketHandler implements HandshakeInterceptor {

  private static final Logger log = LoggerFactory.getLogger(DaemonGateway.class);
  private static final String RUNTIME = "fellowloop.runtimeId";

  private final AuthService auth;
  private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
  private volatile BiConsumer<String, Wire.Frame> frameHandler = (r, f) -> {};
  private volatile java.util.function.Consumer<String> disconnectHandler = r -> {};

  public DaemonGateway(AuthService auth) {
    this.auth = auth;
  }

  void onFrame(BiConsumer<String, Wire.Frame> handler) {
    this.frameHandler = handler;
  }

  void onDisconnect(java.util.function.Consumer<String> handler) {
    this.disconnectHandler = handler;
  }

  public boolean connected(String runtimeId) {
    WebSocketSession s = runtimeId == null ? null : sessions.get(runtimeId);
    return s != null && s.isOpen();
  }

  public boolean send(String runtimeId, Wire.Frame frame) {
    WebSocketSession s = runtimeId == null ? null : sessions.get(runtimeId);
    if (s == null || !s.isOpen()) {
      return false;
    }
    try {
      s.sendMessage(new TextMessage(Json.write(frame)));
      return true;
    } catch (IOException | IllegalStateException e) {
      log.debug("send to {} failed: {}", runtimeId, e.toString());
      return false;
    }
  }

  public void disconnect(String runtimeId) {
    WebSocketSession s = sessions.remove(runtimeId);
    if (s != null) {
      try {
        s.close(CloseStatus.POLICY_VIOLATION.withReason("runtime removed"));
      } catch (IOException ignored) {
        // already closed
      }
    }
  }

  @Override
  public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
      Map<String, Object> attributes) {
    String h = request.getHeaders().getFirst("Authorization");
    String token = h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null;
    return auth.verify(token, AuthService.DAEMON).map(t -> {
      attributes.put(RUNTIME, t.runtimeId);
      return true;
    }).orElseGet(() -> {
      response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
      return false;
    });
  }

  @Override
  public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
      Exception exception) {}

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    String runtimeId = (String) session.getAttributes().get(RUNTIME);
    WebSocketSession previous = sessions.put(runtimeId,
        new ConcurrentWebSocketSessionDecorator(session, 30_000, 8 * 1024 * 1024));
    if (previous != null && previous.isOpen()) {
      try {
        previous.close(CloseStatus.NORMAL.withReason("replaced by a new connection"));
      } catch (IOException ignored) {
        // ignore
      }
    }
    session.setTextMessageSizeLimit(16 * 1024 * 1024);
    log.info("runtime {} connected", runtimeId);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    String runtimeId = (String) session.getAttributes().get(RUNTIME);
    try {
      Map<String, Object> m = Json.readMap(message.getPayload());
      frameHandler.accept(runtimeId, new Wire.Frame(Json.str(m, "type"), Json.obj(m, "data")));
    } catch (RuntimeException e) {
      log.warn("frame from {} failed: {}", runtimeId, e.toString(), e);
      send(runtimeId, Wire.frame(Wire.ERROR, new Wire.Error(String.valueOf(e.getMessage()))));
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    String runtimeId = (String) session.getAttributes().get(RUNTIME);
    WebSocketSession current = sessions.get(runtimeId);
    if (current != null && (current == session || !current.isOpen()
        || (current instanceof ConcurrentWebSocketSessionDecorator d && d.getDelegate() == session))) {
      sessions.remove(runtimeId);
      log.info("runtime {} disconnected ({})", runtimeId, status);
      disconnectHandler.accept(runtimeId);
    }
  }
}
