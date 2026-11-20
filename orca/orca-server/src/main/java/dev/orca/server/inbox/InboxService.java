package dev.orca.server.inbox;

import dev.orca.server.model.InboxItem;
import dev.orca.server.model.Task;
import dev.orca.server.realtime.Realtime;
import dev.orca.server.repo.InboxRepo;
import dev.orca.server.support.ApiException;
import dev.orca.server.support.Ids;
import dev.orca.server.support.JsonCols;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What waits for a person: approvals, questions from agents, form inputs and failure alerts. */
@Service
public class InboxService {
  public static final String PENDING = "pending";
  public static final String ANSWERED = "answered";
  public static final String DISMISSED = "dismissed";
  public static final String EXPIRED = "expired";

  public static final String APPROVAL = "approval";
  public static final String QUESTION = "question";
  public static final String INPUT = "input";
  public static final String ALERT = "alert";

  private final InboxRepo items;
  private final Realtime realtime;
  private final InboxNotifier notifier;

  public InboxService(InboxRepo items, Realtime realtime, InboxNotifier notifier) {
    this.items = items;
    this.realtime = realtime;
    this.notifier = notifier;
  }

  @Transactional
  public InboxItem question(Task t, String requestId, String title, Map<String, Object> data) {
    InboxItem existing = items.findByTaskIdAndRequestId(t.id, requestId).orElse(null);
    if (existing != null) {
      return existing;
    }
    Map<String, Object> form = new LinkedHashMap<>();
    if (data.get("questions") != null) {
      form.put("questions", data.get("questions"));
    }
    if (data.get("schema") != null) {
      form.put("schema", data.get("schema"));
    }
    if (data.get("approval") != null) {
      form.put("approval", data.get("approval"));
    }
    InboxItem i = base(QUESTION, title);
    i.taskId = t.id;
    i.requestId = requestId;
    i.issueId = t.issueId;
    i.formJson = JsonCols.write(form);
    return save(i);
  }

  @Transactional
  public InboxItem approval(String runId, String nodeId, String waitKey, String title, String body, String issueId) {
    InboxItem i = base(APPROVAL, title);
    i.body = body;
    i.runId = runId;
    i.nodeId = nodeId;
    i.waitKey = waitKey;
    i.issueId = issueId;
    i.id = waitKey;
    return save(i);
  }

  @Transactional
  public InboxItem input(String runId, String nodeId, String waitKey, String title, String body,
      List<Map<String, Object>> questions, String issueId) {
    InboxItem i = base(INPUT, title);
    i.body = body;
    i.runId = runId;
    i.nodeId = nodeId;
    i.waitKey = waitKey;
    i.issueId = issueId;
    i.id = waitKey;
    i.formJson = JsonCols.write(Map.of("questions", questions));
    return save(i);
  }

  @Transactional
  public InboxItem alert(String title, String body, String runId, String issueId, String taskId) {
    InboxItem i = base(ALERT, title);
    i.body = body;
    i.runId = runId;
    i.issueId = issueId;
    i.taskId = taskId;
    return save(i);
  }

  @Transactional
  public InboxItem resolve(String id, String status, Object answer, String memberId) {
    InboxItem i = get(id);
    if (!PENDING.equals(i.status)) {
      throw new IllegalStateException("this item is already " + i.status);
    }
    i.status = status;
    i.answerJson = JsonCols.write(answer);
    i.resolvedAt = Ids.now();
    i.resolvedBy = memberId;
    return save(i);
  }

  @Transactional
  public void closeQuestion(String taskId, String requestId) {
    items.findByTaskIdAndRequestId(taskId, requestId).filter(i -> PENDING.equals(i.status)).ifPresent(i -> {
      i.status = EXPIRED;
      i.resolvedAt = Ids.now();
      save(i);
    });
  }

  @Transactional
  public void closeTask(String taskId) {
    for (InboxItem i : items.findByTaskIdAndStatus(taskId, PENDING)) {
      if (QUESTION.equals(i.kind)) {
        i.status = EXPIRED;
        i.resolvedAt = Ids.now();
        save(i);
      }
    }
  }

  @Transactional
  public void closeRun(String runId) {
    for (InboxItem i : items.findByRunIdAndStatus(runId, PENDING)) {
      if (!ALERT.equals(i.kind)) {
        i.status = EXPIRED;
        i.resolvedAt = Ids.now();
        save(i);
      }
    }
  }

  public InboxItem get(String id) {
    return items.findById(id).orElseThrow(() -> ApiException.notFound("inbox item"));
  }

  public List<InboxItem> list(String status) {
    return status == null || status.isBlank() ? items.findTop200ByOrderByCreatedAtDesc()
        : items.findByStatusOrderByCreatedAtDesc(status);
  }

  private InboxItem base(String kind, String title) {
    InboxItem i = new InboxItem();
    i.id = Ids.next("inb");
    i.kind = kind;
    i.status = PENDING;
    i.title = title == null || title.isBlank() ? kind : title.length() > 480 ? title.substring(0, 480) : title;
    i.createdAt = Ids.now();
    return i;
  }

  private InboxItem save(InboxItem i) {
    boolean fresh = PENDING.equals(i.status) && !items.existsById(i.id);
    InboxItem saved = items.save(i);
    if (fresh) {
      notifier.created(saved);
    }
    realtime.publish("inbox.updated", Realtime.ids("id", i.id, "status", i.status, "kind", i.kind));
    return saved;
  }
}
