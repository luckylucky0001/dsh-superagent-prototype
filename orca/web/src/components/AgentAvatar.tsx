import { Avatar, Space, Tag, Tooltip, Typography } from 'antd';
import { RobotOutlined, UserOutlined } from '@ant-design/icons';
import type { Agent } from '@/api/types';

const PALETTE = ['#0f5fd7', '#7a3ff2', '#0b8a6f', '#c2410c', '#be185d', '#4d7c0f', '#0e7490', '#9333ea'];

function colorFor(s: string): string {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  return PALETTE[Math.abs(h) % PALETTE.length];
}

export function initials(name: string): string {
  const parts = name.split(/[\s_-]+/).filter(Boolean);
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
  return name.slice(0, 2).toUpperCase();
}

export const PROVIDER_COLORS: Record<string, string> = { codex: 'geekblue', trae: 'magenta' };
export const PROVIDER_LABELS: Record<string, string> = { codex: 'Codex', trae: 'TRAE' };

export function ProviderTag({ provider, small }: { provider: string | null | undefined; small?: boolean }) {
  if (!provider) return null;
  return (
    <Tag color={PROVIDER_COLORS[provider] ?? 'default'} bordered={false}
      style={{ marginInlineEnd: 0, ...(small ? { fontSize: 11, lineHeight: '16px', paddingInline: 5 } : {}) }}>
      {PROVIDER_LABELS[provider] ?? provider}
    </Tag>
  );
}

export function AgentAvatar({ agent, size = 'small' }: { agent: Pick<Agent, 'name'> | null | undefined; size?: 'small' | 'default' | 'large' | number }) {
  if (!agent) return <Avatar size={size} icon={<RobotOutlined />} />;
  return (
    <Avatar size={size} style={{ background: colorFor(agent.name), fontSize: typeof size === 'number' ? size * 0.4 : undefined, flex: 'none' }}>
      {initials(agent.name)}
    </Avatar>
  );
}

export function MemberAvatar({ name, size = 'small' }: { name?: string | null; size?: 'small' | 'default' | number }) {
  return name ? (
    <Avatar size={size} style={{ background: '#8c8c8c', flex: 'none' }}>{initials(name)}</Avatar>
  ) : (
    <Avatar size={size} icon={<UserOutlined />} style={{ flex: 'none' }} />
  );
}

/** Avatar + name + provider tag. */
export function AgentLabel({ agent, showProvider = true, fallback }: { agent: Agent | null | undefined; showProvider?: boolean; fallback?: string }) {
  if (!agent) return <Typography.Text type="secondary">{fallback ?? '—'}</Typography.Text>;
  return (
    <Tooltip title={agent.model ? `${agent.provider} · ${agent.model}` : agent.provider}>
      <Space size={6} style={{ minWidth: 0 }}>
        <AgentAvatar agent={agent} />
        <Typography.Text ellipsis style={{ maxWidth: 140 }}>{agent.name}</Typography.Text>
        {showProvider && <ProviderTag provider={agent.provider} small />}
      </Space>
    </Tooltip>
  );
}
