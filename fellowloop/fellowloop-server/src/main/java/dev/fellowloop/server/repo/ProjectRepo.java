package dev.fellowloop.server.repo;

import dev.fellowloop.server.model.Project;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepo extends JpaRepository<Project, String> {
  Optional<Project> findByKeyPrefix(String prefix);

  List<Project> findAllByOrderByCreatedAtAsc();
}
