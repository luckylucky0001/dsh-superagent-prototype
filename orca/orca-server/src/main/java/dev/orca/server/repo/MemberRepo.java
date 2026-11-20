package dev.orca.server.repo;

import dev.orca.server.model.Member;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepo extends JpaRepository<Member, String> {
  Optional<Member> findByEmailIgnoreCase(String email);
}
