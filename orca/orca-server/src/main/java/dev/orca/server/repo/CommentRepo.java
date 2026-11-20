package dev.orca.server.repo;

import dev.orca.server.model.Comment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepo extends JpaRepository<Comment, String> {
  List<Comment> findByIssueIdOrderByCreatedAtAsc(String issueId);
}
