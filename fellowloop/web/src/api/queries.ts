import { useMemo } from 'react';
import { useQuery, type QueryClient } from '@tanstack/react-query';
import { api } from './client';
import type { Agent, Issue, Member, Project, Runtime, TaskEvent } from './types';

/** Query keys; realtime events invalidate by these prefixes. */
export const qk = {
  me: ['me'] as const,
  projects: ['projects'] as const,
  members: ['members'] as const,
  skills: ['skills'] as const,
  stats: (days: number) => ['stats', days] as const,
  issues: ['issues'] as const,
  projectIssues: (projectId?: string) => ['issues', 'project', projectId ?? 'all'] as const,
  issue: (idOrKey: string) => ['issue', idOrKey] as const,
  comments: (issueId: string) => ['comments', issueId] as const,
  issueTasks: (issueId: string) => ['tasks', 'issue', issueId] as const,
  issueRuns: (issueId: string) => ['runs', 'issue', issueId] as const,
  agents: ['agents'] as const,
  agent: (id: string) => ['agent', id] as const,
  agentTasks: (id: string) => ['tasks', 'agent', id] as const,
  runtimes: ['runtimes'] as const,
  tasks: ['tasks', 'recent'] as const,
  task: (id: string) => ['task', id] as const,
  taskEvents: (id: string) => ['taskEvents', id] as const,
  inbox: (status?: string) => ['inbox', status ?? 'all'] as const,
  components: ['components'] as const,
  workflows: ['workflows'] as const,
  workflow: (id: string) => ['workflow', id] as const,
  workflowVersions: (id: string) => ['workflow', id, 'versions'] as const,
  runs: (workflowId?: string) => ['runs', 'list', workflowId ?? 'all'] as const,
  run: (id: string) => ['run', id] as const,
};

export function useAgents() {
  return useQuery({ queryKey: qk.agents, queryFn: api.agents, staleTime: 30_000 });
}

export function useAgentMap(): Map<string, Agent> {
  const { data } = useAgents();
  return useMemo(() => new Map((data ?? []).map((a) => [a.id, a])), [data]);
}

export function useRuntimes() {
  return useQuery({ queryKey: qk.runtimes, queryFn: api.runtimes, staleTime: 15_000 });
}

export function useRuntimeMap(): Map<string, Runtime> {
  const { data } = useRuntimes();
  return useMemo(() => new Map((data ?? []).map((r) => [r.id, r])), [data]);
}

export function useIssues() {
  return useQuery({ queryKey: qk.issues, queryFn: () => api.issues(), staleTime: 10_000 });
}

export function useIssueMap(): Map<string, Issue> {
  const { data } = useIssues();
  return useMemo(() => new Map((data ?? []).map((i) => [i.id, i])), [data]);
}

export function useSkills() {
  return useQuery({ queryKey: qk.skills, queryFn: api.skills, staleTime: 60_000 });
}

export function useMembers() {
  return useQuery({ queryKey: qk.members, queryFn: api.members, staleTime: 60_000 });
}

export function useMemberMap(): Map<string, Member> {
  const { data } = useMembers();
  return useMemo(() => new Map((data ?? []).map((m) => [m.id, m])), [data]);
}

export function useProjects() {
  return useQuery({ queryKey: qk.projects, queryFn: api.projects, staleTime: 60_000 });
}

export function useProjectMap(): Map<string, Project> {
  const { data } = useProjects();
  return useMemo(() => new Map((data ?? []).map((p) => [p.id, p])), [data]);
}

export function useComponents() {
  return useQuery({ queryKey: qk.components, queryFn: api.components, staleTime: 5 * 60_000 });
}

export function usePendingInbox() {
  return useQuery({ queryKey: qk.inbox('pending'), queryFn: () => api.inbox('pending'), staleTime: 10_000 });
}

/** Merges events into a sorted, seq-deduplicated list (returns {@code prev} when nothing is new). */
export function mergeEvents(prev: TaskEvent[] | undefined, incoming: TaskEvent[]): TaskEvent[] {
  const base = prev ?? [];
  const known = new Set(base.map((e) => e.seq));
  const fresh = incoming.filter((e) => !known.has(e.seq) && (known.add(e.seq), true));
  if (fresh.length === 0) return prev ?? [];
  return base.concat(fresh).sort((a, b) => a.seq - b.seq);
}

/**
 * Transcript events of a task. Fetches incrementally (after the highest seq already cached) and
 * merges with events that realtime pushed into the cache meanwhile.
 */
export function useTaskEvents(taskId: string, qc: QueryClient) {
  return useQuery({
    queryKey: qk.taskEvents(taskId),
    queryFn: async () => {
      const cached = qc.getQueryData<TaskEvent[]>(qk.taskEvents(taskId));
      // contiguous prefix: fetch after the last seq we have without gaps
      let after = 0;
      if (cached) {
        for (const e of cached) {
          if (e.seq === after + 1) after = e.seq;
          else if (e.seq > after + 1) break;
        }
      }
      const fetched = await api.taskEvents(taskId, after);
      return mergeEvents(qc.getQueryData<TaskEvent[]>(qk.taskEvents(taskId)), fetched);
    },
    staleTime: Infinity,
  });
}

/**
 * The display name of an agent's model when its runtime reports one (TRAE model ids such as
 * `Doubao_1_6` differ from their names, "Doubao-Seed-Code"); otherwise the id as configured.
 */
export function useModelLabel(): (agent: { provider: string; runtimeId: string | null; model: string | null }) => string | null {
  const runtimes = useRuntimeMap();
  return (agent) => {
    if (!agent.model) return null;
    const pick = agent.runtimeId ? [runtimes.get(agent.runtimeId)] : [...runtimes.values()];
    for (const r of pick) {
      const m = r?.providers.find((p) => p.provider === agent.provider)?.models.find((x) => x.id === agent.model);
      if (m?.displayName) return m.displayName;
    }
    return agent.model;
  };
}
