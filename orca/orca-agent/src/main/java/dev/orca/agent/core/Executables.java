package dev.orca.agent.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Locating CLIs on PATH and running short probe commands. */
public final class Executables {

  private Executables() {}

  /** An explicit path wins; otherwise the first match on PATH. */
  public static Optional<Path> find(String explicit, String name) {
    if (explicit != null && !explicit.isBlank()) {
      Path p = Path.of(explicit);
      return Files.isExecutable(p) ? Optional.of(p) : Optional.empty();
    }
    String path = System.getenv("PATH");
    if (path == null) {
      return Optional.empty();
    }
    boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    for (String dir : path.split(File.pathSeparator)) {
      for (String candidate : windows ? List.of(name + ".exe", name + ".cmd", name) : List.of(name)) {
        Path p = Path.of(dir, candidate);
        if (Files.isExecutable(p) && !Files.isDirectory(p)) {
          return Optional.of(p);
        }
      }
    }
    return Optional.empty();
  }

  public record Output(int exitCode, String stdout, String stderr) {}

  /** Runs a command with a timeout and no stdin; never throws. */
  public static Output run(List<String> command, Map<String, String> env, long timeoutSeconds) {
    try {
      ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(command));
      pb.environment().putAll(env);
      pb.redirectInput(ProcessBuilder.Redirect.from(new File(isWindows() ? "NUL" : "/dev/null")));
      Path out = Files.createTempFile("orca-probe", ".out");
      Path err = Files.createTempFile("orca-probe", ".err");
      try {
        pb.redirectOutput(out.toFile()).redirectError(err.toFile());
        Process p = pb.start();
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
          p.descendants().forEach(ProcessHandle::destroyForcibly);
          p.destroyForcibly();
          return new Output(-1, Files.readString(out, StandardCharsets.UTF_8), "timed out");
        }
        return new Output(p.exitValue(), Files.readString(out, StandardCharsets.UTF_8), Files.readString(err, StandardCharsets.UTF_8));
      } finally {
        Files.deleteIfExists(out);
        Files.deleteIfExists(err);
      }
    } catch (IOException e) {
      return new Output(-1, "", e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Output(-1, "", "interrupted");
    }
  }

  private static final java.util.Set<String> SHELLS = java.util.Set.of("sh", "bash", "zsh", "dash");
  private static final java.util.Set<String> SHELL_DIRS = java.util.Set.of("", "/bin/", "/usr/bin/");

  /**
   * {@code /bin/bash -lc 'npm test'} becomes {@code npm test}; anything else is returned trimmed. Only
   * a system shell ({@code sh|bash|zsh|dash}, bare or in /bin or /usr/bin) is unwrapped, and only when
   * its script is one fully quoted word (quoted parts may be concatenated, as in {@code "a "'$b'}): an
   * unquoted part would be expanded by the outer shell, so such commands are left as they are and judged
   * whole.
   */
  public static String unwrapShell(String command) {
    if (command == null) {
      return null;
    }
    String c = command.trim();
    List<Word> words = tokenize(c);
    if (words == null || words.size() != 3) {
      return c;
    }
    Word shell = words.get(0);
    Word flag = words.get(1);
    Word script = words.get(2);
    if (shell.quoted() || flag.quoted() || !script.fullyQuoted()) {
      return c;
    }
    int slash = shell.text().lastIndexOf('/') + 1;
    if (!SHELLS.contains(shell.text().substring(slash)) || !SHELL_DIRS.contains(shell.text().substring(0, slash))) {
      return c;
    }
    if (!(flag.text().equals("-c") || flag.text().equals("-lc") || flag.text().equals("-cl"))) {
      return c;
    }
    return script.text().trim();
  }

  /** Splits a command line into words like a POSIX shell (quotes, backslashes); null if unbalanced. */
  static List<String> shellWords(String s) {
    List<Word> words = tokenize(s);
    return words == null ? null : words.stream().map(Word::text).toList();
  }

  /**
   * True when the script has characters a shell would expand outside quotes: globs, brace expansion,
   * tilde, subshells or comments. Such a command does not mean what its text says.
   */
  public static boolean hasUnquotedExpansion(String script) {
    boolean single = false;
    boolean dbl = false;
    for (int i = 0; i < script.length(); i++) {
      char ch = script.charAt(i);
      if (ch == '\\' && !single) {
        i++;
      } else if (ch == '\'' && !dbl) {
        single = !single;
      } else if (ch == '"' && !single) {
        dbl = !dbl;
      } else if (!single && !dbl && "*?[]{}~()#".indexOf(ch) >= 0) {
        return true;
      }
    }
    return false;
  }

  /** A word and how it was written: {@code quoted} if any part was quoted, {@code fullyQuoted} if all were. */
  record Word(String text, boolean quoted, boolean fullyQuoted) {}

  private static List<Word> tokenize(String s) {
    List<Word> words = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean inWord = false;
    boolean anyQuoted = false;
    boolean anyUnquoted = false;
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch == '\'') {
        int end = s.indexOf('\'', i + 1);
        if (end < 0) {
          return null;
        }
        cur.append(s, i + 1, end);
        i = end;
        inWord = true;
        anyQuoted = true;
      } else if (ch == '"') {
        int j = i + 1;
        for (; j < s.length() && s.charAt(j) != '"'; j++) {
          char d = s.charAt(j);
          if (d == '\\' && j + 1 < s.length() && "\"\\$`\n".indexOf(s.charAt(j + 1)) >= 0) {
            j++;
            if (s.charAt(j) != '\n') {
              cur.append(s.charAt(j));
            }
          } else {
            cur.append(d);
          }
        }
        if (j >= s.length()) {
          return null;
        }
        i = j;
        inWord = true;
        anyQuoted = true;
      } else if (ch == '\\' && i + 1 < s.length()) {
        cur.append(s.charAt(++i));
        inWord = true;
        anyUnquoted = true;
      } else if (Character.isWhitespace(ch)) {
        if (inWord) {
          words.add(new Word(cur.toString(), anyQuoted, anyQuoted && !anyUnquoted));
          cur.setLength(0);
          inWord = false;
          anyQuoted = false;
          anyUnquoted = false;
        }
      } else {
        cur.append(ch);
        inWord = true;
        anyUnquoted = true;
      }
    }
    if (inWord) {
      words.add(new Word(cur.toString(), anyQuoted, anyQuoted && !anyUnquoted));
    }
    return words;
  }

  /** Strips ANSI colour codes from CLI output. */
  public static String plain(String s) {
    return s == null ? "" : s.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "").trim();
  }

  /** The first {@code x.y.z} in a version banner. */
  public static String version(String banner) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+\\.\\d+\\.\\d+[\\w.-]*)").matcher(plain(banner));
    return m.find() ? m.group(1) : null;
  }

  /** Compares dotted numeric versions; non-numeric suffixes are ignored. */
  public static int compareVersions(String a, String b) {
    String[] x = a.split("[^0-9]+");
    String[] y = b.split("[^0-9]+");
    for (int i = 0; i < Math.max(x.length, y.length); i++) {
      int xi = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
      int yi = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
      if (xi != yi) {
        return Integer.compare(xi, yi);
      }
    }
    return 0;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }
}
