import { Flex, Typography } from 'antd';
import type { ReactNode } from 'react';

export function PageHeader({ title, subtitle, extra, breadcrumb }: { title: ReactNode; subtitle?: ReactNode; extra?: ReactNode; breadcrumb?: ReactNode }) {
  return (
    <div className="orca-page-header">
      {breadcrumb && <div style={{ marginBottom: 4 }}>{breadcrumb}</div>}
      <Flex justify="space-between" align="center" gap={16} wrap="wrap">
        <div style={{ minWidth: 0 }}>
          <Typography.Title level={4} style={{ margin: 0 }}>{title}</Typography.Title>
          {subtitle && <Typography.Text type="secondary">{subtitle}</Typography.Text>}
        </div>
        {extra && <Flex gap={8} align="center" wrap="wrap">{extra}</Flex>}
      </Flex>
    </div>
  );
}
