package dev.orca.server.workflow;

import dev.orca.protocol.Json;
import dev.orca.server.issue.IssueEvent;
import dev.orca.server.model.Agent;
import dev.orca.server.model.ScheduleState;
import dev.orca.server.model.Workflow;
import dev.orca.server.repo.AgentRepo;
import dev.orca.server.repo.ScheduleRepo;
import dev.orca.server.support.ApiException;
import dev.orca.server.support.Ids;
import dev.orca.server.support.OrcaProperties;
import dev.orca.server.support.Views;
import dev.orca.workflow.expr.Expression;
import dev.orca.workflow.expr.ExpressionException;
import dev.orca.workflow.expr.Values;
import dev.orca.workflow.model.NodeDef;
import dev.orca.workflow.model.WorkflowDefinition;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Starts workflow runs from issue events, schedules, webhooks and manual requests. */
@Service
public class TriggerService {

  private static final Logger log = LoggerFactory.getLogger(TriggerService.class);

  private final WorkflowService workflows;
  private final AgentRepo agents;
  private final ScheduleRepo schedules;
  private final OrcaProperties props;

  public TriggerService(WorkflowService workflows, AgentRepo agents, ScheduleRepo schedules, OrcaProperties props) {
    this.workflows = workflows;
    this.agents = agents;
    this.schedules = schedules;
    this.props = props;
  }

  // ---------------------------------------------------------------- issue events

  @EventListener
  public void onIssueEvent(IssueEvent e) {
    Map<String, Object> payload = payload(e);
    for (Workflow w : workflows.enabled()) {
      WorkflowDefinition def = WorkflowService.parse(w.definitionJson);
      for (NodeDef n : def.nodes()) {
        if (!"trigger.issue".equals(n.type()) || !matches(n.config(), e, payload)) {
          continue;
        }
        try {
          workflows.start(w, n.id(), payload, e.issue().id);
        } catch (RuntimeException ex) {
          log.error("could not start workflow {} for {}", w.name, e.issue().issueKey, ex);
        }
      }
    }
  }

  private Map<String, Object> payload(IssueEvent e) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("event", e.type());
    p.put("issue", Json.toMap(Views.IssueView.of(e.issue())));
    if (e.comment() != null) {
      p.put("comment", Json.toMap(Views.CommentView.of(e.comment())));
    }
    p.put("actor", Map.of("type", String.valueOf(e.actorType()), "id", String.valueOf(e.actorId())));
    if (e.targetAgentId() != null) {
      p.put("agentId", e.targetAgentId());
      agents.findById(e.targetAgentId()).ifPresent(a -> p.put("agent", Map.of("id", a.id, "name", a.name,
          "provider", a.provider)));
    }
    if (e.previousStatus() != null) {
      p.put("previousStatus", e.previousStatus());
    }
    return p;
  }

  boolean matches(Map<String, Object> cfg, IssueEvent e, Map<String, Object> payload) {
    String event = str(cfg.get("event"), "assigned");
    if (!event.equals(e.type())) {
      return false;
    }
    boolean automation = !"member".equals(e.actorType());
    if (automation && !Values.truthy(cfg.get("includeAutomation"))) {
      return false;
    }
    String target = str(cfg.get("target"), "agent");
    if ("agent".equals(target) && e.targetAgentId() == null
        && (IssueEvent.ASSIGNED.equals(e.type()) || IssueEvent.COMMENTED.equals(e.type()))) {
      return false;
    }
    String agent = str(cfg.get("agent"), "");
    if (!agent.isBlank() && !agent.contains("{{")) {
      Agent a = agents.findById(agent).or(() -> agents.findByNameIgnoreCase(agent)).orElse(null);
      if (a == null || !Objects.equals(a.id, e.targetAgentId())) {
        return false;
      }
    }
    String label = str(cfg.get("label"), "");
    if (!label.isBlank() && !Values.stringify(Json.get(payload, "issue.labels")).contains(label)) {
      return false;
    }
    String toStatus = str(cfg.get("toStatus"), "");
    if (IssueEvent.STATUS_CHANGED.equals(e.type()) && !toStatus.isBlank() && !toStatus.equals(e.issue().status)) {
      return false;
    }
    String condition = str(cfg.get("condition"), "");
    if (!condition.isBlank()) {
      try {
        return Expression.parse(condition).test(Map.of("trigger", payload));
      } catch (ExpressionException ex) {
        log.warn("trigger condition failed: {}", ex.getMessage());
        return false;
      }
    }
    return true;
  }

  // ---------------------------------------------------------------- manual and webhook

  @Transactional
  public dev.orca.server.model.WorkflowRun manual(String workflowId, String nodeId, Map<String, Object> input,
      String memberId) {
    Workflow w = workflows.get(workflowId);
    WorkflowDefinition def = WorkflowService.parse(w.definitionJson);
    NodeDef node = nodeId != null ? def.requireNode(nodeId)
        : def.nodes().stream().filter(n -> n.type().equals("trigger.manual")).findFirst()
            .orElseThrow(() -> ApiException.bad("this workflow has no manual trigger"));
    if (!node.type().equals("trigger.manual")) {
      throw ApiException.bad(node.id() + " is not a manual trigger");
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("event", "manual");
    payload.put("input", input == null ? Map.of() : input);
    payload.put("actor", Map.of("type", "member", "id", String.valueOf(memberId)));
    Object issueId = input == null ? null : input.get("issueId");
    return workflows.start(w, node.id(), payload, issueId == null ? null : String.valueOf(issueId));
  }

  @Transactional
  public dev.orca.server.model.WorkflowRun webhook(String workflowId, String nodeId, String secret,
      Map<String, Object> body, Map<String, String> query) {
    Workflow w = workflows.get(workflowId);
    if (!w.enabled) {
      throw new ApiException(HttpStatus.CONFLICT, "workflow is disabled");
    }
    NodeDef node = WorkflowService.parse(w.definitionJson).requireNode(nodeId);
    if (!"trigger.webhook".equals(node.type())) {
      throw ApiException.notFound("webhook");
    }
    String expected = str(node.config().get("secret"), "");
    if (expected.isBlank() || !java.security.MessageDigest.isEqual(expected.getBytes(), String.valueOf(secret).getBytes())) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "bad webhook secret");
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("event", "webhook");
    payload.put("body", body == null ? Map.of() : body);
    payload.put("query", query == null ? Map.of() : query);
    return workflows.start(w, node.id(), payload, null);
  }

  // ---------------------------------------------------------------- schedules

  @Scheduled(fixedDelay = 30_000, initialDelay = 20_000)
  @Transactional
  public void tick() {
    long now = Ids.now();
    for (Workflow w : workflows.enabled()) {
      WorkflowDefinition def = WorkflowService.parse(w.definitionJson);
      for (NodeDef n : def.nodes()) {
        if (!"trigger.schedule".equals(n.type())) {
          continue;
        }
        String id = w.id + ":" + n.id();
        CronExpression cron;
        ZoneId zone;
        try {
          cron = cron(str(n.config().get("cron"), ""));
          zone = ZoneId.of(str(n.config().get("timezone"), props.defaultTimezone()));
        } catch (RuntimeException ex) {
          continue;
        }
        ScheduleState st = schedules.findById(id).orElse(null);
        if (st == null) {
          st = new ScheduleState();
          st.id = id;
          st.workflowId = w.id;
          st.nodeId = n.id();
          st.nextFireAt = next(cron, zone, now);
          schedules.save(st);
          continue;
        }
        if (st.nextFireAt <= now) {
          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("event", "schedule");
          payload.put("firedAt", now);
          Object extra = n.config().get("payload");
          if (extra instanceof Map<?, ?> m) {
            payload.put("payload", m);
          }
          try {
            workflows.start(w, n.id(), payload, null);
          } catch (RuntimeException ex) {
            log.error("scheduled run of {} failed to start", w.name, ex);
          }
          st.nextFireAt = next(cron, zone, now);
          schedules.save(st);
        }
      }
    }
  }

  /** Accepts 5-field (minute-first) or 6-field (second-first) cron expressions. */
  public static CronExpression cron(String expr) {
    String e = expr.trim();
    if (e.split("\\s+").length == 5) {
      e = "0 " + e;
    }
    return CronExpression.parse(e);
  }

  public static long next(CronExpression cron, ZoneId zone, long after) {
    ZonedDateTime t = cron.next(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(after), zone));
    return t == null ? Long.MAX_VALUE : t.toInstant().toEpochMilli();
  }

  public List<Long> preview(String expr, String timezone, int count) {
    CronExpression c = cron(expr);
    ZoneId zone = ZoneId.of(timezone == null || timezone.isBlank() ? props.defaultTimezone() : timezone);
    long t = Ids.now();
    List<Long> out = new java.util.ArrayList<>();
    for (int i = 0; i < count; i++) {
      t = next(c, zone, t);
      if (t == Long.MAX_VALUE) {
        break;
      }
      out.add(t);
    }
    return out;
  }

  private static String str(Object v, String fallback) {
    return v == null || String.valueOf(v).isBlank() ? fallback : String.valueOf(v);
  }
}
