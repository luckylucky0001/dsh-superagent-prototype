package dev.orca.server.support;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ErrorHandler {
  private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Map<String, Object>> api(ApiException e) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", e.getMessage());
    if (e.details() != null) {
      body.put("details", e.details());
    }
    return ResponseEntity.status(e.status()).body(body);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
    String msg = e.getBindingResult().getFieldErrors().stream()
        .map(f -> f.getField() + " " + f.getDefaultMessage()).findFirst().orElse("invalid request");
    return ResponseEntity.badRequest().body(Map.of("error", msg));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, Object>> illegal(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
  }

  @ExceptionHandler(IllegalStateException.class)
  ResponseEntity<Map<String, Object>> state(IllegalStateException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", String.valueOf(e.getMessage())));
  }

  /** A concurrent change won (e.g. someone else answered the inbox item first). */
  @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
  ResponseEntity<Map<String, Object>> conflict(org.springframework.dao.OptimisticLockingFailureException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(Map.of("error", "someone else changed this at the same time; reload and try again"));
  }
}
