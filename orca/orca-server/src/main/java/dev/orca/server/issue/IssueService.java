package dev.orca.server.issue;

import dev.orca.server.model.Agent;
import dev.orca.server.model.Comment;
import dev.orca.server.model.Issue;
import dev.orca.server.model.Project;
import dev.orca.server.realtime.Realtime;
import dev.orca.server.repo.AgentRepo;
import dev.orca.server.repo.CommentRepo;
import dev.orca.server.repo.IssueRepo;
import dev.orca.server.repo.ProjectRepo;
import dev.orca.server.support.ApiException;
import dev.orca.server.support.Ids;
import dev.orca.server.support.JsonCols;
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

  @Transactional
  public Issue create(IssueInput in, String actorType, String actorId) {
    if (in.title() == null || in.title().isBlank()) {
      throw ApiException.bad("title is required");
    }
    Project p = in.projectId() == null ? defaultProject()
        : projects.findById(in.projectId()).orElseThrow(() -> ApiException.notFound("project"));
    p.nextNumber++;
    projects.save(p);
    Issue i = new Issue();
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
    setAssignee(i, in.assigneeType(), in.assigneeId());
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

  @Transactional
  public Issue update(String id, IssuePatch patch, String actorType, String actorId) {
    Issue i = get(id);
    String oldStatus = i.status;
    String oldAssignee = i.assigneeType + ":" + i.assigneeId;
    if (patch.title() != null && !patch.title().isBlank()) {
      i.title = patch.title().trim();
    }
    if (patch.description() != null) {
      i.description = patch.description();
    }
    if (patch.status() != null) {
      if (!STATUSES.contains(patch.status())) {
        throw ApiException.bad("unknown status " + patch.status());
      }
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
    } else if (patch.assigneeId() != null) {
      setAssignee(i, patch.assigneeType(), patch.assigneeId());
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

  @Transactional
  public Comment comment(String issueId, String body, String authorType, String authorId, String taskId) {
    Issue i = get(issueId);
    if (body == null || body.isBlank()) {
      throw ApiException.bad("comment body is empty");
    }
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
    realtime.publish("comment.created", dev.orca.server.realtime.Realtime.ids("id", c.id, "issueId", i.id));
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
    p.repoUrl = repoUrl;
    p.defaultBranch = branch;
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
