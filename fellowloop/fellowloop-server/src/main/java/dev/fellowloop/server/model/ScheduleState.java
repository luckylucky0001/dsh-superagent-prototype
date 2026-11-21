package dev.fellowloop.server.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Next fire time of one schedule trigger; id is {@code workflowId:nodeId}. */
@Entity
@Table(name = "schedule_state")
public class ScheduleState {
  @Id public String id;
  public String workflowId;
  public String nodeId;
  public long nextFireAt;
  /** {@code cron|zone} that {@link #nextFireAt} was computed from. */
  public String spec;
}
