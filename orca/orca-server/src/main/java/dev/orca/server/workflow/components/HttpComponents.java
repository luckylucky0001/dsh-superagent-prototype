package dev.orca.server.workflow.components;

import static dev.orca.server.workflow.components.Fields.d;
import static dev.orca.server.workflow.components.Fields.f;
import static dev.orca.server.workflow.components.Fields.out;
import static dev.orca.server.workflow.components.Fields.req;
import static dev.orca.server.workflow.components.Fields.select;

import dev.orca.protocol.Json;
import dev.orca.workflow.expr.Values;
import dev.orca.workflow.spi.FieldKind;
import dev.orca.workflow.spi.NodeComponent;
import dev.orca.workflow.spi.NodeContext;
import dev.orca.workflow.spi.NodeDescriptor;
import dev.orca.workflow.spi.NodeResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Outbound HTTP. Only hosts on {@code orca.http.allowed-hosts} (plus the chat webhook hosts for
 * notifications) are reachable, so a workflow cannot be used to probe the internal network.
 */
@Configuration
public class HttpComponents {

  static final Set<String> CHAT_HOSTS = Set.of("open.feishu.cn", "open.larksuite.com", "oapi.dingtalk.com",
      "hooks.slack.com", "qyapi.weixin.qq.com", "outlook.office.com");

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
      .followRedirects(HttpClient.Redirect.NEVER).build();
  private final Set<String> allowed;

  public HttpComponents(@Value("${orca.http.allowed-hosts:}") String hosts) {
    this.allowed = Arrays.stream(hosts.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
        .collect(Collectors.toSet());
  }

  private boolean permitted(URI uri, boolean chat) {
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    boolean scheme = "https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && allowed.contains(host));
    return scheme && (allowed.contains(host) || (chat && CHAT_HOSTS.contains(host)));
  }

  private NodeResult send(String method, String url, Map<String, Object> headers, String body, boolean chat) {
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException e) {
      return NodeResult.fatal("bad URL: " + url);
    }
    if (!permitted(uri, chat)) {
      return NodeResult.fatal("host " + uri.getHost() + " is not on orca.http.allowed-hosts");
    }
    HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
    headers.forEach((k, v) -> b.header(k, Values.stringify(v)));
    HttpRequest.BodyPublisher pub = body == null || body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body);
    b.method(method, pub);
    try {
      HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      Map<String, Object> output = new LinkedHashMap<>();
      output.put("status", res.statusCode());
      String text = res.body() == null ? "" : res.body();
      output.put("text", text.length() > 1_000_000 ? text.substring(0, 1_000_000) : text);
      try {
        output.put("json", Json.MAPPER.readValue(text, Object.class));
      } catch (RuntimeException ignored) {
        // not JSON
      }
      if (res.statusCode() >= 500) {
        return new NodeResult.Failed("HTTP " + res.statusCode(), true, output);
      }
      if (res.statusCode() >= 400) {
        return new NodeResult.Failed("HTTP " + res.statusCode(), false, output);
      }
      return NodeResult.done(output);
    } catch (java.io.IOException e) {
      return NodeResult.failed("request failed: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return NodeResult.failed("interrupted");
    }
  }

  @Bean
  NodeComponent httpRequest() {
    return new NodeComponent() {
      public NodeDescriptor descriptor() {
        return d("http.request", "action", "HTTP request", "Calls an API on an allowed host.", false,
            List.of(select("method", "Method", true, "POST", "GET", "POST", "PUT", "PATCH", "DELETE"),
                req("url", "URL", FieldKind.TEMPLATE), f("headers", "Headers", FieldKind.JSON),
                f("body", "Body", FieldKind.TEMPLATE)),
            List.of(), List.of(out("status", "number", "HTTP status"), out("json", "any", "parsed JSON body"),
                out("text", "string", "raw body")));
      }

      @SuppressWarnings("unchecked")
      public NodeResult execute(NodeContext ctx) {
        Object h = ctx.config().get("headers");
        Map<String, Object> headers = h instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Object body = ctx.config().get("body");
        String b = body instanceof Map<?, ?> || body instanceof List<?> ? Json.write(body) : Values.stringify(body);
        return send(ctx.string("method", "POST"), ctx.string("url"), headers, b, false);
      }
    };
  }

  @Bean
  NodeComponent notifyWebhook() {
    return new NodeComponent() {
      public NodeDescriptor descriptor() {
        return d("notify.webhook", "action", "Chat notification",
            "Posts a message to a Feishu/Lark, DingTalk, WeCom, Slack or Teams incoming webhook.", false,
            List.of(select("format", "Format", true, "slack", "slack", "feishu", "dingtalk", "wecom", "teams"),
                req("url", "Webhook URL", FieldKind.STRING), req("text", "Message", FieldKind.TEMPLATE)),
            List.of(), List.of(out("status", "number", "HTTP status")));
      }

      public NodeResult execute(NodeContext ctx) {
        String text = ctx.string("text", "");
        Object payload = switch (ctx.string("format", "slack")) {
          case "feishu" -> Map.of("msg_type", "text", "content", Map.of("text", text));
          case "dingtalk", "wecom" -> Map.of("msgtype", "text", "text", Map.of("content", text));
          case "teams" -> Map.of("text", text);
          default -> Map.of("text", text);
        };
        return send("POST", ctx.string("url"), Map.of("Content-Type", "application/json"), Json.write(payload), true);
      }
    };
  }
}
