import { useMemo } from 'react';
import { useQuery, type QueryClient } from '@tanstack/react-query';
import { api } from './client';
import type { Agent, Issue, Runtime, TaskEvent } from './types';

/** Query keys; realtime events invalidate by these prefixes. */
export const qk = {
  me: ['me'] as const,
  projects: ['projects'] as const,
  issues: ['issues'] as const,
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
