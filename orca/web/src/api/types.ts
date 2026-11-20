// Types mirroring orca-server's support/Views.java and the workflow model.
// Times are epoch milliseconds.

export interface Member {
  id: string;
  email: string;
  name: string;
  role: string;
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
  maxConcurrency: number;
  timeoutMinutes: number;
  archived: boolean;
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

export interface ProviderStatus {
  provider: Provider | string;
  installed: boolean;
  version: string | null;
  path: string | null;
  loggedIn: boolean;
  authMode: string | null;
  plan: string | null;
  models: ModelInfo[];
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
  register: string;
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
  createdBy: string;
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
