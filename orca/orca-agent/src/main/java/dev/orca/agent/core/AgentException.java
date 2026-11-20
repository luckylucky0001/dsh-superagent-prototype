package dev.orca.agent.core;

/** A failure with a provider-independent {@link dev.orca.protocol.ErrorCodes code}. */
public class AgentException extends Exception {
  private final String code;

  public AgentException(String code, String message) {
    super(message);
    this.code = code;
  }

  public AgentException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
