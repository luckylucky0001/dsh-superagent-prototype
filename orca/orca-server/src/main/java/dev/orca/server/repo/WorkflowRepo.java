package dev.orca.server.repo;

import dev.orca.server.model.Workflow;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRepo extends JpaRepository<Workflow, String> {
  List<Workflow> findAllByOrderByCreatedAtAsc();

  List<Workflow> findByEnabledTrue();
}
