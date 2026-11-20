import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Card, Flex, Form, Input, Segmented, Space, Tag, Tooltip, Typography } from 'antd';
import {
  AlertOutlined,
  CheckOutlined,
  CloseOutlined,
  FormOutlined,
  QuestionCircleOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { api } from '@/api/client';
import { qk, useIssueMap } from '@/api/queries';
import type { InboxAnswer, InboxItem } from '@/api/types';
import { useT } from '@/app/i18n';
import { JsonView } from '@/components/CodeBlock';
import { Markdown } from '@/components/Markdown';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, QueryState } from '@/components/QueryState';
import { QuestionFields, SchemaFields, schemaAnswers } from '@/components/SchemaForm';
import { StatusTag } from '@/components/StatusTag';
import { fmtDateTime, fromNow } from '@/components/format';
import { useNotify } from '@/components/useNotify';

const KIND_ICON: Record<string, React.ReactNode> = {
  approval: <SafetyCertificateOutlined style={{ color: '#0f5fd7' }} />,
  question: <QuestionCircleOutlined style={{ color: '#d46b08' }} />,
  input: <FormOutlined style={{ color: '#7a3ff2' }} />,
  alert: <AlertOutlined style={{ color: '#cf1322' }} />,
};

function useAnswer(item: InboxItem) {
  const t = useT();
  const notify = useNotify();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (a: InboxAnswer | 'dismiss') => (a === 'dismiss' ? api.dismissInbox(item.id) : api.answerInbox(item.id, a)),
    onSuccess: () => {
      notify.success(t('inbox.answeredToast'));
      void qc.invalidateQueries({ queryKey: ['inbox'] });
    },
    onError: notify.error,
  });
}

function ApprovalForm({ item }: { item: InboxItem }) {
  const t = useT();
  const answer = useAnswer(item);
  const [comment, setComment] = useState('');
  const [which, setWhich] = useState<'approved' | 'rejected' | null>(null);
  const send = (decision: 'approved' | 'rejected') => {
    setWhich(decision);
    answer.mutate({ decision, comment });
  };
  return (
    <Space direction="vertical" style={{ display: 'flex' }}>
      <Input.TextArea value={comment} onChange={(e) => setComment(e.target.value)} placeholder={t('inbox.commentOptional')} autoSize={{ minRows: 1, maxRows: 5 }} />
      <Space>
        <Button type="primary" icon={<CheckOutlined />} loading={answer.isPending && which === 'approved'} onClick={() => send('approved')}>
          {t('inbox.approve')}
        </Button>
        <Button danger icon={<CloseOutlined />} loading={answer.isPending && which === 'rejected'} onClick={() => send('rejected')}>
          {t('inbox.reject')}
        </Button>
      </Space>
    </Space>
  );
}

/** An agent asks to go beyond its runtime's policy: show exactly what, then three clear choices. */
function TaskApprovalForm({ item }: { item: InboxItem }) {
  const t = useT();
  const answer = useAnswer(item);
  const [which, setWhich] = useState<string | null>(null);
  const a = item.form?.approval ?? { kind: 'other' };
  const send = (label: string) => {
    setWhich(label);
    answer.mutate({ answers: { decision: label } });
  };
  return (
    <Space direction="vertical" style={{ display: 'flex' }} size={10}>
      {a.reason && <Typography.Text type="secondary">{a.reason}</Typography.Text>}
      {a.command && <pre className="orca-mono orca-approval-cmd">{a.command}</pre>}
      {a.paths && a.paths.length > 0 && (
        <pre className="orca-mono orca-approval-cmd">{a.paths.join('\n')}</pre>
      )}
      {a.grantRoot && <Typography.Text strong>{t('inbox.approvalGrant', { path: a.grantRoot })}</Typography.Text>}
      {a.cwd && <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('inbox.approvalCwd', { cwd: a.cwd })}</Typography.Text>}
      <Space wrap>
        <Button type="primary" icon={<CheckOutlined />} loading={answer.isPending && which === 'Allow once'} onClick={() => send('Allow once')}>
          {t('inbox.allowOnce')}
        </Button>
        <Button icon={<CheckOutlined />} loading={answer.isPending && which === 'Allow for this task'} onClick={() => send('Allow for this task')}>
          {t('inbox.allowTask')}
        </Button>
        <Button danger icon={<CloseOutlined />} loading={answer.isPending && which === 'Deny'} onClick={() => send('Deny')}>
          {t('inbox.deny')}
        </Button>
      </Space>
    </Space>
  );
}

function QuestionForm({ item }: { item: InboxItem }) {
  const t = useT();
  const answer = useAnswer(item);
  const [form] = Form.useForm<{ answers?: Record<string, unknown> }>();
  const questions = item.form?.questions ?? [];
  const schema = item.form?.schema;
  const [declining, setDeclining] = useState(false);
  const submit = (v: { answers?: Record<string, unknown> }) => {
    setDeclining(false);
    const answers = schema ? schemaAnswers(schema, v.answers) : v.answers ?? {};
    answer.mutate({ answers });
  };
  return (
    <Form name="inboxInput" form={form} layout="vertical" onFinish={submit} requiredMark="optional" style={{ maxWidth: 640 }}>
      {questions.length > 0 && <QuestionFields questions={questions} />}
      {schema && <SchemaFields schema={schema} />}
      {questions.length === 0 && !schema && (
        <Form.Item name={['answers', 'answer']} label={t('inbox.answer')} rules={[{ required: true, message: t('form.required') }]}>
          <Input.TextArea autoSize={{ minRows: 2, maxRows: 8 }} />
        </Form.Item>
      )}
      <Space>
        <Button type="primary" htmlType="submit" loading={answer.isPending && !declining}>{t('inbox.submit')}</Button>
        {item.kind === 'question' && (
          <Button loading={answer.isPending && declining} onClick={() => { setDeclining(true); answer.mutate({ declined: true }); }}>
            {t('inbox.decline')}
          </Button>
        )}
      </Space>
    </Form>
  );
}

function AlertActions({ item }: { item: InboxItem }) {
  const t = useT();
  const answer = useAnswer(item);
  return <Button loading={answer.isPending} onClick={() => answer.mutate('dismiss')}>{t('inbox.dismiss')}</Button>;
}

function InboxCard({ item }: { item: InboxItem }) {
  const t = useT();
  const issues = useIssueMap();
  const issue = item.issueId ? issues.get(item.issueId) : undefined;
  const pending = item.status === 'pending';
  return (
    <Card size="small" style={{ marginBottom: 12, opacity: pending ? 1 : 0.85 }} styles={{ body: { padding: 16 } }}>
      <Flex justify="space-between" align="flex-start" gap={12}>
        <Flex gap={10} align="flex-start" style={{ minWidth: 0 }}>
          <span style={{ fontSize: 18, lineHeight: '22px' }}>{KIND_ICON[item.kind]}</span>
          <div style={{ minWidth: 0 }}>
            <Typography.Text strong style={{ fontSize: 15 }}>{item.title}</Typography.Text>
            <Flex gap={8} wrap="wrap" align="center" style={{ marginTop: 2 }}>
              <Tag bordered={false} style={{ marginInlineEnd: 0 }}>{item.form?.approval ? t('inbox.kind.approval') : t.dyn(`inbox.kind.${item.kind}`, item.kind)}</Tag>
              {issue && <Link to={`/issues/${issue.key}`} style={{ fontSize: 12 }}>{issue.key} · {issue.title}</Link>}
              {!issue && item.issueId && <Typography.Text type="secondary" style={{ fontSize: 12 }}>{item.issueId}</Typography.Text>}
              {item.runId && <Link to={`/runs/${item.runId}`} style={{ fontSize: 12 }}>{t('inbox.viewRun')}</Link>}
              {item.taskId && <Link to={`/tasks/${item.taskId}`} style={{ fontSize: 12 }}>{t('inbox.viewTask')}</Link>}
            </Flex>
          </div>
        </Flex>
        <Space direction="vertical" align="end" size={2} style={{ flex: 'none' }}>
          <StatusTag status={item.status} />
          <Tooltip title={fmtDateTime(item.createdAt)}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{fromNow(item.createdAt)}</Typography.Text>
          </Tooltip>
        </Space>
      </Flex>
      {item.body && (
        <div style={{ marginTop: 10, paddingInlineStart: 28 }}>
          <Markdown>{item.body}</Markdown>
        </div>
      )}
      <div style={{ marginTop: 12, paddingInlineStart: 28 }}>
        {pending ? (
          item.kind === 'approval' ? <ApprovalForm item={item} />
            : item.kind === 'alert' ? <AlertActions item={item} />
              : item.form?.approval ? <TaskApprovalForm item={item} />
                : <QuestionForm item={item} />
        ) : (
          item.answer !== null && item.answer !== undefined && (
            <div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {t('inbox.resolved', { when: fmtDateTime(item.resolvedAt) })}
              </Typography.Text>
              <JsonView value={item.answer} maxHeight={160} />
            </div>
          )
        )}
      </div>
    </Card>
  );
}

export function InboxPage() {
  const t = useT();
  const [filter, setFilter] = useState<'pending' | 'all'>('pending');
  const all = useQuery({ queryKey: qk.inbox(), queryFn: () => api.inbox() });
  const items = useMemo(() => {
    const list = [...(all.data ?? [])];
    list.sort((a, b) => Number(b.status === 'pending') - Number(a.status === 'pending') || b.createdAt - a.createdAt);
    return filter === 'pending' ? list.filter((i) => i.status === 'pending') : list;
  }, [all.data, filter]);
  const pendingCount = (all.data ?? []).filter((i) => i.status === 'pending').length;

  return (
    <div style={{ maxWidth: 960 }}>
      <PageHeader
        title={t('nav.inbox')}
        subtitle={t('inbox.subtitle')}
        extra={
          <Segmented value={filter} onChange={(v) => setFilter(v as 'pending' | 'all')}
            options={[{ value: 'pending', label: `${t('inbox.pending')} (${pendingCount})` }, { value: 'all', label: t('inbox.all') }]} />
        }
      />
      <QueryState isLoading={all.isLoading} error={all.error} onRetry={() => void all.refetch()}>
        {items.length === 0 ? (
          <Card><EmptyState description={filter === 'pending' ? t('inbox.emptyPending') : t('inbox.empty')} /></Card>
        ) : (
          items.map((i) => <InboxCard key={i.id} item={i} />)
        )}
      </QueryState>
    </div>
  );
}
