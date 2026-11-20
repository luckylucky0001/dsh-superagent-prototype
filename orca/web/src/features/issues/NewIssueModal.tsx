import { useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Form, Input, Modal, Select, Space, Tabs, Typography } from 'antd';
import { api } from '@/api/client';
import { qk, useAgents, useIssues, useMembers, useProjects } from '@/api/queries';
import { ISSUE_PRIORITIES, ISSUE_STATUSES, type IssueInput, type IssuePriority, type IssueStatus } from '@/api/types';
import { useT } from '@/app/i18n';
import { AgentAvatar, MemberAvatar, ProviderTag } from '@/components/AgentAvatar';
import { Markdown } from '@/components/Markdown';
import { PriorityIcon } from '@/components/StatusTag';
import { useNotify } from '@/components/useNotify';

interface Values {
  title: string;
  description?: string;
  priority: IssuePriority;
  status: IssueStatus;
  assignee?: string;
  projectId?: string;
  labels?: string[];
}

/** Assignee select values are `agent:<id>` or `member:<id>`. */
export function parseAssignee(v: string | undefined): { assigneeType: 'agent' | 'member'; assigneeId: string } | null {
  if (!v) return null;
  const i = v.indexOf(':');
  const type = v.slice(0, i);
  return type === 'agent' || type === 'member' ? { assigneeType: type, assigneeId: v.slice(i + 1) } : null;
}

export function assigneeValue(type: string | null, id: string | null): string | undefined {
  return type && id ? `${type}:${id}` : undefined;
}

/** Agents and members, grouped, for assignee pickers. */
export function useAssigneeOptions() {
  const t = useT();
  const agents = useAgents();
  const members = useMembers();
  return [
    {
      label: t('common.agents'),
      title: 'agents',
      options: (agents.data ?? []).map((a) => ({
        value: `agent:${a.id}`,
        searchText: a.name,
        label: (
          <Space size={6}>
            <AgentAvatar agent={a} size={18} />
            {a.name}
            <ProviderTag provider={a.provider} small />
          </Space>
        ),
      })),
    },
    {
      label: t('common.members'),
      title: 'members',
      options: (members.data ?? []).map((m) => ({
        value: `member:${m.id}`,
        searchText: `${m.name} ${m.email}`,
        label: (
          <Space size={6}>
            <MemberAvatar name={m.name} size={18} />
            {m.name}
          </Space>
        ),
      })),
    },
  ];
}

export function useLabelOptions() {
  const issues = useIssues();
  const all = new Set<string>();
  for (const i of issues.data ?? []) for (const l of i.labels) all.add(l);
  return [...all].sort().map((l) => ({ value: l, label: l }));
}

export function NewIssueModal({ open, onClose, defaultStatus, defaultProjectId }: {
  open: boolean; onClose: () => void; defaultStatus?: IssueStatus; defaultProjectId?: string;
}) {
  const t = useT();
  const notify = useNotify();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [form] = Form.useForm<Values>();
  const description = Form.useWatch('description', form);
  const assigneeOptions = useAssigneeOptions();
  const projects = useProjects();
  const labelOptions = useLabelOptions();

  const create = useMutation({
    mutationFn: (v: Values) => {
      const input: IssueInput = {
        title: v.title,
        description: v.description,
        priority: v.priority,
        status: v.status,
        labels: v.labels ?? [],
        projectId: v.projectId,
      };
      const a = parseAssignee(v.assignee);
      if (a) Object.assign(input, a);
      return api.createIssue(input);
    },
    onSuccess: (issue) => {
      void qc.invalidateQueries({ queryKey: qk.issues });
      notify.success(t('issues.created', { key: issue.key }));
      form.resetFields();
      onClose();
      navigate(`/issues/${issue.key}`);
    },
    onError: notify.error,
  });

  return (
    <Modal
      open={open}
      title={t('issues.new')}
      onCancel={onClose}
      onOk={() => form.submit()}
      okText={t('common.create')}
      confirmLoading={create.isPending}
      width={680}
      destroyOnHidden
    >
      <Form form={form} layout="vertical" onFinish={(v) => create.mutate(v)} preserve={false}
        initialValues={{ priority: 'none', status: defaultStatus ?? 'todo', projectId: defaultProjectId ?? projects.data?.[0]?.id }}>
        <Form.Item name="projectId" label={t('issues.project')}>
          <Select loading={projects.isLoading}
            options={(projects.data ?? []).map((p) => ({ value: p.id, label: <Space size={6}><Typography.Text code>{p.key}</Typography.Text>{p.name}</Space> }))} />
        </Form.Item>
        <Form.Item name="title" label={t('issues.title')} rules={[{ required: true, whitespace: true, message: t('form.required') }]}>
          <Input autoFocus placeholder={t('issues.titlePlaceholder')} />
        </Form.Item>
        <Tabs
          size="small"
          items={[
            {
              key: 'write',
              label: t('common.write'),
              children: (
                <Form.Item name="description" style={{ marginBottom: 12 }}>
                  <Input.TextArea autoSize={{ minRows: 5, maxRows: 14 }} placeholder={t('issues.descriptionPlaceholder')} />
                </Form.Item>
              ),
            },
            {
              key: 'preview',
              label: t('common.preview'),
              children: (
                <div style={{ minHeight: 120, marginBottom: 12, padding: '4px 2px' }}>
                  {description ? <Markdown>{description}</Markdown> : <Typography.Text type="secondary">{t('common.nothingToPreview')}</Typography.Text>}
                </div>
              ),
            },
          ]}
        />
        <Space size={12} wrap style={{ display: 'flex' }}>
          <Form.Item name="status" label={t('issues.status')} style={{ width: 150 }}>
            <Select options={ISSUE_STATUSES.map((s) => ({ value: s, label: t.dyn(`status.${s}`, s) }))} />
          </Form.Item>
          <Form.Item name="priority" label={t('issues.priority')} style={{ width: 150 }}>
            <Select options={ISSUE_PRIORITIES.map((p) => ({ value: p, label: <Space size={6}><PriorityIcon priority={p} />{t.dyn(`priority.${p}`, p)}</Space> }))} />
          </Form.Item>
          <Form.Item name="assignee" label={t('issues.assignee')} style={{ width: 300 }}
            extra={t('issues.assignHint')}>
            <Select allowClear showSearch optionFilterProp="searchText" options={assigneeOptions} placeholder={t('issues.unassigned')} />
          </Form.Item>
        </Space>
        <Form.Item name="labels" label={t('issues.labels')}>
          <Select mode="tags" options={labelOptions} tokenSeparators={[',']} placeholder={t('issues.labelsPlaceholder')} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
