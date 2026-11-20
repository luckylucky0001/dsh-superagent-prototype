package dev.orca.cli;

import dev.orca.protocol.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code orca}: the command-line tool for people and CI. It talks to the server's REST API with a
 * personal access token ({@code orca login}); settings live in {@code ~/.orca/cli.json} (or
 * {@code ORCA_SERVER} / {@code ORCA_TOKEN}).
 */
public final class Main {

  public static final String VERSION = "0.3.0";

  private final PrintStream out;
  private final PrintStream err;
  private final Map<String, String> env;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private String server;
  private String token;
  private boolean json;

  public Main(PrintStream out, PrintStream err, Map<String, String> env) {
    this.out = out;
    this.err = err;
    this.env = env;
  }

  public static void main(String[] argv) {
    System.exit(new Main(System.out, System.err, System.getenv()).run(argv));
  }

  /** Runs one command; returns the process exit code (0 ok, 1 failed, 2 usage). */
  public int run(String[] argv) {
    Args a = Args.parse(argv);
    json = a.flag("json");
    if (a.positional.isEmpty() || a.flag("help") || a.flag("h")) {
      usage();
      return a.positional.isEmpty() && !a.flag("help") && !a.flag("h") ? 2 : 0;
    }
    try {
      String cmd = a.positional.get(0);
      if (cmd.equals("version")) {
        out.println("orca " + VERSION);
        return 0;
      }
      if (cmd.equals("login")) {
        return login(a);
      }
      if (cmd.equals("logout")) {
        Files.deleteIfExists(configFile());
        out.println("signed out (removed " + configFile() + ")");
        return 0;
      }
      loadConfig();
      return switch (cmd) {
        case "whoami" -> show(get("/api/auth/me"), me ->
            out.println(Json.str(me, "name") + " <" + Json.str(me, "email") + "> · " + Json.str(me, "role") + " · " + server));
        case "issues" -> issues(a);
        case "issue" -> issue(a);
        case "agents" -> table(get("/api/agents"), List.of("name", "provider", "model", "permission", "id"));
        case "runtimes" -> table(get("/api/runtimes"), List.of("name", "status", "daemonVersion", "capacity", "id"));
        case "ask" -> ask(a);
        case "task" -> task(a);
        case "workflows" -> table(get("/api/workflows"), List.of("name", "enabled", "id"));
        case "run" -> runWorkflow(a);
        case "runs" -> table(limit(get("/api/runs"), a), List.of("id", "workflowName", "status", "issueId", "startedAt"));
        case "inbox" -> table(get("/api/inbox" + (a.flag("all") ? "" : "?status=pending")),
            List.of("id", "kind", "title", "status", "createdAt"));
        case "approve", "reject" -> decide(a, cmd.equals("approve") ? "approved" : "rejected");
        case "answer" -> answer(a);
        default -> {
          err.println("unknown command: " + cmd + " (orca help)");
          yield 2;
        }
      };
    } catch (CliException e) {
      err.println("error: " + e.getMessage());
      return e.code;
    } catch (IOException e) {
      err.println("error: cannot reach " + server + ": " + e.getMessage());
      return 1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return 130;
    }
  }

  // ---------------------------------------------------------------- commands

  private int login(Args a) throws IOException, InterruptedException {
    server = trimSlash(a.opt("server", env.getOrDefault("ORCA_SERVER", "")));
    if (server.isBlank()) {
      throw new CliException(2, "--server is required, e.g. orca login --server https://orca.example.com --token orca_p_…");
    }
    token = a.opt("token", env.get("ORCA_TOKEN"));
    if (token == null || token.isBlank()) {
      String email = a.opt("email", null);
      if (email == null) {
        throw new CliException(2, "give --token (Settings → Account → API tokens) or --email to sign in with a password");
      }
      char[] pw = System.console() != null ? System.console().readPassword("Password for %s: ", email)
          : new String(System.in.readAllBytes(), StandardCharsets.UTF_8).strip().toCharArray();
      token = null;
      Object session = call("POST", "/api/auth/login", Map.of("email", email, "password", new String(pw)), false);
      token = Json.str(session, "token");
      String host = env.getOrDefault("HOSTNAME", "this machine");
      Object pat = call("POST", "/api/auth/tokens", Map.of("label", "orca CLI on " + host, "days", 90), true);
      call("POST", "/api/auth/logout", Map.of(), true);
      token = Json.str(pat, "token");
    }
    Object me = get("/api/auth/me");
    saveConfig();
    out.println("signed in to " + server + " as " + Json.str(me, "name") + "; settings saved to " + configFile());
    return 0;
  }

  private int issues(Args a) throws IOException, InterruptedException {
    String path = "/api/issues";
    if (a.opt("project", null) != null) {
      path += "?projectId=" + enc(projectId(a.opt("project", null)));
    }
    Object list = get(path);
    String status = a.opt("status", null);
    if (status != null && list instanceof List<?> l) {
      list = l.stream().filter(i -> status.equalsIgnoreCase(Json.str(i, "status"))).toList();
    }
    return table(list, List.of("key", "status", "priority", "title"));
  }

  private int issue(Args a) throws IOException, InterruptedException {
    String sub = a.arg(1, "issue show|create|comment|assign|status");
    return switch (sub) {
      case "show" -> {
        String key = a.arg(2, "issue show KEY");
        Object i = get("/api/issues/" + enc(key));
        if (json) {
          yield print(i);
        }
        out.println(Json.str(i, "key") + "  " + Json.str(i, "title"));
        out.println("status " + Json.str(i, "status") + " · priority " + Json.str(i, "priority")
            + (Json.str(i, "assigneeId") != null ? " · assignee " + Json.str(i, "assigneeType") + ":" + Json.str(i, "assigneeId") : ""));
        if (Json.str(i, "description") != null && !Json.str(i, "description").isBlank()) {
          out.println();
          out.println(Json.str(i, "description").strip());
        }
        Object comments = get("/api/issues/" + enc(key) + "/comments");
        if (comments instanceof List<?> l && !l.isEmpty()) {
          Map<String, String> names = names();
          out.println();
          for (Object c : l) {
            String author = names.getOrDefault(String.valueOf(Json.str(c, "authorId")), Json.str(c, "authorType"));
            out.println("— " + ("system".equals(Json.str(c, "authorType")) ? "Orca" : author) + " · "
                + time(Json.num(c, "createdAt", 0)));
            out.println(indent(String.valueOf(Json.str(c, "body")).strip()));
          }
        }
        yield 0;
      }
      case "create" -> {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", a.required("title"));
        String desc = a.opt("description", null);
        if (a.opt("description-file", null) != null) {
          desc = Files.readString(Path.of(a.opt("description-file", null)));
        }
        body.put("description", desc);
        if (a.opt("project", null) != null) {
          body.put("projectId", projectId(a.opt("project", null)));
        }
        body.put("priority", a.opt("priority", null));
        if (!a.all("label").isEmpty()) {
          body.put("labels", a.all("label"));
        }
        if (a.opt("assign", null) != null) {
          body.put("assigneeType", "agent");
          body.put("assigneeId", agentId(a.opt("assign", null)));
        }
        Object i = call("POST", "/api/issues", body, true);
        yield json ? print(i) : say("created " + Json.str(i, "key") + (a.opt("assign", null) != null
            ? "; " + a.opt("assign", null) + " starts working on it" : ""));
      }
      case "comment" -> {
        String key = a.arg(2, "issue comment KEY TEXT");
        String text = String.join(" ", a.positional.subList(Math.min(3, a.positional.size()), a.positional.size()));
        if (text.isBlank()) {
          throw new CliException(2, "nothing to say: orca issue comment KEY \"text\" (use @Agent to ask an agent)");
        }
        call("POST", "/api/issues/" + enc(key) + "/comments", Map.of("body", text), true);
        yield say("commented on " + key);
      }
      case "assign" -> {
        String key = a.arg(2, "issue assign KEY AGENT");
        String who = a.arg(3, "issue assign KEY AGENT");
        call("PATCH", "/api/issues/" + enc(key), Map.of("assigneeType", "agent", "assigneeId", agentId(who)), true);
        yield say("assigned " + key + " to " + who);
      }
      case "status" -> {
        String key = a.arg(2, "issue status KEY STATUS");
        String status = a.arg(3, "issue status KEY STATUS");
        call("PATCH", "/api/issues/" + enc(key), Map.of("status", status), true);
        yield say(key + " is now " + status);
      }
      default -> throw new CliException(2, "unknown: issue " + sub);
    };
  }

  private int ask(Args a) throws IOException, InterruptedException {
    String agent = a.arg(1, "ask AGENT PROMPT");
    String prompt = String.join(" ", a.positional.subList(Math.min(2, a.positional.size()), a.positional.size()));
    if (prompt.isBlank()) {
      throw new CliException(2, "what should " + agent + " do? orca ask " + agent + " \"…\"");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("prompt", prompt);
    body.put("followUp", a.opt("follow-up", null));
    Object t = call("POST", "/api/agents/" + enc(agentId(agent)) + "/ask", body, true);
    String id = Json.str(t, "id");
    if (a.flag("no-wait")) {
      return json ? print(t) : say(id);
    }
    err.println("task " + id + " (" + server + "/tasks/" + id + ")");
    return follow(id);
  }

  private int task(Args a) throws IOException, InterruptedException {
    String sub = a.arg(1, "task show|follow|cancel|steer ID");
    String id = a.arg(2, "task " + sub + " ID");
    return switch (sub) {
      case "show" -> show(get("/api/tasks/" + enc(id)), t -> {
        out.println(id + " · " + Json.str(t, "kind") + " · " + Json.str(t, "status") + " · " + Json.str(t, "model"));
        if (Json.str(t, "finalText") != null) {
          out.println();
          out.println(Json.str(t, "finalText").strip());
        }
        if (Json.str(t, "error") != null) {
          out.println("error: " + Json.str(t, "error"));
        }
      });
      case "follow" -> follow(id);
      case "cancel" -> {
        call("POST", "/api/tasks/" + enc(id) + "/cancel", Map.of(), true);
        yield say("cancelling " + id);
      }
      case "steer" -> {
        String text = String.join(" ", a.positional.subList(Math.min(3, a.positional.size()), a.positional.size()));
        call("POST", "/api/tasks/" + enc(id) + "/steer", Map.of("text", text), true);
        yield say("sent to " + id);
      }
      default -> throw new CliException(2, "unknown: task " + sub);
    };
  }

  private int runWorkflow(Args a) throws IOException, InterruptedException {
    String ref = a.arg(1, "run WORKFLOW [--input key=value]…");
    String id = workflowId(ref);
    Map<String, Object> input = new LinkedHashMap<>();
    for (String kv : a.all("input")) {
      int eq = kv.indexOf('=');
      if (eq <= 0) {
        throw new CliException(2, "--input wants key=value, got " + kv);
      }
      input.put(kv.substring(0, eq), kv.substring(eq + 1));
    }
    Object run = call("POST", "/api/workflows/" + enc(id) + "/runs", Map.of("input", input), true);
    String runId = Json.str(run, "id");
    err.println("run " + runId + " (" + server + "/runs/" + runId + ")");
    if (!a.flag("wait")) {
      return json ? print(run) : say(runId + " " + Json.str(run, "status"));
    }
    String status = Json.str(run, "status");
    while (!Set.of("succeeded", "failed", "cancelled").contains(status)) {
      Thread.sleep(3000);
      run = get("/api/runs/" + enc(runId));
      status = Json.str(run, "status");
    }
    say(runId + " " + status);
    return "succeeded".equals(status) ? 0 : 1;
  }

  private int decide(Args a, String decision) throws IOException, InterruptedException {
    String id = a.arg(1, (decision.equals("approved") ? "approve" : "reject") + " INBOX_ID [--comment …]");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("decision", decision);
    body.put("comment", a.opt("comment", ""));
    call("POST", "/api/inbox/" + enc(id) + "/answer", body, true);
    return say(decision + " " + id);
  }

  private int answer(Args a) throws IOException, InterruptedException {
    String id = a.arg(1, "answer INBOX_ID key=value…");
    Map<String, Object> answers = new LinkedHashMap<>();
    for (String kv : a.positional.subList(Math.min(2, a.positional.size()), a.positional.size())) {
      int eq = kv.indexOf('=');
      if (eq <= 0) {
        throw new CliException(2, "answers are key=value, got " + kv);
      }
      answers.put(kv.substring(0, eq), kv.substring(eq + 1));
    }
    call("POST", "/api/inbox/" + enc(id) + "/answer", Map.of("answers", answers), true);
    return say("answered " + id);
  }

  /** Prints a task's transcript as it happens; exit code follows the task's outcome. */
  private int follow(String id) throws IOException, InterruptedException {
    long after = 0;
    while (true) {
      Object events = get("/api/tasks/" + enc(id) + "/events?after=" + after);
      if (events instanceof List<?> l) {
        for (Object e : l) {
          after = Math.max(after, Json.num(e, "seq", after));
          printEvent(e);
        }
      }
      Object t = get("/api/tasks/" + enc(id));
      String status = Json.str(t, "status");
      if (Set.of("succeeded", "failed", "cancelled", "timed_out").contains(status)) {
        if (!json && Json.str(t, "error") != null) {
          err.println("error: " + Json.str(t, "error"));
        }
        err.println("task " + status);
        return "succeeded".equals(status) ? 0 : 1;
      }
      Thread.sleep(1500);
    }
  }

  private void printEvent(Object e) {
    if (json) {
      out.println(Json.write(e));
      return;
    }
    Map<String, Object> d = Json.obj(e, "data");
    switch (String.valueOf(Json.str(e, "type"))) {
      case "message" -> {
        String text = Json.str(d, "text");
        if (text != null && !text.isBlank()) {
          out.println(text.strip());
          out.println();
        }
      }
      case "tool.completed" -> err.println("  $ " + Json.str(d, "title")
          + (d.get("exitCode") != null ? "  (exit " + d.get("exitCode") + ")" : "  [" + Json.str(d, "status") + "]"));
      case "approval.resolved" -> err.println("  approval: " + Json.str(d, "decision") + " by " + Json.str(d, "by"));
      case "input.requested" -> err.println("  waiting for an answer in the inbox: " + Json.str(d, "title"));
      case "notice" -> err.println("  note: " + Json.str(d, "message"));
      default -> {
        // other events are shown in the web transcript
      }
    }
  }

  // ---------------------------------------------------------------- lookups

  /** Agent and member names by id, for showing who wrote what. */
  private Map<String, String> names() throws IOException, InterruptedException {
    Map<String, String> m = new LinkedHashMap<>();
    for (Object ag : (List<?>) get("/api/agents")) {
      m.put(Json.str(ag, "id"), Json.str(ag, "name"));
    }
    try {
      for (Object mem : (List<?>) get("/api/members")) {
        m.put(Json.str(mem, "id"), Json.str(mem, "name"));
      }
    } catch (CliException ignored) {
      // members may be visible to admins only
    }
    return m;
  }

  private String agentId(String ref) throws IOException, InterruptedException {
    for (Object ag : (List<?>) get("/api/agents")) {
      if (ref.equals(Json.str(ag, "id")) || ref.equalsIgnoreCase(Json.str(ag, "name"))) {
        return Json.str(ag, "id");
      }
    }
    throw new CliException(1, "no agent named " + ref + " (orca agents)");
  }

  private String projectId(String ref) throws IOException, InterruptedException {
    for (Object p : (List<?>) get("/api/projects")) {
      if (ref.equals(Json.str(p, "id")) || ref.equalsIgnoreCase(Json.str(p, "key"))) {
        return Json.str(p, "id");
      }
    }
    throw new CliException(1, "no project " + ref);
  }

  private String workflowId(String ref) throws IOException, InterruptedException {
    for (Object w : (List<?>) get("/api/workflows")) {
      if (ref.equals(Json.str(w, "id")) || ref.equalsIgnoreCase(Json.str(w, "name"))) {
        return Json.str(w, "id");
      }
    }
    throw new CliException(1, "no workflow " + ref + " (orca workflows)");
  }

  // ---------------------------------------------------------------- output

  private interface Printer {
    void print(Object value);
  }

  private int show(Object value, Printer human) {
    if (json) {
      return print(value);
    }
    human.print(value);
    return 0;
  }

  private int print(Object value) {
    try {
      out.println(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    } catch (RuntimeException e) {
      out.println(Json.write(value));
    }
    return 0;
  }

  private int say(String line) {
    out.println(line);
    return 0;
  }

  private Object limit(Object list, Args a) {
    int n = Integer.parseInt(a.opt("limit", "20"));
    return list instanceof List<?> l && l.size() > n ? l.subList(0, n) : list;
  }

  private int table(Object list, List<String> columns) {
    if (json) {
      return print(list);
    }
    List<?> rows = list instanceof List<?> l ? l : List.of();
    if (rows.isEmpty()) {
      out.println("(none)");
      return 0;
    }
    List<List<String>> cells = new ArrayList<>();
    for (Object r : rows) {
      List<String> line = new ArrayList<>();
      for (String c : columns) {
        Object v = r instanceof Map<?, ?> m ? m.get(c) : null;
        String s = v == null ? "—" : c.endsWith("At") && v instanceof Number n ? time(n.longValue()) : String.valueOf(v);
        line.add(s.length() > 60 ? s.substring(0, 57) + "..." : s);
      }
      cells.add(line);
    }
    int[] width = new int[columns.size()];
    for (int i = 0; i < columns.size(); i++) {
      width[i] = columns.get(i).length();
      for (List<String> line : cells) {
        width[i] = Math.max(width[i], line.get(i).length());
      }
    }
    StringBuilder head = new StringBuilder();
    for (int i = 0; i < columns.size(); i++) {
      head.append(pad(columns.get(i).toUpperCase(), width[i])).append(i + 1 < columns.size() ? "  " : "");
    }
    out.println(head.toString().stripTrailing());
    for (List<String> line : cells) {
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < line.size(); i++) {
        b.append(pad(line.get(i), width[i])).append(i + 1 < line.size() ? "  " : "");
      }
      out.println(b.toString().stripTrailing());
    }
    return 0;
  }

  private static String pad(String s, int w) {
    return s + " ".repeat(Math.max(0, w - s.length()));
  }

  private String time(long millis) {
    if (millis <= 0) {
      return "—";
    }
    ZoneId zone = ZoneId.of(env.getOrDefault("ORCA_TIMEZONE", "Asia/Singapore"));
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(Instant.ofEpochMilli(millis));
  }

  private static String indent(String s) {
    return "  " + s.replace("\n", "\n  ");
  }

  private static String firstNonBlank(String a, String b) {
    return a != null && !a.isBlank() ? a : b;
  }

  // ---------------------------------------------------------------- http

  private Object get(String path) throws IOException, InterruptedException {
    return call("GET", path, null, true);
  }

  private Object call(String method, String path, Object body, boolean auth) throws IOException, InterruptedException {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server + path)).timeout(Duration.ofSeconds(60))
        .header("Accept", "application/json").header("User-Agent", "orca-cli/" + VERSION);
    if (auth && token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    if (body == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
    }
    HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    if (res.statusCode() == 401) {
      throw new CliException(1, "not signed in or the token expired: orca login --server " + server + " --token …");
    }
    if (res.statusCode() >= 400) {
      String msg = res.body();
      try {
        Object e = Json.MAPPER.readValue(res.body(), Object.class);
        msg = firstNonBlank(Json.str(e, "error"), Json.str(e, "message"));
      } catch (RuntimeException ignored) {
        // not JSON
      }
      throw new CliException(1, res.statusCode() + " " + msg);
    }
    String text = res.body();
    return text == null || text.isBlank() ? Map.of() : Json.MAPPER.readValue(text, Object.class);
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String trimSlash(String s) {
    return s == null ? "" : s.trim().replaceAll("/+$", "");
  }

  // ---------------------------------------------------------------- settings

  private Path configFile() {
    String explicit = env.get("ORCA_CLI_CONFIG");
    if (explicit != null && !explicit.isBlank()) {
      return Path.of(explicit);
    }
    String home = env.get("ORCA_HOME");
    return (home != null && !home.isBlank() ? Path.of(home) : Path.of(System.getProperty("user.home"), ".orca"))
        .resolve("cli.json");
  }

  private void loadConfig() throws IOException {
    Path f = configFile();
    if (Files.exists(f) && Files.size(f) > 0) {
      try {
        Map<String, Object> m = Json.readMap(Files.readString(f));
        server = Json.str(m, "server");
        token = Json.str(m, "token");
      } catch (RuntimeException e) {
        err.println("warning: ignoring unreadable settings in " + f + "; sign in again with orca login");
      }
    }
    if (env.get("ORCA_SERVER") != null && !env.get("ORCA_SERVER").isBlank()) {
      server = env.get("ORCA_SERVER");
    }
    if (env.get("ORCA_TOKEN") != null && !env.get("ORCA_TOKEN").isBlank()) {
      token = env.get("ORCA_TOKEN");
    }
    server = trimSlash(server);
    if (server.isBlank() || token == null) {
      throw new CliException(1, "not signed in: orca login --server URL --token TOKEN (or set ORCA_SERVER and ORCA_TOKEN)");
    }
  }

  private void saveConfig() throws IOException {
    Path f = configFile();
    Files.createDirectories(f.toAbsolutePath().getParent());
    Files.writeString(f, Json.write(Map.of("server", server, "token", token)));
    try {
      Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException ignored) {
      // non-POSIX file system
    }
  }

  private void usage() {
    out.println("""
        orca — work with Orca from the command line                                   v%s

          orca login --server URL --token TOKEN     sign in with a personal access token
          orca login --server URL --email EMAIL     sign in with a password (creates a 90-day token)
          orca whoami | logout | version

          orca issues [--project KEY] [--status STATUS]
          orca issue show KEY
          orca issue create --title T [--project KEY] [--description D | --description-file F]
                            [--priority P] [--label L]… [--assign AGENT]
          orca issue comment KEY "text"            (mention @Agent to ask an agent)
          orca issue assign KEY AGENT
          orca issue status KEY STATUS

          orca agents | runtimes
          orca ask AGENT "prompt" [--follow-up TASK] [--no-wait]
          orca task show|follow|cancel ID
          orca task steer ID "text"

          orca workflows
          orca run WORKFLOW [--input key=value]… [--wait]
          orca runs [--limit N]

          orca inbox [--all]
          orca approve ID [--comment C] | reject ID [--comment C]
          orca answer ID key=value…

        Add --json to any command for machine-readable output. Settings: ~/.orca/cli.json,
        or ORCA_SERVER and ORCA_TOKEN in the environment (handy in CI).""".formatted(VERSION));
  }

  /** A failure the user can act on; {@code code} is the exit status. */
  static final class CliException extends RuntimeException {
    final int code;

    CliException(int code, String message) {
      super(message);
      this.code = code;
    }
  }

  /** {@code --key value}, {@code --key=value}, repeated keys and bare flags; the rest are positional. */
  static final class Args {
    final List<String> positional = new ArrayList<>();
    final Map<String, List<String>> options = new LinkedHashMap<>();

    static Args parse(String[] argv) {
      Args a = new Args();
      for (int i = 0; i < argv.length; i++) {
        String s = argv[i];
        if (s.equals("--")) {
          for (int j = i + 1; j < argv.length; j++) {
            a.positional.add(argv[j]);
          }
          break;
        }
        if (s.startsWith("--") && s.length() > 2) {
          String key = s.substring(2);
          int eq = key.indexOf('=');
          if (eq >= 0) {
            a.options.computeIfAbsent(key.substring(0, eq), k -> new ArrayList<>()).add(key.substring(eq + 1));
          } else if (i + 1 < argv.length && !argv[i + 1].startsWith("--") && !FLAGS.contains(key)) {
            a.options.computeIfAbsent(key, k -> new ArrayList<>()).add(argv[++i]);
          } else {
            a.options.computeIfAbsent(key, k -> new ArrayList<>()).add("true");
          }
        } else if (s.equals("-h")) {
          a.options.computeIfAbsent("h", k -> new ArrayList<>()).add("true");
        } else {
          a.positional.add(s);
        }
      }
      return a;
    }

    private static final Set<String> FLAGS = Set.of("json", "help", "all", "wait", "no-wait");

    boolean flag(String key) {
      return options.containsKey(key);
    }

    String opt(String key, String fallback) {
      List<String> v = options.get(key);
      return v == null || v.isEmpty() ? fallback : v.get(v.size() - 1);
    }

    String required(String key) {
      String v = opt(key, null);
      if (v == null || v.isBlank()) {
        throw new CliException(2, "--" + key + " is required");
      }
      return v;
    }

    List<String> all(String key) {
      return options.getOrDefault(key, List.of());
    }

    String arg(int index, String usage) {
      if (positional.size() <= index) {
        throw new CliException(2, "usage: orca " + usage);
      }
      return positional.get(index);
    }
  }
}
