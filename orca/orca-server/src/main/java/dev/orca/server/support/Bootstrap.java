package dev.orca.server.support;

import dev.orca.server.auth.AuthService;
import dev.orca.server.issue.IssueService;
import dev.orca.server.model.Member;
import dev.orca.server.repo.MemberRepo;
import dev.orca.server.repo.WorkflowRepo;
import dev.orca.server.workflow.WorkflowService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** First start: an admin account, a default project and the built-in workflows. */
@Component
public class Bootstrap implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

  public static final String ASSIGN_WORKFLOW = "wf_builtin_assign";
  public static final String COMMENT_WORKFLOW = "wf_builtin_comment";
  public static final String REVIEW_TEMPLATE = "wf_template_review";
  public static final String LOOP_TEMPLATE = "wf_template_review_loop";
  public static final String CODEX_REVIEW_TEMPLATE = "wf_template_codex_review";

  private final MemberRepo members;
  private final AuthService auth;
  private final IssueService issues;
  private final WorkflowService workflows;
  private final WorkflowRepo workflowRepo;
  private final OrcaProperties props;

  public Bootstrap(MemberRepo members, AuthService auth, IssueService issues, WorkflowService workflows,
      WorkflowRepo workflowRepo, OrcaProperties props) {
    this.members = members;
    this.auth = auth;
    this.issues = issues;
    this.workflows = workflows;
    this.workflowRepo = workflowRepo;
    this.props = props;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (members.count() == 0) {
      String password = props.admin().password();
      boolean generated = password == null || password.isBlank();
      if (generated) {
        password = Ids.random(16);
      }
      Member m = new Member();
      m.id = Ids.next("mem");
      m.email = props.admin().email();
      m.name = props.admin().name() == null ? "Admin" : props.admin().name();
      m.role = "owner";
      m.passwordHash = auth.hashPassword(password);
      m.createdAt = Ids.now();
      members.save(m);
      log.warn("created admin {}{}", m.email, generated ? " with generated password: " + password
          + " (set ORCA_ADMIN_PASSWORD to choose one)" : "");
    }
    if (issues.projects().isEmpty()) {
      issues.createProject("ORCA", "Default", "Default project", null, null);
    }
    if (!workflowRepo.existsById(ASSIGN_WORKFLOW)) {
      workflows.save(ASSIGN_WORKFLOW, "Assigned issue → agent",
          "When an issue is assigned to an agent, the agent works on it, comments the result and moves it to review.",
          true, assignDefinition(), true, null);
    }
    if (!workflowRepo.existsById(COMMENT_WORKFLOW)) {
      workflows.save(COMMENT_WORKFLOW, "Comment → agent",
          "When someone comments on an agent's issue or @mentions an agent, the agent continues in the same session.",
          true, commentDefinition(), true, null);
    }
    if (!workflowRepo.existsById(LOOP_TEMPLATE)) {
      workflows.save(LOOP_TEMPLATE, "Template: implement ⇄ review loop → approve",
          "Run by hand: the implementer works, the reviewer checks; requested changes go back until the review "
              + "passes (max 3 rounds); then a person approves and the issue is done.",
          false, loopDefinition(), false, null);
    }
    if (!workflowRepo.existsById(CODEX_REVIEW_TEMPLATE)) {
      workflows.save(CODEX_REVIEW_TEMPLATE, "Template: review an issue branch",
          "Run by hand: an agent reviews the issue branch against the default branch (Codex uses its built-in "
              + "reviewer) and posts the findings on the issue.",
          false, codexReviewDefinition(), false, null);
    }
    if (!workflowRepo.existsById(REVIEW_TEMPLATE)) {
      workflows.save(REVIEW_TEMPLATE, "Template: implement → cross-review → approve",
          "Run by hand: one agent implements, another reviews with a structured verdict, a person approves.",
          false, reviewDefinition(), false, null);
    }
  }

  static Map<String, Object> node(String id, String type, String name, Map<String, Object> config, int x, int y) {
    return node(id, type, name, config, x, y, 0, "FAIL");
  }

  static Map<String, Object> node(String id, String type, String name, Map<String, Object> config, int x, int y,
      int retries, String onError) {
    return Map.of("id", id, "type", type, "name", name, "config", config, "retries", retries, "onError", onError,
        "ui", Map.of("x", x, "y", y));
  }

  static Map<String, Object> edge(String id, String source, String port, String target) {
    return Map.of("id", id, "source", source, "sourcePort", port, "target", target);
  }

  static final String WORK_PROMPT = """
      You have been assigned issue {{ trigger.issue.key }}: {{ trigger.issue.title }}

      {{ trigger.issue.description }}

      Do the work in the current directory. When you are done, reply with a short summary for the team: what you \
      changed, how you checked it, and anything left open.""";

  static Map<String, Object> assignDefinition() {
    return Map.of(
        "nodes", List.of(
            node("start", "trigger.issue", "Assigned to an agent", Map.of("event", "assigned", "target", "agent"), 0, 0),
            node("mark", "issue.update", "Mark in progress", Map.of("issue", "{{ trigger.issue.id }}", "status", "in_progress"), 0, 170),
            node("work", "agent.run", "Agent works", Map.of("agent", "{{ trigger.agentId }}", "issue", "{{ trigger.issue.id }}",
                "session", "continue", "prompt", WORK_PROMPT), 0, 340, 1, "CONTINUE"),
            node("reply", "issue.comment", "Post the result", Map.of("issue", "{{ trigger.issue.id }}",
                "body", "{{ nodes.work.output.text }}", "author", "{{ trigger.agentId }}", "taskId", "{{ nodes.work.output.taskId }}"), -130, 510),
            node("review", "issue.update", "Move to review", Map.of("issue", "{{ trigger.issue.id }}", "status", "in_review"), -130, 680),
            node("failed", "issue.comment", "Report the failure", Map.of("issue", "{{ trigger.issue.id }}",
                "body", "The agent run failed: {{ nodes.work.output.error }}", "taskId", "{{ nodes.work.output.taskId }}"), 150, 510)),
        "edges", List.of(edge("e1", "start", "out", "mark"), edge("e2", "mark", "out", "work"),
            edge("e3", "work", "out", "reply"), edge("e4", "reply", "out", "review"),
            edge("e5", "work", "error", "failed")));
  }

  static Map<String, Object> commentDefinition() {
    return Map.of(
        "nodes", List.of(
            node("start", "trigger.issue", "Comment for an agent", Map.of("event", "commented", "target", "agent"), 0, 0),
            node("work", "agent.run", "Agent continues", Map.of("agent", "{{ trigger.agentId }}", "issue", "{{ trigger.issue.id }}",
                "session", "continue", "prompt", "A teammate commented on {{ trigger.issue.key }}:\n\n{{ trigger.comment.body }}"),
                0, 170, 1, "CONTINUE"),
            node("reply", "issue.comment", "Reply", Map.of("issue", "{{ trigger.issue.id }}", "body", "{{ nodes.work.output.text }}",
                "author", "{{ trigger.agentId }}", "taskId", "{{ nodes.work.output.taskId }}"), -130, 340),
            node("failed", "issue.comment", "Report the failure", Map.of("issue", "{{ trigger.issue.id }}",
                "body", "The agent run failed: {{ nodes.work.output.error }}", "taskId", "{{ nodes.work.output.taskId }}"), 150, 340)),
        "edges", List.of(edge("e1", "start", "out", "work"), edge("e2", "work", "out", "reply"),
            edge("e3", "work", "error", "failed")));
  }

  static Map<String, Object> loopDefinition() {
    return Map.of(
        "nodes", List.of(
            node("start", "trigger.manual", "Run by hand", Map.of("inputs", "issueId, implementer, reviewer"), 0, 0),
            node("loop", "agent.review_loop", "Implement ⇄ review", Map.of("implementer", "{{ trigger.input.implementer }}",
                "reviewer", "{{ trigger.input.reviewer }}", "issue", "{{ trigger.input.issueId }}", "maxRounds", 3,
                "prompt", "Implement the issue in the current directory, run the tests, commit, and summarise the change."),
                0, 170),
            node("approve", "human.approval", "Approve", Map.of("issue", "{{ trigger.input.issueId }}",
                "title", "Approve the change (review passed in {{ nodes.loop.output.rounds }} round(s))",
                "body", "{{ nodes.loop.output.summary }}"), -130, 340),
            node("done", "issue.update", "Mark done", Map.of("issue", "{{ trigger.input.issueId }}", "status", "done"), -130, 510),
            node("report", "issue.comment", "Report open points", Map.of("issue", "{{ trigger.input.issueId }}",
                "body", "The review did not pass after {{ nodes.loop.output.rounds }} rounds:\n\n- "
                    + "{{ join(nodes.loop.output.issues, '\n- ') }}"), 150, 340)),
        "edges", List.of(edge("e1", "start", "out", "loop"), edge("e2", "loop", "passed", "approve"),
            edge("e3", "approve", "approved", "done"), edge("e4", "loop", "failed", "report")));
  }

  static Map<String, Object> codexReviewDefinition() {
    return Map.of(
        "nodes", List.of(
            node("start", "trigger.manual", "Run by hand", Map.of("inputs", "issueId, reviewer"), 0, 0),
            node("review", "agent.run", "Review the branch", Map.of("agent", "{{ trigger.input.reviewer }}",
                "issue", "{{ trigger.input.issueId }}", "mode", "review", "reviewTarget", "baseBranch",
                "prompt", "Pay attention to correctness, tests and anything the issue asks for."), 0, 170),
            node("post", "issue.comment", "Post the review", Map.of("issue", "{{ trigger.input.issueId }}",
                "body", "{{ nodes.review.output.text }}", "author", "{{ trigger.input.reviewer }}",
                "taskId", "{{ nodes.review.output.taskId }}"), 0, 340)),
        "edges", List.of(edge("e1", "start", "out", "review"), edge("e2", "review", "out", "post")));
  }

  static Map<String, Object> reviewDefinition() {
    Map<String, Object> verdict = Map.of("type", "object", "additionalProperties", false,
        "required", List.of("verdict", "summary", "issues"),
        "properties", Map.of("verdict", Map.of("type", "string", "enum", List.of("pass", "changes_requested")),
            "summary", Map.of("type", "string"),
            "issues", Map.of("type", "array", "items", Map.of("type", "string"))));
    return Map.of(
        "nodes", List.of(
            node("start", "trigger.manual", "Run by hand", Map.of("inputs", "issueId, implementer, reviewer"), 0, 0),
            node("implement", "agent.run", "Implement", Map.of("agent", "{{ trigger.input.implementer }}",
                "issue", "{{ trigger.input.issueId }}", "session", "continue",
                "prompt", "Implement the issue in the current directory, run the tests, and summarise the change."), 0, 170),
            node("review", "agent.run", "Cross-review", Map.of("agent", "{{ trigger.input.reviewer }}",
                "issue", "{{ trigger.input.issueId }}", "session", "new", "outputSchema", verdict,
                "prompt", "You are reviewing a teammate's work in the current directory. Use shell commands to inspect it: "
                    + "ls, cat, and git status/git diff when it is a git repository. Reading files is always allowed. Run the "
                    + "tests if you can; if the sandbox prevents it, review the code by reading it. Verdict 'pass' only if the "
                    + "change does what the issue asks; if you cannot find the change, the verdict is 'changes_requested'."
                    + "\n\nThe author's summary:\n\n"
                    + "{{ nodes.implement.output.text }}"), 0, 340),
            node("check", "logic.condition", "Passed?", Map.of("when", "nodes.review.output.structured.verdict == 'pass'"), 0, 510),
            node("approve", "human.approval", "Approve the change", Map.of("issue", "{{ trigger.input.issueId }}",
                "title", "Approve the reviewed change",
                "body", "Review summary: {{ nodes.review.output.structured.summary }}"), -130, 680),
            node("done", "issue.update", "Mark done", Map.of("issue", "{{ trigger.input.issueId }}", "status", "done"), -130, 850),
            node("rework", "issue.comment", "Ask for changes", Map.of("issue", "{{ trigger.input.issueId }}",
                "body", "Review requested changes:\n\n{{ join(nodes.review.output.structured.issues, '\\n- ') }}"), 150, 680)),
        "edges", List.of(edge("e1", "start", "out", "implement"), edge("e2", "implement", "out", "review"),
            edge("e3", "review", "out", "check"), edge("e4", "check", "true", "approve"),
            edge("e5", "approve", "approved", "done"), edge("e6", "check", "false", "rework")));
  }
}
