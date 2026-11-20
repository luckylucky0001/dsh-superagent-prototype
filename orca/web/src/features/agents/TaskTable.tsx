import { Link } from 'react-router-dom';
import { Table, Typography } from 'antd';
import { useAgentMap, useIssueMap } from '@/api/queries';
import { taskTokensOf, type Task } from '@/api/types';
import { useT } from '@/app/i18n';
import { ProviderTag } from '@/components/AgentAvatar';
import { StatusTag } from '@/components/StatusTag';
import { durationBetween, fmtDuration, fmtTime, fmtTokens } from '@/components/format';

/** Table of tasks with links to their transcripts. */
export function TaskTable({ tasks, loading, showAgent }: { tasks: Task[]; loading?: boolean; showAgent?: boolean }) {
  const t = useT();
  const issues = useIssueMap();
  const agents = useAgentMap();
  return (
    <Table<Task>
      rowKey="id"
      size="small"
      loading={loading}
      dataSource={tasks}
      pagination={{ pageSize: 20, hideOnSinglePage: true }}
      locale={{ emptyText: t('agents.noTasks') }}
      columns={[
        { title: t('tasks.task'), dataIndex: 'id', render: (id: string) => <Link to={`/tasks/${id}`} className="orca-mono" style={{ fontSize: 12 }}>{id}</Link> },
        ...(showAgent ? [{ title: t('common.agent'), dataIndex: 'agentId', render: (id: string) => agents.get(id)?.name ?? id }] : []),
        {
          title: t('common.issue'),
          dataIndex: 'issueId',
          render: (id: string | null) => {
            const i = id ? issues.get(id) : undefined;
            return i ? <Link to={`/issues/${i.key}`}>{i.key}</Link> : <Typography.Text type="secondary">—</Typography.Text>;
          },
        },
        { title: t('common.status'), dataIndex: 'status', render: (s: string) => <StatusTag status={s} /> },
        { title: t('agents.model'), key: 'model', render: (_: unknown, r) => <><ProviderTag provider={r.provider} small /> <span className="orca-mono" style={{ fontSize: 12 }}>{r.model ?? '—'}</span></> },
        { title: t('tasks.queuedAt'), dataIndex: 'queuedAt', render: (v: number) => fmtTime(v) },
        { title: t('common.duration'), key: 'dur', render: (_: unknown, r) => fmtDuration(durationBetween(r.startedAt, r.finishedAt)) },
        { title: t('transcript.tokens'), key: 'tok', render: (_: unknown, r) => fmtTokens(taskTokensOf(r.usage)) },
      ]}
    />
  );
}
