package dev.fellowloop.server.inbox;

import dev.fellowloop.server.auth.CurrentUser;
import dev.fellowloop.server.model.InboxItem;
import dev.fellowloop.server.support.ApiException;
import dev.fellowloop.server.support.Views.InboxView;
import dev.fellowloop.server.task.TaskService;
import dev.fellowloop.server.workflow.WorkflowService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inbox")
public class InboxController {
  private final InboxService inbox;
  private final WorkflowService workflows;
  private final TaskService tasks;

  public InboxController(InboxService inbox, WorkflowService workflows, TaskService tasks) {
    this.inbox = inbox;
    this.workflows = workflows;
    this.tasks = tasks;
  }

  @GetMapping
  public List<InboxView> list(@RequestParam(required = false) String status) {
    return inbox.list(status).stream().map(InboxView::of).toList();
  }

  /**
   * Answers an item. Approvals take {@code {decision: approved|rejected, comment}}; questions and inputs
   * take {@code {answers: {id: value|[values]}}} or {@code {declined: true}}.
   */
  @PostMapping("/{id}/answer")
  @Transactional
  public InboxView answer(@PathVariable String id, @RequestBody Map<String, Object> body) {
    InboxItem item = inbox.get(id);
    String member = CurrentUser.id();
    switch (item.kind) {
      case InboxService.APPROVAL -> {
        String decision = String.valueOf(body.get("decision"));
        if (!decision.equals("approved") && !decision.equals("rejected")) {
          throw ApiException.bad("decision must be approved or rejected");
        }
        InboxItem done = inbox.resolve(id, InboxService.ANSWERED, body, member);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("decision", decision);
        p.put("comment", body.getOrDefault("comment", ""));
        p.put("by", member);
        workflows.signal(item.runId, item.nodeId, item.waitKey, p);
        return InboxView.of(done);
      }
      case InboxService.INPUT -> {
        InboxItem done = inbox.resolve(id, InboxService.ANSWERED, body, member);
        workflows.signal(item.runId, item.nodeId, item.waitKey, Map.of("answers", body.getOrDefault("answers", Map.of()),
            "by", member));
        return InboxView.of(done);
      }
      case InboxService.QUESTION -> {
        if (!tasks.answer(item.taskId, item.requestId, TaskService.answerFrom(body))) {
          throw ApiException.bad("the agent's runtime is not connected; try again when it is online");
        }
        return InboxView.of(inbox.resolve(id, InboxService.ANSWERED, body, member));
      }
      default -> {
        return InboxView.of(inbox.resolve(id, InboxService.DISMISSED, body, member));
      }
    }
  }

  @PostMapping("/{id}/dismiss")
  public InboxView dismiss(@PathVariable String id) {
    return InboxView.of(inbox.resolve(id, InboxService.DISMISSED, null, CurrentUser.id()));
  }
}
