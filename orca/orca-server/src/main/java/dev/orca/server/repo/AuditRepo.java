package dev.orca.server.repo;

import dev.orca.server.model.AuditEvent;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepo extends JpaRepository<AuditEvent, String> {
  List<AuditEvent> findByAtLessThanOrderByAtDesc(long before, Pageable page);

  List<AuditEvent> findByAtBetweenOrderByAtAsc(long from, long to);

  /** Keyset page, newest first: events before ({@code at}, {@code id}). */
  @org.springframework.data.jpa.repository.Query("select e from AuditEvent e where e.at < :at or (e.at = :at and e.id < :id) order by e.at desc, e.id desc")
  List<AuditEvent> page(long at, String id, Pageable page);

  /** Keyset page, oldest first, for the CSV export. */
  @org.springframework.data.jpa.repository.Query("select e from AuditEvent e where (e.at > :at or (e.at = :at and e.id > :id)) and e.at <= :to order by e.at asc, e.id asc")
  List<AuditEvent> after(long at, String id, long to, Pageable page);

  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.transaction.annotation.Transactional
  long deleteByAtLessThan(long at);
}
