package dev.fellowloop.workflow.spi;

import dev.fellowloop.workflow.model.EdgeDef;
import java.util.List;
import java.util.Map;

/** What a component returns from {@link NodeComponent#execute} or {@link NodeComponent#onSignal}. */
public sealed interface NodeResult {

  /** The node finished; edges leaving {@code ports} are taken. */
  record Completed(Map<String, Object> output, List<String> ports) implements NodeResult {
    public Completed {
      output = output == null ? Map.of() : output;
      ports = ports == null || ports.isEmpty() ? List.of(EdgeDef.DEFAULT_PORT) : List.copyOf(ports);
    }
  }

  /**
   * The node waits for an external signal, such as an agent task or a human decision.
   *
   * @param waitKey opaque handle the component uses to correlate the later signal, e.g. a task id
   */
  record Waiting(String waitKey, Map<String, Object> output) implements NodeResult {
    public Waiting {
      output = output == null ? Map.of() : output;
    }
  }

  /** The node failed. Non-retryable failures skip the node's retries. */
  record Failed(String error, boolean retryable, Map<String, Object> output) implements NodeResult {
    public Failed {
      output = output == null ? Map.of() : output;
    }
  }

  static Completed done(Map<String, Object> output) {
    return new Completed(output, List.of(EdgeDef.DEFAULT_PORT));
  }

  static Completed done(Map<String, Object> output, String port) {
    return new Completed(output, List.of(port));
  }

  static Waiting waiting(String waitKey) {
    return new Waiting(waitKey, Map.of());
  }

  static Failed failed(String error) {
    return new Failed(error, true, Map.of());
  }

  static Failed fatal(String error) {
    return new Failed(error, false, Map.of());
  }
}
