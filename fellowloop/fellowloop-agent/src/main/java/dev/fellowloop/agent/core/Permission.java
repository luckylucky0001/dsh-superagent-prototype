package dev.fellowloop.agent.core;

/** Provider-independent permission tiers; each driver maps them to its CLI's sandbox and approvals. */
public enum Permission {
  /** May read the workspace; every write or command outside the sandbox is refused. */
  READ_ONLY,
  /** May edit the workspace and run commands in the sandbox; escalations go through the policy. */
  WORKSPACE,
  /** No sandbox and no approvals. Only for runtimes that are isolated externally. */
  FULL;

  public static Permission parse(String s) {
    if (s == null || s.isBlank()) {
      return WORKSPACE;
    }
    return switch (s.trim().toLowerCase().replace('-', '_')) {
      case "read_only", "readonly" -> READ_ONLY;
      case "full", "full_access", "danger_full_access" -> FULL;
      default -> WORKSPACE;
    };
  }
}
