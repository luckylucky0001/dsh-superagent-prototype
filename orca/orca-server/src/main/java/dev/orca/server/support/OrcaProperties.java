package dev.orca.server.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orca")
public record OrcaProperties(String publicUrl, Admin admin, String defaultTimezone, int leaseSeconds,
    int heartbeatSeconds) {

  public OrcaProperties {
    publicUrl = publicUrl == null ? "http://localhost:8080" : publicUrl.replaceAll("/$", "");
    admin = admin == null ? new Admin("admin@orca.local", "", "Admin") : admin;
    defaultTimezone = defaultTimezone == null || defaultTimezone.isBlank() ? "Asia/Singapore" : defaultTimezone;
    leaseSeconds = leaseSeconds <= 0 ? 60 : leaseSeconds;
    heartbeatSeconds = heartbeatSeconds <= 0 ? 15 : heartbeatSeconds;
  }

  public record Admin(String email, String password, String name) {}
}
