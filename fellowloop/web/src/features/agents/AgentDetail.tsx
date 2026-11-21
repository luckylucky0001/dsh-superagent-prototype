import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Breadcrumb, Button, Card, Col, Descriptions, Flex, Input, Modal, Popconfirm, Row, Select, Space, Tag, Typography } from 'antd';
import { DeleteOutlined, EditOutlined, MessageOutlined } from '@ant-design/icons';
import { api } from '@/api/client';
import { qk, useModelLabel, useProjects, useRuntimeMap } from '@/api/queries';
import { useT } from '@/app/i18n';
import { AgentAvatar, ProviderTag } from '@/components/AgentAvatar';
import { QueryState } from '@/components/QueryState';
import { StatusTag } from '@/components/StatusTag';
import { fmtDateTime } from '@/components/format';
import { useNotify } from '@/components/useNotify';
import { AgentDrawer } from './AgentDrawer';
import { PERMISSION_COLORS } from './AgentsPage';
import { TaskTable } from './TaskTable';

export function AgentDetail() {
  const { id = '' } = useParams();
  const t = useT();
  const modelLabel = useModelLabel();
  const notify = useNotify();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const runtimes = useRuntimeMap();
  const agent = useQuery({ queryKey: qk.agent(id), queryFn: () => api.agent(id) });
  const tasks = useQuery({ queryKey: qk.agentTasks(id), queryFn: () => api.agentTasks(id) });
  const [editing, setEditing] = useState(false);
  const [asking, setAsking] = useState(false);
  const [prompt, setPrompt] = useState('');
  const [project, setProject] = useState<string | undefined>();
  const projects = useProjects();
  const repoProjects = (projects.data ?? []).filter((p) => p.repoUrl);
  const ask = useMutation({
    mutationFn: () => api.askAgent(id, prompt, undefined, project),
    onSuccess: (task) => {
      setAsking(false);
      setPrompt('');
      navigate(`/tasks/${task.id}`);
    },
    onError: notify.error,
  });
  const archive = useMutation({
    mutationFn: () => api.archiveAgent(id),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: qk.agents });
      notify.success(t('agents.archived'));
      navigate('/agents');
    },
    onError: notify.error,
  });
  const a = agent.data;
  const rt = a?.runtimeId ? runtimes.get(a.runtimeId) : undefined;

  return (
    <QueryState isLoading={agent.isLoading} error={agent.error} onRetry={() => void agent.refetch()} fullPage>
      {a && (
        <>
          <Breadcrumb style={{ marginBottom: 8 }} items={[{ title: <Link to="/agents">{t('nav.agents')}</Link> }, { title: a.name }]} />
          <Card style={{ marginBottom: 16 }}>
            <Flex justify="space-between" align="flex-start" gap={16} wrap="wrap">
              <Flex gap={16} align="center">
                <AgentAvatar agent={a} size={56} />
                <div>
                  <Flex align="center" gap={8}>
                    <Typography.Title level={4} style={{ margin: 0 }}>{a.name}</Typography.Title>
                    <ProviderTag provider={a.provider} />
                  </Flex>
                  <Typography.Text type="secondary">{a.description || t('agents.mentionAs', { name: a.name })}</Typography.Text>
                </div>
              </Flex>
              <Space>
                <Button type="primary" icon={<MessageOutlined />} onClick={() => setAsking(true)}>{t('agents.ask')}</Button>
                <Button icon={<EditOutlined />} onClick={() => setEditing(true)}>{t('common.edit')}</Button>
                <Popconfirm title={t('agents.archiveConfirm')} onConfirm={() => archive.mutate()} okButtonProps={{ danger: true }}>
                  <Button danger icon={<DeleteOutlined />} loading={archive.isPending}>{t('agents.archive')}</Button>
                </Popconfirm>
              </Space>
            </Flex>
          </Card>
          <Modal open={asking} title={t('agents.askTitle', { name: a.name })} onCancel={() => setAsking(false)}
            okText={t('agents.askSend')} okButtonProps={{ disabled: !prompt.trim() }} confirmLoading={ask.isPending}
            onOk={() => ask.mutate()} destroyOnHidden>
            <Typography.Paragraph type="secondary">{t('agents.askHelp')}</Typography.Paragraph>
            {repoProjects.length > 0 && (
              <Select allowClear value={project} onChange={setProject} style={{ width: '100%', marginBottom: 12 }}
                placeholder={t('agents.askProject')}
                options={repoProjects.map((p) => ({ value: p.id, label: `${p.key} · ${p.name}` }))} />
            )}
            <Input.TextArea value={prompt} onChange={(e) => setPrompt(e.target.value)} autoSize={{ minRows: 4, maxRows: 14 }}
              autoFocus placeholder={t('agents.askPlaceholder')} />
          </Modal>
          <Row gutter={16}>
            <Col xs={24} xl={8}>
              <Card size="small" title={t('agents.configuration')} style={{ marginBottom: 16 }}>
                <Descriptions size="small" column={1}
                  items={[
                    { key: 'rt', label: t('common.runtime'), children: rt ? <Space size={6}>{rt.name}<StatusTag status={rt.status} /></Space> : t('agents.anyRuntime') },
                    { key: 'model', label: t('agents.model'), children: <span className="fellowloop-mono">{a.model ? (modelLabel(a) !== a.model ? `${modelLabel(a)} (${a.model})` : a.model) : t('agents.modelDefault')}</span> },
                    { key: 'effort', label: t('agents.effort'), children: a.effort ?? '—' },
                    { key: 'perm', label: t('agents.permission'), children: <Tag bordered={false} color={PERMISSION_COLORS[a.permission]}>{t.dyn(`agents.permShort.${a.permission}`, a.permission)}</Tag> },
                    { key: 'net', label: t('agents.network'), children: a.permission === 'workspace' && a.networkAccess ? t('agents.networkOn') : t('agents.networkOff') },
                    { key: 'esc', label: t('agents.escalate'), children: a.permission !== 'read_only' && a.escalateApprovals ? t('agents.escalateOn') : t('agents.escalateOff') },
                    { key: 'cmds', label: t('agents.allowedCommands'), children: a.allowedCommands.length ? <Space size={4} wrap>{a.allowedCommands.map((c) => <Tag key={c} className="fellowloop-mono">{c}</Tag>)}</Space> : '—' },
                    { key: 'conc', label: t('agents.maxConcurrency'), children: a.maxConcurrency },
                    { key: 'to', label: t('agents.timeoutMinutes'), children: a.timeoutMinutes },
                    { key: 'upd', label: t('common.updated'), children: fmtDateTime(a.updatedAt) },
                  ]} />
                {a.instructions && (
                  <>
                    <Typography.Text strong>{t('agents.instructions')}</Typography.Text>
                    <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginTop: 4 }} type="secondary">{a.instructions}</Typography.Paragraph>
                  </>
                )}
              </Card>
            </Col>
            <Col xs={24} xl={16}>
              <Card size="small" title={t('agents.recentTasks')}>
                <TaskTable tasks={tasks.data ?? []} loading={tasks.isLoading} />
              </Card>
            </Col>
          </Row>
          <AgentDrawer open={editing} agent={a} onClose={() => setEditing(false)} />
        </>
      )}
    </QueryState>
  );
}
