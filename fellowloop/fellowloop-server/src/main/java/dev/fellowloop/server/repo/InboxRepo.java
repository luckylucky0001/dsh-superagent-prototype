package dev.fellowloop.server.repo;

import dev.fellowloop.server.model.InboxItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxRepo extends JpaRepository<InboxItem, String> {
  List<InboxItem> findByStatusOrderByCreatedAtDesc(String status);

  List<InboxItem> findTop200ByOrderByCreatedAtDesc();

  Optional<InboxItem> findByTaskIdAndRequestId(String taskId, String requestId);

  List<InboxItem> findByRunIdAndStatus(String runId, String status);

  List<InboxItem> findByTaskIdAndStatus(String taskId, String status);
}
