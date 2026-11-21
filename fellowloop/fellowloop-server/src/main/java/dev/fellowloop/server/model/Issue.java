package dev.fellowloop.server.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "issue")
public class Issue {
  @Id public String id;
  public String projectId;
  public int number;
  public String issueKey;
  public String title;
  public String description;
  public String status;
  public String priority;
  public String assigneeType;
  public String assigneeId;
  public String labelsJson;
  public String createdBy;
  public long createdAt;
  public long updatedAt;
}
