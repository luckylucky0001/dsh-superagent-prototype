import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Background, Controls, MarkerType, ReactFlow, ReactFlowProvider } from '@xyflow/react';
import { Alert, Breadcrumb, Button, Descriptions, Divider, Flex, Popconfirm, Space, Tag, Typography } from 'antd';
import { ExportOutlined, RedoOutlined, StopOutlined } from '@ant-design/icons';
import { api } from '@/api/client';
import { qk, useComponents, useIssueMap } from '@/api/queries';
import { normStatus, type Run } from '@/api/types';
import { useT } from '@/app/i18n';
import { JsonView } from '@/components/CodeBlock';
import { QueryState } from '@/components/QueryState';
import { StatusTag } from '@/components/StatusTag';
import { durationBetween, fmtDateTime, fmtDuration } from '@/components/format';
import { useNotify } from '@/components/useNotify';
import { nodeTypes, STATUS_COLORS } from '@/features/workflows/FlowNode';
import { defToFlow, type FlowEdge, type FlowNode } from '@/features/workflows/graph';

const ACTIVE_RUN = ['running', 'waiting'];

function NodePanel({ node, onRetry, retrying }: { node: FlowNode; onRetry: () => void; retrying: boolean }) {
  const t = useT();
  const s = node.data.state;
  const taskId = typeof s?.output?.taskId === 'string' ? s.output.taskId : null;
  return (
    <div>
      <Flex align="center" gap={8} justify="space-between">
        <Typography.Text strong style={{ fontSize: 15 }}>{node.data.def.name || node.id}</Typography.Text>
        {s && <StatusTag status={s.status} />}
      </Flex>
      <Typography.Text type="secondary" className="fellowloop-mono" style={{ fontSize: 12 }}>{node.id} · {node.data.def.type}</Typography.Text>
      {!s ? (
        <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>{t('runs.notReached')}</Typography.Paragraph>
      ) : (
        <>
          <Descriptions size="small" column={1} style={{ marginTop: 12 }}
            items={[
              { key: 'att', label: t('runs.attempts'), children: s.attempts },
              { key: 'st', label: t('common.started'), children: fmtDateTime(s.startedAt || null) },
              { key: 'fin', label: t('common.finished'), children: fmtDateTime(s.finishedAt || null) },
              { key: 'dur', label: t('common.duration'), children: s.startedAt ? fmtDuration(durationBetween(s.startedAt, s.finishedAt || null)) : '—' },
              { key: 'ports', label: t('runs.ports'), children: s.ports?.length ? <Space size={4}>{s.ports.map((p) => <Tag key={p} bordered={false}>{p}</Tag>)}</Space> : '—' },
              ...(s.deadlineAt ? [{ key: 'dl', label: t('runs.deadline'), children: fmtDateTime(s.deadlineAt) }] : []),
            ]} />
          {s.status === 'WAITING' && s.waitKey && (
            <Alert type="warning" showIcon message={t('runs.waitingInbox')} action={<Link to="/inbox"><Button size="small">{t('nav.inbox')}</Button></Link>} style={{ marginBottom: 12 }} />
          )}
          {taskId && (
            <Link to={`/tasks/${taskId}`}>
              <Button block icon={<ExportOutlined />} style={{ marginBottom: 12 }}>{t('runs.openTranscript')}</Button>
            </Link>
          )}
          {s.error && <Alert type="error" showIcon message={t('runs.error')} description={s.error} style={{ marginBottom: 12 }} />}
          {s.status === 'FAILED' && (
            <Popconfirm title={t('runs.retryConfirm')} onConfirm={onRetry}>
              <Button icon={<RedoOutlined />} block loading={retrying} style={{ marginBottom: 12 }}>{t('runs.retry')}</Button>
            </Popconfirm>
          )}
          <Typography.Text strong>{t('runs.output')}</Typography.Text>
          <JsonView value={s.output} maxHeight={420} />
        </>
      )}
    </div>
  );
}

function RunCanvas({ run, selected, onSelect }: { run: Run; selected: string | null; onSelect: (id: string | null) => void }) {
  const components = useComponents();
  const descriptors = useMemo(() => new Map((components.data ?? []).map((c) => [c.type, c])), [components.data]);
  const { nodes, edges } = useMemo(() => {
    const base = defToFlow(run.definition ?? { nodes: [], edges: [] }, descriptors, true);
    const st = run.state;
    const nodes: FlowNode[] = base.nodes.map((n) => ({
      ...n,
      selected: n.id === selected,
      data: { ...n.data, state: st?.nodes?.[n.id] },
    }));
    const edges: FlowEdge[] = base.edges.map((e) => {
      const status = st?.edges?.[e.id];
      const color = status === 'ACTIVE' ? STATUS_COLORS.SUCCEEDED : status === 'DEAD' ? '#d9d9d9' : '#8c99ad';
      return {
        ...e,
        type: 'smoothstep',
        animated: status === 'ACTIVE' && st?.nodes?.[e.target]?.status === 'RUNNING',
        style: { stroke: color, strokeWidth: status === 'ACTIVE' ? 2 : 1.5, strokeDasharray: status === 'DEAD' ? '4 4' : undefined, opacity: status === 'DEAD' ? 0.6 : 1 },
        markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16, color },
        labelStyle: { fontSize: 11, fill: status === 'DEAD' ? '#bfbfbf' : '#595959' },
        data: { ...e.data, status },
      };
    });
    return { nodes, edges };
  }, [run, descriptors, selected]);

  return (
    <ReactFlow<FlowNode, FlowEdge>
      nodes={nodes}
      edges={edges}
      nodeTypes={nodeTypes}
      nodesDraggable={false}
      nodesConnectable={false}
      elementsSelectable
      onNodeClick={(_e, n) => onSelect(n.id)}
      onPaneClick={() => onSelect(null)}
      fitView
      fitViewOptions={{ padding: 0.25, maxZoom: 1.1 }}
    >
      <Background gap={20} color="#e5e7eb" />
      <Controls showInteractive={false} />
    </ReactFlow>
  );
}

export function RunDetail() {
  const { id = '' } = useParams();
  const t = useT();
  const notify = useNotify();
  const qc = useQueryClient();
  const run = useQuery({ queryKey: qk.run(id), queryFn: () => api.run(id) });
  const issues = useIssueMap();
  const [selected, setSelected] = useState<string | null>(null);
  const r = run.data;
  const status = normStatus(r?.status);

  const cancel = useMutation({
    mutationFn: () => api.cancelRun(id),
    onSuccess: (updated) => { qc.setQueryData(qk.run(id), updated); notify.success(t('runs.cancelled')); },
    onError: notify.error,
  });
  const retry = useMutation({
    mutationFn: (nodeId: string) => api.retryRun(id, nodeId),
    onSuccess: (updated) => { qc.setQueryData(qk.run(id), updated); notify.success(t('runs.retried')); },
    onError: notify.error,
  });

  const issue = r?.issueId ? issues.get(r.issueId) : undefined;
  const selectedNode = useMemo(() => {
    if (!r || !selected) return undefined;
    const def = r.definition?.nodes.find((n) => n.id === selected);
    if (!def) return undefined;
    const node: FlowNode = { id: def.id, type: 'fellowloop', position: { x: 0, y: 0 }, data: { def, state: r.state?.nodes?.[def.id] } };
    return node;
  }, [r, selected]);

  return (
    <QueryState isLoading={run.isLoading} error={run.error} onRetry={() => void run.refetch()} fullPage>
      {r && (
        <>
          <Breadcrumb style={{ marginBottom: 8 }} items={[{ title: <Link to="/runs">{t('nav.runs')}</Link> }, { title: r.id }]} />
          <Flex justify="space-between" align="center" gap={12} wrap="wrap" style={{ marginBottom: 12 }}>
            <Space size={12} wrap>
              <Typography.Title level={4} style={{ margin: 0 }}>
                <Link to={`/workflows/${r.workflowId}/edit`} style={{ color: 'inherit' }}>{r.workflowName}</Link>
              </Typography.Title>
              <Tag bordered={false}>v{r.version}</Tag>
              <StatusTag status={r.status} />
              <Typography.Text type="secondary">
                {fmtDateTime(r.startedAt)} · {fmtDuration(durationBetween(r.startedAt, r.finishedAt))}
              </Typography.Text>
              {issue && <Link to={`/issues/${issue.key}`}>{issue.key} · {issue.title}</Link>}
            </Space>
            {ACTIVE_RUN.includes(status) && (
              <Popconfirm title={t('runs.cancelConfirm')} onConfirm={() => cancel.mutate()} okButtonProps={{ danger: true }}>
                <Button danger icon={<StopOutlined />} loading={cancel.isPending}>{t('runs.cancel')}</Button>
              </Popconfirm>
            )}
          </Flex>
          {r.error && <Alert type="error" showIcon message={r.error} style={{ marginBottom: 12 }} />}
          <div className="fellowloop-editor readonly">
            <div className="fellowloop-canvas">
              <ReactFlowProvider>
                <RunCanvas run={r} selected={selected} onSelect={setSelected} />
              </ReactFlowProvider>
            </div>
            <div className="fellowloop-inspector">
              {selectedNode ? (
                <NodePanel node={selectedNode} retrying={retry.isPending} onRetry={() => retry.mutate(selectedNode.id)} />
              ) : (
                <div>
                  <Typography.Text strong>{t('runs.summary')}</Typography.Text>
                  <Typography.Paragraph type="secondary" style={{ fontSize: 13, marginTop: 4 }}>{t('runs.clickNode')}</Typography.Paragraph>
                  <Descriptions size="small" column={1}
                    items={[
                      { key: 'trigger', label: t('runs.trigger'), children: <span className="fellowloop-mono">{r.triggerNodeId ?? '—'}</span> },
                      { key: 'started', label: t('common.started'), children: fmtDateTime(r.startedAt) },
                      { key: 'finished', label: t('common.finished'), children: fmtDateTime(r.finishedAt) },
                      {
                        key: 'nodes',
                        label: t('wf.nodes'),
                        children: (
                          <Space size={4} wrap>
                            {Object.entries(r.state?.nodes ?? {}).map(([nid, s]) => (
                              <Tag key={nid} color={s.status === 'FAILED' ? 'error' : s.status === 'SUCCEEDED' ? 'success' : undefined}
                                style={{ cursor: 'pointer' }} onClick={() => setSelected(nid)}>{nid}</Tag>
                            ))}
                          </Space>
                        ),
                      },
                    ]} />
                  <Divider style={{ margin: '12px 0' }} />
                  <Typography.Text strong>{t('runs.triggerPayload')}</Typography.Text>
                  <JsonView value={r.state?.trigger} maxHeight={480} />
                </div>
              )}
            </div>
          </div>
        </>
      )}
    </QueryState>
  );
}
