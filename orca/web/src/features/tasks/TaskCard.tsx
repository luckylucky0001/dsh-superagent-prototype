import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Alert, Button, Card, Flex, Space, Tooltip, Typography } from 'antd';
import { DownOutlined, ExportOutlined, RightOutlined } from '@ant-design/icons';
import { useAgentMap } from '@/api/queries';
import { ACTIVE_TASK_STATUSES, taskTokensOf, type Task } from '@/api/types';
import { useT } from '@/app/i18n';
import { AgentAvatar, ProviderTag } from '@/components/AgentAvatar';
import { StatusTag } from '@/components/StatusTag';
import { durationBetween, fmtDuration, fmtTime, fmtTokens } from '@/components/format';
import { CancelTaskButton, Transcript } from '@/components/transcript/Transcript';

/** Summary line of a task: agent, provider, model, status, duration and tokens. */
export function TaskMeta({ task }: { task: Task }) {
  const t = useT();
  const agents = useAgentMap();
  const agent = agents.get(task.agentId);
  const tokens = taskTokensOf(task.usage);
  const running = !!task.startedAt && !task.finishedAt;
  const [, tick] = useState(0);
  useEffect(() => {
    if (!running) return undefined;
    const id = window.setInterval(() => tick((n) => n + 1), 1000);
    return () => window.clearInterval(id);
  }, [running]);
  return (
    <Flex align="center" gap={10} wrap="wrap" style={{ minWidth: 0 }}>
      <Space size={6}>
        <AgentAvatar agent={agent} />
        <Typography.Text strong>{agent?.name ?? task.agentId}</Typography.Text>
      </Space>
      <ProviderTag provider={task.provider} small />
      {task.model && <Typography.Text type="secondary" className="orca-mono" style={{ fontSize: 12 }}>{task.model}</Typography.Text>}
      <StatusTag status={task.status} />
      {task.statusDetail && <Typography.Text type="secondary" style={{ fontSize: 12 }}>{task.statusDetail}</Typography.Text>}
      <Tooltip title={`${t('tasks.queuedAt')}: ${fmtTime(task.queuedAt)}`}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {fmtDuration(durationBetween(task.startedAt, task.finishedAt))}
        </Typography.Text>
      </Tooltip>
      {tokens !== undefined && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {fmtTokens(tokens)} {t('transcript.tokensShort')}
        </Typography.Text>
      )}
    </Flex>
  );
}

/** A task in the issue timeline; expands to its live transcript. */
export function TaskCard({ task }: { task: Task }) {
  const t = useT();
  const active = ACTIVE_TASK_STATUSES.includes(task.status) || task.status === 'queued';
  // open while the agent works and stay open when it finishes, so the reader keeps their place
  const [expanded, setOpen] = useState(active);
  useEffect(() => {
    if (active) setOpen(true);
  }, [active]);
  const runId = typeof task.origin?.runId === 'string' ? task.origin.runId : null;

  return (
    <Card size="small" className="orca-task-card" styles={{ body: { padding: 0 } }}>
      <Flex align="center" justify="space-between" gap={8} style={{ padding: '8px 12px', cursor: 'pointer' }}
        onClick={() => setOpen(!expanded)}>
        <Flex align="center" gap={8} style={{ minWidth: 0 }}>
          {expanded ? <DownOutlined style={{ fontSize: 10 }} /> : <RightOutlined style={{ fontSize: 10 }} />}
          <TaskMeta task={task} />
        </Flex>
        <Space size={4} onClick={(e) => e.stopPropagation()}>
          {runId && (
            <Link to={`/runs/${runId}`}>
              <Button type="link" size="small">{t('tasks.viewRun')}</Button>
            </Link>
          )}
          <Link to={`/tasks/${task.id}`}>
            <Button type="text" size="small" icon={<ExportOutlined />} title={t('tasks.open')} />
          </Link>
          {active && <CancelTaskButton task={task} />}
        </Space>
      </Flex>
      {task.error && (task.status === 'failed' || task.status === 'timed_out') && (
        <Alert type="error" banner message={task.error} style={{ marginInline: 0 }} />
      )}
      {expanded && (
        <div style={{ padding: '8px 12px 12px', borderTop: '1px solid #f0f0f0' }}>
          <Transcript task={task} maxHeight={560} />
        </div>
      )}
    </Card>
  );
}
