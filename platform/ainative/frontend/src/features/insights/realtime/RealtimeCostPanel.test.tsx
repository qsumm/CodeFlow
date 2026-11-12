import { CreditsCostCard } from '../components/CreditsCostCard';
import { i18n } from '@/shared/i18n';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { act, cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RealtimeCostPanel, { type RealtimeCostPanelProps } from './RealtimeCostPanel';
import type { CostComposition, CostKpi } from './api';
import type { InsightWorker } from '../types';

// antd Tooltip 延迟出现，负向断言必须等过该延迟，否则回归会被漏过。
async function waitPastTooltipDelay() {
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 300)); });
}

const cost: CostKpi = { range: 30, agentId: 'all', total: 82.61, perTask: 12.5, perDay: 2.75, tokens: 82610 };

// 新后端契约：composition 不再返回 credits 合计，主维度为 tokens；share 已按 tokens 算好（百分数刻度）。
const composition: CostComposition = {
  range: 30,
  scope: 'ALL_AGENTS',
  tokens: 82610,
  sources: [
    { key: 'WORKITEM_EXECUTION', name: '工作项执行', credits: 76, tokens: 76000, share: 92 },
    { key: 'REQUIREMENT_CLARIFY', name: '需求澄清', credits: 6.61, tokens: 6610, share: 8 },
  ],
};

const workers: InsightWorker[] = [
  { id: 12, name: '验收AI 成员' },
  { id: 13, name: '备用AI 成员' },
];

function renderPanel(overrides: Partial<RealtimeCostPanelProps> = {}) {
  const props: RealtimeCostPanelProps = {
    workerId: undefined,
    onWorkerChange: vi.fn(),
    timeRange: '30d',
    onTimeRangeChange: vi.fn(),
    workers,
    cost,
    composition,
    isLoading: false,
    isError: false,
    error: null,
    ...overrides,
  };
  return { ...render(<RealtimeCostPanel {...props} />), props };
}

function costRegion() {
  return within(screen.getByRole('region', { name: '成本分析' }));
}

function compositionRegion() {
  return within(screen.getByRole('region', { name: 'Token 来源构成' }));
}

// 成本分析面板内含统计块与来源构成两块，金额断言必须收窄到统计卡，避免与构成合计重名。
function statsRegion() {
  return within(costRegion().getByText('总 Token').closest('.cost-card') as HTMLElement);
}

describe('RealtimeCostPanel', () => {
  it('shows the scoped kpi.cost as the main KPI and no token tooltip', async () => {
    const user = userEvent.setup();
    renderPanel();

    expect(costRegion().getByText('总 Token')).toBeInTheDocument();
    expect(statsRegion().getAllByText('82.6K')).toHaveLength(2);
    expect(statsRegion().getAllByText('2.8K')).toHaveLength(1);

    await user.hover(costRegion().getByText('总 Token'));
    await waitPastTooltipDelay();

    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
    expect(screen.queryByText('总 Token:')).not.toBeInTheDocument();
    expect(screen.queryByText('100K')).not.toBeInTheDocument();
    expect(screen.queryByText('5.0K')).not.toBeInTheDocument();
    expect(screen.queryByText('14K')).not.toBeInTheDocument();
  });

  it('centers the three cost KPIs as one group without changing their order', () => {
    renderPanel();

    const group = costRegion().getByText('总 Token').closest('.metric-card-kpis');
    expect(group).not.toBeNull();
    expect(group).toHaveClass('metric-card-kpis', 'is-centered', 'cost-kpis');
    const card = group!.parentElement;
    expect(card).toHaveClass('cost-card');
    const items = Array.from(card!.querySelectorAll('.cost-kpi'));
    expect(items).toHaveLength(3);
    expect(items.map((item) => item.querySelector('.cost-kpi-label')?.textContent)).toEqual(['总 Token', '均/任务', '日均']);
    expect(items.map((item) => item.querySelector('.cost-kpi-value')?.textContent)).toEqual(['82.6K', '82.6K', '2.8K']);
  });

  it('drops the duplicate cost title and sparkline from the cost panel card', () => {
    renderPanel();

    const card = costRegion().getByText('总 Token').closest('.cost-card');
    expect(card).not.toBeNull();
    expect(within(card as HTMLElement).queryByText('成本')).not.toBeInTheDocument();
    expect(card!.querySelector('svg')).toBeNull();
    expect(costRegion().getByRole('heading', { name: '成本分析' })).toBeInTheDocument();
  });

  it('renders zero token usage without NaN when there is no usage data', () => {
    renderPanel({
      cost: { range: 30, agentId: 'all', total: 0, perTask: 0, perDay: 0 },
      composition: { range: 30, scope: 'ALL_AGENTS', tokens: 0, sources: [] },
    });

    expect(statsRegion().getAllByText('0')).toHaveLength(3);
    expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
    expect(compositionRegion().getByText('所选范围暂无 Token 消耗')).toBeInTheDocument();
  });

  it('no longer renders the scope note under the panel header', () => {
    renderPanel();

    expect(screen.queryByText('统计口径：')).not.toBeInTheDocument();
    expect(screen.queryByText('所选AI 成员 × 日期范围')).not.toBeInTheDocument();
    expect(screen.queryByText('该组筛选仅作用于本面板的成本数据，不影响下方实时指标')).not.toBeInTheDocument();
    expect(document.querySelector('.insight-panel--cost .insight-panel-note')).toBeNull();
  });

  it('keeps the filters usable and shows only a local loading indicator while loading', () => {
    renderPanel({ cost: undefined, composition: null, isLoading: true });

    expect(screen.queryByText('总 Token')).not.toBeInTheDocument();
    expect(costRegion().getByRole('combobox', { name: '成本AI 成员' })).toBeInTheDocument();
    expect(document.querySelector('.ant-spin')).not.toBeNull();
  });

  it('shows a local failure hint when the cost query fails', () => {
    renderPanel({ cost: undefined, composition: null, isError: true, error: new Error('realtime 聚合失败') });

    expect(costRegion().getByText('Token 成本加载失败')).toBeInTheDocument();
    expect(costRegion().getByText('realtime 聚合失败')).toBeInTheDocument();
    expect(screen.queryByText('总 Token')).not.toBeInTheDocument();
  });

  it('falls back to a generic hint when the failure carries no message', () => {
    renderPanel({ cost: undefined, composition: null, isError: true, error: null });

    expect(costRegion().getByText('Token 成本加载失败')).toBeInTheDocument();
    expect(costRegion().getByText('请稍后重试')).toBeInTheDocument();
  });

  it('keeps the last cost data visible when a later refresh fails', () => {
    renderPanel({ isError: true, error: new Error('realtime 聚合失败') });

    expect(costRegion().getByText('总 Token')).toBeInTheDocument();
    expect(costRegion().getByText('Token 成本加载失败')).toBeInTheDocument();
  });

  it('forwards worker and time range changes to the owner', async () => {
    const user = userEvent.setup();
    const { props } = renderPanel();

    await user.click(screen.getByRole('combobox', { name: '成本AI 成员' }));
    const workerOptions = await screen.findAllByText('验收AI 成员');
    await user.click(workerOptions[workerOptions.length - 1]);
    expect(props.onWorkerChange).toHaveBeenCalledWith(12);

    await user.click(screen.getByText('近7天'));
    expect(props.onTimeRangeChange).toHaveBeenCalledWith('7d');
  });

  it('defaults to the all-agent scope with the first dropdown option and no clear control', async () => {
    const user = userEvent.setup();
    renderPanel();

    expect(costRegion().getByText('全部AI 成员')).toBeInTheDocument();
    expect(screen.getByTestId('cost-composition-chip')).toHaveTextContent('全体AI 成员 · 近30天');
    expect(costRegion().queryByRole('button', { name: '清除AI 成员筛选' })).not.toBeInTheDocument();

    await user.click(costRegion().getByRole('combobox', { name: '成本AI 成员' }));
    const options = await screen.findAllByRole('option');
    expect(options[0]).toHaveAttribute('aria-label', '全部AI 成员');
    expect(options[0]).toHaveAttribute('aria-selected', 'true');
  });

  it('clears a specific agent from the scope chip back to the all-agent view', async () => {
    const user = userEvent.setup();
    const { props } = renderPanel({ workerId: 12, timeRange: '7d' });

    expect(costRegion().getByText('验收AI 成员 · 近7天')).toBeInTheDocument();
    const clear = costRegion().getByRole('button', { name: '清除AI 成员筛选' });
    expect(clear).toHaveAttribute('title', '清除筛选，回到全部AI 成员');
    await user.click(clear);

    expect(props.onWorkerChange).toHaveBeenCalledWith(undefined);
  });

  it('returns to the all-agent scope from the dropdown default row', async () => {
    const user = userEvent.setup();
    const { props } = renderPanel({ workerId: 12 });

    await user.click(costRegion().getByRole('combobox', { name: '成本AI 成员' }));
    const options = await screen.findAllByRole('option');
    expect(options[0]).toHaveAttribute('aria-label', '全部AI 成员');
    const allAgentLabels = await screen.findAllByText('全部AI 成员');
    await user.click(allAgentLabels[allAgentLabels.length - 1]);

    expect(props.onWorkerChange).toHaveBeenCalledWith(undefined);
  });

  it('falls back to the stored worker id when the worker list has no matching name', () => {
    renderPanel({ workerId: 99, workers: [] });

    expect(costRegion().getByText('99 · 近30天')).toBeInTheDocument();
  });

  it('renders the composition bar and legend with absolute tokens and shares', () => {
    renderPanel();

    expect(compositionRegion().getByRole('heading', { name: 'Token 来源构成' })).toBeInTheDocument();
    const bar = screen.getByTestId('cost-composition-bar');
    const segments = Array.from(bar.querySelectorAll('i'));
    expect(segments.map(segment => segment.getAttribute('data-source'))).toEqual(['WORKITEM_EXECUTION', 'REQUIREMENT_CLARIFY']);
    expect(segments.map(segment => (segment as HTMLElement).style.width)).toEqual(['92%', '8%']);
    expect(segments[0]).toHaveAttribute('title', '工作项执行: 76K tokens · 92.0%');

    const rows = within(compositionRegion().getByRole('list', { name: 'Token 来源图例' })).getAllByRole('listitem');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('工作项执行');
    expect(rows[0]).toHaveTextContent('76K92.0%');
    expect(rows[1]).toHaveTextContent('需求澄清');
    expect(rows[1]).toHaveTextContent('6.6K8.0%');
    expect(compositionRegion().getByText('82.6K')).toBeInTheDocument();
  });

  it('shows the dashed all-agent chip when no worker is selected', () => {
    renderPanel();

    const chip = screen.getByTestId('cost-composition-chip');
    expect(chip).toHaveTextContent('全体AI 成员 · 近30天');
    expect(chip).not.toHaveClass('is-locked');
    expect(chip.querySelector('.anticon-lock')).toBeNull();
  });

  it('locks the composition chip when a specific agent is selected while keeping the all-agent data', async () => {
    const user = userEvent.setup();
    renderPanel({ workerId: 12 });

    const chip = screen.getByTestId('cost-composition-chip');
    expect(chip).toHaveClass('is-locked');
    expect(chip).toHaveTextContent('全部AI 成员口径 · 不随上方员工筛选联动');
    expect(chip.querySelector('.anticon-lock')).not.toBeNull();
    // 构成条数据仍是全员口径，不随上方员工筛选变化
    expect(compositionRegion().getByRole('list', { name: 'Token 来源图例' })).toHaveTextContent('工作项执行');

    await user.hover(chip);
    expect(await screen.findByRole('tooltip')).toHaveTextContent('来源构成不区分AI 成员，仅随日期范围变化');
  });

  it('omits the composition strip entirely for legacy payloads without costComposition', () => {
    renderPanel({ composition: null });

    expect(costRegion().getByText('总 Token')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Token 来源构成' })).not.toBeInTheDocument();
    expect(screen.queryByTestId('cost-composition-bar')).not.toBeInTheDocument();
  });

  it('keeps the composition data unchanged and flashes only the stats when the agent changes', () => {
    const { rerender, props } = renderPanel();
    const scopedCost = { range: 30, agentId: '12', total: 40.2, perTask: 8.04, perDay: 1.34, tokens: 40200 };

    rerender(<RealtimeCostPanel {...props} workerId={12} cost={scopedCost} composition={composition} />);

    const statsValue = costRegion().getAllByText('40.2K')[0].closest('.insight-flash');
    expect(statsValue).toHaveClass('is-flash');
    const compositionValue = compositionRegion().getByText('76K').closest('.insight-flash');
    expect(compositionValue).not.toHaveClass('is-flash');
    expect(compositionRegion().getByText('6.6K')).toBeInTheDocument();
  });

  it('flashes both the stats and the composition when the date range changes', () => {
    const { rerender, props } = renderPanel();
    const nextComposition: CostComposition = {
      range: 7,
      scope: 'ALL_AGENTS',
      tokens: 20000,
      sources: [{ key: 'WORKITEM_EXECUTION', name: '工作项执行', credits: 20, tokens: 20000, share: 100 }],
    };

    rerender(<RealtimeCostPanel
      {...props}
      timeRange="7d"
      cost={{ range: 7, agentId: 'all', total: 20, perTask: 5, perDay: 2.86, tokens: 20000 }}
      composition={nextComposition}
    />);

    expect(statsRegion().getAllByText('20K')[0].closest('.insight-flash')).toHaveClass('is-flash');
    expect(compositionRegion().getAllByText('100.0%')[0].closest('.insight-flash')).toHaveClass('is-flash');
    const chip = screen.getByTestId('cost-composition-chip');
    expect(chip).toHaveTextContent('全体AI 成员 · 近7天');
    expect(chip).not.toHaveClass('is-locked');
  });

  it('falls back to the server display name for an unknown source key', () => {
    renderPanel({
      composition: {
        ...composition,
        sources: [{ key: 'CUSTOM_SOURCE', name: '自定义来源', credits: 3, tokens: 3000, share: 100 }],
      },
    });

    const rows = within(compositionRegion().getByRole('list', { name: 'Token 来源图例' })).getAllByRole('listitem');
    expect(rows[0]).toHaveTextContent('自定义来源');
  });
});

beforeEach(async () => {
  localStorage.clear();
  await i18n.changeLanguage('zh-CN');
});
afterEach(async () => {
  cleanup();
  await i18n.changeLanguage('zh-CN');
});

it('switches the independently cached cost panel without token detail while preserving filter codes', async () => {
  const onWorkerChange = vi.fn();
  const onTimeRangeChange = vi.fn();
  const router = createMemoryRouter([{
    path: '/',
    element: <RealtimeCostPanel
      workerId={12}
      onWorkerChange={onWorkerChange}
      timeRange="7d"
      onTimeRangeChange={onTimeRangeChange}
      workers={workers}
      cost={{ range: 7, agentId: '12', total: 10, perTask: 2, perDay: 1.43 }}
      composition={{ ...composition, range: 7 }}
      isLoading={false}
      isError={true}
      error={new Error('<b>原始错误</b>')}
    />,
  }]);
  render(<RouterProvider router={router} />);
  const select = screen.getByRole('combobox', { name: '成本AI 成员' });
  await userEvent.hover(screen.getByText('总 Token'));
  await waitPastTooltipDelay();
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByRole('combobox', { name: 'Cost Agent' })).toBe(select);
  expect(screen.getByText('验收AI 成员')).toBeInTheDocument();
  expect(screen.getByText('Failed to load Token cost')).toBeInTheDocument();
  expect(screen.getByText('<b>原始错误</b>')).toBeInTheDocument();
  expect(screen.getByText('Total Tokens')).toBeInTheDocument();
  expect(screen.getByText('Per task')).toBeInTheDocument();
  expect(screen.getByText('Daily average')).toBeInTheDocument();
  expect(screen.queryByText('Total tokens:')).not.toBeInTheDocument();
  expect(screen.getByRole('radio', { name: 'Last 7 days' })).toBeChecked();
  expect(screen.getByTestId('cost-composition-chip')).toHaveTextContent('All agents scope · unaffected by the agent filter above');
  expect(screen.getByText('Task execution')).toBeInTheDocument();
  await userEvent.click(screen.getByText('Last 90 days'));
  expect(onTimeRangeChange).toHaveBeenCalledWith('90d');
  expect(onWorkerChange).not.toHaveBeenCalled();
});

it('updates a directly cached shared Token card across languages without detail popover', async () => {
  const router = createMemoryRouter([{ path: '/', element: <CreditsCostCard cost={{
    totalTokens: 100000,
    avgTokensPerTask: 5000,
    dailyAvg: 14000,
    trend: [10, 12, 11, 13, 14, 12, 15],
    totalCredits: 82.61,
    avgCreditsPerTask: 12.5,
    dailyAvgCredits: 2.75,
    creditsTrend: [1.2, 2.5, 3.1, 4, 5.5, 6.2, 7.1],
  }} /> }]);
  render(<RouterProvider router={router} />);
  await userEvent.hover(screen.getByText('总 Token'));
  await waitPastTooltipDelay();
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  expect(screen.queryByTestId('insights-credits-breakdown')).not.toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Total Tokens')).toBeInTheDocument();
  expect(screen.getByText('100,000')).toBeInTheDocument();
  expect(screen.queryByText('Total Credits')).not.toBeInTheDocument();
  expect(screen.queryByRole('img', { name: 'dollar' })).not.toBeInTheDocument();
});

it('updates local loading and generic error text in both languages', async () => {
  const { rerender, props } = renderPanel({ cost: undefined, composition: null, isLoading: true });
  expect(screen.getByText('加载 Token 成本…')).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Loading Token cost…')).toBeInTheDocument();
  rerender(<RealtimeCostPanel {...props} isLoading={false} isError error={null} />);
  expect(screen.getByText('Failed to load Token cost')).toBeInTheDocument();
  expect(screen.getByText('Try again later')).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(screen.getByText('请稍后重试')).toBeInTheDocument();
});
