import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactElement } from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import {
  AppLayout,
  getWorkspaceDeepLinkId,
  removeWorkspaceDeepLink,
} from './AppLayout';
import { useAuthStore } from '@/shared/auth/store';
import { refreshCurrentMembership } from '@/shared/auth/refreshCurrentMembership';

vi.mock('@/shared/ui/NotificationBell', () => ({
  NotificationBell: () => <div data-testid="notification-bell">bell</div>,
}));
vi.mock('@/shared/auth/refreshCurrentMembership', () => ({
  refreshCurrentMembership: vi.fn(),
}));

function renderWithQueryClient(ui: ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        {ui}
      </QueryClientProvider>,
    ),
  };
}

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{location.pathname}{location.search}</div>;
}

describe('AppLayout', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    vi.clearAllMocks();
  });

  it('keeps the header on public help without platform navigation or workspace refresh', async () => {
    renderWithQueryClient(
      <MemoryRouter initialEntries={['/help?workspaceId=99']}>
        <Routes>
          <Route element={<AppLayout helpCenter />}>
            <Route path="/help" element={<h1>帮助正文</h1>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByRole('heading', { name: '帮助正文' })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: /返回工作项列表/ })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '文档中心' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: /登\s*录/ })).toBeInTheDocument();
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /菜单/ })).not.toBeInTheDocument();
    window.dispatchEvent(new Event('focus'));
    await waitFor(() => expect(refreshCurrentMembership).not.toHaveBeenCalled());
  });

  it('refreshes current membership on mount and when the window regains focus', () => {
    useAuthStore.getState().setCurrentWorkspace(
      { id: 7, name: '星云工坊', description: '' },
      'ADMIN',
    );
    renderWithQueryClient(
      <MemoryRouter initialEntries={['/workitems']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/workitems" element={<div>工作项页</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    expect(refreshCurrentMembership).toHaveBeenCalledTimes(1);
    window.dispatchEvent(new Event('focus'));

    expect(refreshCurrentMembership).toHaveBeenCalledTimes(2);
  });

  it('parses and removes one-shot workspace deep-link parameters', () => {
    expect(getWorkspaceDeepLinkId('?workspaceId=8')).toBe(8);
    expect(getWorkspaceDeepLinkId('?workspaceId=0')).toBeNull();
    expect(getWorkspaceDeepLinkId('?workspaceId=abc')).toBeNull();
    expect(removeWorkspaceDeepLink('?workspaceId=8&tab=timeline')).toBe('?tab=timeline');
    expect(removeWorkspaceDeepLink('?workspaceId=8')).toBe('');
  });

  it('renders the workbench shell with topbar brand and left tree', async () => {
    useAuthStore.getState().setCurrentWorkspace(
      { id: 7, name: '星云工坊', description: '' },
      'READ_ONLY',
    );

    renderWithQueryClient(
      <MemoryRouter initialEntries={['/workitems']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/workitems" element={<div>工作项页</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    // 设计契约 v5:IDE 工作台骨架——顶栏(品牌+路径+⌘K) + 左树 + 底部状态栏
    const shell = document.querySelector('.wb-shell');
    expect(shell).not.toBeNull();
    const brand = await screen.findByRole('link', { name: /返回工作项列表/ });
    expect(brand.closest('.wb-top')).toBeTruthy();
    expect(screen.getByRole('navigation', { name: '导航树' })).toBeInTheDocument();
    expect(document.querySelector('.wb-status')).toBeTruthy();
    expect(document.querySelector('.wb-cmd')).toBeTruthy();
  });

  it('marks the current route as selected in the tree', async () => {
    renderWithQueryClient(
      <MemoryRouter initialEntries={['/workitems']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/workitems" element={<div>工作项列表页</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    const workitems = await screen.findByRole('button', { name: /工作项/ });
    expect(workitems).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('button', { name: /项目总览/ })).not.toHaveAttribute('aria-current');
  });

  it('renders workspace context and a compact user avatar in the topbar', () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    useAuthStore.getState().setCurrentWorkspace({ id: 7, name: '星云工坊', description: '研发项目' }, 'READ_ONLY');

    renderWithQueryClient(
      <MemoryRouter initialEntries={['/settings/roles']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/settings/roles" element={<div>角色权限页</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    // 设计契约 v5:项目切换入口在顶栏(可访问名=项目名);树底提供文档/设置
    expect(screen.getByRole('button', { name: '星云工坊' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '用户菜单' })).toHaveTextContent('爱');
    expect(screen.queryByText('系统设置')).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /成员管理/ })).toHaveLength(1);
    expect(screen.getByRole('region', { name: '页面内容' })).toContainElement(screen.getByText('角色权限页'));
    expect(screen.queryByText('爱丽丝')).not.toBeInTheDocument();
    expect(screen.queryByText('alice@example.com')).not.toBeInTheDocument();
    expect(screen.getByText('角色权限页')).toBeInTheDocument();
  });

  it('navigates to personal settings from user menu', async () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    useAuthStore.getState().setCurrentWorkspace({ id: 7, name: '星云工坊', description: '研发项目' }, 'READ_ONLY');

    renderWithQueryClient(
      <MemoryRouter initialEntries={['/workitems']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/workitems" element={<div>工作项页</div>} />
            <Route path="/profile/settings" element={<div>个人设置页</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    await userEvent.click(screen.getByRole('button', { name: '用户菜单' }));
    await userEvent.click(await screen.findByText('个人设置'));

    expect(await screen.findByText('个人设置页')).toBeInTheDocument();
  });

  it('switches to the deep-linked workitem workspace and cleans the url', async () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    useAuthStore.getState().setAccessToken('workspace-7-token');
    useAuthStore.getState().setCurrentWorkspace({ id: 7, name: '星云工坊', description: '研发项目' }, 'READ_ONLY');
    server.use(
      http.get('/api/workspaces/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [
          { id: 7, name: '星云工坊', description: '研发项目' },
          { id: 8, name: '平台商业化', description: '私有化项目' },
        ],
      })),
      http.post('/api/workspaces/8/switch', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: { accessToken: 'workspace-8-token', accessLevel: 'READ_ONLY' },
      })),
    );

    const { queryClient } = renderWithQueryClient(
      <MemoryRouter initialEntries={['/workitems/42?workspaceId=8']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/workitems/:id" element={<><LocationProbe /><div>工作项详情页</div></>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    queryClient.setQueryData(['workitems', { page: 1 }], { list: [{ id: 101 }] });
    queryClient.setQueryData(['workitem', '42'], { id: 42, title: '旧项目详情缓存' });
    queryClient.setQueryData(['workitem', '42', 'unified-timeline'], [{ id: 1 }]);

    await waitFor(() => {
      expect(useAuthStore.getState().currentWorkspace?.id).toBe(8);
    });
    expect(useAuthStore.getState().accessToken).toBe('workspace-8-token');
    expect(await screen.findByTestId('location-probe')).toHaveTextContent('/workitems/42');
    await waitFor(() => expect(screen.getByTestId('location-probe')).not.toHaveTextContent('workspaceId=8'));
    expect(queryClient.getQueryData(['workitems', { page: 1 }])).toBeUndefined();
    expect(queryClient.getQueryData(['workitem', '42'])).toBeUndefined();
    expect(queryClient.getQueryData(['workitem', '42', 'unified-timeline'])).toBeUndefined();
  });

  it('opens workspace dropdown menu from the topbar project chip', async () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    useAuthStore.getState().setAccessToken('workspace-7-token');
    useAuthStore.getState().setCurrentWorkspace({ id: 7, name: '星云工坊', description: '研发项目' }, 'READ_ONLY');
    server.use(
      http.get('/api/workspaces/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [
          { id: 7, name: '星云工坊', description: '研发项目' },
          { id: 8, name: '平台商业化', description: '私有化项目' },
        ],
      })),
    );

    renderWithQueryClient(
      <MemoryRouter initialEntries={['/workitems']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/workitems" element={<div>工作项页</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    // 设计契约 v5:项目切换入口在顶栏(可访问名=项目名)
    await userEvent.click(await screen.findByRole('button', { name: '星云工坊' }));

    expect(await screen.findByText('平台商业化')).toBeInTheDocument();
    expect(await screen.findByText('管理项目...')).toBeInTheDocument();
  });

  it('switches workspace from the topbar dropdown without navigating away', async () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    useAuthStore.getState().setAccessToken('workspace-7-token');
    useAuthStore.getState().setCurrentWorkspace({ id: 7, name: '星云工坊', description: '研发项目' }, 'READ_ONLY');
    server.use(
      http.get('/api/workspaces/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [
          { id: 7, name: '星云工坊', description: '研发项目' },
          { id: 8, name: '平台商业化', description: '私有化项目' },
        ],
      })),
      http.post('/api/workspaces/8/switch', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: { accessToken: 'workspace-8-token', accessLevel: 'READ_ONLY' },
      })),
    );

    const { queryClient } = renderWithQueryClient(
      <MemoryRouter initialEntries={['/workitems']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/workitems" element={<><LocationProbe /><div>工作项页</div></>} />
            <Route path="/workspaces" element={<div>项目选择页</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    queryClient.setQueryData(['workitems', { page: 1 }], { list: [{ id: 101 }] });
    queryClient.setQueryData(['agents', 1, 10], { list: [{ id: 1, name: 'stale-agent' }] });

    await userEvent.click(await screen.findByRole('button', { name: '星云工坊' }));
    await userEvent.click(await screen.findByText('平台商业化'));

    await waitFor(() => {
      expect(useAuthStore.getState().currentWorkspace?.id).toBe(8);
    });
    expect(useAuthStore.getState().accessToken).toBe('workspace-8-token');
    expect(await screen.findByTestId('location-probe')).toHaveTextContent('/workitems');
    expect(screen.queryByText('项目选择页')).not.toBeInTheDocument();
    expect(queryClient.getQueryData(['workitems', { page: 1 }])).toBeUndefined();
    expect(queryClient.getQueryData(['agents', 1, 10])).toBeUndefined();
  });

  it('removes the legacy "切换项目" menu item from the user dropdown', async () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    useAuthStore.getState().setAccessToken('workspace-7-token');
    useAuthStore.getState().setCurrentWorkspace({ id: 7, name: '星云工坊', description: '研发项目' }, 'READ_ONLY');

    renderWithQueryClient(
      <MemoryRouter initialEntries={['/workitems']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/workitems" element={<div>工作项页</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    await userEvent.click(screen.getByRole('button', { name: '用户菜单' }));
    expect(screen.queryByText('切换项目')).not.toBeInTheDocument();
    expect(screen.getByText('个人设置')).toBeInTheDocument();
    expect(screen.getByText('退出登录')).toBeInTheDocument();
  });

  it('clears auth state and the entire query cache on logout', async () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    useAuthStore.getState().setAccessToken('session-token');
    useAuthStore.getState().setCurrentWorkspace({ id: 7, name: '星云工坊', description: '研发项目' }, 'ADMIN');

    const { queryClient } = renderWithQueryClient(
      <MemoryRouter initialEntries={['/workitems']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/workitems" element={<div>工作项页</div>} />
          </Route>
          <Route path="/login" element={<div>登录页</div>} />
        </Routes>
      </MemoryRouter>,
    );
    queryClient.setQueryData(['workspaces', 'mine', 1], [{ id: 7, name: '星云工坊', description: '研发项目' }]);
    queryClient.setQueryData(['workitems', { page: 1, size: 20 }], { content: [{ id: 101, title: '租户数据' }] });

    await userEvent.click(screen.getByRole('button', { name: '用户菜单' }));
    await userEvent.click(screen.getByText('退出登录'));

    await waitFor(() => {
      expect(screen.getByText('登录页')).toBeInTheDocument();
    });
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().currentWorkspace).toBeNull();
    expect(queryClient.getQueryData(['workspaces', 'mine', 1])).toBeUndefined();
    expect(queryClient.getQueryData(['workitems', { page: 1, size: 20 }])).toBeUndefined();
  });

  it('truncates workspace text and hides long user names and email', () => {
    const longWorkspaceName = '超长项目名称'.repeat(12);
    const longNickname = '超长昵称'.repeat(12);
    const longEmail = `${'verylong'.repeat(8)}@example.com`;

    useAuthStore.getState().setUser({
      id: 2,
      username: 'long-user',
      nickname: longNickname,
      email: longEmail,
    });
    useAuthStore.getState().setCurrentWorkspace({ id: 8, name: longWorkspaceName, description: '长项目' }, 'READ_ONLY');

    renderWithQueryClient(
      <MemoryRouter initialEntries={['/status-templates']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/status-templates" element={<div>状态模板页</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    // 设计契约 v5:顶栏项目 chip 可访问名=项目名,长名由原生 title 提示;头像为 30px/8px 平面方chip
    const projectChip = screen.getByRole('button', { name: longWorkspaceName });
    expect(projectChip).toHaveAttribute('title', longWorkspaceName);
    expect(screen.queryByTitle(longNickname)).not.toBeInTheDocument();
    expect(screen.queryByTitle(longEmail)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '用户菜单' })).toHaveStyle({ width: '30px', height: '30px', borderRadius: '8px' });
  });

  it('opens the command palette from the topbar and navigates on click', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 7, name: '星云工坊', description: '' }, 'READ_ONLY');

    renderWithQueryClient(
      <MemoryRouter initialEntries={['/workitems']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/workitems" element={<div>工作项页</div>} />
            <Route path="/insights" element={<div>效能分析页</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    const paletteButtons = await screen.findAllByRole('button', { name: '命令面板' });
    await userEvent.click(paletteButtons[0]);
    const paletteInput = await screen.findByRole('textbox');
    await userEvent.type(paletteInput, '效能');
    const dialog = screen.getByRole('dialog');
    await userEvent.click(within(dialog).getByText('效能分析'));

    expect(await screen.findByText('效能分析页')).toBeInTheDocument();
  });
});
