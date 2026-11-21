import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button, Card, Flex, Progress, Typography } from 'antd';
import { CheckCircleFilled, CloseOutlined } from '@ant-design/icons';
import { useAgents, useIssues, useProjects, useRuntimes } from '@/api/queries';
import { useT } from '@/app/i18n';

const DISMISSED = 'fellowloop.setupDismissed';

interface Step {
  key: string;
  done: boolean;
  title: string;
  help: string;
  action: React.ReactNode;
}

/**
 * First-run guide on the board: the five things a new team does before agents can work, each ticked off
 * from live data. Hidden once everything is done, or when dismissed (remembered in this browser).
 */
export function SetupChecklist({ onNewIssue }: { onNewIssue: () => void }) {
  const t = useT();
  const runtimes = useRuntimes();
  const agents = useAgents();
  const projects = useProjects();
  const issues = useIssues();
  const [dismissed, setDismissed] = useState(() => {
    try {
      return localStorage.getItem(DISMISSED) === '1';
    } catch {
      return false;
    }
  });
  if (dismissed || !runtimes.data || !agents.data || !projects.data || !issues.data) return null;

  const online = runtimes.data.some((r) => r.status === 'online');
  const cliReady = runtimes.data.some((r) => r.status === 'online' && r.providers.some((p) => p.installed && p.loggedIn));
  const steps: Step[] = [
    {
      key: 'runtime', done: online, title: t('setup.runtime'), help: t('setup.runtimeHelp'),
      action: <Link to="/runtimes"><Button size="small">{t('setup.open')}</Button></Link>,
    },
    {
      key: 'cli', done: cliReady, title: t('setup.cli'), help: t('setup.cliHelp'),
      action: <Link to="/runtimes"><Button size="small">{t('setup.open')}</Button></Link>,
    },
    {
      key: 'agent', done: agents.data.length > 0, title: t('setup.agent'), help: t('setup.agentHelp'),
      action: <Link to="/agents"><Button size="small">{t('setup.open')}</Button></Link>,
    },
    {
      key: 'project', done: projects.data.some((p) => !!p.repoUrl), title: t('setup.project'), help: t('setup.projectHelp'),
      action: <Link to="/settings?tab=projects"><Button size="small">{t('setup.open')}</Button></Link>,
    },
    {
      key: 'issue', done: issues.data.some((i) => i.assigneeType === 'agent'), title: t('setup.issue'), help: t('setup.issueHelp'),
      action: <Button size="small" type="primary" onClick={onNewIssue}>{t('issues.new')}</Button>,
    },
  ];
  const done = steps.filter((s) => s.done).length;
  if (done === steps.length) {
    // once a team is set up, a machine going offline later does not bring the guide back
    try {
      localStorage.setItem(DISMISSED, '1');
    } catch {
      // not remembered; it hides again on the next complete check
    }
    return null;
  }
  const next = steps.find((s) => !s.done)?.key;

  const dismiss = () => {
    try {
      localStorage.setItem(DISMISSED, '1');
    } catch {
      // the guide simply comes back next time
    }
    setDismissed(true);
  };

  return (
    <Card size="small" style={{ marginBottom: 16 }}
      title={<Flex align="center" gap={12}>{t('setup.title')}
        <Progress percent={Math.round((done / steps.length) * 100)} size="small" style={{ width: 140, margin: 0 }}
          format={() => `${done}/${steps.length}`} /></Flex>}
      extra={<Button type="text" size="small" icon={<CloseOutlined />} onClick={dismiss} aria-label={t('setup.dismiss')} />}>
      <Flex vertical gap={10}>
        {steps.map((s, i) => (
          <Flex key={s.key} align="center" gap={12} style={{ opacity: s.done ? 0.55 : 1 }}>
            {s.done
              ? <CheckCircleFilled style={{ color: '#16a34a', fontSize: 18 }} />
              : <span className="fellowloop-setup-num">{i + 1}</span>}
            <div style={{ flex: 1, minWidth: 0 }}>
              <Typography.Text strong delete={s.done}>{s.title}</Typography.Text>
              {!s.done && <div><Typography.Text type="secondary" style={{ fontSize: 12 }}>{s.help}</Typography.Text></div>}
            </div>
            {!s.done && s.key === next && s.action}
          </Flex>
        ))}
      </Flex>
    </Card>
  );
}
