package dev.orca.server.runtime;

import dev.orca.protocol.Wire;
import dev.orca.server.auth.AuthService;
import dev.orca.server.auth.CurrentUser;
import dev.orca.server.support.ApiException;
import dev.orca.server.support.OrcaProperties;
import dev.orca.server.support.Views.RuntimeView;
import dev.orca.server.task.TaskService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RuntimeController {

  private final RuntimeService runtimes;
  private final AuthService auth;
  private final DaemonGateway gateway;
  private final TaskService tasks;
  private final OrcaProperties props;
  private final String downloads;

  public RuntimeController(RuntimeService runtimes, AuthService auth, DaemonGateway gateway, TaskService tasks,
      OrcaProperties props, @org.springframework.beans.factory.annotation.Value("${orca.downloads-dir:}") String downloads) {
    this.downloads = downloads;
    this.runtimes = runtimes;
    this.auth = auth;
    this.gateway = gateway;
    this.tasks = tasks;
    this.props = props;
  }

  @GetMapping("/api/runtimes")
  public List<RuntimeView> list() {
    return runtimes.all().stream().map(r -> RuntimeView.of(r, tasks.active(r.id))).toList();
  }

  @org.springframework.beans.factory.annotation.Autowired
  private dev.orca.server.audit.AuditService audit;

  @PostMapping("/api/runtimes/registration-codes")
  public Map<String, Object> code() {
    audit.record("runtime.registration_code", "runtime", null, Map.of());
    String code = auth.issueRegistrationCode(CurrentUser.id(), "runtime registration");
    String server = props.publicUrl();
    Map<String, Object> out = new java.util.LinkedHashMap<>();
    out.put("code", code);
    out.put("expiresInMinutes", 60);
    out.put("server", server);
    if (downloadable("install-runtime.sh")) {
      // one line that checks Java/git/Codex, installs, registers, runs doctor and offers a service
      out.put("install", "curl -fsSL " + server + "/downloads/install-runtime.sh | sh -s -- --server " + server
          + " --code " + code);
    }
    out.put("download", "curl -fsSLO " + server + "/downloads/orca-daemon.jar");
    out.put("register", "java -jar orca-daemon.jar register --server " + server + " --code " + code);
    out.put("doctor", "java -jar orca-daemon.jar doctor");
    out.put("start", "java -jar orca-daemon.jar start");
    return out;
  }

  private boolean downloadable(String file) {
    return downloads != null && !downloads.isBlank() && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(downloads, file));
  }

  /**
   * Lets {@code orca-daemon doctor} check its runtime token over plain HTTP, without opening (and so
   * displacing) the daemon connection. Authenticated by the runtime token itself.
   */
  @GetMapping("/api/daemon/whoami")
  public Map<String, Object> whoami(jakarta.servlet.http.HttpServletRequest req) {
    var t = auth.verify(dev.orca.server.auth.AuthFilter.token(req), AuthService.DAEMON)
        .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
            "the runtime token is not valid (the runtime may have been removed)"));
    var r = runtimes.find(t.runtimeId).orElseThrow(() -> ApiException.notFound("runtime"));
    return Map.of("runtimeId", r.id, "name", r.name, "status", r.status);
  }

  @PostMapping("/api/runtimes/{id}/probe")
  public Map<String, Object> probe(@PathVariable String id) {
    if (!gateway.send(id, Wire.frame(Wire.PROBE, Map.of()))) {
      throw ApiException.bad("the runtime is offline");
    }
    return Map.of("ok", true);
  }

  @DeleteMapping("/api/runtimes/{id}")
  public Map<String, Object> delete(@PathVariable String id) {
    String name = runtimes.find(id).map(r -> r.name).orElseThrow(() -> ApiException.notFound("runtime"));
    gateway.disconnect(id);
    runtimes.delete(id);
    audit.record("runtime.remove", "runtime", id, Map.of("name", name));
    return Map.of("ok", true);
  }

  public record RegisterRequest(String code, String name) {}

  /** Called by {@code orca-daemon register}; unauthenticated, the one-time code is the credential. */
  @PostMapping("/api/daemon/register")
  public Map<String, Object> register(@RequestBody RegisterRequest req) {
    String[] token = new String[1];
    Wire.Welcome w = runtimes.register(req.code(), req.name(), token);
    audit.record(null, "daemon", "runtime.register", "runtime", w.runtimeId(),
        Map.of("name", req.name() == null ? "" : req.name()));
    return Map.of("token", token[0], "runtimeId", w.runtimeId());
  }
}
