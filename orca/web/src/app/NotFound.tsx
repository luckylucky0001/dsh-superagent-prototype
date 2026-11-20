import { Button, Result } from 'antd';
import { Link } from 'react-router-dom';
import { useT } from './i18n';

export function NotFound() {
  const t = useT();
  return (
    <Result status="404" title={t('common.notFound')}
      extra={<Link to="/issues"><Button type="primary">{t('nav.issues')}</Button></Link>} />
  );
}
