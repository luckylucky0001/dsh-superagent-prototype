import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Alert, Badge, Button, Card, Col, Descriptions, Flex, Modal, Popconfirm, Progress, Row, Space, Steps, Tag, Tooltip, Typography } from 'antd';
import { CheckCircleFilled, CloseCircleFilled, DeleteOutlined, PlusOutlined, ReloadOutlined, WarningFilled } from '@ant-design/icons';
import { api } from '@/api/client';
import { qk, useRuntimes } from '@/api/queries';
import type { ProviderStatus, RegistrationCode, Runtime } from '@/api/types';
import { useT } from '@/app/i18n';
import { PROVIDER_LABELS } from '@/components/AgentAvatar';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, QueryState } from '@/components/QueryState';
import { StatusTag } from '@/components/StatusTag';
import { fmtDateTime, fromNow } from '@/components/format';
import { useNotify } from '@/components/useNotify';

function Check({ ok, label }: { ok: boolean; label: string }) {
  return (
    <Space size={4}>
      {ok ? <CheckCircleFilled style={{ color: '#52c41a' }} /> : <CloseCircleFilled style={{ color: '#bfbfbf' }} />}
      <span>{label}</span>
    </Space>
  );
}

function ProviderPanel({ p }: { p: ProviderStatus }) {
  const t = useT();
  const healthy = p.installed && p.loggedIn && !p.error;
  return (
    <div style={{ border: '1px solid #f0f0f0', borderRadius: 8, padding: '10px 12px', background: healthy ? '#fff' : '#fffbf5' }}>
      <Flex justify="space-between" align="center" style={{ marginBottom: 6 }}>
        <Space size={8}>
          {healthy ? <CheckCircleFilled style={{ color: '#52c41a' }} /> : <WarningFilled style={{ color: '#faad14' }} />}
          <Typography.Text strong>{PROVIDER_LABELS[p.provider] ?? p.provider}</Typography.Text>
          {p.version && <Typography.Text type="secondary" className="orca-mono" style={{ fontSize: 12 }}>v{p.version}</Typography.Text>}
        </Space>
        {p.models.length > 0 && (
          <Tooltip title={p.models.map((m) => m.id).join(', ')}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('runtimes.models', { n: p.models.length })}</Typography.Text>
          </Tooltip>
        )}
      </Flex>
      <Flex gap={16} wrap="wrap" style={{ fontSize: 13 }}>
        <Check ok={p.installed} label={t('runtimes.installed')} />
        <Check ok={p.loggedIn} label={t('runtimes.loggedIn')} />
        {p.authMode && (
          <span>
            <Typography.Text type="secondary">{t('runtimes.auth')}: </Typography.Text>
            {p.authMode}{p.plan ? ` · ${p.plan}` : ''}
          </span>
        )}
      </Flex>
      {p.capabilities.length > 0 && (
        <Flex gap={4} wrap="wrap" style={{ marginTop: 8 }}>
          {[...p.capabilities].sort().map((c) => (
            <Tag key={c} bordered={false} style={{ marginInlineEnd: 0, fontSize: 11 }}>{c}</Tag>
          ))}
        </Flex>
      )}
      {p.error && <Alert type="warning" showIcon message={p.error} style={{ marginTop: 8, padding: '4px 10px', fontSize: 12 }} />}
    </div>
  );
}

function RuntimeCard({ r }: { r: Runtime }) {
  const t = useT();
  const notify = useNotify();
  const qc = useQueryClient();
  const probe = useMutation({
    mutationFn: () => api.probeRuntime(r.id),
    onSuccess: () => notify.success(t('runtimes.probeSent')),
    onError: notify.error,
  });
  const remove = useMutation({
    mutationFn: () => api.deleteRuntime(r.id),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: qk.runtimes });
      notify.success(t('runtimes.removed'));
    },
    onError: notify.error,
  });
  const online = r.status === 'online';
  const load = r.capacity > 0 ? Math.round((r.activeTasks / r.capacity) * 100) : 0;
  return (
    <Card
      title={
        <Space>
          <Badge status={online ? 'success' : 'default'} />
          <span>{r.name}</span>
          <StatusTag status={r.status} />
          {r.isolated && <Tag color="purple" bordered={false}>{t('runtimes.isolated')}</Tag>}
        </Space>
      }
      extra={
        <Space>
          <Tooltip title={online ? t('runtimes.probeHelp') : t('runtimes.offlineHelp')}>
            <Button size="small" icon={<ReloadOutlined />} disabled={!online} loading={probe.isPending} onClick={() => probe.mutate()}>
              {t('runtimes.probe')}
            </Button>
          </Tooltip>
          <Popconfirm title={t('runtimes.removeConfirm')} onConfirm={() => remove.mutate()} okButtonProps={{ danger: true }}>
            <Button size="small" danger icon={<DeleteOutlined />} loading={remove.isPending}>{t('runtimes.remove')}</Button>
          </Popconfirm>
        </Space>
      }
      style={{ height: '100%' }}
    >
      <Descriptions size="small" column={2} style={{ marginBottom: 12 }}
        items={[
          { key: 'host', label: t('runtimes.hostname'), children: r.hostname ?? '—' },
          { key: 'os', label: t('runtimes.os'), children: r.os ?? '—' },
          { key: 'ver', label: t('runtimes.daemon'), children: r.daemonVersion ? <span className="orca-mono">{r.daemonVersion}</span> : '—' },
          { key: 'seen', label: t('runtimes.lastSeen'), children: <Tooltip title={fmtDateTime(r.lastSeenAt)}>{fromNow(r.lastSeenAt)}</Tooltip> },
          {
            key: 'cap',
            label: t('runtimes.capacity'),
            span: 2,
            children: (
              <Flex align="center" gap={8} style={{ width: '100%' }}>
                <Progress percent={load} size="small" showInfo={false} style={{ width: 120, margin: 0 }} />
                {t('runtimes.activeOf', { active: r.activeTasks, capacity: r.capacity })}
              </Flex>
            ),
          },
        ]} />
      <Space direction="vertical" style={{ display: 'flex' }} size={8}>
        {r.providers.length === 0 && <Typography.Text type="secondary">{t('runtimes.noProviders')}</Typography.Text>}
        {r.providers.map((p) => <ProviderPanel key={p.provider} p={p} />)}
      </Space>
    </Card>
  );
}

function AddRuntimeModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT();
  const notify = useNotify();
  const [code, setCode] = useState<RegistrationCode | null>(null);
  const gen = useMutation({ mutationFn: api.registrationCode, onSuccess: setCode, onError: notify.error });
  return (
    <Modal open={open} onCancel={() => { onClose(); setCode(null); }} footer={null} width={720} title={t('runtimes.add')}
      afterOpenChange={(o) => o && !code && gen.mutate()}>
      <Alert type="info" showIcon message={t('runtimes.prereq')} style={{ marginBottom: 16 }} />
      {gen.isPending && <Typography.Text type="secondary">{t('runtimes.generating')}</Typography.Text>}
      {code && (
        <Steps
          direction="vertical"
          size="small"
          current={-1}
          items={[
            {
              title: t('runtimes.step1'),
              description: (
                <Typography.Paragraph copyable={{ text: code.register }} className="orca-mono"
                  style={{ background: '#0f172a', color: '#e2e8f0', padding: '8px 12px', borderRadius: 6, fontSize: 12 }}>
                  {code.register}
                </Typography.Paragraph>
              ),
            },
            {
              title: t('runtimes.step2'),
              description: (
                <Typography.Paragraph copyable={{ text: code.start }} className="orca-mono"
                  style={{ background: '#0f172a', color: '#e2e8f0', padding: '8px 12px', borderRadius: 6, fontSize: 12 }}>
                  {code.start}
                </Typography.Paragraph>
              ),
            },
            { title: t('runtimes.step3'), description: t('runtimes.step3Help') },
          ]}
        />
      )}
      {code && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {t('runtimes.codeExpires', { n: code.expiresInMinutes })}
        </Typography.Text>
      )}
    </Modal>
  );
}

export function RuntimesPage() {
  const t = useT();
  const runtimes = useRuntimes();
  const [adding, setAdding] = useState(false);
  return (
    <>
      <PageHeader title={t('nav.runtimes')} subtitle={t('runtimes.subtitle')}
        extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => setAdding(true)}>{t('runtimes.add')}</Button>} />
      <QueryState isLoading={runtimes.isLoading} error={runtimes.error} onRetry={() => void runtimes.refetch()}>
        {(runtimes.data ?? []).length === 0 ? (
          <Card><EmptyState description={t('runtimes.empty')}><Button type="primary" onClick={() => setAdding(true)}>{t('runtimes.add')}</Button></EmptyState></Card>
        ) : (
          <Row gutter={[16, 16]}>
            {runtimes.data!.map((r) => (
              <Col key={r.id} xs={24} xl={12}>
                <RuntimeCard r={r} />
              </Col>
            ))}
          </Row>
        )}
      </QueryState>
      <AddRuntimeModal open={adding} onClose={() => setAdding(false)} />
    </>
  );
}
