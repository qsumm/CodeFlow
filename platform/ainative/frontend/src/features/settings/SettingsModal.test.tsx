import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { i18n } from '@/shared/i18n';
import { server } from '@/test/mocks/server';
import { SettingsModal } from './SettingsModal';

function apiOk<T>(data: T) {
  return { success: true, code: '0', message: '', traceId: null, data };
}

// 弹窗一挂载就拉 AI 配置与用量,切到系统段还会拉 SYSTEM;不桩会触发 401 跳登录。
beforeEach(() => {
  server.use(
    http.get('/api/ai-usage/quota', () => HttpResponse.json(apiOk({ periodType: 'MONTH', maxCalls: 20000, maxTokens: 10000000, concurrencyLimit: 8 }))),
    http.get('/api/ai-usage', () => HttpResponse.json(apiOk([]))),
    http.get('/api/settings/AI', () => HttpResponse.json(apiOk([
      { key: 'default_model', valueJson: 'qwen-max', secret: false },
    ]))),
    http.get('/api/settings/SYSTEM', () => HttpResponse.json(apiOk([
      { key: 'default_sdlc_id', valueJson: '334208147726012416', secret: false },
    ]))),
  );
});

afterEach(async () => { cleanup(); await i18n.changeLanguage('zh-CN'); });

function renderModal(onClose = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><SettingsModal open onClose={onClose} /></QueryClientProvider>);
  return onClose;
}

describe('SettingsModal', () => {
  it('opens on the AI section with a left category rail', async () => {
    renderModal();
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'AI 配置' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('button', { name: '通知配置' })).not.toHaveAttribute('aria-current');
    await waitFor(() => expect(screen.getByText('默认模型')).toBeInTheDocument());
  });

  it('switches sections without unmounting the dialog', async () => {
    renderModal();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: '系统配置' }));
    expect(await screen.findByText('默认流水线流程')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '系统配置' })).toHaveAttribute('aria-current', 'page');
  });

  it('closes through the dialog close control', async () => {
    const onClose = renderModal();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /关闭|Close/ }));
    expect(onClose).toHaveBeenCalled();
  });
});
