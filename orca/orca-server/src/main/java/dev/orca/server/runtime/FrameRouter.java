package dev.orca.server.runtime;

import dev.orca.protocol.Json;
import dev.orca.protocol.Wire;
import dev.orca.server.support.OrcaProperties;
import dev.orca.server.task.TaskService;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.stereotype.Component;

/** Wires daemon frames to the services that handle them. */
@Component
public class FrameRouter {
  private final DaemonGateway gateway;
  private final RuntimeService runtimes;
  private final TaskService tasks;
  private final OrcaProperties props;

  public FrameRouter(DaemonGateway gateway, RuntimeService runtimes, TaskService tasks, OrcaProperties props) {
    this.gateway = gateway;
    this.runtimes = runtimes;
    this.tasks = tasks;
    this.props = props;
  }

  @PostConstruct
  void wire() {
    gateway.onFrame(this::route);
    gateway.onDisconnect(runtimes::offline);
  }

  void route(String runtimeId, Wire.Frame f) {
    // a frame racing with dispatch or lease expiry on the same task is simply applied again
    for (int attempt = 1; ; attempt++) {
      try {
        apply(runtimeId, f);
        return;
      } catch (org.springframework.dao.OptimisticLockingFailureException e) {
        if (attempt >= 3) {
          throw e;
        }
      }
    }
  }

  private void apply(String runtimeId, Wire.Frame f) {
    switch (f.type()) {
      case Wire.HELLO -> {
        Wire.Hello h = Json.convert(f.data(), Wire.Hello.class);
        runtimes.hello(runtimeId, h);
        List<String> lost = tasks.reconcile(runtimeId, h.running());
        gateway.send(runtimeId, Wire.frame(Wire.WELCOME, new Wire.Welcome(runtimeId, props.heartbeatSeconds(), lost)));
        for (String orphan : tasks.orphans(runtimeId, h.running())) {
          gateway.send(runtimeId, Wire.frame(Wire.TASK_CANCEL, new Wire.TaskCancel(orphan, "the task already ended")));
        }
      }
      case Wire.HEARTBEAT -> {
        runtimes.seen(runtimeId);
        tasks.heartbeat(runtimeId, Json.convert(f.data(), Wire.Heartbeat.class));
      }
      case Wire.PROBE_RESULT -> runtimes.probed(runtimeId, Json.convert(f.data(), Wire.ProbeResult.class).providers());
      case Wire.TASK_ACCEPTED -> tasks.accepted(runtimeId, Json.convert(f.data(), Wire.TaskAccepted.class));
      case Wire.TASK_STARTED -> tasks.started(runtimeId, Json.convert(f.data(), Wire.TaskStarted.class));
      case Wire.TASK_EVENTS -> tasks.events(runtimeId, Json.convert(f.data(), Wire.TaskEvents.class));
      case Wire.TASK_COMPLETED -> tasks.completed(runtimeId, Json.convert(f.data(), Wire.TaskCompleted.class));
      default -> { }
    }
  }
}
