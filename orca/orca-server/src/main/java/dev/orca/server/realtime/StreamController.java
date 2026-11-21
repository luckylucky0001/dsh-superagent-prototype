package dev.orca.server.realtime;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class StreamController {
  private final Realtime realtime;

  public StreamController(Realtime realtime) {
    this.realtime = realtime;
  }

  @GetMapping(path = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(jakarta.servlet.http.HttpServletRequest req) {
    String token = dev.orca.server.auth.AuthFilter.token(req);
    return realtime.subscribe(token == null ? null : dev.orca.server.auth.AuthService.hash(token));
  }
}
