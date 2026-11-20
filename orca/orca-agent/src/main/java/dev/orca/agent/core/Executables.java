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
