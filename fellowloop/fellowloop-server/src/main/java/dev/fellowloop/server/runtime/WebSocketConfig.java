package dev.fellowloop.server.runtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
  private final DaemonGateway gateway;

  public WebSocketConfig(DaemonGateway gateway) {
    this.gateway = gateway;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(gateway, "/api/daemon/ws").addInterceptors(gateway).setAllowedOrigins("*");
  }
}
