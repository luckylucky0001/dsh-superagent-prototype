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

  @org.springframework.beans.factory.annotation.Autowired
  private dev.orca.server.audit.AuditService audit;

  /** The settings that decide what an agent may do: these are audited when they change. */
  private static java.util.Map<String, Object> powers(Agent a) {
    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("provider", a.provider);
    m.put("runtime", a.runtimeId);
    m.put("model", a.model);
    m.put("permission", a.permission);
    m.put("allowedCommands", a.allowedCommandsJson);
    m.put("extraArgs", a.extraArgsJson);
    m.put("skills", a.skillsJson);
    m.put("escalateApprovals", a.escalateApprovals);
    m.put("networkAccess", a.networkAccess);
    m.put("instructions", sha256(a.instructions));
    return m;
  }

  /**
   * What an approved command can do outside the sandbox is set by these fields; only owners and admins change
   * them (an allow-listed {@code python3} is code execution on the runtime machine).
   */
  private static void checkPowers(Agent a, java.util.Map<String, Object> before) {
    if (dev.orca.server.auth.Roles.isAdmin()) {
      return;
    }
    java.util.Map<String, Object> after = powers(a);
    for (String k : java.util.List.of("permission", "allowedCommands", "extraArgs", "networkAccess")) {
      Object was = before == null ? null : before.get(k);
      Object now = after.get(k);
      boolean widened = before == null
          ? "permission".equals(k) ? "full".equals(now) : "networkAccess".equals(k) ? Boolean.TRUE.equals(now)
              : now != null && !now.equals("[]")
          : !java.util.Objects.equals(was, now);
      if (widened) {
        throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
            "only owners and admins can change an agent's permission tier, allowed commands, extra arguments or network access");
      }
    }
  }

  private static String sha256(String s) {
    if (s == null) {
      return "";
    }
    try {
      return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

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
    checkPowers(a, null);
    if (a.name == null) {
      throw ApiException.bad("name is required");
    }
    agents.save(a);
    java.util.Map<String, Object> created = powers(a);
    created.remove("instructions");
    created.put("name", a.name);
    audit.record("agent.create", "agent", a.id, created);
    realtime.publish("agent.updated", Realtime.ids("id", a.id));
    return AgentView.of(a);
  }

  @PatchMapping("/{id}")
  @Transactional
  public AgentView update(@PathVariable String id, @RequestBody AgentInput in) {
    Agent a = find(id);
    java.util.Map<String, Object> before = powers(a);
    apply(a, in);
    checkPowers(a, before);
    java.util.Map<String, Object> after = powers(a);
    java.util.Map<String, Object> changes = new java.util.LinkedHashMap<>();
    for (String k : after.keySet()) {
      if (!java.util.Objects.equals(before.get(k), after.get(k))) {
        // instructions are not copied into the log, only the fact that they changed
        changes.put(k, "instructions".equals(k) ? "changed" : java.util.Map.of("from", String.valueOf(before.get(k)),
            "to", String.valueOf(after.get(k))));
      }
    }
    if (!changes.isEmpty()) {
      changes.put("name", a.name);
      audit.record("agent.update", "agent", a.id, changes);
    }
    agents.save(a);
    realtime.publish("agent.updated", Realtime.ids("id", a.id));
    return AgentView.of(a);
  }

  @DeleteMapping("/{id}")
  @Transactional
  public AgentView archive(@PathVariable String id) {
    Agent a = find(id);
    a.archived = true;
    audit.record("agent.archive", "agent", a.id, java.util.Map.of("name", a.name));
    a.name = a.name + "-archived-" + Ids.random(4);
    a.updatedAt = Ids.now();
    agents.save(a);
    realtime.publish("agent.updated", Realtime.ids("id", a.id));
    return AgentView.of(a);
  }

  /**
   * {@code followUp}: a finished Ask task of this agent whose session the question continues (it keeps that
   * task's folder). {@code project}: a project id or key; the agent then works in a fresh checkout of the
   * project's repository instead of an empty scratch folder.
   */
  public record AskInput(String prompt, String followUp, String project) {}

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
        java.util.Map.of("source", "ask", "by", dev.orca.server.auth.CurrentUser.id()), null, null, in.project())));
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
