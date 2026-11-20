// Types mirroring orca-server's support/Views.java and the workflow model.
// Times are epoch milliseconds.

export interface Member {
  id: string;
  email: string;
  name: string;
  role: string;
}

export type MemberRole = 'owner' | 'admin' | 'member';

export interface MemberInput {
  email?: string;
  name?: string;
  password?: string;
  role?: MemberRole;
  /** required when changing your own password */
  currentPassword?: string;
}

export interface AuditEvent {
  id: string;
  at: number;
  actorId: string | null;
  actorName: string | null;
  action: string;
  targetType: string | null;
  targetId: string | null;
  detail: Record<string, unknown>;
  ip: string | null;
}

export interface ProjectInput {
  key: string;
  name: string;
  description?: string;
  repoUrl?: string;
  defaultBranch?: string;
}

export interface DayStats {
  day: string;
  runs: number;
  succeeded: number;
  failed: number;
  tokens: number;
}

export interface AgentStats {
  agentId: string;
  name: string;
  provider: Provider;
  runs: number;
  succeeded: number;
  failed: number;
  tokens: number;
  busyMs: number;
}

export interface Stats {
  timezone: string;
  days: DayStats[];
  agents: AgentStats[];
}

export interface LoginResponse {
  member: Member;
  token: string;
}

export interface Project {
  id: string;
  key: string;
  name: string;
  description: string | null;
  repoUrl: string | null;
  defaultBranch: string | null;
  createdAt: number;
}

export const ISSUE_STATUSES = ['backlog', 'todo', 'in_progress', 'in_review', 'done', 'cancelled'] as const;
export type IssueStatus = (typeof ISSUE_STATUSES)[number];
export const ISSUE_PRIORITIES = ['none', 'low', 'medium', 'high', 'urgent'] as const;
export type IssuePriority = (typeof ISSUE_PRIORITIES)[number];

export interface Issue {
  id: string;
  key: string;
  projectId: string;
  number: number;
  title: string;
  description: string | null;
  status: IssueStatus;
  priority: IssuePriority;
  assigneeType: 'agent' | 'member' | null;
  assigneeId: string | null;
  labels: string[];
  createdBy: string | null;
  createdAt: number;
  updatedAt: number;
}

export interface IssueInput {
  projectId?: string;
  title: string;
  description?: string;
  status?: IssueStatus;
  priority?: IssuePriority;
  assigneeType?: 'agent' | 'member';
  assigneeId?: string;
  labels?: string[];
}

export interface IssuePatch {
  title?: string;
  description?: string;
  status?: IssueStatus;
  priority?: IssuePriority;
  assigneeType?: 'agent' | 'member';
  assigneeId?: string;
  labels?: string[];
  clearAssignee?: boolean;
}

export interface Comment {
  id: string;
  issueId: string;
  authorType: 'member' | 'agent' | 'system';
  authorId: string | null;
  body: string;
  taskId: string | null;
  createdAt: number;
}

export type Provider = 'codex' | 'trae';
export type Permission = 'read_only' | 'workspace' | 'full';

export interface Agent {
  id: string;
  name: string;
  description: string | null;
  provider: Provider;
  runtimeId: string | null;
  model: string | null;
  effort: string | null;
  instructions: string | null;
  permission: Permission;
  allowedCommands: string[];
  extraArgs: string[];
  skills: string[];
  maxConcurrency: number;
  timeoutMinutes: number;
  archived: boolean;
  /** Ask a person in the inbox when the runtime's policy would deny a request. */
  escalateApprovals: boolean;
  /** Network access inside the workspace sandbox; the runtime must allow it. */
  networkAccess: boolean;
  createdAt: number;
  updatedAt: number;
}

export type AgentInput = Partial<Omit<Agent, 'id' | 'createdAt' | 'updatedAt'>>;

export interface ModelInfo {
  id: string;
  displayName: string | null;
  description: string | null;
  isDefault: boolean;
  efforts: string[] | null;
  defaultEffort: string | null;
}

export interface UsageWindow {
  usedPercent: number;
  /** 300 = 5 hours, 10080 = a week */
  windowMinutes: number | null;
  resetsAt: number | null;
  reached: boolean;
}

export interface ProviderStatus {
  provider: Provider | string;
  installed: boolean;
  version: string | null;
  path: string | null;
  loggedIn: boolean;
  authMode: string | null;
  plan: string | null;
  models: ModelInfo[];
  /** plan usage limits (Codex with a ChatGPT plan) */
  usage?: UsageWindow[];
  capabilities: string[];
  error: string | null;
}

export interface Runtime {
  id: string;
  name: string;
  hostname: string | null;
  os: string | null;
  daemonVersion: string | null;
  capacity: number;
  isolated: boolean;
  allowNetwork: boolean;
  status: 'online' | 'offline' | string;
  lastSeenAt: number | null;
  providers: ProviderStatus[];
  activeTasks: number;
  createdAt: number;
}

export interface RegistrationCode {
  code: string;
  expiresInMinutes: number;
  server: string;
  install?: string;
  download: string;
  register: string;
  doctor: string;
  start: string;
}

export type TaskStatus =
  | 'queued'
  | 'dispatched'
  | 'running'
  | 'waiting_input'
  | 'succeeded'
  | 'failed'
  | 'cancelled'
  | 'timed_out';

export const ACTIVE_TASK_STATUSES: TaskStatus[] = ['dispatched', 'running', 'waiting_input'];
export const TERMINAL_TASK_STATUSES: TaskStatus[] = ['succeeded', 'failed', 'cancelled', 'timed_out'];

export interface Usage {
  inputTokens?: number;
  cachedInputTokens?: number;
  outputTokens?: number;
  reasoningTokens?: number;
  totalTokens?: number;
  contextWindow?: number;
  lastTotalTokens?: number;
  /** Tokens of this task only; totalTokens covers the whole provider session. */
  taskTokens?: number;
}

/** Tokens spent by one task: taskTokens when the server reports it, else the session total. */
export function taskTokensOf(u: Usage | null | undefined): number | undefined {
  if (!u) return undefined;
  return u.taskTokens ?? u.totalTokens;
}

export interface Task {
  id: string;
  agentId: string;
  runtimeId: string | null;
  issueId: string | null;
  provider: Provider;
  kind: string;
  origin: Record<string, unknown> | null;
  prompt: string | null;
  status: TaskStatus;
  statusDetail: string | null;
  sessionRef: string | null;
  resumeRef: string | null;
  model: string | null;
  finalText: string | null;
  structured: unknown;
  errorCode: string | null;
  error: string | null;
  usage: Usage | null;
  lastSeq: number;
  queuedAt: number;
  startedAt: number | null;
  finishedAt: number | null;
}

export interface TaskEvent {
  seq: number;
  ts: number;
  type: string;
  data: Record<string, unknown>;
}

// ---------------------------------------------------------------- workflows

export type OnError = 'FAIL' | 'CONTINUE';

export interface NodeDef {
  id: string;
  type: string;
  name?: string | null;
  config: Record<string, unknown>;
  retries: number;
  timeoutSeconds: number;
  onError: OnError;
  ui: { x?: number; y?: number; [k: string]: unknown };
}

export interface EdgeDef {
  id: string;
  source: string;
  sourcePort?: string | null;
  target: string;
  condition?: string | null;
}

export interface WorkflowDefinition {
  nodes: NodeDef[];
  edges: EdgeDef[];
}

export type FieldKind =
  | 'STRING'
  | 'TEXT'
  | 'TEMPLATE'
  | 'EXPRESSION'
  | 'NUMBER'
  | 'BOOLEAN'
  | 'SELECT'
  | 'AGENT'
  | 'CRON'
  | 'JSON'
  | 'KEY_VALUE';

export interface FieldSpec {
  name: string;
  label: string;
  kind: FieldKind;
  required: boolean;
  defaultValue: unknown;
  options: { value: string; label: string }[];
  help: string | null;
  /** Shown only while another field has one of these values. */
  visibleWhen: { field: string; values: string[] } | null;
}

export interface OutputSpec {
  name: string;
  type: string;
  description: string | null;
}

export type ComponentCategory = 'trigger' | 'agent' | 'logic' | 'human' | 'action';

export interface NodeDescriptor {
  type: string;
  category: ComponentCategory | string;
  label: string;
  description: string | null;
  trigger: boolean;
  fields: FieldSpec[];
  ports: string[];
  outputs: OutputSpec[];
}

export interface Workflow {
  id: string;
  name: string;
  description: string | null;
  enabled: boolean;
  builtIn: boolean;
  currentVersion: number;
  definition: WorkflowDefinition;
  createdAt: number;
  updatedAt: number;
}

export interface WorkflowInput {
  name?: string;
  description?: string;
  enabled: boolean;
  definition: WorkflowDefinition;
}

export interface WorkflowVersion {
  version: number;
  createdAt: number;
  createdBy: string | null;
}

export interface WorkflowVersionDetail {
  version: number;
  createdAt?: number;
  definition: WorkflowDefinition;
}

export interface ValidationIssue {
  nodeId: string | null;
  edgeId: string | null;
  message: string;
}

export type NodeStatus = 'PENDING' | 'READY' | 'RUNNING' | 'WAITING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED' | 'CANCELLED';
export type EdgeStatus = 'PENDING' | 'ACTIVE' | 'DEAD';

export interface NodeState {
  status: NodeStatus;
  attempts: number;
  output: Record<string, unknown>;
  ports: string[];
  error: string | null;
  waitKey: string | null;
  deadlineAt: number;
  startedAt: number;
  finishedAt: number;
}

export interface RunState {
  runId: string;
  workflowId: string;
  status: string;
  triggerNodeId: string;
  trigger: Record<string, unknown>;
  nodes: Record<string, NodeState>;
  edges: Record<string, EdgeStatus>;
  error: string | null;
  startedAt: number;
  finishedAt: number;
}

/** Run status; the list returns lower case, the state upper case. Compare with {@link normStatus}. */
export interface Run {
  id: string;
  workflowId: string;
  workflowName: string;
  version: number;
  status: string;
  triggerNodeId: string | null;
  issueId: string | null;
  error: string | null;
  startedAt: number;
  finishedAt: number | null;
  state: RunState | null;
  definition: WorkflowDefinition | null;
}

// ---------------------------------------------------------------- inbox

export type InboxKind = 'approval' | 'question' | 'input' | 'alert';
export type InboxStatus = 'pending' | 'answered' | 'dismissed' | 'expired';

export interface QuestionOption {
  label: string;
  description?: string | null;
}

export interface Question {
  id: string;
  header?: string | null;
  question?: string | null;
  options?: QuestionOption[] | null;
  isOther?: boolean;
  isSecret?: boolean;
}

export interface JsonSchema {
  type?: string | string[];
  title?: string;
  description?: string;
  properties?: Record<string, JsonSchema>;
  required?: string[];
  enum?: unknown[];
  items?: JsonSchema;
  default?: unknown;
  [k: string]: unknown;
}

export interface InboxForm {
  questions?: Question[];
  schema?: JsonSchema;
  /** Present when an agent asks to go beyond its runtime policy. */
  approval?: { kind: string; command?: string; paths?: string[]; grantRoot?: string; reason?: string; cwd?: string; sessionAllowed?: boolean };
}

export interface InboxItem {
  id: string;
  kind: InboxKind;
  status: InboxStatus;
  title: string;
  body: string | null;
  form: InboxForm | null;
  answer: unknown;
  taskId: string | null;
  requestId: string | null;
  runId: string | null;
  nodeId: string | null;
  issueId: string | null;
  createdAt: number;
  resolvedAt: number | null;
  resolvedBy: string | null;
}

export interface InboxAnswer {
  decision?: 'approved' | 'rejected';
  comment?: string;
  answers?: Record<string, unknown>;
  declined?: boolean;
}

export function normStatus(s: string | null | undefined): string {
  return (s ?? '').toLowerCase();
}

export interface Skill {
  id: string;
  name: string;
  description: string | null;
  content: string;
  createdAt: number;
  updatedAt: number;
}

export interface SkillInput {
  name: string;
  description?: string;
  content: string;
}

export interface Attachment {
  id: string;
  issueId: string;
  name: string;
  contentType: string;
  size: number;
  url: string;
  createdAt: number;
}
