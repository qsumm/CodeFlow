import { useTranslation } from 'react-i18next';
import { LineChartOutlined } from '@ant-design/icons';
import { Tag } from 'antd';
import { SurfaceCard } from '@/shared/ui/PageShell';
import type { InsightCard, Severity } from '../types';

const SEVERITY_STYLES: Record<Severity, { bg: string; border: string; color: string }> = {
  critical: { bg: 'color-mix(in srgb, var(--cf-error) 10%, var(--cf-panel))', border: 'color-mix(in srgb, var(--cf-error) 30%, var(--cf-border))', color: 'var(--cf-error)' },
  warning: { bg: 'color-mix(in srgb, var(--cf-warning) 10%, var(--cf-panel))', border: 'color-mix(in srgb, var(--cf-warning) 30%, var(--cf-border))', color: 'var(--cf-warning)' },
  info: { bg: 'color-mix(in srgb, var(--cf-info) 10%, var(--cf-panel))', border: 'color-mix(in srgb, var(--cf-info) 30%, var(--cf-border))', color: 'var(--cf-info)' },
  good: { bg: 'color-mix(in srgb, var(--cf-success) 10%, var(--cf-panel))', border: 'color-mix(in srgb, var(--cf-success) 30%, var(--cf-border))', color: 'var(--cf-success)' },
};

interface KeyInsightsProps {
  cards: InsightCard[];
  workerLabel: string;
}

export function KeyInsights({ cards, workerLabel }: KeyInsightsProps) {
  const { t } = useTranslation('insights');
  return (
    <SurfaceCard
      title={<span className="insight-panel-heading"><LineChartOutlined aria-hidden="true" className="insight-panel-heading__icon" style={{ color: 'var(--cf-info)' }} />{t('panels.keyInsights')}</span>}
      actions={<Tag style={{ borderRadius: 4 }}>{workerLabel}</Tag>}
    >
      <div style={{ display: 'grid', gap: 10 }}>
        {cards.map((card, index) => {
          const style = SEVERITY_STYLES[card.severity];
          return (
            <div key={index} style={{ border: `1px solid ${style.border}`, background: style.bg, borderRadius: 8, padding: '12px 14px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 5 }}>
                <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--cf-text)' }}>{card.title}</span>
                <span style={{ marginLeft: 'auto', fontSize: 13, fontWeight: 700, color: style.color }}>{card.value}</span>
              </div>
              <div style={{ fontSize: 12, lineHeight: '18px', color: 'var(--cf-muted)' }}>{card.body}</div>
            </div>
          );
        })}
      </div>
    </SurfaceCard>
  );
}
