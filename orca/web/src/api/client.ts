import type {
  Agent,
  AgentInput,
  Comment,
  InboxAnswer,
  InboxItem,
  Issue,
  IssueInput,
  IssuePatch,
  LoginResponse,
  Member,
  MemberInput,
  ProjectInput,
  AuditEvent,
  Stats,
  WorkflowVersionDetail,
  NodeDescriptor,
  Project,
  RegistrationCode,
  Run,
  Runtime,
  Task,
  TaskEvent,
  ValidationIssue,
  Workflow,
  WorkflowDefinition,
  WorkflowInput,
  WorkflowVersion,
  Skill,
  SkillInput,
  Attachment,
} from './types';

/** An error response from the API; {@code message} is the server's {@code error} text. */
export class ApiError extends Error {
  readonly status: number;
  readonly details: unknown;

  constructor(status: number, message: string, details?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.details = details;
  }

  /** Validation problems attached to a 422 from workflow save. */
  get validationIssues(): ValidationIssue[] {
    return Array.isArray(this.details) ? (this.details as ValidationIssue[]) : [];
  }
}

export function errorMessage(e: unknown): string {
  if (e instanceof Error) return e.message;
  return String(e);
}

type Method = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

let unauthorizedHandler: (() => void) | null = null;

/** Called whenever a request returns 401, so the app can send the user to the login page. */
export function onUnauthorized(handler: () => void): void {
  unauthorizedHandler = handler;
}

async function request<T>(method: Method, path: string, body?: unknown): Promise<T> {
  const init: RequestInit = {
    method,
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  };
  if (body !== undefined) {
    init.body = JSON.stringify(body);
    (init.headers as Record<string, string>)['Content-Type'] = 'application/json';
  }
  let res: Response;
  try {
    res = await fetch(`/api${path}`, init);
  } catch (e) {
    throw new ApiError(0, `cannot reach the Orca server (${errorMessage(e)})`);
  }
  const text = await res.text();
  let json: unknown = undefined;
  if (text) {
    try {
      json = JSON.parse(text);
    } catch {
      json = undefined;
    }
  }
  if (!res.ok) {
    const obj = (json ?? {}) as { error?: string; details?: unknown; message?: string };
    const msg = obj.error ?? obj.message ?? (text && text.length < 300 ? text : `${res.status} ${res.statusText}`);
    if (res.status === 401 && !path.startsWith('/auth/login')) unauthorizedHandler?.();
    throw new ApiError(res.status, msg, obj.details);
  }
  return json as T;
}

/** Uploads a file to an issue; returns the attachment with its URL for Markdown. */
async function upload(issueId: string, file: File): Promise<Attachment> {
  const form = new FormData();
  form.append('file', file, file.name || 'pasted.png');
  const res = await fetch(`/api/issues/${encodeURIComponent(issueId)}/attachments`, {
    method: 'POST',
    credentials: 'same-origin',
    body: form,
  });
  const text = await res.text();
  if (!res.ok) {
    let msg = `${res.status} ${res.statusText}`;
    try {
      msg = (JSON.parse(text) as { error?: string }).error ?? msg;
    } catch {
      // keep the status text
    }
    if (res.status === 401) unauthorizedHandler?.();
    throw new ApiError(res.status, msg);
  }
  return JSON.parse(text) as Attachment;
}

const q = (params: Record<string, string | number | undefined | null>) => {
  const s = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null && v !== '') s.set(k, String(v));
  const str = s.toString();
  return str ? `?${str}` : '';
};

const enc = encodeURIComponent;

export const api = {
  // auth
  login: (email: string, password: string) => request<LoginResponse>('POST', '/auth/login', { email, password }),
  logout: () => request<{ ok: boolean }>('POST', '/auth/logout'),
  me: () => request<Member>('GET', '/auth/me'),

  // skills
  skills: () => request<Skill[]>('GET', '/skills'),
  createSkill: (input: SkillInput) => request<Skill>('POST', '/skills', input),
  updateSkill: (id: string, input: SkillInput) => request<Skill>('PUT', `/skills/${enc(id)}`, input),
  deleteSkill: (id: string) => request<unknown>('DELETE', `/skills/${enc(id)}`),
  tokens: () => request<{ id: string; label: string; createdAt: number; expiresAt: number | null }[]>('GET', '/auth/tokens'),
  createToken: (label: string, days: number) => request<{ token: string }>('POST', '/auth/tokens', { label, days }),
  revokeToken: (id: string) => request<unknown>('DELETE', `/auth/tokens/${enc(id)}`),
  skillLibrary: () => request<{ name: string; description: string | null; content: string; installed: boolean }[]>('GET', '/skills/library'),
  installSkill: (name: string) => request<{ added: number }>('POST', `/skills/library/${enc(name)}`),

  // members
  members: () => request<Member[]>('GET', '/members'),
  createMember: (input: MemberInput) => request<Member>('POST', '/members', input),
  updateMember: (id: string, input: MemberInput) => request<Member>('PATCH', `/members/${enc(id)}`, input),
  deleteMember: (id: string) => request<unknown>('DELETE', `/members/${enc(id)}`),

  // usage
  stats: (days: number) => request<Stats>('GET', `/stats${q({ days })}`),

  // projects & issues
  projects: () => request<Project[]>('GET', '/projects'),
  createProject: (input: ProjectInput) => request<Project>('POST', '/projects', input),
  updateProject: (id: string, input: Partial<ProjectInput>) => request<Project>('PATCH', `/projects/${enc(id)}`, input),
  audit: (before?: string, limit = 100) => request<AuditEvent[]>('GET', `/audit${q({ before, limit })}`),
  issues: (projectId?: string) => request<Issue[]>('GET', `/issues${q({ projectId })}`),
  issue: (idOrKey: string) => request<Issue>('GET', `/issues/${enc(idOrKey)}`),
  createIssue: (input: IssueInput) => request<Issue>('POST', '/issues', input),
  updateIssue: (id: string, patch: IssuePatch) => request<Issue>('PATCH', `/issues/${enc(id)}`, patch),
  comments: (issueId: string) => request<Comment[]>('GET', `/issues/${enc(issueId)}/comments`),
  addComment: (issueId: string, body: string) => request<Comment>('POST', `/issues/${enc(issueId)}/comments`, { body }),
  uploadAttachment: upload,
  attachments: (issueId: string) => request<Attachment[]>('GET', `/issues/${enc(issueId)}/attachments`),
  issueTasks: (issueId: string) => request<Task[]>('GET', `/issues/${enc(issueId)}/tasks`),
  issueRuns: (issueId: string) => request<Run[]>('GET', `/issues/${enc(issueId)}/runs`),

  // agents
  agents: () => request<Agent[]>('GET', '/agents'),
  agent: (id: string) => request<Agent>('GET', `/agents/${enc(id)}`),
  askAgent: (id: string, prompt: string, followUp?: string, project?: string) =>
    request<Task>('POST', `/agents/${enc(id)}/ask`, { prompt, followUp, project }),
  createAgent: (input: AgentInput) => request<Agent>('POST', '/agents', input),
  updateAgent: (id: string, input: AgentInput) => request<Agent>('PATCH', `/agents/${enc(id)}`, input),
  archiveAgent: (id: string) => request<Agent>('DELETE', `/agents/${enc(id)}`),
  agentTasks: (id: string) => request<Task[]>('GET', `/agents/${enc(id)}/tasks`),

  // runtimes
  runtimes: () => request<Runtime[]>('GET', '/runtimes'),
  registrationCode: () => request<RegistrationCode>('POST', '/runtimes/registration-codes'),
  probeRuntime: (id: string) => request<{ ok: boolean }>('POST', `/runtimes/${enc(id)}/probe`),
  deleteRuntime: (id: string) => request<{ ok: boolean }>('DELETE', `/runtimes/${enc(id)}`),

  // tasks
  tasks: () => request<Task[]>('GET', '/tasks'),
  task: (id: string) => request<Task>('GET', `/tasks/${enc(id)}`),
  taskEvents: (id: string, after = 0) => request<TaskEvent[]>('GET', `/tasks/${enc(id)}/events${q({ after })}`),
  cancelTask: (id: string) => request<Task>('POST', `/tasks/${enc(id)}/cancel`),
  steerTask: (id: string, text: string) => request<{ ok: boolean }>('POST', `/tasks/${enc(id)}/steer`, { text }),

  // inbox
  inbox: (status?: string) => request<InboxItem[]>('GET', `/inbox${q({ status })}`),
  answerInbox: (id: string, answer: InboxAnswer) => request<InboxItem>('POST', `/inbox/${enc(id)}/answer`, answer),
  dismissInbox: (id: string) => request<InboxItem>('POST', `/inbox/${enc(id)}/dismiss`),

  // workflows
  components: () => request<NodeDescriptor[]>('GET', '/components'),
  workflows: () => request<Workflow[]>('GET', '/workflows'),
  workflow: (id: string) => request<Workflow>('GET', `/workflows/${enc(id)}`),
  createWorkflow: (input: WorkflowInput) => request<Workflow>('POST', '/workflows', input),
  updateWorkflow: (id: string, input: WorkflowInput) => request<Workflow>('PUT', `/workflows/${enc(id)}`, input),
  deleteWorkflow: (id: string) => request<{ ok: boolean }>('DELETE', `/workflows/${enc(id)}`),
  setWorkflowEnabled: (id: string, enabled: boolean) =>
    request<Workflow>('POST', `/workflows/${enc(id)}/enabled`, { enabled }),
  validateWorkflow: (definition: WorkflowDefinition) =>
    request<ValidationIssue[]>('POST', '/workflows/validate', definition),
  workflowVersions: (id: string) => request<WorkflowVersion[]>('GET', `/workflows/${enc(id)}/versions`),
  workflowVersion: (id: string, version: number) =>
    request<WorkflowVersionDetail>('GET', `/workflows/${enc(id)}/versions/${version}`),
  runWorkflow: (id: string, input: Record<string, unknown>, nodeId?: string) =>
    request<Run>('POST', `/workflows/${enc(id)}/runs`, { input, nodeId }),
  cronPreview: (expr: string, timezone?: string) =>
    request<number[]>('GET', `/cron/preview${q({ expr, timezone })}`),

  // runs
  runs: (workflowId?: string) => request<Run[]>('GET', `/runs${q({ workflowId })}`),
  run: (id: string) => request<Run>('GET', `/runs/${enc(id)}`),
  cancelRun: (id: string) => request<Run>('POST', `/runs/${enc(id)}/cancel`),
  retryRun: (id: string, nodeId: string) => request<Run>('POST', `/runs/${enc(id)}/retry`, { nodeId }),
};
