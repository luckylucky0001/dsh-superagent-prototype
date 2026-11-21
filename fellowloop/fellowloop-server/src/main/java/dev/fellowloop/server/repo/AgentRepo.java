package dev.fellowloop.server.repo;

import dev.fellowloop.server.model.Agent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepo extends JpaRepository<Agent, String> {
  Optional<Agent> findByNameIgnoreCase(String name);

  List<Agent> findByArchivedFalseOrderByNameAsc();
}
