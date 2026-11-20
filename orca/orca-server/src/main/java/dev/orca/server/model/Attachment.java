package dev.orca.server.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "attachment")
public class Attachment {
  @Id public String id;
  public String issueId;
  public String name;
  public String contentType;
  public long sizeBytes;
  public String storagePath;
  public String createdBy;
  public long createdAt;
}
