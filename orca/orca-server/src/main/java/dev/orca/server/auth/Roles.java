package dev.orca.server.auth;

import dev.orca.server.support.ApiException;
import org.springframework.http.HttpStatus;

/** Role checks shared by controllers. */
public final class Roles {
  private Roles() {}

  public static boolean isAdmin() {
    String role = CurrentUser.get().role;
    return "owner".equals(role) || "admin".equals(role);
  }

  public static void requireAdmin(String what) {
    if (!isAdmin()) {
      throw new ApiException(HttpStatus.FORBIDDEN, "only owners and admins can " + what);
    }
  }
}
