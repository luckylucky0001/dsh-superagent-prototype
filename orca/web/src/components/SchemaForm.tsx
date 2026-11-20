import { Form, Input, InputNumber, Radio, Select, Space, Switch, Typography } from 'antd';
import type { JsonSchema, Question } from '@/api/types';
import { useT } from '@/app/i18n';

/**
 * Form items for agent / workflow questions: [{id, header, question, options[{label, description}]}].
 * Render inside an antd Form; values land under {@code answers.<id>}.
 */
export function QuestionFields({ questions }: { questions: Question[] }) {
  const t = useT();
  return (
    <>
      {questions.map((q) => {
        const label = (
          <Space direction="vertical" size={0}>
            {q.header && q.header !== q.question && <Typography.Text strong>{q.header}</Typography.Text>}
            <span>{q.question ?? q.header ?? q.id}</span>
          </Space>
        );
        const name = ['answers', q.id];
        if (q.options && q.options.length > 0) {
          return (
            <Form.Item key={q.id} name={name} label={label} rules={[{ required: true, message: t('form.required') }]}>
              <Radio.Group>
                <Space direction="vertical">
                  {q.options.map((o) => (
                    <Radio key={o.label} value={o.label}>
                      {o.label}
                      {o.description && (
                        <Typography.Text type="secondary" style={{ marginInlineStart: 8, fontSize: 12 }}>
                          {o.description}
                        </Typography.Text>
                      )}
                    </Radio>
                  ))}
                </Space>
              </Radio.Group>
            </Form.Item>
          );
        }
        return (
          <Form.Item key={q.id} name={name} label={label} rules={[{ required: true, message: t('form.required') }]}>
            {q.isSecret ? <Input.Password /> : <Input.TextArea autoSize={{ minRows: 1, maxRows: 6 }} />}
          </Form.Item>
        );
      })}
    </>
  );
}

function typeOf(s: JsonSchema): string {
  if (Array.isArray(s.type)) return s.type.find((x) => x !== 'null') ?? 'string';
  return s.type ?? (s.enum ? 'string' : s.properties ? 'object' : 'string');
}

/** Form items for a JSON Schema object's properties; values land under {@code answers.<property>}. */
export function SchemaFields({ schema }: { schema: JsonSchema }) {
  const t = useT();
  const props = schema.properties ?? {};
  const required = new Set(schema.required ?? []);
  return (
    <>
      {Object.entries(props).map(([key, p]) => {
        const type = typeOf(p);
        const label = p.title ?? key;
        const rules = required.has(key) && type !== 'boolean' ? [{ required: true, message: t('form.required') }] : [];
        const common = { name: ['answers', key], label, rules, extra: p.description, initialValue: p.default };
        if (p.enum && p.enum.length > 0) {
          return (
            <Form.Item key={key} {...common}>
              <Select options={p.enum.map((v) => ({ value: v as string, label: String(v) }))} />
            </Form.Item>
          );
        }
        switch (type) {
          case 'number':
          case 'integer':
            return (
              <Form.Item key={key} {...common}>
                <InputNumber style={{ width: '100%' }} precision={type === 'integer' ? 0 : undefined} />
              </Form.Item>
            );
          case 'boolean':
            return (
              <Form.Item key={key} {...common} valuePropName="checked">
                <Switch />
              </Form.Item>
            );
          case 'array': {
            const items = p.items ?? {};
            return (
              <Form.Item key={key} {...common}>
                <Select
                  mode={items.enum ? 'multiple' : 'tags'}
                  options={(items.enum ?? []).map((v) => ({ value: v as string, label: String(v) }))}
                  placeholder={items.enum ? undefined : t('form.tagsHint')}
                  tokenSeparators={[',']}
                />
              </Form.Item>
            );
          }
          case 'object':
            return (
              <Form.Item
                key={key}
                {...common}
                rules={[
                  ...rules,
                  {
                    validator: (_r, v: unknown) => {
                      if (v === undefined || v === '' || typeof v !== 'string') return Promise.resolve();
                      try {
                        JSON.parse(v);
                        return Promise.resolve();
                      } catch {
                        return Promise.reject(new Error(t('form.invalidJson')));
                      }
                    },
                  },
                ]}
                extra={p.description ?? t('form.jsonObject')}
              >
                <Input.TextArea autoSize={{ minRows: 2, maxRows: 8 }} className="orca-mono" />
              </Form.Item>
            );
          default:
            return (
              <Form.Item key={key} {...common}>
                <Input.TextArea autoSize={{ minRows: 1, maxRows: 6 }} />
              </Form.Item>
            );
        }
      })}
    </>
  );
}

/** Converts SchemaFields values (object properties are JSON strings) to the answer payload. */
export function schemaAnswers(schema: JsonSchema, answers: Record<string, unknown> | undefined): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(answers ?? {})) {
    if (v === undefined) continue;
    const p = schema.properties?.[k];
    if (p && typeOf(p) === 'object' && typeof v === 'string' && v.trim()) out[k] = JSON.parse(v);
    else out[k] = v;
  }
  return out;
}
