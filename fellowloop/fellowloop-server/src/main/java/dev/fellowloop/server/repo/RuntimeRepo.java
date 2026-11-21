package dev.fellowloop.server.repo;

import dev.fellowloop.server.model.RuntimeHost;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuntimeRepo extends JpaRepository<RuntimeHost, String> {
  List<RuntimeHost> findAllByOrderByNameAsc();
}
