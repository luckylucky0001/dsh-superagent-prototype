package dev.orca.server.repo;

import dev.orca.server.model.Attachment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepo extends JpaRepository<Attachment, String> {
  List<Attachment> findByIssueIdOrderByCreatedAtAsc(String issueId);
}
