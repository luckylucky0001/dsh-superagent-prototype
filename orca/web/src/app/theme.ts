import type { ThemeConfig } from 'antd';

export const theme: ThemeConfig = {
  token: {
    colorPrimary: '#0f5fd7',
    colorInfo: '#0f5fd7',
    borderRadius: 6,
    colorBgLayout: '#f5f7fa',
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif",
    fontFamilyCode: "'JetBrains Mono', 'SFMono-Regular', Menlo, Consolas, 'Liberation Mono', monospace",
  },
  components: {
    Layout: { headerBg: '#ffffff', siderBg: '#ffffff', headerHeight: 52, headerPadding: '0 20px' },
    Menu: { itemBorderRadius: 6, itemMarginInline: 8, itemHeight: 38 },
  },
};
