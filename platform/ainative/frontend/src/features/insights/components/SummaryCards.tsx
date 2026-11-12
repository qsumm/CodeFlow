import { ApartmentOutlined, AlertOutlined, LineChartOutlined } from '@ant-design/icons';
import type { ReactNode } from 'react';
import { SurfaceCard } from '@/shared/ui/PageShell';
import type { InsightCard, Severity } from '../types';

const SEVERITY_STYLES: Record<Severity, { bg: string; border: string; color: string }> = {
  critical: { bg: 'color-mix(in srgb, var(--cf-error) 10%, var(--cf-panel))', border: 'color-mix(in srgb, var(--cf-error) 30%, var(--cf-border))', color: 'var(--cf-error)' },
  warning: { bg: 'color-mix(in srgb, var(--cf-warning) 10%, var(--cf-panel))', border: 'color-mix(in srgb, var(--cf-warning) 30%, var(--cf-border))', color: 'var(--cf-warning)' },
  info: { bg: 'color-mix(in srgb, var(--cf-info) 10%, var(--cf-panel))', border: 'color-mix(in srgb, var(--cf-info) 30%, var(--cf-border))', color: 'var(--cf-info)' },
  good: { bg: 'color-mix(in srgb, var(--cf-success) 10%, var(--cf-panel))', border: 'color-mix(in srgb, var(--cf-success) 30%, var(--cf-border))', color: 'var(--cf-success)' },
};

const ICONS = [<ApartmentOutlined key="0" />, <AlertOutlined key="1" />, <LineChartOutlined key="2" />];

interface SummaryCardsProps {
  cards: InsightCard[];
  dateLabel: string;
}

export function SummaryCards({ cards, dateLabel }: SummaryCardsProps) {
  return (
    <div className="cfds-stat-row cfds-stat-row--quad">
      {cards.map((card, index) => {
        const style = SEVERITY_STYLES[card.severity];
        const icon = ICONS[index] as ReactNode;
        return (
          <SurfaceCard
            key={index}
            title={(
              <span className="insight-panel-heading">
                <span aria-hidden="true" className="insight-panel-heading__glyph" style={{ background: style.bg, color: style.color }}>
                  {icon}
                </span>
                {card.title}
              </span>
            )}
            actions={<span className="insight-panel-heading__meta">{dateLabel}</span>}
          >
            <div className="summary-card-value" style={{ color: style.color }}>{card.value}</div>
            <div className="summary-card-body">{card.body}</div>
          </SurfaceCard>
        );
      })}
    </div>
  );
}
