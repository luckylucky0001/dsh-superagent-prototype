package dev.orca.server.task;

import dev.orca.server.support.Views;
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

  @PostMapping("/{id}/cancel")
  public Views.TaskView cancel(@PathVariable String id) {
    return Views.TaskView.of(tasks.cancel(id, "cancelled by a person"));
  }

  public record SteerInput(String text) {}

  @PostMapping("/{id}/steer")
  public Map<String, Object> steer(@PathVariable String id, @RequestBody SteerInput in) {
    tasks.steer(id, in.text());
    return Map.of("ok", true);
  }
}
