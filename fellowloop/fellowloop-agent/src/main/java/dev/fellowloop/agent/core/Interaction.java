package dev.fellowloop.agent.core;

import java.util.List;
import java.util.Map;

/** Requests an agent makes of its host while it works. */
public final class Interaction {

  private Interaction() {}

  public enum ApprovalKind {
    COMMAND,
    FILE_CHANGE,
    PERMISSIONS,
    NETWORK,
    TOOL,
    OTHER
  }

  /**
   * @param command shell command for {@link ApprovalKind#COMMAND}, else null
   * @param paths   affected files for {@link ApprovalKind#FILE_CHANGE}
   */
  public record ApprovalRequest(String requestId, ApprovalKind kind, String title, String command, String cwd,
      List<String> paths, String reason, Map<String, Object> raw) {}

  public enum Decision {
    ACCEPT,
    ACCEPT_FOR_SESSION,
    DECLINE,
    CANCEL
  }

  /**
   * @param questions each has {@code id, header, question, options:[{label, description}]}
   */
  public record InputRequest(String requestId, String title, List<Map<String, Object>> questions,
      Map<String, Object> schema) {}

  /** {@code answers} maps question id to the chosen labels or free text; null declines. */
  public record InputReply(Map<String, List<String>> answers, boolean declined) {
    public static InputReply decline() {
      return new InputReply(Map.of(), true);
    }
  }

  /** Decides on behalf of the person; implementations may block until a human answers. */
  public interface Handler {
    Decision approve(ApprovalRequest request);

    InputReply input(InputRequest request);
  }
}
