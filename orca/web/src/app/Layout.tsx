import { useState } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { Badge, Button, Dropdown, Flex, Layout, Menu, Segmented, Space, Tooltip, Typography } from 'antd';
import {
  ApartmentOutlined,
  CloudServerOutlined,
  InboxOutlined,
  LogoutOutlined,
  NodeIndexOutlined,
  ProjectOutlined,
  RobotOutlined,
} from '@ant-design/icons';
import { api } from '@/api/client';
import { usePendingInbox } from '@/api/queries';
import { MemberAvatar } from '@/components/AgentAvatar';
import { browserTimeZone } from '@/components/format';
import { useMe } from './auth';
import { useI18n } from './i18n';
import { OrcaLogo } from './OrcaLogo';
import { RealtimeProvider, useRealtimeStatus } from './realtime';

const SECTIONS = ['issues', 'inbox', 'agents', 'runtimes', 'workflows', 'runs'] as const;

function ConnectionDot() {
  const { t } = useI18n();
  const status = useRealtimeStatus();
  const color = status === 'open' ? '#52c41a' : status === 'connecting' ? '#d9d9d9' : '#faad14';
  return (
    <Tooltip title={t.dyn(`realtime.${status}`, status)}>
      <span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: 4, background: color }} />
    </Tooltip>
  );
}

function Shell() {
  const { t, lang, setLang } = useI18n();
  const location = useLocation();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const me = useMe();
  const inbox = usePendingInbox();
  const [collapsed, setCollapsed] = useState(false);
  const section = SECTIONS.find((s) => location.pathname.startsWith(`/${s}`)) ?? (location.pathname.startsWith('/tasks') ? 'issues' : 'issues');
  const pending = inbox.data?.length ?? 0;

  const items = [
    { key: 'issues', icon: <ProjectOutlined />, label: <Link to="/issues">{t('nav.issues')}</Link> },
    {
      key: 'inbox',
      icon: <InboxOutlined />,
      label: (
        <Link to="/inbox">
          <Flex justify="space-between" align="center">
            {t('nav.inbox')}
            {!collapsed && pending > 0 && <Badge count={pending} size="small" />}
          </Flex>
        </Link>
      ),
    },
    { key: 'agents', icon: <RobotOutlined />, label: <Link to="/agents">{t('nav.agents')}</Link> },
    { key: 'runtimes', icon: <CloudServerOutlined />, label: <Link to="/runtimes">{t('nav.runtimes')}</Link> },
    { key: 'workflows', icon: <ApartmentOutlined />, label: <Link to="/workflows">{t('nav.workflows')}</Link> },
    { key: 'runs', icon: <NodeIndexOutlined />, label: <Link to="/runs">{t('nav.runs')}</Link> },
  ];

  const logout = async () => {
    try {
      await api.logout();
    } finally {
      qc.clear();
      navigate('/login', { replace: true });
    }
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Sider theme="light" width={216} collapsible collapsed={collapsed} onCollapse={setCollapsed} className="orca-sider">
        <Link to="/issues" className="orca-brand">
          <OrcaLogo size={28} />
          {!collapsed && <span>Orca</span>}
        </Link>
        <Menu mode="inline" selectedKeys={[section]} items={items} style={{ borderInlineEnd: 0 }} />
      </Layout.Sider>
      <Layout>
        <Layout.Header className="orca-header">
          <Space size={8}>
            <ConnectionDot />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('header.timezone', { tz: browserTimeZone() })}
            </Typography.Text>
          </Space>
          <Space size={16}>
            <Segmented size="small" value={lang} onChange={(v) => setLang(v as 'en' | 'zh')}
              options={[{ value: 'en', label: 'EN' }, { value: 'zh', label: '中文' }]} />
            <Dropdown
              menu={{
                items: [
                  { key: 'email', label: me.data?.email, disabled: true },
                  { type: 'divider' },
                  { key: 'logout', icon: <LogoutOutlined />, label: t('header.logout'), onClick: logout },
                ],
              }}
            >
              <Button type="text" style={{ paddingInline: 6 }}>
                <Space size={8}>
                  <MemberAvatar name={me.data?.name} />
                  <span>{me.data?.name}</span>
                </Space>
              </Button>
            </Dropdown>
          </Space>
        </Layout.Header>
        <Layout.Content className="orca-content">
          <Outlet />
        </Layout.Content>
      </Layout>
    </Layout>
  );
}

export function AppLayout() {
  return (
    <RealtimeProvider>
      <Shell />
    </RealtimeProvider>
  );
}
