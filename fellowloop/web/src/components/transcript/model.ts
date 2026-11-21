import type { Question, TaskEvent, Usage } from '@/api/types';

export interface FileChange {
  path: string;
  kind?: string;
  diff?: string;
}

export type TranscriptItem =
  | { kind: 'session'; key: string; ts: number; model?: string; provider?: string; resumed?: boolean; sessionRef?: string; cwd?: string }
  | { kind: 'user'; key: string; ts: number; text: string }
  | { kind: 'message'; key: string; ts: number; text: string; streaming: boolean; phase?: string }
  | { kind: 'reasoning'; key: string; ts: number; text: string; streaming: boolean }
  | {
      kind: 'tool';
      key: string;
      ts: number;
      toolKind: string;
      title: string;
      input?: unknown;
      status: 'running' | 'completed' | 'failed' | 'declined' | string;
      output: string;
      exitCode?: number | null;
      durationMs?: number | null;
      changes: FileChange[];
    }
  | { kind: 'plan'; key: string; ts: number; entries: { step: string; status: string }[]; explanation?: string }
  | { kind: 'diff'; key: string; ts: number; diff: string }
  | {
      kind: 'approval';
      key: string;
      ts: number;
      requestId: string;
      approvalKind?: string;
      title?: string;
      detail?: unknown;
      decision?: string;
      by?: string;
    }
  | {
      kind: 'input';
      key: string;
      ts: number;
      requestId: string;
      title?: string;
      questions?: Question[];
      schema?: unknown;
      answer?: unknown;
      resolved: boolean;
    }
  | { kind: 'notice'; key: string; ts: number; level: 'info' | 'warn' | 'error' | string; message: string; code?: string }
  | { kind: 'turn'; key: string; ts: number; status: string; errorCode?: string; error?: string };

export interface Transcript {
  items: TranscriptItem[];
  usage: Usage | null;
  turns: number;
}

const str = (v: unknown): string | undefined => (v === undefined || v === null ? undefined : String(v));
const num = (v: unknown): number | undefined => (typeof v === 'number' ? v : undefined);

function changesOf(v: unknown): FileChange[] {
  if (!Array.isArray(v)) return [];
  return v
    .filter((c): c is Record<string, unknown> => !!c && typeof c === 'object')
    .map((c) => ({ path: String(c.path ?? ''), kind: str(c.kind), diff: str(c.diff) }));
}

/**
 * Folds the event stream into renderable items: deltas stream into the message / reasoning / tool
 * with the same itemId and are replaced by the final event; the turn's latest diff is shown once,
 * just before the turn's status line.
 */
export function buildTranscript(events: TaskEvent[]): Transcript {
  const items: TranscriptItem[] = [];
  const byKey = new Map<string, TranscriptItem>();
  let usage: Usage | null = null;
  let turns = 0;
  let turnDiff: { kind: 'diff'; key: string; ts: number; diff: string } | null = null;
  let turnPlan: Extract<TranscriptItem, { kind: 'plan' }> | null = null;

  const put = (item: TranscriptItem) => {
    items.push(item);
    byKey.set(item.key, item);
  };

  for (const e of events) {
    const d = e.data ?? {};
    const itemId = str(d.itemId) ?? `seq-${e.seq}`;
    switch (e.type) {
      case 'session':
        put({ kind: 'session', key: `session-${e.seq}`, ts: e.ts, model: str(d.model), provider: str(d.provider),
          resumed: d.resumed === true, sessionRef: str(d.sessionRef), cwd: str(d.cwd) });
        break;
      case 'user.message':
        put({ kind: 'user', key: `user-${e.seq}`, ts: e.ts, text: str(d.text) ?? '' });
        break;
      case 'turn.started':
        turns++;
        turnDiff = null;
        turnPlan = null;
        break;
      case 'message.delta': {
        const k = `msg-${itemId}`;
        const cur = byKey.get(k);
        if (cur && cur.kind === 'message') cur.text += str(d.text) ?? '';
        else put({ kind: 'message', key: k, ts: e.ts, text: str(d.text) ?? '', streaming: true });
        break;
      }
      case 'message': {
        const k = `msg-${itemId}`;
        const cur = byKey.get(k);
        if (cur && cur.kind === 'message') {
          cur.text = str(d.text) ?? cur.text;
          cur.streaming = false;
          cur.phase = str(d.phase);
        } else put({ kind: 'message', key: k, ts: e.ts, text: str(d.text) ?? '', streaming: false, phase: str(d.phase) });
        break;
      }
      case 'reasoning.delta': {
        const k = `rsn-${itemId}`;
        const cur = byKey.get(k);
        if (cur && cur.kind === 'reasoning') cur.text += str(d.text) ?? '';
        else put({ kind: 'reasoning', key: k, ts: e.ts, text: str(d.text) ?? '', streaming: true });
        break;
      }
      case 'reasoning': {
        const k = `rsn-${itemId}`;
        const cur = byKey.get(k);
        if (cur && cur.kind === 'reasoning') {
          cur.text = str(d.text) ?? cur.text;
          cur.streaming = false;
        } else put({ kind: 'reasoning', key: k, ts: e.ts, text: str(d.text) ?? '', streaming: false });
        break;
      }
      case 'tool.started': {
        const k = `tool-${itemId}`;
        if (!byKey.has(k)) {
          put({ kind: 'tool', key: k, ts: e.ts, toolKind: str(d.kind) ?? 'other', title: str(d.title) ?? '',
            input: d.input, status: 'running', output: '', changes: changesOf(d.changes) });
        }
        break;
      }
      case 'tool.output': {
        const k = `tool-${itemId}`;
        let cur = byKey.get(k);
        if (!cur) {
          cur = { kind: 'tool', key: k, ts: e.ts, toolKind: 'other', title: '', status: 'running', output: '', changes: [] };
          put(cur);
        }
        if (cur.kind === 'tool') cur.output += str(d.text) ?? '';
        break;
      }
      case 'tool.completed': {
        const k = `tool-${itemId}`;
        let cur = byKey.get(k);
        if (!cur || cur.kind !== 'tool') {
          cur = { kind: 'tool', key: k, ts: e.ts, toolKind: str(d.kind) ?? 'other', title: str(d.title) ?? '',
            status: 'completed', output: '', changes: [] };
          put(cur);
        }
        if (cur.kind === 'tool') {
          cur.toolKind = str(d.kind) ?? cur.toolKind;
          cur.title = str(d.title) ?? cur.title;
          cur.status = str(d.status) ?? 'completed';
          if (d.output !== undefined && d.output !== null) cur.output = String(d.output);
          cur.exitCode = num(d.exitCode) ?? cur.exitCode;
          cur.durationMs = num(d.durationMs) ?? cur.durationMs;
          const ch = changesOf(d.changes);
          if (ch.length) cur.changes = ch;
        }
        break;
      }
      case 'plan': {
        const entries = Array.isArray(d.entries)
          ? (d.entries as Record<string, unknown>[]).map((x) => ({ step: String(x.step ?? ''), status: String(x.status ?? 'pending') }))
          : [];
        if (turnPlan) {
          turnPlan.entries = entries;
          turnPlan.explanation = str(d.explanation);
        } else {
          turnPlan = { kind: 'plan', key: `plan-${e.seq}`, ts: e.ts, entries, explanation: str(d.explanation) };
          put(turnPlan);
        }
        break;
      }
      case 'diff':
        turnDiff = { kind: 'diff', key: `diff-${e.seq}`, ts: e.ts, diff: str(d.diff) ?? '' };
        break;
      case 'usage':
        usage = d as Usage;
        break;
      case 'approval.requested': {
        const rid = str(d.requestId) ?? String(e.seq);
        put({ kind: 'approval', key: `apr-${rid}`, ts: e.ts, requestId: rid, approvalKind: str(d.kind), title: str(d.title), detail: d.detail });
        break;
      }
      case 'approval.resolved': {
        const rid = str(d.requestId) ?? '';
        const cur = byKey.get(`apr-${rid}`);
        if (cur && cur.kind === 'approval') {
          cur.decision = str(d.decision);
          cur.by = str(d.by);
        } else {
          put({ kind: 'approval', key: `apr-${rid || e.seq}`, ts: e.ts, requestId: rid, decision: str(d.decision), by: str(d.by) });
        }
        break;
      }
      case 'input.requested': {
        const rid = str(d.requestId) ?? String(e.seq);
        put({ kind: 'input', key: `inp-${rid}`, ts: e.ts, requestId: rid, title: str(d.title),
          questions: Array.isArray(d.questions) ? (d.questions as Question[]) : undefined, schema: d.schema, resolved: false });
        break;
      }
      case 'input.resolved': {
        const cur = byKey.get(`inp-${str(d.requestId) ?? ''}`);
        if (cur && cur.kind === 'input') {
          cur.resolved = true;
          cur.answer = d.answer;
        }
        break;
      }
      case 'notice':
        put({ kind: 'notice', key: `ntc-${e.seq}`, ts: e.ts, level: str(d.level) ?? 'info', message: str(d.message) ?? '', code: str(d.code) });
        break;
      case 'turn.completed':
        // a tool still running when its turn ended was stopped with it (cancel, timeout): no spinner forever
        for (const it of items) {
          if (it.kind === 'tool' && it.status === 'running') it.status = 'interrupted';
        }
        if (turnDiff && turnDiff.diff.trim()) put(turnDiff);
        turnDiff = null;
        put({ kind: 'turn', key: `turn-${e.seq}`, ts: e.ts, status: str(d.status) ?? 'completed', errorCode: str(d.errorCode), error: str(d.error) });
        break;
      default:
        break;
    }
  }
  if (turnDiff && turnDiff.diff.trim()) put(turnDiff);
  return { items, usage, turns };
}
