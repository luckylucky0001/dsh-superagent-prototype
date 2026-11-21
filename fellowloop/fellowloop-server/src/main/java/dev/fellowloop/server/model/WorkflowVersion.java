package dev.fellowloop.server.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "workflow_version")
public class WorkflowVersion {
  @Id public String id;
  public String workflowId;
  public int version;
  public String definitionJson;
  public String createdBy;
  public long createdAt;
}
