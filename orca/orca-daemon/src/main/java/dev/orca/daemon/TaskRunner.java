package dev.orca.daemon;

import dev.orca.agent.core.AgentException;
import dev.orca.agent.core.AgentProvider;
import dev.orca.agent.core.AgentSession;
import dev.orca.agent.core.Executables;
import dev.orca.agent.core.Interaction;
import dev.orca.agent.core.Permission;
import dev.orca.agent.core.SessionSpec;
import dev.orca.agent.core.TurnResult;
import dev.orca.agent.core.TurnSpec;
import dev.orca.protocol.AgentEvent;
import dev.orca.protocol.ErrorCodes;
import dev.orca.protocol.Wire;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Executes one assigned task: prepares the workspace, runs the agent session, streams events. */
final class TaskRunner implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(TaskRunner.class);
  private static final Duration INPUT_WAIT = Duration.ofHours(12);

  private final Wire.TaskAssign task;
  private final AgentProvider provider;
  private final Workspaces workspaces;
  private final Link link;
  private final boolean isolated;
  private final boolean allowExtraArgs;
  private final String server;
  private final Runnable onDone;
  private final EventBuffer buffer = new EventBuffer();
  private final Map<String, CompletableFuture<Interaction.InputReply>> inputs = new ConcurrentHashMap<>();
  private final ConcurrentLinkedQueue<String> followUps = new ConcurrentLinkedQueue<>();
  private volatile AgentSession session;
  private volatile boolean cancelRequested;
  private volatile boolean finished;
  private volatile Wire.Frame completion;

  TaskRunner(Wire.TaskAssign task, AgentProvider provider, Workspaces workspaces, Link link, boolean isolated,
      boolean allowExtraArgs, String server, Runnable onDone) {
    this.allowExtraArgs = allowExtraArgs;
    this.server = server == null ? "" : server.replaceAll("/+$", "");
    this.task = task;
    this.provider = provider;
    this.workspaces = workspaces;
    this.link = link;
    this.isolated = isolated;
    this.onDone = onDone;
  }

  String taskId() {
    return task.taskId();
  }

  long lastSeq() {
    return buffer.lastSeq();
  }

  @Override
  public void run() {
    Thread flusher = Thread.ofVirtual().name("flush-" + task.taskId()).start(this::flushLoop);
    Wire.TaskCompleted result;
    try {
      result = execute();
    } catch (AgentException e) {
      buffer.add(AgentEvent.of(AgentEvent.NOTICE, "level", "error", "message", e.getMessage(), "code", e.code()));
      result = completed(cancelRequested ? "cancelled" : "failed", null, null, null, e.code(), e.getMessage(), Map.of());
    } catch (RuntimeException e) {
      log.error("task {} crashed", task.taskId(), e);
      result = completed("failed", null, null, null, ErrorCodes.AGENT_ERROR, String.valueOf(e), Map.of());
    } finally {
      AgentSession s = session;
      if (s != null) {
        s.close();
      }
      inputs.values().forEach(f -> f.complete(Interaction.InputReply.decline()));
    }
    finished = true;
    flusher.interrupt();
    flush();
    completion = Wire.frame(Wire.TASK_COMPLETED, withSeq(result));
    link.send(completion);
    log.info("task {} {}", task.taskId(), result.status());
    onDone.run();
  }

  private Wire.TaskCompleted withSeq(Wire.TaskCompleted r) {
    return new Wire.TaskCompleted(r.taskId(), r.status(), r.finalText(), r.structured(), r.sessionRef(),
        r.errorCode(), r.error(), r.usage(), buffer.lastSeq());
  }

  private Wire.TaskCompleted execute() throws AgentException {
    link.send(Wire.frame(Wire.TASK_ACCEPTED, new Wire.TaskAccepted(task.taskId())));
    Wire.AgentSpec a = task.agent();
    Permission permission = Permission.parse(a == null ? null : a.permission());
    if (permission == Permission.FULL && !isolated) {
      buffer.add(AgentEvent.of(AgentEvent.NOTICE, "level", "warn",
          "message", "full access is only allowed on isolated runtimes; using workspace permissions"));
      permission = Permission.WORKSPACE;
    }
    Path dir = workspaces.prepare(task.workspaceKey(), task.repo());
    ApprovalPolicy policy = new ApprovalPolicy(permission, dir, a == null ? List.of() : a.allowedCommands());
    Interaction.Handler handler = new Interaction.Handler() {
      public Interaction.Decision approve(Interaction.ApprovalRequest r) {
        return policy.decide(r);
      }

      public Interaction.InputReply input(Interaction.InputRequest r) {
        return awaitInput(r);
      }
    };
    List<String> extra = ExtraArgs.filter(a == null ? List.of() : a.extraArgs(), allowExtraArgs);
    if (a != null && a.extraArgs() != null && extra.size() != a.extraArgs().size()) {
      buffer.add(AgentEvent.of(AgentEvent.NOTICE, "level", "warn", "message", allowExtraArgs
          ? "sandbox and approval arguments in the agent's extra arguments were ignored"
          : "the agent's extra CLI arguments were ignored; start the daemon with --allow-extra-args to use them"));
    }
    SessionSpec spec = new SessionSpec(dir, a == null ? null : a.model(), a == null ? null : a.effort(),
        a == null ? null : a.instructions(), permission, task.sessionRef(),
        task.mcp() == null ? null : absolute(task.mcp().url()), task.mcp() == null ? null : task.mcp().token(),
        a == null ? null : a.allowedCommands(), extra, a == null ? null : a.env());
    String prompt = task.prompt();
    try {
      session = provider.open(spec, handler);
    } catch (AgentException e) {
      if (!ErrorCodes.SESSION_NOT_FOUND.equals(e.code())) {
        throw e;
      }
      buffer.add(AgentEvent.of(AgentEvent.NOTICE, "level", "warn",
          "message", "previous session could not be resumed; starting a new one with a recap"));
      session = provider.open(spec.withResume(null), handler);
      if (task.recap() != null && !task.recap().isBlank()) {
        prompt = task.recap() + "\n\n---\n\n" + prompt;
      }
    }
    if (cancelRequested) {
      return completed("cancelled", null, null, session.sessionRef(), ErrorCodes.CANCELLED, "cancelled", Map.of());
    }
    link.send(Wire.frame(Wire.TASK_STARTED, new Wire.TaskStarted(task.taskId(), session.sessionRef(), session.model(),
        session.resumed())));
    buffer.add(AgentEvent.of(AgentEvent.SESSION, "provider", provider.id(), "sessionRef", session.sessionRef(),
        "model", session.model(), "resumed", session.resumed(), "cwd", dir.toString()));
    Duration timeout = Duration.ofSeconds(task.timeoutSeconds() > 0 ? task.timeoutSeconds() : 3600);

    List<Path> images = download(dir);
    buffer.add(AgentEvent.of(AgentEvent.USER_MESSAGE, "text", task.prompt(),
        "images", images.isEmpty() ? null : images.stream().map(p -> p.getFileName().toString()).toList()));
    TurnResult r = session.runTurn(new TurnSpec(prompt, images, task.outputSchema(), timeout), buffer::add);
    Map<String, Object> usage = new LinkedHashMap<>(r.usage());
    while (r.ok() && !cancelRequested && !followUps.isEmpty()) {
      List<String> texts = new ArrayList<>();
      String t;
      while ((t = followUps.poll()) != null) {
        texts.add(t);
      }
      String follow = String.join("\n\n", texts);
      buffer.add(AgentEvent.of(AgentEvent.USER_MESSAGE, "text", follow, "followUp", true));
      r = session.runTurn(new TurnSpec(follow, List.of(), task.outputSchema(), timeout), buffer::add);
      usage.putAll(r.usage());
    }
    if (r.ok() && task.repo() != null) {
      autoCommit(dir, r.finalText());
    }
    String status = switch (r.status()) {
      case COMPLETED -> "succeeded";
      case INTERRUPTED -> cancelRequested ? "cancelled" : "failed";
      case FAILED -> ErrorCodes.TIMEOUT.equals(r.errorCode()) ? "timed_out" : "failed";
    };
    return completed(status, r.finalText(), r.structured(), session.sessionRef(), r.errorCode(), r.error(), usage);
  }

  /** Server paths are relative so they work from wherever this daemon reaches the server. */
  private String absolute(String url) {
    return url != null && url.startsWith("/") ? server + url : url;
  }

  /** Fetches the issue's new images into {@code .orca/attachments} with the task token. */
  private List<Path> download(Path dir) {
    if (task.images() == null || task.images().isEmpty() || task.taskToken() == null) {
      return List.of();
    }
    List<Path> out = new ArrayList<>();
    java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    try {
      Path folder = dir.resolve(".orca/attachments");
      java.nio.file.Files.createDirectories(folder);
      for (Wire.FileRef f : task.images()) {
        Path target = folder.resolve(f.id() + "-" + f.name().replaceAll("[^A-Za-z0-9._-]", "_"));
        java.net.http.HttpResponse<Path> res = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(absolute(f.url())))
            .timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + task.taskToken()).GET().build(),
            java.net.http.HttpResponse.BodyHandlers.ofFile(target));
        if (res.statusCode() == 200) {
          out.add(target);
        } else {
          java.nio.file.Files.deleteIfExists(target);
          buffer.add(AgentEvent.of(AgentEvent.NOTICE, "level", "warn", "message",
              "could not download " + f.name() + ": HTTP " + res.statusCode()));
        }
      }
    } catch (Exception e) {
      buffer.add(AgentEvent.of(AgentEvent.NOTICE, "level", "warn", "message", "could not download attachments: " + e));
    }
    return out;
  }

  /**
   * Captures the agent's work on the issue branch when it left changes uncommitted (Codex keeps .git
   * read-only in its sandbox, and smaller models often do not ask to escalate).
   */
  private void autoCommit(Path dir, String finalText) {
    Executables.Output st = Executables.run(List.of("git", "-C", dir.toString(), "status", "--porcelain"), Map.of(), 30);
    if (st.exitCode() != 0 || st.stdout().isBlank()) {
      return;
    }
    String summary = finalText == null ? "" : finalText.strip().lines().findFirst().orElse("").replaceAll("[`*#>]", "");
    if (summary.length() > 72) {
      summary = summary.substring(0, 69) + "...";
    }
    String key = task.workspaceKey().contains("/") ? task.workspaceKey().substring(task.workspaceKey().lastIndexOf('/') + 1)
        : task.workspaceKey();
    String message = key + ": " + (summary.isBlank() ? "changes by " + (task.agent() == null ? "agent" : task.agent().name())
        : summary) + "\n\nCommitted by Orca for task " + task.taskId();
    Executables.run(List.of("git", "-C", dir.toString(), "add", "-A"), Map.of(), 60);
    List<String> commit = new ArrayList<>(List.of("git", "-C", dir.toString()));
    if (Executables.run(List.of("git", "-C", dir.toString(), "config", "user.email"), Map.of(), 10).stdout().isBlank()) {
      commit.addAll(List.of("-c", "user.name=Orca Agent", "-c", "user.email=agent@orca.local"));
    }
    commit.addAll(List.of("commit", "-q", "-m", message));
    Executables.Output c = Executables.run(commit, Map.of(), 60);
    Executables.Output head = Executables.run(List.of("git", "-C", dir.toString(), "rev-parse", "--short", "HEAD"), Map.of(), 10);
    buffer.add(AgentEvent.of(AgentEvent.NOTICE, "level", c.exitCode() == 0 ? "info" : "warn",
        "message", c.exitCode() == 0 ? "committed the remaining changes as " + head.stdout().trim()
            : "could not commit the remaining changes: " + Executables.plain(c.stderr())));
  }

  private Wire.TaskCompleted completed(String status, String text, Object structured, String ref, String code,
      String error, Map<String, Object> usage) {
    return new Wire.TaskCompleted(task.taskId(), status, text, structured, ref != null ? ref : sessionRefOrNull(), code,
        error, usage, 0);
  }

  private String sessionRefOrNull() {
    AgentSession s = session;
    return s == null ? null : s.sessionRef();
  }

  private Interaction.InputReply awaitInput(Interaction.InputRequest r) {
    CompletableFuture<Interaction.InputReply> f = new CompletableFuture<>();
    inputs.put(r.requestId(), f);
    flush();
    try {
      return f.get(INPUT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      return Interaction.InputReply.decline();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Interaction.InputReply.decline();
    } catch (Exception e) {
      return Interaction.InputReply.decline();
    } finally {
      inputs.remove(r.requestId());
    }
  }

  // ---------------------------------------------------------------- control from the server

  void cancel(String reason) {
    cancelRequested = true;
    inputs.values().forEach(f -> f.complete(Interaction.InputReply.decline()));
    AgentSession s = session;
    if (s != null) {
      s.interrupt();
    }
  }

  void steer(String text) {
    AgentSession s = session;
    if (s != null && s.capabilities().contains(dev.orca.agent.core.Capability.STEER)) {
      try {
        s.steer(text);
        buffer.add(AgentEvent.of(AgentEvent.USER_MESSAGE, "text", text, "steer", true));
        return;
      } catch (AgentException | UnsupportedOperationException e) {
        log.debug("steer failed, queueing as follow-up: {}", e.getMessage());
      }
    }
    followUps.add(text);
    buffer.add(AgentEvent.of(AgentEvent.NOTICE, "level", "info", "message", "message queued for the next turn"));
  }

  @SuppressWarnings("unchecked")
  void input(String requestId, Map<String, Object> answer) {
    CompletableFuture<Interaction.InputReply> f = inputs.get(requestId);
    if (f == null) {
      return;
    }
    if (answer == null || Boolean.TRUE.equals(answer.get("_declined"))) {
      f.complete(Interaction.InputReply.decline());
      return;
    }
    Map<String, List<String>> answers = new LinkedHashMap<>();
    answer.forEach((k, v) -> {
      if (v instanceof List<?> l) {
        answers.put(k, l.stream().map(String::valueOf).toList());
      } else if (v != null) {
        answers.put(k, List.of(String.valueOf(v)));
      }
    });
    f.complete(new Interaction.InputReply(answers, false));
  }

  void ack(long seq) {
    buffer.ack(seq);
  }

  /** After a reconnect: resend what the server has not acknowledged, and the completion if done. */
  void replay() {
    List<AgentEvent> again = buffer.unacked();
    if (!again.isEmpty()) {
      link.send(Wire.frame(Wire.TASK_EVENTS, new Wire.TaskEvents(task.taskId(), again)));
    }
    if (finished && completion != null) {
      link.send(completion);
    }
  }

  boolean finished() {
    return finished;
  }

  // ---------------------------------------------------------------- streaming

  private void flushLoop() {
    while (!finished) {
      try {
        Thread.sleep(250);
      } catch (InterruptedException e) {
        return;
      }
      flush();
    }
  }

  private synchronized void flush() {
    if (!link.connected()) {
      return; // keep pending; drain() only after a successful connection
    }
    List<AgentEvent> batch = buffer.drain();
    if (!batch.isEmpty()) {
      link.send(Wire.frame(Wire.TASK_EVENTS, new Wire.TaskEvents(task.taskId(), batch)));
    }
  }
}
