import { useTranslation } from 'react-i18next';
import { BulbOutlined } from '@ant-design/icons';
import { SurfaceCard } from '@/shared/ui/PageShell';

interface RecommendationsProps {
  items: string[];
}

export function Recommendations({ items }: RecommendationsProps) {
  const { t } = useTranslation('insights');
  return (
    <SurfaceCard
      title={<span className="insight-panel-heading"><BulbOutlined aria-hidden="true" className="insight-panel-heading__icon" style={{ color: 'var(--cf-success)' }} />{t('panels.recommendations')}</span>}
      actions={<span className="insight-panel-heading__meta">{t('panels.updates')}</span>}
    >
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 10 }}>
        {items.map((item) => (
          <div key={item} style={{ fontSize: 12, color: 'var(--cf-muted)', lineHeight: '18px', padding: '10px 12px', background: 'var(--cf-raised)', border: '1px solid var(--cf-border)', borderRadius: 8 }}>
            {item}
          </div>
        ))}
      </div>
    </SurfaceCard>
  );
}
