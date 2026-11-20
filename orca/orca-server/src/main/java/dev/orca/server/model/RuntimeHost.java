package dev.orca.server.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "runtime")
public class RuntimeHost {
  @Id public String id;
  public String name;
  public String hostname;
  public String os;
  public String daemonVersion;
  public int capacity;
  public boolean isolated;
  public String status;
  public Long lastSeenAt;
  public String providersJson;
  public long createdAt;
}
