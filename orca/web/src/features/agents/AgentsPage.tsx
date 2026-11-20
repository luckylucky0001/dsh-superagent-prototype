import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Card, Col, Flex, Row, Space, Tag, Typography } from 'antd';
import { PlusOutlined, RobotOutlined } from '@ant-design/icons';
import { useAgents, useRuntimeMap } from '@/api/queries';
import { useT } from '@/app/i18n';
import { AgentAvatar, ProviderTag } from '@/components/AgentAvatar';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, QueryState } from '@/components/QueryState';
import { StatusTag } from '@/components/StatusTag';
import { AgentDrawer } from './AgentDrawer';

export const PERMISSION_COLORS: Record<string, string> = { read_only: 'green', workspace: 'blue', full: 'red' };

export function AgentsPage() {
  const t = useT();
  const agents = useAgents();
  const runtimes = useRuntimeMap();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);

  return (
    <>
      <PageHeader title={t('nav.agents')} subtitle={t('agents.subtitle')}
        extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>{t('agents.new')}</Button>} />
      <QueryState isLoading={agents.isLoading} error={agents.error} onRetry={() => void agents.refetch()}>
        {(agents.data ?? []).length === 0 ? (
          <Card>
            <EmptyState description={t('agents.empty')}>
              <Button type="primary" icon={<RobotOutlined />} onClick={() => setCreating(true)}>{t('agents.new')}</Button>
            </EmptyState>
          </Card>
        ) : (
          <Row gutter={[16, 16]}>
            {agents.data!.map((a) => {
              const rt = a.runtimeId ? runtimes.get(a.runtimeId) : undefined;
              return (
                <Col key={a.id} xs={24} md={12} xl={8} xxl={6}>
                  <Card hoverable onClick={() => navigate(`/agents/${a.id}`)} style={{ height: '100%' }}>
                    <Flex gap={12} align="flex-start">
                      <AgentAvatar agent={a} size={44} />
                      <div style={{ minWidth: 0, flex: 1 }}>
                        <Flex align="center" gap={8}>
                          <Typography.Text strong style={{ fontSize: 16 }}>{a.name}</Typography.Text>
                          <ProviderTag provider={a.provider} />
                        </Flex>
                        <Typography.Paragraph type="secondary" ellipsis={{ rows: 2 }} style={{ margin: '4px 0 8px', minHeight: 22 }}>
                          {a.description || '—'}
                        </Typography.Paragraph>
                        <Space size={[6, 6]} wrap>
                          {a.model && <Tag bordered={false} className="orca-mono">{a.model}{a.effort ? ` · ${a.effort}` : ''}</Tag>}
                          <Tag bordered={false} color={PERMISSION_COLORS[a.permission]}>{t.dyn(`agents.permShort.${a.permission}`, a.permission)}</Tag>
                          {rt ? <Space size={4}><Typography.Text type="secondary" style={{ fontSize: 12 }}>{rt.name}</Typography.Text><StatusTag status={rt.status} /></Space>
                            : <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('agents.anyRuntime')}</Typography.Text>}
                        </Space>
                      </div>
                    </Flex>
                  </Card>
                </Col>
              );
            })}
          </Row>
        )}
      </QueryState>
      <AgentDrawer open={creating} agent={null} onClose={() => setCreating(false)} onSaved={(a) => navigate(`/agents/${a.id}`)} />
    </>
  );
}
