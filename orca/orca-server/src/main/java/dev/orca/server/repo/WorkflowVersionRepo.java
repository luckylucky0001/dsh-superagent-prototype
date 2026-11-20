package dev.orca.server.repo;

import dev.orca.server.model.WorkflowVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowVersionRepo extends JpaRepository<WorkflowVersion, String> {
  Optional<WorkflowVersion> findByWorkflowIdAndVersion(String workflowId, int version);

  List<WorkflowVersion> findByWorkflowIdOrderByVersionDesc(String workflowId);
}
