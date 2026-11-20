package dev.orca.agent.core;

/** Capability names shared by probes, sessions and the UI. */
public final class Capability {
  public static final String RESUME = "resume";
  public static final String FORK = "fork";
  public static final String STEER = "steer";
  public static final String STRUCTURED_OUTPUT = "structured_output";
  public static final String IMAGES = "images";
  public static final String USER_INPUT = "user_input";
  public static final String MCP_HTTP = "mcp_http";
  public static final String USAGE = "usage";
  /** A built-in code review mode (Codex {@code review/start}). */
  public static final String REVIEW = "review";

  private Capability() {}
}
