import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Alert, Breadcrumb, Button, Card, Col, Descriptions, Input, Row, Space, Typography } from 'antd';
import { SendOutlined } from '@ant-design/icons';
import { api } from '@/api/client';
import { qk, useAgentMap, useIssueMap, useRuntimeMap } from '@/api/queries';
import { useMe } from '@/app/auth';
import { useT } from '@/app/i18n';
import { JsonView } from '@/components/CodeBlock';
import { QueryState } from '@/components/QueryState';
import { fmtDateTime } from '@/components/format';
import { useNotify } from '@/components/useNotify';
import type { Task } from '@/api/types';
import { CancelTaskButton, Transcript } from '@/components/transcript/Transcript';
import { TaskMeta } from './TaskCard';

export function TaskPage() {
  const { id = '' } = useParams();
  const t = useT();
  const task = useQuery({ queryKey: qk.task(id), queryFn: () => api.task(id) });
  const agents = useAgentMap();
  const issues = useIssueMap();
  const runtimes = useRuntimeMap();
  const me = useMe();
  const d = task.data;
  // only the person who asked continues the conversation (the server enforces this too)
  const mine = !d?.origin?.by || d.origin.by === me.data?.id;
  const issue = d?.issueId ? issues.get(d.issueId) : undefined;
  const runId = typeof d?.origin?.runId === 'string' ? d.origin.runId : null;

  return (
    <QueryState isLoading={task.isLoading} error={task.error} onRetry={() => void task.refetch()} fullPage>
      {d && (
        <>
          <Breadcrumb
            style={{ marginBottom: 8 }}
            items={[
              issue ? { title: <Link to={`/issues/${issue.key}`}>{issue.key}</Link> } : { title: <Link to="/issues">{t('nav.issues')}</Link> },
              { title: `${t('tasks.task')} ${d.id}` },
            ]}
          />
          <Card style={{ marginBottom: 16 }} styles={{ body: { padding: '12px 16px' } }}>
            <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
              <TaskMeta task={d} />
              <CancelTaskButton task={d} size="middle" />
            </Space>
          </Card>
          <Row gutter={16}>
            <Col xs={24} xl={17}>
              {d.error && <Alert type="error" showIcon message={d.errorCode ?? t('status.failed')} description={d.error} style={{ marginBottom: 12 }} />}
              <Card title={t('tasks.transcript')} size="small">
                <Transcript task={d} maxHeight="calc(100vh - 320px)" />
              </Card>
              {d.kind === 'ask' && d.status === 'succeeded' && mine && <AskFollowUp task={d} />}
              {typeof d.origin?.followUpOf === 'string' && (
                <Typography.Paragraph type="secondary" style={{ marginTop: 8, fontSize: 12 }}>
                  {t('tasks.followUpOf')} <Link to={`/tasks/${d.origin.followUpOf}`} className="orca-mono">{d.origin.followUpOf}</Link>
                </Typography.Paragraph>
              )}
            </Col>
            <Col xs={24} xl={7}>
              <Card size="small" title={t('tasks.details')}>
                <Descriptions
                  size="small"
                  column={1}
                  items={[
                    { key: 'agent', label: t('common.agent'), children: agents.get(d.agentId) ? <Link to={`/agents/${d.agentId}`}>{agents.get(d.agentId)!.name}</Link> : d.agentId },
                    { key: 'issue', label: t('common.issue'), children: issue ? <Link to={`/issues/${issue.key}`}>{issue.key} · {issue.title}</Link> : '—' },
                    { key: 'run', label: t('tasks.workflowRun'), children: runId ? <Link to={`/runs/${runId}`}>{runId}</Link> : '—' },
                    { key: 'runtime', label: t('common.runtime'), children: (d.runtimeId && runtimes.get(d.runtimeId)?.name) ?? d.runtimeId ?? '—' },
                    { key: 'kind', label: t('tasks.kind'), children: d.kind },
                    { key: 'queued', label: t('tasks.queuedAt'), children: fmtDateTime(d.queuedAt) },
                    { key: 'started', label: t('common.started'), children: fmtDateTime(d.startedAt) },
                    { key: 'finished', label: t('common.finished'), children: fmtDateTime(d.finishedAt) },
                    { key: 'session', label: t('tasks.session'), children: d.sessionRef ? <Typography.Text className="orca-mono" copyable style={{ fontSize: 12 }}>{d.sessionRef}</Typography.Text> : '—' },
                  ]}
                />
                {d.structured !== null && d.structured !== undefined && (
                  <>
                    <Typography.Text strong>{t('tasks.structured')}</Typography.Text>
                    <JsonView value={d.structured} maxHeight={300} />
                  </>
                )}
              </Card>
            </Col>
          </Row>
        </>
      )}
    </QueryState>
  );
}

/** Continue a finished Ask in the same session: the agent remembers the conversation. */
function AskFollowUp({ task }: { task: Task }) {
  const t = useT();
  const notify = useNotify();
  const navigate = useNavigate();
  const [text, setText] = useState('');
  const send = useMutation({
    mutationFn: () => api.askAgent(task.agentId, text, task.id),
    onSuccess: (next) => {
      setText('');
      navigate(`/tasks/${next.id}`);
    },
    onError: notify.error,
  });
  return (
    <Card size="small" style={{ marginTop: 12 }}>
      <Space.Compact style={{ width: '100%' }}>
        <Input.TextArea value={text} onChange={(e) => setText(e.target.value)} autoSize={{ minRows: 1, maxRows: 6 }}
          placeholder={t('tasks.followUpPlaceholder')}
          onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && text.trim()) send.mutate(); }} />
        <Button type="primary" icon={<SendOutlined />} loading={send.isPending} disabled={!text.trim()} onClick={() => send.mutate()}>
          {t('tasks.followUp')}
        </Button>
      </Space.Compact>
    </Card>
  );
}
