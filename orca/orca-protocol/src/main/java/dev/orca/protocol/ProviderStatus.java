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
 * @param usage        plan usage limits when the CLI reports them (Codex with a ChatGPT plan); empty otherwise
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
    String error,
    List<UsageWindow> usage) {

  public ProviderStatus {
    models = models == null ? List.of() : List.copyOf(models);
    capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    usage = usage == null ? List.of() : List.copyOf(usage);
  }

  public ProviderStatus(String provider, boolean installed, String version, String path, boolean loggedIn,
      String authMode, String plan, List<ModelInfo> models, Set<String> capabilities, String error) {
    this(provider, installed, version, path, loggedIn, authMode, plan, models, capabilities, error, List.of());
  }

  /**
   * One plan usage window, e.g. the 5-hour or the weekly Codex limit.
   *
   * @param usedPercent   0..100
   * @param windowMinutes length of the window (300 = 5 hours, 10080 = a week); null when unknown
   * @param resetsAt      when the window resets, epoch milliseconds; null when unknown
   * @param reached       the CLI reports the limit as reached
   */
  public record UsageWindow(int usedPercent, Long windowMinutes, Long resetsAt, boolean reached) {}

  /** The most used window, or null when nothing was reported. */
  public UsageWindow tightestUsage() {
    return usage.stream().max(java.util.Comparator.comparingInt(UsageWindow::usedPercent)).orElse(null);
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
