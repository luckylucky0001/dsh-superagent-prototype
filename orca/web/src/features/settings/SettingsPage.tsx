import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Card, Flex, Form, Input, Modal, Popconfirm, Select, Space, Table, Tabs, Tag, Tooltip, Typography } from 'antd';
import { BranchesOutlined, DeleteOutlined, KeyOutlined, PlusOutlined } from '@ant-design/icons';
import { api } from '@/api/client';
import { qk, useMembers, useProjects } from '@/api/queries';
import type { Member, MemberInput, MemberRole, Project, ProjectInput } from '@/api/types';
import { useMe } from '@/app/auth';
import { useT } from '@/app/i18n';
import { MemberAvatar } from '@/components/AgentAvatar';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { fmtDateTime } from '@/components/format';
import { useNotify } from '@/components/useNotify';
import { SkillsTab } from './SkillsTab';

const ROLE_COLORS: Record<string, string> = { owner: 'gold', admin: 'blue', member: 'default' };
const MIN_PASSWORD = 8;

function RoleTag({ role }: { role: string }) {
  const t = useT();
  return <Tag bordered={false} color={ROLE_COLORS[role]}>{t.dyn(`settings.role.${role}`, role)}</Tag>;
}

// ---------------------------------------------------------------- my account

function MyAccount() {
  const t = useT();
  const notify = useNotify();
  const qc = useQueryClient();
  const me = useMe();
  const [nameForm] = Form.useForm<{ name: string }>();
  const [pwForm] = Form.useForm<{ password: string; confirm: string }>();
  const save = useMutation({
    mutationFn: (input: MemberInput) => api.updateMember(me.data!.id, input),
    onSuccess: (m, input) => {
      qc.setQueryData(qk.me, m);
      void qc.invalidateQueries({ queryKey: qk.members });
      if (input.password) {
        pwForm.resetFields();
        notify.success(t('settings.passwordChanged'));
      } else notify.success(t('settings.saved'));
    },
    onError: notify.error,
  });
  if (!me.data) return null;
  return (
    <Space direction="vertical" size={16} style={{ display: 'flex', maxWidth: 560 }}>
      <Card size="small" title={t('settings.profile')}>
        <Space size={12} style={{ marginBottom: 16 }}>
          <MemberAvatar name={me.data.name} size={40} />
          <div>
            <Typography.Text strong>{me.data.name}</Typography.Text>
            <div><Typography.Text type="secondary">{me.data.email}</Typography.Text> <RoleTag role={me.data.role} /></div>
          </div>
        </Space>
        <Form form={nameForm} layout="vertical" initialValues={{ name: me.data.name }} onFinish={(v) => save.mutate({ name: v.name.trim() })}>
          <Form.Item name="name" label={t('settings.name')} rules={[{ required: true, whitespace: true, message: t('form.required') }]}>
            <Input />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={save.isPending && !save.variables?.password}>{t('common.save')}</Button>
        </Form>
      </Card>
      <Card size="small" title={t('settings.changePassword')}>
        <Form form={pwForm} layout="vertical" onFinish={(v) => save.mutate({ password: v.password })}>
          <Form.Item name="password" label={t('settings.newPassword')}
            rules={[{ required: true, message: t('form.required') }, { min: MIN_PASSWORD, message: t('settings.passwordMin', { n: MIN_PASSWORD }) }]}>
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item name="confirm" label={t('settings.confirmPassword')} dependencies={['password']}
            rules={[
              { required: true, message: t('form.required') },
              ({ getFieldValue }) => ({
                validator: (_r, v) => (!v || v === getFieldValue('password') ? Promise.resolve() : Promise.reject(new Error(t('settings.passwordMismatch')))),
              }),
            ]}>
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" icon={<KeyOutlined />} loading={save.isPending && !!save.variables?.password}>
            {t('settings.changePassword')}
          </Button>
        </Form>
      </Card>
    </Space>
  );
}

// ---------------------------------------------------------------- members

function AddMemberModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT();
  const notify = useNotify();
  const qc = useQueryClient();
  const [form] = Form.useForm<MemberInput>();
  const create = useMutation({
    mutationFn: (v: MemberInput) => api.createMember({ ...v, email: v.email?.trim(), name: v.name?.trim() }),
    onSuccess: (m) => {
      void qc.invalidateQueries({ queryKey: qk.members });
      notify.success(t('settings.memberAdded', { name: m.name }));
      onClose();
    },
    onError: notify.error,
  });
  return (
    <Modal open={open} onCancel={onClose} title={t('settings.addMember')} okText={t('common.create')}
      onOk={() => form.submit()} confirmLoading={create.isPending} destroyOnHidden>
      <Form form={form} layout="vertical" preserve={false} initialValues={{ role: 'member' }} onFinish={(v) => create.mutate(v)}>
        <Form.Item name="email" label={t('login.email')} rules={[{ required: true, type: 'email', message: t('settings.emailInvalid') }]}>
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item name="name" label={t('settings.name')} rules={[{ required: true, whitespace: true, message: t('form.required') }]}>
          <Input />
        </Form.Item>
        <Form.Item name="password" label={t('settings.initialPassword')} extra={t('settings.initialPasswordHelp')}
          rules={[{ required: true, message: t('form.required') }, { min: MIN_PASSWORD, message: t('settings.passwordMin', { n: MIN_PASSWORD }) }]}>
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Form.Item name="role" label={t('settings.role')}>
          <Select options={(['member', 'admin'] as MemberRole[]).map((r) => ({ value: r, label: t.dyn(`settings.role.${r}`, r) }))} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

function ResetPasswordModal({ member, onClose }: { member: Member | null; onClose: () => void }) {
  const t = useT();
  const notify = useNotify();
  const [form] = Form.useForm<{ password: string }>();
  const reset = useMutation({
    mutationFn: (password: string) => api.updateMember(member!.id, { password }),
    onSuccess: () => {
      notify.success(t('settings.passwordChanged'));
      onClose();
    },
    onError: notify.error,
  });
  return (
    <Modal open={!!member} onCancel={onClose} title={t('settings.resetPasswordFor', { name: member?.name ?? '' })}
      onOk={() => form.submit()} confirmLoading={reset.isPending} destroyOnHidden>
      <Form form={form} layout="vertical" preserve={false} onFinish={(v) => reset.mutate(v.password)}>
        <Form.Item name="password" label={t('settings.newPassword')}
          rules={[{ required: true, message: t('form.required') }, { min: MIN_PASSWORD, message: t('settings.passwordMin', { n: MIN_PASSWORD }) }]}>
          <Input.Password autoComplete="new-password" />
        </Form.Item>
      </Form>
    </Modal>
  );
}

function Members() {
  const t = useT();
  const notify = useNotify();
  const qc = useQueryClient();
  const me = useMe();
  const members = useMembers();
  const [adding, setAdding] = useState(false);
  const [resetting, setResetting] = useState<Member | null>(null);
  const canManage = me.data?.role === 'owner' || me.data?.role === 'admin';
  const update = useMutation({
    mutationFn: ({ id, input }: { id: string; input: MemberInput }) => api.updateMember(id, input),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: qk.members });
      notify.success(t('settings.saved'));
    },
    onError: notify.error,
  });
  const remove = useMutation({
    mutationFn: (id: string) => api.deleteMember(id),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: qk.members });
      notify.success(t('settings.memberRemoved'));
    },
    onError: notify.error,
  });

  return (
    <QueryState isLoading={members.isLoading} error={members.error} onRetry={() => void members.refetch()}>
      {!canManage && <Alert type="info" showIcon message={t('settings.membersReadOnly')} style={{ marginBottom: 12 }} />}
      <Card size="small" styles={{ body: { padding: 0 } }}
        title={t('settings.membersCount', { n: members.data?.length ?? 0 })}
        extra={canManage && <Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => setAdding(true)}>{t('settings.addMember')}</Button>}>
        <Table<Member>
          rowKey="id"
          dataSource={members.data ?? []}
          pagination={false}
          columns={[
            {
              title: t('settings.name'),
              key: 'name',
              render: (_, m) => (
                <Space>
                  <MemberAvatar name={m.name} />
                  <Typography.Text strong>{m.name}</Typography.Text>
                  {m.id === me.data?.id && <Tag bordered={false}>{t('settings.you')}</Tag>}
                </Space>
              ),
            },
            { title: t('login.email'), dataIndex: 'email' },
            {
              title: t('settings.role'),
              key: 'role',
              width: 180,
              render: (_, m) =>
                canManage && m.role !== 'owner' && m.id !== me.data?.id ? (
                  <Select size="small" value={m.role as MemberRole} style={{ width: 130 }}
                    onChange={(role: MemberRole) => update.mutate({ id: m.id, input: { role } })}
                    options={(['member', 'admin'] as MemberRole[]).map((r) => ({ value: r, label: t.dyn(`settings.role.${r}`, r) }))} />
                ) : (
                  <RoleTag role={m.role} />
                ),
            },
            {
              title: '',
              key: 'actions',
              width: 120,
              align: 'right',
              render: (_, m) =>
                canManage && m.role !== 'owner' && m.id !== me.data?.id ? (
                  <Space size={4}>
                    <Tooltip title={t('settings.resetPassword')}>
                      <Button type="text" size="small" icon={<KeyOutlined />} onClick={() => setResetting(m)} />
                    </Tooltip>
                    <Popconfirm title={t('settings.removeConfirm', { name: m.name })} onConfirm={() => remove.mutate(m.id)} okButtonProps={{ danger: true }}>
                      <Button type="text" size="small" danger icon={<DeleteOutlined />} />
                    </Popconfirm>
                  </Space>
                ) : null,
            },
          ]}
        />
      </Card>
      <AddMemberModal open={adding} onClose={() => setAdding(false)} />
      <ResetPasswordModal member={resetting} onClose={() => setResetting(null)} />
    </QueryState>
  );
}

// ---------------------------------------------------------------- projects

function NewProjectModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT();
  const notify = useNotify();
  const qc = useQueryClient();
  const [form] = Form.useForm<ProjectInput>();
  const key = Form.useWatch('key', form);
  const create = useMutation({
    mutationFn: (v: ProjectInput) =>
      api.createProject({
        key: v.key.trim().toUpperCase(),
        name: v.name.trim(),
        description: v.description?.trim() || undefined,
        repoUrl: v.repoUrl?.trim() || undefined,
        defaultBranch: v.defaultBranch?.trim() || undefined,
      }),
    onSuccess: (p) => {
      void qc.invalidateQueries({ queryKey: qk.projects });
      notify.success(t('settings.projectCreated', { key: p.key }));
      onClose();
    },
    onError: notify.error,
  });
  return (
    <Modal open={open} onCancel={onClose} title={t('settings.newProject')} okText={t('common.create')}
      onOk={() => form.submit()} confirmLoading={create.isPending} destroyOnHidden width={600}>
      <Form form={form} layout="vertical" preserve={false} onFinish={(v) => create.mutate(v)}>
        <Flex gap={12} align="start">
          <Form.Item name="key" label={t('settings.projectKey')} style={{ width: 150 }}
            normalize={(v: string) => v?.toUpperCase()}
            extra={t('settings.projectKeyHelp', { example: `${key || 'APP'}-1` })}
            rules={[{ required: true, message: t('form.required') }, { pattern: /^[A-Za-z][A-Za-z0-9]{1,9}$/, message: t('settings.projectKeyInvalid') }]}>
            <Input placeholder="APP" maxLength={10} className="orca-mono" />
          </Form.Item>
          <Form.Item name="name" label={t('common.name')} style={{ flex: 1 }} rules={[{ required: true, whitespace: true, message: t('form.required') }]}>
            <Input placeholder={t('settings.projectNamePlaceholder')} />
          </Form.Item>
        </Flex>
        <Form.Item name="description" label={t('common.description')}>
          <Input.TextArea autoSize={{ minRows: 2, maxRows: 5 }} />
        </Form.Item>
        <Alert type="info" showIcon icon={<BranchesOutlined />} style={{ marginBottom: 16 }} message={t('settings.repoExplain')} />
        <Flex gap={12} align="start">
          <Form.Item name="repoUrl" label={t('settings.repoUrl')} style={{ flex: 1 }}>
            <Input placeholder="git@github.com:acme/app.git" className="orca-mono" />
          </Form.Item>
          <Form.Item name="defaultBranch" label={t('settings.defaultBranch')} style={{ width: 160 }}>
            <Input placeholder="main" className="orca-mono" />
          </Form.Item>
        </Flex>
      </Form>
    </Modal>
  );
}

function Projects() {
  const t = useT();
  const projects = useProjects();
  const [creating, setCreating] = useState(false);
  return (
    <QueryState isLoading={projects.isLoading} error={projects.error} onRetry={() => void projects.refetch()}>
      <Card size="small" styles={{ body: { padding: 0 } }} title={t('settings.projectsCount', { n: projects.data?.length ?? 0 })}
        extra={<Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => setCreating(true)}>{t('settings.newProject')}</Button>}>
        <Table<Project>
          rowKey="id"
          dataSource={projects.data ?? []}
          pagination={false}
          columns={[
            { title: t('settings.projectKey'), dataIndex: 'key', width: 110, render: (k: string) => <Typography.Text code>{k}</Typography.Text> },
            {
              title: t('common.name'),
              key: 'name',
              width: 260,
              render: (_, p) => (
                <div>
                  <Typography.Text strong>{p.name}</Typography.Text>
                  {p.description && <div><Typography.Text type="secondary" style={{ fontSize: 12 }}>{p.description}</Typography.Text></div>}
                </div>
              ),
            },
            {
              title: t('settings.repository'),
              key: 'repo',
              render: (_, p) =>
                p.repoUrl ? (
                  <Space direction="vertical" size={0} style={{ maxWidth: 520 }}>
                    <Typography.Text className="orca-mono" style={{ fontSize: 12, maxWidth: 520 }} ellipsis={{ tooltip: p.repoUrl }}>{p.repoUrl}</Typography.Text>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      <BranchesOutlined /> {p.defaultBranch ?? 'main'} → orca/{p.key.toLowerCase()}-N
                    </Typography.Text>
                  </Space>
                ) : (
                  <Typography.Text type="secondary">{t('settings.noRepo')}</Typography.Text>
                ),
            },
            { title: t('common.created'), dataIndex: 'createdAt', width: 170, render: (v: number) => fmtDateTime(v) },
          ]}
        />
      </Card>
      <NewProjectModal open={creating} onClose={() => setCreating(false)} />
    </QueryState>
  );
}

export function SettingsPage() {
  const t = useT();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'account';
  return (
    <div style={{ maxWidth: 1100 }}>
      <PageHeader title={t('nav.settings')} />
      <Tabs
        activeKey={tab}
        onChange={(k) => setParams({ tab: k }, { replace: true })}
        items={[
          { key: 'account', label: t('settings.myAccount'), children: <MyAccount /> },
          { key: 'members', label: t('settings.members'), children: <Members /> },
          { key: 'projects', label: t('settings.projects'), children: <Projects /> },
          { key: 'skills', label: t('skills.title'), children: <SkillsTab /> },
        ]}
      />
    </div>
  );
}
