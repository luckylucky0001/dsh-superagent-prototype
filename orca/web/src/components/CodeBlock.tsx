import { Typography } from 'antd';
import type { CSSProperties } from 'react';
import { prettyJson } from './format';

export function CodeBlock({ children, maxHeight = 360, style }: { children: string; maxHeight?: number; style?: CSSProperties }) {
  return (
    <pre className="orca-code" style={{ maxHeight, ...style }}>
      {children}
    </pre>
  );
}

export function JsonView({ value, maxHeight }: { value: unknown; maxHeight?: number }) {
  if (value === undefined) return <Typography.Text type="secondary">—</Typography.Text>;
  return <CodeBlock maxHeight={maxHeight}>{prettyJson(value)}</CodeBlock>;
}

/** A unified diff with added/removed lines coloured. */
export function DiffView({ diff, maxHeight = 420 }: { diff: string; maxHeight?: number }) {
  const lines = diff.split('\n');
  return (
    <pre className="orca-code orca-diff" style={{ maxHeight }}>
      {lines.map((l, i) => {
        let cls = '';
        if (l.startsWith('+++') || l.startsWith('---') || l.startsWith('diff ') || l.startsWith('index ')) cls = 'meta';
        else if (l.startsWith('@@')) cls = 'hunk';
        else if (l.startsWith('+')) cls = 'add';
        else if (l.startsWith('-')) cls = 'del';
        return (
          <span key={i} className={cls}>
            {l}
            {'\n'}
          </span>
        );
      })}
    </pre>
  );
}
