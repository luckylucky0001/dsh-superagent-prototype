import { createBrowserRouter, Navigate } from 'react-router-dom';
import { RequireAuth } from './auth';
import { AppLayout } from './Layout';
import { LoginPage } from './LoginPage';
import { IssuesBoard } from '@/features/issues/IssuesBoard';
import { IssueDetail } from '@/features/issues/IssueDetail';
import { InboxPage } from '@/features/inbox/InboxPage';
import { AgentsPage } from '@/features/agents/AgentsPage';
import { AgentDetail } from '@/features/agents/AgentDetail';
import { RuntimesPage } from '@/features/runtimes/RuntimesPage';
import { WorkflowsPage } from '@/features/workflows/WorkflowsPage';
import { WorkflowEditor } from '@/features/workflows/WorkflowEditor';
import { RunsPage } from '@/features/runs/RunsPage';
import { RunDetail } from '@/features/runs/RunDetail';
import { TaskPage } from '@/features/tasks/TaskPage';
import { NotFound } from './NotFound';

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Navigate to="/issues" replace /> },
      { path: 'issues', element: <IssuesBoard /> },
      { path: 'issues/:key', element: <IssueDetail /> },
      { path: 'inbox', element: <InboxPage /> },
      { path: 'agents', element: <AgentsPage /> },
      { path: 'agents/:id', element: <AgentDetail /> },
      { path: 'runtimes', element: <RuntimesPage /> },
      { path: 'workflows', element: <WorkflowsPage /> },
      { path: 'workflows/new', element: <WorkflowEditor /> },
      { path: 'workflows/:id/edit', element: <WorkflowEditor /> },
      { path: 'runs', element: <RunsPage /> },
      { path: 'runs/:id', element: <RunDetail /> },
      { path: 'tasks/:id', element: <TaskPage /> },
      { path: '*', element: <NotFound /> },
    ],
  },
]);
