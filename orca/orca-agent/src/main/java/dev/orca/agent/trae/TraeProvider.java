package dev.orca.agent.trae;

import dev.orca.agent.core.AgentException;
import dev.orca.agent.core.AgentProvider;
import dev.orca.agent.core.AgentSession;
import dev.orca.agent.core.Capability;
import dev.orca.agent.core.Executables;
import dev.orca.agent.core.Interaction;
import dev.orca.agent.core.JsonRpcConnection;
import dev.orca.agent.core.SessionSpec;
import dev.orca.protocol.ErrorCodes;
import dev.orca.protocol.Json;
import dev.orca.protocol.ProviderStatus;
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
    Set<String> caps = new HashSet<>(Set.of(Capability.STRUCTURED_OUTPUT, Capability.USAGE));
    String error = loggedIn ? null : "not logged in: run `traecli login` (or set TRAECLI_PERSONAL_ACCESS_TOKEN and run "
        + "`traecli login --with-trae-pat`) on this machine";
    Process p = null;
    try {
      p = start(base, Path.of(System.getProperty("java.io.tmpdir")), Map.of(), List.of());
      try (JsonRpcConnection c = new JsonRpcConnection(p, "trae-probe", true)) {
        Object init = initialize(c, Duration.ofSeconds(30));
        caps.addAll(capabilities(init));
      }
    } catch (AgentException | IOException e) {
      error = error == null ? "ACP probe failed: " + e.getMessage() : error;
    } finally {
      if (p != null) {
        p.destroy();
      }
    }
    List<ProviderStatus.ModelInfo> models = new ArrayList<>();
    if (loggedIn) {
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
        "clientInfo", Map.of("name", config.clientName(), "title", "Orca", "version", config.clientVersion()),
        "clientCapabilities", Map.of("fs", Map.of("readTextFile", false, "writeTextFile", false), "terminal", false)),
        timeout);
  }

  private static List<String> concat(List<String> base, String... more) {
    List<String> l = new ArrayList<>(base);
    l.addAll(List.of(more));
    return l;
  }
}
