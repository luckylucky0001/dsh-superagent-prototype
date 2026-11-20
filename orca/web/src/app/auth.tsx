import { useEffect, type ReactNode } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Flex, Spin } from 'antd';
import { api, ApiError, onUnauthorized } from '@/api/client';
import { qk } from '@/api/queries';
import type { Member } from '@/api/types';

export function useMe() {
  return useQuery<Member, Error>({
    queryKey: qk.me,
    queryFn: api.me,
    retry: (count, e) => !(e instanceof ApiError && e.status === 401) && count < 2,
    staleTime: 5 * 60_000,
  });
}

/** Renders children only for a signed-in member; otherwise sends them to /login. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const me = useMe();
  const location = useLocation();
  const navigate = useNavigate();
  const qc = useQueryClient();

  useEffect(() => {
    onUnauthorized(() => {
      qc.removeQueries({ queryKey: qk.me });
      navigate('/login', { replace: true, state: { from: window.location.pathname + window.location.search } });
    });
  }, [navigate, qc]);

  if (me.isLoading) {
    return (
      <Flex justify="center" align="center" style={{ height: '100vh' }}>
        <Spin size="large" />
      </Flex>
    );
  }
  if (me.error instanceof ApiError && me.error.status === 401) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  return <>{children}</>;
}
