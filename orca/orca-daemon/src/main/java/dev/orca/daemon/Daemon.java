package dev.orca.daemon;

import dev.orca.agent.codex.CodexConfig;
import dev.orca.agent.codex.CodexProvider;
import dev.orca.agent.core.AgentProvider;
import dev.orca.protocol.Json;
import dev.orca.protocol.ProviderStatus;
import dev.orca.protocol.Wire;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Connects to the server, reports what this machine can run, and executes assigned tasks. */
public final class Daemon {

  public static final String VERSION = "0.1.0";
  private static final Logger log = LoggerFactory.getLogger(Daemon.class);

  private final DaemonConfig config;
  private final Map<String, AgentProvider> providers;
  private final Workspaces workspaces;
  private final Map<String, TaskRunner> running = new ConcurrentHashMap<>();
  private volatile List<ProviderStatus> statuses = List.of();
  private volatile int heartbeatSeconds = 15;
  private volatile String runtimeId;
  private ServerLink link;

  public Daemon(DaemonConfig config, Map<String, AgentProvider> providers) {
    this.config = config;
    this.providers = providers;
    this.workspaces = new Workspaces(config.workRootPath());
  }

  /** The providers this build supports, configured from the daemon settings. */
  public static Map<String, AgentProvider> defaultProviders(DaemonConfig c,
      Function<DaemonConfig, AgentProvider> traeFactory) {
    Map<String, AgentProvider> m = new LinkedHashMap<>();
    m.put(CodexProvider.ID, new CodexProvider(new CodexConfig(c.codexCommand(), c.env(), "orca", VERSION,
        Duration.ofSeconds(90), c.recordDir() == null ? null : Path.of(c.recordDir()))));
    if (traeFactory != null) {
      AgentProvider trae = traeFactory.apply(c);
      m.put(trae.id(), trae);
    }
    return m;
  }

  public List<ProviderStatus> probe() {
    List<ProviderStatus> out = new ArrayList<>();
    for (AgentProvider p : providers.values()) {
      ProviderStatus s = p.probe();
      out.add(s);
      log.info("provider {}: installed={} version={} loggedIn={} models={}{}", s.provider(), s.installed(), s.version(),
          s.loggedIn(), s.models().size(), s.error() == null ? "" : " (" + s.error() + ")");
    }
    statuses = out;
    return out;
  }

  public void run() {
    if (config.server() == null || config.token() == null) {
      throw new IllegalStateException("server and token are required; run `orca-daemon register` first");
    }
    probe();
    link = new ServerLink(config.server(), config.token(), this::onFrame, this::onOpen);
    Thread.ofVirtual().name("heartbeat").start(this::heartbeatLoop);
    Thread.ofVirtual().name("reprobe").start(this::probeLoop);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      running.values().forEach(r -> r.cancel("daemon shutting down"));
      link.stop();
    }));
    link.runForever();
  }

  private void onOpen() {
    List<Wire.RunningTask> tasks = new ArrayList<>();
    running.values().forEach(r -> tasks.add(new Wire.RunningTask(r.taskId(), r.lastSeq())));
    link.send(Wire.frame(Wire.HELLO, new Wire.Hello(name(), hostname(), System.getProperty("os.name") + " "
        + System.getProperty("os.arch"), VERSION, config.capacity(), config.isolated(), statuses, tasks)));
    running.values().forEach(TaskRunner::replay);
  }

  void onFrame(Wire.Frame f) {
    switch (f.type()) {
      case Wire.WELCOME -> {
        Wire.Welcome w = Json.convert(f.data(), Wire.Welcome.class);
        runtimeId = w.runtimeId();
        if (w.heartbeatSeconds() > 0) {
          heartbeatSeconds = w.heartbeatSeconds();
        }
        log.info("registered as runtime {}", runtimeId);
      }
      case Wire.TASK_ASSIGN -> assign(Json.convert(f.data(), Wire.TaskAssign.class));
      case Wire.TASK_CANCEL -> {
        Wire.TaskCancel c = Json.convert(f.data(), Wire.TaskCancel.class);
        TaskRunner r = running.get(c.taskId());
        if (r != null) {
          r.cancel(c.reason());
        }
      }
      case Wire.TASK_STEER -> {
        Wire.TaskSteer s = Json.convert(f.data(), Wire.TaskSteer.class);
        TaskRunner r = running.get(s.taskId());
        if (r != null) {
          r.steer(s.text());
        }
      }
      case Wire.TASK_INPUT -> {
        Wire.TaskInput i = Json.convert(f.data(), Wire.TaskInput.class);
        TaskRunner r = running.get(i.taskId());
        if (r != null) {
          r.input(i.requestId(), i.answer());
        }
      }
      case Wire.ACK -> {
        Wire.Ack a = Json.convert(f.data(), Wire.Ack.class);
        TaskRunner r = running.get(a.taskId());
        if (r != null) {
          r.ack(a.seq());
        }
      }
      case Wire.PROBE -> Thread.ofVirtual().start(() -> link.send(Wire.frame(Wire.PROBE_RESULT,
          new Wire.ProbeResult(probe()))));
      case Wire.ERROR -> log.warn("server error: {}", Json.str(f.data(), "message"));
      default -> log.debug("ignoring frame {}", f.type());
    }
  }

  private void assign(Wire.TaskAssign t) {
    if (running.containsKey(t.taskId())) {
      return;
    }
    AgentProvider p = providers.get(t.provider());
    if (p == null || running.size() >= config.capacity()) {
      String why = p == null ? "provider " + t.provider() + " is not available on this runtime" : "runtime is at capacity";
      link.send(Wire.frame(Wire.TASK_COMPLETED, new Wire.TaskCompleted(t.taskId(), "failed", null, null, null,
          dev.orca.protocol.ErrorCodes.PROVIDER_UNAVAILABLE, why, Map.of(), 0)));
      return;
    }
    TaskRunner r = new TaskRunner(t, p, workspaces, link, config.isolated(), () -> running.remove(t.taskId()));
    running.put(t.taskId(), r);
    Thread.ofVirtual().name("task-" + t.taskId()).start(r);
  }

  private void heartbeatLoop() {
    while (true) {
      try {
        Thread.sleep(heartbeatSeconds * 1000L);
      } catch (InterruptedException e) {
        return;
      }
      if (link.connected()) {
        link.send(Wire.frame(Wire.HEARTBEAT, new Wire.Heartbeat(new ArrayList<>(running.keySet()),
            Math.max(0, config.capacity() - running.size()))));
      }
    }
  }

  private void probeLoop() {
    while (true) {
      try {
        Thread.sleep(Duration.ofMinutes(5).toMillis());
      } catch (InterruptedException e) {
        return;
      }
      if (running.isEmpty() && link.connected()) {
        link.send(Wire.frame(Wire.PROBE_RESULT, new Wire.ProbeResult(probe())));
      }
    }
  }

  private String name() {
    return config.name() != null && !config.name().isBlank() ? config.name() : hostname();
  }

  private static String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "unknown";
    }
  }
}
