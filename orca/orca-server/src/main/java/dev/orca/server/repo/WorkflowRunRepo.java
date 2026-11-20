package dev.orca.server.repo;

import dev.orca.server.model.WorkflowRun;
import java.util.Collection;
import java.util.List;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowRunRepo extends JpaRepository<WorkflowRun, String> {
  List<WorkflowRun> findTop100ByOrderByStartedAtDesc();

  List<WorkflowRun> findTop100ByWorkflowIdOrderByStartedAtDesc(String workflowId);

  List<WorkflowRun> findByIssueIdOrderByStartedAtAsc(String issueId);

  List<WorkflowRun> findByStatusIn(Collection<String> statuses);

  /** Row lock held until the transaction ends; serialises every state change of one run. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from WorkflowRun r where r.id = :id")
  Optional<WorkflowRun> lockById(@Param("id") String id);
}
