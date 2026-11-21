package dev.fellowloop.agent.codex;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Daemon-side settings for the Codex driver.
 *
 * @param command    the CLI to run, e.g. {@code [codex]} or {@code [node, /opt/codex/bin/codex.js]};
 *                   empty means {@code codex} on PATH
 * @param env        extra environment, e.g. {@code CODEX_HOME}
 * @param recordDir  when set, every session's raw protocol lines are written here (for fixtures)
 */
public record CodexConfig(List<String> command, Map<String, String> env, String clientName, String clientVersion,
    Duration requestTimeout, Path recordDir) {

  public CodexConfig {
    command = command == null ? List.of() : List.copyOf(command);
    env = env == null ? Map.of() : Map.copyOf(env);
    clientName = clientName == null || clientName.isBlank() ? "fellowloop" : clientName;
    clientVersion = clientVersion == null ? "0.1.0" : clientVersion;
    requestTimeout = requestTimeout == null ? Duration.ofSeconds(60) : requestTimeout;
  }

  public static CodexConfig defaults() {
    return new CodexConfig(List.of(), Map.of(), "fellowloop", "0.1.0", null, null);
  }

  public CodexConfig withCommand(List<String> cmd) {
    return new CodexConfig(cmd, env, clientName, clientVersion, requestTimeout, recordDir);
  }
}
