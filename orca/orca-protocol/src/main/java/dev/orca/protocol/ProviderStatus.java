package dev.orca.protocol;

import java.util.List;
import java.util.Set;

/**
 * What a daemon found out about one agent CLI on its machine. Never contains credentials or account
 * identifiers such as e-mail addresses.
 *
 * @param provider     {@code codex} or {@code trae}
 * @param authMode     e.g. {@code chatgpt}, {@code apikey}, {@code pat}; null when not logged in
 * @param plan         subscription tier when the CLI reports one
 * @param capabilities e.g. {@code resume}, {@code steer}, {@code structured_output}, {@code images}
 */
public record ProviderStatus(
    String provider,
    boolean installed,
    String version,
    String path,
    boolean loggedIn,
    String authMode,
    String plan,
    List<ModelInfo> models,
    Set<String> capabilities,
    String error) {

  public ProviderStatus {
    models = models == null ? List.of() : List.copyOf(models);
    capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
  }

  public boolean healthy() {
    return installed && loggedIn && error == null;
  }

  public static ProviderStatus missing(String provider, String error) {
    return new ProviderStatus(provider, false, null, null, false, null, null, List.of(), Set.of(), error);
  }

  public record ModelInfo(String id, String displayName, String description, boolean isDefault,
      List<String> efforts, String defaultEffort) {}
}
