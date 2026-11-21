import { RouterProvider } from 'react-router-dom';
import { QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntApp, ConfigProvider } from 'antd';
import enUS from 'antd/locale/en_US';
import zhCN from 'antd/locale/zh_CN';
import { ApiError } from '@/api/client';
import { I18nProvider, useI18n } from './i18n';
import { router } from './router';
import { theme } from './theme';

const queryClient = new QueryClient({
  queryCache: new QueryCache(),
  defaultOptions: {
    queries: {
      retry: (count, e) => !(e instanceof ApiError && e.status >= 400 && e.status < 500) && count < 2,
      refetchOnWindowFocus: true,
      staleTime: 5_000,
    },
    mutations: { retry: false },
  },
});

function Themed() {
  const { lang } = useI18n();
  return (
    <ConfigProvider locale={lang === 'zh' ? zhCN : enUS} theme={theme}>
      <AntApp>
        <RouterProvider router={router} future={{ v7_startTransition: true }} />
      </AntApp>
    </ConfigProvider>
  );
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <Themed />
      </I18nProvider>
    </QueryClientProvider>
  );
}
