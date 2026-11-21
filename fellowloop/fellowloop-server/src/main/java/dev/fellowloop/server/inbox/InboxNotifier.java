package dev.fellowloop.server.inbox;

import dev.fellowloop.protocol.Json;
import dev.fellowloop.server.model.InboxItem;
import dev.fellowloop.server.support.FellowloopProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Posts new inbox items to a team chat (Feishu/Lark, DingTalk, WeCom, Slack or Teams incoming webhook)
 * so a question from an agent reaches people who are not looking at Fellowloop. Off unless configured.
 */
@Component
public class InboxNotifier {
  private static final Logger log = LoggerFactory.getLogger(InboxNotifier.class);

  private final String url;
  private final String format;
  private final FellowloopProperties props;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public InboxNotifier(@Value("${fellowloop.notify.webhook-url:}") String url, @Value("${fellowloop.notify.format:slack}") String format,
      FellowloopProperties props) {
    this.url = url;
    this.format = format;
    this.props = props;
  }

  public boolean enabled() {
    return url != null && !url.isBlank();
  }

  void created(InboxItem item) {
    if (!enabled()) {
      return;
    }
    String label = switch (item.kind) {
      case InboxService.APPROVAL -> "Approval needed";
      case InboxService.QUESTION -> item.formJson != null && item.formJson.contains("\"approval\"")
          ? "Approval needed" : "An agent has a question";
      case InboxService.INPUT -> "Input needed";
      default -> "Alert";
    };
    String text = "[Fellowloop] " + label + ": " + item.title + "\n" + props.publicUrl() + "/inbox";
    Runnable send = () -> Thread.ofVirtual().start(() -> post(text));
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          send.run();
        }
      });
    } else {
      send.run();
    }
  }

  private void post(String text) {
    Object body = switch (format) {
      case "feishu", "lark" -> Map.of("msg_type", "text", "content", Map.of("text", text));
      case "dingtalk", "wecom" -> Map.of("msgtype", "text", "text", Map.of("content", text));
      default -> Map.of("text", text);
    };
    try {
      HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
          .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(),
          HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() >= 300) {
        log.warn("inbox notification returned HTTP {}", res.statusCode());
      }
    } catch (Exception e) {
      log.warn("inbox notification failed: {}", e.toString());
    }
  }
}
