package dev.orca.agent.core;

import dev.orca.protocol.ErrorCodes;
import dev.orca.protocol.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Newline-delimited JSON-RPC 2.0 over a child process's stdio, as both ACP and the Codex app-server
 * speak it. Requests from the agent are handled on virtual threads, so a handler may block (for
 * example while a person decides) without stalling the stream.
 */
public final class JsonRpcConnection implements AutoCloseable {

  /** Handles a request the agent sends; returns the result or throws {@link RpcError}. */
  @FunctionalInterface
  public interface RequestHandler {
    Object handle(String method, Map<String, Object> params) throws RpcError;
  }

  public static final class RpcError extends Exception {
    private final long code;
    private final Object data;

    public RpcError(long code, String message, Object data) {
      super(message);
      this.code = code;
      this.data = data;
    }

    public long code() {
      return code;
    }

    public Object data() {
      return data;
    }
  }

  private static final Logger log = LoggerFactory.getLogger(JsonRpcConnection.class);
  private static final int STDERR_TAIL = 60;

  private final Process process;
  private final String name;
  private final boolean jsonrpcField;
  private final OutputStream stdin;
  private final Map<Long, CompletableFuture<Object>> pending = new ConcurrentHashMap<>();
  private final AtomicLong ids = new AtomicLong();
  private final Deque<String> stderrTail = new ArrayDeque<>();
  private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
  private final CompletableFuture<Void> closed = new CompletableFuture<>();
  private volatile BiConsumer<String, Map<String, Object>> notificationListener = (m, p) -> {};
  private volatile RequestHandler requestHandler = (m, p) -> {
    throw new RpcError(-32601, "method not supported by client: " + m, null);
  };
  private volatile java.util.function.Consumer<String> rawListener;

  /** Every CLI process still open, so a shutting-down daemon can make sure none outlives it. */
  private static final java.util.Set<JsonRpcConnection> LIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** Kills every open CLI process and its children at once (daemon shutdown: Java does not kill children). */
  public static void killAll() {
    for (JsonRpcConnection c : LIVE) {
      c.process.descendants().forEach(ProcessHandle::destroyForcibly);
      c.process.destroyForcibly();
    }
  }

  public JsonRpcConnection(Process process, String name, boolean jsonrpcField) {
    this.process = process;
    LIVE.add(this);
    this.name = name;
    this.jsonrpcField = jsonrpcField;
    this.stdin = process.getOutputStream();
    Thread.ofPlatform().daemon().name(name + "-stdout").start(this::readStdout);
    Thread.ofPlatform().daemon().name(name + "-stderr").start(this::readStderr);
  }

  public void onNotification(BiConsumer<String, Map<String, Object>> listener) {
    this.notificationListener = listener;
  }

  public void onRequest(RequestHandler handler) {
    this.requestHandler = handler;
  }

  /** Observes every raw line in both directions, prefixed with {@code <} or {@code >}; for recording. */
  public void onRaw(java.util.function.Consumer<String> listener) {
    this.rawListener = listener;
  }

  public CompletableFuture<Object> request(String method, Object params) {
    long id = ids.incrementAndGet();
    CompletableFuture<Object> f = new CompletableFuture<>();
    pending.put(id, f);
    Map<String, Object> msg = new LinkedHashMap<>();
    if (jsonrpcField) {
      msg.put("jsonrpc", "2.0");
    }
    msg.put("id", id);
    msg.put("method", method);
    if (params != null) {
      msg.put("params", params);
    }
    try {
      send(msg);
    } catch (IOException e) {
      pending.remove(id);
      f.completeExceptionally(e);
    }
    return f;
  }

  /** Sends a request and waits for its result. */
  public Object call(String method, Object params, Duration timeout) throws AgentException {
    CompletableFuture<Object> f = request(method, params);
    try {
      return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      f.cancel(false);
      pending.values().remove(f);
      throw new AgentException(ErrorCodes.TIMEOUT, name + ": " + method + " timed out after " + timeout);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      pending.values().remove(f);
      throw new AgentException(ErrorCodes.CANCELLED, name + ": interrupted during " + method);
    } catch (ExecutionException e) {
      Throwable c = e.getCause();
      if (c instanceof RpcError r) {
        throw new AgentException(ErrorCodes.PROTOCOL, name + ": " + method + " failed: " + r.getMessage()
            + (r.data() == null ? "" : " " + Json.write(r.data())), r);
      }
      throw new AgentException(ErrorCodes.PROVIDER_UNAVAILABLE, name + ": " + method + " failed: " + c.getMessage(), c);
    }
  }

  public void notify(String method, Object params) throws IOException {
    Map<String, Object> msg = new LinkedHashMap<>();
    if (jsonrpcField) {
      msg.put("jsonrpc", "2.0");
    }
    msg.put("method", method);
    if (params != null) {
      msg.put("params", params);
    }
    send(msg);
  }

  public boolean alive() {
    return process.isAlive() && !closed.isDone();
  }

  /** Completes when the process's stdout closes. */
  public CompletableFuture<Void> closedFuture() {
    return closed;
  }

  public synchronized String stderrTail() {
    return String.join("\n", stderrTail);
  }

  public long pid() {
    return process.pid();
  }

  @Override
  public void close() {
    // snapshot first: once the CLI exits its children (running commands, MCP servers) are re-parented
    java.util.List<ProcessHandle> children = process.descendants().toList();
    try {
      stdin.close();
    } catch (IOException ignored) {
      // already closed
    }
    try {
      if (!process.waitFor(3, TimeUnit.SECONDS)) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          process.descendants().forEach(ProcessHandle::destroyForcibly);
          process.destroyForcibly();
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      children.forEach(ProcessHandle::destroyForcibly);
    }
    // re-parented children (a command the agent started, an MCP server): ask, then insist
    java.util.List<ProcessHandle> left = children.stream().filter(ProcessHandle::isAlive).toList();
    left.forEach(ProcessHandle::destroy);
    for (ProcessHandle h : left) {
      try {
        h.onExit().get(2, TimeUnit.SECONDS);
      } catch (Exception notYet) {
        h.destroyForcibly();
      }
    }
    LIVE.remove(this);
    handlers.shutdownNow();
    failPending(new IOException(name + " closed"));
  }

  // ---------------------------------------------------------------- internals

  private void send(Map<String, Object> msg) throws IOException {
    String line = Json.write(msg);
    java.util.function.Consumer<String> raw = rawListener;
    if (raw != null) {
      raw.accept("> " + line);
    }
    byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
    synchronized (stdin) {
      stdin.write(bytes);
      stdin.flush();
    }
  }

  private void readStdout() {
    try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        java.util.function.Consumer<String> raw = rawListener;
        if (raw != null) {
          raw.accept("< " + line);
        }
        Map<String, Object> msg;
        try {
          msg = Json.readMap(line);
        } catch (RuntimeException e) {
          log.debug("{}: ignoring non-JSON line: {}", name, line.length() > 200 ? line.substring(0, 200) : line);
          continue;
        }
        dispatch(msg);
      }
    } catch (IOException e) {
      log.debug("{}: stdout closed: {}", name, e.toString());
    } finally {
      failPending(new IOException(name + " exited" + exitSuffix()));
      closed.complete(null);
    }
  }

  @SuppressWarnings("unchecked")
  private void dispatch(Map<String, Object> msg) {
    Object id = msg.get("id");
    String method = (String) msg.get("method");
    Map<String, Object> params = msg.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
    if (method == null && id != null) {
      CompletableFuture<Object> f = pending.remove(toLong(id));
      if (f == null) {
        return;
      }
      if (msg.containsKey("error")) {
        Map<String, Object> err = Json.obj(msg, "error");
        f.completeExceptionally(new RpcError(Json.num(err, "code", -1), Json.str(err, "message"), err.get("data")));
      } else {
        f.complete(msg.get("result"));
      }
      return;
    }
    if (method != null && id != null) {
      handlers.submit(() -> respond(id, method, params));
      return;
    }
    if (method != null) {
      try {
        notificationListener.accept(method, params);
      } catch (RuntimeException e) {
        log.warn("{}: notification handler failed for {}", name, method, e);
      }
    }
  }

  private void respond(Object id, String method, Map<String, Object> params) {
    Map<String, Object> reply = new LinkedHashMap<>();
    if (jsonrpcField) {
      reply.put("jsonrpc", "2.0");
    }
    reply.put("id", id);
    try {
      Object result = requestHandler.handle(method, params);
      reply.put("result", result == null ? Map.of() : result);
    } catch (RpcError e) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("code", e.code());
      err.put("message", e.getMessage());
      reply.put("error", err);
    } catch (RuntimeException e) {
      log.warn("{}: request handler failed for {}", name, method, e);
      reply.put("error", Map.of("code", -32603, "message", String.valueOf(e.getMessage())));
    }
    try {
      send(reply);
    } catch (IOException e) {
      log.debug("{}: could not reply to {}: {}", name, method, e.toString());
    }
  }

  private void readStderr() {
    try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        synchronized (this) {
          stderrTail.addLast(line.length() > 500 ? line.substring(0, 500) : line);
          while (stderrTail.size() > STDERR_TAIL) {
            stderrTail.removeFirst();
          }
        }
      }
    } catch (IOException ignored) {
      // process ended
    }
  }

  private void failPending(Exception e) {
    pending.values().forEach(f -> f.completeExceptionally(e));
    pending.clear();
  }

  private String exitSuffix() {
    try {
      if (process.waitFor(200, TimeUnit.MILLISECONDS)) {
        return " with code " + process.exitValue();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return "";
  }

  private static long toLong(Object id) {
    if (id instanceof Number n) {
      return n.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(id));
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
