package dev.orca.server.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent")
public class Agent {
  @Id public String id;
  public String name;
  public String description;
  public String provider;
  public String runtimeId;
  public String model;
  public String effort;
  public String instructions;
  public String permission;
  public String allowedCommandsJson;
  public String extraArgsJson;
  public int maxConcurrency;
  public int timeoutMinutes;
  public boolean archived;
  public long createdAt;
  public long updatedAt;
}
