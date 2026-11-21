package dev.fellowloop.server.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnswerFromTest {

  @Test
  void approveAndRejectFromTheCliAnswerAnApprovalRequest() {
    assertEquals(Map.of("decision", List.of("Allow once")), TaskService.answerFrom(Map.of("decision", "approved")),
        "fellowloop approve allows the command once (it used to send no answer, which declined it)");
    assertEquals(Map.of("_declined", true), TaskService.answerFrom(Map.of("decision", "rejected")));
    assertEquals(Map.of("decision", List.of("Allow for this task")),
        TaskService.answerFrom(Map.of("answers", Map.of("decision", List.of("Allow for this task")))));
    assertEquals(Map.of("db", "postgres", "_declined", true),
        TaskService.answerFrom(Map.of("answers", Map.of("db", "postgres"), "declined", true)));
  }
}
