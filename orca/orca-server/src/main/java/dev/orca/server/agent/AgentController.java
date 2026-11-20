package dev.orca.server.agent;

import dev.orca.server.model.Agent;
import dev.orca.server.realtime.Realtime;
import dev.orca.server.repo.AgentRepo;
import dev.orca.server.support.ApiException;
import dev.orca.server.support.Ids;
import dev.orca.server.support.JsonCols;
import dev.orca.server.support.Views;
import dev.orca.server.support.Views.AgentView;
import dev.orca.server.task.TaskService;
import java.util.List;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

  static final Set<String> PROVIDERS = Set.of("codex", "trae");
  static final Set<String> PERMISSIONS = Set.of("read_only", "workspace", "full");

  private final AgentRepo agents;
  private final TaskService tasks;
  private final Realtime realtime;

  public AgentController(AgentRepo agents, TaskService tasks, Realtime realtime) {
    this.agents = agents;
    this.tasks = tasks;
    this.realtime = realtime;
  }

  public record AgentInput(String name, String description, String provider, String runtimeId, String model,
      String effort, String instructions, String permission, List<String> allowedCommands, List<String> extraArgs,
      List<String> skills, Integer maxConcurrency, Integer timeoutMinutes, Boolean archived, Boolean escalateApprovals, Boolean networkAccess) {}

  @GetMapping
  public List<AgentView> list() {
    return agents.findByArchivedFalseOrderByNameAsc().stream().map(AgentView::of).toList();
  }

  @GetMapping("/{id}")
  public AgentView get(@PathVariable String id) {
    return AgentView.of(find(id));
  }

  @PostMapping
  @Transactional
  public AgentView create(@RequestBody AgentInput in) {
    Agent a = new Agent();
    a.id = Ids.next("agt");
    a.createdAt = Ids.now();
    a.provider = "codex";
    a.permission = "workspace";
    a.maxConcurrency = 1;
    a.timeoutMinutes = 60;
    apply(a, in);
    if (a.name == null) {
      throw ApiException.bad("name is required");
    }
    agents.save(a);
    realtime.publish("agent.updated", Realtime.ids("id", a.id));
    return AgentView.of(a);
  }

  @PatchMapping("/{id}")
  @Transactional
  public AgentView update(@PathVariable String id, @RequestBody AgentInput in) {
    Agent a = find(id);
    apply(a, in);
    agents.save(a);
    realtime.publish("agent.updated", Realtime.ids("id", a.id));
    return AgentView.of(a);
  }

  @DeleteMapping("/{id}")
  @Transactional
  public AgentView archive(@PathVariable String id) {
    Agent a = find(id);
    a.archived = true;
    a.name = a.name + "-archived-" + Ids.random(4);
    a.updatedAt = Ids.now();
    agents.save(a);
    realtime.publish("agent.updated", Realtime.ids("id", a.id));
    return AgentView.of(a);
  }

  /** {@code followUp}: a finished Ask task of this agent whose session the question continues. */
  public record AskInput(String prompt, String followUp) {}

  /** A one-off task for the agent, without an issue; the transcript is at /tasks/{id}. */
  @PostMapping("/{id}/ask")
  public Views.TaskView ask(@PathVariable String id, @RequestBody AskInput in) {
    if (in.prompt() == null || in.prompt().isBlank()) {
      throw ApiException.bad("prompt is empty");
    }
    Agent a = find(id);
    if (in.followUp() != null && !in.followUp().isBlank()) {
      return Views.TaskView.of(tasks.followUp(a, in.followUp(), in.prompt(), dev.orca.server.auth.CurrentUser.id()));
    }
    return Views.TaskView.of(tasks.enqueue(new dev.orca.server.task.TaskRequest(a.id, null, in.prompt(), "ask", null, "new",
        java.util.Map.of("source", "ask", "by", dev.orca.server.auth.CurrentUser.id()), null)));
  }

  @GetMapping("/{id}/tasks")
  public List<Views.TaskView> tasks(@PathVariable String id) {
    return tasks.forAgent(id).stream().limit(100).map(Views.TaskView::of).toList();
  }

  private void apply(Agent a, AgentInput in) {
    if (in.name() != null) {
      String n = in.name().trim();
      if (!n.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
        throw ApiException.bad("agent names use letters, digits, '-' and '_' so they can be @mentioned");
      }
      agents.findByNameIgnoreCase(n).filter(o -> !o.id.equals(a.id)).ifPresent(o -> {
        throw ApiException.bad("an agent named " + n + " already exists");
      });
      a.name = n;
    }
    if (in.description() != null) {
      a.description = in.description();
    }
    if (in.provider() != null) {
      if (!PROVIDERS.contains(in.provider())) {
        throw ApiException.bad("provider must be codex or trae");
      }
      a.provider = in.provider();
    }
    if (in.runtimeId() != null) {
      a.runtimeId = in.runtimeId().isBlank() ? null : in.runtimeId();
    }
    if (in.model() != null) {
      a.model = in.model().isBlank() ? null : in.model();
    }
    if (in.effort() != null) {
      a.effort = in.effort().isBlank() ? null : in.effort();
    }
    if (in.instructions() != null) {
      a.instructions = in.instructions();
    }
    if (in.permission() != null) {
      if (!PERMISSIONS.contains(in.permission())) {
        throw ApiException.bad("permission must be read_only, workspace or full");
      }
      a.permission = in.permission();
    }
    if (in.allowedCommands() != null) {
      a.allowedCommandsJson = JsonCols.write(in.allowedCommands());
    }
    if (in.extraArgs() != null) {
      a.extraArgsJson = JsonCols.write(in.extraArgs());
    }
    if (in.skills() != null) {
      a.skillsJson = JsonCols.write(in.skills());
    }
    if (in.maxConcurrency() != null) {
      a.maxConcurrency = Math.max(1, Math.min(16, in.maxConcurrency()));
    }
    if (in.timeoutMinutes() != null) {
      a.timeoutMinutes = Math.max(1, Math.min(24 * 60, in.timeoutMinutes()));
    }
    if (in.archived() != null) {
      a.archived = in.archived();
    }
    if (in.escalateApprovals() != null) {
      a.escalateApprovals = in.escalateApprovals();
    }
    if (in.networkAccess() != null) {
      a.networkAccess = in.networkAccess();
    }
    a.updatedAt = Ids.now();
  }

  private Agent find(String id) {
    return agents.findById(id).or(() -> agents.findByNameIgnoreCase(id)).orElseThrow(() -> ApiException.notFound("agent"));
  }
}
