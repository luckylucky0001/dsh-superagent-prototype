import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Breadcrumb,
  Button,
  Card,
  Col,
  Descriptions,
  Divider,
  Flex,
  Input,
  Mentions,
  Row,
  Select,
  Space,
  Tag,
  Tooltip,
  Typography,
  Upload,
} from 'antd';
import { EditOutlined, PaperClipOutlined, RobotOutlined, SendOutlined } from '@ant-design/icons';
import { api } from '@/api/client';
import { qk, useAgentMap, useAgents, useMemberMap, useProjectMap } from '@/api/queries';
import { ISSUE_PRIORITIES, ISSUE_STATUSES, type Comment, type Issue, type IssuePatch, type Task } from '@/api/types';
import { useMe } from '@/app/auth';
import { useT } from '@/app/i18n';
import { AgentAvatar, MemberAvatar, ProviderTag } from '@/components/AgentAvatar';
import { Markdown } from '@/components/Markdown';
import { EmptyState, QueryState } from '@/components/QueryState';
import { PriorityIcon, StatusTag } from '@/components/StatusTag';
import { fmtDateTime, fromNow } from '@/components/format';
import { useNotify } from '@/components/useNotify';
import { TaskCard } from '@/features/tasks/TaskCard';
import { assigneeValue, parseAssignee, useAssigneeOptions, useLabelOptions } from './NewIssueModal';

function CommentItem({ comment }: { comment: Comment }) {
  const t = useT();
  const me = useMe();
  const agents = useAgentMap();
  const members = useMemberMap();
  const agent = comment.authorType === 'agent' && comment.authorId ? agents.get(comment.authorId) : undefined;
  let name: string;
  let avatar: React.ReactNode;
  if (comment.authorType === 'agent') {
    name = agent?.name ?? t('common.agent');
    avatar = <AgentAvatar agent={agent} size={32} />;
  } else if (comment.authorType === 'system') {
    name = 'Orca';
    avatar = <MemberAvatar size={32} name="Orca" />;
  } else {
    name = (comment.authorId && members.get(comment.authorId)?.name)
      ?? (comment.authorId && comment.authorId === me.data?.id ? me.data.name : t('common.member'));
    avatar = <MemberAvatar size={32} name={name} />;
  }
  return (
    <div className="orca-timeline-item">
      {avatar}
      <div className={`orca-comment ${comment.authorType}`}>
        <Flex className="orca-comment-head" gap={8} align="center" wrap="wrap">
          <Typography.Text strong>{name}</Typography.Text>
          {agent && <ProviderTag provider={agent.provider} small />}
          {comment.authorType === 'system' && <Tag bordered={false}>{t('issues.system')}</Tag>}
          <Tooltip title={fmtDateTime(comment.createdAt)}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{fromNow(comment.createdAt)}</Typography.Text>
          </Tooltip>
          {comment.taskId && (
            <Link to={`/tasks/${comment.taskId}`} style={{ fontSize: 12 }}>{t('issues.fromTask')}</Link>
          )}
        </Flex>
        <Markdown>{comment.body}</Markdown>
      </div>
    </div>
  );
}

function TaskTimelineItem({ task }: { task: Task }) {
  return (
    <div className="orca-timeline-item">
      <div style={{ width: 32, flex: 'none', display: 'flex', justifyContent: 'center', paddingTop: 8 }}>
        <RobotOutlined style={{ color: '#8c8c8c', fontSize: 16 }} />
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <TaskCard task={task} />
      </div>
    </div>
  );
}

function CommentBox({ issue }: { issue: Issue }) {
  const t = useT();
  const notify = useNotify();
  const qc = useQueryClient();
  const agents = useAgents();
  const [body, setBody] = useState('');
  const [uploading, setUploading] = useState(false);
  const attach = async (files: File[]) => {
    const images = files.filter((f) => f.size > 0);
    if (!images.length) return;
    setUploading(true);
    try {
      const links: string[] = [];
      for (const f of images) {
        const a = await api.uploadAttachment(issue.id, f);
        links.push(a.contentType.startsWith('image/') ? `![${a.name}](${a.url})` : `[${a.name}](${a.url})`);
      }
      setBody((b) => (b.trim() ? `${b.trimEnd()}\n\n` : '') + links.join('\n'));
    } catch (e) {
      notify.error(e);
    } finally {
      setUploading(false);
    }
  };
  const post = useMutation({
    mutationFn: () => api.addComment(issue.id, body),
    onSuccess: (c) => {
      setBody('');
      qc.setQueryData<Comment[]>(qk.comments(issue.id), (list) => (list?.some((x) => x.id === c.id) ? list : [...(list ?? []), c]));
      void qc.invalidateQueries({ queryKey: qk.issueTasks(issue.id) });
    },
    onError: notify.error,
  });
  return (
    <Card size="small"
      onPaste={(e) => {
        const files = Array.from(e.clipboardData?.files ?? []);
        if (files.length) {
          e.preventDefault();
          void attach(files);
        }
      }}
      onDrop={(e) => {
        const files = Array.from(e.dataTransfer?.files ?? []);
        if (files.length) {
          e.preventDefault();
          void attach(files);
        }
      }}
      onDragOver={(e) => e.preventDefault()}>
      <Mentions
        value={body}
        onChange={setBody}
        autoSize={{ minRows: 3, maxRows: 12 }}
        placeholder={t('issues.commentPlaceholder')}
        options={(agents.data ?? []).map((a) => ({ value: a.name, label: <Space size={6}><AgentAvatar agent={a} size={18} />{a.name}</Space> }))}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && (e.metaKey || e.ctrlKey) && body.trim()) {
            e.preventDefault();
            post.mutate();
          }
        }}
        variant="borderless"
      />
      <Flex justify="space-between" align="center" style={{ marginTop: 8 }} gap={8} wrap="wrap">
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('issues.mentionHint')} {t('issues.attachHint')}</Typography.Text>
        <Space>
          <Upload showUploadList={false} multiple beforeUpload={(f) => { void attach([f]); return false; }}>
            <Button icon={<PaperClipOutlined />} loading={uploading}>{t('issues.attach')}</Button>
          </Upload>
          <Button type="primary" icon={<SendOutlined />} disabled={!body.trim() || uploading} loading={post.isPending} onClick={() => post.mutate()}>
            {t('issues.comment')}
          </Button>
        </Space>
      </Flex>
    </Card>
  );
}

function Description({ issue, onSave, saving }: { issue: Issue; onSave: (d: string) => void; saving: boolean }) {
  const t = useT();
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState('');
  if (editing) {
    return (
      <div>
        <Input.TextArea value={text} onChange={(e) => setText(e.target.value)} autoSize={{ minRows: 4, maxRows: 24 }} autoFocus />
        <Space style={{ marginTop: 8 }}>
          <Button type="primary" size="small" loading={saving} onClick={() => { onSave(text); setEditing(false); }}>{t('common.save')}</Button>
          <Button size="small" onClick={() => setEditing(false)}>{t('common.cancel')}</Button>
        </Space>
      </div>
    );
  }
  return (
    <div className="orca-description" onDoubleClick={() => { setText(issue.description ?? ''); setEditing(true); }}>
      {issue.description ? <Markdown>{issue.description}</Markdown> : <Typography.Text type="secondary">{t('issues.noDescription')}</Typography.Text>}
      <Button type="link" size="small" icon={<EditOutlined />} style={{ paddingInline: 0, marginTop: 4 }}
        onClick={() => { setText(issue.description ?? ''); setEditing(true); }}>
        {t('issues.editDescription')}
      </Button>
    </div>
  );
}

function Properties({ issue, update }: { issue: Issue; update: (p: IssuePatch) => void }) {
  const t = useT();
  const assigneeOptions = useAssigneeOptions();
  const project = useProjectMap().get(issue.projectId);
  const labelOptions = useLabelOptions();
  const runs = useQuery({ queryKey: qk.issueRuns(issue.id), queryFn: () => api.issueRuns(issue.id) });
  return (
    <Card size="small" title={t('issues.properties')}>
      <Space direction="vertical" size={14} style={{ display: 'flex' }}>
        <div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('issues.status')}</Typography.Text>
          <Select value={issue.status} style={{ width: '100%' }} onChange={(status) => update({ status })}
            options={ISSUE_STATUSES.map((s) => ({ value: s, label: <StatusTag status={s} /> }))} />
        </div>
        <div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('issues.priority')}</Typography.Text>
          <Select value={issue.priority} style={{ width: '100%' }} onChange={(priority) => update({ priority })}
            options={ISSUE_PRIORITIES.map((p) => ({ value: p, label: <Space size={6}><PriorityIcon priority={p} />{t.dyn(`priority.${p}`, p)}</Space> }))} />
        </div>
        <div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('issues.assignee')}</Typography.Text>
          <Select
            value={assigneeValue(issue.assigneeType, issue.assigneeId)}
            style={{ width: '100%' }}
            allowClear
            showSearch
            optionFilterProp="searchText"
            placeholder={t('issues.unassigned')}
            options={assigneeOptions}
            onChange={(v?: string) => {
              const a = parseAssignee(v);
              update(a ?? { clearAssignee: true });
            }}
          />
          <Typography.Text type="secondary" style={{ fontSize: 11 }}>{t('issues.assignHint')}</Typography.Text>
        </div>
        <div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('issues.labels')}</Typography.Text>
          <Select mode="tags" value={issue.labels} style={{ width: '100%' }} options={labelOptions} tokenSeparators={[',']}
            onChange={(labels: string[]) => update({ labels })} placeholder={t('issues.labelsPlaceholder')} />
        </div>
        <Divider style={{ margin: '2px 0' }} />
        <Descriptions size="small" column={1} colon={false}
          items={[
            { key: 'p', label: t('issues.project'), children: project ? `${project.key} · ${project.name}` : '—' },
            { key: 'c', label: t('common.created'), children: fmtDateTime(issue.createdAt) },
            { key: 'u', label: t('common.updated'), children: fromNow(issue.updatedAt) },
          ]} />
        <div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('issues.workflowRuns')}</Typography.Text>
          {(runs.data ?? []).length === 0 ? (
            <div><Typography.Text type="secondary" style={{ fontSize: 12 }}>—</Typography.Text></div>
          ) : (
            <Space direction="vertical" size={4} style={{ display: 'flex', marginTop: 4 }}>
              {runs.data!.slice(0, 8).map((r) => (
                <Flex key={r.id} justify="space-between" align="center" gap={6}>
                  <Link to={`/runs/${r.id}`} style={{ fontSize: 12, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {r.workflowName}
                  </Link>
                  <StatusTag status={r.status} />
                </Flex>
              ))}
            </Space>
          )}
        </div>
      </Space>
    </Card>
  );
}

export function IssueDetail() {
  const { key = '' } = useParams();
  const t = useT();
  const notify = useNotify();
  const qc = useQueryClient();
  const issue = useQuery({ queryKey: qk.issue(key), queryFn: () => api.issue(key) });
  const issueId = issue.data?.id;
  const comments = useQuery({ queryKey: qk.comments(issueId ?? ''), queryFn: () => api.comments(issueId!), enabled: !!issueId });
  const tasks = useQuery({ queryKey: qk.issueTasks(issueId ?? ''), queryFn: () => api.issueTasks(issueId!), enabled: !!issueId });

  const update = useMutation({
    mutationFn: (patch: IssuePatch) => api.updateIssue(issueId!, patch),
    onMutate: (patch) => {
      const prev = qc.getQueryData<Issue>(qk.issue(key));
      if (prev) {
        const next: Issue = { ...prev };
        if (patch.status) next.status = patch.status;
        if (patch.priority) next.priority = patch.priority;
        if (patch.labels) next.labels = patch.labels;
        if (patch.title) next.title = patch.title;
        if (patch.description !== undefined) next.description = patch.description;
        if (patch.clearAssignee) {
          next.assigneeId = null;
          next.assigneeType = null;
        } else if (patch.assigneeId) {
          next.assigneeId = patch.assigneeId;
          next.assigneeType = patch.assigneeType ?? 'agent';
        }
        qc.setQueryData(qk.issue(key), next);
      }
      return { prev };
    },
    onSuccess: (updated, patch) => {
      qc.setQueryData(qk.issue(key), updated);
      void qc.invalidateQueries({ queryKey: qk.issues });
      if (patch.assigneeId && patch.assigneeType === 'agent') notify.success(t('issues.assignedToast'));
    },
    onError: (e, _p, ctx) => {
      if (ctx?.prev) qc.setQueryData(qk.issue(key), ctx.prev);
      notify.error(e);
    },
  });

  const timeline = useMemo(() => {
    const entries: ({ at: number; kind: 'comment'; c: Comment } | { at: number; kind: 'task'; task: Task })[] = [];
    for (const c of comments.data ?? []) entries.push({ at: c.createdAt, kind: 'comment', c });
    for (const task of tasks.data ?? []) entries.push({ at: task.queuedAt, kind: 'task', task });
    return entries.sort((a, b) => a.at - b.at);
  }, [comments.data, tasks.data]);

  return (
    <QueryState isLoading={issue.isLoading} error={issue.error} onRetry={() => void issue.refetch()} fullPage>
      {issue.data && (
        <>
          <Breadcrumb style={{ marginBottom: 8 }} items={[{ title: <Link to="/issues">{t('nav.issues')}</Link> }, { title: issue.data.key }]} />
          <Row gutter={20}>
            <Col xs={24} lg={17}>
              <Card style={{ marginBottom: 16 }}>
                <Typography.Title level={4} style={{ marginTop: 0 }}
                  editable={{ onChange: (title) => title.trim() && title !== issue.data!.title && update.mutate({ title }), triggerType: ['icon', 'text'] }}>
                  {issue.data.title}
                </Typography.Title>
                <Description issue={issue.data} saving={update.isPending} onSave={(description) => update.mutate({ description })} />
              </Card>
              <Typography.Title level={5} style={{ margin: '4px 0 12px' }}>{t('issues.activity')}</Typography.Title>
              <QueryState isLoading={comments.isLoading || tasks.isLoading} error={comments.error ?? tasks.error}>
                {timeline.length === 0 && <EmptyState description={t('issues.noActivity')} />}
                {timeline.map((e) =>
                  e.kind === 'comment' ? <CommentItem key={e.c.id} comment={e.c} /> : <TaskTimelineItem key={e.task.id} task={e.task} />,
                )}
              </QueryState>
              <CommentBox issue={issue.data} />
            </Col>
            <Col xs={24} lg={7}>
              <div style={{ position: 'sticky', top: 72 }}>
                <Properties issue={issue.data} update={(p) => update.mutate(p)} />
              </div>
            </Col>
          </Row>
        </>
      )}
    </QueryState>
  );
}
