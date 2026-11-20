import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Card, Col, Flex, Progress, Row, Segmented, Space, Statistic, Table, Tooltip, Typography } from 'antd';
import { api } from '@/api/client';
import { qk } from '@/api/queries';
import type { AgentStats, DayStats } from '@/api/types';
import { useT } from '@/app/i18n';
import { AgentAvatar, ProviderTag } from '@/components/AgentAvatar';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { fmtDuration, fmtTokens } from '@/components/format';

const COLORS = { succeeded: '#0f5fd7', failed: '#d4380d', other: '#bfc6d1', tokens: '#0f5fd7' };

interface Segment {
  key: string;
  name: string;
  value: number;
  color: string;
}

interface Column {
  label: string;
  segments: Segment[];
}

function niceMax(v: number): number {
  if (v <= 0) return 1;
  const p = 10 ** Math.floor(Math.log10(v));
  for (const m of [1, 2, 2.5, 5, 10]) if (m * p >= v) return m * p;
  return 10 * p;
}

/**
 * Stacked columns on one axis, drawn with CSS: bars anchored to the baseline with rounded data ends,
 * a 2px gap between stacked segments, recessive gridlines, and a tooltip per day.
 */
function Bars({ columns, format, height = 180, legend }: {
  columns: Column[]; format: (n: number) => string; height?: number; legend?: Segment[];
}) {
  const max = niceMax(Math.max(0, ...columns.map((c) => c.segments.reduce((s, x) => s + x.value, 0))));
  const ticks = [max, max / 2, 0];
  const every = columns.length > 20 ? 5 : columns.length > 10 ? 2 : 1;
  return (
    <div>
      {legend && legend.length > 1 && (
        <Flex gap={16} style={{ marginBottom: 8, fontSize: 12 }} wrap="wrap">
          {legend.map((l) => (
            <Space key={l.key} size={6}>
              <span style={{ display: 'inline-block', width: 10, height: 10, borderRadius: 2, background: l.color }} />
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>{l.name}</Typography.Text>
            </Space>
          ))}
        </Flex>
      )}
      <Flex>
        <div style={{ position: 'relative', width: 44, height, flex: 'none' }}>
          {ticks.map((v) => (
            <Typography.Text key={v} type="secondary"
              style={{ position: 'absolute', right: 6, top: (1 - v / max) * height - 8, fontSize: 11 }}>
              {format(v)}
            </Typography.Text>
          ))}
        </div>
        <div style={{ position: 'relative', flex: 1, height, borderBottom: '1px solid #d9d9d9' }}>
          {ticks.slice(0, -1).map((v) => (
            <div key={v} style={{ position: 'absolute', left: 0, right: 0, top: (1 - v / max) * height, borderTop: '1px dashed #eef0f3' }} />
          ))}
          <Flex style={{ position: 'absolute', inset: 0 }} align="flex-end" gap={columns.length > 20 ? 3 : 6}>
            {columns.map((c) => {
              const total = c.segments.reduce((s, x) => s + x.value, 0);
              const visible = c.segments.filter((s) => s.value > 0);
              return (
                <Tooltip
                  key={c.label}
                  title={
                    <div>
                      <div style={{ fontWeight: 600, marginBottom: 2 }}>{c.label}</div>
                      {c.segments.map((s) => (
                        <div key={s.key}>
                          <span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: 2, background: s.color, marginInlineEnd: 6 }} />
                          {s.name}: {format(s.value)}
                        </div>
                      ))}
                    </div>
                  }
                >
                  {/* the hit target is the whole column, taller than the bar */}
                  <div style={{ flex: 1, height: '100%', display: 'flex', flexDirection: 'column', justifyContent: 'flex-end', cursor: 'default', minWidth: 0 }}>
                    <div style={{ display: 'flex', flexDirection: 'column-reverse', gap: 2, height: `${(total / max) * 100}%`, maxWidth: 28, width: '100%', margin: '0 auto' }}>
                      {visible.map((s, i) => (
                        <div key={s.key} style={{
                          flex: s.value,
                          minHeight: 2,
                          background: s.color,
                          borderRadius: i === visible.length - 1 ? '4px 4px 0 0' : 0,
                        }} />
                      ))}
                    </div>
                  </div>
                </Tooltip>
              );
            })}
          </Flex>
        </div>
      </Flex>
      <Flex style={{ marginInlineStart: 44 }} gap={columns.length > 20 ? 3 : 6}>
        {columns.map((c, i) => (
          <div key={c.label} style={{ flex: 1, textAlign: 'center', fontSize: 11, color: '#8c8c8c', minWidth: 0, whiteSpace: 'nowrap', overflow: 'visible' }}>
            {(columns.length - 1 - i) % every === 0 ? c.label.slice(5) : ''}
          </div>
        ))}
      </Flex>
    </div>
  );
}

function rate(s: number, f: number): number | null {
  return s + f > 0 ? Math.round((s / (s + f)) * 100) : null;
}

export function UsagePage() {
  const t = useT();
  const [days, setDays] = useState(14);
  const stats = useQuery({ queryKey: qk.stats(days), queryFn: () => api.stats(days) });
  const d = stats.data;
  const totals = (d?.days ?? []).reduce(
    (a, x) => ({ runs: a.runs + x.runs, succeeded: a.succeeded + x.succeeded, failed: a.failed + x.failed, tokens: a.tokens + x.tokens }),
    { runs: 0, succeeded: 0, failed: 0, tokens: 0 },
  );
  const success = rate(totals.succeeded, totals.failed);
  const legend: Segment[] = [
    { key: 'succeeded', name: t('status.succeeded'), value: 0, color: COLORS.succeeded },
    { key: 'failed', name: t('status.failed'), value: 0, color: COLORS.failed },
    { key: 'other', name: t('usage.otherRuns'), value: 0, color: COLORS.other },
  ];
  const runColumns: Column[] = (d?.days ?? []).map((x: DayStats) => ({
    label: x.day,
    segments: [
      { key: 'succeeded', name: t('status.succeeded'), value: x.succeeded, color: COLORS.succeeded },
      { key: 'failed', name: t('status.failed'), value: x.failed, color: COLORS.failed },
      { key: 'other', name: t('usage.otherRuns'), value: Math.max(0, x.runs - x.succeeded - x.failed), color: COLORS.other },
    ],
  }));
  const tokenColumns: Column[] = (d?.days ?? []).map((x) => ({
    label: x.day,
    segments: [{ key: 'tokens', name: t('transcript.tokens'), value: x.tokens, color: COLORS.tokens }],
  }));

  return (
    <>
      <PageHeader
        title={t('nav.usage')}
        subtitle={d ? t('usage.subtitle', { tz: d.timezone }) : undefined}
        extra={<Segmented value={days} onChange={(v) => setDays(Number(v))}
          options={[7, 14, 30].map((n) => ({ value: n, label: t('usage.days', { n }) }))} />}
      />
      <QueryState isLoading={stats.isLoading} error={stats.error} onRetry={() => void stats.refetch()}>
        {d && (
          <>
            <Row gutter={16} style={{ marginBottom: 16 }}>
              <Col xs={12} md={6}><Card size="small"><Statistic title={t('usage.agentRuns')} value={totals.runs} /></Card></Col>
              <Col xs={12} md={6}>
                <Card size="small">
                  <Statistic title={t('usage.successRate')} value={success ?? '—'} suffix={success !== null ? '%' : undefined} />
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {t('usage.succeededFailed', { s: totals.succeeded, f: totals.failed })}
                  </Typography.Text>
                </Card>
              </Col>
              <Col xs={12} md={6}><Card size="small"><Statistic title={t('transcript.tokens')} value={fmtTokens(totals.tokens)} /></Card></Col>
              <Col xs={12} md={6}>
                <Card size="small">
                  <Statistic title={t('usage.busyTime')} value={fmtDuration(d.agents.reduce((s, a) => s + a.busyMs, 0))} />
                </Card>
              </Col>
            </Row>
            <Row gutter={16} style={{ marginBottom: 16 }}>
              <Col xs={24} xl={12}>
                <Card size="small" title={t('usage.runsPerDay')}>
                  <Bars columns={runColumns} legend={legend} format={(n) => String(Math.round(n))} />
                </Card>
              </Col>
              <Col xs={24} xl={12}>
                <Card size="small" title={t('usage.tokensPerDay')}>
                  <div style={{ height: 22 }} />
                  <Bars columns={tokenColumns} format={(n) => fmtTokens(n)} />
                </Card>
              </Col>
            </Row>
            <Card size="small" title={t('usage.perAgent')} styles={{ body: { padding: 0 } }}>
              <Table<AgentStats>
                rowKey="agentId"
                dataSource={[...d.agents].sort((a, b) => b.tokens - a.tokens)}
                pagination={false}
                locale={{ emptyText: t('usage.noRuns') }}
                columns={[
                  {
                    title: t('common.agent'),
                    key: 'agent',
                    render: (_, a) => (
                      <Space size={8}>
                        <AgentAvatar agent={a} />
                        <Link to={`/agents/${a.agentId}`}>{a.name}</Link>
                        <ProviderTag provider={a.provider} small />
                      </Space>
                    ),
                  },
                  { title: t('usage.runs'), dataIndex: 'runs', align: 'right', width: 90, sorter: (a, b) => a.runs - b.runs },
                  {
                    title: t('usage.successRate'),
                    key: 'rate',
                    width: 200,
                    render: (_, a) => {
                      const r = rate(a.succeeded, a.failed);
                      return r === null ? '—' : (
                        <Tooltip title={t('usage.succeededFailed', { s: a.succeeded, f: a.failed })}>
                          <Flex align="center" gap={8}>
                            <Progress percent={r} size="small" showInfo={false} style={{ width: 100, margin: 0 }}
                              strokeColor={COLORS.succeeded} trailColor="#f0f0f0" />
                            <span>{r}%</span>
                          </Flex>
                        </Tooltip>
                      );
                    },
                  },
                  { title: t('transcript.tokens'), dataIndex: 'tokens', align: 'right', width: 120, render: (n: number) => fmtTokens(n), sorter: (a, b) => a.tokens - b.tokens },
                  { title: t('usage.busyTime'), dataIndex: 'busyMs', align: 'right', width: 130, render: (n: number) => fmtDuration(n), sorter: (a, b) => a.busyMs - b.busyMs },
                ]}
              />
            </Card>
          </>
        )}
      </QueryState>
    </>
  );
}
