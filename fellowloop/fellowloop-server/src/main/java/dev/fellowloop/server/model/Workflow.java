package dev.fellowloop.server.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "workflow")
public class Workflow {
  @Id public String id;
  public String name;
  public String description;
  public boolean enabled;
  public boolean builtIn;
  public int currentVersion;
  public String definitionJson;
  public long createdAt;
  public long updatedAt;
}
