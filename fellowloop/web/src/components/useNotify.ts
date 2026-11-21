import { App } from 'antd';
import { useCallback } from 'react';
import { errorMessage } from '@/api/client';

/** antd message helpers bound to the App context. */
export function useNotify() {
  const { message } = App.useApp();
  const error = useCallback((e: unknown) => {
    message.error(errorMessage(e), 5);
  }, [message]);
  const success = useCallback((text: string) => {
    message.success(text);
  }, [message]);
  return { error, success, message };
}
