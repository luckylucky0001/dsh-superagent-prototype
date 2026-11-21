package dev.fellowloop.server.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One security-relevant action: who did it, to what, when and from where. Append-only. */
@Entity
@Table(name = "audit_event")
public class AuditEvent {
  @Id public String id;
  public long at;
  public String actorId;
  public String actorName;
  public String action;
  public String targetType;
  public String targetId;
  public String detailJson;
  public String ip;
}
