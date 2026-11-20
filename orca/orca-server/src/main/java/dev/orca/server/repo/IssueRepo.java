package dev.orca.server.repo;

import dev.orca.server.model.Issue;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IssueRepo extends JpaRepository<Issue, String> {
  List<Issue> findByProjectIdOrderByUpdatedAtDesc(String projectId);

  List<Issue> findAllByOrderByUpdatedAtDesc();

  Optional<Issue> findByIssueKeyIgnoreCase(String key);
}
