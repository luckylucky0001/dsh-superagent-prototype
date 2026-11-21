import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Checkbox, Flex, Input, Popconfirm, Progress, Space, Spin, Tag, Tooltip, Typography } from 'antd';
import {
  BulbOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  DownOutlined,
  FileSearchOutlined,
  InfoCircleOutlined,
  QuestionCircleOutlined,
  RightOutlined,
  SafetyOutlined,
  SendOutlined,
  StopOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { api } from '@/api/client';
import { qk, useRuntimeMap, useTaskEvents } from '@/api/queries';
import { ACTIVE_TASK_STATUSES, taskTokensOf, TERMINAL_TASK_STATUSES, type Task, type Usage } from '@/api/types';
import { useT } from '@/app/i18n';
import { useMe } from '@/app/auth';
import { Markdown } from '../Markdown';
import { DiffView, JsonView } from '../CodeBlock';
import { fmtTime, fmtTokens, prettyJson } from '../format';
import { useNotify } from '../useNotify';
import { buildTranscript, type TranscriptItem } from './model';
import { ToolCard } from './ToolCard';

function Collapsible({ title, icon, children, defaultOpen = false, muted }: {
  title: React.ReactNode; icon?: React.ReactNode; children: React.ReactNode; defaultOpen?: boolean; muted?: boolean;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className={`fellowloop-collapsible ${muted ? 'muted' : ''}`}>
      <Flex align="center" gap={6} className="fellowloop-collapsible-head" onClick={() => setOpen((o) => !o)}>
        {open ? <DownOutlined style={{ fontSize: 10 }} /> : <RightOutlined style={{ fontSize: 10 }} />}
        {icon}
        <span>{title}</span>
      </Flex>
      {open && <div className="fellowloop-collapsible-body">{children}</div>}
    </div>
  );
}

function ItemView({ item, taskId }: { item: TranscriptItem; taskId: string }) {
  const t = useT();
  switch (item.kind) {
    case 'session':
      return (
        <div className="fellowloop-tx-meta">
          <InfoCircleOutlined />{' '}
          {item.resumed ? t('transcript.sessionResumed') : t('transcript.sessionStarted')}
          {item.model && <> · <span className="fellowloop-mono">{item.model}</span></>}
          {' · '}{fmtTime(item.ts)}
        </div>
      );
    case 'user':
      return (
        <div className="fellowloop-tx-user">
          <div className="fellowloop-tx-user-label"><UserOutlined /> {t('transcript.prompt')}</div>
          <Markdown>{item.text}</Markdown>
        </div>
      );
    case 'message':
      return (
        <div className={`fellowloop-tx-msg ${item.phase === 'final_answer' ? 'final' : ''}`}>
          {item.phase === 'final_answer' && <Tag color="success" bordered={false} style={{ marginBottom: 4 }}>{t('transcript.finalAnswer')}</Tag>}
          <Markdown>{item.text + (item.streaming ? ' ▍' : '')}</Markdown>
        </div>
      );
    case 'reasoning':
      return (
        <Collapsible muted icon={<BulbOutlined />} title={item.streaming ? t('transcript.thinking') : t('transcript.reasoning')}>
          <Markdown className="muted">{item.text}</Markdown>
        </Collapsible>
      );
    case 'tool':
      return <ToolCard item={item} />;
    case 'plan':
      return (
        <div className="fellowloop-tx-plan">
          <Typography.Text strong style={{ fontSize: 13 }}>{t('transcript.plan')}</Typography.Text>
          {item.explanation && <Typography.Paragraph type="secondary" style={{ margin: '2px 0 6px', fontSize: 12 }}>{item.explanation}</Typography.Paragraph>}
          <Space direction="vertical" size={2} style={{ display: 'flex' }}>
            {item.entries.map((en, i) => (
              <Checkbox key={i} checked={en.status === 'completed'} indeterminate={en.status === 'in_progress'} style={{ pointerEvents: 'none' }}>
                <span style={{ textDecoration: en.status === 'completed' ? 'line-through' : undefined, color: en.status === 'completed' ? '#8c8c8c' : undefined }}>
                  {en.step}
                </span>
              </Checkbox>
            ))}
          </Space>
        </div>
      );
    case 'diff': {
      const files = (item.diff.match(/^diff --git /gm) ?? []).length;
      return (
        <Collapsible icon={<FileSearchOutlined />} title={t('transcript.turnDiff', { n: Math.max(files, 1) })}>
          <DiffView diff={item.diff} />
        </Collapsible>
      );
    }
    case 'approval': {
      const outcome = decisionOutcome(item.decision);
      const approved = outcome === 'approved' || outcome === 'approvedSession';
      const by = item.by === 'policy' || item.by === 'human' ? item.by : 'none';
      return (
        <Alert
          type={!item.decision ? 'warning' : approved ? 'success' : 'error'}
          showIcon
          icon={<SafetyOutlined />}
          message={
            <Space wrap>
              <span>{item.title ?? t('transcript.approvalRequested')}</span>
              {item.approvalKind && <Tag bordered={false}>{item.approvalKind}</Tag>}
              {item.decision ? (
                <Tooltip title={item.decision}>
                  <Tag color={approved ? 'success' : outcome === 'cancelled' ? 'default' : 'error'} icon={approved ? <CheckCircleOutlined /> : <CloseCircleOutlined />}>
                    {outcome ? t.dyn(`transcript.decision.${outcome}.${by}`, item.decision ?? '') : item.decision}
                  </Tag>
                </Tooltip>
              ) : (
                <Tag color="warning">{t('transcript.awaitingApproval')}</Tag>
              )}
            </Space>
          }
          description={item.detail ? (typeof item.detail === 'string' ? <span className="fellowloop-mono">{item.detail}</span> : <JsonView value={item.detail} maxHeight={160} />) : undefined}
        />
      );
    }
    case 'input':
      return (
        <Alert
          type={item.resolved ? 'info' : 'warning'}
          showIcon
          icon={<QuestionCircleOutlined />}
          message={
            <Space wrap>
              <span>{item.title ?? t('transcript.inputRequested')}</span>
              {item.resolved ? <Tag color="success">{t('transcript.answered')}</Tag> : <Link to="/inbox">{t('transcript.answerInInbox')}</Link>}
            </Space>
          }
          description={
            <>
              {item.questions?.map((q) => (
                <div key={q.id}>• {q.question ?? q.header}</div>
              ))}
              {item.resolved && item.answer !== undefined && (
                <Typography.Text type="secondary" className="fellowloop-mono" style={{ fontSize: 12 }}>
                  {prettyJson(item.answer)}
                </Typography.Text>
              )}
            </>
          }
        />
      );
    case 'notice':
      return (
        <Alert
          type={item.level === 'error' ? 'error' : item.level === 'warn' || item.level === 'warning' ? 'warning' : 'info'}
          showIcon
          message={item.message}
          description={item.code ? <span className="fellowloop-mono">{item.code}</span> : undefined}
          style={{ padding: '6px 12px' }}
        />
      );
    case 'turn': {
      const ok = item.status === 'completed';
      return (
        <div className={`fellowloop-tx-turn ${ok ? 'ok' : 'bad'}`} data-task={taskId}>
          {ok ? <CheckCircleOutlined /> : <CloseCircleOutlined />} {t.dyn(`transcript.turn.${item.status}`, item.status)}
          {item.error && <> — {item.error}</>}
          {item.errorCode && <Tag bordered={false} style={{ marginInlineStart: 8 }}>{item.errorCode}</Tag>}
          <span className="fellowloop-tx-time">{fmtTime(item.ts)}</span>
        </div>
      );
    }
  }
}

/** Maps provider decisions (accept, accept_for_session, decline, cancel, approved, rejected…) to an outcome. */
function decisionOutcome(d: string | undefined): 'approved' | 'approvedSession' | 'declined' | 'cancelled' | null {
  if (!d) return null;
  const s = d.toLowerCase();
  if (s.includes('session')) return 'approvedSession';
  if (/^(accept|approve|approved|allow|yes)/.test(s)) return 'approved';
  if (/cancel|abort/.test(s)) return 'cancelled';
  if (/decline|deny|denied|reject|no/.test(s)) return 'declined';
  return null;
}

function UsageFooter({ usage }: { usage: Usage }) {
  const t = useT();
  const window = usage.contextWindow ?? 0;
  // TRAE reports what the context holds (contextUsed); Codex the size of the last request
  const used = usage.contextUsed ?? usage.lastTotalTokens ?? usage.totalTokens ?? 0;
  const pct = window > 0 ? Math.min(100, Math.round((used / window) * 100)) : null;
  return (
    <Flex className="fellowloop-tx-usage" gap={16} wrap="wrap" align="center">
      <Tooltip title={usage.taskTokens !== undefined ? t('transcript.taskTokensTooltip') : t('transcript.usageTooltip')}>
        <span>{t('transcript.tokens')}: {fmtTokens(taskTokensOf(usage))}</span>
      </Tooltip>
      <Tooltip
        title={
          <>
            <div>{t('transcript.input')}: {fmtTokens(usage.inputTokens)}{usage.cachedInputTokens ? ` (${t('transcript.cached')} ${fmtTokens(usage.cachedInputTokens)})` : ''}</div>
            <div>{t('transcript.output')}: {fmtTokens(usage.outputTokens)}</div>
            {!!usage.reasoningTokens && <div>{t('transcript.reasoningTokens')}: {fmtTokens(usage.reasoningTokens)}</div>}
          </>
        }
      >
        <span>{t('transcript.session')}: {fmtTokens(usage.totalTokens)}</span>
      </Tooltip>
      {pct !== null && (
        <Tooltip title={`${fmtTokens(used)} / ${fmtTokens(window)}`}>
          <Flex align="center" gap={6}>
            {t('transcript.context')}
            <Progress percent={pct} size="small" style={{ width: 90, margin: 0 }} showInfo={false}
              strokeColor={pct > 85 ? '#ff4d4f' : pct > 60 ? '#faad14' : undefined} />
            {pct}%
          </Flex>
        </Tooltip>
      )}
    </Flex>
  );
}

function SteerBox({ task }: { task: Task }) {
  const t = useT();
  const notify = useNotify();
  const runtimes = useRuntimeMap();
  const [text, setText] = useState('');
  const provider = task.runtimeId ? runtimes.get(task.runtimeId)?.providers.find((p) => p.provider === task.provider) : undefined;
  const canSteer = provider ? provider.capabilities.includes('steer') : undefined;
  const steer = useMutation({
    mutationFn: () => api.steerTask(task.id, text.trim()),
    onSuccess: () => {
      setText('');
      notify.success(canSteer === false ? t('transcript.steerQueued') : t('transcript.steerSent'));
    },
    onError: notify.error,
  });
  return (
    <div className="fellowloop-tx-steer">
      <Space.Compact style={{ width: '100%' }}>
        <Input.TextArea
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={t('transcript.steerPlaceholder')}
          autoSize={{ minRows: 1, maxRows: 5 }}
          onPressEnter={(e) => {
            if (!e.shiftKey && text.trim()) {
              e.preventDefault();
              steer.mutate();
            }
          }}
        />
        <Button type="primary" icon={<SendOutlined />} loading={steer.isPending} disabled={!text.trim()} onClick={() => steer.mutate()}>
          {t('transcript.steer')}
        </Button>
      </Space.Compact>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {canSteer === false ? t('transcript.steerHintNoSteer') : t('transcript.steerHint')}
      </Typography.Text>
    </div>
  );
}

/** An Ask is the asker's conversation: the server lets only them (or an owner/admin) steer or cancel it. */
export function useCanControl(task: Task): boolean {
  const me = useMe().data;
  if (task.kind !== 'ask') return true;
  const by = task.origin?.by;
  if (by == null || !me) return true;
  return by === me.id || me.role === 'owner' || me.role === 'admin';
}

export function CancelTaskButton({ task, size = 'small' }: { task: Task; size?: 'small' | 'middle' }) {
  const t = useT();
  const notify = useNotify();
  const qc = useQueryClient();
  const cancel = useMutation({
    mutationFn: () => api.cancelTask(task.id),
    onSuccess: (updated) => {
      qc.setQueryData(qk.task(task.id), updated);
      void qc.invalidateQueries({ queryKey: ['tasks'] });
      notify.success(t('transcript.cancelRequested'));
    },
    onError: notify.error,
  });
  const canControl = useCanControl(task);
  if (TERMINAL_TASK_STATUSES.includes(task.status) || !canControl) return null;
  return (
    <Popconfirm title={t('transcript.cancelConfirm')} onConfirm={() => cancel.mutate()} okButtonProps={{ danger: true }}>
      <Button danger size={size} icon={<StopOutlined />} loading={cancel.isPending || task.statusDetail === 'cancelling'}
        onClick={(e) => e.stopPropagation()}>
        {t('transcript.cancel')}
      </Button>
    </Popconfirm>
  );
}

interface TranscriptProps {
  task: Task;
  /** Height of the scrolling area; 'auto' grows with the content. */
  maxHeight?: number | string;
  showControls?: boolean;
}

/**
 * The live transcript of an agent task: fetches stored events, then follows `task.events` from the
 * realtime stream (merged into the same query cache by {@link RealtimeProvider}).
 */
export function Transcript({ task, maxHeight = 560, showControls = true }: TranscriptProps) {
  const t = useT();
  const qc = useQueryClient();
  const events = useTaskEvents(task.id, qc);
  const transcript = useMemo(() => buildTranscript(events.data ?? []), [events.data]);
  const scroller = useRef<HTMLDivElement>(null);
  const active = ACTIVE_TASK_STATUSES.includes(task.status);
  const canControl = useCanControl(task);
  // follow the end of live transcripts; finished ones open at the prompt
  const stick = useRef(active || task.status === 'queued');
  const lastSeq = events.data?.length ? events.data[events.data.length - 1].seq : 0;

  // the task row knows the last stored seq; catch up if realtime missed something
  useEffect(() => {
    if (!events.isFetching && events.data && task.lastSeq > lastSeq) {
      void qc.invalidateQueries({ queryKey: qk.taskEvents(task.id) });
    }
  }, [task.lastSeq, lastSeq, events.isFetching, events.data, qc, task.id]);

  useLayoutEffect(() => {
    const el = scroller.current;
    if (el && stick.current) el.scrollTop = el.scrollHeight;
  }, [transcript]);

  const onScroll = () => {
    const el = scroller.current;
    if (el) stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
  };

  return (
    <div className="fellowloop-transcript">
      <div ref={scroller} className="fellowloop-tx-scroll" style={{ maxHeight }} onScroll={onScroll}>
        {events.isLoading ? (
          <Flex justify="center" style={{ padding: 24 }}><Spin /></Flex>
        ) : events.error ? (
          <Alert type="error" message={t('common.loadFailed')} description={String((events.error as Error).message)} />
        ) : transcript.items.length === 0 ? (
          <Typography.Text type="secondary">
            {task.status === 'queued' ? t('transcript.queued') : active ? t('transcript.waitingForEvents') : t('transcript.noEvents')}
          </Typography.Text>
        ) : (
          <Space direction="vertical" size={10} style={{ display: 'flex' }}>
            {transcript.items.map((it) => (
              <ItemView key={it.key} item={it} taskId={task.id} />
            ))}
            {active && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                <Spin size="small" style={{ marginInlineEnd: 8 }} />
                {task.status === 'waiting_input' ? t('transcript.waitingInput') : t('transcript.working')}
              </Typography.Text>
            )}
          </Space>
        )}
      </div>
      {transcript.usage && <UsageFooter usage={transcript.usage} />}
      {showControls && active && canControl && <SteerBox task={task} />}
    </div>
  );
}
