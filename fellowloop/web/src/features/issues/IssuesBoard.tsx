import { useEffect, useMemo, useState, type DragEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Badge, Button, Flex, Input, Select, Space, Switch, Tag, Tooltip, Typography } from 'antd';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { api } from '@/api/client';
import { qk, useAgentMap, useMemberMap, useProjects } from '@/api/queries';
import type { Agent, Issue, IssueStatus, Member } from '@/api/types';
import { useT } from '@/app/i18n';
import { AgentAvatar, MemberAvatar, ProviderTag } from '@/components/AgentAvatar';
import { PageHeader } from '@/components/PageHeader';
import { SetupChecklist } from './SetupChecklist';
import { QueryState } from '@/components/QueryState';
import { PriorityIcon } from '@/components/StatusTag';
import { fromNow } from '@/components/format';
import { useNotify } from '@/components/useNotify';
import { NewIssueModal } from './NewIssueModal';

const COLUMNS: IssueStatus[] = ['backlog', 'todo', 'in_progress', 'in_review', 'done'];
const PRIORITY_ORDER: Record<string, number> = { urgent: 0, high: 1, medium: 2, low: 3, none: 4 };
const DOT: Record<string, string> = {
  backlog: '#bfbfbf',
  todo: '#4096ff',
  in_progress: '#faad14',
  in_review: '#9254de',
  done: '#52c41a',
  cancelled: '#8c8c8c',
};

const PROJECT_KEY = 'fellowloop.project';
const ALL = '__all';

/** The board's project filter, remembered in localStorage ('__all' = every project). */
export function useSelectedProject(): [string, (id: string) => void] {
  const [value, setValue] = useState<string>(() => {
    try {
      return localStorage.getItem(PROJECT_KEY) ?? ALL;
    } catch {
      return ALL;
    }
  });
  const set = (id: string) => {
    setValue(id);
    try {
      localStorage.setItem(PROJECT_KEY, id);
    } catch {
      /* ignore */
    }
  };
  return [value, set];
}

function IssueCard({ issue, agent, member, onDragStart, onDragEnd, dragging }: {
  issue: Issue; agent?: Agent; member?: Member; dragging: boolean;
  onDragStart: (e: DragEvent) => void; onDragEnd: () => void;
}) {
  const t = useT();
  const navigate = useNavigate();
  return (
    <div
      className={`fellowloop-issue-card ${dragging ? 'dragging' : ''}`}
      draggable
      onDragStart={onDragStart}
      onDragEnd={onDragEnd}
      onClick={() => navigate(`/issues/${issue.key}`)}
      role="link"
      tabIndex={0}
      onKeyDown={(e) => e.key === 'Enter' && navigate(`/issues/${issue.key}`)}
    >
      <Flex justify="space-between" align="center" style={{ marginBottom: 4 }}>
        <Space size={6}>
          <Tooltip title={t.dyn(`priority.${issue.priority}`, issue.priority)}>
            <span><PriorityIcon priority={issue.priority} /></span>
          </Tooltip>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{issue.key}</Typography.Text>
        </Space>
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>{fromNow(issue.updatedAt)}</Typography.Text>
      </Flex>
      <Typography.Paragraph style={{ margin: 0, fontWeight: 500 }} ellipsis={{ rows: 3 }}>{issue.title}</Typography.Paragraph>
      {(issue.labels.length > 0 || agent || member) && (
        <Flex justify="space-between" align="center" style={{ marginTop: 8 }} gap={6}>
          <Flex gap={4} wrap="wrap" style={{ minWidth: 0 }}>
            {issue.labels.map((l) => (
              <Tag key={l} bordered={false} style={{ marginInlineEnd: 0, fontSize: 11 }}>{l}</Tag>
            ))}
          </Flex>
          {member && (
            <Tooltip title={member.name}>
              <span style={{ flex: 'none' }}><MemberAvatar name={member.name} size={22} /></span>
            </Tooltip>
          )}
          {agent && (
            <Tooltip title={`${agent.name} · ${agent.model ?? agent.provider}`}>
              <Space size={4} style={{ flex: 'none' }}>
                <ProviderTag provider={agent.provider} small />
                <AgentAvatar agent={agent} size={22} />
              </Space>
            </Tooltip>
          )}
        </Flex>
      )}
    </div>
  );
}

export function IssuesBoard() {
  const t = useT();
  const notify = useNotify();
  const qc = useQueryClient();
  const [project, setProject] = useSelectedProject();
  const projects = useProjects();
  const projectId = project === ALL ? undefined : project;
  const issues = useQuery({ queryKey: qk.projectIssues(projectId), queryFn: () => api.issues(projectId) });
  const agents = useAgentMap();
  const members = useMemberMap();
  // a remembered project that no longer exists falls back to all projects
  useEffect(() => {
    if (projectId && projects.data && !projects.data.some((p) => p.id === projectId)) setProject(ALL);
  }, [projectId, projects.data]); // eslint-disable-line react-hooks/exhaustive-deps
  const [showCancelled, setShowCancelled] = useState(false);
  const [creating, setCreating] = useState<IssueStatus | null>(null);
  const [search, setSearch] = useState('');
  const [assignee, setAssignee] = useState<string | undefined>();
  const [dragId, setDragId] = useState<string | null>(null);
  const [dropCol, setDropCol] = useState<IssueStatus | null>(null);

  const move = useMutation({
    mutationFn: ({ id, status }: { id: string; status: IssueStatus }) => api.updateIssue(id, { status }),
    onMutate: async ({ id, status }) => {
      const key = qk.projectIssues(projectId);
      await qc.cancelQueries({ queryKey: key });
      const prev = qc.getQueryData<Issue[]>(key);
      qc.setQueryData<Issue[]>(key, (list) => list?.map((i) => (i.id === id ? { ...i, status, updatedAt: Date.now() } : i)));
      return { prev };
    },
    onError: (e, _v, ctx) => {
      if (ctx?.prev) qc.setQueryData(qk.projectIssues(projectId), ctx.prev);
      notify.error(e);
    },
    onSettled: () => void qc.invalidateQueries({ queryKey: qk.issues }),
  });

  const columns = showCancelled ? [...COLUMNS, 'cancelled' as IssueStatus] : COLUMNS;
  const grouped = useMemo(() => {
    const m = new Map<IssueStatus, Issue[]>();
    for (const c of [...COLUMNS, 'cancelled' as IssueStatus]) m.set(c, []);
    const q = search.trim().toLowerCase();
    for (const i of issues.data ?? []) {
      if (q && !`${i.key} ${i.title} ${i.labels.join(' ')}`.toLowerCase().includes(q)) continue;
      if (assignee === '__none' ? !!i.assigneeId : assignee && `${i.assigneeType}:${i.assigneeId}` !== assignee) continue;
      m.get(i.status)?.push(i);
    }
    for (const list of m.values()) {
      list.sort((a, b) => (PRIORITY_ORDER[a.priority] ?? 9) - (PRIORITY_ORDER[b.priority] ?? 9) || b.updatedAt - a.updatedAt);
    }
    return m;
  }, [issues.data, search, assignee]);

  const onDrop = (status: IssueStatus) => (e: DragEvent) => {
    e.preventDefault();
    const id = e.dataTransfer.getData('text/fellowloop-issue') || dragId;
    setDropCol(null);
    setDragId(null);
    const issue = issues.data?.find((i) => i.id === id);
    if (issue && issue.status !== status) move.mutate({ id: issue.id, status });
  };

  return (
    <>
      <PageHeader
        title={t('nav.issues')}
        extra={
          <>
            <Select value={project} onChange={setProject} style={{ width: 200 }} loading={projects.isLoading}
              options={[{ value: ALL, label: t('issues.allProjects') }, ...(projects.data ?? []).map((p) => ({ value: p.id, label: `${p.key} · ${p.name}` }))]} />
            <Input allowClear prefix={<SearchOutlined />} placeholder={t('issues.search')} value={search}
              onChange={(e) => setSearch(e.target.value)} style={{ width: 220 }} />
            <Select allowClear placeholder={t('issues.assignee')} value={assignee} onChange={setAssignee} style={{ width: 170 }}
              options={[
                { value: '__none', label: t('issues.unassigned') },
                ...[...agents.values()].map((a) => ({ value: `agent:${a.id}`, label: `${a.name} · ${a.provider}` })),
                ...[...members.values()].map((m) => ({ value: `member:${m.id}`, label: m.name })),
              ]} />
            <Space size={6}>
              <Switch size="small" checked={showCancelled} onChange={setShowCancelled} />
              <Typography.Text type="secondary">{t('issues.showCancelled')}</Typography.Text>
            </Space>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating('todo')}>{t('issues.new')}</Button>
          </>
        }
      />
      <SetupChecklist onNewIssue={() => setCreating('todo')} />
      <QueryState isLoading={issues.isLoading} error={issues.error} onRetry={() => void issues.refetch()}>
        <div className="fellowloop-board">
          {columns.map((status) => {
            const list = grouped.get(status) ?? [];
            return (
              <div
                key={status}
                className={`fellowloop-column ${dropCol === status ? 'drop' : ''}`}
                onDragOver={(e) => {
                  e.preventDefault();
                  e.dataTransfer.dropEffect = 'move';
                  if (dropCol !== status) setDropCol(status);
                }}
                onDragLeave={(e) => {
                  if (!e.currentTarget.contains(e.relatedTarget as Node)) setDropCol(null);
                }}
                onDrop={onDrop(status)}
              >
                <Flex className="fellowloop-column-head" justify="space-between" align="center">
                  <Space size={8}>
                    <Badge color={DOT[status]} />
                    <Typography.Text strong>{t.dyn(`status.${status}`, status)}</Typography.Text>
                    <Typography.Text type="secondary">{list.length}</Typography.Text>
                  </Space>
                  <Tooltip title={t('issues.new')}>
                    <Button type="text" size="small" icon={<PlusOutlined />} onClick={() => setCreating(status)} />
                  </Tooltip>
                </Flex>
                {list.map((i) => (
                  <IssueCard
                    key={i.id}
                    issue={i}
                    agent={i.assigneeType === 'agent' && i.assigneeId ? agents.get(i.assigneeId) : undefined}
                    member={i.assigneeType === 'member' && i.assigneeId ? members.get(i.assigneeId) : undefined}
                    dragging={dragId === i.id}
                    onDragStart={(e) => {
                      e.dataTransfer.setData('text/fellowloop-issue', i.id);
                      e.dataTransfer.effectAllowed = 'move';
                      setDragId(i.id);
                    }}
                    onDragEnd={() => {
                      setDragId(null);
                      setDropCol(null);
                    }}
                  />
                ))}
                {list.length === 0 && (
                  <Typography.Text type="secondary" style={{ display: 'block', textAlign: 'center', padding: '24px 0', fontSize: 12 }}>
                    {t('issues.emptyColumn')}
                  </Typography.Text>
                )}
              </div>
            );
          })}
        </div>
      </QueryState>
      <NewIssueModal open={creating !== null} defaultStatus={creating ?? undefined} defaultProjectId={projectId}
        onClose={() => setCreating(null)} />
    </>
  );
}
