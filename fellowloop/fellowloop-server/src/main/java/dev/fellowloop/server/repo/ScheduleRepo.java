package dev.fellowloop.server.repo;

import dev.fellowloop.server.model.ScheduleState;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleRepo extends JpaRepository<ScheduleState, String> {
  List<ScheduleState> findByWorkflowId(String workflowId);
}
