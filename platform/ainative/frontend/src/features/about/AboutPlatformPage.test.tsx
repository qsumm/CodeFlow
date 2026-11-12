import { describe, expect, it } from 'vitest';
import type { ReactElement } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { AboutPlatformPage } from './AboutPlatformPage';

function renderWithQueryClient(ui: ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<MemoryRouter><QueryClientProvider client={queryClient}>{ui}</QueryClientProvider></MemoryRouter>);
}

describe('AboutPlatformPage', () => {
  it('shows the independent delivery story and removes the old onboarding content', () => {
    renderWithQueryClient(<AboutPlatformPage />);

    expect(screen.getByRole('region', { name: '关于平台' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '软件产品自动交付平台' })).toBeInTheDocument();
    expect(screen.getByText('从需求到交付的一站式 AI 交付平台')).toBeInTheDocument();
    // 「交付 9 号线」地铁图:9 个站名牌(编号 + 站名 + 一句说明),交付之后新增运维形成闭环
    expect(screen.getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      '1需求澄清AI 对话对齐验收标准', '2启动选择小队 · 派发任务', '3开发运行器编写代码',
      '4验证完成门自动校验', '5评审版本提交审核', '6部署部署到目标环境',
      '7测试自动化回归验证', '8交付报告与产物归档', '9运维持续监控与迭代',
    ]);
    expect(screen.getByText('交付 9 号线 · 一单一趟,全程有站')).toBeInTheDocument();
    expect(screen.getByText('全程 9 站 · 全程留痕,产物归档')).toBeInTheDocument();
    expect(screen.getByText('持续迭代')).toBeInTheDocument();
    expect(screen.queryByText('工作项系统集成')).not.toBeInTheDocument();
    expect(screen.queryByText('5 分钟理解工作流')).not.toBeInTheDocument();
  });

  it('shows the default deployment version placeholder', async () => {
    renderWithQueryClient(<AboutPlatformPage />);

    await waitFor(() => {
      expect(screen.getByText('x.x.x')).toBeInTheDocument();
    });
  });

  it('shows the configured deployment version from public branding', async () => {
    server.use(
      http.get('/api/platform/branding/public', () => {
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: {
            platformName: 'CodeFlow',
            logoUrl: '/logo.png',
            themeKey: 'ocean-blue',
            primaryColor: '#f97316',
            domain: 'https://community.example',
            mcpBaseUrl: 'https://community.example/api/mcp',
            recommendedRuntimeVersion: '0.2.125',
            deploymentVersion: '1.2.3',
            canManage: false,
          },
          traceId: 'trace-branding',
        });
      }),
    );

    renderWithQueryClient(<AboutPlatformPage />);

    await waitFor(() => {
      expect(screen.getByText('1.2.3')).toBeInTheDocument();
    });
  });

  it('falls back to the placeholder when branding returns an empty deployment version', async () => {
    server.use(
      http.get('/api/platform/branding/public', () => {
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: {
            platformName: 'CodeFlow',
            logoUrl: '/logo.png',
            themeKey: 'ocean-blue',
            primaryColor: '#f97316',
            domain: 'https://community.example',
            mcpBaseUrl: 'https://community.example/api/mcp',
            recommendedRuntimeVersion: '0.2.125',
            deploymentVersion: '   ',
            canManage: false,
          },
          traceId: 'trace-branding',
        });
      }),
    );

    renderWithQueryClient(<AboutPlatformPage />);

    await waitFor(() => {
      expect(screen.getByText('x.x.x')).toBeInTheDocument();
    });
  });

  it('still renders the deployment version when the branding request fails', async () => {
    server.use(
      http.get('/api/platform/branding/public', () => {
        return HttpResponse.json({ success: false, code: '500', message: 'boom' }, { status: 500 });
      }),
    );

    renderWithQueryClient(<AboutPlatformPage />);

    expect(screen.getByText('x.x.x')).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByText('软件产品自动交付平台')).toBeInTheDocument();
    });
  });
});
