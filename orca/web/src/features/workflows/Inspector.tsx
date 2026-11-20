import { useState } from 'react';
import { Alert, Button, Collapse, Divider, Flex, Form, Input, InputNumber, Popconfirm, Select, Tag, Typography } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import type { NodeDef } from '@/api/types';
import { useT } from '@/app/i18n';
import { FieldInput, TemplateInput, VariableList } from './FieldInput';
import { CATEGORY_COLORS, NODE_ID_PATTERN, type FlowEdge, type FlowNode, type VariableGroup } from './graph';
import { CATEGORY_ICONS } from './FlowNode';

interface NodeInspectorProps {
  node: FlowNode;
  groups: VariableGroup[];
  /** The id may still change for nodes added in this editing session. */
  idEditable: boolean;
  takenIds: Set<string>;
  onChange: (def: NodeDef) => void;
  onRename: (newId: string) => void;
  onDelete: () => void;
  onJsonValidity: (field: string, ok: boolean) => void;
}

export function NodeInspector({ node, groups, idEditable, takenIds, onChange, onRename, onDelete, onJsonValidity }: NodeInspectorProps) {
  const t = useT();
  const { def, descriptor, problems } = node.data;
  const [idDraft, setIdDraft] = useState(def.id);
  const idError = !NODE_ID_PATTERN.test(idDraft)
    ? t('wf.idInvalid')
    : idDraft !== def.id && takenIds.has(idDraft)
      ? t('wf.idTaken')
      : null;
  const set = (patch: Partial<NodeDef>) => onChange({ ...def, ...patch });
  const setConfig = (name: string, v: unknown) => {
    const config = { ...def.config };
    if (v === undefined || v === '') delete config[name];
    else config[name] = v;
    onChange({ ...def, config });
  };
  const category = descriptor?.category ?? 'action';

  return (
    <div>
      <Flex align="center" gap={8} style={{ marginBottom: 4 }}>
        <span style={{ color: CATEGORY_COLORS[category] }}>{CATEGORY_ICONS[category]}</span>
        <Typography.Text strong style={{ fontSize: 15 }}>{descriptor?.label ?? def.type}</Typography.Text>
        <Tag bordered={false} className="orca-mono" style={{ marginInlineStart: 'auto' }}>{def.type}</Tag>
      </Flex>
      {descriptor?.description && <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>{descriptor.description}</Typography.Paragraph>}
      {!descriptor && <Alert type="error" showIcon message={t('wf.unknownComponent')} style={{ marginBottom: 12 }} />}
      {problems && problems.length > 0 && (
        <Alert type="error" showIcon style={{ marginBottom: 12 }} message={<ul style={{ margin: 0, paddingInlineStart: 16 }}>{problems.map((p) => <li key={p}>{p}</li>)}</ul>} />
      )}
      <Form layout="vertical" size="small" requiredMark="optional" component="div">
        <Form.Item label={t('wf.nodeName')}>
          <Input value={def.name ?? ''} onChange={(e) => set({ name: e.target.value })} placeholder={def.id} />
        </Form.Item>
        <Form.Item label={t('wf.nodeId')} validateStatus={idError ? 'error' : undefined}
          help={idError ?? (idEditable ? t('wf.idHelpNew') : t('wf.idHelp', { id: def.id }))}>
          <Input value={idEditable ? idDraft : def.id} disabled={!idEditable} className="orca-mono"
            onChange={(e) => setIdDraft(e.target.value)}
            onBlur={() => !idError && idDraft !== def.id && onRename(idDraft)}
            onPressEnter={() => !idError && idDraft !== def.id && onRename(idDraft)} />
        </Form.Item>
        <Divider orientation="left" orientationMargin={0} plain style={{ margin: '8px 0 12px', fontSize: 12 }}>{t('wf.configuration')}</Divider>
        {(descriptor?.fields ?? []).map((f) => (
          <Form.Item key={f.name} label={f.label} required={f.required} extra={f.help}
            validateStatus={f.required && (def.config[f.name] === undefined || def.config[f.name] === '') ? 'warning' : undefined}>
            <FieldInput field={f} value={def.config[f.name]} onChange={(v) => setConfig(f.name, v)} groups={groups} config={def.config}
              onJsonValidity={(ok) => onJsonValidity(f.name, ok)} />
          </Form.Item>
        ))}
        {descriptor && descriptor.fields.length === 0 && <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('wf.noConfig')}</Typography.Text>}
        {!descriptor?.trigger && (
          <>
            <Divider orientation="left" orientationMargin={0} plain style={{ margin: '8px 0 12px', fontSize: 12 }}>{t('wf.execution')}</Divider>
            <Flex gap={12}>
              <Form.Item label={t('wf.retries')} style={{ flex: 1 }}>
                <InputNumber min={0} max={10} value={def.retries} onChange={(v) => set({ retries: v ?? 0 })} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item label={t('wf.timeoutSeconds')} style={{ flex: 1 }} tooltip={t('wf.timeoutHelp')}>
                <InputNumber min={0} value={def.timeoutSeconds} onChange={(v) => set({ timeoutSeconds: v ?? 0 })} style={{ width: '100%' }} />
              </Form.Item>
            </Flex>
            <Form.Item label={t('wf.onError')} extra={def.onError === 'CONTINUE' ? t('wf.onErrorContinueHelp') : t('wf.onErrorFailHelp')}>
              <Select value={def.onError} onChange={(onError) => set({ onError })}
                options={[{ value: 'FAIL', label: t('wf.onErrorFail') }, { value: 'CONTINUE', label: t('wf.onErrorContinue') }]} />
            </Form.Item>
          </>
        )}
      </Form>
      {groups.length > 0 && (
        <Collapse size="small" ghost style={{ marginInline: -12 }}
          items={[{
            key: 'vars',
            label: <Typography.Text type="secondary">{t('wf.availableVariables')}</Typography.Text>,
            children: (
              <VariableList groups={groups} onPick={(expr) => void navigator.clipboard?.writeText(`{{ ${expr} }}`)} />
            ),
          }]} />
      )}
      <Divider style={{ margin: '12px 0' }} />
      <Popconfirm title={t('wf.deleteNodeConfirm')} onConfirm={onDelete} okButtonProps={{ danger: true }}>
        <Button danger block icon={<DeleteOutlined />}>{t('wf.deleteNode')}</Button>
      </Popconfirm>
    </div>
  );
}

export function EdgeInspector({ edge, groups, onChange, onDelete }: {
  edge: FlowEdge; groups: VariableGroup[]; onChange: (condition: string | null) => void; onDelete: () => void;
}) {
  const t = useT();
  return (
    <div>
      <Typography.Text strong style={{ fontSize: 15 }}>{t('wf.edge')}</Typography.Text>
      <Typography.Paragraph type="secondary" className="orca-mono" style={{ fontSize: 12, marginTop: 4 }}>
        {edge.source}.{edge.sourceHandle ?? 'out'} → {edge.target}
      </Typography.Paragraph>
      {edge.data?.problems && edge.data.problems.length > 0 && (
        <Alert type="error" showIcon style={{ marginBottom: 12 }} message={edge.data.problems.join('; ')} />
      )}
      <Form layout="vertical" size="small" component="div">
        <Form.Item label={t('wf.condition')} extra={t('wf.conditionHelp')}>
          <TemplateInput value={edge.data?.condition ?? undefined} onChange={(v) => onChange(v ?? null)} mono bare groups={groups}
            placeholder="nodes.review.output.structured.verdict == 'pass'" />
        </Form.Item>
      </Form>
      <Button danger block icon={<DeleteOutlined />} onClick={onDelete}>{t('wf.deleteEdge')}</Button>
    </div>
  );
}
