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
    // H2 by default; ORCA_TEST_DB_URL / _USER / _PASSWORD run the same suite against PostgreSQL
    "spring.datasource.url=${ORCA_TEST_DB_URL:jdbc:h2:mem:e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${ORCA_TEST_DB_USER:sa}", "spring.datasource.password=${ORCA_TEST_DB_PASSWORD:}",
    "orca.admin.password=test-pass", "orca.admin.email=admin@test.local", "orca.heartbeat-seconds=2"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndToEndTest {

  /** Unique per run, so the suite can run repeatedly against the same PostgreSQL database. */
  private static final String RUN = Long.toString(System.currentTimeMillis() % 46656, 36).toUpperCase();


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
    // keep uploads out of the real ~/.orca
    r.add("orca.storage-dir", () -> {
      try {
        return Files.createTempDirectory("orca-e2e-files").toString();
      } catch (java.io.IOException e) {
        throw new IllegalStateException(e);
      }
    });
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
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> audited = (List<Map<String, Object>>) call("GET", "/api/audit?limit=500", null);
    assertTrue(audited.stream().anyMatch(e -> "approval.decide".equals(e.get("action"))
        && "approved".equals(Json.str(e.get("detail"), "decision"))), "the audit log records approved / rejected");

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
    call("POST", "/api/projects", Map.of("key", "CHK" + RUN, "name", "Checked", "repoUrl", repo.toString()));
    Map<String, Object> def = Map.of(
        "nodes", List.of(
            Map.of("id", "start", "type", "trigger.manual", "config", Map.of()),
            Map.of("id", "check", "type", "agent.run", "config", Map.of("agent", "Builder", "project", "chk" + RUN.toLowerCase(),
                "issue", "", "prompt", "hello"))),
        "edges", List.of(Map.of("id", "e1", "source", "start", "target", "check")));
    String wf = Json.str(call("POST", "/api/workflows", Map.of("name", "Project check", "definition", def)), "id");
    String run = Json.str(call("POST", "/api/workflows/" + wf + "/runs", Map.of("input", Map.of())), "id");
    await("check done", quietly(() -> "succeeded".equals(Json.str(call("GET", "/api/runs/" + run, null), "status"))));
    String taskId = String.valueOf(Json.get(call("GET", "/api/runs/" + run, null), "state.nodes.check.output.taskId"));
    String cwd = Json.str(Json.obj(sessionEvent(taskId), "data"), "cwd");
    assertTrue(cwd.endsWith("/runs/" + run + "-CHK" + RUN), cwd);
    Process branch = new ProcessBuilder("git", "-C", cwd, "rev-parse", "--abbrev-ref", "HEAD").start();
    assertEquals("orca/runs-" + run.toLowerCase() + "-chk" + RUN.toLowerCase(), new String(branch.getInputStream().readAllBytes()).trim(),
        "a branch of its own, so nothing lands on main");
  }

  @Test
  void theIssueBranchIsPushedFromTheRuntimeThatWorkedOnIt() throws Exception {
    Path origin = Files.createTempDirectory("orca-e2e-origin");
    for (List<String> cmd : List.of(List.of("git", "init", "-q", "-b", "main"),
        List.of("git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "init"))) {
      assertEquals(0, new ProcessBuilder(cmd).directory(origin.toFile()).inheritIO().start().waitFor());
    }
    String project = Json.str(call("POST", "/api/projects", Map.of("key", "PSH", "name", "Push", "repoUrl", origin.toString())), "id");
    Object issue = call("POST", "/api/issues", Map.of("projectId", project, "title", "Ship it", "description", "hello",
        "assigneeType", "agent", "assigneeId", agentId));
    String key = Json.str(issue, "key");
    await("worked on", quietly(() -> "in_review".equals(Json.str(call("GET", "/api/issues/" + key, null), "status"))));
    Map<String, Object> def = Map.of(
        "nodes", List.of(
            Map.of("id", "start", "type", "trigger.manual", "config", Map.of()),
            Map.of("id", "push", "type", "repo.push", "config", Map.of("issue", key, "agent", "Builder"))),
        "edges", List.of(Map.of("id", "e1", "source", "start", "target", "push")));
    String wf = Json.str(call("POST", "/api/workflows", Map.of("name", "Push", "definition", def)), "id");
    String run = Json.str(call("POST", "/api/workflows/" + wf + "/runs", Map.of("input", Map.of())), "id");
    await("pushed", quietly(() -> !"waiting".equals(Json.str(call("GET", "/api/runs/" + run, null), "status"))));
    Object done = call("GET", "/api/runs/" + run, null);
    assertEquals("succeeded", Json.str(done, "status"), String.valueOf(Json.get(done, "state.nodes.push")));
    assertEquals("orca/" + key.toLowerCase(), Json.get(done, "state.nodes.push.output.branch"));
    Process branches = new ProcessBuilder("git", "-C", origin.toString(), "branch", "--list", "orca/*").start();
    assertTrue(new String(branches.getInputStream().readAllBytes()).contains("orca/" + key.toLowerCase()),
        "the branch reached the remote");
  }

  private record Res(int status, String body) {}

  private Res raw(HttpClient client, String method, String path, Object body, String bearer) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
    if (bearer != null) {
      b.header("Authorization", "Bearer " + bearer);
    }
    b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(Json.write(body)));
    HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return new Res(res.statusCode(), res.body());
  }

  @Test
  void personalAccessTokensCannotTakeOverTheAccount() throws Exception {
    call("POST", "/api/members", Map.of("email", "pat-user" + RUN.toLowerCase() + "@test.local", "name", "Pat", "password", "first-pass-1"));
    HttpClient web = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    assertEquals(200, raw(web, "POST", "/api/auth/login", Map.of("email", "pat-user" + RUN.toLowerCase() + "@test.local", "password", "first-pass-1"), null).status());
    String me = Json.str(Json.MAPPER.readValue(raw(web, "GET", "/api/auth/me", null, null).body(), Object.class), "id");
    String pat = Json.str(Json.MAPPER.readValue(
        raw(web, "POST", "/api/auth/tokens", Map.of("label", "ci", "days", 7), null).body(), Object.class), "token");
    HttpClient bare = HttpClient.newHttpClient();

    // a PAT does everyday work...
    assertEquals(200, raw(bare, "GET", "/api/issues", null, pat).status());
    assertEquals(200, raw(bare, "GET", "/api/auth/tokens", null, pat).status());
    // ...but cannot change the password, mint tokens, revoke tokens, manage members or add machines
    assertEquals(403, raw(bare, "PATCH", "/api/members/" + me, Map.of("password", "stolen-pass-1"), pat).status());
    assertEquals(403, raw(bare, "POST", "/api/auth/tokens", Map.of("label", "more", "days", 365), pat).status());
    assertEquals(403, raw(bare, "POST", "/api/runtimes/registration-codes", null, pat).status());
    assertEquals(403, raw(bare, "POST", "/api/projects", Map.of("key", "PX" + RUN, "name", "x"), pat).status());

    // the web session needs the current password, and changing it signs out every other session and token
    assertEquals(400, raw(web, "PATCH", "/api/members/" + me, Map.of("password", "second-pass-2"), null).status());
    assertEquals(400, raw(web, "PATCH", "/api/members/" + me,
        Map.of("password", "second-pass-2", "currentPassword", "wrong-pass"), null).status());
    assertEquals(200, raw(web, "PATCH", "/api/members/" + me,
        Map.of("password", "second-pass-2", "currentPassword", "first-pass-1"), null).status());
    assertEquals(401, raw(bare, "GET", "/api/issues", null, pat).status(), "the token was revoked");
    assertEquals(200, raw(web, "GET", "/api/auth/me", null, null).status(), "this session stays signed in");
  }

  @Test
  @SuppressWarnings("unchecked")
  void securityRelevantActionsAreAudited() throws Exception {
    // a project's repository can be changed by admins, and that change is recorded with its old value
    String pid = Json.str(call("POST", "/api/projects", Map.of("key", "AUD" + RUN, "name", "Audited")), "id");
    call("PATCH", "/api/projects/" + pid, Map.of("repoUrl", "git@example.com:acme/wms.git", "defaultBranch", "main"));
    Map<String, Object> agent = (Map<String, Object>) call("GET", "/api/agents/" + agentId, null);
    boolean wasNetwork = Boolean.TRUE.equals(agent.get("networkAccess"));
    call("PATCH", "/api/agents/" + agentId, Map.of("networkAccess", !wasNetwork));
    call("PATCH", "/api/agents/" + agentId, Map.of("networkAccess", wasNetwork));
    String tokenId = Json.str(Json.obj(call("POST", "/api/auth/tokens", Map.of("label", "=cmd|' /C calc'!A0", "days", 1)), "info"), "id");
    call("DELETE", "/api/auth/tokens/" + tokenId, null);

    // credentials in a repository URL never reach the log; option-like or malformed URLs are refused
    call("PATCH", "/api/projects/" + pid, Map.of("repoUrl", "https://ci:sekret-token@git.example.com/acme/wms.git"));
    assertEquals(400, raw(http, "PATCH", "/api/projects/" + pid, Map.of("repoUrl", "-uevil"), null).status());
    assertEquals(400, raw(http, "PATCH", "/api/projects/" + pid, Map.of("key", "OTHER"), null).status());
    // a failed sign-in is recorded without trusting the typed name as the actor
    raw(HttpClient.newHttpClient(), "POST", "/api/auth/login", Map.of("email", "admin@test.local", "password", "wrong"), null);

    List<Map<String, Object>> log = (List<Map<String, Object>>) call("GET", "/api/audit?limit=200", null);
    assertTrue(log.stream().noneMatch(e -> String.valueOf(e.get("detail")).contains("sekret")), "credentials are redacted");
    assertTrue(log.stream().anyMatch(e -> String.valueOf(e.get("detail")).contains("https://***@git.example.com")));
    Map<String, Object> failed = log.stream().filter(e -> "auth.login_failed".equals(e.get("action"))).findFirst().orElseThrow();
    assertEquals(null, failed.get("actorName"));
    assertEquals("admin@test.local", Json.str(failed.get("detail"), "login"));
    // keyset paging: two pages of one event never repeat or skip
    List<Map<String, Object>> p1 = (List<Map<String, Object>>) call("GET", "/api/audit?limit=1", null);
    String cursor = p1.get(0).get("at") + ":" + p1.get(0).get("id");
    List<Map<String, Object>> p2 = (List<Map<String, Object>>) call("GET", "/api/audit?limit=1&before=" + cursor, null);
    assertEquals(log.get(1).get("id"), p2.get(0).get("id"));
    java.util.function.Predicate<String> has = action -> log.stream().anyMatch(e -> action.equals(e.get("action")));
    for (String action : List.of("auth.login", "project.create", "project.update", "agent.update", "token.create",
        "token.revoke", "runtime.register")) {
      assertTrue(has.test(action), action + " is in the audit log");
    }
    Map<String, Object> update = log.stream().filter(e -> "project.update".equals(e.get("action"))
        && "".equals(Json.str(Json.obj(e.get("detail"), "before"), "repoUrl"))).findFirst().orElseThrow();
    assertEquals("git@example.com:acme/wms.git", Json.str(update.get("detail"), "repoUrl"));
    assertEquals("", Json.str(Json.obj(update.get("detail"), "before"), "repoUrl"));
    Map<String, Object> agentChange = log.stream().filter(e -> "agent.update".equals(e.get("action"))).findFirst().orElseThrow();
    assertTrue(((Map<String, Object>) agentChange.get("detail")).containsKey("networkAccess"), agentChange.toString());
    assertEquals("Admin", agentChange.get("actorName"));
    assertTrue(log.stream().noneMatch(e -> String.valueOf(e.get("detail")).contains("orca_p_")), "no token values");

    HttpClient bare = HttpClient.newHttpClient();
    String csv = raw(http, "GET", "/api/audit.csv", null, null).body();
    assertTrue(csv.contains("time_utc,actor,action"), csv.substring(0, Math.min(200, csv.length())));
    assertEquals("\"'=1+1\"", dev.orca.server.audit.AuditController.cell("=1+1"), "formulas are neutralised");
    assertEquals("\"a \"\"b\"\"\"", dev.orca.server.audit.AuditController.cell("a \"b\""));

    // members cannot read the log or change projects
    call("POST", "/api/members", Map.of("email", "plain" + RUN.toLowerCase() + "@test.local", "name", "Plain", "password", "plain-pass-1"));
    HttpClient member = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    raw(member, "POST", "/api/auth/login", Map.of("email", "plain" + RUN.toLowerCase() + "@test.local", "password", "plain-pass-1"), null);
    assertEquals(403, raw(member, "GET", "/api/audit", null, null).status());
    assertEquals(403, raw(member, "PATCH", "/api/projects/" + pid, Map.of("repoUrl", "evil"), null).status());
    assertEquals(401, raw(bare, "GET", "/api/audit", null, null).status());
  }

  @Test
  void theDaemonTokenCanBeCheckedWithoutTouchingTheConnection() throws Exception {
    HttpClient bare = HttpClient.newHttpClient();
    Res health = raw(bare, "GET", "/api/health", null, null);
    assertTrue(health.status() == 200 && health.body().contains("\"version\""), "health tells the version: " + health.body());
    String code = Json.str(call("POST", "/api/runtimes/registration-codes", null), "code");
    Object reg = call("POST", "/api/daemon/register", Map.of("code", code, "name", "doctor-check"));
    Res ok = raw(bare, "GET", "/api/daemon/whoami", null, Json.str(reg, "token"));
    assertEquals(200, ok.status(), ok.body());
    assertTrue(ok.body().contains("doctor-check"), ok.body());
    assertEquals(401, raw(bare, "GET", "/api/daemon/whoami", null, "orca_d_wrong").status());
    call("DELETE", "/api/runtimes/" + Json.str(reg, "runtimeId"), null);
    assertEquals(401, raw(bare, "GET", "/api/daemon/whoami", null, Json.str(reg, "token")).status(), "removed runtime");
  }

  @Test
  void askingAboutAProjectWorksInACheckoutOfItsRepository() throws Exception {
    Path repo = Files.createTempDirectory("orca-e2e-ask-repo");
    Files.writeString(repo.resolve("README.md"), "stock service\n");
    for (List<String> cmd : List.of(List.of("git", "init", "-q", "-b", "main"), List.of("git", "add", "."),
        List.of("git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "init"))) {
      assertEquals(0, new ProcessBuilder(cmd).directory(repo.toFile()).inheritIO().start().waitFor());
    }
    call("POST", "/api/projects", Map.of("key", "ASK" + RUN, "name", "Asked", "repoUrl", repo.toString()));
    String first = Json.str(call("POST", "/api/agents/" + agentId + "/ask", Map.of("prompt", "what is this?", "project", "ask" + RUN.toLowerCase())), "id");
    await("answer", quietly(() -> "succeeded".equals(Json.str(call("GET", "/api/tasks/" + first, null), "status"))));
    String cwd = Json.str(Json.obj(sessionEvent(first), "data"), "cwd");
    assertTrue(Files.isRegularFile(Path.of(cwd, "README.md")), "the agent starts in a checkout: " + cwd);
    assertTrue(cwd.endsWith("-ASK" + RUN), cwd);
    String second = Json.str(call("POST", "/api/agents/" + agentId + "/ask",
        Map.of("prompt", "and the tests?", "followUp", first)), "id");
    await("follow-up", quietly(() -> "succeeded".equals(Json.str(call("GET", "/api/tasks/" + second, null), "status"))));
    assertEquals(cwd, Json.str(Json.obj(sessionEvent(second), "data"), "cwd"), "a follow-up keeps the checkout");
  }

  @Test
  @SuppressWarnings("unchecked")
  void aRuntimeIsListedUnderItsNameFromRegistration() throws Exception {
    String code = Json.str(call("POST", "/api/runtimes/registration-codes", null), "code");
    Map<String, Object> reg = (Map<String, Object>) call("POST", "/api/daemon/register",
        Map.of("code", code, "name", "  build-box-7  "));
    String id = Json.str(reg, "runtimeId");
    List<Map<String, Object>> all = (List<Map<String, Object>>) call("GET", "/api/runtimes", null);
    assertEquals("build-box-7", all.stream().filter(r -> id.equals(r.get("id"))).findFirst().orElseThrow().get("name"));
    call("DELETE", "/api/runtimes/" + id, null);
  }

  @Test
  void theCliWorksWithAPersonalAccessToken() throws Exception {
    String pat = Json.str(call("POST", "/api/auth/tokens", Map.of("label", "ci", "days", 7)), "token");
    assertTrue(pat.startsWith("orca_p_"), pat);
    Path config = Files.createTempFile("orca-cli", ".json");
    Map<String, String> env = Map.of("ORCA_SERVER", "http://localhost:" + port, "ORCA_TOKEN", pat,
        "ORCA_CLI_CONFIG", config.toString());
    assertEquals(0, cli(env, "whoami").code(), "the token signs the CLI in");
    String created = cli(env, "issue", "create", "--title", "From the CLI", "--description", "hello",
        "--assign", "Builder").out();
    String key = created.split(" ")[1].replace(";", "").trim();
    await("worked on", quietly(() -> "in_review".equals(Json.str(call("GET", "/api/issues/" + key, null), "status"))));
    Cli show = cli(env, "issue", "show", key);
    assertTrue(show.out().contains("From the CLI") && show.out().contains("Builder"), show.out());
    Cli ask = cli(env, "ask", "Builder", "hello");
    assertEquals(0, ask.code(), ask.err());
    assertTrue(cli(env, "agents", "--json").out().contains("\"Builder\""));
    assertEquals(1, cli(Map.of("ORCA_SERVER", "http://localhost:" + port, "ORCA_TOKEN", "orca_p_wrong",
        "ORCA_CLI_CONFIG", config.toString()), "whoami").code(), "a wrong token is refused");

    String second = Json.str(call("POST", "/api/auth/tokens", Map.of("label", "laptop", "days", 7)), "token");
    Path saved = Files.createTempDirectory("orca-cli-home").resolve("cli.json");
    Map<String, String> fileEnv = Map.of("ORCA_CLI_CONFIG", saved.toString());
    assertEquals(0, cli(fileEnv, "login", "--server", "http://localhost:" + port, "--token", second).code());
    if (saved.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(saved)));
    }
    assertEquals(2, cli(fileEnv, "issues", "--wiat").code(), "unknown options are refused");
    assertEquals(0, cli(fileEnv, "logout").code());
    assertTrue(Files.notExists(saved));
    assertEquals(1, cli(Map.of("ORCA_SERVER", "http://localhost:" + port, "ORCA_TOKEN", second,
        "ORCA_CLI_CONFIG", config.toString()), "whoami").code(), "logout revoked the token on the server");
  }

  record Cli(int code, String out, String err) {}

  private static Cli cli(Map<String, String> env, String... args) {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
    int code = new dev.orca.cli.Main(new java.io.PrintStream(out, true), new java.io.PrintStream(err, true), env).run(args);
    return new Cli(code, out.toString(), err.toString());
  }

  @Test
  void aMissingProjectFailsTheNodeInsteadOfLosingTheRun() throws Exception {
    for (Object[] c : List.of(new Object[] {"NOPE", "NOPE"}, new Object[] {"{{ trigger.input.project }}", "rendered to nothing"})) {
      Map<String, Object> def = Map.of(
          "nodes", List.of(
              Map.of("id", "start", "type", "trigger.manual", "config", Map.of()),
              Map.of("id", "check", "type", "agent.run", "config", Map.of("agent", "Builder", "project", c[0],
                  "issue", "", "prompt", "hello"))),
          "edges", List.of(Map.of("id", "e1", "source", "start", "target", "check")));
      String wf = Json.str(call("POST", "/api/workflows", Map.of("name", "Bad project " + c[1], "definition", def)), "id");
      String run = Json.str(call("POST", "/api/workflows/" + wf + "/runs", Map.of("input", Map.of())), "id");
      Object r = call("GET", "/api/runs/" + run, null);
      assertEquals("failed", Json.str(r, "status"), String.valueOf(Json.get(r, "state.nodes")));
      assertTrue(String.valueOf(Json.get(r, "state.nodes.check.error")).contains(String.valueOf(c[1])),
          String.valueOf(Json.get(r, "state.nodes.check")));
    }
  }


  @org.springframework.beans.factory.annotation.Autowired
  dev.orca.server.task.TaskService taskService;

  @org.springframework.beans.factory.annotation.Autowired
  dev.orca.server.repo.TaskRepo taskRepo;

  private dev.orca.server.model.Task rawTask(String status, String runtimeId, Long startedAt, Long leaseUntil) {
    dev.orca.server.model.Task t = new dev.orca.server.model.Task();
    t.id = dev.orca.server.support.Ids.next("tsk");
    t.agentId = "agt_reconcile";
    t.provider = "codex";
    t.kind = "ask";
    t.prompt = "x";
    t.status = status;
    t.runtimeId = runtimeId;
    t.startedAt = startedAt;
    t.leaseUntil = leaseUntil;
    t.workspaceKey = "adhoc/" + t.id;
    t.queuedAt = System.currentTimeMillis();
    return taskRepo.save(t);
  }

  @Test
  void aReconnectingRuntimeKeepsItsTasksAndTakesBackRequeuedOnes() {
    long now = System.currentTimeMillis();
    // hold the dispatcher's lock so it cannot pick up the queued tasks while the reconnect is handled
    Object dispatcher = org.springframework.test.util.AopTestUtils.getTargetObject(taskService);
    if (agentRepo.findById("agt_reconcile").isEmpty()) {
      var a = new dev.orca.server.model.Agent();
      a.id = "agt_reconcile";
      a.name = "Reconcile";
      a.provider = "codex";
      a.permission = "workspace";
      a.runtimeId = "rt_reconnect";
      a.maxConcurrency = 1;
      a.timeoutMinutes = 60;
      a.archived = true; // not picked by other tests
      a.createdAt = now;
      a.updatedAt = now;
      agentRepo.save(a);
    }
    synchronized (dispatcher) {
      var running = rawTask("running", "rt_reconnect", now - 120_000, now - 5_000);
      var requeued = rawTask("queued", null, now - 120_000, null);
      requeued.statusDetail = "re-queued: the runtime did not start it";
      taskRepo.save(requeued);
      var stranger = rawTask("queued", null, null, null);
      // another runtime cannot claim a queued task by naming its id, nor one its agent is not bound to
      taskService.reconcile("rt_other", List.of(new dev.orca.protocol.Wire.RunningTask(requeued.id, 0)));
      assertEquals(null, taskRepo.findById(requeued.id).orElseThrow().runtimeId, "only the agent's runtime takes it back");
      var neverReceived = rawTask("dispatched", "rt_reconnect", null, now + 30_000);
      var forgotten = rawTask("waiting_input", "rt_reconnect", now - 120_000, now + 30_000);
      List<String> lost = taskService.reconcile("rt_reconnect", List.of(
          new dev.orca.protocol.Wire.RunningTask(running.id, 0), new dev.orca.protocol.Wire.RunningTask(requeued.id, 0),
          new dev.orca.protocol.Wire.RunningTask(stranger.id, 0)));
      assertEquals(null, taskRepo.findById(stranger.id).orElseThrow().runtimeId, "a task never re-queued is not adopted");
      var r1 = taskRepo.findById(running.id).orElseThrow();
      assertTrue(r1.leaseUntil != null && r1.leaseUntil > now, "the lease is renewed at once, before any heartbeat");
      var r2 = taskRepo.findById(requeued.id).orElseThrow();
      assertEquals("rt_reconnect", r2.runtimeId, "a task re-queued during the outage is taken back, not run twice");
      assertEquals("running", r2.status);
      var r3 = taskRepo.findById(neverReceived.id).orElseThrow();
      assertEquals("queued", r3.status, "an assignment that never arrived is queued again");
      assertEquals(List.of(forgotten.id), lost, "a task waiting for input that the runtime no longer has is lost");
      for (var t : List.of(r1, r2, r3, stranger)) {
        t = taskRepo.findById(t.id).orElseThrow();
        t.status = "cancelled";
        taskRepo.save(t);
      }
    }
  }


  @Test
  void eventsAreAcknowledgedOnlyWithoutGaps() {
    long now = System.currentTimeMillis();
    var t = rawTask("running", "rt_seq", now, now + 60_000);
    java.util.function.LongFunction<dev.orca.protocol.AgentEvent> ev = n ->
        new dev.orca.protocol.AgentEvent(n, now, dev.orca.protocol.AgentEvent.NOTICE, Map.of("message", "e" + n));
    taskService.events("rt_seq", new dev.orca.protocol.Wire.TaskEvents(t.id, List.of(ev.apply(1), ev.apply(2))));
    assertEquals(2, taskRepo.findById(t.id).orElseThrow().lastSeq);
    // the batch with 3 failed here; 4 and 5 arrive
    taskService.events("rt_seq", new dev.orca.protocol.Wire.TaskEvents(t.id, List.of(ev.apply(4), ev.apply(5))));
    assertEquals(2, taskRepo.findById(t.id).orElseThrow().lastSeq, "not acknowledged past the gap");
    // the daemon resends from 3: the gap is filled, 4 and 5 are not stored twice
    taskService.events("rt_seq", new dev.orca.protocol.Wire.TaskEvents(t.id, List.of(ev.apply(3), ev.apply(4), ev.apply(5))));
    assertEquals(5, taskRepo.findById(t.id).orElseThrow().lastSeq);
    var done = taskRepo.findById(t.id).orElseThrow();
    done.status = "cancelled";
    taskRepo.save(done);
  }


  @org.springframework.beans.factory.annotation.Autowired
  dev.orca.server.repo.AgentRepo agentRepo;

  @Test
  void anAskCanBeContinuedAfterItFailedButNotWhileItRuns() {
    long now = System.currentTimeMillis();
    var agent = new dev.orca.server.model.Agent();
    agent.id = dev.orca.server.support.Ids.next("agt");
    agent.name = "FollowUp" + RUN;
    agent.provider = "codex";
    agent.permission = "workspace";
    agent.maxConcurrency = 1;
    agent.timeoutMinutes = 5;
    agent.createdAt = now;
    agent.updatedAt = now;
    agent.archived = false; // no runtime: the dispatcher leaves its tasks queued
    agentRepo.save(agent);
    Object dispatcher = org.springframework.test.util.AopTestUtils.getTargetObject(taskService);
    synchronized (dispatcher) {
      var crashed = rawTask("failed", null, now - 60_000, null);
      crashed.agentId = agent.id;
      crashed.sessionRef = "thr_crashed";
      taskRepo.save(crashed);
      var followUp = taskService.followUp(agent, crashed.id, "carry on", null);
      assertEquals("thr_crashed", followUp.resumeRef, "the crashed task's session is continued");
      var running = rawTask("running", "rt_x", now, now + 60_000);
      running.agentId = agent.id;
      running.sessionRef = "thr_running";
      taskRepo.save(running);
      var refused = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
          () -> taskService.followUp(agent, running.id, "more", null));
      assertTrue(refused.getMessage().contains("still running"), refused.getMessage());
      for (String id : List.of(crashed.id, followUp.id, running.id)) {
        var t = taskRepo.findById(id).orElseThrow();
        t.status = "cancelled";
        taskRepo.save(t);
      }
    }
  }


  @org.springframework.beans.factory.annotation.Autowired
  dev.orca.server.workflow.TriggerService triggerService;

  @org.springframework.beans.factory.annotation.Autowired
  dev.orca.server.repo.ScheduleRepo scheduleRepo;

  @org.springframework.beans.factory.annotation.Autowired
  dev.orca.server.workflow.WorkflowService workflowService;

  private static Map<String, Object> scheduled(String cron) {
    return Map.of("nodes", List.of(
            Map.of("id", "every", "type", "trigger.schedule", "config", Map.of("cron", cron, "timezone", "Asia/Singapore")),
            Map.of("id", "note", "type", "logic.set", "config", Map.of("values", Map.of("ok", true)))),
        "edges", List.of(Map.of("id", "e1", "source", "every", "target", "note")));
  }

  @Test
  void aScheduleIsRecomputedWhenEditedAndDoesNotReplayAStaleTime() throws Exception {
    String wf = Json.str(call("POST", "/api/workflows", Map.of("name", "Nightly", "enabled", true,
        "definition", scheduled("0 0 3 * * *"))), "id");
    triggerService.tick();
    var st = scheduleRepo.findById(wf + ":every").orElseThrow();
    long nightly = st.nextFireAt;
    assertTrue(nightly > System.currentTimeMillis());
    // a stale time (the workflow was disabled for a day) is not fired on the next tick
    st.nextFireAt = System.currentTimeMillis() - 86_400_000L;
    scheduleRepo.save(st);
    triggerService.tick();
    assertTrue(scheduleRepo.findById(wf + ":every").orElseThrow().nextFireAt > System.currentTimeMillis());
    assertEquals(0, ((List<?>) call("GET", "/api/runs?workflowId=" + wf, null)).size(), "no surprise run");
    // editing the cron takes effect at once, not after the old next time
    call("PUT", "/api/workflows/" + wf, Map.of("definition", scheduled("0 */5 * * * *")));
    triggerService.tick();
    long edited = scheduleRepo.findById(wf + ":every").orElseThrow().nextFireAt;
    assertTrue(edited - System.currentTimeMillis() <= 5 * 60_000L, "the new schedule's next time is used");
    call("POST", "/api/workflows/" + wf + "/enabled", Map.of("enabled", false));
  }

  @Test
  void aTimedOutApprovalIsClosedInTheInbox() throws Exception {
    Map<String, Object> def = Map.of(
        "nodes", List.of(
            Map.of("id", "start", "type", "trigger.manual", "config", Map.of()),
            Map.of("id", "ask", "type", "human.approval", "timeoutSeconds", 1, "config", Map.of("title", "Expire me"))),
        "edges", List.of(Map.of("id", "e1", "source", "start", "target", "ask")));
    String wf = Json.str(call("POST", "/api/workflows", Map.of("name", "Expiring", "definition", def)), "id");
    String run = Json.str(call("POST", "/api/workflows/" + wf + "/runs", Map.of("input", Map.of())), "id");
    Thread.sleep(1_200);
    workflowService.expireDeadlines();
    assertEquals("failed", Json.str(call("GET", "/api/runs/" + run, null), "status"));
    Object item = ((List<?>) call("GET", "/api/inbox", null)).stream()
        .filter(i -> run.equals(Json.str(i, "runId")) && "Expire me".equals(Json.str(i, "title"))).findFirst().orElseThrow();
    assertEquals("expired", Json.str(item, "status"), "a timed-out approval can no longer be answered");
  }

  @Test
  void aRedeliveredWebhookStartsNoSecondRun() throws Exception {
    Map<String, Object> def = Map.of("nodes", List.of(
            Map.of("id", "hook", "type", "trigger.webhook", "config", Map.of("secret", "s3cret-for-test")),
            Map.of("id", "note", "type", "logic.set", "config", Map.of("values", Map.of("ok", true)))),
        "edges", List.of(Map.of("id", "e1", "source", "hook", "target", "note")));
    String wf = Json.str(call("POST", "/api/workflows", Map.of("name", "Hooked", "enabled", true, "definition", def)), "id");
    java.util.function.Function<String, String> deliver = id -> {
      try {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/hooks/" + wf + "/hook"))
            .header("Content-Type", "application/json").header("X-Orca-Secret", "s3cret-for-test")
            .POST(HttpRequest.BodyPublishers.ofString("{\"ref\":\"main\"}"));
        if (id != null) {
          b.header("X-GitHub-Delivery", id);
        }
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        return Json.str(Json.MAPPER.readValue(res.body(), Object.class), "runId");
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    };
    String first = deliver.apply("d-1");
    assertEquals(first, deliver.apply("d-1"), "GitHub retried the delivery: the same run is returned");
    assertTrue(!first.equals(deliver.apply("d-2")), "a new delivery starts a new run");
    assertTrue(!deliver.apply(null).equals(deliver.apply(null)), "without a delivery id every POST is a run");
    assertEquals(4, ((List<?>) call("GET", "/api/runs?workflowId=" + wf, null)).size());
  }
}
