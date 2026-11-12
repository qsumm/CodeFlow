import { useTranslation } from 'react-i18next';
import { ApartmentOutlined } from '@ant-design/icons';
import { Tag } from 'antd';
import { SurfaceCard } from '@/shared/ui/PageShell';
import type { WorkerFinding, RiskLevel } from '../types';

const RISK_STYLES: Record<RiskLevel, { bg: string; border: string; color: string }> = {
  high: { bg: 'color-mix(in srgb, var(--cf-error) 10%, var(--cf-panel))', border: 'color-mix(in srgb, var(--cf-error) 30%, var(--cf-border))', color: 'var(--cf-error)' },
  medium: { bg: 'color-mix(in srgb, var(--cf-warning) 10%, var(--cf-panel))', border: 'color-mix(in srgb, var(--cf-warning) 30%, var(--cf-border))', color: 'var(--cf-warning)' },
  low: { bg: 'var(--cf-raised)', border: 'var(--cf-text)', color: 'var(--cf-muted)' },
};

interface SdlcLinkIssuesProps {
  findings: WorkerFinding[];
}

export function SdlcLinkIssues({ findings }: SdlcLinkIssuesProps) {
  const { t } = useTranslation('insights');
  return (
    <SurfaceCard
      title={<span className="insight-panel-heading"><ApartmentOutlined aria-hidden="true" className="insight-panel-heading__icon" style={{ color: 'var(--cf-warning)' }} />{t('panels.sdlcIssues')}</span>}
    >
      <div style={{ display: 'grid', gap: 9 }}>
        {findings.map((item) => {
          const style = RISK_STYLES[item.severity];
          return (
            <div key={`${item.worker}-${item.link}`} style={{ display: 'grid', gridTemplateColumns: '1fr auto', gap: 8, borderBottom: '1px solid var(--cf-border)', paddingBottom: 9 }}>
              <div style={{ minWidth: 0 }}>
                <div style={{ display: 'flex', gap: 6, alignItems: 'center', marginBottom: 4 }}>
                  <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--cf-text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{item.worker}</span>
                  <Tag style={{ margin: 0, borderRadius: 4, color: style.color, borderColor: style.border, background: style.bg }}>{item.link}</Tag>
                </div>
                <div style={{ fontSize: 12, color: 'var(--cf-muted)', lineHeight: '18px' }}>{t('panels.issueImpact', { issue: item.issue, impact: item.impact })}</div>
              </div>
              <span style={{ alignSelf: 'start', fontSize: 11, color: style.color, fontWeight: 700 }}>{item.signal}</span>
            </div>
          );
        })}
      </div>
    </SurfaceCard>
  );
}
