import { memo, type ReactNode } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import { Tooltip } from 'antd';
import {
  ApartmentOutlined,
  BranchesOutlined,
  CloseCircleFilled,
  RobotOutlined,
  SendOutlined,
  ThunderboltOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { StatusTag } from '@/components/StatusTag';
import { CATEGORY_COLORS, ERROR_PORT, portsOf, type FlowNode } from './graph';

export const CATEGORY_ICONS: Record<string, ReactNode> = {
  trigger: <ThunderboltOutlined />,
  agent: <RobotOutlined />,
  logic: <BranchesOutlined />,
  human: <UserOutlined />,
  action: <SendOutlined />,
};

export const STATUS_COLORS: Record<string, string> = {
  PENDING: '#d9d9d9',
  READY: '#91caff',
  RUNNING: '#1677ff',
  WAITING: '#faad14',
  SUCCEEDED: '#52c41a',
  FAILED: '#ff4d4f',
  SKIPPED: '#d9d9d9',
  CANCELLED: '#8c8c8c',
};

function OrcaNodeView({ data, selected }: NodeProps<FlowNode>) {
  const { def, descriptor, problems, state } = data;
  const category = descriptor?.category ?? 'action';
  const color = state ? STATUS_COLORS[state.status] ?? '#d9d9d9' : CATEGORY_COLORS[category] ?? '#8c8c8c';
  const ports = portsOf(def, descriptor);
  const showLabels = ports.length > 1;
  const isTrigger = descriptor?.trigger ?? def.type.startsWith('trigger.');
  const dim = state && (state.status === 'SKIPPED' || state.status === 'PENDING');

  return (
    <div
      className={`orca-flow-node ${selected ? 'selected' : ''} ${problems?.length ? 'problem' : ''}`}
      style={{ borderTopColor: color, opacity: dim ? 0.55 : 1, background: state?.status === 'FAILED' ? '#fff8f8' : undefined }}
    >
      {!isTrigger && <Handle type="target" position={Position.Top} id="in" />}
      <div className="orca-flow-node-type">
        <span style={{ color: CATEGORY_COLORS[category] }}>{CATEGORY_ICONS[category] ?? <ApartmentOutlined />}</span>
        <span>{descriptor?.label ?? def.type}</span>
        <span style={{ marginInlineStart: 'auto', fontFamily: 'monospace' }}>{def.id}</span>
      </div>
      <div className="orca-flow-node-name">{def.name || def.id}</div>
      {state && (
        <div style={{ marginTop: 4, display: 'flex', alignItems: 'center', gap: 6 }}>
          <StatusTag status={state.status} />
          {state.attempts > 1 && <span style={{ color: '#8c8c8c' }}>×{state.attempts}</span>}
        </div>
      )}
      {problems && problems.length > 0 && (
        <Tooltip title={problems.join('\n')}>
          <div style={{ color: '#ff4d4f', marginTop: 4, fontSize: 11 }}>
            <CloseCircleFilled /> {problems[0]}
            {problems.length > 1 ? ` (+${problems.length - 1})` : ''}
          </div>
        </Tooltip>
      )}
      {showLabels && (
        <div className="orca-flow-node-ports">
          {ports.map((p) => (
            <span key={p} style={{ color: p === ERROR_PORT ? '#ff4d4f' : undefined }}>{p}</span>
          ))}
        </div>
      )}
      {ports.map((p, i) => (
        <Handle
          key={p}
          type="source"
          position={Position.Bottom}
          id={p}
          className={p === ERROR_PORT ? 'error-handle' : undefined}
          style={{ left: `${((i + 1) / (ports.length + 1)) * 100}%` }}
          title={p}
        />
      ))}
    </div>
  );
}

export const OrcaNode = memo(OrcaNodeView);
export const nodeTypes = { orca: OrcaNode };
