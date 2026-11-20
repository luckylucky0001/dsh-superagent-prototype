import { useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Alert, Form, Input, Modal, Select, Typography } from 'antd';
import { api } from '@/api/client';
import { useAgents, useIssues } from '@/api/queries';
import type { Workflow, WorkflowDefinition } from '@/api/types';
import { useT } from '@/app/i18n';
import { useNotify } from '@/components/useNotify';

export function manualTrigger(def: WorkflowDefinition | null | undefined) {
  return def?.nodes?.find((n) => n.type === 'trigger.manual');
}

export function manualInputs(def: WorkflowDefinition | null | undefined): string[] {
  const n = manualTrigger(def);
  const v = n?.config?.inputs;
  return typeof v === 'string' ? v.split(',').map((s) => s.trim()).filter(Boolean) : [];
}

/** Asks for the manual trigger's inputs and starts a run. */
export function RunWorkflowModal({ workflow, open, onClose }: { workflow: Workflow | null; open: boolean; onClose: () => void }) {
  const t = useT();
  const notify = useNotify();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [form] = Form.useForm<Record<string, string>>();
  const inputs = manualInputs(workflow?.definition);
  const trigger = manualTrigger(workflow?.definition);
  const issues = useIssues();
  const agents = useAgents();
  const run = useMutation({
    mutationFn: (values: Record<string, string>) => {
      const input: Record<string, unknown> = {};
      for (const k of inputs) if (values[k] !== undefined && values[k] !== '') input[k] = values[k];
      return api.runWorkflow(workflow!.id, input, trigger?.id);
    },
    onSuccess: (r) => {
      void qc.invalidateQueries({ queryKey: ['runs'] });
      notify.success(t('wf.runStarted'));
      onClose();
      navigate(`/runs/${r.id}`);
    },
    onError: notify.error,
  });
  return (
    <Modal open={open} onCancel={onClose} title={t('wf.runTitle', { name: workflow?.name ?? '' })} okText={t('wf.run')}
      onOk={() => form.submit()} confirmLoading={run.isPending} destroyOnHidden>
      {!workflow?.enabled && <Alert type="info" showIcon message={t('wf.runDisabledNote')} style={{ marginBottom: 12 }} />}
      <Form name="runWorkflow" form={form} layout="vertical" onFinish={(v) => run.mutate(v)} preserve={false}>
        {inputs.length === 0 && <Typography.Text type="secondary">{t('wf.noInputs')}</Typography.Text>}
        {inputs.map((name) => (
          <Form.Item key={name} name={name} label={<span className="orca-mono">{name}</span>}
            extra={/issue/i.test(name) ? t('wf.issueIdHelp') : /agent|implementer|reviewer|assignee/i.test(name) ? t('wf.agentInputHelp') : undefined}>
            {/issue/i.test(name) ? (
              <Select showSearch allowClear optionFilterProp="label" loading={issues.isLoading}
                options={(issues.data ?? []).map((i) => ({ value: i.id, label: `${i.key} · ${i.title}` }))} />
            ) : /agent|implementer|reviewer|assignee/i.test(name) ? (
              <Select showSearch allowClear optionFilterProp="label" loading={agents.isLoading}
                options={(agents.data ?? []).map((a) => ({ value: a.name, label: `${a.name} · ${a.provider}` }))} />
            ) : (
              <Input />
            )}
          </Form.Item>
        ))}
      </Form>
    </Modal>
  );
}
