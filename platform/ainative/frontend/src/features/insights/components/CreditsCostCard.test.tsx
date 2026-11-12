import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { CreditsCostCard } from './CreditsCostCard';
import { i18n } from '@/shared/i18n';
import type { CostMetrics } from '../types';

const cost: CostMetrics = {
  totalTokens: 100000,
  avgTokensPerTask: 5000,
  dailyAvg: 14000,
  trend: [1, 2, 3],
  totalCredits: 12.5,
  avgCreditsPerTask: 6.25,
  dailyAvgCredits: 0.42,
  creditsTrend: [1, 2, 3],
};

const costWithWorkers: CostMetrics = {
  ...cost,
  byWorker: [
    { agentId: 100, agentName: 'DEV', credits: 8, conversationCredits: 2, tokens: 80000 },
    { agentId: 101, agentName: 'CR', credits: 4.5, conversationCredits: 0, tokens: 20000 },
    { agentId: 102, agentName: 'IDLE', credits: 0, conversationCredits: 0, tokens: 0 },
  ],
};

async function waitPastHoverDelay() {
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 300)); });
}

async function openBreakdown() {
  fireEvent.mouseEnter(screen.getByText('总 Token'));
  return screen.findByTestId('insights-credits-breakdown');
}

beforeEach(async () => { await i18n.changeLanguage('zh-CN'); });
afterEach(async () => { cleanup(); await i18n.changeLanguage('zh-CN'); });

describe('CreditsCostCard', () => {
  it('shows Token as the three main KPIs', () => {
    render(<CreditsCostCard cost={cost} />);

    expect(screen.getByText('总 Token')).toBeInTheDocument();
    expect(screen.getByText('100,000')).toBeInTheDocument();
    expect(screen.getByText('5,000')).toBeInTheDocument();
    expect(screen.getByText('14,000')).toBeInTheDocument();
    // credits 退居后台，不再作为主位展示
    expect(screen.queryByText('总 Credits')).not.toBeInTheDocument();
    expect(screen.queryByText('12.5')).not.toBeInTheDocument();
  });

  it('shows the per-worker tokens and the range total on hover', async () => {
    render(<CreditsCostCard cost={costWithWorkers} />);

    const breakdown = await openBreakdown();

    expect(breakdown).toHaveTextContent('按AI 成员');
    expect(breakdown).toHaveTextContent('DEV80,000');
    expect(breakdown).toHaveTextContent('CR20,000');
    expect(breakdown).not.toHaveTextContent('IDLE');
    expect(breakdown).toHaveTextContent('总计100,000');
    // credits 副行（会话占比）已随 token 主维度下线
    expect(breakdown).not.toHaveTextContent('含对话');
  });

  it('keeps no conversation sub-line even when the worker reports conversation credits', async () => {
    render(<CreditsCostCard cost={{ ...cost, byWorker: [{ agentId: 101, agentName: 'CR', credits: 4.5, conversationCredits: 2, tokens: 20000 }] }} />);

    const breakdown = await openBreakdown();

    expect(breakdown).toHaveTextContent('CR20,000');
    expect(breakdown).not.toHaveTextContent('含对话');
  });

  it('does not attach hover detail for legacy payloads without byWorker', async () => {
    render(<CreditsCostCard cost={cost} />);

    fireEvent.mouseEnter(screen.getByText('总 Token'));
    await waitPastHoverDelay();

    expect(screen.queryByTestId('insights-credits-breakdown')).not.toBeInTheDocument();
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });

  it('does not attach hover detail when every worker row is non-positive', async () => {
    render(<CreditsCostCard cost={{ ...cost, byWorker: [{ agentId: 102, agentName: 'IDLE', credits: 0, tokens: 0 }] }} />);

    fireEvent.mouseEnter(screen.getByText('总 Token'));
    await waitPastHoverDelay();

    expect(screen.queryByTestId('insights-credits-breakdown')).not.toBeInTheDocument();
  });

  it('falls back to the agent id and then to an unknown worker label', async () => {
    render(<CreditsCostCard cost={{ ...cost, byWorker: [
      { agentId: 100, agentName: null, credits: 1, tokens: 100 },
      { agentId: null, agentName: '   ', credits: 1, tokens: 100 },
    ] }} />);

    const breakdown = await openBreakdown();

    expect(breakdown).toHaveTextContent('agent-100');
    expect(breakdown).toHaveTextContent('未知AI 成员');
  });

  it('treats missing tokens on a worker row as zero for the hover filter', async () => {
    render(<CreditsCostCard cost={{ ...cost, byWorker: [{ agentId: 103, agentName: 'LEGACY', credits: 3 }] }} />);

    fireEvent.mouseEnter(screen.getByText('总 Token'));
    await waitPastHoverDelay();

    expect(screen.queryByTestId('insights-credits-breakdown')).not.toBeInTheDocument();
  });

  it('keeps the breakdown while switching languages', async () => {
    render(<CreditsCostCard cost={costWithWorkers} variant="panel" />);
    const breakdown = await openBreakdown();

    await act(async () => { await i18n.changeLanguage('en-US'); });

    expect(screen.getByTestId('insights-credits-breakdown')).toBe(breakdown);
    expect(breakdown).toHaveTextContent('By AI member');
    expect(breakdown).toHaveTextContent('Total100,000');
    expect(breakdown).not.toHaveTextContent('tokens from conversations');
  });
});
