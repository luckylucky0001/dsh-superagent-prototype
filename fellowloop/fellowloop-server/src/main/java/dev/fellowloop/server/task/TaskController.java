package dev.fellowloop.server.task;

import dev.fellowloop.server.support.Views;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
  private final TaskService tasks;

  public TaskController(TaskService tasks) {
    this.tasks = tasks;
  }

  @GetMapping
  public List<Views.TaskView> recent() {
    return tasks.recent().stream().map(Views.TaskView::of).toList();
  }

  @GetMapping("/{id}")
  public Views.TaskView get(@PathVariable String id) {
    return Views.TaskView.of(tasks.get(id));
  }

  @GetMapping("/{id}/events")
  public List<Views.EventView> events(@PathVariable String id, @RequestParam(defaultValue = "0") long after) {
    return tasks.events(id, after).stream().map(Views.EventView::of).toList();
  }

  /**
   * An Ask is one person's conversation with an agent: only they (or an owner/admin) steer or cancel it. Tasks
   * of issues and workflows belong to the team, as the issue does.
   */
  private void checkAsker(String id) {
    dev.fellowloop.server.model.Task t = tasks.get(id);
    if (!"ask".equals(t.kind) || dev.fellowloop.server.auth.Roles.isAdmin()) {
      return;
    }
    Object by = t.originJson == null ? null : dev.fellowloop.server.support.JsonCols.map(t.originJson).get("by");
    if (by != null && !by.equals(dev.fellowloop.server.auth.CurrentUser.id())) {
      throw new dev.fellowloop.server.support.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
          "only the person who asked can steer or cancel this Ask");
    }
  }

  @PostMapping("/{id}/cancel")
  public Views.TaskView cancel(@PathVariable String id) {
    checkAsker(id);
    return Views.TaskView.of(dev.fellowloop.server.support.Retry.onConflict(() -> tasks.cancel(id, "cancelled by a person")));
  }

  public record SteerInput(String text) {}

  @PostMapping("/{id}/steer")
  public Map<String, Object> steer(@PathVariable String id, @RequestBody SteerInput in) {
    checkAsker(id);
    tasks.steer(id, in.text());
    return Map.of("ok", true);
  }
}
