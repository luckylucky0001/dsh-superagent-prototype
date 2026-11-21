package dev.fellowloop.server.task;

import java.util.Map;

/**
 * What a workflow node (or the API) asks an agent to do.
 *
 * @param sessionMode {@code new} or {@code continue} (resume the agent's last session on the issue)
 * @param origin      where the result goes, e.g. {@code {runId, nodeId, waitKey}}
 */
public record TaskRequest(String agentId, String issueId, String prompt, String kind, Map<String, Object> outputSchema,
    String sessionMode, Map<String, Object> origin, Integer timeoutMinutes, Map<String, Object> review,
    String projectId) {

  public TaskRequest(String agentId, String issueId, String prompt, String kind, Map<String, Object> outputSchema,
      String sessionMode, Map<String, Object> origin, Integer timeoutMinutes, Map<String, Object> review) {
    this(agentId, issueId, prompt, kind, outputSchema, sessionMode, origin, timeoutMinutes, review, null);
  }

  public TaskRequest(String agentId, String issueId, String prompt, String kind, Map<String, Object> outputSchema,
      String sessionMode, Map<String, Object> origin, Integer timeoutMinutes) {
    this(agentId, issueId, prompt, kind, outputSchema, sessionMode, origin, timeoutMinutes, null);
  }
}
