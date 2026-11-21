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
  private final boolean allowNetwork;
  /** This task's own directory, once prepared. */
  private volatile Path taskDir;
  private final String server;
  private final Runnable onDone;
  private final EventBuffer buffer = new EventBuffer();
  private final Map<String, CompletableFuture<Interaction.InputReply>> inputs = new ConcurrentHashMap<>();
  /** Approval requests a person decided (escalated from the policy). */
  private final java.util.Set<String> decidedByPerson = ConcurrentHashMap.newKeySet();
  private final ConcurrentLinkedQueue<String> followUps = new ConcurrentLinkedQueue<>();
  /** Set (under the followUps lock) once no further turn will run; later messages are refused, not lost. */
  private boolean followUpsClosed;
  /** Commands a person allowed "for this task": exact, after unwrapping the shell; gone when the task ends. */
  private final java.util.Set<String> taskGrants = ConcurrentHashMap.newKeySet();
  private volatile AgentSession session;
  private volatile boolean cancelRequested;
  private volatile boolean finished;
  private volatile Wire.Frame completion;
  /** The last TASK_STARTED sent; resent after a reconnect, since the first may have been lost. */
  private volatile Wire.Frame startedFrame;
  private volatile boolean stopFlushing;

  TaskRunner(Wire.TaskAssign task, AgentProvider provider, Workspaces workspaces, Link link, boolean isolated,
      boolean allowExtraArgs, boolean allowNetwork, String server, Runnable onDone) {
    this.allowExtraArgs = allowExtraArgs;
    this.allowNetwork = allowNetwork;
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
    // stop the flusher without interrupting it: an interrupt during a socket send aborts the connection
    stopFlushing = true;
    try {
      flusher.join(5_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    // number every remaining event now, connected or not, so the completion's lastSeq covers them and a
    // reconnect replays them before the completion. Under the same lock as replay(), so one of the two sends
    // the completion; heartbeats resend it until the server acknowledges it (see resendCompletion).
    synchronized (this) {
      List<AgentEvent> rest = buffer.drain();
      completion = Wire.frame(Wire.TASK_COMPLETED, withSeq(result));
      finished = true;
      if (link.connected()) {
        if (!rest.isEmpty()) {
          sendEvents(rest);
        }
        link.send(completion);
      }
    }
    log.info("task {} {}", task.taskId(), result.status());
    // the runner stays registered until the server acknowledges the completion (see Daemon)
  }

  /** Called when the server confirmed it stored the completion. */
  void completionAcknowledged() {
    onDone.run();
  }

  private Wire.TaskCompleted withSeq(Wire.TaskCompleted r) {
    return new Wire.TaskCompleted(r.taskId(), r.status(), r.finalText(), r.structured(), r.sessionRef(),
        r.errorCode(), r.error(), r.usage(), buffer.lastSeq());
  }

  private Wire.TaskCompleted execute() throws AgentException {
    link.send(Wire.frame(Wire.TASK_ACCEPTED, new Wire.TaskAccepted(task.taskId())));
    if (PUSH.equals(task.action())) {
      return push();
    }
    Wire.AgentSpec a = task.agent();
    Permission permission = Permission.parse(a == null ? null : a.permission());
    if (task.review() != null && permission != Permission.READ_ONLY) {
      // a reviewer reads; whatever it might change would be committed by the next implementer
      permission = Permission.READ_ONLY;
    }
    if (permission == Permission.FULL && !isolated) {
      buffer.add(AgentEvent.of(AgentEvent.NOTICE, "level", "warn",
          "message", "full access is only allowed on isolated runtimes; using workspace permissions"));
      permission = Permission.WORKSPACE;
    }
    Path dir = workspaces.prepare(task.workspaceKey(), task.repo());
    taskDir = dir;
    List<String> allowed = ApprovalPolicy.sanitize(a == null ? List.of() : a.allowedCommands());
    ApprovalPolicy policy = new ApprovalPolicy(permission, dir, allowed);
    boolean escalate = a != null && a.escalateApprovals() && permission != Permission.READ_ONLY;
    Interaction.Handler handler = new Interaction.Handler() {
      public Interaction.Decision approve(Interaction.ApprovalRequest r) {
        if (cancelRequested) {
          return Interaction.Decision.DECLINE; // a request already queued when the task was cancelled
        }
        Interaction.Decision d = policy.decide(r);
        if (d == Interaction.Decision.DECLINE && r.kind() == Interaction.ApprovalKind.COMMAND && r.command() != null
            && taskGrants.contains(dev.orca.agent.core.Executables.unwrapShell(r.command()))) {
          // a person allowed exactly this command for the rest of the task; kept here, not in the CLI
          return Interaction.Decision.ACCEPT;
        }
        return d == Interaction.Decision.DECLINE && escalate ? askPerson(r) : d;
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
    boolean network = a != null && a.networkAccess() && permission == Permission.WORKSPACE;
    if (network && !allowNetwork) {
      buffer.add(AgentEvent.of(AgentEvent.NOTICE, "level", "warn", "message", "the agent asks for network access, but "
          + "this runtime does not allow it; start the daemon with --allow-network to grant it"));
      network = false;
    }
    SessionSpec spec = new SessionSpec(dir, a == null ? null : a.model(), a == null ? null : a.effort(),
        a == null ? null : a.instructions(), permission, task.sessionRef(),
        task.mcp() == null ? null : absolute(task.mcp().url()), task.mcp() == null ? null : task.mcp().token(),
        allowed, extra, agentEnv(a), network);
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
    sendStarted(Wire.frame(Wire.TASK_STARTED, new Wire.TaskStarted(task.taskId(), session.sessionRef(), session.model(),
        session.resumed())));
    buffer.add(AgentEvent.of(AgentEvent.SESSION, "provider", provider.id(), "sessionRef", session.sessionRef(),
        "model", session.model(), "resumed", session.resumed(), "cwd", dir.toString()));
    Duration timeout = Duration.ofSeconds(task.timeoutSeconds() > 0 ? task.timeoutSeconds() : 3600);

    List<Path> images = download(dir);
    boolean nativeReview = task.review() != null && session.capabilities().contains(dev.orca.agent.core.Capability.REVIEW);
    buffer.add(AgentEvent.of(AgentEvent.USER_MESSAGE, "text", nativeReview ? describeReview(task.review()) : task.prompt(),
        "images", images.isEmpty() ? null : images.stream().map(p -> p.getFileName().toString()).toList()));
    dev.orca.agent.core.AgentEventSink sink = e -> buffer.add(attribute(relativize(e, dir)));
    Map<String, Object> review = task.review();
    if (review != null && !session.capabilities().contains(dev.orca.agent.core.Capability.REVIEW)) {
      // no native review in this CLI: ask for the same review in words
      prompt = TurnSpec.reviewPrompt(review, prompt);
      review = null;
    }
    if (cancelRequested) {
      // cancelled while attachments were downloading
      return completed("cancelled", null, null, session.sessionRef(), ErrorCodes.CANCELLED, "cancelled", Map.of());
    }
    TurnResult r = session.runTurn(new TurnSpec(prompt, images, task.outputSchema(), timeout, review), sink);
    Map<String, Object> usage = new LinkedHashMap<>(r.usage());
    while (r.ok() && !cancelRequested) {
      List<String> texts = new ArrayList<>();
      synchronized (followUps) {
        if (followUps.isEmpty()) {
          followUpsClosed = true; // a message arriving from now on cannot be answered in this task
          break;
        }
        String t;
        while ((t = followUps.poll()) != null) {
          texts.add(t);
        }
      }
      String follow = String.join("\n\n", texts);
      buffer.add(AgentEvent.of(AgentEvent.USER_MESSAGE, "text", follow, "followUp", true));
      r = session.runTurn(new TurnSpec(follow, List.of(), task.outputSchema(), timeout), sink);
      usage.putAll(r.usage());
    }
    synchronized (followUps) {
      followUpsClosed = true;
      if (!followUps.isEmpty()) {
        buffer.add(AgentEvent.of(AgentEvent.NOTICE, "level", "warn", "message", followUps.size()
            + " queued message(s) were not sent because the turn did not complete; comment again to continue"));
        followUps.clear();
      }
    }
    // a review must not commit what it was asked to review; a cancelled task leaves its changes uncommitted
    if (r.ok() && !cancelRequested && task.repo() != null && task.review() == null) {
      autoCommit(dir, r.finalText());
    }
    String status = cancelRequested ? "cancelled" : switch (r.status()) {
      case COMPLETED -> "succeeded";
      case INTERRUPTED -> cancelRequested ? "cancelled" : "failed";
      case FAILED -> ErrorCodes.TIMEOUT.equals(r.errorCode()) ? "timed_out" : "failed";
    };
    // the answer is posted as a comment: paths in this machine's work folder read as repository paths
    String root = dir.toAbsolutePath().normalize().toString();
    String text = r.finalText() == null ? null : shortenPaths(r.finalText(), root, root + java.io.File.separator);
    return completed(status, text, r.structured(), session.sessionRef(), r.errorCode(), r.error(), usage);
  }

  /**
   * Commits made during the task carry the agent as author (the machine's git identity stays the
   * committer), so history shows which teammate wrote what.
   */
  static Map<String, String> agentEnv(Wire.AgentSpec a) {
    Map<String, String> env = new LinkedHashMap<>();
    if (a != null && a.name() != null && !a.name().isBlank()) {
      env.put("GIT_AUTHOR_NAME", a.name() + " (Orca agent)");
      env.put("GIT_AUTHOR_EMAIL", a.name().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]", "-")
          + "@agents.orca.invalid");
    }
    if (a != null && a.env() != null) {
      env.putAll(a.env());
    }
    return env;
  }

  static String describeReview(Map<String, Object> review) {
    return switch (String.valueOf(review.get("type"))) {
      case "baseBranch" -> "Review the changes on this branch against `" + review.getOrDefault("branch", "main") + "`.";
      case "uncommittedChanges" -> "Review the uncommitted changes.";
      case "commit" -> "Review commit " + review.get("sha") + ".";
      default -> TurnSpec.reviewPrompt(review, null);
    };
  }

  static final String PUSH = "git.push";

  /**
   * Pushes the issue's own branch with this machine's git credentials: only {@code orca/*} branches,
   * never main, never forced. No agent session is involved. Git hosts print a merge-request link on push;
   * it is returned so the workflow can post it.
   */
  private Wire.TaskCompleted push() throws AgentException {
    if (task.repo() == null || task.repo().branch() == null || !task.repo().branch().startsWith("orca/")) {
      return completed("failed", null, null, null, ErrorCodes.AGENT_ERROR, "nothing to push: the task has no orca branch",
          Map.of());
    }
    String branch = task.repo().branch();
    Path dir = workspaces.prepare(task.workspaceKey(), task.repo());
    sendStarted(Wire.frame(Wire.TASK_STARTED, new Wire.TaskStarted(task.taskId(), null, null, false)));
    Executables.Output head = Executables.run(SafeGit.cmd(dir, "rev-parse", "--abbrev-ref", "HEAD"),
        Map.of(), 30);
    if (head.exitCode() != 0 || !branch.equals(head.stdout().trim())) {
      return completed("failed", null, null, null, ErrorCodes.AGENT_ERROR,
          "the workspace is on " + head.stdout().trim() + ", not " + branch + "; not pushing", Map.of());
    }
    String shown = "git push -u origin " + branch;
    buffer.add(AgentEvent.of(AgentEvent.TOOL_STARTED, "itemId", "push", "kind", "command", "title", shown,
        "input", Map.of("command", shown)));
    Executables.Output out = Executables.run(SafeGit.cmd(dir, "push", "--porcelain", "--no-verify", "-u", "origin",
        "refs/heads/" + branch + ":refs/heads/" + branch), Map.of("GIT_TERMINAL_PROMPT", "0"), 300);
    String output = (out.stdout() + "\n" + out.stderr()).strip();
    buffer.add(AgentEvent.of(AgentEvent.TOOL_COMPLETED, "itemId", "push", "kind", "command", "title", shown,
        "status", out.exitCode() == 0 ? "completed" : "failed", "exitCode", out.exitCode(),
        "output", dev.orca.agent.core.Truncate.middle(Executables.plain(output), 4000)));
    if (out.exitCode() != 0) {
      return completed("failed", null, null, null, ErrorCodes.AGENT_ERROR, "git push failed: " + Executables.plain(out.stderr()),
          Map.of());
    }
    List<String> links = new ArrayList<>();
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://\\S+").matcher(output);
    while (m.find()) {
      links.add(m.group());
    }
    String text = "Pushed `" + branch + "` to origin." + (links.isEmpty() ? ""
        : "\n\nOpen a merge request: " + String.join(" ", links));
    buffer.add(AgentEvent.of(AgentEvent.MESSAGE, "itemId", "push-result", "text", text, "phase", "final_answer"));
    return completed("succeeded", text, Map.of("branch", branch, "links", links), null, null, null, Map.of());
  }

  /** Approvals a person decided are reported as such rather than as the policy's. */
  private AgentEvent attribute(AgentEvent e) {
    if (!AgentEvent.APPROVAL_RESOLVED.equals(e.type()) || !decidedByPerson.contains(String.valueOf(e.data().get("requestId")))) {
      return e;
    }
    Map<String, Object> data = new LinkedHashMap<>(e.data());
    data.put("by", "person");
    return new AgentEvent(e.seq(), e.ts(), e.type(), data);
  }

  /**
   * Paths are reported relative to the workspace: file changes, and the workspace path inside tool titles
   * and commands (TRAE puts absolute paths in titles such as "Search for '*.py' in /home/…/WMS-5"). Easier
   * to read, and the machine's directory layout stays on the machine.
   */
  static AgentEvent relativize(AgentEvent e, Path dir) {
    String root = dir.toAbsolutePath().normalize().toString();
    String prefix = root + java.io.File.separator;
    boolean touched = false;
    Map<String, Object> data = new LinkedHashMap<>(e.data());
    if (data.get("changes") instanceof List<?> changes && !changes.isEmpty()) {
      List<Object> out = new ArrayList<>();
      for (Object c : changes) {
        if (c instanceof Map<?, ?> m && m.get("path") instanceof String path && path.startsWith(prefix)) {
          Map<Object, Object> copy = new LinkedHashMap<>(m);
          copy.put("path", path.substring(prefix.length()));
          out.add(copy);
        } else {
          out.add(c);
        }
      }
      data.put("changes", out);
      touched = true;
    }
    // whole messages too (Codex review comments cite /abs/path/file.py:72); streamed deltas may split a path
    List<String> keys = AgentEvent.MESSAGE.equals(e.type()) ? List.of("title", "command", "text") : List.of("title", "command");
    for (String key : keys) {
      if (data.get(key) instanceof String text && text.contains(root)) {
        data.put(key, shortenPaths(text, root, prefix));
        touched = true;
      }
    }
    return touched ? new AgentEvent(e.seq(), e.ts(), e.type(), data) : e;
  }

  /** {@code /work/WMS-5/wms/x.py} becomes {@code wms/x.py}; the workspace itself becomes {@code .}. */
  static String shortenPaths(String text, String root, String prefix) {
    return text.replace(prefix, "").replaceAll(java.util.regex.Pattern.quote(root) + "(?![\\w./-])", ".");
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
  /**
   * Subject from what changed ({@code WMS-5: update wms/counting.py, tests/test_counting.py}), body from
   * the agent's own summary, so the history reads well even when the agent could not commit itself.
   */
  static String autoCommitMessage(String key, String porcelain, String finalText, String agent, String taskId) {
    List<String> files = porcelain.lines().filter(l -> l.length() > 3).map(l -> l.substring(3).trim())
        .map(f -> f.contains(" -> ") ? f.substring(f.indexOf(" -> ") + 4) : f).map(f -> f.replace("\"", "")).toList();
    String subject = key + ": update " + String.join(", ", files.subList(0, Math.min(2, files.size())))
        + (files.size() > 2 ? " and " + (files.size() - 2) + " more" : "");
    if (subject.length() > 72) {
      subject = key + ": update " + files.size() + " files";
    }
    String body = finalText == null ? "" : finalText.strip().lines().limit(30).map(String::stripTrailing)
        .collect(java.util.stream.Collectors.joining("\n"));
    return subject + "\n\n" + (body.isBlank() ? "" : body + "\n\n") + "Committed by Orca for " + agent + " (task " + taskId + ")";
  }

  private void autoCommit(Path dir, String finalText) {
    Executables.Output st = Executables.run(SafeGit.cmd(dir, "status", "--porcelain", "--ignore-submodules=all"), Map.of(), 30);
    if (st.exitCode() != 0 || st.stdout().isBlank()) {
      return;
    }
    String key = task.workspaceKey().contains("/") ? task.workspaceKey().substring(task.workspaceKey().lastIndexOf('/') + 1)
        : task.workspaceKey();
    String message = autoCommitMessage(key, st.stdout(), finalText, task.agent() == null ? "agent" : task.agent().name(),
        task.taskId());
    Executables.run(SafeGit.cmd(dir, "add", "-A"), Map.of(), 60);
    List<String> commit = SafeGit.cmd(dir);
    if (Executables.run(SafeGit.cmd(dir, "config", "user.email"), Map.of(), 10).stdout().isBlank()) {
      commit.addAll(List.of("-c", "user.name=Orca Agent", "-c", "user.email=agent@orca.local"));
    }
    commit.addAll(List.of("commit", "-q", "--no-verify", "-m", message));
    Executables.Output c = Executables.run(commit, agentEnv(task.agent()), 60);
    Executables.Output head = Executables.run(SafeGit.cmd(dir, "rev-parse", "--short", "HEAD"), Map.of(), 10);
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

  /**
   * The policy would decline; the agent is configured to ask a person instead. The question goes to
   * the inbox like any agent question, and the answer becomes the decision.
   */
  private Interaction.Decision askPerson(Interaction.ApprovalRequest r) {
    // only requests a person can judge from what is shown: a command, or write access to a directory.
    // Network and extra-permission requests carry details the card cannot show faithfully; they stay declined.
    if (cancelRequested || (r.kind() != Interaction.ApprovalKind.COMMAND && r.kind() != Interaction.ApprovalKind.FILE_CHANGE)) {
      return Interaction.Decision.DECLINE;
    }
    String requestId = "approval-" + r.requestId();
    List<String> paths = r.paths() == null ? List.of() : r.paths();
    boolean command = r.kind() == Interaction.ApprovalKind.COMMAND && r.command() != null;
    String shown = command ? dev.orca.agent.core.Executables.unwrapShell(r.command()) : null;
    String grantRoot = !command && r.cwd() != null ? shownPath(r.cwd()) : null;
    // "for this task" only for grants inside the task's own folder; anything wider is decided once at a time
    boolean sessionAllowed = command || grantRoot == null || !Path.of(grantRoot).isAbsolute();
    String what = command ? shown
        : grantRoot != null ? "write access under " + grantRoot + (paths.isEmpty() ? "" : " (" + String.join(", ", paths) + ")")
            : !paths.isEmpty() ? String.join(", ", paths) : String.valueOf(r.title());
    String agentName = task.agent() == null ? "The agent" : task.agent().name();
    String title = agentName + " asks to " + (command ? "run a command its policy does not allow"
        : "write files its policy does not allow");
    Map<String, Object> approval = new LinkedHashMap<>();
    approval.put("kind", r.kind().name().toLowerCase(java.util.Locale.ROOT));
    approval.put("command", shown);
    approval.put("paths", paths.isEmpty() ? null : paths);
    approval.put("grantRoot", grantRoot);
    approval.put("reason", r.reason());
    approval.put("cwd", command && r.cwd() != null ? shownPath(r.cwd()) : null);
    approval.put("sessionAllowed", sessionAllowed);
    approval.values().removeIf(java.util.Objects::isNull);
    Map<String, Object> question = new LinkedHashMap<>();
    question.put("id", "decision");
    question.put("header", "Approval");
    question.put("question", (r.reason() == null || r.reason().isBlank() ? "" : r.reason() + "\n\n") + what);
    question.put("options", sessionAllowed
        ? List.of(Map.of("label", APPROVE_ONCE), Map.of("label", APPROVE_TASK), Map.of("label", DENY))
        : List.of(Map.of("label", APPROVE_ONCE), Map.of("label", DENY)));
    buffer.add(AgentEvent.of(AgentEvent.INPUT_REQUESTED, "requestId", requestId, "title", title,
        "questions", List.of(question), "approval", approval));
    Interaction.InputReply reply = awaitInput(new Interaction.InputRequest(requestId, title, List.of(question), null));
    Interaction.Decision d = decisionFrom(reply);
    if (d == Interaction.Decision.ACCEPT_FOR_SESSION && !sessionAllowed) {
      d = Interaction.Decision.ACCEPT;
    }
    if (d == Interaction.Decision.ACCEPT_FOR_SESSION && command) {
      taskGrants.add(shown);
    }
    buffer.add(AgentEvent.of(AgentEvent.INPUT_RESOLVED, "requestId", requestId, "declined", reply.declined(),
        "answers", reply.answers()));
    if (!reply.declined()) {
      // a timeout or a cancellation is not a person's decision
      decidedByPerson.add(r.requestId());
    }
    return d;
  }

  static final String APPROVE_ONCE = "Allow once";
  static final String APPROVE_TASK = "Allow for this task";
  static final String DENY = "Deny";

  static Interaction.Decision decisionFrom(Interaction.InputReply reply) {
    if (reply == null || reply.declined()) {
      return Interaction.Decision.DECLINE;
    }
    List<String> v = reply.answers().getOrDefault("decision", List.of());
    String choice = v.isEmpty() ? "" : v.get(0);
    return switch (choice) {
      case APPROVE_ONCE, "accept" -> Interaction.Decision.ACCEPT;
      case APPROVE_TASK, "accept_for_session" -> Interaction.Decision.ACCEPT_FOR_SESSION;
      default -> Interaction.Decision.DECLINE;
    };
  }

  /**
   * A path as a person should see it: relative to this task's folder ({@code .} for the folder itself),
   * absolute for anything outside it, so a grant wider than the task is never disguised.
   */
  private String shownPath(String path) {
    try {
      Path p = Path.of(path).toAbsolutePath().normalize();
      Path dir = taskDir == null ? null : taskDir.toAbsolutePath().normalize();
      if (dir != null && p.startsWith(dir)) {
        String rel = dir.relativize(p).toString();
        return rel.isEmpty() ? "." : rel;
      }
      return p.toString();
    } catch (RuntimeException e) {
      return path;
    }
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
    if (task.review() != null) {
      // codex refuses to steer a review, and a follow-up turn would replace the review's result
      buffer.add(AgentEvent.of(AgentEvent.NOTICE, "level", "warn", "message",
          "a review cannot take messages while it runs; the message was not sent. Comment after it finishes."));
      return;
    }
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
    synchronized (followUps) {
      if (followUpsClosed) {
        buffer.add(AgentEvent.of(AgentEvent.NOTICE, "level", "warn", "message",
            "the task is finishing; the message was not sent. Comment or ask again to continue."));
        return;
      }
      followUps.add(text);
    }
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

  /** Events per frame: a replay after a long outage would otherwise exceed the server's frame limit. */
  static final int EVENTS_PER_FRAME = 200;

  private void sendEvents(List<AgentEvent> events) {
    for (int i = 0; i < events.size(); i += EVENTS_PER_FRAME) {
      link.send(Wire.frame(Wire.TASK_EVENTS, new Wire.TaskEvents(task.taskId(),
          events.subList(i, Math.min(events.size(), i + EVENTS_PER_FRAME)))));
    }
  }

  private void sendStarted(Wire.Frame f) {
    startedFrame = f;
    link.send(f);
  }

  /** Until acknowledged: the completion may have been lost (sent while the link was reconnecting). */
  synchronized void resendCompletion() {
    if (finished && completion != null && link.connected()) {
      replay();
    }
  }

  /** After a reconnect: resend what the server has not acknowledged, and the completion if done. */
  synchronized void replay() {
    if (startedFrame != null) {
      link.send(startedFrame); // applied once by the server; the first may have been sent while disconnected
    }
    buffer.drain(); // pending events from the disconnected period join the unacknowledged ones, in order
    List<AgentEvent> again = buffer.unacked();
    if (!again.isEmpty()) {
      sendEvents(again);
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
    while (!stopFlushing && !finished) {
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
      sendEvents(batch);
    }
  }
}
