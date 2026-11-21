package dev.fellowloop.workflow.spi;

import java.util.Map;

/**
 * A reusable workflow building block. Implementations are stateless; anything a waiting node needs
 * later lives in its output or in the system the wait key points to.
 */
public interface NodeComponent {

  NodeDescriptor descriptor();

  /** Runs the node. Trigger components are never executed; their output is the trigger payload. */
  NodeResult execute(NodeContext ctx) throws Exception;

  /**
   * Resumes a node that returned {@link NodeResult.Waiting}. The default treats the payload as the
   * output; {@code _port} selects the output port and {@code _error} fails the node.
   */
  default NodeResult onSignal(NodeContext ctx, Map<String, Object> payload) {
    Object error = payload.get("_error");
    if (error != null) {
      return NodeResult.failed(String.valueOf(error));
    }
    Object port = payload.get("_port");
    return port == null ? NodeResult.done(payload) : NodeResult.done(payload, String.valueOf(port));
  }

  /** Called when the run is cancelled while this node waits. */
  default void cancel(NodeContext ctx) {}
}
