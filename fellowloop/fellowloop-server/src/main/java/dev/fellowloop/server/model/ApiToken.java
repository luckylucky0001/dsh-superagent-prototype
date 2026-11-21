package dev.fellowloop.server.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "api_token")
public class ApiToken {
  @Id public String id;
  public String kind;
  public String tokenHash;
  public String memberId;
  public String runtimeId;
  public String label;
  public Long expiresAt;
  public Long usedAt;
  public long createdAt;

  public String getLabel() {
    return label;
  }
}
