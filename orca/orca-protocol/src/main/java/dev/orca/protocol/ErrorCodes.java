package dev.orca.protocol;

/** Provider-independent failure classes; workflows decide retries from these. */
public final class ErrorCodes {
  public static final String AUTH_REQUIRED = "AUTH_REQUIRED";
  public static final String USAGE_LIMIT = "USAGE_LIMIT";
  public static final String CONTEXT_EXCEEDED = "CONTEXT_EXCEEDED";
  public static final String SANDBOX = "SANDBOX";
  public static final String PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";
  public static final String PROVIDER_NOT_INSTALLED = "PROVIDER_NOT_INSTALLED";
  public static final String SESSION_NOT_FOUND = "SESSION_NOT_FOUND";
  public static final String PROTOCOL = "PROTOCOL";
  public static final String TIMEOUT = "TIMEOUT";
  public static final String CANCELLED = "CANCELLED";
  public static final String RUNTIME_LOST = "RUNTIME_LOST";
  public static final String AGENT_ERROR = "AGENT_ERROR";
  public static final String INVALID_OUTPUT = "INVALID_OUTPUT";
  public static final String MODEL_NOT_FOUND = "MODEL_NOT_FOUND";
  /** The runtime refused an assignment because it is still busy; the server queues the task again. */
  public static final String RUNTIME_BUSY = "RUNTIME_BUSY";

  private ErrorCodes() {}

  /** Failures worth an automatic retry of the same work. */
  public static boolean retryable(String code) {
    return PROVIDER_UNAVAILABLE.equals(code) || TIMEOUT.equals(code) || RUNTIME_LOST.equals(code)
        || PROTOCOL.equals(code) || AGENT_ERROR.equals(code) || INVALID_OUTPUT.equals(code);
  }
}
