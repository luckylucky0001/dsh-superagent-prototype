import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Card, Form, Input, List, Modal, Popconfirm, Space, Table, Tag, Typography } from 'antd';
import { AppstoreAddOutlined, DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import { api } from '@/api/client';
import { qk, useAgents, useSkills } from '@/api/queries';
import type { Skill, SkillInput } from '@/api/types';
import { useT } from '@/app/i18n';
import { Markdown } from '@/components/Markdown';
import { QueryState } from '@/components/QueryState';
import { fmtDateTime } from '@/components/format';
import { useNotify } from '@/components/useNotify';

/** Reusable Markdown instructions that agents can be given (Settings → Skills). */
export function SkillsTab() {
  const t = useT();
  const skills = useSkills();
  const agents = useAgents();
  const notify = useNotify();
  const qc = useQueryClient();
  const [editing, setEditing] = useState<Skill | 'new' | null>(null);
  const [browsing, setBrowsing] = useState(false);
  const remove = useMutation({
    mutationFn: (id: string) => api.deleteSkill(id),
    onSuccess: () => void qc.invalidateQueries({ queryKey: qk.skills }),
    onError: (e) => notify.error(e),
  });
  const usedBy = (id: string) => (agents.data ?? []).filter((a) => (a.skills ?? []).includes(id)).map((a) => a.name);

  return (
    <QueryState isLoading={skills.isLoading} error={skills.error} onRetry={() => void skills.refetch()}>
      <Typography.Paragraph type="secondary">{t('skills.intro')}</Typography.Paragraph>
      <Card size="small" styles={{ body: { padding: 0 } }} title={t('skills.count', { n: skills.data?.length ?? 0 })}
        extra={
          <Space>
            <Button size="small" icon={<AppstoreAddOutlined />} onClick={() => setBrowsing(true)}>{t('skills.library')}</Button>
            <Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => setEditing('new')}>{t('skills.new')}</Button>
          </Space>
        }>
        <Table<Skill>
          rowKey="id"
          dataSource={skills.data ?? []}
          pagination={false}
          expandable={{ expandedRowRender: (s) => <div style={{ padding: '0 8px' }}><Markdown>{s.content}</Markdown></div> }}
          columns={[
            {
              title: t('common.name'),
              key: 'name',
              render: (_, s) => (
                <div>
                  <Typography.Text strong>{s.name}</Typography.Text>
                  {s.description && <div><Typography.Text type="secondary" style={{ fontSize: 12 }}>{s.description}</Typography.Text></div>}
                </div>
              ),
            },
            {
              title: t('skills.usedBy'),
              key: 'used',
              width: 260,
              render: (_, s) => {
                const names = usedBy(s.id);
                return names.length ? names.join(', ') : <Typography.Text type="secondary">—</Typography.Text>;
              },
            },
            { title: t('skills.updated'), dataIndex: 'updatedAt', width: 170, render: (v: number) => fmtDateTime(v) },
            {
              key: 'actions',
              width: 110,
              render: (_, s) => (
                <Space>
                  <Button size="small" type="text" icon={<EditOutlined />} onClick={() => setEditing(s)} />
                  <Popconfirm title={t('skills.deleteConfirm', { name: s.name })} onConfirm={() => remove.mutate(s.id)}>
                    <Button size="small" type="text" danger icon={<DeleteOutlined />} />
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      </Card>
      <SkillModal skill={editing} onClose={() => setEditing(null)} />
      <SkillLibraryModal open={browsing} onClose={() => setBrowsing(false)} />
    </QueryState>
  );
}

function SkillModal({ skill, onClose }: { skill: Skill | 'new' | null; onClose: () => void }) {
  const t = useT();
  const notify = useNotify();
  const qc = useQueryClient();
  const [form] = Form.useForm<SkillInput>();
  const existing = skill && skill !== 'new' ? skill : null;
  useEffect(() => {
    if (!skill) return;
    form.resetFields();
    if (existing) form.setFieldsValue({ name: existing.name, description: existing.description ?? '', content: existing.content });
  }, [skill, existing, form]);
  const save = useMutation({
    mutationFn: (v: SkillInput) => (existing ? api.updateSkill(existing.id, v) : api.createSkill(v)),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: qk.skills });
      notify.success(t('skills.saved'));
      onClose();
    },
    onError: (e) => notify.error(e),
  });
  return (
    <Modal open={skill !== null} width={760} title={existing ? t('skills.edit') : t('skills.new')} onCancel={onClose}
      okText={t('common.save')} confirmLoading={save.isPending} onOk={() => form.submit()} destroyOnHidden>
      <Form name="skill" form={form} layout="vertical" onFinish={(v) => save.mutate(v)}>
        <Form.Item name="name" label={t('common.name')} rules={[{ required: true }]}>
          <Input placeholder="release-rules" maxLength={100} />
        </Form.Item>
        <Form.Item name="description" label={t('skills.description')}>
          <Input maxLength={300} />
        </Form.Item>
        <Form.Item name="content" label={t('skills.content')} extra={t('skills.contentHelp')} rules={[{ required: true }]}>
          <Input.TextArea className="orca-mono" autoSize={{ minRows: 10, maxRows: 24 }} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

/** Skills shipped with Orca: add the ones you want, or all that are missing. */
function SkillLibraryModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT();
  const notify = useNotify();
  const qc = useQueryClient();
  const library = useQuery({ queryKey: ['skills', 'library'], queryFn: api.skillLibrary, enabled: open });
  const install = useMutation({
    mutationFn: (name: string) => api.installSkill(name),
    onSuccess: (r) => {
      notify.success(t('skills.libraryAdded', { n: r.added }));
      void qc.invalidateQueries({ queryKey: qk.skills });
      void qc.invalidateQueries({ queryKey: ['skills', 'library'] });
    },
    onError: notify.error,
  });
  const missing = (library.data ?? []).filter((e) => !e.installed).length;
  return (
    <Modal open={open} onCancel={onClose} title={t('skills.library')} width={720} destroyOnHidden
      footer={<Space><Button onClick={onClose}>{t('common.close')}</Button>
        <Button type="primary" disabled={missing === 0} loading={install.isPending && install.variables === '*'}
          onClick={() => install.mutate('*')}>{t('skills.libraryAddAll', { n: missing })}</Button></Space>}>
      <Typography.Paragraph type="secondary">{t('skills.libraryIntro')}</Typography.Paragraph>
      <QueryState isLoading={library.isLoading} error={library.error} onRetry={() => void library.refetch()}>
        <List dataSource={library.data ?? []} renderItem={(e) => (
          <List.Item actions={[e.installed
            ? <Tag key="i" bordered={false} color="green" style={{ marginInlineEnd: 0 }}>{t('skills.installed')}</Tag>
            : <Button key="a" size="small" loading={install.isPending && install.variables === e.name} onClick={() => install.mutate(e.name)}>{t('skills.add')}</Button>]}>
            <List.Item.Meta title={<span className="orca-mono">{e.name}</span>} description={e.description} />
          </List.Item>
        )} />
      </QueryState>
    </Modal>
  );
}
