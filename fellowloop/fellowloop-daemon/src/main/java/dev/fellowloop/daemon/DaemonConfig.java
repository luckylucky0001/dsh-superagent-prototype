package dev.fellowloop.daemon;

import dev.fellowloop.protocol.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

/**
 * Daemon settings, stored in {@code ~/.fellowloop/daemon.json}. Command-line flags override the file.
 *
 * @param codexCommand command that runs Codex, e.g. {@code ["codex"]}; empty means PATH lookup
 * @param traeCommand  command that runs TRAE CLI; empty means PATH lookup
 * @param isolated     the machine is a disposable sandbox, so the FULL permission tier is allowed
 * @param allowExtraArgs pass agents' extra CLI arguments through (sandbox and approval flags are always dropped)
 * @param allowNetwork   agents that ask for it get network access inside the workspace sandbox
 */
public record DaemonConfig(
    String server,
    String token,
    String name,
    int capacity,
    String workRoot,
    List<String> codexCommand,
    List<String> traeCommand,
    Map<String, String> env,
    boolean isolated,
    String recordDir,
    boolean allowExtraArgs,
    boolean allowNetwork) {

  public DaemonConfig(String server, String token, String name, int capacity, String workRoot, List<String> codexCommand,
      List<String> traeCommand, Map<String, String> env, boolean isolated, String recordDir, boolean allowExtraArgs) {
    this(server, token, name, capacity, workRoot, codexCommand, traeCommand, env, isolated, recordDir, allowExtraArgs,
        false);
  }

  public DaemonConfig {
    capacity = capacity <= 0 ? 2 : capacity;
    codexCommand = codexCommand == null ? List.of() : List.copyOf(codexCommand);
    traeCommand = traeCommand == null ? List.of() : List.copyOf(traeCommand);
    env = env == null ? Map.of() : Map.copyOf(env);
  }

  /** How the runtime is listed: the configured name, else this host's name. */
  public String displayName() {
    return name != null && !name.isBlank() ? name : Daemon.hostname();
  }

  public static Path home() {
    String h = System.getenv("FELLOWLOOP_HOME");
    return h != null && !h.isBlank() ? Path.of(h) : Path.of(System.getProperty("user.home"), ".fellowloop");
  }

  public static Path file() {
    return home().resolve("daemon.json");
  }

  public static DaemonConfig load() throws IOException {
    Path f = file();
    if (!Files.exists(f)) {
      return new DaemonConfig(null, null, null, 2, null, null, null, null, false, null, false);
    }
    return Json.read(Files.readString(f), DaemonConfig.class);
  }

  public void save() throws IOException {
    Path f = file();
    Files.createDirectories(f.getParent());
    Files.writeString(f, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this));
    try {
      Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException ignored) {
      // non-POSIX file system
    }
  }

  public Path workRootPath() {
    return workRoot == null || workRoot.isBlank() ? home().resolve("work") : Path.of(workRoot);
  }

  public DaemonConfig with(String server, String token, String name, Integer capacity, String workRoot) {
    return new DaemonConfig(server != null ? server : this.server, token != null ? token : this.token,
        name != null ? name : this.name, capacity != null ? capacity : this.capacity,
        workRoot != null ? workRoot : this.workRoot, codexCommand, traeCommand, env, isolated, recordDir, allowExtraArgs, allowNetwork);
  }

  public DaemonConfig withCommands(List<String> codex, List<String> trae) {
    return new DaemonConfig(server, token, name, capacity, workRoot, codex != null ? codex : codexCommand,
        trae != null ? trae : traeCommand, env, isolated, recordDir, allowExtraArgs, allowNetwork);
  }

  public DaemonConfig withIsolated(boolean value) {
    return new DaemonConfig(server, token, name, capacity, workRoot, codexCommand, traeCommand, env, value, recordDir,
        allowExtraArgs, allowNetwork);
  }

  public DaemonConfig withAllowExtraArgs(boolean value) {
    return new DaemonConfig(server, token, name, capacity, workRoot, codexCommand, traeCommand, env, isolated, recordDir,
        value, allowNetwork);
  }

  public DaemonConfig withAllowNetwork(boolean value) {
    return new DaemonConfig(server, token, name, capacity, workRoot, codexCommand, traeCommand, env, isolated, recordDir,
        allowExtraArgs, value);
  }
}
