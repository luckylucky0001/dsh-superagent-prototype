import { useState, type ReactNode } from 'react';
import { Flex, Space, Tag, Tooltip, Typography } from 'antd';
import {
  ApiOutlined,
  CheckCircleFilled,
  CloseCircleFilled,
  CodeOutlined,
  DownOutlined,
  EditOutlined,
  GlobalOutlined,
  LoadingOutlined,
  RightOutlined,
  StopOutlined,
  ToolOutlined,
} from '@ant-design/icons';
import { CodeBlock, DiffView } from '../CodeBlock';
import { fmtDuration, prettyJson, shortPath } from '../format';
import { useT } from '@/app/i18n';
import type { TranscriptItem } from './model';

type ToolItem = Extract<TranscriptItem, { kind: 'tool' }>;

const KIND_ICONS: Record<string, ReactNode> = {
  command: <CodeOutlined />,
  file_change: <EditOutlined />,
  mcp: <ApiOutlined />,
  web_search: <GlobalOutlined />,
};

function StatusIcon({ status }: { status: string }) {
  switch (status) {
    case 'running':
      return <LoadingOutlined style={{ color: '#1677ff' }} />;
    case 'failed':
      return <CloseCircleFilled style={{ color: '#ff4d4f' }} />;
    case 'declined':
      return <StopOutlined style={{ color: '#faad14' }} />;
    case 'interrupted':
      return <StopOutlined style={{ color: '#8c8c8c' }} />;
    default:
      return <CheckCircleFilled style={{ color: '#52c41a' }} />;
  }
}

const CHANGE_COLORS: Record<string, string> = { add: 'green', create: 'green', update: 'blue', delete: 'red' };

function FileChangeRow({ path, kind, diff }: { path: string; kind?: string; diff?: string }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="fellowloop-filechange">
      <Flex align="center" gap={8} onClick={() => diff && setOpen((o) => !o)} style={{ cursor: diff ? 'pointer' : 'default' }}>
        {diff ? (open ? <DownOutlined style={{ fontSize: 10 }} /> : <RightOutlined style={{ fontSize: 10 }} />) : <span style={{ width: 10 }} />}
        {kind && <Tag color={CHANGE_COLORS[kind] ?? 'default'} bordered={false} style={{ marginInlineEnd: 0 }}>{kind}</Tag>}
        <Tooltip title={path}>
          <Typography.Text className="fellowloop-mono" style={{ fontSize: 12 }}>{shortPath(path, 4)}</Typography.Text>
        </Tooltip>
      </Flex>
      {open && diff && <DiffView diff={diff} maxHeight={320} />}
    </div>
  );
}

/** MCP results arrive as {content:[{type:'text', text}]}; show the text, else the raw output. */
function mcpText(output: string): string {
  try {
    const v = JSON.parse(output) as { content?: { type?: string; text?: string }[] };
    const texts = (v.content ?? []).filter((c) => c.type === 'text' && typeof c.text === 'string').map((c) => c.text as string);
    if (texts.length) {
      return texts.map((x) => {
        try {
          return prettyJson(JSON.parse(x));
        } catch {
          return x;
        }
      }).join('\n');
    }
  } catch {
    /* not JSON */
  }
  return output;
}

/** One tool call: command, file change, MCP call or web search. */
export function ToolCard({ item }: { item: ToolItem }) {
  const t = useT();
  const running = item.status === 'running';
  const [open, setOpen] = useState<boolean | null>(null);
  const hasBody = item.output.length > 0 || item.changes.length > 0 || (item.toolKind !== 'command' && item.input !== undefined);
  // running commands show their output; finished ones collapse unless they failed
  const expanded = open ?? (running || item.status === 'failed' || item.toolKind === 'file_change');
  const input = item.input as Record<string, unknown> | undefined;
  const title =
    item.toolKind === 'file_change' && item.changes.length > 0
      ? item.changes.length === 1
        ? shortPath(item.changes[0].path, 4)
        : t('transcript.filesChanged', { n: item.changes.length })
      : item.title || item.toolKind;

  return (
    <div className={`fellowloop-tool fellowloop-tool-${item.status}`}>
      <Flex align="center" gap={8} className="fellowloop-tool-head" onClick={() => hasBody && setOpen(!expanded)}
        style={{ cursor: hasBody ? 'pointer' : 'default' }}>
        <span className="fellowloop-tool-icon">{KIND_ICONS[item.toolKind] ?? <ToolOutlined />}</span>
        <Typography.Text className={item.toolKind === 'command' ? 'fellowloop-mono' : undefined} ellipsis={{ tooltip: item.title }}
          style={{ flex: 1, minWidth: 0, fontSize: 13 }}>
          {title}
        </Typography.Text>
        <Space size={8}>
          {item.exitCode !== undefined && item.exitCode !== null && (
            <Tag color={item.exitCode === 0 ? 'default' : 'red'} bordered={false} style={{ marginInlineEnd: 0 }}>
              {t('transcript.exit', { code: item.exitCode })}
            </Tag>
          )}
          {!!item.durationMs && <Typography.Text type="secondary" style={{ fontSize: 12 }}>{fmtDuration(item.durationMs)}</Typography.Text>}
          <Tooltip title={t.dyn(`transcript.toolStatus.${item.status}`, item.status)}>
            <span><StatusIcon status={item.status} /></span>
          </Tooltip>
          {hasBody && (expanded ? <DownOutlined style={{ fontSize: 10 }} /> : <RightOutlined style={{ fontSize: 10 }} />)}
        </Space>
      </Flex>
      {expanded && hasBody && (
        <div className="fellowloop-tool-body">
          {item.toolKind === 'command' && typeof input?.cwd === 'string' && (
            <Typography.Text type="secondary" className="fellowloop-mono" style={{ fontSize: 11, display: 'block', marginBottom: 4 }}>
              {t('transcript.cwd')}: {shortPath(input.cwd, 4)}
            </Typography.Text>
          )}
          {item.toolKind !== 'command' && item.toolKind !== 'file_change' && item.input !== undefined && (
            <CodeBlock maxHeight={200}>{typeof item.input === 'string' ? item.input : prettyJson(item.input)}</CodeBlock>
          )}
          {item.changes.map((c, i) => (
            <FileChangeRow key={`${c.path}-${i}`} {...c} />
          ))}
          {item.output && <CodeBlock maxHeight={320}>{item.toolKind === 'mcp' ? mcpText(item.output) : item.output}</CodeBlock>}
        </div>
      )}
    </div>
  );
}
