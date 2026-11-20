import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Card, Select, Table, Tooltip, Typography } from 'antd';
import { api } from '@/api/client';
import { qk, useIssueMap } from '@/api/queries';
import type { Run } from '@/api/types';
import { useT } from '@/app/i18n';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { StatusTag } from '@/components/StatusTag';
import { durationBetween, fmtDateTime, fmtDuration, fmtTime } from '@/components/format';

export function RunsPage() {
  const t = useT();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [workflowId, setWorkflowId] = useState<string | undefined>(params.get('workflow') ?? undefined);
  const runs = useQuery({ queryKey: qk.runs(workflowId), queryFn: () => api.runs(workflowId) });
  const workflows = useQuery({ queryKey: qk.workflows, queryFn: api.workflows });
  const issues = useIssueMap();

  return (
    <>
      <PageHeader
        title={t('nav.runs')}
        subtitle={t('runs.subtitle')}
        extra={
          <Select allowClear placeholder={t('runs.allWorkflows')} style={{ width: 260 }} value={workflowId}
            onChange={(v?: string) => {
              setWorkflowId(v);
              setParams(v ? { workflow: v } : {});
            }}
            options={(workflows.data ?? []).map((w) => ({ value: w.id, label: w.name }))} />
        }
      />
      <QueryState isLoading={runs.isLoading} error={runs.error} onRetry={() => void runs.refetch()}>
        <Card styles={{ body: { padding: 0 } }}>
          <Table<Run>
            rowKey="id"
            dataSource={runs.data ?? []}
            pagination={{ pageSize: 30, hideOnSinglePage: true }}
            locale={{ emptyText: t('runs.empty') }}
            onRow={(r) => ({ onClick: () => navigate(`/runs/${r.id}`), style: { cursor: 'pointer' } })}
            columns={[
              {
                title: t('runs.workflow'),
                key: 'wf',
                render: (_, r) => (
                  <div>
                    <Link to={`/runs/${r.id}`} onClick={(e) => e.stopPropagation()} style={{ fontWeight: 500 }}>{r.workflowName}</Link>
                    <Typography.Text type="secondary" style={{ fontSize: 12, marginInlineStart: 8 }}>v{r.version}</Typography.Text>
                    {r.error && <Typography.Paragraph type="danger" style={{ margin: 0, fontSize: 12 }} ellipsis={{ rows: 1, tooltip: r.error }}>{r.error}</Typography.Paragraph>}
                  </div>
                ),
              },
              { title: t('common.status'), dataIndex: 'status', width: 130, render: (s: string) => <StatusTag status={s} /> },
              { title: t('runs.trigger'), dataIndex: 'triggerNodeId', width: 120, render: (v: string | null) => <span className="orca-mono" style={{ fontSize: 12 }}>{v ?? '—'}</span> },
              {
                title: t('common.issue'),
                dataIndex: 'issueId',
                width: 220,
                render: (id: string | null) => {
                  if (!id) return <Typography.Text type="secondary">—</Typography.Text>;
                  const i = issues.get(id);
                  return i ? (
                    <Link to={`/issues/${i.key}`} onClick={(e) => e.stopPropagation()}>
                      <Typography.Text ellipsis style={{ maxWidth: 200, color: 'inherit' }}>{i.key} · {i.title}</Typography.Text>
                    </Link>
                  ) : <span className="orca-mono" style={{ fontSize: 12 }}>{id}</span>;
                },
              },
              { title: t('common.started'), dataIndex: 'startedAt', width: 150, render: (v: number) => <Tooltip title={fmtDateTime(v)}><span>{fmtTime(v)}</span></Tooltip> },
              { title: t('common.duration'), key: 'dur', width: 110, render: (_, r) => fmtDuration(durationBetween(r.startedAt, r.finishedAt)) },
            ]}
          />
        </Card>
      </QueryState>
    </>
  );
}
