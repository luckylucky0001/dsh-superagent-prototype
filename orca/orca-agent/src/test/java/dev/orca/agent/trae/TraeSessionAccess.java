package dev.orca.agent.trae;

import java.util.Map;

/** Test access to package-private helpers of the TRAE driver. */
public final class TraeSessionAccess {
  private TraeSessionAccess() {}

  public static String reviewCommand(Map<String, Object> review, String extra) {
    return TraeSession.reviewCommand(review, extra);
  }
}
