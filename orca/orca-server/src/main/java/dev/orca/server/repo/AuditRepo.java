package dev.orca.server.repo;

import dev.orca.server.model.AuditEvent;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepo extends JpaRepository<AuditEvent, String> {
  List<AuditEvent> findByAtLessThanOrderByAtDesc(long before, Pageable page);

  List<AuditEvent> findByAtBetweenOrderByAtAsc(long from, long to);
}
