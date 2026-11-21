package dev.fellowloop.workflow.spi;

/** How the editor renders a configuration field. The engine itself treats all values as data. */
public enum FieldKind {
  STRING,
  TEXT,
  /** Multi-line text that may contain {@code {{ }}} templates. */
  TEMPLATE,
  /** A single expression, e.g. an edge condition. */
  EXPRESSION,
  NUMBER,
  BOOLEAN,
  SELECT,
  /** Reference to an Agent; may also be a template resolving to an agent id. */
  AGENT,
  CRON,
  /** A JSON value, e.g. a JSON Schema for structured output. */
  JSON,
  KEY_VALUE
}
