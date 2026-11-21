import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Flex, Input, InputNumber, Popover, Select, Space, Switch, Typography } from 'antd';
import type { InputRef } from 'antd';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import { DeleteOutlined, FunctionOutlined, PlusOutlined } from '@ant-design/icons';
import { api } from '@/api/client';
import { useAgents } from '@/api/queries';
import type { FieldSpec } from '@/api/types';
import { useT } from '@/app/i18n';
import { browserTimeZone, fmtDateTime } from '@/components/format';
import type { VariableGroup } from './graph';

export function VariableList({ groups, onPick }: { groups: VariableGroup[]; onPick: (expr: string) => void }) {
  const t = useT();
  if (groups.length === 0 || groups.every((g) => g.vars.length === 0)) {
    return <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('wf.noVariables')}</Typography.Text>;
  }
  return (
    <div style={{ maxHeight: 320, overflow: 'auto', minWidth: 260 }}>
      {groups.map((g) => (
        <div key={g.key} style={{ marginBottom: 8 }}>
          <Typography.Text type="secondary" style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: 0.4 }}>{g.title}</Typography.Text>
          {g.vars.map((v) => (
            <span key={v.expr} className="fellowloop-var fellowloop-mono" onMouseDown={(e) => e.preventDefault()} onClick={() => onPick(v.expr)} title={v.description ?? undefined}>
              {v.expr}
              {v.description && <Typography.Text type="secondary" style={{ fontSize: 11, marginInlineStart: 6, fontFamily: 'inherit' }}>{v.description}</Typography.Text>}
            </span>
          ))}
        </div>
      ))}
    </div>
  );
}

function VariableButton({ groups, onPick }: { groups: VariableGroup[]; onPick: (expr: string) => void }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  return (
    <Popover open={open} onOpenChange={setOpen} trigger="click" placement="leftTop" title={t('wf.insertVariable')}
      content={<VariableList groups={groups} onPick={(e) => { onPick(e); setOpen(false); }} />}>
      <Button size="small" type="text" icon={<FunctionOutlined />} title={t('wf.insertVariable')} />
    </Popover>
  );
}

function insertAt(value: string, el: HTMLInputElement | HTMLTextAreaElement | null | undefined, text: string): [string, number] {
  const start = el?.selectionStart ?? value.length;
  const end = el?.selectionEnd ?? value.length;
  return [value.slice(0, start) + text + value.slice(end), start + text.length];
}

interface TextFieldProps {
  value: string | undefined;
  onChange: (v: string | undefined) => void;
  multiline?: boolean;
  mono?: boolean;
  groups?: VariableGroup[];
  /** Expressions insert bare names; templates wrap them in {{ }}. */
  bare?: boolean;
  placeholder?: string;
}

/** Input / textarea with a variable picker that inserts at the cursor. */
export function TemplateInput({ value, onChange, multiline, mono, groups, bare, placeholder }: TextFieldProps) {
  const inputRef = useRef<InputRef>(null);
  const areaRef = useRef<TextAreaRef>(null);
  const pick = (expr: string) => {
    const el = multiline ? areaRef.current?.resizableTextArea?.textArea : inputRef.current?.input;
    const [next, caret] = insertAt(value ?? '', el, bare ? expr : `{{ ${expr} }}`);
    onChange(next);
    requestAnimationFrame(() => {
      el?.focus();
      el?.setSelectionRange(caret, caret);
    });
  };
  const button = groups ? <VariableButton groups={groups} onPick={pick} /> : null;
  if (multiline) {
    return (
      <div style={{ position: 'relative' }}>
        <Input.TextArea ref={areaRef} value={value ?? ''} onChange={(e) => onChange(e.target.value)} autoSize={{ minRows: 3, maxRows: 14 }}
          className={mono ? 'fellowloop-mono' : undefined} placeholder={placeholder} style={{ paddingInlineEnd: 30, fontSize: mono ? 12 : undefined }} />
        {button && <div style={{ position: 'absolute', top: 2, right: 2 }}>{button}</div>}
      </div>
    );
  }
  return (
    <Input ref={inputRef} value={value ?? ''} onChange={(e) => onChange(e.target.value === '' ? undefined : e.target.value)}
      className={mono ? 'fellowloop-mono' : undefined} placeholder={placeholder} suffix={button} style={{ fontSize: mono ? 12 : undefined }} />
  );
}

function AgentField({ value, onChange, groups }: { value: string | undefined; onChange: (v: string | undefined) => void; groups: VariableGroup[] }) {
  const t = useT();
  const agents = useAgents();
  const known = (agents.data ?? []).some((a) => a.id === value || a.name === value);
  const [mode, setMode] = useState<'agent' | 'template'>(value && !known && agents.data ? 'template' : 'agent');
  useEffect(() => {
    if (value && agents.data && !known) setMode('template');
  }, [value, known, agents.data]);
  return (
    <Space.Compact style={{ width: '100%' }}>
      <Select value={mode} onChange={setMode} style={{ width: 104 }} popupMatchSelectWidth={false}
        options={[{ value: 'agent', label: t('wf.agentMode') }, { value: 'template', label: t('wf.templateMode') }]} />
      {mode === 'agent' ? (
        <Select value={known ? (agents.data!.find((a) => a.id === value || a.name === value)!.id) : undefined} allowClear
          onChange={(v?: string) => onChange(v)} style={{ flex: 1 }} placeholder={t('wf.pickAgent')} loading={agents.isLoading}
          options={(agents.data ?? []).map((a) => ({ value: a.id, label: `${a.name} · ${a.provider}` }))} />
      ) : (
        <div style={{ flex: 1 }}>
          <TemplateInput value={value} onChange={onChange} mono groups={groups} placeholder="{{ trigger.agentId }}" />
        </div>
      )}
    </Space.Compact>
  );
}

function CronField({ value, onChange, timezone }: { value: string | undefined; onChange: (v: string | undefined) => void; timezone?: string }) {
  const t = useT();
  const [debounced, setDebounced] = useState(value ?? '');
  useEffect(() => {
    const h = setTimeout(() => setDebounced(value ?? ''), 400);
    return () => clearTimeout(h);
  }, [value]);
  const preview = useQuery({
    queryKey: ['cron', debounced, timezone ?? ''],
    queryFn: () => api.cronPreview(debounced, timezone),
    enabled: debounced.trim().split(/\s+/).length >= 5,
    retry: false,
  });
  return (
    <div>
      <Input value={value ?? ''} onChange={(e) => onChange(e.target.value || undefined)} className="fellowloop-mono" placeholder="0 9 * * 1-5" />
      <div style={{ marginTop: 4, fontSize: 12 }}>
        {preview.error ? (
          <Typography.Text type="danger">{(preview.error as Error).message}</Typography.Text>
        ) : preview.data ? (
          <>
            <Typography.Text type="secondary">{t('wf.nextRuns', { tz: browserTimeZone() })}</Typography.Text>
            {preview.data.map((ms) => (
              <div key={ms} className="fellowloop-mono" style={{ color: '#595959' }}>{fmtDateTime(ms)}</div>
            ))}
          </>
        ) : null}
      </div>
    </div>
  );
}

/** JSON textarea; reports parse errors and only propagates valid values. */
function JsonField({ value, onChange, onValidity }: { value: unknown; onChange: (v: unknown) => void; onValidity: (ok: boolean) => void }) {
  const t = useT();
  // the text is owned here once mounted; the inspector remounts fields when another node is selected
  const [text, setText] = useState(() => (value === undefined || value === null ? '' : JSON.stringify(value, null, 2)));
  const [error, setError] = useState<string | null>(null);
  return (
    <div>
      <Input.TextArea value={text} className="fellowloop-mono" style={{ fontSize: 12 }} autoSize={{ minRows: 3, maxRows: 16 }}
        status={error ? 'error' : undefined}
        onChange={(e) => {
          const s = e.target.value;
          setText(s);
          if (!s.trim()) {
            setError(null);
            onValidity(true);
            onChange(undefined);
            return;
          }
          try {
            const parsed: unknown = JSON.parse(s);
            setError(null);
            onValidity(true);
            onChange(parsed);
          } catch (err) {
            setError((err as Error).message);
            onValidity(false);
          }
        }} />
      {error && <Typography.Text type="danger" style={{ fontSize: 12 }}>{t('form.invalidJson')}: {error}</Typography.Text>}
    </div>
  );
}

function KeyValueField({ value, onChange, groups }: { value: unknown; onChange: (v: Record<string, string> | undefined) => void; groups: VariableGroup[] }) {
  const t = useT();
  const obj = value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
  const [rows, setRows] = useState<[string, string][]>(() => Object.entries(obj).map(([k, v]) => [k, String(v ?? '')]));
  const commit = (next: [string, string][]) => {
    setRows(next);
    const out: Record<string, string> = {};
    for (const [k, v] of next) if (k.trim()) out[k.trim()] = v;
    onChange(Object.keys(out).length ? out : undefined);
  };
  return (
    <Space direction="vertical" style={{ display: 'flex' }} size={4}>
      {rows.map(([k, v], i) => (
        <Flex key={i} gap={4}>
          <Input value={k} placeholder={t('wf.key')} style={{ width: '38%' }} className="fellowloop-mono"
            onChange={(e) => commit(rows.map((r, j) => (j === i ? [e.target.value, r[1]] : r)))} />
          <div style={{ flex: 1 }}>
            <TemplateInput value={v} groups={groups} mono onChange={(nv) => commit(rows.map((r, j) => (j === i ? [r[0], nv ?? ''] : r)))} />
          </div>
          <Button type="text" icon={<DeleteOutlined />} onClick={() => commit(rows.filter((_, j) => j !== i))} />
        </Flex>
      ))}
      <Button size="small" icon={<PlusOutlined />} onClick={() => setRows([...rows, ['', '']])}>{t('wf.addRow')}</Button>
    </Space>
  );
}

interface FieldInputProps {
  field: FieldSpec;
  value: unknown;
  onChange: (v: unknown) => void;
  groups: VariableGroup[];
  config: Record<string, unknown>;
  onJsonValidity: (ok: boolean) => void;
}

/** One configuration field, rendered by its FieldKind. */
export function FieldInput({ field, value, onChange, groups, config, onJsonValidity }: FieldInputProps) {
  const str = typeof value === 'string' ? value : value === undefined || value === null ? undefined : String(value);
  switch (field.kind) {
    case 'TEXT':
      return <TemplateInput value={str} onChange={onChange} multiline />;
    case 'TEMPLATE':
      return <TemplateInput value={str} onChange={onChange} multiline groups={groups} />;
    case 'EXPRESSION':
      return <TemplateInput value={str} onChange={onChange} mono bare groups={groups} placeholder="nodes.x.output.y == 'z'" />;
    case 'NUMBER':
      return <InputNumber value={typeof value === 'number' ? value : value ? Number(value) : null} onChange={(v) => onChange(v ?? undefined)} style={{ width: '100%' }} />;
    case 'BOOLEAN':
      return <Switch checked={value === true || value === 'true'} onChange={onChange} />;
    case 'SELECT':
      return (
        <Select value={str} onChange={onChange} allowClear={!field.required} style={{ width: '100%' }}
          options={field.options.map((o) => ({ value: o.value, label: o.label }))} />
      );
    case 'AGENT':
      return <AgentField value={str} onChange={onChange} groups={groups} />;
    case 'CRON':
      return <CronField value={str} onChange={onChange} timezone={typeof config.timezone === 'string' ? config.timezone : undefined} />;
    case 'JSON':
      return <JsonField value={value} onChange={onChange} onValidity={onJsonValidity} />;
    case 'KEY_VALUE':
      return <KeyValueField value={value} onChange={onChange} groups={groups} />;
    case 'STRING':
    default:
      return <TemplateInput value={str} onChange={onChange} groups={groups} />;
  }
}
