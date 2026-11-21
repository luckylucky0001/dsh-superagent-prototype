package dev.fellowloop.agent.codex;

import dev.fellowloop.agent.core.SessionSpec;
import java.util.Map;

/** Test access to package-private helpers of the Codex driver. */
public final class CodexSessionAccess {
  private CodexSessionAccess() {}

  public static Map<String, Object> threadConfig(SessionSpec spec) {
    return CodexSession.threadConfig(spec);
  }

  public static String errorCode(Object info) {
    return CodexEvents.errorCode(info);
  }
}
