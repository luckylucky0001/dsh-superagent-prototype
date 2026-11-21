package dev.fellowloop.server.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "project")
public class Project {
  @Id public String id;
  public String keyPrefix;
  public String name;
  public String description;
  public String repoUrl;
  public String defaultBranch;
  public int nextNumber;
  public long createdAt;
}
