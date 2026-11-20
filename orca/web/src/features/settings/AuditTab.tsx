import { useState } from 'react';
import { useInfiniteQuery } from '@tanstack/react-query';
import { Button, Card, Flex, Input, Table, Tag, Typography } from 'antd';
import { DownloadOutlined, SearchOutlined } from '@ant-design/icons';
import { api } from '@/api/client';
import type { AuditEvent } from '@/api/types';
import { useT } from '@/app/i18n';
import { QueryState } from '@/components/QueryState';
import { fmtDateTime } from '@/components/format';

const PAGE = 100;

/** Colour by area, so approvals and access changes stand out when scanning. */
function areaColor(action: string): string | undefined {
  if (action.startsWith('approval.')) return 'gold';
  if (action.startsWith('auth.login_failed') || action.startsWith('auth.login_blocked')) return 'red';
  if (action.startsWith('member.') || action.startsWith('token.')) return 'purple';
  if (action.startsWith('agent.') || action.startsWith('runtime.')) return 'blue';
  if (action.startsWith('project.') || action.startsWith('workflow.') || action.startsWith('run.')) return 'cyan';
  return undefined;
}

function detailText(d: Record<string, unknown>): string {
  return Object.entries(d)
    .map(([k, v]) => `${k}: ${typeof v === 'object' && v !== null ? JSON.stringify(v) : String(v)}`)
    .join(' · ');
}

/** Who did what, newest first; owners and admins only. CSV export covers the last 90 days. */
export function AuditTab() {
  const t = useT();
  const [filter, setFilter] = useState('');
  const events = useInfiniteQuery({
    queryKey: ['audit'],
    queryFn: ({ pageParam }) => api.audit(pageParam, PAGE),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => (last.length < PAGE ? undefined : `${last[last.length - 1].at}:${last[last.length - 1].id}`),
  });
  const rows = (events.data?.pages ?? []).flat();
  const f = filter.trim().toLowerCase();
  const shown = f
    ? rows.filter((e) => [e.action, e.actorName ?? 'login', e.targetId, e.ip, detailText(e.detail)].some((x) => x?.toLowerCase().includes(f)))
    : rows;
  return (
    <QueryState isLoading={events.isLoading} error={events.error} onRetry={() => void events.refetch()}>
      <Card size="small" styles={{ body: { padding: 0 } }}
        title={t('audit.title')}
        extra={
          <Flex gap={8}>
            <Input allowClear size="small" prefix={<SearchOutlined />} placeholder={t('audit.filter')} value={filter}
              onChange={(e) => setFilter(e.target.value)} style={{ width: 220 }} />
            <Button size="small" icon={<DownloadOutlined />} href="/api/audit.csv">{t('audit.csv')}</Button>
          </Flex>
        }>
        <Typography.Paragraph type="secondary" style={{ fontSize: 12, margin: 0, padding: '10px 12px' }}>{t('audit.intro')}</Typography.Paragraph>
        <Table<AuditEvent>
          rowKey="id"
          size="small"
          tableLayout="fixed"
          dataSource={shown}
          pagination={false}
          columns={[
            { title: t('audit.time'), dataIndex: 'at', width: 160, render: (v: number) => <span style={{ whiteSpace: 'nowrap', fontSize: 12 }}>{fmtDateTime(v)}</span> },
            { title: t('audit.actor'), dataIndex: 'actorName', width: 140, ellipsis: true, render: (v: string | null) => v ?? <Typography.Text type="secondary">{t('audit.anonymous')}</Typography.Text> },
            {
              title: t('audit.action'), dataIndex: 'action', width: 210,
              render: (a: string) => <Tag color={areaColor(a)} bordered={false} className="orca-mono" style={{ fontSize: 11 }}>{a}</Tag>,
            },
            {
              title: t('audit.detail'), key: 'detail', ellipsis: true,
              render: (_, e) => (
                <Typography.Text style={{ fontSize: 12 }} ellipsis={{ tooltip: detailText(e.detail) }}>
                  {e.targetId && <Typography.Text type="secondary" className="orca-mono" style={{ fontSize: 11 }}>{e.targetId} </Typography.Text>}
                  {detailText(e.detail)}
                </Typography.Text>
              ),
            },
            { title: 'IP', dataIndex: 'ip', width: 120, ellipsis: true, render: (v: string | null) => <Typography.Text type="secondary" style={{ fontSize: 12 }}>{v ?? ''}</Typography.Text> },
          ]}
        />
        {events.hasNextPage && (
          <Flex justify="center" style={{ padding: 12 }}>
            <Button onClick={() => void events.fetchNextPage()} loading={events.isFetchingNextPage}>{t('audit.more')}</Button>
          </Flex>
        )}
      </Card>
    </QueryState>
  );
}
