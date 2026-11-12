import { useTranslation } from 'react-i18next';
import { ThunderboltOutlined } from '@ant-design/icons';
import { Popover } from 'antd';
import { MetricCardShell } from './MetricCardShell';
import { i18n } from '@/shared/i18n';
import { formatTokens } from '@/shared/lib/tokenFormat';
import type { CostMetrics, WorkerCost } from '../types';

interface CreditsCostCardProps {
  cost: CostMetrics;
  variant?: 'card' | 'panel';
}

function workerLabel(row: WorkerCost): string {
  const name = row.agentName?.trim();
  if (name) return name;
  return row.agentId != null
    ? i18n.t('insights:cost.workerFallback', { id: row.agentId })
    : i18n.t('insights:cost.workerUnknown');
}

function TokensBreakdown({ cost, rows }: { cost: CostMetrics; rows: WorkerCost[] }) {
  return (
    <div data-testid="insights-credits-breakdown" style={{ minWidth: 220, lineHeight: '1.8' }}>
      <div style={{ fontWeight: 600 }}>{i18n.t('insights:cost.breakdownTitle')}</div>
      {rows.map((row, index) => (
        <div key={`${row.agentId ?? 'unknown'}-${index}`}>
          <span>{workerLabel(row)}</span>
          <span style={{ marginLeft: 8 }}>{formatTokens(row.tokens ?? 0)}</span>
        </div>
      ))}
      <div>
        <span style={{ fontWeight: 600 }}>{i18n.t('insights:cost.breakdownTotal')}</span>
        <span style={{ marginLeft: 8 }}>{formatTokens(cost.totalTokens)}</span>
      </div>
    </div>
  );
}

// 成本卡是全站唯一只读真实聚合的指标卡：主位只展示 Token（后端统计主维度），
// 不做 seed 缩放、估值或合成。前后端独立发布，旧版后端可能还没有对应字段：
// 缺失时按 0 / 空趋势兜底，不能让成本卡的渲染异常波及实时看板其他模块。
// panel 变体仅用于成本分析版块：保留卡片容器，去掉重复的标题行与趋势线，让三项统计成为视觉中心。
export function CreditsCostCard({ cost, variant = 'card' }: CreditsCostCardProps) {
  const { t } = useTranslation('insights');
  const kpis = [
    { label: t('cost.totalTokens'), value: formatTokens(cost.totalTokens) },
    { label: t('cost.perTask'), value: formatTokens(cost.avgTokensPerTask) },
    { label: t('cost.dailyAverage'), value: formatTokens(cost.dailyAvg) },
  ];
  // 旧后端没有 byWorker / tokens：此时不挂 hover，保持原样。
  const workers = (cost.byWorker ?? []).filter(row => row != null && (row.tokens ?? 0) > 0);

  const card = variant === 'panel' ? (
    <div className="cost-card">
      <div className="metric-card-kpis is-centered cost-kpis">
        {kpis.map((kpi, index) => (
          <div key={index} className="cost-kpi">
            <span className="cost-kpi-value">{kpi.value}</span>
            <span className="cost-kpi-label">{kpi.label}</span>
          </div>
        ))}
      </div>
    </div>
  ) : (
    <MetricCardShell
      title={t('cost.title')}
      icon={<ThunderboltOutlined aria-hidden="true" />}
      color="var(--cf-warning)"
      bg="color-mix(in srgb, var(--cf-warning) 10%, var(--cf-panel))"
      kpis={kpis}
      trend={cost.trend ?? []}
      kpiLayout="center"
    />
  );

  if (workers.length === 0) return card;
  return (
    <Popover content={<TokensBreakdown cost={cost} rows={workers} />} trigger="hover">
      <div>{card}</div>
    </Popover>
  );
}
