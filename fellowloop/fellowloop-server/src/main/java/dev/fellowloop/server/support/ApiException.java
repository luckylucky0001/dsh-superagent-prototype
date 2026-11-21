package dev.fellowloop.server.support;

import org.springframework.http.HttpStatus;

/** An error with an HTTP status and a message safe to show users. */
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final Object details;

  public ApiException(HttpStatus status, String message) {
    this(status, message, null);
  }

  public ApiException(HttpStatus status, String message, Object details) {
    super(message);
    this.status = status;
    this.details = details;
  }

  public HttpStatus status() {
    return status;
  }

  public Object details() {
    return details;
  }

  public static ApiException notFound(String what) {
    return new ApiException(HttpStatus.NOT_FOUND, what + " not found");
  }

  public static ApiException bad(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, message);
  }
}
