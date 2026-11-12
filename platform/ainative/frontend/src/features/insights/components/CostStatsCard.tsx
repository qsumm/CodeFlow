import { useTranslation } from 'react-i18next';
import { formatTokenCount } from '@/shared/lib/tokenFormat';
import FlashValue from '../realtime/FlashValue';

export interface CostStatsValues {
  total: number;
  perTask: number;
  perDay: number;
  /** Token 维度(旧后端缺失时 undefined → 0)。 */
  tokens?: number;
  range?: number;
  tasks?: number;
}

/** 成本分析面板的统计块：视觉沿用线上居中三指标卡片，仅数据换成 realtime 的 kpi.cost。 */
export function CostStatsCard({ values }: { values: CostStatsValues }) {
  const { t } = useTranslation('insights');
  const kpis = [
    { label: t('cost.totalTokens'), value: formatTokenCount(values.tokens ?? 0) },
    { label: t('cost.perTask'), value: formatTokenCount(Math.round((values.tokens ?? 0) / Math.max(1, values.tasks ?? 1))) },
    { label: t('cost.dailyAverage'), value: formatTokenCount(Math.round((values.tokens ?? 0) / Math.max(1, values.range ?? 1))) },
  ];

  return (
    <div className="cost-card">
      <div className="metric-card-kpis is-centered cost-kpis">
        {kpis.map(kpi => (
          <div key={kpi.label} className="cost-kpi">
            <span className="cost-kpi-value"><FlashValue value={kpi.value} /></span>
            <span className="cost-kpi-label">{kpi.label}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
