package dev.orca.server.issue;

import dev.orca.server.auth.CurrentUser;
import dev.orca.server.support.Views;
import dev.orca.server.support.Views.CommentView;
import dev.orca.server.support.Views.IssueView;
import dev.orca.server.support.Views.ProjectView;
import dev.orca.server.task.TaskService;
import dev.orca.server.workflow.WorkflowService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class IssueController {

  private final IssueService issues;
  private final TaskService tasks;
  private final WorkflowService workflows;

  public IssueController(IssueService issues, TaskService tasks, WorkflowService workflows) {
    this.issues = issues;
    this.tasks = tasks;
    this.workflows = workflows;
  }

  @GetMapping("/projects")
  public List<ProjectView> projects() {
    return issues.projects().stream().map(ProjectView::of).toList();
  }

  public record ProjectInput(String key, String name, String description, String repoUrl, String defaultBranch) {}

  @PostMapping("/projects")
  public ProjectView createProject(@RequestBody ProjectInput in) {
    return ProjectView.of(issues.createProject(in.key(), in.name(), in.description(), in.repoUrl(), in.defaultBranch()));
  }

  @GetMapping("/issues")
  public List<IssueView> list(@RequestParam(required = false) String projectId) {
    return issues.list(projectId).stream().map(IssueView::of).toList();
  }

  @PostMapping("/issues")
  public IssueView create(@RequestBody IssueService.IssueInput in) {
    return IssueView.of(issues.create(in, "member", CurrentUser.id()));
  }

  @GetMapping("/issues/{id}")
  public IssueView get(@PathVariable String id) {
    return IssueView.of(issues.get(id));
  }

  @PatchMapping("/issues/{id}")
  public IssueView update(@PathVariable String id, @RequestBody IssueService.IssuePatch patch) {
    return IssueView.of(issues.update(id, patch, "member", CurrentUser.id()));
  }

  @GetMapping("/issues/{id}/comments")
  public List<CommentView> comments(@PathVariable String id) {
    return issues.comments(id).stream().map(CommentView::of).toList();
  }

  public record CommentInput(String body) {}

  @PostMapping("/issues/{id}/comments")
  public CommentView comment(@PathVariable String id, @RequestBody CommentInput in) {
    return CommentView.of(issues.comment(id, in.body(), "member", CurrentUser.id(), null));
  }

  @GetMapping("/issues/{id}/tasks")
  public List<Views.TaskView> tasks(@PathVariable String id) {
    return tasks.forIssue(issues.get(id).id).stream().map(Views.TaskView::of).toList();
  }

  @GetMapping("/issues/{id}/runs")
  public List<Views.RunView> runs(@PathVariable String id) {
    return workflows.runsForIssue(issues.get(id).id).stream()
        .map(r -> Views.RunView.of(r, workflows.name(r.workflowId), null, false)).toList();
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private org.springframework.boot.info.BuildProperties build;

  /** Unauthenticated liveness check; also tells daemons and the CLI which server version they talk to. */
  @GetMapping("/health")
  public Map<String, Object> health() {
    return Map.of("ok", true, "version", build == null ? "dev" : build.getVersion());
  }
}
