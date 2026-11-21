package dev.fellowloop.server.issue;

import dev.fellowloop.server.model.Agent;
import dev.fellowloop.server.model.Comment;
import dev.fellowloop.server.model.Issue;
import dev.fellowloop.server.model.Project;
import dev.fellowloop.server.realtime.Realtime;
import dev.fellowloop.server.repo.AgentRepo;
import dev.fellowloop.server.repo.CommentRepo;
import dev.fellowloop.server.repo.IssueRepo;
import dev.fellowloop.server.repo.ProjectRepo;
import dev.fellowloop.server.support.ApiException;
import dev.fellowloop.server.support.Ids;
import dev.fellowloop.server.support.JsonCols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IssueService {

  public static final Set<String> STATUSES = Set.of("backlog", "todo", "in_progress", "in_review", "done", "cancelled");
  public static final Set<String> PRIORITIES = Set.of("none", "low", "medium", "high", "urgent");
  private static final Pattern MENTION = Pattern.compile("(?<![\\w@])@([A-Za-z0-9][A-Za-z0-9_-]{0,99})");

  private final IssueRepo issues;
  private final ProjectRepo projects;
  private final CommentRepo comments;
  private final AgentRepo agents;
  private final Realtime realtime;
  private final ApplicationEventPublisher publisher;

  public IssueService(IssueRepo issues, ProjectRepo projects, CommentRepo comments, AgentRepo agents,
      Realtime realtime, ApplicationEventPublisher publisher) {
    this.issues = issues;
    this.projects = projects;
    this.comments = comments;
    this.agents = agents;
    this.realtime = realtime;
    this.publisher = publisher;
  }

  public record IssueInput(String projectId, String title, String description, String status, String priority,
      String assigneeType, String assigneeId, List<String> labels) {}

  // a validation error (unknown issue, status or project) must not mark a workflow's surrounding transaction
  // rollback-only: the engine records the failed node, and its run must still be saved
  @Transactional(noRollbackFor = ApiException.class)
  public Issue create(IssueInput in, String actorType, String actorId) {
    if (in.title() == null || in.title().isBlank()) {
      throw ApiException.bad("title is required");
    }
    Project p = in.projectId() == null ? defaultProject()
        : projects.findById(in.projectId())
            .or(() -> projects.findByKeyPrefix(in.projectId().trim().toUpperCase(java.util.Locale.ROOT)))
            .orElseThrow(() -> ApiException.notFound("project"));
    Issue i = new Issue();
    // every check runs before anything changes: with noRollbackFor, a 400 must leave nothing half-applied
    setAssignee(i, in.assigneeType(), in.assigneeId());
    p.nextNumber++;
    projects.save(p);
    i.id = Ids.next("iss");
    i.projectId = p.id;
    i.number = p.nextNumber;
    i.issueKey = p.keyPrefix + "-" + i.number;
    i.title = in.title().trim();
    i.description = in.description();
    i.status = in.status() != null && STATUSES.contains(in.status()) ? in.status() : "todo";
    i.priority = in.priority() != null && PRIORITIES.contains(in.priority()) ? in.priority() : "none";
    i.labelsJson = JsonCols.write(in.labels() == null ? List.of() : in.labels());
    i.createdBy = actorId;
    i.createdAt = i.updatedAt = Ids.now();
    issues.save(i);
    changed(i);
    publisher.publishEvent(new IssueEvent(IssueEvent.CREATED, i, null, actorType, actorId, null, null));
    if (i.assigneeId != null) {
      publisher.publishEvent(new IssueEvent(IssueEvent.ASSIGNED, i, null, actorType, actorId, agentAssignee(i), null));
    }
    return i;
  }

  public record IssuePatch(String title, String description, String status, String priority, String assigneeType,
      String assigneeId, List<String> labels, Boolean clearAssignee) {}

  // a validation error (unknown issue, status or project) must not mark a workflow's surrounding transaction
  // rollback-only: the engine records the failed node, and its run must still be saved
  @Transactional(noRollbackFor = ApiException.class)
  public Issue update(String id, IssuePatch patch, String actorType, String actorId) {
    Issue i = get(id);
    // every check runs before anything changes: with noRollbackFor, a 400 must leave nothing half-applied
    if (patch.status() != null && !STATUSES.contains(patch.status())) {
      throw ApiException.bad("unknown status " + patch.status());
    }
    Issue assignee = new Issue();
    if (!Boolean.TRUE.equals(patch.clearAssignee()) && patch.assigneeId() != null) {
      setAssignee(assignee, patch.assigneeType(), patch.assigneeId());
    }
    String oldStatus = i.status;
    String oldAssignee = i.assigneeType + ":" + i.assigneeId;
    if (patch.title() != null && !patch.title().isBlank()) {
      i.title = patch.title().trim();
    }
    if (patch.description() != null) {
      i.description = patch.description();
    }
    if (patch.status() != null) {
      i.status = patch.status();
    }
    if (patch.priority() != null && PRIORITIES.contains(patch.priority())) {
      i.priority = patch.priority();
    }
    if (patch.labels() != null) {
      i.labelsJson = JsonCols.write(patch.labels());
    }
    if (Boolean.TRUE.equals(patch.clearAssignee())) {
      i.assigneeType = null;
      i.assigneeId = null;
    } else if (assignee.assigneeId != null) {
      i.assigneeType = assignee.assigneeType;
      i.assigneeId = assignee.assigneeId;
    }
    i.updatedAt = Ids.now();
    issues.save(i);
    changed(i);
    if (!oldStatus.equals(i.status)) {
      publisher.publishEvent(new IssueEvent(IssueEvent.STATUS_CHANGED, i, null, actorType, actorId, null, oldStatus));
    }
    if (i.assigneeId != null && !oldAssignee.equals(i.assigneeType + ":" + i.assigneeId)) {
      publisher.publishEvent(new IssueEvent(IssueEvent.ASSIGNED, i, null, actorType, actorId, agentAssignee(i), null));
    }
    return i;
  }

  // a validation error (unknown issue, status or project) must not mark a workflow's surrounding transaction
  // rollback-only: the engine records the failed node, and its run must still be saved
  @Transactional(noRollbackFor = ApiException.class)
  public Comment comment(String issueId, String body, String authorType, String authorId, String taskId) {
    if (body == null || body.isBlank()) {
      throw ApiException.bad("comment body is empty");
    }
    Issue i = get(issueId);
    Comment c = new Comment();
    c.id = Ids.next("cmt");
    c.issueId = i.id;
    c.authorType = authorType;
    c.authorId = authorId;
    c.body = body.length() > 100_000 ? body.substring(0, 100_000) + "\n…" : body;
    c.taskId = taskId;
    c.createdAt = Ids.now();
    comments.save(c);
    i.updatedAt = c.createdAt;
    issues.save(i);
    realtime.publish("comment.created", dev.fellowloop.server.realtime.Realtime.ids("id", c.id, "issueId", i.id));
    changed(i);
    String target = mentionedAgent(c.body).map(a -> a.id).orElse(agentAssignee(i));
    publisher.publishEvent(new IssueEvent(IssueEvent.COMMENTED, i, c, authorType, authorId, target, null));
    return c;
  }

  /** The first agent mentioned as {@code @Name} in the text. */
  public Optional<Agent> mentionedAgent(String text) {
    if (text == null) {
      return Optional.empty();
    }
    Matcher m = MENTION.matcher(text);
    while (m.find()) {
      Optional<Agent> a = agents.findByNameIgnoreCase(m.group(1));
      if (a.isPresent() && !a.get().archived) {
        return a;
      }
    }
    return Optional.empty();
  }

  private void setAssignee(Issue i, String type, String id) {
    if (id == null || id.isBlank()) {
      return;
    }
    String t = type == null ? "agent" : type.toLowerCase(Locale.ROOT);
    if ("agent".equals(t)) {
      Agent a = agents.findById(id).or(() -> agents.findByNameIgnoreCase(id))
          .orElseThrow(() -> ApiException.notFound("agent " + id));
      i.assigneeType = "agent";
      i.assigneeId = a.id;
    } else {
      i.assigneeType = "member";
      i.assigneeId = id;
    }
  }

  private static String agentAssignee(Issue i) {
    return "agent".equals(i.assigneeType) ? i.assigneeId : null;
  }

  public Issue get(String idOrKey) {
    return issues.findById(idOrKey).or(() -> issues.findByIssueKeyIgnoreCase(idOrKey))
        .orElseThrow(() -> ApiException.notFound("issue " + idOrKey));
  }

  public List<Issue> list(String projectId) {
    return projectId == null ? issues.findAllByOrderByUpdatedAtDesc() : issues.findByProjectIdOrderByUpdatedAtDesc(projectId);
  }

  public List<Comment> comments(String issueId) {
    return comments.findByIssueIdOrderByCreatedAtAsc(get(issueId).id);
  }

  public Project defaultProject() {
    List<Project> all = projects.findAllByOrderByCreatedAtAsc();
    if (all.isEmpty()) {
      throw new IllegalStateException("no project exists");
    }
    return all.get(0);
  }

  /**
   * Changes a project's name, description, repository or default branch; the key stays (issue keys use
   * it). A null argument keeps the current value, a blank repository URL removes the repository.
   */
  @Transactional
  public Project updateProject(String id, String name, String description, String repoUrl, String branch) {
    Project p = projects.findById(id).orElseThrow(() -> ApiException.notFound("project"));
    if (name != null && !name.isBlank()) {
      p.name = name.trim();
    }
    if (description != null) {
      p.description = description;
    }
    if (repoUrl != null) {
      p.repoUrl = repoUrl.isBlank() ? null : checkRepoUrl(repoUrl);
    }
    if (branch != null) {
      p.defaultBranch = branch.isBlank() ? null : checkBranch(branch);
    }
    projects.save(p);
    realtime.publish("project.updated", Realtime.ids("id", p.id));
    return p;
  }

  /**
   * A clone URL the daemon's git understands: {@code https://}, {@code ssh://}, {@code git://},
   * {@code git@host:org/repo}, or an absolute path on the runtime machines. No spaces or option-like
   * values ({@code -u...}), which git would read as flags.
   */
  static String checkRepoUrl(String url) {
    String u = url.trim();
    boolean ok = u.matches("(?i)(https?|ssh|git)://[^\\s]+") || u.matches("[\\w.-]+@[\\w.-]+:[^\\s]+")
        || u.matches("/[^\\s]*") || u.matches("(?i)file:///[^\\s]*");
    if (!ok || u.startsWith("-")) {
      throw ApiException.bad("the repository must be an https://, ssh:// or git@host:org/repo URL, or an absolute path");
    }
    return u;
  }

  static String checkBranch(String branch) {
    String b = branch.trim();
    if (!b.matches("[A-Za-z0-9._/-]{1,200}") || b.startsWith("-") || b.contains("..")) {
      throw ApiException.bad("not a valid branch name: " + b);
    }
    return b;
  }

  @Transactional
  public Project createProject(String key, String name, String description, String repoUrl, String branch) {
    String prefix = key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
    if (!prefix.matches("[A-Z][A-Z0-9]{1,9}")) {
      throw ApiException.bad("project key must be 2-10 letters or digits, starting with a letter");
    }
    if (projects.findByKeyPrefix(prefix).isPresent()) {
      throw ApiException.bad("project key " + prefix + " is taken");
    }
    Project p = new Project();
    p.id = Ids.next("prj");
    p.keyPrefix = prefix;
    p.name = name == null || name.isBlank() ? prefix : name;
    p.description = description;
    p.repoUrl = repoUrl == null || repoUrl.isBlank() ? null : checkRepoUrl(repoUrl);
    p.defaultBranch = branch == null || branch.isBlank() ? null : checkBranch(branch);
    p.createdAt = Ids.now();
    projects.save(p);
    realtime.publish("project.updated", Realtime.ids("id", p.id));
    return p;
  }

  public List<Project> projects() {
    return new ArrayList<>(projects.findAllByOrderByCreatedAtAsc());
  }

  private void changed(Issue i) {
    realtime.publish("issue.updated", Realtime.ids("id", i.id, "key", i.issueKey, "status", i.status));
  }
}
