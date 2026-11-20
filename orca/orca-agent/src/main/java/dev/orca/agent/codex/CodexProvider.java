package dev.orca.agent.codex;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** OpenAI Codex CLI, driven through {@code codex app-server} (JSON-RPC over stdio). */
public final class CodexProvider implements AgentProvider {

  public static final String ID = "codex";
  static final Set<String> CAPABILITIES = Set.of(Capability.RESUME, Capability.FORK, Capability.STEER,
      Capability.STRUCTURED_OUTPUT, Capability.IMAGES, Capability.USER_INPUT, Capability.MCP_HTTP, Capability.USAGE);

  private final CodexConfig config;

  public CodexProvider(CodexConfig config) {
    this.config = config;
  }

  @Override
  public String id() {
    return ID;
  }

  List<String> baseCommand() throws AgentException {
    if (!config.command().isEmpty()) {
      return config.command();
    }
    Optional<Path> exe = Executables.find(null, "codex");
    if (exe.isEmpty()) {
      throw new AgentException(ErrorCodes.PROVIDER_NOT_INSTALLED, "codex is not installed or not on PATH");
    }
    return List.of(exe.get().toString());
  }

  CodexConfig config() {
    return config;
  }

  @Override
  public ProviderStatus probe() {
    List<String> base;
    try {
      base = baseCommand();
    } catch (AgentException e) {
      return ProviderStatus.missing(ID, e.getMessage());
    }
    List<String> versionCmd = new ArrayList<>(base);
    versionCmd.add("--version");
    Executables.Output out = Executables.run(versionCmd, config.env(), 20);
    String version = Executables.version(out.stdout());
    if (out.exitCode() != 0 || version == null) {
      return new ProviderStatus(ID, false, null, String.join(" ", base), false, null, null, List.of(), Set.of(),
          "codex --version failed: " + Executables.plain(out.stderr()));
    }
    String path = String.join(" ", base);
    Process p = null;
    try {
      p = start(base, Path.of(System.getProperty("java.io.tmpdir")), Map.of());
      try (JsonRpcConnection c = new JsonRpcConnection(p, "codex-probe", false)) {
        Duration t = Duration.ofSeconds(30);
        initialize(c, t);
        Object account = c.call("account/read", Map.of(), t);
        Map<String, Object> acct = Json.obj(account, "account");
        boolean loggedIn = !acct.isEmpty();
        String authMode = loggedIn ? Json.str(acct, "type") : null;
        String plan = loggedIn ? Json.str(acct, "planType") : null;
        List<ProviderStatus.ModelInfo> models = new ArrayList<>();
        if (loggedIn) {
          Object list = c.call("model/list", Map.of(), t);
          for (Object o : Json.list(list, "data")) {
            if (Json.bool(o, "hidden")) {
              continue;
            }
            List<String> efforts = new ArrayList<>();
            for (Object e : Json.list(o, "supportedReasoningEfforts")) {
              efforts.add(e instanceof Map<?, ?> ? Json.str(e, "reasoningEffort") : String.valueOf(e));
            }
            models.add(new ProviderStatus.ModelInfo(Json.str(o, "id"), Json.str(o, "displayName"),
                Json.str(o, "description"), Json.bool(o, "isDefault"), efforts, Json.str(o, "defaultReasoningEffort")));
          }
        }
        return new ProviderStatus(ID, true, version, path, loggedIn, authMode, plan, models, CAPABILITIES,
            loggedIn ? null : "not logged in: run `codex login` (or `codex login --device-auth`) on this machine");
      }
    } catch (AgentException | IOException e) {
      return new ProviderStatus(ID, true, version, path, false, null, null, List.of(), CAPABILITIES,
          "app-server probe failed: " + e.getMessage());
    } finally {
      if (p != null) {
        p.destroy();
      }
    }
  }

  @Override
  public AgentSession open(SessionSpec spec, Interaction.Handler handler) throws AgentException {
    return CodexSession.open(this, spec, handler);
  }

  Process start(List<String> base, Path cwd, Map<String, String> extraEnv, List<String> extraArgs) throws IOException {
    List<String> cmd = new ArrayList<>(base);
    cmd.addAll(extraArgs);
    cmd.add("app-server");
    cmd.add("--listen");
    cmd.add("stdio://");
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
    pb.environment().putAll(config.env());
    pb.environment().putAll(extraEnv);
    return pb.start();
  }

  Process start(List<String> base, Path cwd, Map<String, String> extraEnv) throws IOException {
    return start(base, cwd, extraEnv, List.of());
  }

  void initialize(JsonRpcConnection c, Duration timeout) throws AgentException, IOException {
    c.call("initialize", Map.of("clientInfo",
        Map.of("name", config.clientName(), "title", "Orca", "version", config.clientVersion())), timeout);
    c.notify("initialized", null);
  }
}
