package dev.orca.server.repo;

import dev.orca.server.model.Skill;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillRepo extends JpaRepository<Skill, String> {
  List<Skill> findAllByOrderByNameAsc();

  Optional<Skill> findByNameIgnoreCase(String name);
}
