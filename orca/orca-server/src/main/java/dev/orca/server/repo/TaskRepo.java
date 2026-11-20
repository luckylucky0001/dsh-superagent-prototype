package dev.orca.server.repo;

import dev.orca.server.model.Task;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepo extends JpaRepository<Task, String> {
  List<Task> findByStatusOrderByQueuedAtAsc(String status);

  List<Task> findByStatusIn(Collection<String> statuses);

  List<Task> findByIssueIdOrderByQueuedAtAsc(String issueId);

  List<Task> findByAgentIdOrderByQueuedAtDesc(String agentId);

  List<Task> findTop100ByOrderByQueuedAtDesc();

  List<Task> findByQueuedAtGreaterThan(long since);

  List<Task> findByAgentIdAndIssueIdAndStatusOrderByFinishedAtDesc(String agentId, String issueId, String status);

  long countByRuntimeIdAndStatusIn(String runtimeId, Collection<String> statuses);

  long countByAgentIdAndStatusIn(String agentId, Collection<String> statuses);

  long countByAgentIdAndIssueIdAndStatusIn(String agentId, String issueId, Collection<String> statuses);

  long countByWorkspaceKeyAndStatusIn(String workspaceKey, Collection<String> statuses);
}
