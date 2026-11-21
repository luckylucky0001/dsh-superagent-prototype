package dev.fellowloop.server.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "inbox_item")
public class InboxItem {
  @Id public String id;
  public String kind;
  public String status;
  public String title;
  public String body;
  public String formJson;
  public String answerJson;
  public String taskId;
  public String requestId;
  public String runId;
  public String nodeId;
  public String waitKey;
  public String issueId;
  public long createdAt;
  public Long resolvedAt;
  public String resolvedBy;
  /** {@code null} until first saved, so a new item is inserted rather than merged. */
  @jakarta.persistence.Version public Long version;
}
