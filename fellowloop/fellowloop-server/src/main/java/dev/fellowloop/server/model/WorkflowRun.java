package dev.fellowloop.server.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "workflow_run")
public class WorkflowRun {
  @Id public String id;
  public String workflowId;
  public int version;
  public String status;
  public String triggerNodeId;
  public String issueId;
  /** {@code workflowId:nodeId:deliveryId} of the webhook delivery that started it, if it named one. */
  public String deliveryKey;
  public String stateJson;
  public String error;
  public long startedAt;
  public Long finishedAt;
  @Version public long lockVersion;
}
