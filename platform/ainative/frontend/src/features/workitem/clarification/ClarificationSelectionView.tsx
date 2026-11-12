import type { ReactNode } from 'react';
import { Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { SquadAgentSelector } from './SquadAgentSelector';
import type { SquadAgentSelection } from './SquadAgentSelector';
import { CLARIFICATION_THEME } from './theme';

/** 未选数字人时的落地页:唯一入口是小队→成员选择器。 */
export function ClarificationSelectionView({
  fullscreen, headerPrefix, headerSuffix, selection, onSelectionChange,
}: {
  fullscreen?: boolean;
  headerPrefix?: ReactNode;
  headerSuffix?: ReactNode;
  selection: SquadAgentSelection | null;
  onSelectionChange: (selection: SquadAgentSelection | null) => void;
}) {
  const { t } = useTranslation('workitem');
  const fullscreenColumnStyle = fullscreen
    ? { width: '100%', minWidth: 0, overflowWrap: 'break-word' as const }
    : {};
  return (
      <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
        {/* 选人页头部：外层已不渲染独立头行，「返回/全屏」插槽在这里接住，
            与对话页一致；否则进入选人页后没有任何返回入口。 */}
        <div
          data-testid="clarification-panel-header"
          style={{
            padding: fullscreen ? '12px 24px' : '10px 14px',
            borderBottom: `1px solid ${CLARIFICATION_THEME.hairline}`,
            flexShrink: 0,
          }}
        >
          <div
            style={{
              display: 'flex', alignItems: 'center', gap: 8,
              flexWrap: fullscreen ? 'nowrap' : 'wrap',
              ...(fullscreen ? {} : { marginBottom: 8 }),
            }}
          >
            {headerPrefix}
            <span style={{ flex: '1 1 0%' }} />
            {headerSuffix}
          </div>
        </div>
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto', padding: fullscreen ? '20px 24px' : 12 }}>
          <div data-testid="clarification-selection-column" style={fullscreenColumnStyle}>
            <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>
              {t('clarification.selectSquadAgent')}
            </Typography.Text>
            <SquadAgentSelector
              value={selection}
              onChange={onSelectionChange}
            />
          </div>
        </div>
      </div>
    );
}
