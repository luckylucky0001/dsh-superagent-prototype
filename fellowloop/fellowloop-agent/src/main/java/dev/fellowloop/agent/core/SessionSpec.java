package dev.fellowloop.agent.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * How to open an agent session.
 *
 * @param resumeRef   provider session to continue, or null for a new session
 * @param mcpUrl      platform MCP endpoint the agent may call, or null
 * @param mcpToken    bearer token for {@code mcpUrl}; passed through the environment, never argv
 * @param extraArgs   additional CLI arguments configured on the agent
 * @param network     network access inside the workspace sandbox
 */
public record SessionSpec(
    Path cwd,
    String model,
    String effort,
    String instructions,
    Permission permission,
    String resumeRef,
    String mcpUrl,
    String mcpToken,
    List<String> allowedCommands,
    List<String> extraArgs,
    Map<String, String> env,
    boolean network) {

  public SessionSpec(Path cwd, String model, String effort, String instructions, Permission permission, String resumeRef,
      String mcpUrl, String mcpToken, List<String> allowedCommands, List<String> extraArgs, Map<String, String> env) {
    this(cwd, model, effort, instructions, permission, resumeRef, mcpUrl, mcpToken, allowedCommands, extraArgs, env, false);
  }

  public SessionSpec {
    permission = permission == null ? Permission.WORKSPACE : permission;
    allowedCommands = allowedCommands == null ? List.of() : List.copyOf(allowedCommands);
    extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
    env = env == null ? Map.of() : Map.copyOf(env);
  }

  public static SessionSpec simple(Path cwd, String model) {
    return new SessionSpec(cwd, model, null, null, Permission.WORKSPACE, null, null, null, null, null, null);
  }

  public SessionSpec withResume(String ref) {
    return new SessionSpec(cwd, model, effort, instructions, permission, ref, mcpUrl, mcpToken, allowedCommands, extraArgs, env,
        network);
  }
}
