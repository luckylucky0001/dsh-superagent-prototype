import { useCallback, useEffect, useMemo, useRef, useState, type DragEvent } from 'react';
import { Link, useBlocker, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  addEdge,
  Background,
  Controls,
  MarkerType,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  useEdgesState,
  useNodesState,
  useReactFlow,
  type Connection,
  type IsValidConnection,
} from '@xyflow/react';
import { Alert, App, Badge, Breadcrumb, Button, Flex, Input, List, Popover, Space, Switch, Tag, Tooltip, Typography } from 'antd';
import {
  CheckCircleOutlined,
  CloseCircleFilled,
  HistoryOutlined,
  PlayCircleOutlined,
  SaveOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { api, ApiError } from '@/api/client';
import { qk, useComponents } from '@/api/queries';
import type { NodeDef, NodeDescriptor, ValidationIssue, Workflow, WorkflowDefinition } from '@/api/types';
import { useT } from '@/app/i18n';
import { QueryState } from '@/components/QueryState';
import { fmtDateTime } from '@/components/format';
import { useNotify } from '@/components/useNotify';
import { nodeTypes } from './FlowNode';
import {
  CATEGORY_COLORS,
  defaultConfig,
  defToFlow,
  edgeLabel,
  flowToDef,
  nextEdgeId,
  nextNodeId,
  portsOf,
  variablesFor,
  type FlowEdge,
  type FlowNode,
} from './graph';
import { EdgeInspector, NodeInspector } from './Inspector';
import { DND_TYPE, Palette } from './Palette';
import { manualTrigger, RunWorkflowModal } from './RunWorkflowModal';

type Selection = { kind: 'node' | 'edge'; id: string } | null;

const EMPTY: WorkflowDefinition = { nodes: [], edges: [] };

function Versions({ workflowId }: { workflowId: string }) {
  const t = useT();
  const versions = useQuery({ queryKey: qk.workflowVersions(workflowId), queryFn: () => api.workflowVersions(workflowId) });
  return (
    <div style={{ width: 280, maxHeight: 320, overflow: 'auto' }}>
      <List
        size="small"
        loading={versions.isLoading}
        dataSource={[...(versions.data ?? [])].sort((a, b) => b.version - a.version)}
        renderItem={(v) => (
          <List.Item>
            <Space>
              <Tag bordered={false}>v{v.version}</Tag>
              <Typography.Text style={{ fontSize: 12 }}>{fmtDateTime(v.createdAt)}</Typography.Text>
            </Space>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {v.createdBy && v.createdBy !== 'null' ? v.createdBy : t('wf.system')}
            </Typography.Text>
          </List.Item>
        )}
      />
    </div>
  );
}

function EditorBody({ workflow, components }: { workflow: Workflow | null; components: NodeDescriptor[] }) {
  const t = useT();
  const notify = useNotify();
  const { modal } = App.useApp();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const flow = useReactFlow<FlowNode, FlowEdge>();
  const descriptors = useMemo(() => new Map(components.map((c) => [c.type, c])), [components]);
  const initial = useMemo(() => defToFlow(workflow?.definition ?? EMPTY, descriptors), [workflow, descriptors]);

  const [nodes, setNodes, onNodesChange] = useNodesState<FlowNode>(initial.nodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState<FlowEdge>(initial.edges);
  const [name, setName] = useState(workflow?.name ?? '');
  const [description, setDescription] = useState(workflow?.description ?? '');
  const [enabled, setEnabled] = useState(workflow?.enabled ?? false);
  const [selection, setSelection] = useState<Selection>(null);
  const [problems, setProblems] = useState<ValidationIssue[] | null>(null);
  const [newIds, setNewIds] = useState<Set<string>>(() => new Set());
  const [jsonInvalid, setJsonInvalid] = useState<Set<string>>(() => new Set());
  const [running, setRunning] = useState(false);
  const wrapper = useRef<HTMLDivElement>(null);
  const skipBlock = useRef(false);

  const snapshot = useCallback(
    (n: FlowNode[], e: FlowEdge[], nm: string, ds: string, en: boolean) => JSON.stringify([flowToDef(n, e), nm, ds, en]),
    [],
  );
  const [baseline, setBaseline] = useState(() =>
    snapshot(initial.nodes, initial.edges, workflow?.name ?? '', workflow?.description ?? '', workflow?.enabled ?? false),
  );
  const current = useMemo(() => snapshot(nodes, edges, name, description, enabled), [snapshot, nodes, edges, name, description, enabled]);
  const dirty = current !== baseline;

  // ------------------------------------------------------------ unsaved-changes guard
  const blocker = useBlocker(({ currentLocation, nextLocation }) =>
    !skipBlock.current && dirty && currentLocation.pathname !== nextLocation.pathname);
  useEffect(() => {
    if (blocker.state !== 'blocked') return;
    modal.confirm({
      title: t('wf.unsavedTitle'),
      content: t('wf.unsavedBody'),
      okText: t('wf.discard'),
      okButtonProps: { danger: true },
      onOk: () => blocker.proceed(),
      onCancel: () => blocker.reset(),
    });
  }, [blocker, modal, t]);
  useEffect(() => {
    if (!dirty) return;
    const h = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener('beforeunload', h);
    return () => window.removeEventListener('beforeunload', h);
  }, [dirty]);

  // ------------------------------------------------------------ problems → node/edge decorations
  const problemsByNode = useMemo(() => {
    const m = new Map<string, string[]>();
    for (const p of problems ?? []) if (p.nodeId) m.set(p.nodeId, [...(m.get(p.nodeId) ?? []), p.message]);
    return m;
  }, [problems]);
  const problemsByEdge = useMemo(() => {
    const m = new Map<string, string[]>();
    for (const p of problems ?? []) if (p.edgeId) m.set(p.edgeId, [...(m.get(p.edgeId) ?? []), p.message]);
    return m;
  }, [problems]);

  const viewNodes = useMemo(
    () => nodes.map((n) => {
      const pr = problemsByNode.get(n.id);
      return pr || n.data.problems ? { ...n, data: { ...n.data, problems: pr } } : n;
    }),
    [nodes, problemsByNode],
  );
  const viewEdges = useMemo(
    () => edges.map((e) => {
      const pr = problemsByEdge.get(e.id);
      const isError = e.sourceHandle === 'error';
      return {
        ...e,
        type: 'smoothstep',
        markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16, color: pr ? '#ff4d4f' : isError ? '#ff7875' : '#8c99ad' },
        style: { stroke: pr ? '#ff4d4f' : isError ? '#ff7875' : '#8c99ad', strokeWidth: pr ? 2.5 : 1.5, strokeDasharray: isError ? '5 4' : undefined },
        labelStyle: { fontSize: 11, fill: '#595959' },
        labelBgStyle: { fill: '#fff' },
        data: { ...e.data, problems: pr },
      };
    }),
    [edges, problemsByEdge],
  );

  // ------------------------------------------------------------ editing
  const addNode = useCallback(
    (d: NodeDescriptor, position?: { x: number; y: number }) => {
      const id = nextNodeId(d.type, nodes.map((n) => n.id));
      let pos = position;
      // clicked in the palette: chain below the selected node (and connect it), else below the lowest node
      const anchor = !position && selection?.kind === 'node' ? nodes.find((n) => n.id === selection.id) : undefined;
      if (!pos && anchor) {
        const below = nodes.filter((n) => Math.abs(n.position.x - anchor.position.x) < 200 && n.position.y > anchor.position.y);
        pos = { x: anchor.position.x, y: anchor.position.y + 130 };
        if (below.some((n) => Math.abs(n.position.y - pos!.y) < 90)) pos = { x: anchor.position.x + 260, y: pos.y };
      } else if (!pos && nodes.length > 0) {
        const lowest = nodes.reduce((a, b) => (b.position.y > a.position.y ? b : a));
        pos = { x: lowest.position.x, y: lowest.position.y + 130 };
      } else if (!pos) {
        const rect = wrapper.current?.getBoundingClientRect();
        const c = rect ? flow.screenToFlowPosition({ x: rect.left + rect.width / 2, y: rect.top + 80 }) : { x: 0, y: 0 };
        pos = { x: c.x - 110, y: c.y };
      }
      const def: NodeDef = { id, type: d.type, name: d.label, config: defaultConfig(d), retries: 0, timeoutSeconds: 0, onError: 'FAIL', ui: {} };
      setNodes((ns) => [...ns.map((n) => ({ ...n, selected: false })), { id, type: 'orca', position: pos!, data: { def, descriptor: d }, selected: true }]);
      if (anchor && !d.trigger) {
        const ports = portsOf(anchor.data.def, anchor.data.descriptor);
        setEdges((es) => [...es, {
          id: nextEdgeId(es.map((x) => x.id)),
          source: anchor.id,
          target: id,
          sourceHandle: ports[0],
          targetHandle: 'in',
          label: edgeLabel({ sourcePort: ports[0] }, ports.length > 1),
          data: { condition: null },
        }]);
      }
      setNewIds((s) => new Set(s).add(id));
      setSelection({ kind: 'node', id });
      if (!position) window.requestAnimationFrame(() => void flow.fitView({ padding: 0.25, maxZoom: 1.1, duration: 250 }));
    },
    [nodes, flow, setNodes, setEdges, selection],
  );

  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    const type = e.dataTransfer.getData(DND_TYPE);
    const d = descriptors.get(type);
    if (!d) return;
    const p = flow.screenToFlowPosition({ x: e.clientX, y: e.clientY });
    addNode(d, { x: p.x - 110, y: p.y - 30 });
  };

  const onConnect = useCallback(
    (c: Connection) => {
      setEdges((es) => {
        const src = nodes.find((n) => n.id === c.source);
        const multi = src ? portsOf(src.data.def, src.data.descriptor).length > 1 : false;
        const port = c.sourceHandle ?? 'out';
        const edge: FlowEdge = {
          id: nextEdgeId(es.map((x) => x.id)),
          source: c.source,
          target: c.target,
          sourceHandle: port,
          targetHandle: 'in',
          label: edgeLabel({ sourcePort: port }, multi),
          data: { condition: null },
        };
        return addEdge(edge, es);
      });
    },
    [nodes, setEdges],
  );

  const isValidConnection: IsValidConnection<FlowEdge> = useCallback(
    (c) => {
      if (c.source === c.target) return false;
      const target = nodes.find((n) => n.id === c.target);
      if (target?.data.descriptor?.trigger) return false;
      return !edges.some((e) => e.source === c.source && e.target === c.target && (e.sourceHandle ?? 'out') === (c.sourceHandle ?? 'out'));
    },
    [nodes, edges],
  );

  const updateNode = (id: string, def: NodeDef) => {
    const node = nodes.find((n) => n.id === id);
    const ports = new Set(portsOf(def, node?.data.descriptor));
    setNodes((ns) => ns.map((n) => (n.id === id ? { ...n, data: { ...n.data, def } } : n)));
    const multi = ports.size > 1;
    setEdges((es) =>
      es
        .filter((e) => e.source !== id || ports.has(e.sourceHandle ?? 'out'))
        .map((e) => (e.source === id ? { ...e, label: edgeLabel({ sourcePort: e.sourceHandle, condition: e.data?.condition }, multi) } : e)),
    );
  };

  const renameNode = (oldId: string, newId: string) => {
    setNodes((ns) => ns.map((n) => (n.id === oldId ? { ...n, id: newId, data: { ...n.data, def: { ...n.data.def, id: newId } } } : n)));
    setEdges((es) => es.map((e) => ({ ...e, source: e.source === oldId ? newId : e.source, target: e.target === oldId ? newId : e.target })));
    setNewIds((s) => {
      const next = new Set(s);
      next.delete(oldId);
      next.add(newId);
      return next;
    });
    setSelection({ kind: 'node', id: newId });
  };

  const deleteNode = (id: string) => {
    setNodes((ns) => ns.filter((n) => n.id !== id));
    setEdges((es) => es.filter((e) => e.source !== id && e.target !== id));
    setSelection(null);
  };

  const setEdgeCondition = (id: string, condition: string | null) => {
    setEdges((es) =>
      es.map((e) => {
        if (e.id !== id) return e;
        const src = nodes.find((n) => n.id === e.source);
        const multi = src ? portsOf(src.data.def, src.data.descriptor).length > 1 : false;
        return { ...e, data: { ...e.data, condition }, label: edgeLabel({ sourcePort: e.sourceHandle, condition }, multi) };
      }),
    );
  };

  // ------------------------------------------------------------ save / validate / enable
  const definition = () => flowToDef(nodes, edges);

  const save = useMutation({
    mutationFn: () => {
      const input = { name: name.trim() || t('wf.untitled'), description, enabled, definition: definition() };
      return workflow ? api.updateWorkflow(workflow.id, input) : api.createWorkflow(input);
    },
    onSuccess: async (wf) => {
      qc.setQueryData(qk.workflow(wf.id), wf);
      void qc.invalidateQueries({ queryKey: qk.workflows });
      void qc.invalidateQueries({ queryKey: qk.workflowVersions(wf.id) });
      setEnabled(wf.enabled);
      setBaseline(snapshot(nodes, edges, name.trim() || t('wf.untitled'), description, wf.enabled));
      if (name.trim() === '') setName(wf.name);
      notify.success(t('wf.saved', { v: wf.currentVersion }));
      if (!wf.enabled) {
        // saved disabled: show what still blocks enabling
        const issues = await api.validateWorkflow(wf.definition).catch(() => []);
        setProblems(issues);
      } else setProblems(null);
      if (!workflow) {
        skipBlock.current = true;
        navigate(`/workflows/${wf.id}/edit`, { replace: true });
      }
    },
    onError: (e) => {
      if (e instanceof ApiError && e.validationIssues.length) {
        setProblems(e.validationIssues);
        notify.message.error(t('wf.saveProblems', { n: e.validationIssues.length }));
      } else notify.error(e);
    },
  });

  const validate = useMutation({
    mutationFn: () => api.validateWorkflow(definition()),
    onSuccess: (issues) => {
      setProblems(issues);
      if (issues.length === 0) notify.success(t('wf.valid'));
      else notify.message.warning(t('wf.saveProblems', { n: issues.length }));
    },
    onError: notify.error,
  });

  const toggleEnabled = useMutation({
    mutationFn: (v: boolean) => api.setWorkflowEnabled(workflow!.id, v),
    onSuccess: (wf) => {
      qc.setQueryData(qk.workflow(wf.id), wf);
      void qc.invalidateQueries({ queryKey: qk.workflows });
      setEnabled(wf.enabled);
      setBaseline(snapshot(nodes, edges, name, description, wf.enabled));
    },
    onError: notify.error,
  });

  const onToggle = (v: boolean) => {
    if (workflow && !dirty) toggleEnabled.mutate(v);
    else setEnabled(v);
  };

  const trySave = () => {
    if (jsonInvalid.size > 0) {
      notify.message.error(t('wf.fixJson'));
      return;
    }
    save.mutate();
  };

  // ctrl/cmd+S
  const saveRef = useRef(trySave);
  saveRef.current = trySave;
  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 's') {
        e.preventDefault();
        saveRef.current();
      }
    };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, []);

  const focusProblem = (p: ValidationIssue) => {
    if (p.nodeId) {
      const n = nodes.find((x) => x.id === p.nodeId);
      if (n) {
        setSelection({ kind: 'node', id: n.id });
        setNodes((ns) => ns.map((x) => ({ ...x, selected: x.id === n.id })));
        void flow.setCenter(n.position.x + 110, n.position.y + 40, { zoom: 1.1, duration: 400 });
      }
    } else if (p.edgeId) {
      setSelection({ kind: 'edge', id: p.edgeId });
    }
  };

  // ------------------------------------------------------------ render
  const selectedNode = selection?.kind === 'node' ? viewNodes.find((n) => n.id === selection.id) : undefined;
  const selectedEdge = selection?.kind === 'edge' ? viewEdges.find((e) => e.id === selection.id) : undefined;
  const groups = useMemo(
    () => (selection ? variablesFor(selection.kind === 'node' ? selection.id : (edges.find((e) => e.id === selection.id)?.source ?? ''), nodes, edges) : []),
    [selection, nodes, edges],
  );
  // an edge condition can also read its source node's output
  const edgeGroups = useMemo(() => {
    if (!selectedEdge) return groups;
    const src = nodes.find((n) => n.id === selectedEdge.source);
    if (!src || src.data.descriptor?.trigger) return groups;
    return [
      ...groups,
      { key: `self-${src.id}`, title: src.data.def.name || src.id, vars: (src.data.descriptor?.outputs ?? []).map((o) => ({ expr: `nodes.${src.id}.output.${o.name}`, description: o.description })) },
    ];
  }, [groups, selectedEdge, nodes]);
  const hasManual = !!manualTrigger(workflow?.definition);
  const graphProblems = (problems ?? []).filter((p) => !p.nodeId && !p.edgeId);

  return (
    <>
      <Flex justify="space-between" align="center" gap={12} wrap="wrap" style={{ marginBottom: 12 }}>
        <Flex align="center" gap={12} style={{ minWidth: 0, flex: 1 }}>
          <Breadcrumb style={{ whiteSpace: 'nowrap', flex: 'none' }} items={[{ title: <Link to="/workflows">{t('nav.workflows')}</Link> }, { title: workflow ? t('common.edit') : t('wf.new') }]} />
          <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={t('wf.namePlaceholder')} variant="borderless"
            style={{ fontSize: 17, fontWeight: 600, maxWidth: 360 }} />
          <Input value={description} onChange={(e) => setDescription(e.target.value)} placeholder={t('wf.descriptionPlaceholder')}
            variant="borderless" style={{ maxWidth: 420, color: '#595959' }} />
        </Flex>
        <Space wrap>
          {workflow?.builtIn && <Tag color="gold" bordered={false}>{t('wf.builtIn')}</Tag>}
          {workflow && <Tag bordered={false}>v{workflow.currentVersion}</Tag>}
          <Tooltip title={t('wf.enabledHelp')}>
            <Space size={6}>
              <Switch checked={enabled} onChange={onToggle} loading={toggleEnabled.isPending} size="small" />
              <Typography.Text>{enabled ? t('wf.enabled') : t('wf.disabled')}</Typography.Text>
            </Space>
          </Tooltip>
          <Button icon={<SafetyCertificateOutlined />} loading={validate.isPending} onClick={() => validate.mutate()}>{t('wf.validate')}</Button>
          {workflow && (
            <Popover trigger="click" title={t('wf.versions')} content={<Versions workflowId={workflow.id} />} placement="bottomRight">
              <Button icon={<HistoryOutlined />}>{t('wf.versions')}</Button>
            </Popover>
          )}
          {hasManual && (
            <Tooltip title={dirty ? t('wf.saveBeforeRun') : undefined}>
              <Button icon={<PlayCircleOutlined />} disabled={dirty} onClick={() => setRunning(true)}>{t('wf.run')}</Button>
            </Tooltip>
          )}
          <Badge dot={dirty} offset={[-4, 4]}>
            <Button type="primary" icon={<SaveOutlined />} loading={save.isPending} onClick={trySave}>{t('common.save')}</Button>
          </Badge>
        </Space>
      </Flex>

      <div className="orca-editor">
        <Palette components={components} onAdd={(d) => addNode(d)} />
        <div className="orca-canvas" ref={wrapper} onDragOver={(e) => { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; }} onDrop={onDrop}>
          <ReactFlow<FlowNode, FlowEdge>
            nodes={viewNodes}
            edges={viewEdges}
            nodeTypes={nodeTypes}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            isValidConnection={isValidConnection}
            onNodeClick={(_e, n) => setSelection({ kind: 'node', id: n.id })}
            onEdgeClick={(_e, e) => setSelection({ kind: 'edge', id: e.id })}
            onPaneClick={() => setSelection(null)}
            onNodesDelete={(ds) => { if (ds.some((d) => d.id === selection?.id)) setSelection(null); }}
            onEdgesDelete={(ds) => { if (ds.some((d) => d.id === selection?.id)) setSelection(null); }}
            fitView
            fitViewOptions={{ padding: 0.25, maxZoom: 1.1 }}
            deleteKeyCode={['Backspace', 'Delete']}
            snapToGrid
            snapGrid={[10, 10]}
          >
            <Background gap={20} color="#e5e7eb" />
            <Controls showInteractive={false} />
            <MiniMap pannable zoomable nodeColor={(n) => CATEGORY_COLORS[(n as FlowNode).data.descriptor?.category ?? ''] ?? '#bfbfbf'} style={{ width: 140, height: 90 }} />
          </ReactFlow>
          {nodes.length === 0 && (
            <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', pointerEvents: 'none' }}>
              <Typography.Text type="secondary">{t('wf.emptyCanvas')}</Typography.Text>
            </div>
          )}
          {problems && problems.length > 0 && (
            <div style={{ position: 'absolute', left: 12, bottom: 12, maxWidth: 440, zIndex: 5 }}>
              <Alert
                type="error"
                showIcon
                closable
                onClose={() => setProblems(null)}
                message={t('wf.problems', { n: problems.length })}
                description={
                  <div style={{ maxHeight: 180, overflow: 'auto' }}>
                    {problems.map((p, i) => (
                      <div key={i} style={{ cursor: p.nodeId || p.edgeId ? 'pointer' : 'default', fontSize: 12, padding: '1px 0' }} onClick={() => focusProblem(p)}>
                        <CloseCircleFilled style={{ color: '#ff4d4f', marginInlineEnd: 6 }} />
                        {(p.nodeId || p.edgeId) && <Typography.Text code style={{ fontSize: 11 }}>{p.nodeId ?? p.edgeId}</Typography.Text>} {p.message}
                      </div>
                    ))}
                  </div>
                }
              />
            </div>
          )}
          {problems && problems.length === 0 && (
            <div style={{ position: 'absolute', left: 12, bottom: 12, zIndex: 5 }}>
              <Alert type="success" showIcon icon={<CheckCircleOutlined />} message={t('wf.valid')} closable onClose={() => setProblems(null)} />
            </div>
          )}
        </div>
        <div className="orca-inspector">
          {selectedNode ? (
            <NodeInspector
              key={selectedNode.id}
              node={selectedNode}
              groups={groups}
              idEditable={newIds.has(selectedNode.id)}
              takenIds={new Set(nodes.map((n) => n.id))}
              onChange={(def) => updateNode(selectedNode.id, def)}
              onRename={(nid) => renameNode(selectedNode.id, nid)}
              onDelete={() => deleteNode(selectedNode.id)}
              onJsonValidity={(field, ok) =>
                setJsonInvalid((s) => {
                  const k = `${selectedNode.id}:${field}`;
                  if (ok === !s.has(k)) return s;
                  const next = new Set(s);
                  if (ok) next.delete(k);
                  else next.add(k);
                  return next;
                })
              }
            />
          ) : selectedEdge ? (
            <EdgeInspector
              key={selectedEdge.id}
              edge={selectedEdge}
              groups={edgeGroups}
              onChange={(c) => setEdgeCondition(selectedEdge.id, c)}
              onDelete={() => {
                setEdges((es) => es.filter((e) => e.id !== selectedEdge.id));
                setSelection(null);
              }}
            />
          ) : (
            <div>
              <Typography.Text strong>{t('wf.inspector')}</Typography.Text>
              <Typography.Paragraph type="secondary" style={{ fontSize: 13, marginTop: 6 }}>{t('wf.inspectorHint')}</Typography.Paragraph>
              {graphProblems.length > 0 && (
                <Alert type="error" showIcon message={graphProblems.map((p) => p.message).join('; ')} />
              )}
              <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
                {t('wf.stats', { nodes: nodes.length, edges: edges.length })}
              </Typography.Paragraph>
            </div>
          )}
        </div>
      </div>
      <RunWorkflowModal workflow={workflow} open={running} onClose={() => setRunning(false)} />
    </>
  );
}

export function WorkflowEditor() {
  const { id } = useParams();
  const components = useComponents();
  const workflow = useQuery({ queryKey: qk.workflow(id ?? ''), queryFn: () => api.workflow(id!), enabled: !!id, staleTime: Infinity });
  const loading = components.isLoading || (!!id && workflow.isLoading);
  return (
    <QueryState isLoading={loading} error={components.error ?? workflow.error} fullPage>
      {components.data && (!id || workflow.data) && (
        <ReactFlowProvider key={id ?? 'new'}>
          <EditorBody workflow={id ? workflow.data! : null} components={components.data} />
        </ReactFlowProvider>
      )}
    </QueryState>
  );
}
