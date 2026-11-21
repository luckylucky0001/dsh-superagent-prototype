package dev.fellowloop.agent.trae;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Daemon-side settings for the TRAE CLI driver.
 *
 * @param command  the CLI to run; empty means {@code traecli} on PATH
 * @param trustRoot unused: TRAE persists a trusted-project override into the user's global config, so the
 *                  driver no longer marks workspaces trusted
 */
public record TraeConfig(List<String> command, Map<String, String> env, String clientName, String clientVersion,
    Duration requestTimeout, Path recordDir, Path trustRoot) {

  public TraeConfig {
    command = command == null ? List.of() : List.copyOf(command);
    env = env == null ? Map.of() : Map.copyOf(env);
    clientName = clientName == null || clientName.isBlank() ? "fellowloop" : clientName;
    clientVersion = clientVersion == null ? "0.1.0" : clientVersion;
    requestTimeout = requestTimeout == null ? Duration.ofSeconds(60) : requestTimeout;
  }

  public static TraeConfig defaults() {
    return new TraeConfig(List.of(), Map.of(), "fellowloop", "0.1.0", null, null, null);
  }
}
