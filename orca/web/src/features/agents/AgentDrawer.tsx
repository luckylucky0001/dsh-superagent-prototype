import { useEffect, useMemo } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Alert, AutoComplete, Button, Drawer, Flex, Form, Input, InputNumber, Radio, Select, Space, Typography } from 'antd';
import { api } from '@/api/client';
import { qk, useRuntimes } from '@/api/queries';
import type { Agent, AgentInput, Permission, Provider } from '@/api/types';
import { useT } from '@/app/i18n';
import { PROVIDER_LABELS } from '@/components/AgentAvatar';
import { StatusTag } from '@/components/StatusTag';
import { useNotify } from '@/components/useNotify';

interface Values {
  name: string;
  description?: string;
  provider: Provider;
  runtimeId?: string;
  model?: string;
  effort?: string;
  permission: Permission;
  allowedCommands?: string[];
  instructions?: string;
  maxConcurrency: number;
  timeoutMinutes: number;
}

export function AgentDrawer({ open, agent, onClose, onSaved }: { open: boolean; agent: Agent | null; onClose: () => void; onSaved?: (a: Agent) => void }) {
  const t = useT();
  const notify = useNotify();
  const qc = useQueryClient();
  const runtimes = useRuntimes();
  const [form] = Form.useForm<Values>();
  const provider = Form.useWatch('provider', form);
  const runtimeId = Form.useWatch('runtimeId', form);
  const model = Form.useWatch('model', form);
  const permission = Form.useWatch('permission', form);

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    form.setFieldsValue(
      agent
        ? {
            name: agent.name,
            description: agent.description ?? undefined,
            provider: agent.provider,
            runtimeId: agent.runtimeId ?? undefined,
            model: agent.model ?? undefined,
            effort: agent.effort ?? undefined,
            permission: agent.permission,
            allowedCommands: agent.allowedCommands,
            instructions: agent.instructions ?? undefined,
            maxConcurrency: agent.maxConcurrency,
            timeoutMinutes: agent.timeoutMinutes,
          }
        : {
            provider: 'codex',
            permission: 'workspace',
            maxConcurrency: 1,
            timeoutMinutes: 60,
            runtimeId: runtimes.data?.find((r) => r.status === 'online')?.id,
          },
    );
  }, [open, agent, form, runtimes.data]);

  const runtime = runtimes.data?.find((r) => r.id === runtimeId);
  const providerStatus = runtime?.providers.find((p) => p.provider === provider);
  const models = useMemo(() => providerStatus?.models ?? [], [providerStatus]);
  const modelInfo = models.find((m) => m.id === model);
  const efforts = modelInfo?.efforts ?? [];

  const save = useMutation({
    mutationFn: (v: Values) => {
      const input: AgentInput = {
        name: v.name.trim(),
        description: v.description ?? '',
        provider: v.provider,
        runtimeId: v.runtimeId ?? '',
        model: v.model ?? '',
        effort: v.effort ?? '',
        permission: v.permission,
        allowedCommands: v.allowedCommands ?? [],
        instructions: v.instructions ?? '',
        maxConcurrency: v.maxConcurrency,
        timeoutMinutes: v.timeoutMinutes,
      };
      return agent ? api.updateAgent(agent.id, input) : api.createAgent(input);
    },
    onSuccess: (a) => {
      void qc.invalidateQueries({ queryKey: qk.agents });
      qc.setQueryData(qk.agent(a.id), a);
      notify.success(agent ? t('agents.saved') : t('agents.created', { name: a.name }));
      onSaved?.(a);
      onClose();
    },
    onError: notify.error,
  });

  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={560}
      title={agent ? t('agents.edit', { name: agent.name }) : t('agents.new')}
      destroyOnHidden
      extra={
        <Space>
          <Button onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="primary" loading={save.isPending} onClick={() => form.submit()}>{t('common.save')}</Button>
        </Space>
      }
    >
      <Form form={form} layout="vertical" onFinish={(v) => save.mutate(v)} requiredMark>
        <Form.Item name="name" label={t('agents.name')} extra={t('agents.nameHelp')}
          rules={[
            { required: true, message: t('form.required') },
            { pattern: /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/, message: t('agents.nameInvalid') },
          ]}>
          <Input prefix="@" placeholder="Builder" />
        </Form.Item>
        <Form.Item name="description" label={t('common.description')}>
          <Input placeholder={t('agents.descriptionPlaceholder')} />
        </Form.Item>
        <Form.Item name="provider" label={t('agents.provider')}>
          <Radio.Group optionType="button" buttonStyle="solid"
            onChange={() => form.setFieldsValue({ model: undefined, effort: undefined })}
            options={(['codex', 'trae'] as const).map((p) => ({ value: p, label: PROVIDER_LABELS[p] }))} />
        </Form.Item>
        <Form.Item name="runtimeId" label={t('common.runtime')} extra={t('agents.runtimeHelp')}>
          <Select allowClear placeholder={t('agents.anyRuntime')} loading={runtimes.isLoading}
            options={(runtimes.data ?? []).map((r) => ({
              value: r.id,
              label: <Space size={6}>{r.name}<StatusTag status={r.status} /></Space>,
            }))} />
        </Form.Item>
        {runtime && provider && (!providerStatus || !providerStatus.installed || !providerStatus.loggedIn) && (
          <Alert type="warning" showIcon style={{ marginBottom: 16 }}
            message={t('agents.providerUnavailable', { provider: PROVIDER_LABELS[provider] ?? provider, runtime: runtime.name })}
            description={providerStatus?.error ?? undefined} />
        )}
        <Flex gap={12}>
          <Form.Item name="model" label={t('agents.model')} style={{ flex: 1 }} extra={modelInfo?.description ?? (models.length ? undefined : t('agents.modelFree'))}>
            <AutoComplete
              allowClear
              placeholder={models.find((m) => m.isDefault)?.id ?? t('agents.modelDefault')}
              options={models.map((m) => ({
                value: m.id,
                label: (
                  <Space size={6}>
                    <span>{m.displayName ?? m.id}</span>
                    <Typography.Text type="secondary" className="orca-mono" style={{ fontSize: 11 }}>{m.id}</Typography.Text>
                    {m.isDefault && <Typography.Text type="success" style={{ fontSize: 11 }}>{t('agents.default')}</Typography.Text>}
                  </Space>
                ),
              }))}
              filterOption={(input, opt) => String(opt?.value ?? '').toLowerCase().includes(input.toLowerCase())}
              onChange={() => form.setFieldsValue({ effort: undefined })}
            />
          </Form.Item>
          <Form.Item name="effort" label={t('agents.effort')} style={{ width: 200 }}>
            <Select allowClear placeholder={modelInfo?.defaultEffort ?? t('agents.modelDefault')}
              options={(efforts.length ? efforts : ['low', 'medium', 'high']).map((e) => ({ value: e, label: e }))} />
          </Form.Item>
        </Flex>
        <Form.Item name="permission" label={t('agents.permission')}>
          <Radio.Group>
            <Space direction="vertical">
              <Radio value="read_only">{t('agents.perm.read_only')}</Radio>
              <Radio value="workspace">{t('agents.perm.workspace')}</Radio>
              <Radio value="full">{t('agents.perm.full')}</Radio>
            </Space>
          </Radio.Group>
        </Form.Item>
        {permission === 'full' && (
          <Alert type={runtime && !runtime.isolated ? 'error' : 'warning'} showIcon style={{ marginBottom: 16 }}
            message={t('agents.fullWarning')}
            description={runtime && !runtime.isolated ? t('agents.fullNotIsolated', { runtime: runtime.name }) : undefined} />
        )}
        <Form.Item name="allowedCommands" label={t('agents.allowedCommands')} extra={t('agents.allowedCommandsHelp')}>
          <Select mode="tags" tokenSeparators={[',']} placeholder="git, npm test, mvn -q verify" />
        </Form.Item>
        <Form.Item name="instructions" label={t('agents.instructions')} extra={t('agents.instructionsHelp')}>
          <Input.TextArea autoSize={{ minRows: 4, maxRows: 16 }} />
        </Form.Item>
        <Space size={16}>
          <Form.Item name="maxConcurrency" label={t('agents.maxConcurrency')}>
            <InputNumber min={1} max={16} />
          </Form.Item>
          <Form.Item name="timeoutMinutes" label={t('agents.timeoutMinutes')}>
            <InputNumber min={1} max={1440} />
          </Form.Item>
        </Space>
      </Form>
    </Drawer>
  );
}
