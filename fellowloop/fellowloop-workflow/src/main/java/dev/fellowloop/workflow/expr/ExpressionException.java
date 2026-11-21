package dev.fellowloop.workflow.expr;

/** A syntax or evaluation error in an expression or template. */
public class ExpressionException extends RuntimeException {
  public ExpressionException(String message) {
    super(message);
  }
}
