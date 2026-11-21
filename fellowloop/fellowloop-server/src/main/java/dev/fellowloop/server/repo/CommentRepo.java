package dev.fellowloop.server.repo;

import dev.fellowloop.server.model.Comment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepo extends JpaRepository<Comment, String> {
  List<Comment> findByIssueIdOrderByCreatedAtAsc(String issueId);
}
