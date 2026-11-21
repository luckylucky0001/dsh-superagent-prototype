package dev.fellowloop.server.task;

import dev.fellowloop.server.model.Agent;
import dev.fellowloop.server.model.Task;
import dev.fellowloop.server.repo.AgentRepo;
import dev.fellowloop.server.repo.TaskRepo;
import dev.fellowloop.server.support.Ids;
import dev.fellowloop.server.support.JsonCols;
import dev.fellowloop.server.support.FellowloopProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Usage per agent and per day: runs, outcomes, tokens, time. */
@RestController
public class StatsController {
  private final TaskRepo tasks;
  private final AgentRepo agents;
  private final FellowloopProperties props;

  public StatsController(TaskRepo tasks, AgentRepo agents, FellowloopProperties props) {
    this.tasks = tasks;
    this.agents = agents;
    this.props = props;
  }

  @GetMapping("/api/stats")
  public Map<String, Object> stats(@RequestParam(defaultValue = "14") int days) {
    int span = Math.max(1, Math.min(days, 90));
    long since = Ids.now() - span * 86_400_000L;
    ZoneId zone = ZoneId.of(props.defaultTimezone());
    Map<String, Map<String, Object>> byAgent = new LinkedHashMap<>();
    TreeMap<String, Map<String, Long>> byDay = new TreeMap<>();
    LocalDate today = LocalDate.now(zone);
    for (int i = span - 1; i >= 0; i--) {
      byDay.put(today.minusDays(i).toString(), new LinkedHashMap<>(Map.of("runs", 0L, "succeeded", 0L, "failed", 0L, "tokens", 0L)));
    }
    for (Task t : tasks.findByQueuedAtGreaterThan(since)) {
      Map<String, Object> usage = JsonCols.map(t.usageJson);
      long tokens = ((Number) usage.getOrDefault("taskTokens", usage.getOrDefault("totalTokens", 0))).longValue();
      boolean ok = TaskStatus.SUCCEEDED.equals(t.status);
      boolean failed = TaskStatus.FAILED.equals(t.status) || TaskStatus.TIMED_OUT.equals(t.status);
      Map<String, Object> a = byAgent.computeIfAbsent(t.agentId, id -> {
        Map<String, Object> m = new LinkedHashMap<>();
        Agent ag = agents.findById(id).orElse(null);
        m.put("agentId", id);
        m.put("name", ag == null ? id : ag.name);
        m.put("provider", ag == null ? t.provider : ag.provider);
        m.put("runs", 0L);
        m.put("succeeded", 0L);
        m.put("failed", 0L);
        m.put("tokens", 0L);
        m.put("busyMs", 0L);
        return m;
      });
      add(a, "runs", 1);
      add(a, "succeeded", ok ? 1 : 0);
      add(a, "failed", failed ? 1 : 0);
      add(a, "tokens", tokens);
      if (t.startedAt != null && t.finishedAt != null) {
        add(a, "busyMs", t.finishedAt - t.startedAt);
      }
      String day = Instant.ofEpochMilli(t.queuedAt).atZone(zone).toLocalDate().toString();
      Map<String, Long> d = byDay.get(day);
      if (d != null) {
        d.merge("runs", 1L, Long::sum);
        d.merge("succeeded", ok ? 1L : 0L, Long::sum);
        d.merge("failed", failed ? 1L : 0L, Long::sum);
        d.merge("tokens", tokens, Long::sum);
      }
    }
    List<Map<String, Object>> perDay = new ArrayList<>();
    byDay.forEach((k, v) -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("day", k);
      m.putAll(v);
      perDay.add(m);
    });
    return Map.of("days", perDay, "agents", new ArrayList<>(byAgent.values()), "timezone", zone.getId());
  }

  private static void add(Map<String, Object> m, String k, long v) {
    m.put(k, ((Number) m.get(k)).longValue() + v);
  }
}
