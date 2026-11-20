package dev.orca.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orca.agent.fake.FakeCodexAppServer;
import dev.orca.daemon.Daemon;
import dev.orca.daemon.DaemonConfig;
import dev.orca.protocol.Json;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The whole loop in one JVM: the server, a real daemon over WebSocket, and a scripted fake Codex
 * app-server process. No network, no account.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
    "orca.admin.password=test-pass", "orca.admin.email=admin@test.local", "orca.heartbeat-seconds=2"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndToEndTest {

  @LocalServerPort
  int port;

  static final java.util.List<String> NOTIFICATIONS = new java.util.concurrent.CopyOnWriteArrayList<>();
  static final com.sun.net.httpserver.HttpServer CHAT;

  static {
    try {
      CHAT = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      CHAT.createContext("/hook", ex -> {
        NOTIFICATIONS.add(new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        ex.sendResponseHeaders(200, -1);
        ex.close();
      });
      CHAT.start();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @org.springframework.test.context.DynamicPropertySource
  static void chatWebhook(org.springframework.test.context.DynamicPropertyRegistry r) {
    r.add("orca.notify.webhook-url", () -> "http://127.0.0.1:" + CHAT.getAddress().getPort() + "/hook");
    r.add("orca.notify.format", () -> "feishu");
  }

  private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
  private String agentId;

  private Object call(String method, String path, Object body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
    b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(Json.write(body)));
    HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    if (res.statusCode() >= 400) {
      throw new AssertionError(method + " " + path + " -> " + res.statusCode() + " " + res.body());
    }
    return res.body().isBlank() ? null : Json.MAPPER.readValue(res.body(), Object.class);
  }

  private static void await(String what, Supplier<Boolean> cond) throws InterruptedException {
    for (int i = 0; i < 200; i++) {
      if (cond.get()) {
        return;
      }
      Thread.sleep(150);
    }
    throw new AssertionError("timed out waiting for " + what);
  }

  private Supplier<Boolean> quietly(java.util.concurrent.Callable<Boolean> c) {
    return () -> {
      try {
        return c.call();
      } catch (Exception e) {
        return false;
      }
    };
  }

  @BeforeAll
  void startDaemon() throws Exception {
    call("POST", "/api/auth/login", Map.of("email", "admin@test.local", "password", "test-pass"));
    String code = Json.str(call("POST", "/api/runtimes/registration-codes", null), "code");
    String token = Json.str(call("POST", "/api/daemon/register", Map.of("code", code)), "token");
    Path work = Files.createTempDirectory("orca-e2e");
    DaemonConfig cfg = new DaemonConfig("http://localhost:" + port, token, "test-box", 2, work.toString(),
        FakeCodexAppServer.command(), List.of("/nonexistent/traecli"), Map.of("FAKE_SCENARIO", "echo"), false, null, false);
    Daemon daemon = new Daemon(cfg, Daemon.defaultProviders(cfg, null));
    Thread.ofPlatform().daemon().name("test-daemon").start(daemon::run);
    await("runtime online", quietly(() -> {
      List<?> rts = (List<?>) call("GET", "/api/runtimes", null);
      return !rts.isEmpty() && "online".equals(Json.str(rts.get(0), "status"))
          && Json.list(rts.get(0), "providers").stream().anyMatch(p -> Json.bool(p, "loggedIn"));
    }));
    String runtimeId = Json.str(((List<?>) call("GET", "/api/runtimes", null)).get(0), "id");
    agentId = Json.str(call("POST", "/api/agents", Map.of("name", "Builder", "provider", "codex", "runtimeId", runtimeId,
        "model", "gpt-6-luna")), "id");
  }

  @Test
  void assignedIssueIsWorkedOnCommentedAndMovedToReview() throws Exception {
    Object issue = call("POST", "/api/issues", Map.of("title", "Say hi", "description", "please", "assigneeType", "agent",
        "assigneeId", agentId));
    String key = Json.str(issue, "key");
    await("issue in review", quietly(() -> "in_review".equals(Json.str(call("GET", "/api/issues/" + key, null), "status"))));

    List<?> comments = (List<?>) call("GET", "/api/issues/" + key + "/comments", null);
    assertEquals(1, comments.size());
    assertEquals("agent", Json.str(comments.get(0), "authorType"));
    assertTrue(Json.str(comments.get(0), "body").startsWith("ECHO: You have been assigned issue " + key));

    List<?> tasks = (List<?>) call("GET", "/api/issues/" + key + "/tasks", null);
    String taskId = Json.str(tasks.get(0), "id");
    List<?> events = (List<?>) call("GET", "/api/tasks/" + taskId + "/events", null);
    List<String> types = events.stream().map(e -> Json.str(e, "type")).toList();
    assertTrue(types.containsAll(List.of("session", "user.message", "plan", "tool.completed", "message", "usage",
        "turn.completed")), types.toString());

    // a follow-up comment continues the same provider session
    call("POST", "/api/issues/" + key + "/comments", Map.of("body", "and add tests"));
    await("follow-up done", quietly(() -> ((List<?>) call("GET", "/api/issues/" + key + "/comments", null)).size() == 3));
    List<?> after = (List<?>) call("GET", "/api/issues/" + key + "/tasks", null);
    assertEquals(2, after.size());
    assertEquals("thr_fake_1", Json.str(after.get(1), "resumeRef"));
  }

  @Test
  void approvalNodesWaitForTheInbox() throws Exception {
    Map<String, Object> def = Map.of(
        "nodes", List.of(
            Map.of("id", "start", "type", "trigger.manual", "config", Map.of()),
            Map.of("id", "ask", "type", "human.approval", "config", Map.of("title", "Ship {{ trigger.input.what }}?")),
            Map.of("id", "yes", "type", "logic.set", "config", Map.of("values", Map.of("ok", "{{ nodes.ask.output.comment }}"))),
            Map.of("id", "no", "type", "logic.set", "config", Map.of("values", Map.of("ok", false)))),
        "edges", List.of(Map.of("id", "e1", "source", "start", "target", "ask"),
            Map.of("id", "e2", "source", "ask", "sourcePort", "approved", "target", "yes"),
            Map.of("id", "e3", "source", "ask", "sourcePort", "rejected", "target", "no")));
    String wf = Json.str(call("POST", "/api/workflows", Map.of("name", "Approve", "definition", def)), "id");
    String run = Json.str(call("POST", "/api/workflows/" + wf + "/runs", Map.of("input", Map.of("what", "v2"))), "id");
    assertEquals("waiting", Json.str(call("GET", "/api/runs/" + run, null), "status"));

    Object item = ((List<?>) call("GET", "/api/inbox?status=pending", null)).stream()
        .filter(i -> run.equals(Json.str(i, "runId"))).findFirst().orElseThrow();
    assertEquals("Ship v2?", Json.str(item, "title"));
    await("chat notification", () -> NOTIFICATIONS.stream().anyMatch(n -> n.contains("Approval needed: Ship v2?")
        && n.contains("\"msg_type\":\"text\"")));
    call("POST", "/api/inbox/" + Json.str(item, "id") + "/answer", Map.of("decision", "approved", "comment", "lgtm"));

    Object done = call("GET", "/api/runs/" + run, null);
    assertEquals("succeeded", Json.str(done, "status"));
    assertEquals("lgtm", Json.get(done, "state.nodes.yes.output.ok"));
    assertEquals("SKIPPED", Json.get(done, "state.nodes.no.status"));
  }

  @Test
  void invalidWorkflowsAreRejectedWithLocatedProblems() throws Exception {
    HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/workflows"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("name", "bad", "definition", Map.of(
            "nodes", List.of(Map.of("id", "a", "type", "agent.run", "config", Map.of())), "edges", List.of())))))
        .build();
    HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
    assertEquals(422, res.statusCode());
    assertTrue(res.body().contains("\"nodeId\":\"a\""), res.body());
  }

  @Test
  void concurrentSignalsOnOneRunAreNotLost() throws Exception {
    Map<String, Object> def = Map.of(
        "nodes", List.of(
            Map.of("id", "start", "type", "trigger.manual", "config", Map.of()),
            Map.of("id", "a", "type", "human.approval", "config", Map.of("title", "A")),
            Map.of("id", "b", "type", "human.approval", "config", Map.of("title", "B")),
            Map.of("id", "both", "type", "logic.join", "config", Map.of())),
        "edges", List.of(Map.of("id", "e1", "source", "start", "target", "a"),
            Map.of("id", "e2", "source", "start", "target", "b"),
            Map.of("id", "e3", "source", "a", "sourcePort", "approved", "target", "both"),
            Map.of("id", "e4", "source", "b", "sourcePort", "approved", "target", "both")));
    String wf = Json.str(call("POST", "/api/workflows", Map.of("name", "Parallel", "definition", def)), "id");
    for (int round = 0; round < 5; round++) {
      String run = Json.str(call("POST", "/api/workflows/" + wf + "/runs", Map.of()), "id");
      List<String> items = ((List<?>) call("GET", "/api/inbox?status=pending", null)).stream()
          .filter(i -> run.equals(Json.str(i, "runId"))).map(i -> Json.str(i, "id")).toList();
      assertEquals(2, items.size());
      java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
      List<Thread> threads = items.stream().map(id -> Thread.ofPlatform().start(() -> {
        try {
          go.await();
          call("POST", "/api/inbox/" + id + "/answer", Map.of("decision", "approved"));
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      })).toList();
      go.countDown();
      for (Thread t : threads) {
        t.join();
      }
      Object done = call("GET", "/api/runs/" + run, null);
      assertEquals("succeeded", Json.str(done, "status"), "round " + round + ": " + Json.write(Json.get(done, "state.nodes")));
      assertEquals("SUCCEEDED", Json.get(done, "state.nodes.both.status"));
    }
  }

  @Test
  void reviewLoopAlternatesImplementerAndReviewerUntilTheLimit() throws Exception {
    Object issue = call("POST", "/api/issues", Map.of("title", "Loop me"));
    String key = Json.str(issue, "key");
    Map<String, Object> def = Map.of(
        "nodes", List.of(
            Map.of("id", "start", "type", "trigger.manual", "config", Map.of()),
            Map.of("id", "loop", "type", "agent.review_loop", "config", Map.of("implementer", "Builder", "reviewer", "Builder",
                "prompt", "implement it", "issue", "{{ trigger.input.issueId }}", "maxRounds", 2)),
            Map.of("id", "ok", "type", "logic.set", "config", Map.of("values", Map.of("r", "passed"))),
            Map.of("id", "ko", "type", "logic.set", "config", Map.of("values", Map.of("r", "{{ nodes.loop.output.rounds }}")))),
        "edges", List.of(Map.of("id", "e1", "source", "start", "target", "loop"),
            Map.of("id", "e2", "source", "loop", "sourcePort", "passed", "target", "ok"),
            Map.of("id", "e3", "source", "loop", "sourcePort", "failed", "target", "ko")));
    String wf = Json.str(call("POST", "/api/workflows", Map.of("name", "Loop", "definition", def)), "id");
    String run = Json.str(call("POST", "/api/workflows/" + wf + "/runs", Map.of("input", Map.of("issueId", key))), "id");
    await("loop finished", quietly(() -> "succeeded".equals(Json.str(call("GET", "/api/runs/" + run, null), "status"))));
    Object done = call("GET", "/api/runs/" + run, null);
    assertEquals("SKIPPED", Json.get(done, "state.nodes.ok.status"));
    assertEquals(2, ((Number) Json.get(done, "state.nodes.ko.output.r")).intValue());
    List<?> tasks = (List<?>) call("GET", "/api/issues/" + key + "/tasks", null);
    assertEquals(List.of("run", "structured", "run", "structured"), tasks.stream().map(t -> Json.str(t, "kind")).toList());
    assertTrue(Json.str(tasks.get(2), "prompt").contains("The reviewer requested changes (round 1 of 2)"));
  }

  @Test
  void skillsAreGivenToAgents() throws Exception {
    String skill = Json.str(call("POST", "/api/skills", Map.of("name", "release-rules",
        "content", "Always bump the CHANGELOG before tagging.")), "id");
    String runtimeId = Json.str(((List<?>) call("GET", "/api/runtimes", null)).get(0), "id");
    String agent = Json.str(call("POST", "/api/agents", Map.of("name", "Releaser", "provider", "codex",
        "runtimeId", runtimeId, "skills", List.of(skill), "instructions", "You ship releases.")), "id");
    Object issue = call("POST", "/api/issues", Map.of("title", "Release", "description", "show instructions",
        "assigneeType", "agent", "assigneeId", agent));
    String key = Json.str(issue, "key");
    await("reply", quietly(() -> !((List<?>) call("GET", "/api/issues/" + key + "/comments", null)).isEmpty()));
    String body = Json.str(((List<?>) call("GET", "/api/issues/" + key + "/comments", null)).get(0), "body");
    assertTrue(body.contains("You ship releases.") && body.contains("## Skill: release-rules")
        && body.contains("Always bump the CHANGELOG") && body.contains("You are Releaser"), body);
  }

  @Test
  void issueImagesReachTheAgent() throws Exception {
    String key = Json.str(call("POST", "/api/issues", Map.of("title", "Fix the button", "description", "see screenshot")), "key");
    String boundary = "orcaboundary";
    byte[] png = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"shot.png\"\r\n"
        + "Content-Type: image/png\r\n\r\n").getBytes());
    body.write(png);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes());
    HttpResponse<String> up = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/issues/" + key + "/attachments"))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, up.statusCode(), up.body());
    String url = Json.str(Json.readMap(up.body()), "url");
    assertEquals(200, http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + url)).build(),
        HttpResponse.BodyHandlers.ofByteArray()).statusCode(), "members can read attachments");
    assertEquals(401, HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + url)).build(),
        HttpResponse.BodyHandlers.ofByteArray()).statusCode(), "anonymous requests are refused");

    call("PATCH", "/api/issues/" + key, Map.of("assigneeType", "agent", "assigneeId", agentId));
    await("reply", quietly(() -> !((List<?>) call("GET", "/api/issues/" + key + "/comments", null)).isEmpty()));
    String reply = Json.str(((List<?>) call("GET", "/api/issues/" + key + "/comments", null)).get(0), "body");
    assertTrue(reply.contains("[images:1]"), reply);
  }

  @Test
  void obfuscatedApiPathsDoNotBypassAuthentication() throws Exception {
    HttpClient anon = HttpClient.newHttpClient();
    for (String path : List.of("/api;/members", "/%61pi/members", "/api/;/members", "/api;x=1/issues", "/api/members;x=1",
        "/API/members", "/api/./members", "/api//members")) {
      int code = anon.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
          HttpResponse.BodyHandlers.ofString()).statusCode();
      assertTrue(code == 400 || code == 401 || code == 404, path + " -> " + code);
    }
    assertEquals(401, anon.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/members")).build(),
        HttpResponse.BodyHandlers.ofString()).statusCode());
  }

  @Test
  void aFailingWorkflowDoesNotUndoThePersonsChange() throws Exception {
    String runtimeId = Json.str(((List<?>) call("GET", "/api/runtimes", null)).get(0), "id");
    String temp = Json.str(call("POST", "/api/agents", Map.of("name", "Temp", "provider", "codex", "runtimeId", runtimeId)), "id");
    String key = Json.str(call("POST", "/api/issues", Map.of("title", "Archived owner", "assigneeType", "agent",
        "assigneeId", temp)), "key");
    await("first run", quietly(() -> "in_review".equals(Json.str(call("GET", "/api/issues/" + key, null), "status"))));
    call("DELETE", "/api/agents/" + temp, null);
    call("POST", "/api/issues/" + key + "/comments", Map.of("body", "one more thing"));
    await("failure reported", quietly(() -> ((List<?>) call("GET", "/api/issues/" + key + "/comments", null)).stream()
        .anyMatch(c -> String.valueOf(Json.str(c, "body")).contains("is archived"))));
    assertTrue(((List<?>) call("GET", "/api/issues/" + key + "/comments", null)).stream()
        .anyMatch(c -> "one more thing".equals(Json.str(c, "body"))), "the person's comment is kept");
  }

  @Test
  void cancellingARunDoesNotTakeItsErrorBranch() throws Exception {
    String idle = Json.str(call("POST", "/api/agents", Map.of("name", "Idle", "provider", "codex")), "id");
    String key = Json.str(call("POST", "/api/issues", Map.of("title", "Never runs", "assigneeType", "agent",
        "assigneeId", idle)), "key");
    List<?> runs = (List<?>) call("GET", "/api/issues/" + key + "/runs", null);
    String run = Json.str(runs.get(0), "id");
    call("POST", "/api/runs/" + run + "/cancel", null);
    Thread.sleep(1500);
    assertEquals("cancelled", Json.str(call("GET", "/api/runs/" + run, null), "status"));
    assertTrue(((List<?>) call("GET", "/api/issues/" + key + "/comments", null)).isEmpty(), "no failure comment");
    assertTrue(((List<?>) call("GET", "/api/inbox?status=pending", null)).stream()
        .noneMatch(i -> run.equals(Json.str(i, "runId"))), "no failure alert");
    assertEquals("cancelled", Json.str(((List<?>) call("GET", "/api/issues/" + key + "/tasks", null)).get(0), "status"));
  }

  @Test
  void askFollowUpsContinueTheSameSession() throws Exception {
    String first = Json.str(call("POST", "/api/agents/" + agentId + "/ask", Map.of("prompt", "hello")), "id");
    await("first answer", quietly(() -> "succeeded".equals(Json.str(call("GET", "/api/tasks/" + first, null), "status"))));
    Object next = call("POST", "/api/agents/" + agentId + "/ask", Map.of("prompt", "and then?", "followUp", first));
    String second = Json.str(next, "id");
    await("second answer", quietly(() -> "succeeded".equals(Json.str(call("GET", "/api/tasks/" + second, null), "status"))));
    Object s1 = sessionEvent(first);
    Object s2 = sessionEvent(second);
    assertEquals(Json.str(Json.obj(s1, "data"), "cwd"), Json.str(Json.obj(s2, "data"), "cwd"), "same directory");
    assertTrue(Json.bool(Json.obj(s2, "data"), "resumed"), "the follow-up resumes the first session");
  }

  private Object sessionEvent(String taskId) throws Exception {
    List<?> events = (List<?>) call("GET", "/api/tasks/" + taskId + "/events", null);
    return events.stream().filter(e -> "session".equals(Json.str(e, "type"))).findFirst().orElseThrow();
  }

  @Test
  void agentsCanWorkOnAProjectRepositoryWithoutAnIssue() throws Exception {
    Path repo = Files.createTempDirectory("orca-e2e-repo");
    for (List<String> cmd : List.of(List.of("git", "init", "-q", "-b", "main"),
        List.of("git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "init"))) {
      assertEquals(0, new ProcessBuilder(cmd).directory(repo.toFile()).inheritIO().start().waitFor());
    }
    call("POST", "/api/projects", Map.of("key", "CHK", "name", "Checked", "repoUrl", repo.toString()));
    Map<String, Object> def = Map.of(
        "nodes", List.of(
            Map.of("id", "start", "type", "trigger.manual", "config", Map.of()),
            Map.of("id", "check", "type", "agent.run", "config", Map.of("agent", "Builder", "project", "chk",
                "issue", "", "prompt", "hello"))),
        "edges", List.of(Map.of("id", "e1", "source", "start", "target", "check")));
    String wf = Json.str(call("POST", "/api/workflows", Map.of("name", "Project check", "definition", def)), "id");
    String run = Json.str(call("POST", "/api/workflows/" + wf + "/runs", Map.of("input", Map.of())), "id");
    await("check done", quietly(() -> "succeeded".equals(Json.str(call("GET", "/api/runs/" + run, null), "status"))));
    String taskId = String.valueOf(Json.get(call("GET", "/api/runs/" + run, null), "state.nodes.check.output.taskId"));
    String cwd = Json.str(Json.obj(sessionEvent(taskId), "data"), "cwd");
    assertTrue(cwd.contains("/runs/" + run), cwd);
    Process branch = new ProcessBuilder("git", "-C", cwd, "rev-parse", "--abbrev-ref", "HEAD").start();
    assertEquals("orca/" + run.toLowerCase(), new String(branch.getInputStream().readAllBytes()).trim(),
        "a branch of its own, so nothing lands on main");
  }
}
