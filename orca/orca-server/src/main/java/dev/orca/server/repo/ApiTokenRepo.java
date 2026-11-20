package dev.orca.server.repo;

import dev.orca.server.model.ApiToken;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiTokenRepo extends JpaRepository<ApiToken, String> {
  Optional<ApiToken> findByTokenHash(String hash);

  List<ApiToken> findByRuntimeId(String runtimeId);
}
