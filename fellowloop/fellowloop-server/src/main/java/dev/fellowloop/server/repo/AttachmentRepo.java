package dev.fellowloop.server.repo;

import dev.fellowloop.server.model.Attachment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepo extends JpaRepository<Attachment, String> {
  List<Attachment> findByIssueIdOrderByCreatedAtAsc(String issueId);
}
