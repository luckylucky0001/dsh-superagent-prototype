package dev.orca.server.repo;

import dev.orca.server.model.ApiToken;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiTokenRepo extends JpaRepository<ApiToken, String> {
  Optional<ApiToken> findByTokenHash(String hash);

  List<ApiToken> findByRuntimeId(String runtimeId);

  List<ApiToken> findByKindAndLabel(String kind, String label);

  List<ApiToken> findByKindAndMemberIdOrderByCreatedAtDesc(String kind, String memberId);

  List<ApiToken> findByMemberId(String memberId);

  /** Marks an unused, unexpired registration code used; 1 for exactly one caller, however many race. */
  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query("update ApiToken t set t.usedAt = :now where t.tokenHash = :hash "
      + "and t.kind = :kind and t.usedAt is null and (t.expiresAt is null or t.expiresAt > :now)")
  int markUsed(@org.springframework.data.repository.query.Param("hash") String hash,
      @org.springframework.data.repository.query.Param("kind") String kind,
      @org.springframework.data.repository.query.Param("now") long now);
}
