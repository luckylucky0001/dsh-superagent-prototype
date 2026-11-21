package dev.fellowloop.agent.trae;

import dev.fellowloop.agent.core.AgentException;
import dev.fellowloop.agent.core.AgentProvider;
import dev.fellowloop.agent.core.AgentSession;
import dev.fellowloop.agent.core.Capability;
import dev.fellowloop.agent.core.Executables;
import dev.fellowloop.agent.core.Interaction;
import dev.fellowloop.agent.core.JsonRpcConnection;
import dev.fellowloop.agent.core.SessionSpec;
import dev.fellowloop.protocol.ErrorCodes;
import dev.fellowloop.protocol.Json;
import dev.fellowloop.protocol.ProviderStatus;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * TRAE CLI 2.x ({@code traecli}), driven over ACP ({@code traecli acp serve}). TRAE CLI 1.x speaks a
 * different protocol and is refused.
 */
public final class TraeProvider implements AgentProvider {

  public static final String ID = "trae";
  static final String MIN_VERSION = "0.200.0";

  private final TraeConfig config;

  public TraeProvider(TraeConfig config) {
    this.config = config;
  }

  @Override
  public String id() {
    return ID;
  }

  TraeConfig config() {
    return config;
  }

  List<String> baseCommand() throws AgentException {
    if (!config.command().isEmpty()) {
      return config.command();
    }
    Optional<Path> exe = Executables.find(null, "traecli");
    if (exe.isEmpty()) {
      throw new AgentException(ErrorCodes.PROVIDER_NOT_INSTALLED, "traecli is not installed or not on PATH");
    }
    return List.of(exe.get().toString());
  }

  private static final long MODEL_CACHE_MS = 30 * 60_000L;
  private volatile List<ProviderStatus.ModelInfo> cachedModels;
  private volatile long cachedAt;

  /**
   * The models this account may use, from the {@code model} config option of a fresh ACP session: ids
   * ({@code Doubao_1_6}) with display names ("Doubao-Seed-Code"), descriptions and the default. The probe
   * session is closed right away; no prompt is sent, so it costs no tokens.
   */
  static List<ProviderStatus.ModelInfo> acpModels(JsonRpcConnection c) {
    Path dir = null;
    try {
      dir = java.nio.file.Files.createTempDirectory("fellowloop-trae-probe");
      Object s = c.call("session/new", Map.of("cwd", dir.toString(), "mcpServers", List.of()), Duration.ofSeconds(30));
      List<ProviderStatus.ModelInfo> out = modelsFrom(s);
      String sid = Json.str(s, "sessionId");
      if (sid != null) {
        try {
          c.call("session/close", Map.of("sessionId", sid), Duration.ofSeconds(10));
        } catch (AgentException ignored) {
          // older CLIs cannot close sessions; it is only an empty session
        }
      }
      return out;
    } catch (AgentException | IOException e) {
      return List.of();
    } finally {
      if (dir != null) {
        // TRAE may write into the session folder; a probe runs every half hour, so delete all of it
        try (java.util.stream.Stream<java.nio.file.Path> all = java.nio.file.Files.walk(dir)) {
          all.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        } catch (IOException ignored) {
          // best effort
        }
      }
    }
  }

  static List<ProviderStatus.ModelInfo> modelsFrom(Object sessionNew) {
    List<ProviderStatus.ModelInfo> out = new ArrayList<>();
    for (Object o : Json.list(sessionNew, "configOptions")) {
      if (!"model".equals(Json.str(o, "id"))) {
        continue;
      }
      String current = Json.str(o, "currentValue");
      for (Object m : Json.list(o, "options")) {
        String id = Json.str(m, "value");
        if (id != null) {
          out.add(new ProviderStatus.ModelInfo(id, Json.str(m, "name"), Json.str(m, "description"), id.equals(current),
              List.of(), null));
        }
      }
    }
    return out;
  }

  @Override
  public ProviderStatus probe() {
    List<String> base;
    try {
      base = baseCommand();
    } catch (AgentException e) {
      return ProviderStatus.missing(ID, e.getMessage());
    }
    String path = String.join(" ", base);
    Executables.Output v = Executables.run(concat(base, "-V"), config.env(), 20);
    String version = Executables.version(v.stdout());
    if (v.exitCode() != 0 || version == null) {
      return new ProviderStatus(ID, false, null, path, false, null, null, List.of(), Set.of(),
          "traecli -V failed: " + Executables.plain(v.stderr()));
    }
    if (Executables.compareVersions(version, MIN_VERSION) < 0) {
      return new ProviderStatus(ID, true, version, path, false, null, null, List.of(), Set.of(),
          "TRAE CLI " + version + " is 1.x; install TRAE CLI 2.x");
    }
    Executables.Output login = Executables.run(concat(base, "login", "status"), config.env(), 20);
    String loginText = Executables.plain(login.stdout() + "\n" + login.stderr());
    boolean loggedIn = login.exitCode() == 0 && !loginText.toLowerCase().contains("not logged in");
    Set<String> caps = new HashSet<>(Set.of(Capability.STRUCTURED_OUTPUT, Capability.USAGE, Capability.REVIEW));
    String error = loggedIn ? null : "not logged in: run `traecli login` (or set TRAECLI_PERSONAL_ACCESS_TOKEN and run "
        + "`traecli login --with-trae-pat`) on this machine";
    Process p = null;
    try {
      p = start(base, Path.of(System.getProperty("java.io.tmpdir")), Map.of(), List.of());
      try (JsonRpcConnection c = new JsonRpcConnection(p, "trae-probe", true)) {
        Object init = initialize(c, Duration.ofSeconds(30));
        caps.addAll(capabilities(init));
        if (loggedIn && (cachedModels == null || System.currentTimeMillis() - cachedAt > MODEL_CACHE_MS)) {
          cachedModels = acpModels(c);
          cachedAt = System.currentTimeMillis();
        }
      }
    } catch (AgentException | IOException e) {
      error = error == null ? "ACP probe failed: " + e.getMessage() : error;
    } finally {
      if (p != null) {
        p.destroy();
      }
    }
    List<ProviderStatus.ModelInfo> models = new ArrayList<>(loggedIn && cachedModels != null ? cachedModels : List.of());
    if (loggedIn && models.isEmpty()) {
      Executables.Output m = Executables.run(concat(base, "models"), config.env(), 30);
      if (m.exitCode() == 0) {
        for (String line : Executables.plain(m.stdout()).split("\n")) {
          String id = line.trim().replaceFirst("^[*\\-•]\\s*", "").split("\\s+")[0];
          if (!id.isBlank() && !id.endsWith(":") && id.length() < 80) {
            models.add(new ProviderStatus.ModelInfo(id, id, line.trim(), line.contains("*") || line.contains("default"),
                List.of(), null));
          }
        }
      }
    }
    return new ProviderStatus(ID, true, version, path, loggedIn, loggedIn ? "trae" : null, null, models, caps, error);
  }

  static Set<String> capabilities(Object initResult) {
    Set<String> caps = new HashSet<>();
    Map<String, Object> agent = Json.obj(initResult, "agentCapabilities");
    if (Json.bool(agent, "loadSession") || !Json.obj(agent, "sessionCapabilities").isEmpty()
        && Json.obj(agent, "sessionCapabilities").containsKey("resume")) {
      caps.add(Capability.RESUME);
    }
    if (Json.obj(agent, "sessionCapabilities").containsKey("fork")) {
      caps.add(Capability.FORK);
    }
    if (Json.bool(Json.obj(agent, "promptCapabilities"), "image")) {
      caps.add(Capability.IMAGES);
    }
    if (Json.bool(Json.obj(agent, "mcpCapabilities"), "http")) {
      caps.add(Capability.MCP_HTTP);
    }
    if (Json.obj(agent, "sessionCapabilities").containsKey("systemPrompt")) {
      caps.add(TraeSession.SYSTEM_PROMPT);
    }
    return caps;
  }

  @Override
  public AgentSession open(SessionSpec spec, Interaction.Handler handler) throws AgentException {
    return TraeSession.open(this, spec, handler);
  }

  Process start(List<String> base, Path cwd, Map<String, String> env, List<String> args) throws IOException {
    List<String> cmd = new ArrayList<>(base);
    cmd.addAll(args);
    if (!args.contains("serve")) {
      cmd.add("acp");
      cmd.add("serve");
    }
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
    pb.environment().putAll(config.env());
    pb.environment().putAll(env);
    return pb.start();
  }

  Object initialize(JsonRpcConnection c, Duration timeout) throws AgentException {
    return c.call("initialize", Map.of(
        "protocolVersion", 1,
        "clientInfo", Map.of("name", config.clientName(), "title", "Fellowloop", "version", config.clientVersion()),
        "clientCapabilities", Map.of("fs", Map.of("readTextFile", false, "writeTextFile", false), "terminal", false)),
        timeout);
  }

  private static List<String> concat(List<String> base, String... more) {
    List<String> l = new ArrayList<>(base);
    l.addAll(List.of(more));
    return l;
  }
}
