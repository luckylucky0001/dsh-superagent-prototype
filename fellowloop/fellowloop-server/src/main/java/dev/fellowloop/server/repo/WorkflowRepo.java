package dev.fellowloop.server.repo;

import dev.fellowloop.server.model.Workflow;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRepo extends JpaRepository<Workflow, String> {
  List<Workflow> findAllByOrderByCreatedAtAsc();

  List<Workflow> findByEnabledTrue();
}
