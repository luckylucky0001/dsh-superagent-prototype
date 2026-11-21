import type { Edge, Node } from '@xyflow/react';
import type {
  EdgeDef,
  EdgeStatus,
  JsonSchema,
  NodeDef,
  NodeDescriptor,
  NodeState,
  WorkflowDefinition,
} from '@/api/types';

export interface FlowNodeData extends Record<string, unknown> {
  def: NodeDef;
  descriptor?: NodeDescriptor;
  problems?: string[];
  state?: NodeState;
  readOnly?: boolean;
}

export interface FlowEdgeData extends Record<string, unknown> {
  condition?: string | null;
  problems?: string[];
  status?: EdgeStatus;
}

export type FlowNode = Node<FlowNodeData, 'fellowloop'>;
export type FlowEdge = Edge<FlowEdgeData>;

export const CATEGORY_ORDER = ['trigger', 'agent', 'logic', 'human', 'action'];
export const CATEGORY_COLORS: Record<string, string> = {
  trigger: '#2f54eb',
  agent: '#722ed1',
  logic: '#fa8c16',
  human: '#13a8a8',
  action: '#389e0d',
};

export const NODE_ID_PATTERN = /^[A-Za-z_][A-Za-z0-9_]*$/;
export const ERROR_PORT = 'error';
export const DEFAULT_PORT = 'out';

/** Output ports of a node, including the error port when the node continues on error. */
export function portsOf(def: NodeDef, d: NodeDescriptor | undefined): string[] {
  const base = d?.ports?.length ? d.ports : [DEFAULT_PORT];
  return def.onError === 'CONTINUE' ? [...base, ERROR_PORT] : base;
}

export function edgeLabel(e: { sourcePort?: string | null; condition?: string | null }, multiPort: boolean): string | undefined {
  const port = e.sourcePort ?? DEFAULT_PORT;
  const parts: string[] = [];
  // multi-port nodes already name their ports under the handles; repeat only the error port on the edge
  if (port === ERROR_PORT && !multiPort) parts.push(port);
  if (e.condition) parts.push(e.condition.length > 32 ? `${e.condition.slice(0, 30)}…` : e.condition);
  return parts.length ? parts.join(' · ') : undefined;
}

export function toFlowEdge(e: EdgeDef, nodesById: Map<string, { def: NodeDef; descriptor?: NodeDescriptor }>): FlowEdge {
  const src = nodesById.get(e.source);
  const multi = src ? portsOf(src.def, src.descriptor).length > 1 : false;
  const port = e.sourcePort || DEFAULT_PORT;
  return {
    id: e.id,
    source: e.source,
    target: e.target,
    sourceHandle: port,
    targetHandle: 'in',
    label: edgeLabel({ sourcePort: port, condition: e.condition }, multi),
    data: { condition: e.condition ?? null },
  };
}

export function defToFlow(def: WorkflowDefinition, descriptors: Map<string, NodeDescriptor>, readOnly = false): { nodes: FlowNode[]; edges: FlowEdge[] } {
  const nodes: FlowNode[] = (def.nodes ?? []).map((n, i) => ({
    id: n.id,
    type: 'fellowloop',
    position: { x: typeof n.ui?.x === 'number' ? n.ui.x : 0, y: typeof n.ui?.y === 'number' ? n.ui.y : i * 120 },
    data: { def: normaliseNode(n), descriptor: descriptors.get(n.type), readOnly },
    deletable: !readOnly,
    draggable: !readOnly,
    connectable: !readOnly,
  }));
  const byId = new Map(nodes.map((n) => [n.id, n.data]));
  const edges = (def.edges ?? []).map((e) => ({ ...toFlowEdge(e, byId), deletable: !readOnly }));
  return { nodes, edges };
}

export function normaliseNode(n: NodeDef): NodeDef {
  return {
    id: n.id,
    type: n.type,
    name: n.name ?? null,
    config: n.config ?? {},
    retries: n.retries ?? 0,
    timeoutSeconds: n.timeoutSeconds ?? 0,
    onError: n.onError ?? 'FAIL',
    ui: n.ui ?? {},
  };
}

export function flowToDef(nodes: FlowNode[], edges: FlowEdge[]): WorkflowDefinition {
  return {
    nodes: nodes.map((n) => ({
      ...n.data.def,
      id: n.id,
      ui: { ...n.data.def.ui, x: Math.round(n.position.x), y: Math.round(n.position.y) },
    })),
    edges: edges.map((e) => {
      const out: EdgeDef = { id: e.id, source: e.source, sourcePort: e.sourceHandle ?? DEFAULT_PORT, target: e.target };
      if (e.data?.condition) out.condition = e.data.condition;
      return out;
    }),
  };
}

/** e.g. agent.run → agent_run_2, the first free number. */
export function nextNodeId(type: string, existing: Iterable<string>): string {
  const taken = new Set(existing);
  let base = type.replace(/[^A-Za-z0-9_]/g, '_');
  if (!/^[A-Za-z_]/.test(base)) base = `n_${base}`;
  for (let i = 1; ; i++) {
    const id = `${base}_${i}`;
    if (!taken.has(id)) return id;
  }
}

export function nextEdgeId(existing: Iterable<string>): string {
  const taken = new Set(existing);
  for (let i = 1; ; i++) {
    const id = `e${i}`;
    if (!taken.has(id)) return id;
  }
}

export function defaultConfig(d: NodeDescriptor): Record<string, unknown> {
  const config: Record<string, unknown> = {};
  for (const f of d.fields) {
    if (f.defaultValue !== null && f.defaultValue !== undefined) config[f.name] = f.defaultValue;
  }
  return config;
}

// ---------------------------------------------------------------- variables

export interface Variable {
  expr: string;
  description?: string | null;
  type?: string;
}

export interface VariableGroup {
  key: string;
  title: string;
  vars: Variable[];
}

const ISSUE_FIELDS = ['id', 'key', 'title', 'description', 'status', 'priority', 'labels', 'assigneeId'];

function ancestors(nodeId: string, edges: FlowEdge[]): Set<string> {
  const incoming = new Map<string, string[]>();
  for (const e of edges) incoming.set(e.target, [...(incoming.get(e.target) ?? []), e.source]);
  const seen = new Set<string>();
  const stack = [...(incoming.get(nodeId) ?? [])];
  while (stack.length) {
    const n = stack.pop()!;
    if (seen.has(n)) continue;
    seen.add(n);
    stack.push(...(incoming.get(n) ?? []));
  }
  return seen;
}

function splitNames(v: unknown): string[] {
  return typeof v === 'string' ? v.split(',').map((s) => s.trim()).filter(Boolean) : [];
}

/** Variables a node's templates can read: the trigger's outputs and upstream nodes' outputs. */
export function variablesFor(nodeId: string, nodes: FlowNode[], edges: FlowEdge[]): VariableGroup[] {
  const up = ancestors(nodeId, edges);
  const byId = new Map(nodes.map((n) => [n.id, n]));
  let triggers = [...up].map((id) => byId.get(id)).filter((n): n is FlowNode => !!n && !!n.data.descriptor?.trigger);
  if (triggers.length === 0) triggers = nodes.filter((n) => n.data.descriptor?.trigger && n.id !== nodeId);
  const self = byId.get(nodeId);
  if (self?.data.descriptor?.trigger) triggers = [];
  const groups: VariableGroup[] = [];

  for (const tr of triggers) {
    const d = tr.data.descriptor!;
    const vars: Variable[] = [];
    for (const o of d.outputs) {
      vars.push({ expr: `trigger.${o.name}`, description: o.description, type: o.type });
      if (d.type === 'trigger.issue' && o.name === 'issue') {
        for (const f of ISSUE_FIELDS) vars.push({ expr: `trigger.issue.${f}` });
      }
      if (d.type === 'trigger.issue' && o.name === 'comment') vars.push({ expr: 'trigger.comment.body' });
      if (d.type === 'trigger.manual' && o.name === 'input') {
        for (const name of splitNames(tr.data.def.config.inputs)) vars.push({ expr: `trigger.input.${name}`, type: 'input' });
      }
    }
    groups.push({ key: `trigger-${tr.id}`, title: `${tr.data.def.name || tr.id} (trigger)`, vars });
  }

  for (const id of up) {
    const n = byId.get(id);
    if (!n || n.data.descriptor?.trigger) continue;
    const d = n.data.descriptor;
    const vars: Variable[] = [];
    for (const o of d?.outputs ?? []) {
      if (o.name === '*') {
        const values = n.data.def.config.values;
        if (values && typeof values === 'object') {
          for (const k of Object.keys(values as Record<string, unknown>)) vars.push({ expr: `nodes.${id}.output.${k}` });
        }
        continue;
      }
      vars.push({ expr: `nodes.${id}.output.${o.name}`, description: o.description, type: o.type });
      if (o.name === 'structured') {
        const schema = n.data.def.config.outputSchema as JsonSchema | undefined;
        for (const k of Object.keys(schema?.properties ?? {})) vars.push({ expr: `nodes.${id}.output.structured.${k}` });
      }
    }
    if (d?.type === 'human.input' && typeof n.data.def.config.questions === 'string') {
      n.data.def.config.questions.split('\n').filter((l) => l.trim()).forEach((q, i) =>
        vars.push({ expr: `nodes.${id}.output.answers.q${i + 1}`, description: q.trim() }));
    }
    if (n.data.def.onError === 'CONTINUE') vars.push({ expr: `nodes.${id}.output.error`, description: 'error message (error port)' });
    groups.push({ key: id, title: n.data.def.name || id, vars });
  }
  return groups;
}
