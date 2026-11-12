import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, beforeEach } from 'vitest';
import { http, HttpResponse } from 'msw';

import { OnboardingPage } from './OnboardingPage';
import { server } from '@/test/mocks/server';

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/onboarding']}>
      <OnboardingPage />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  localStorage.removeItem('ainative.onboarding.done');
});

describe('OnboardingPage', () => {
  it('renders the 4-step tour with the model explanation first', async () => {
    renderPage();
    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(screen.getAllByText(/运行器/).length).toBeGreaterThan(0);
  });

  it('advances through steps via 下一步', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole('button', { name: /下一步/ }));
    expect(screen.getByRole('heading', { name: /跑起运行器/ })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /下一步/ }));
    expect(screen.getByRole('heading', { name: /预置阵容/ })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /下一步/ }));
    expect(screen.getByRole('heading', { name: /接入禅道/ })).toBeInTheDocument();
  });

  it('shows executor probe offline then online when executors report ONLINE', async () => {
    server.use(http.get('/api/executors', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null,
      data: [{ id: 10000, name: 'dev', status: 'ONLINE' }],
    })));
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole('button', { name: /下一步/ }));
    expect(await screen.findByText(/运行器已上线/)).toBeInTheDocument();
  });

  it('shows offline probe when no executors are online', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole('button', { name: /下一步/ }));
    expect(await screen.findByText(/还没有在线的运行器/)).toBeInTheDocument();
  });

  it('skip with executor online finishes immediately (no confirm)', async () => {
    server.use(http.get('/api/executors', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null,
      data: [{ id: 10000, name: 'dev', status: 'ONLINE' }],
    })));
    const user = userEvent.setup();
    renderPage();
    // 等探测变绿(第 2 步探测横幅出现),然后点顶部「跳过引导」——在线时直接完成,无确认弹窗
    await user.click(screen.getByRole('button', { name: /下一步/ }));
    expect(await screen.findByText(/运行器已上线/)).toBeInTheDocument();
    await user.click(await screen.findByText(/跳过引导/));
    await waitFor(() => expect(localStorage.getItem('ainative.onboarding.done')).toBe('1'));
  });

  it('shows platform-generated launch command when an executor exists (even offline)', async () => {
    server.use(
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 10000, name: 'dev-01', status: 'OFFLINE', launchCommandSupported: true }],
      })),
      http.post('/api/executors/10000/launch-command', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { executorId: 10000, command: 'npx -y ainative connect --ws-url ws://x --token t --executor-id 10000' },
      })),
    );
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole('button', { name: /下一步/ }));
    const cmd = await screen.findByTestId('onb-cmd', {}, { timeout: 8000 });
    expect(cmd).toBeInTheDocument();
  });

  it('skip confirmation offers a dont-show-again checkbox', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: /跳过引导/ }));
    expect((await screen.findAllByText('运行器还未上线')).length).toBeGreaterThan(0);
    const okBtn = await screen.findAllByRole('button', { name: '仍要跳过' });
    // modal 打开时有 dont-show checkbox(勾选即写入 done)
    const cb = screen.queryByRole('checkbox');
    expect(cb).not.toBeNull();
    await user.click(okBtn[okBtn.length - 1]);
    await waitFor(() => expect(localStorage.getItem('ainative.onboarding.done')).toBe('1'));
  });

  it('finish on last step marks onboarding done', async () => {
    const user = userEvent.setup();
    renderPage();
    for (let i = 0; i < 3; i += 1) {
      await user.click(screen.getByRole('button', { name: /下一步/ }));
    }
    await user.click(screen.getByTestId('onb-finish'));
    await waitFor(() => expect(localStorage.getItem('ainative.onboarding.done')).toBe('1'));
  });
});
