import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import { AuditLogPage } from './AuditLogPage';
import type { AuditLog } from './api';

const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });

const record: AuditLog = {
  id: 1,
  module: 'WORKITEM',
  action: 'DELETE',
  riskLevel: 'high',
  actorId: 7,
  actorType: 'HUMAN',
  actorName: '张三',
  targetType: 'workitem',
  targetId: 42,
  detail: null,
  detailJson: '{"triggerType":"EVENT","triggerSource":"USER_CLICK","eventType":"workitem.delete","message":"删除工作项 ZENTAO#123"}',
  gmtCreate: '2026-07-01T10:11:12',
};

const language = (locale: string) => act(async () => { await i18n.changeLanguage(locale); });

let requests: string[] = [];

function stubAudit(rows: AuditLog[], total: number) {
  server.use(
    http.get('/api/audit-logs', ({ request }) => {
      requests.push(request.url);
      return ok(rows);
    }),
    http.get('/api/audit-logs/count', ({ request }) => {
      requests.push(request.url);
      return ok(total);
    }),
  );
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter><AuditLogPage /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AuditLogPage v2 locale boundary', () => {
  beforeEach(async () => {
    requests = [];
    window.localStorage.clear();
    await i18n.changeLanguage('zh-CN');
  });

  afterEach(async () => {
    await i18n.changeLanguage('zh-CN');
  });

  it('translates labels while keeping filters, drawer JSON and requests stable', async () => {
    stubAudit([record], 23);
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText('命中 23 条')).toBeInTheDocument();
    expect(screen.getByText('操作日志')).toBeInTheDocument();
    expect(screen.getByText('谁在什么时候动了什么,全部留痕')).toBeInTheDocument();
    for (const name of ['时间', '谁', '模块 / 动作', '风险', '对象', '触发 / 事件', '摘要']) {
      expect(screen.getAllByText(name).length).toBeGreaterThan(0);
    }
    expect(screen.getByText('共 23 条')).toBeInTheDocument();
    expect(screen.getByText('工作项')).toBeInTheDocument();
    expect(screen.getByText('高危')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('搜详情关键词')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('起始日期')).toBeInTheDocument();
    expect(screen.getByText('全部模块')).toBeInTheDocument();

    await user.click(screen.getByText('张三'));
    const dialog = await screen.findByRole('dialog');
    const full = within(dialog).getByRole('region', { name: '完整留痕' });
    const json = full.querySelector('code')?.textContent;
    expect(json).toBe(JSON.stringify(record, null, 2));
    const frozen = [...requests];

    await language('en-US');

    expect(screen.getByText('Audit logs')).toBeInTheDocument();
    expect(screen.getByText('Who changed what, and when — all on record.')).toBeInTheDocument();
    for (const name of ['Time', 'Who', 'Module / action', 'Risk', 'Target', 'Trigger / event', 'Summary']) {
      expect(screen.getAllByText(name).length).toBeGreaterThan(0);
    }
    expect(screen.getByText('23 matched')).toBeInTheDocument();
    expect(screen.getByText('23 records')).toBeInTheDocument();
    expect(screen.getByText('Work item')).toBeInTheDocument();
    expect(screen.getByText('High')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Risk level rubric' })).toBeInTheDocument();
    expect(screen.getByPlaceholderText('Search detail keywords')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('From')).toBeInTheDocument();
    expect(screen.getByText('All modules')).toBeInTheDocument();
    expect(screen.getByText('Audit log details')).toBeInTheDocument();
    expect(within(dialog).getByRole('region', { name: 'Full record' }).querySelector('code')?.textContent).toBe(json);
    expect(requests).toEqual(frozen);

    await user.click(screen.getByRole('combobox', { name: 'Risk' }));
    await user.click(await screen.findByText('High', { selector: '.ant-select-item-option-content' }));
    await waitFor(() => expect(requests.some((url) => new URL(url).searchParams.get('riskLevel') === 'high')).toBe(true));
    expect(within(dialog).getByRole('region', { name: 'Full record' }).querySelector('code')?.textContent).toBe(json);

    await language('zh-CN');
    expect(screen.getByText('命中 23 条')).toBeInTheDocument();
    expect(within(dialog).getByRole('region', { name: '完整留痕' }).querySelector('code')?.textContent).toBe(json);
  });

  it.each([0, 1, 2])('renders the count chip plural for %i records in both languages', async (count) => {
    stubAudit(count === 0 ? [] : [record], count);
    renderPage();

    expect(await screen.findByText(`命中 ${count} 条`)).toBeInTheDocument();
    await language('en-US');
    expect(screen.getByText(count === 1 ? '1 record in current filter' : `${count} matched`)).toBeInTheDocument();
  });

  it('translates the load failure alert', async () => {
    server.use(
      http.get('/api/audit-logs', () => HttpResponse.json({ success: false, code: '500', message: 'boom' }, { status: 500 })),
      http.get('/api/audit-logs/count', () => ok(0)),
    );
    renderPage();

    expect(await screen.findByText('操作日志没读到,稍后再试')).toBeInTheDocument();
    await language('en-US');
    expect(screen.getByText('Could not load the audit trail — retry later')).toBeInTheDocument();
  });
});
