import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Card, Popconfirm, Space, Switch, Table, Tag, Tooltip, Typography } from 'antd';
import { DeleteOutlined, EditOutlined, PlayCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { api } from '@/api/client';
import { qk } from '@/api/queries';
import type { Workflow } from '@/api/types';
import { useT } from '@/app/i18n';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { fromNow } from '@/components/format';
import { useNotify } from '@/components/useNotify';
import { CATEGORY_COLORS } from './graph';
import { manualTrigger, RunWorkflowModal } from './RunWorkflowModal';

const TRIGGER_LABELS: Record<string, string> = {
  'trigger.issue': 'wf.trigger.issue',
  'trigger.manual': 'wf.trigger.manual',
  'trigger.schedule': 'wf.trigger.schedule',
  'trigger.webhook': 'wf.trigger.webhook',
};

function EnabledSwitch({ wf }: { wf: Workflow }) {
  const notify = useNotify();
  const qc = useQueryClient();
  const toggle = useMutation({
    mutationFn: (v: boolean) => api.setWorkflowEnabled(wf.id, v),
    onSuccess: (updated) => {
      qc.setQueryData<Workflow[]>(qk.workflows, (list) => list?.map((w) => (w.id === updated.id ? updated : w)));
      qc.setQueryData(qk.workflow(updated.id), updated);
    },
    onError: notify.error,
  });
  return <Switch size="small" checked={wf.enabled} loading={toggle.isPending} onChange={(v) => toggle.mutate(v)} />;
}

export function WorkflowsPage() {
  const t = useT();
  const notify = useNotify();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const workflows = useQuery({ queryKey: qk.workflows, queryFn: api.workflows });
  const [running, setRunning] = useState<Workflow | null>(null);
  const remove = useMutation({
    mutationFn: (id: string) => api.deleteWorkflow(id),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: qk.workflows });
      notify.success(t('wf.deleted'));
    },
    onError: notify.error,
  });

  return (
    <>
      <PageHeader title={t('nav.workflows')} subtitle={t('wf.subtitle')}
        extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/workflows/new')}>{t('wf.new')}</Button>} />
      <QueryState isLoading={workflows.isLoading} error={workflows.error} onRetry={() => void workflows.refetch()}>
        <Card styles={{ body: { padding: 0 } }}>
          <Table<Workflow>
            rowKey="id"
            dataSource={workflows.data ?? []}
            pagination={false}
            locale={{ emptyText: t('wf.empty') }}
            onRow={(w) => ({ onDoubleClick: () => navigate(`/workflows/${w.id}/edit`) })}
            columns={[
              {
                title: t('common.name'),
                key: 'name',
                render: (_, w) => (
                  <div>
                    <Space size={8}>
                      <Link to={`/workflows/${w.id}/edit`} style={{ fontWeight: 500 }}>{w.name}</Link>
                      {w.builtIn && <Tag color="gold" bordered={false}>{t('wf.builtIn')}</Tag>}
                    </Space>
                    {w.description && <Typography.Paragraph type="secondary" style={{ margin: 0, fontSize: 12 }} ellipsis={{ rows: 2 }}>{w.description}</Typography.Paragraph>}
                  </div>
                ),
              },
              {
                title: t('wf.triggers'),
                key: 'triggers',
                width: 200,
                render: (_, w) => (
                  <Space size={4} wrap>
                    {(w.definition?.nodes ?? []).filter((n) => n.type.startsWith('trigger.')).map((n) => (
                      <Tag key={n.id} bordered={false} style={{ color: CATEGORY_COLORS.trigger }}>
                        {t.dyn(TRIGGER_LABELS[n.type] ?? '', n.type)}
                        {n.type === 'trigger.issue' && typeof n.config.event === 'string' ? ` · ${n.config.event}` : ''}
                      </Tag>
                    ))}
                  </Space>
                ),
              },
              { title: t('wf.nodes'), key: 'nodes', width: 80, render: (_, w) => w.definition?.nodes?.length ?? 0 },
              { title: t('wf.version'), dataIndex: 'currentVersion', width: 90, render: (v: number) => `v${v}` },
              { title: t('common.updated'), dataIndex: 'updatedAt', width: 140, render: (v: number) => fromNow(v) },
              { title: t('wf.enabled'), key: 'enabled', width: 90, render: (_, w) => <EnabledSwitch wf={w} /> },
              {
                title: '',
                key: 'actions',
                width: 200,
                align: 'right',
                render: (_, w) => (
                  <Space size={4}>
                    {manualTrigger(w.definition) && (
                      <Button size="small" icon={<PlayCircleOutlined />} onClick={() => setRunning(w)}>{t('wf.run')}</Button>
                    )}
                    <Tooltip title={t('common.edit')}>
                      <Button size="small" type="text" icon={<EditOutlined />} onClick={() => navigate(`/workflows/${w.id}/edit`)} />
                    </Tooltip>
                    {!w.builtIn && (
                      <Popconfirm title={t('wf.deleteConfirm')} onConfirm={() => remove.mutate(w.id)} okButtonProps={{ danger: true }}>
                        <Button size="small" type="text" danger icon={<DeleteOutlined />} />
                      </Popconfirm>
                    )}
                  </Space>
                ),
              },
            ]}
          />
        </Card>
      </QueryState>
      <RunWorkflowModal workflow={running} open={!!running} onClose={() => setRunning(null)} />
    </>
  );
}
