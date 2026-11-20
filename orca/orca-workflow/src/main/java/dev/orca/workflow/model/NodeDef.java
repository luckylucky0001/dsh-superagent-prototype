package dev.orca.workflow.model;

import java.util.Map;

/**
 * One node of a workflow graph.
 *
 * @param id             unique within the definition; also the variable name under {@code nodes.<id>}
 * @param type           component type, e.g. {@code agent.run}
 * @param name           display name
 * @param config         component configuration; string values may contain {@code {{ expression }}} templates
 * @param retries        how many times a failed execution is retried before the node fails
 * @param timeoutSeconds 0 means the component default
 * @param onError        {@link OnError#FAIL} fails the run; {@link OnError#CONTINUE} routes through the {@code error} port
 * @param ui             editor-only data such as the canvas position; ignored by the engine
 */
public record NodeDef(
    String id,
    String type,
    String name,
    Map<String, Object> config,
    int retries,
    int timeoutSeconds,
    OnError onError,
    Map<String, Object> ui) {

  public NodeDef {
    config = config == null ? Map.of() : config;
    onError = onError == null ? OnError.FAIL : onError;
    ui = ui == null ? Map.of() : ui;
    retries = Math.max(0, retries);
    timeoutSeconds = Math.max(0, timeoutSeconds);
  }

  public NodeDef(String id, String type, Map<String, Object> config) {
    this(id, type, id, config, 0, 0, OnError.FAIL, Map.of());
  }

  public String displayName() {
    return name == null || name.isBlank() ? id : name;
  }

  public enum OnError {
    FAIL,
    CONTINUE
  }
}
