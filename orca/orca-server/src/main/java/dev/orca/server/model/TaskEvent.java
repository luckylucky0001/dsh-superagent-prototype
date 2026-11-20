package dev.orca.server.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;

@Entity
@Table(name = "task_event")
public class TaskEvent {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
  public String taskId;
  public long seq;
  public String type;
  public long ts;
  public String dataJson;
}
