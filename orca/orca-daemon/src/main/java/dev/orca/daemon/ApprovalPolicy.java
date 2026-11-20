package dev.orca.daemon;

import dev.orca.agent.core.Interaction.ApprovalKind;
import dev.orca.agent.core.Interaction.ApprovalRequest;
import dev.orca.agent.core.Interaction.Decision;
import dev.orca.agent.core.Permission;
import java.nio.file.Path;
import java.util.List;

/**
 * Answers approval requests without a person, from the agent's permission tier and its allowed
 * command prefixes. Anything not clearly allowed is declined; the agent then reports that it could
 * not proceed, which is the honest outcome.
 */
public final class ApprovalPolicy {

  private final Permission permission;
  private final Path workspace;
  private final List<String> allowedCommands;

  public ApprovalPolicy(Permission permission, Path workspace, List<String> allowedCommands) {
    this.permission = permission;
    this.workspace = workspace.toAbsolutePath().normalize();
    this.allowedCommands = allowedCommands == null ? List.of() : allowedCommands;
  }

  public Decision decide(ApprovalRequest r) {
    if (permission == Permission.FULL) {
      return Decision.ACCEPT;
    }
    if (permission == Permission.READ_ONLY) {
      return Decision.DECLINE;
    }
    if (r.kind() == ApprovalKind.FILE_CHANGE) {
      boolean inside = !r.paths().isEmpty() && r.paths().stream().allMatch(this::insideWorkspace);
      return inside ? Decision.ACCEPT : Decision.DECLINE;
    }
    if (r.kind() == ApprovalKind.COMMAND && r.command() != null && commandAllowed(r.command())) {
      return Decision.ACCEPT_FOR_SESSION;
    }
    return Decision.DECLINE;
  }

  boolean commandAllowed(String command) {
    String c = command.trim();
    for (String allowed : allowedCommands) {
      String a = allowed.trim();
      if (a.isEmpty()) {
        continue;
      }
      if (a.endsWith("*") ? c.startsWith(a.substring(0, a.length() - 1)) : c.equals(a) || c.startsWith(a + " ")) {
        return !c.contains("&&") && !c.contains(";") && !c.contains("|") && !c.contains("`") && !c.contains("$(");
      }
    }
    return false;
  }

  boolean insideWorkspace(String path) {
    if (path == null) {
      return false;
    }
    Path p = Path.of(path);
    Path abs = (p.isAbsolute() ? p : workspace.resolve(p)).normalize();
    return abs.startsWith(workspace);
  }
}
