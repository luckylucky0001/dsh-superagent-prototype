package dev.orca.server.repo;

import dev.orca.server.model.ScheduleState;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleRepo extends JpaRepository<ScheduleState, String> {
  List<ScheduleState> findByWorkflowId(String workflowId);
}
