import { afterEach, describe, expect, it } from 'vitest';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { i18n } from '@/shared/i18n';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { NAV_SECTIONS, NAV_SETTINGS, allNavItems, navItemMatchesPath, resolveSelectedNavKey } from './Sidebar';
import { WorkbenchTree } from './workbench/WorkbenchTree';
import { useAuthStore } from '@/shared/auth/store';
import { scheduledTaskCapabilityQueryKey } from '@/features/scheduledTask/hooks';
import type { ScheduledTaskCapability } from '@/features/scheduledTask/types';

const readyCapability: ScheduledTaskCapability = {
  available: true,
  mode: 'V037_READY',
  clusterReady: true,
  reason: null,
};

afterEach(async () => { cleanup(); await act(async () => { await i18n.changeLanguage('zh-CN'); }); });

function TestWrapper({
  children,
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
  initialEntries = ['/workitems'],
}: {
  children: React.ReactNode;
  queryClient?: QueryClient;
  initialEntries?: string[];
}) {
  return (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={initialEntries}>
        {children}
      </MemoryRouter>
    </QueryClientProvider>
  );
}

it('renders translated tree labels and swaps them in place across languages', async () => {
  render(<TestWrapper><WorkbenchTree /></TestWrapper>);
  const workitems = screen.getByText('工作项');
  expect(screen.getByRole('navigation', { name: '导航树' })).toContainElement(workitems);
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Work items')).toBe(workitems);
  expect(screen.queryByText('工作项')).not.toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(screen.getByText('工作项')).toBeInTheDocument();
});

describe('导航树结构(设计契约 v5)', () => {
  it('交付分组按动线排序:工作项 → 流水线 → 项目总览 → 执行记录 → 回收站', () => {
    const delivery = NAV_SECTIONS.find((section) => section.key === 'delivery');
    expect(delivery?.items.map((item) => item.key))
      .toEqual(['/workitems', '/sdlcs', '/overview', '/executions', '/workitems/recycled']);
    expect(delivery?.items[0]?.label).toBe('工作项');
    expect(delivery?.items[1]?.label).toBe('流水线');
    expect(delivery?.items[2]?.label).toBe('项目总览');
    expect(resolveSelectedNavKey('/sdlcs')).toBe('/sdlcs');
  });

  it('分析/自动化/资产/项目 分组归属正确', () => {
    const keysOf = (key: string) => NAV_SECTIONS.find((section) => section.key === key)?.items.map((item) => item.key) ?? [];
    expect(keysOf('analysis')).toEqual(['/insights', '/audit-logs', '/evolution']);
    expect(keysOf('automation')).toEqual(['/scheduled-tasks', '/executors', '/agents']);
    expect(keysOf('assets')).toEqual(['/repos', '/memories', '/skills', '/status-templates']);
    expect(keysOf('project')).toEqual(['/integrations', '/settings/members', '/settings/backups', '/settings/environment-variables', '/settings']);
    expect(NAV_SECTIONS.map((section) => section.label)).toEqual(['交付', '分析', '自动化', '资产', '项目']);
  });

  it('沿用新术语:运行器/AI 成员/连接/操作日志/效能分析/记忆库/技能', () => {
    const labelOf = (key: string) => allNavItems().find((item) => item.key === key)?.label;
    expect(labelOf('/executors')).toBe('运行器');
    expect(labelOf('/agents')).toBe('AI 成员');
    expect(labelOf('/integrations')).toBe('连接');
    expect(labelOf('/audit-logs')).toBe('操作日志');
    expect(labelOf('/insights')).toBe('效能分析');
    expect(labelOf('/memories')).toBe('记忆库');
    expect(labelOf('/skills')).toBe('技能');
  });

  it('成员管理保持单一入口,旧路由别名归并到成员管理', () => {
    expect(allNavItems().filter((item) => item.key.startsWith('/settings/members'))).toHaveLength(1);
    expect(resolveSelectedNavKey('/settings/members-roles/roles')).toBe('/settings/members');
    expect(resolveSelectedNavKey('/settings/roles')).toBe('/settings/members');
    expect(resolveSelectedNavKey('/platform/branding')).toBe('');
    expect(resolveSelectedNavKey('/open-platform')).toBe('');
  });

  it('自进化与设置保留路由元数据但不在树中渲染', () => {
    const evolution = NAV_SECTIONS.flatMap((section) => section.items).find((item) => item.key === '/evolution');
    const settings = NAV_SECTIONS.flatMap((section) => section.items).find((item) => item.key === '/settings');
    expect(evolution?.hidden).toBe(true);
    expect(settings?.hidden).toBe(true);
    expect(resolveSelectedNavKey('/evolution')).toBe('/evolution');
    expect(resolveSelectedNavKey('/settings')).toBe('/settings');
  });

  it('嵌套与旧路由按最长前缀匹配', () => {
    expect(resolveSelectedNavKey('/agents/reviews')).toBe('/agents');
    expect(resolveSelectedNavKey('/agents/42')).toBe('/agents');
    expect(resolveSelectedNavKey('/repos/map')).toBe('/repos');
    expect(resolveSelectedNavKey('/integrations/channels')).toBe('/integrations');
    expect(resolveSelectedNavKey('/workitems/recycled')).toBe('/workitems/recycled');
    expect(navItemMatchesPath({ key: '/workitems', label: '', icon: null }, '/workitems/42')).toBe(true);
  });
});

describe('树渲染行为', () => {
  it('渲染全部可见分组与树底固定入口(文档/设置/关于)', () => {
    render(<TestWrapper><WorkbenchTree /></TestWrapper>);
    for (const label of ['交付', '分析', '自动化', '资产', '项目']) {
      expect(screen.getByText(label)).toBeInTheDocument();
    }
    expect(screen.getByText('文档中心')).toBeInTheDocument();
    expect(screen.getByText(NAV_SETTINGS.label)).toBeInTheDocument();
    expect(screen.getByText('关于平台')).toBeInTheDocument();
    expect(screen.queryByText('自进化')).toBeNull();
    expect(screen.queryByText('系统设置')).not.toBeInTheDocument();
  });

  it('标记当前路由为选中态', () => {
    render(<TestWrapper initialEntries={['/insights']}><WorkbenchTree /></TestWrapper>);
    expect(screen.getByRole('button', { name: /效能分析/ })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('button', { name: /工作项/ })).not.toHaveAttribute('aria-current');
  });

  it('徽标计数渲染在工作项入口上', () => {
    render(<TestWrapper><WorkbenchTree badges={{ '/agents': 125 }} /></TestWrapper>);
    expect(screen.getByText('125')).toBeInTheDocument();
  });

  it('项目头展示当前项目名与 id,未选择时显示占位', () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 7, name: '测试项目', description: '' }, 'READ_ONLY');
    render(<TestWrapper><WorkbenchTree /></TestWrapper>);
    expect(screen.getByText('测试项目')).toBeInTheDocument();
    expect(screen.getByText('#7')).toBeInTheDocument();
  });
});

describe('定时任务 V037 门控(沿用)', () => {
  it('能力就绪前隐藏定时任务,其余项不受影响', async () => {
    let resolveCapability: (() => void) | undefined;
    server.use(
      http.get('/api/capabilities/scheduled-task', async () => {
        await new Promise<void>((resolve) => { resolveCapability = resolve; });
        return HttpResponse.json({ success: true, code: '0', message: '', data: { available: true, mode: 'V037_READY', clusterReady: true, reason: null } });
      }),
    );

    render(<TestWrapper><WorkbenchTree /></TestWrapper>);

    expect(screen.queryByText('定时任务')).not.toBeInTheDocument();
    expect(screen.getByText('工作项')).toBeInTheDocument();
    await waitFor(() => expect(resolveCapability).toBeTypeOf('function'));
    act(() => resolveCapability?.());
    expect(await screen.findByText('定时任务')).toBeInTheDocument();
  });

  it('能力不可用时不渲染定时任务', async () => {
    server.use(
      http.get('/api/capabilities/scheduled-task', () => HttpResponse.json({ success: true, code: '0', message: '', data: { available: false, mode: 'LEGACY', clusterReady: false, reason: 'DATABASE_UPGRADE_REQUIRED' } })),
    );

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<TestWrapper queryClient={queryClient}><WorkbenchTree /></TestWrapper>);

    await waitFor(() => expect(queryClient.getQueryState(scheduledTaskCapabilityQueryKey)?.status).toBe('success'));
    expect(screen.getByText('工作项')).toBeInTheDocument();
    expect(screen.queryByText('定时任务')).not.toBeInTheDocument();
  });

  it('能力请求失败时不渲染定时任务', async () => {
    server.use(
      http.get('/api/capabilities/scheduled-task', () => HttpResponse.json({ success: false, code: '10000', message: 'failed', data: null }, { status: 500 })),
    );

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<TestWrapper queryClient={queryClient}><WorkbenchTree /></TestWrapper>);

    await waitFor(() => expect(queryClient.getQueryState(scheduledTaskCapabilityQueryKey)?.status).toBe('error'));
    expect(screen.getByText('工作项')).toBeInTheDocument();
    expect(screen.queryByText('定时任务')).not.toBeInTheDocument();
  });

  it('陈旧缓存重校验期间保持隐藏,成功后显示', async () => {
    let resolveCapability: (() => void) | undefined;
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(scheduledTaskCapabilityQueryKey, readyCapability, { updatedAt: 0 });
    server.use(
      http.get('/api/capabilities/scheduled-task', async () => {
        await new Promise<void>((resolve) => { resolveCapability = resolve; });
        return HttpResponse.json({ success: true, code: '0', message: '', data: readyCapability });
      }),
    );

    render(<TestWrapper queryClient={queryClient}><WorkbenchTree /></TestWrapper>);

    await waitFor(() => expect(queryClient.getQueryState(scheduledTaskCapabilityQueryKey)?.fetchStatus).toBe('fetching'));
    expect(screen.queryByText('定时任务')).not.toBeInTheDocument();
    act(() => resolveCapability?.());
    expect(await screen.findByText('定时任务')).toBeInTheDocument();
  });

  it('陈旧缓存重校验失败时保持隐藏', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(scheduledTaskCapabilityQueryKey, readyCapability, { updatedAt: 0 });
    server.use(http.get('/api/capabilities/scheduled-task', () => HttpResponse.error()));

    render(<TestWrapper queryClient={queryClient}><WorkbenchTree /></TestWrapper>);

    expect(screen.getByText('工作项')).toBeInTheDocument();
    expect(screen.queryByText('定时任务')).not.toBeInTheDocument();
    await waitFor(() => expect(queryClient.getQueryState(scheduledTaskCapabilityQueryKey)?.status).toBe('error'));
    expect(screen.queryByText('定时任务')).not.toBeInTheDocument();
  });
});
