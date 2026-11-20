package dev.orca.server.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Reusable instructions (Markdown) that agents can be given, e.g. a team's coding or release rules. */
@Entity
@Table(name = "skill")
public class Skill {
  @Id public String id;
  public String name;
  public String description;
  public String content;
  public long createdAt;
  public long updatedAt;
}
