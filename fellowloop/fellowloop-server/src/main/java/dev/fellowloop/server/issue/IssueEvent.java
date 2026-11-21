package dev.fellowloop.server.issue;

import dev.fellowloop.server.model.Comment;
import dev.fellowloop.server.model.Issue;

/**
 * Something happened to an issue. Triggers start workflows from these.
 *
 * @param type          {@code created}, {@code assigned}, {@code commented} or {@code status_changed}
 * @param actorType     {@code member}, {@code agent}, {@code workflow} or {@code system}
 * @param targetAgentId for comments: the agent mentioned, or the issue's agent assignee
 */
public record IssueEvent(String type, Issue issue, Comment comment, String actorType, String actorId,
    String targetAgentId, String previousStatus) {

  public static final String CREATED = "created";
  public static final String ASSIGNED = "assigned";
  public static final String COMMENTED = "commented";
  public static final String STATUS_CHANGED = "status_changed";
}
