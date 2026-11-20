package dev.orca.server.repo;

import dev.orca.server.model.WorkflowRun;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRunRepo extends JpaRepository<WorkflowRun, String> {
  List<WorkflowRun> findTop100ByOrderByStartedAtDesc();

  List<WorkflowRun> findTop100ByWorkflowIdOrderByStartedAtDesc(String workflowId);

  List<WorkflowRun> findByIssueIdOrderByStartedAtAsc(String issueId);

  List<WorkflowRun> findByStatusIn(Collection<String> statuses);
}
