package dev.orca.server.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "member")
public class Member {
  @Id public String id;
  public String email;
  public String name;
  public String passwordHash;
  public String role;
  public long createdAt;
}
