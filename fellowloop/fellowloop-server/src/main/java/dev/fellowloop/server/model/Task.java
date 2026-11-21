package dev.fellowloop.server.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "task")
public class Task {
  @Id public String id;
  public String agentId;
  public String runtimeId;
  public String issueId;
  /** Without an issue: the project whose repository the task works in (on a branch of its own). */
  public String projectId;
  public String provider;
  public String kind;
  public String originJson;
  public String prompt;
  public String recap;
  public String outputSchemaJson;
  /** Native review target (Codex review/start) instead of a prompt; null for a normal turn. */
  public String reviewJson;
  public String status;
  public String statusDetail;
  public String sessionRef;
  public String resumeRef;
  public String workspaceKey;
  public String model;
  public String finalText;
  public String structuredJson;
  public String errorCode;
  public String error;
  public String usageJson;
  public long lastSeq;
  public Long leaseUntil;
  public long queuedAt;
  public Long startedAt;
  public Long finishedAt;
  @Version public long version;
}
