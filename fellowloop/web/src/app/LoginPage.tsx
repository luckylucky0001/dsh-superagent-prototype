import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Card, Flex, Form, Input, Segmented, Typography } from 'antd';
import { LockOutlined, MailOutlined } from '@ant-design/icons';
import { api, errorMessage } from '@/api/client';
import { qk } from '@/api/queries';
import { useI18n } from './i18n';
import { FellowloopLogo } from './FellowloopLogo';

export function LoginPage() {
  const { t, lang, setLang } = useI18n();
  const navigate = useNavigate();
  const location = useLocation();
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const from = (location.state as { from?: string } | null)?.from;

  const onFinish = async (v: { email: string; password: string }) => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.login(v.email.trim(), v.password);
      qc.clear();
      qc.setQueryData(qk.me, res.member);
      navigate(from && from !== '/login' ? from : '/issues', { replace: true });
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setLoading(false);
    }
  };

  return (
    <Flex vertical align="center" justify="center" className="fellowloop-login">
      <Card style={{ width: 380, maxWidth: '92vw' }} styles={{ body: { padding: 32 } }}>
        <Flex vertical align="center" gap={4} style={{ marginBottom: 24 }}>
          <FellowloopLogo size={40} />
          <Typography.Title level={3} style={{ margin: '8px 0 0' }}>Fellowloop</Typography.Title>
          <Typography.Text type="secondary">{t('login.tagline')}</Typography.Text>
        </Flex>
        {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
        <Form layout="vertical" onFinish={onFinish} requiredMark={false} autoComplete="on">
          <Form.Item name="email" label={t('login.email')} rules={[{ required: true, message: t('form.required') }]}>
            <Input prefix={<MailOutlined />} autoComplete="username" autoFocus />
          </Form.Item>
          <Form.Item name="password" label={t('login.password')} rules={[{ required: true, message: t('form.required') }]}>
            <Input.Password prefix={<LockOutlined />} autoComplete="current-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>{t('login.submit')}</Button>
        </Form>
        <Flex justify="center" style={{ marginTop: 20 }}>
          <Segmented size="small" value={lang} onChange={(v) => setLang(v as 'en' | 'zh')}
            options={[{ value: 'en', label: 'English' }, { value: 'zh', label: '中文' }]} />
        </Flex>
      </Card>
    </Flex>
  );
}
