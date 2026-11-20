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
      boolean inside = !r.paths().isEmpty() && r.paths().stream().allMatch(p -> insideWorkspace(p) && !inGitDir(p));
      return inside ? Decision.ACCEPT : Decision.DECLINE;
    }
    if (r.kind() == ApprovalKind.COMMAND && r.command() != null && commandAllowed(r.command())) {
      return Decision.ACCEPT_FOR_SESSION;
    }
    return Decision.DECLINE;
  }

  /** Commits stay inside the workspace; Codex keeps {@code .git} read-only, so they arrive as escalations. */
  static final List<String> BUILT_IN = List.of("git add", "git commit", "git status", "git diff", "git log");

  /** Options that would let a built-in git command write elsewhere, run programs or change its repository. */
  private static final List<String> GIT_FORBIDDEN = List.of("--output", "-o", "--exec", "-c", "-C", "--git-dir",
      "--work-tree", "--namespace", "--upload-pack", "--receive-pack", "--template", "--ext-diff", "--config-env",
      "--exec-path", "--no-verify", "-n");

  /** Allow-list entries the daemon will honour: no bare or leading wildcards, no wildcard after a short prefix. */
  static List<String> sanitize(List<String> entries) {
    List<String> out = new java.util.ArrayList<>();
    for (String e : entries == null ? List.<String>of() : entries) {
      String a = e == null ? "" : e.trim();
      if (a.isEmpty() || a.startsWith("*") || a.contains("*") && (!a.endsWith("*") || a.length() < 5)
          || a.chars().anyMatch(c -> ";|&`$<>\n\\".indexOf(c) >= 0)) {
        continue;
      }
      out.add(a);
    }
    return out;
  }

  /**
   * A command is allowed when every {@code &&}-separated part matches an allowed prefix word by word and
   * nothing can smuggle in another command. Outside quotes: no {@code ; | & < >} (other than the
   * {@code &&} separators), no newlines, no globs or subshells. Anywhere: no {@code $}, backticks,
   * backslashes or carriage returns (they expand or escape even inside double quotes). A newline inside
   * quotes is literal (a multi-line commit message). The first part may be {@code cd DIR} when DIR is in
   * the workspace: TRAE CLI writes {@code cd <workspace> && git add -A && git commit -m "…"}.
   */
  boolean commandAllowed(String command) {
    String c = dev.orca.agent.core.Executables.unwrapShell(command);
    if (c == null || c.isEmpty() || c.chars().anyMatch(ch -> "`$\\\r".indexOf(ch) >= 0)
        || dev.orca.agent.core.Executables.hasUnquotedExpansion(c)) {
      return false;
    }
    List<String> parts = splitUnquoted(c);
    if (parts == null) {
      return false;
    }
    for (int i = 0; i < parts.size(); i++) {
      List<String> w = words(parts.get(i).trim());
      if (i == 0 && parts.size() > 1 && cdInsideWorkspace(w)) {
        continue;
      }
      if (!partAllowed(w)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Splits on {@code &&} outside quotes; null when an unquoted {@code ; | & < >} or newline appears, or a
   * quote is left open.
   */
  static List<String> splitUnquoted(String c) {
    List<String> parts = new java.util.ArrayList<>();
    StringBuilder cur = new StringBuilder();
    char quote = 0;
    for (int i = 0; i < c.length(); i++) {
      char ch = c.charAt(i);
      if (quote != 0) {
        if (ch == quote) {
          quote = 0;
        }
        cur.append(ch);
      } else if (ch == '\'' || ch == '"') {
        quote = ch;
        cur.append(ch);
      } else if (ch == '&' && i + 1 < c.length() && c.charAt(i + 1) == '&') {
        if (i + 2 < c.length() && c.charAt(i + 2) == '&') {
          return null;
        }
        parts.add(cur.toString());
        cur.setLength(0);
        i++;
      } else if (";|&<>\n".indexOf(ch) >= 0) {
        return null;
      } else {
        cur.append(ch);
      }
    }
    if (quote != 0) {
      return null;
    }
    parts.add(cur.toString());
    return parts.stream().anyMatch(p -> p.isBlank()) ? null : parts;
  }

  /** {@code cd DIR} with one plain argument inside the workspace (it only changes the directory). */
  private boolean cdInsideWorkspace(List<String> words) {
    return words.size() == 2 && words.get(0).equals("cd") && !words.get(1).startsWith("-")
        && insideWorkspace(words.get(1)) && !inGitDir(words.get(1));
  }

  private boolean partAllowed(List<String> words) {
    // unquoted & ; | < > were refused while splitting; inside quotes they are plain text
    if (words.isEmpty()) {
      return false;
    }
    for (String b : BUILT_IN) {
      List<String> prefix = words(b);
      if (startsWith(words, prefix)) {
        // nothing between "git" and the subcommand, and no option that escapes the workspace
        return words.subList(prefix.size(), words.size()).stream().noneMatch(ApprovalPolicy::forbiddenGitArg);
      }
    }
    for (String allowed : sanitize(allowedCommands)) {
      if (allowed.endsWith("*")) {
        if (String.join(" ", words).startsWith(allowed.substring(0, allowed.length() - 1))) {
          return true;
        }
      } else if (startsWith(words, words(allowed))) {
        return true;
      }
    }
    return false;
  }

  private static boolean forbiddenGitArg(String w) {
    String flag = w.contains("=") ? w.substring(0, w.indexOf('=')) : w;
    if (GIT_FORBIDDEN.contains(flag) || flag.startsWith("--output") || flag.startsWith("--exec")) {
      return true;
    }
    // short options glued to a value, e.g. -ofile or -cfoo
    return (w.startsWith("-o") || w.startsWith("-c") || w.startsWith("-C")) && !w.startsWith("--") && w.length() > 2;
  }

  private static boolean startsWith(List<String> words, List<String> prefix) {
    return words.size() >= prefix.size() && words.subList(0, prefix.size()).equals(prefix);
  }

  /** Splits a command into words, honouring single and double quotes. */
  static List<String> words(String s) {
    List<String> out = new java.util.ArrayList<>();
    StringBuilder cur = new StringBuilder();
    char quote = 0;
    boolean any = false;
    for (char ch : s.toCharArray()) {
      if (quote != 0) {
        if (ch == quote) {
          quote = 0;
        } else {
          cur.append(ch);
        }
      } else if (ch == '\'' || ch == '"') {
        quote = ch;
        any = true;
      } else if (Character.isWhitespace(ch)) {
        if (cur.length() > 0 || any) {
          out.add(cur.toString());
          cur.setLength(0);
          any = false;
        }
      } else {
        cur.append(ch);
      }
    }
    if (cur.length() > 0 || any) {
      out.add(cur.toString());
    }
    return out;
  }

  boolean inGitDir(String path) {
    Path p = Path.of(path);
    Path abs = (p.isAbsolute() ? p : workspace.resolve(p)).normalize();
    for (Path part : workspace.relativize(abs)) {
      if (part.toString().equals(".git")) {
        return true;
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
