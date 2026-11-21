package dev.fellowloop.server.repo;

import dev.fellowloop.server.model.TaskEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskEventRepo extends JpaRepository<TaskEvent, Long> {
  List<TaskEvent> findByTaskIdAndSeqGreaterThanOrderBySeqAsc(String taskId, long seq);

  boolean existsByTaskIdAndSeq(String taskId, long seq);
}
