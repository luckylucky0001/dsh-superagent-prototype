import { Tag } from 'antd';
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  MinusCircleOutlined,
  PauseCircleOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import type { ReactNode } from 'react';
import { useT } from '@/app/i18n';
import { normStatus } from '@/api/types';

interface Style {
  color: string;
  icon?: ReactNode;
}

const STYLES: Record<string, Style> = {
  // issues
  backlog: { color: 'default' },
  todo: { color: 'blue' },
  in_progress: { color: 'processing', icon: <SyncOutlined spin /> },
  in_review: { color: 'purple' },
  done: { color: 'success', icon: <CheckCircleOutlined /> },
  // tasks / runs / nodes
  queued: { color: 'default', icon: <ClockCircleOutlined /> },
  dispatched: { color: 'cyan', icon: <SyncOutlined spin /> },
  running: { color: 'processing', icon: <SyncOutlined spin /> },
  waiting_input: { color: 'warning', icon: <PauseCircleOutlined /> },
  waiting: { color: 'warning', icon: <PauseCircleOutlined /> },
  succeeded: { color: 'success', icon: <CheckCircleOutlined /> },
  failed: { color: 'error', icon: <CloseCircleOutlined /> },
  cancelled: { color: 'default', icon: <MinusCircleOutlined /> },
  timed_out: { color: 'error', icon: <ExclamationCircleOutlined /> },
  pending: { color: 'default', icon: <ClockCircleOutlined /> },
  ready: { color: 'cyan' },
  skipped: { color: 'default', icon: <MinusCircleOutlined /> },
  // inbox
  answered: { color: 'success', icon: <CheckCircleOutlined /> },
  dismissed: { color: 'default' },
  expired: { color: 'default', icon: <ClockCircleOutlined /> },
  // runtimes
  online: { color: 'success' },
  offline: { color: 'default' },
};

/** Colour-coded status label for issues, tasks, runs, nodes, inbox items and runtimes. */
export function StatusTag({ status, bordered = false }: { status: string | null | undefined; bordered?: boolean }) {
  const t = useT();
  const s = normStatus(status);
  const style = STYLES[s] ?? { color: 'default' };
  return (
    <Tag color={style.color} icon={style.icon} bordered={bordered} style={{ marginInlineEnd: 0 }}>
      {t.dyn(`status.${s}`, s.replace(/_/g, ' '))}
    </Tag>
  );
}

export const PRIORITY_COLORS: Record<string, string> = {
  urgent: '#cf1322',
  high: '#d46b08',
  medium: '#d4b106',
  low: '#8c8c8c',
  none: '#bfbfbf',
};

export function PriorityTag({ priority }: { priority: string }) {
  const t = useT();
  if (!priority || priority === 'none') return null;
  return (
    <Tag bordered={false} color={priority === 'urgent' ? 'red' : priority === 'high' ? 'orange' : undefined} style={{ marginInlineEnd: 0 }}>
      <PriorityIcon priority={priority} /> {t.dyn(`priority.${priority}`, priority)}
    </Tag>
  );
}

/** Three little bars, filled by priority. */
export function PriorityIcon({ priority }: { priority: string }) {
  const level = { none: 0, low: 1, medium: 2, high: 3, urgent: 3 }[priority] ?? 0;
  const color = PRIORITY_COLORS[priority] ?? '#bfbfbf';
  if (priority === 'urgent') {
    return <ExclamationCircleOutlined style={{ color }} />;
  }
  return (
    <svg width="12" height="12" viewBox="0 0 12 12" style={{ verticalAlign: '-1px' }} aria-hidden>
      {[0, 1, 2].map((i) => (
        <rect key={i} x={1 + i * 4} y={8 - i * 3} width="2.5" height={3 + i * 3} rx="0.8" fill={i < level ? color : '#e0e0e0'} />
      ))}
    </svg>
  );
}
