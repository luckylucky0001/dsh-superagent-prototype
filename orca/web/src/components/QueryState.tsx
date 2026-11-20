import { Alert, Button, Empty, Result, Spin } from 'antd';
import type { ReactNode } from 'react';
import { ApiError, errorMessage } from '@/api/client';
import { useT } from '@/app/i18n';

interface Props {
  isLoading: boolean;
  error: unknown;
  onRetry?: () => void;
  children?: ReactNode;
  /** Shown instead of a small spinner when loading. */
  fullPage?: boolean;
}

/** Standard loading / error wrapper for a query. */
export function QueryState({ isLoading, error, onRetry, children, fullPage }: Props) {
  const t = useT();
  if (isLoading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: fullPage ? 96 : 32 }}>
        <Spin />
      </div>
    );
  }
  if (error) {
    if (error instanceof ApiError && error.status === 404) {
      return <Result status="404" title={t('common.notFound')} subTitle={error.message} />;
    }
    return (
      <Alert
        type="error"
        showIcon
        message={t('common.loadFailed')}
        description={errorMessage(error)}
        action={onRetry && <Button size="small" onClick={onRetry}>{t('common.retry')}</Button>}
        style={{ margin: 16 }}
      />
    );
  }
  return <>{children}</>;
}

export function EmptyState({ description, children }: { description: ReactNode; children?: ReactNode }) {
  return (
    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={description} style={{ padding: 32 }}>
      {children}
    </Empty>
  );
}
