import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { mergeEvents, qk } from '@/api/queries';
import type { TaskEvent } from '@/api/types';

export type RealtimeStatus = 'connecting' | 'open' | 'reconnecting';

const RealtimeContext = createContext<RealtimeStatus>('connecting');

export function useRealtimeStatus(): RealtimeStatus {
  return useContext(RealtimeContext);
}

type Payload = Record<string, unknown>;

const str = (v: unknown) => (v === undefined || v === null ? undefined : String(v));

/** Maps a server event to the queries it makes stale. */
function handle(qc: QueryClient, type: string, data: Payload) {
  const inv = (queryKey: readonly unknown[]) => void qc.invalidateQueries({ queryKey });
  const id = str(data.id);
  switch (type) {
    case 'issue.updated':
      inv(qk.issues);
      inv(['issue']);
      break;
    case 'comment.created':
      if (data.issueId) inv(qk.comments(String(data.issueId)));
      break;
    case 'task.updated':
      if (id) inv(qk.task(id));
      inv(['tasks']);
      inv(qk.runtimes); // active task counts
      break;
    case 'task.events': {
      const taskId = str(data.taskId);
      const events = Array.isArray(data.events) ? (data.events as TaskEvent[]) : [];
      if (taskId && events.length) {
        const key = qk.taskEvents(taskId);
        // only append to transcripts that are (or were) on screen; others load fresh when opened
        if (qc.getQueryState(key)) qc.setQueryData<TaskEvent[]>(key, (prev) => mergeEvents(prev, events));
      }
      break;
    }
    case 'run.updated':
      inv(['runs']);
      if (id) inv(qk.run(id));
      break;
    case 'run.node':
      if (data.runId) inv(qk.run(String(data.runId)));
      break;
    case 'inbox.updated':
      inv(['inbox']);
      break;
    case 'runtime.updated':
      inv(qk.runtimes);
      break;
    case 'agent.updated':
      inv(qk.agents);
      if (id) inv(qk.agent(id));
      break;
    case 'workflow.updated':
      inv(qk.workflows);
      if (id) inv(qk.workflow(id));
      break;
    case 'project.updated':
      inv(qk.projects);
      break;
    default:
      break;
  }
}

const EVENT_TYPES = [
  'issue.updated',
  'comment.created',
  'task.updated',
  'task.events',
  'run.updated',
  'run.node',
  'inbox.updated',
  'runtime.updated',
  'agent.updated',
  'workflow.updated',
  'project.updated',
];

/**
 * One EventSource for the whole app. EventSource retries by itself after network errors; when the
 * browser gives up (e.g. the server restarted or returned an error) we reconnect with back-off, and
 * after any reconnect refetch everything, since events may have been missed.
 */
export function RealtimeProvider({ children }: { children: ReactNode }) {
  const qc = useQueryClient();
  const [status, setStatus] = useState<RealtimeStatus>('connecting');
  const retry = useRef(0);

  useEffect(() => {
    let es: EventSource | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let disposed = false;
    let hadConnection = false;

    const connect = () => {
      if (disposed) return;
      es = new EventSource('/api/stream', { withCredentials: true });
      es.addEventListener('open', () => {
        setStatus('open');
        retry.current = 0;
        if (hadConnection) void qc.invalidateQueries();
        hadConnection = true;
      });
      es.addEventListener('error', () => {
        setStatus('reconnecting');
        if (es && es.readyState === EventSource.CLOSED) {
          es.close();
          const delay = Math.min(30_000, 1000 * 2 ** retry.current++);
          timer = setTimeout(connect, delay);
        }
      });
      for (const type of EVENT_TYPES) {
        es.addEventListener(type, (ev) => {
          try {
            const data = JSON.parse((ev as MessageEvent<string>).data) as Payload;
            handle(qc, type, data);
          } catch {
            /* ignore malformed events */
          }
        });
      }
    };
    connect();
    return () => {
      disposed = true;
      if (timer) clearTimeout(timer);
      es?.close();
    };
  }, [qc]);

  return <RealtimeContext.Provider value={status}>{children}</RealtimeContext.Provider>;
}
