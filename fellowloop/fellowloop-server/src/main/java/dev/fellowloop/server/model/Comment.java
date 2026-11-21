package dev.fellowloop.server.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "comment")
public class Comment {
  @Id public String id;
  public String issueId;
  public String authorType;
  public String authorId;
  public String body;
  public String taskId;
  public long createdAt;
}
