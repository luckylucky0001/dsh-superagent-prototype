package dev.orca.server.workflow;

import dev.orca.protocol.Json;
import dev.orca.server.auth.CurrentUser;
import dev.orca.server.model.Workflow;
import dev.orca.server.model.WorkflowRun;
import dev.orca.server.support.JsonCols;
import dev.orca.server.support.Views.RunView;
import dev.orca.server.support.Views.WorkflowView;
import dev.orca.workflow.engine.ValidationIssue;
import dev.orca.workflow.model.WorkflowDefinition;
import dev.orca.workflow.spi.NodeDescriptor;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class WorkflowController {
  private final WorkflowService workflows;
  private final TriggerService triggers;

  public WorkflowController(WorkflowService workflows, TriggerService triggers) {
    this.workflows = workflows;
    this.triggers = triggers;
  }

  @GetMapping("/components")
  public List<NodeDescriptor> components() {
    return workflows.components();
  }

  @GetMapping("/workflows")
  public List<WorkflowView> list() {
    return workflows.all().stream().map(WorkflowView::of).toList();
  }

  @GetMapping("/workflows/{id}")
  public WorkflowView get(@PathVariable String id) {
    return WorkflowView.of(workflows.get(id));
  }

  public record WorkflowInput(String name, String description, Boolean enabled, Object definition) {}

  @org.springframework.beans.factory.annotation.Autowired
  private dev.orca.server.audit.AuditService audit;

  @PostMapping("/workflows")
  public WorkflowView create(@RequestBody WorkflowInput in) {
    Workflow w = workflows.save(null, in.name(), in.description(), in.enabled(), in.definition(), false, CurrentUser.id());
    audit.record("workflow.create", "workflow", w.id, Map.of("name", w.name, "enabled", w.enabled));
    return WorkflowView.of(w);
  }

  @PutMapping("/workflows/{id}")
  public WorkflowView update(@PathVariable String id, @RequestBody WorkflowInput in) {
    Workflow w = workflows.get(id);
    Workflow saved = workflows.save(id, in.name() == null ? w.name : in.name(),
        in.description() == null ? w.description : in.description(), in.enabled(),
        in.definition() == null ? JsonCols.any(w.definitionJson) : in.definition(), w.builtIn, CurrentUser.id());
    audit.record("workflow.update", "workflow", id, Map.of("name", saved.name, "enabled", saved.enabled,
        "definitionChanged", in.definition() != null));
    return WorkflowView.of(saved);
  }

  public record EnableInput(boolean enabled) {}

  @PostMapping("/workflows/{id}/enabled")
  public WorkflowView enable(@PathVariable String id, @RequestBody EnableInput in) {
    Workflow w = workflows.setEnabled(id, in.enabled());
    audit.record(in.enabled() ? "workflow.enable" : "workflow.disable", "workflow", id, Map.of("name", w.name));
    return WorkflowView.of(w);
  }

  @DeleteMapping("/workflows/{id}")
  public Map<String, Object> delete(@PathVariable String id) {
    String name = workflows.get(id).name;
    workflows.delete(id);
    audit.record("workflow.delete", "workflow", id, Map.of("name", name));
    return Map.of("ok", true);
  }

  @PostMapping("/workflows/validate")
  public List<ValidationIssue> validate(@RequestBody Object definition) {
    return workflows.validate(Json.convert(definition, WorkflowDefinition.class));
  }

  @GetMapping("/workflows/{id}/versions")
  public List<Map<String, Object>> versions(@PathVariable String id) {
    return workflows.versions(id).stream().map(v -> {
      Map<String, Object> m = new java.util.LinkedHashMap<>();
      m.put("version", v.version);
      m.put("createdAt", v.createdAt);
      m.put("createdBy", v.createdBy);
      return m;
    }).toList();
  }

  @GetMapping("/workflows/{id}/versions/{version}")
  public Map<String, Object> version(@PathVariable String id, @PathVariable int version) {
    return workflows.versions(id).stream().filter(v -> v.version == version).findFirst()
        .map(v -> Map.<String, Object>of("version", v.version, "createdAt", v.createdAt,
            "definition", JsonCols.any(v.definitionJson)))
        .orElseThrow(() -> dev.orca.server.support.ApiException.notFound("version"));
  }

  public record RunInput(String nodeId, Map<String, Object> input) {}

  @PostMapping("/workflows/{id}/runs")
  public RunView run(@PathVariable String id, @RequestBody(required = false) RunInput in) {
    WorkflowRun r = triggers.manual(id, in == null ? null : in.nodeId(), in == null ? null : in.input(), CurrentUser.id());
    audit.record("workflow.run", "run", r.id, Map.of("workflow", id));
    return view(r, true);
  }

  @GetMapping("/runs")
  public List<RunView> runs(@RequestParam(required = false) String workflowId) {
    return workflows.recentRuns(workflowId).stream().map(r -> view(r, false)).toList();
  }

  @GetMapping("/runs/{id}")
  public RunView runDetail(@PathVariable String id) {
    return view(workflows.run(id), true);
  }

  @PostMapping("/runs/{id}/cancel")
  public RunView cancel(@PathVariable String id) {
    RunView v = view(workflows.cancel(id, "cancelled by a person"), true);
    audit.record("run.cancel", "run", id, Map.of());
    return v;
  }

  public record RetryInput(String nodeId) {}

  @PostMapping("/runs/{id}/retry")
  public RunView retry(@PathVariable String id, @RequestBody RetryInput in) {
    return view(workflows.retry(id, in.nodeId()), true);
  }

  @GetMapping("/cron/preview")
  public List<Long> cron(@RequestParam String expr, @RequestParam(required = false) String timezone) {
    return triggers.preview(expr, timezone, 5);
  }

  /** Webhook trigger; authenticated by the node's secret, given as {@code ?secret=} or {@code X-Orca-Secret}. */
  @PostMapping("/hooks/{workflowId}/{nodeId}")
  public Map<String, Object> hook(@PathVariable String workflowId, @PathVariable String nodeId,
      @RequestParam(required = false) String secret, @RequestHeader(value = "X-Orca-Secret", required = false) String header,
      @RequestParam Map<String, String> query, @RequestBody(required = false) Map<String, Object> body) {
    Map<String, String> q = new java.util.LinkedHashMap<>(query);
    q.remove("secret");
    WorkflowRun r = triggers.webhook(workflowId, nodeId, secret != null ? secret : header, body, q);
    return Map.of("runId", r.id, "status", r.status);
  }

  private RunView view(WorkflowRun r, boolean full) {
    return RunView.of(r, workflows.name(r.workflowId), full ? JsonCols.any(Json.write(workflows.definition(r))) : null, full);
  }
}
