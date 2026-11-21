package dev.orca.daemon;

import dev.orca.agent.core.Executables;
import dev.orca.agent.core.Interaction.ApprovalKind;
import dev.orca.agent.core.Interaction.ApprovalRequest;
import dev.orca.agent.core.Interaction.Decision;
import dev.orca.agent.core.Permission;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Answers approval requests without a person, from the agent's permission tier and its allowed
 * command prefixes. Anything not clearly allowed is declined; the agent then reports that it could
 * not proceed, which is the honest outcome.
 *
 * <p>An approved command runs outside the agent's sandbox, so the rules are allow-lists, not
 * block-lists: the command's directory must be in the workspace, built-in git commands accept only
 * listed options, and no git command is approved while the workspace holds a nested repository
 * (whose configuration the agent wrote, and which git would read). Approvals are for this one
 * command only; the agent asks again for the next one.
 */
public final class ApprovalPolicy {

  private final Permission permission;
  private final Path workspace;
  private final List<String> allowedCommands;

  public ApprovalPolicy(Permission permission, Path workspace, List<String> allowedCommands) {
    this.permission = permission;
    Path abs = workspace.toAbsolutePath().normalize();
    try {
      abs = abs.toRealPath();
    } catch (IOException e) {
      // not created yet: compare lexically
    }
    this.workspace = abs;
    this.allowedCommands = sanitize(allowedCommands);
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
    if (r.kind() == ApprovalKind.COMMAND && r.command() != null) {
      Path cwd = r.cwd() == null || r.cwd().isBlank() ? workspace : real(workspace, r.cwd());
      if (cwd != null && cwd.startsWith(workspace) && commandAllowed(r.command(), cwd)) {
        return Decision.ACCEPT;
      }
    }
    return Decision.DECLINE;
  }

  /** Commits stay inside the workspace; the sandboxes keep {@code .git} read-only, so they arrive as escalations. */
  static final List<String> BUILT_IN = List.of("git add", "git commit", "git status", "git diff", "git log");

  /**
   * Options of a built-in git subcommand that are approved: exact words, prefixes (a word starting with
   * one is allowed), short flags that may be clustered, and short flags that take the rest of the word
   * (or, for {@code nextValue}, the next word) as their value. Anything else is declined: git accepts
   * abbreviated long options, so a block-list cannot be complete.
   */
  private record GitOptions(Set<String> exact, List<String> prefixes, String shortFlags, String attachedValue,
      String nextValue, Set<String> longNext) {
    GitOptions(Set<String> exact, List<String> prefixes, String shortFlags, String attachedValue, String nextValue) {
      this(exact, prefixes, shortFlags, attachedValue, nextValue, Set.of());
    }
  }

  private static final Map<String, GitOptions> GIT_OPTIONS = Map.of(
      "status", new GitOptions(Set.of("--short", "--branch", "--porcelain", "--long", "--untracked-files", "--ignored",
          "--show-stash", "--ahead-behind", "--no-ahead-behind", "--renames", "--no-renames", "--verbose",
          "--ignore-submodules", "--null"),
          List.of("--porcelain=", "--untracked-files=", "--ignored="), "sbzv", "u", ""),
      "diff", new GitOptions(Set.of("--stat", "--shortstat", "--numstat", "--dirstat", "--summary", "--name-only",
          "--name-status", "--cached", "--staged", "--patch", "--no-patch", "--raw", "--patch-with-stat",
          "--no-color", "--color", "--word-diff", "--minimal", "--patience", "--histogram", "--ignore-all-space",
          "--ignore-space-change", "--ignore-blank-lines", "--ignore-cr-at-eol", "--function-context", "--check",
          "--exit-code", "--quiet", "--find-renames", "--no-renames", "--merge-base", "--relative", "--no-ext-diff",
          "--no-textconv", "--full-index", "--binary", "--abbrev", "--color-words"),
          List.of("--stat=", "--color=", "--word-diff=", "--unified=", "--diff-filter=", "--find-renames=",
              "--relative=", "--abbrev=", "--diff-algorithm=", "--color-words="), "pusbwRzW", "UMSG", "SG",
          Set.of("--diff-filter")),
      "log", new GitOptions(Set.of("--oneline", "--stat", "--shortstat", "--numstat", "--name-only", "--name-status",
          "--graph", "--decorate", "--no-decorate", "--abbrev-commit", "--all", "--branches", "--reverse",
          "--first-parent", "--no-merges", "--merges", "--follow", "--no-color", "--patch", "--no-patch",
          "--summary", "--date-order", "--topo-order", "--no-ext-diff", "--no-textconv", "--source"),
          List.of("--format=", "--pretty=", "--pretty", "--decorate=", "--since=", "--until=", "--after=",
              "--before=", "--author=", "--committer=", "--grep=", "--max-count=", "--skip=", "--date=",
              "--unified=", "--diff-filter=", "--abbrev="), "ps", "UnSG", "nSG",
          Set.of("--max-count", "--grep", "--author", "--committer", "--since", "--until", "--after", "--before",
              "--format", "--pretty", "--skip", "--date", "--diff-filter")),
      // no --force: it would stage ignored folders (node_modules, …) that the nested-repository check skips
      "add", new GitOptions(Set.of("--all", "--update", "--verbose", "--dry-run", "--intent-to-add",
          "--ignore-errors", "--renormalize"), List.of(), "AuvnN", "", ""),
      "commit", new GitOptions(Set.of("--all", "--amend", "--no-edit", "--quiet", "--signoff", "--allow-empty",
          "--verbose", "--no-gpg-sign", "--no-status", "--message"),
          List.of("--message="), "aqsv", "m", "m"));

  /** For git commands an administrator allowed (e.g. {@code git push}): options that run programs or leave the workspace. */
  private static final List<String> GIT_FORBIDDEN_LONG = List.of("--output", "--exec", "--exec-path", "--git-dir",
      "--work-tree", "--namespace", "--upload-pack", "--receive-pack", "--template", "--ext-diff", "--textconv",
      "--config-env", "--no-verify", "--file", "--no-index", "--pathspec-from-file", "--edit", "--patch",
      "--interactive", "--gpg-sign", "--super-prefix", "--recurse-submodules", "--repo", "--config");
  private static final String GIT_FORBIDDEN_SHORT = "cCFoepStn";

  /** Allow-list entries the daemon will honour: no bare or leading wildcards, no wildcard after a short prefix. */
  public static List<String> sanitize(List<String> entries) {
    List<String> out = new ArrayList<>();
    for (String e : entries == null ? List.<String>of() : entries) {
      String a = e == null ? "" : e.trim();
      if (a.isEmpty() || a.startsWith("*") || a.contains("*") && (!a.endsWith("*") || a.length() < 5)
          || a.chars().anyMatch(c -> ";|&`$<>\n\r\\()\"'".indexOf(c) >= 0)) {
        continue;
      }
      out.add(a);
    }
    return out;
  }

  /** {@link #commandAllowed(String, Path)} run in the workspace root. */
  boolean commandAllowed(String command) {
    return commandAllowed(command, workspace);
  }

  /**
   * A command is allowed when every {@code &&}-separated part is allowed and nothing can smuggle in another
   * command. Outside quotes: no {@code ; | & < >} (other than the {@code &&} separators), no newlines, no
   * globs or subshells. Anywhere: no {@code $}, backticks, backslashes or carriage returns (they expand or
   * escape even inside double quotes). A newline inside quotes is literal (a multi-line commit message). The
   * first part may be {@code cd DIR} when DIR is the workspace root itself: TRAE CLI writes
   * {@code cd <workspace> && git add -A && git commit -m "…"}.
   */
  boolean commandAllowed(String command, Path cwd) {
    String c = Executables.unwrapShell(command);
    if (c == null || c.isEmpty() || c.chars().anyMatch(ch -> "`$\\\r".indexOf(ch) >= 0)
        || Executables.hasUnquotedExpansion(c)) {
      return false;
    }
    List<String> parts = splitUnquoted(c);
    if (parts == null) {
      return false;
    }
    Path dir = cwd;
    safeMemo.remove();
    for (int i = 0; i < parts.size(); i++) {
      List<String> w = words(parts.get(i).trim());
      if (i == 0 && parts.size() > 1 && w.size() == 2 && w.get(0).equals("cd")) {
        // only to the workspace root, after following symlinks: anywhere else could be a nested repository
        if (!workspace.equals(real(dir, w.get(1)))) {
          return false;
        }
        dir = workspace;
        continue;
      }
      if (!partAllowed(w, dir)) {
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
    List<String> parts = new ArrayList<>();
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
    return parts.stream().anyMatch(String::isBlank) ? null : parts;
  }

  private boolean partAllowed(List<String> words, Path cwd) {
    // unquoted & ; | < > were refused while splitting; inside quotes they are plain text
    if (words.isEmpty()) {
      return false;
    }
    if (words.get(0).equals("git")) {
      // no options before the subcommand (-c, -C, --git-dir, …) and no nested repository git would wander into
      if (words.size() < 2 || words.get(1).startsWith("-") || !safeOnce()) {
        return false;
      }
      GitOptions options = GIT_OPTIONS.get(words.get(1));
      if (options != null) {
        return gitArgsAllowed(words.subList(2, words.size()), options, cwd);
      }
      if (words.get(1).equals("push")) {
        // only when an administrator allowed it, and only Orca's own branches to origin
        return allowedCommands.stream().anyMatch(a -> entryMatches(a, List.of("git", "push")))
            && pushAllowed(words.subList(2, words.size()));
      }
      if (words.stream().skip(2).anyMatch(w -> forbiddenGitArg(w) || remoteLike(w))) {
        return false;
      }
    }
    return allowedCommands.stream().anyMatch(a -> entryMatches(a, words));
  }

  /** An allow-list entry is a prefix of whole words, or ends with * to match any text after it. */
  private static boolean entryMatches(String allowed, List<String> words) {
    if (allowed.endsWith("*")) {
      return String.join(" ", words).startsWith(allowed.substring(0, allowed.length() - 1));
    }
    return startsWith(words, words(allowed));
  }

  /** Every option is on the subcommand's list; every other argument is a ref or a path inside the workspace. */
  private boolean gitArgsAllowed(List<String> args, GitOptions o, Path cwd) {
    boolean paths = false;
    for (int i = 0; i < args.size(); i++) {
      String a = args.get(i);
      if (!paths && a.equals("--")) {
        paths = true;
      } else if (!paths && a.startsWith("--")) {
        if (!o.exact().contains(a) && !o.longNext().contains(a) && o.prefixes().stream().noneMatch(a::startsWith)) {
          return false;
        }
        if (a.equals("--message") || o.longNext().contains(a)) {
          i++; // its value, any text
        }
      } else if (!paths && a.startsWith("-") && a.length() > 1) {
        if (a.substring(1).chars().allMatch(Character::isDigit) && o.nextValue().contains("n")) {
          continue; // git log -5
        }
        int skip = shortCluster(a.substring(1), o);
        if (skip < 0) {
          return false;
        }
        i += skip;
      } else if (!pathArgAllowed(a, cwd)) {
        return false;
      }
    }
    return true;
  }

  private static final java.util.regex.Pattern ORCA_REF =
      java.util.regex.Pattern.compile("(refs/heads/)?orca/[A-Za-z0-9._/-]+");

  /**
   * An allowed {@code git push}: to {@code origin} only, Orca's {@code orca/*} branches only, no force,
   * delete or mirror — the admin allowed publishing the agent's work, not rewriting or copying the
   * repository elsewhere. {@code git push origin orca/x}, {@code git push -u origin HEAD:orca/x}.
   */
  static boolean pushAllowed(List<String> args) {
    List<String> positional = new ArrayList<>();
    for (String a : args) {
      if (a.startsWith("-")) {
        if (!Set.of("-u", "--set-upstream", "-q", "--quiet", "--porcelain", "-v", "--verbose", "--dry-run", "-n")
            .contains(a)) {
          return false;
        }
      } else {
        positional.add(a);
      }
    }
    if (positional.size() < 2 || !positional.get(0).equals("origin")) {
      return false; // an explicit branch: a bare "git push" follows push.default and may publish main
    }
    for (String spec : positional.subList(1, positional.size())) {
      int colon = spec.indexOf(':');
      String src = colon < 0 ? spec : spec.substring(0, colon);
      String dst = colon < 0 ? null : spec.substring(colon + 1);
      boolean srcOk = ORCA_REF.matcher(src).matches() || src.equals("HEAD") && dst != null;
      if (!srcOk || dst != null && !ORCA_REF.matcher(dst).matches()) {
        return false;
      }
    }
    return true;
  }

  /** A URL, scp-style address, local path or transport helper where a remote name belongs. */
  private static boolean remoteLike(String w) {
    return !w.startsWith("-") && (w.contains("://") || w.contains("::") || w.contains("@") || w.contains(":")
        || w.startsWith("/") || w.startsWith(".") || w.startsWith("~"));
  }

  /** -1 when a flag is not allowed, else how many following words it takes as a value (0 or 1). */
  private static int shortCluster(String cluster, GitOptions o) {
    for (int j = 0; j < cluster.length(); j++) {
      char f = cluster.charAt(j);
      if (o.attachedValue().indexOf(f) >= 0) {
        // the rest of the word is the value; at the end of the word, some flags take the next one
        return j == cluster.length() - 1 && o.nextValue().indexOf(f) >= 0 ? 1 : 0;
      }
      if (o.shortFlags().indexOf(f) < 0) {
        return -1;
      }
    }
    return 0;
  }

  /**
   * A ref or path for a built-in git command: no {@code ..} segments, and inside the workspace after
   * following symlinks. (Two paths outside the work tree turn {@code git diff} into {@code --no-index}.)
   */
  private boolean pathArgAllowed(String a, Path cwd) {
    if (a.isEmpty() || a.startsWith("~") || List.of(a.split("[/\\\\]")).contains("..")) {
      return false;
    }
    Path p = real(cwd, a);
    return p != null && p.startsWith(workspace) && !gitDirPart(workspace.relativize(p));
  }

  private static boolean forbiddenGitArg(String w) {
    if (w.startsWith("--")) {
      String flag = w.contains("=") ? w.substring(0, w.indexOf('=')) : w;
      // git accepts any unambiguous prefix of a long option: --no-verif means --no-verify
      return flag.length() > 2 && GIT_FORBIDDEN_LONG.stream().anyMatch(f -> f.startsWith(flag) || flag.startsWith(f));
    }
    return w.startsWith("-") && w.length() > 1 && w.substring(1).chars().anyMatch(ch -> GIT_FORBIDDEN_SHORT.indexOf(ch) >= 0);
  }

  private static boolean startsWith(List<String> words, List<String> prefix) {
    return words.size() >= prefix.size() && words.subList(0, prefix.size()).equals(prefix);
  }

  /** Splits a command into words, honouring single and double quotes. */
  static List<String> words(String s) {
    List<String> out = new ArrayList<>();
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

  /** One walk per command, however many git parts it chains. */
  private final ThreadLocal<Boolean> safeMemo = new ThreadLocal<>();

  private boolean safeOnce() {
    Boolean b = safeMemo.get();
    if (b == null) {
      b = repositorySafe();
      safeMemo.set(b);
    }
    return b;
  }

  /** Most entries walked when looking for nested repositories; a bigger tree is treated as unsafe. */
  static final int WALK_LIMIT = 1_000_000;

  /**
   * True when the workspace has no nested repository: no {@code .git} (directory or file, any letter case)
   * anywhere below the root. git reads a nested repository's configuration — which the agent wrote — when
   * run inside it or when recursing into a submodule, and that configuration can name programs to run.
   * Symlinked directories are not followed: git does not follow them either, and a working directory
   * reached through one must resolve inside the workspace, where the walk sees its real location. The
   * check runs when the request is answered; the agent waits for that answer before the command runs.
   */
  boolean repositorySafe() {
    boolean skipIgnored;
    try {
      skipIgnored = Files.readString(workspace.resolve(".git/info/exclude")).contains("# orca");
    } catch (IOException e) {
      skipIgnored = false;
    }
    return repositorySafe(skipIgnored);
  }

  /** Folders {@code Workspaces.excludeCaches} lists in {@code .git/info/exclude}. */
  static final Set<String> IGNORED_DIRS = Set.of("node_modules", ".venv", ".mypy_cache", ".ruff_cache", ".gradle",
      "__pycache__", ".pytest_cache");

  private boolean repositorySafe(boolean skipIgnored) {
    int[] seen = {0};
    boolean[] nested = {false};
    try {
      Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
          if (dir.equals(workspace)) {
            return FileVisitResult.CONTINUE;
          }
          if (dir.getParent().equals(workspace) && dir.getFileName().toString().equals(".git")) {
            // the workspace's own repository (exact name; ".GIT" or ".git." beside it is treated as nested)
            return FileVisitResult.SKIP_SUBTREE; // the workspace's own repository
          }
          FileVisitResult r = visit(dir);
          // dependency folders the daemon excluded from git in .git/info/exclude (which agents cannot edit): git
          // neither enters them nor stages them (git add -f is not approved), so a repository inside is inert
          return r == FileVisitResult.CONTINUE && skipIgnored && IGNORED_DIRS.contains(dir.getFileName().toString())
              ? FileVisitResult.SKIP_SUBTREE : r;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          return visit(file);
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException e) {
          // unreadable for the daemon's user, so unreadable for the git it would approve as well
          return FileVisitResult.SKIP_SUBTREE;
        }

        private FileVisitResult visit(Path p) {
          if (++seen[0] > WALK_LIMIT || isGitName(p.getFileName().toString())) {
            nested[0] = true;
            return FileVisitResult.TERMINATE;
          }
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      return false;
    }
    return !nested[0];
  }

  boolean inGitDir(String path) {
    Path p = real(workspace, path);
    return p == null || !p.startsWith(workspace) || gitDirPart(workspace.relativize(p));
  }

  private static boolean gitDirPart(Path relative) {
    for (Path part : relative) {
      if (isGitName(part.toString())) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@code .git} as some file system may open it: any letter case (macOS, Windows) and with trailing dots
   * or spaces (Windows drops them, so {@code .git.} opens {@code .git}).
   */
  static boolean isGitName(String name) {
    return name.replaceAll("[. ]+$", "").toLowerCase(Locale.ROOT).equals(".git");
  }

  boolean insideWorkspace(String path) {
    Path p = path == null ? null : real(workspace, path);
    return p != null && p.startsWith(workspace);
  }

  /**
   * {@code path} resolved against {@code base} with symlinks followed: the deepest part that exists is
   * replaced by its real path, the rest appended. Null when the path is not valid.
   */
  static Path real(Path base, String path) {
    try {
      Path p = Path.of(path);
      Path abs = (p.isAbsolute() ? p : base.resolve(p)).normalize();
      Path existing = abs;
      while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
        existing = existing.getParent();
      }
      if (existing == null) {
        return abs;
      }
      Path rest = existing.relativize(abs);
      return existing.toRealPath().resolve(rest).normalize();
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }
}
