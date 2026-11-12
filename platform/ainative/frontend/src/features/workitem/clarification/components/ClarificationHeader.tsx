import type { ReactNode } from 'react';
import { Button, Select, Typography } from 'antd';
import { PlusOutlined, UserSwitchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { formatDateTime } from '@/shared/i18n/format';
import { CLARIFICATION_THEME } from '../theme';
import type { ClarificationConversation } from '../types';

interface ClarificationHeaderProps {
  fullscreen?: boolean;
  /** 全屏合并头行插槽：外层（RightPanel）把「返回/标题」塞在本面板头行开头。 */
  headerPrefix?: ReactNode;
  /** 全屏合并头行插槽：「退出全屏」等尾部动作挂在头行末尾。 */
  headerSuffix?: ReactNode;
  /** 当前数字人名称：停靠态居中显示，全屏态靠左显示。 */
  displayName: string;
  /** 当前会话：null = 尚未落到具体会话（自动选择尚未落定）。 */
  conversationId: number | null;
  /** 会话列表：下拉候选由它生成。 */
  conversations: ClarificationConversation[];
  /** 会话列表加载中：下拉框显示 loading。 */
  conversationsLoading: boolean;
  /** 创建会话进行中：期间禁用切换/新建/下拉。 */
  isCreatingConversation: boolean;
  onSelectConversation: (conversationId: number) => void;
  onCreateConversation: () => void;
  /** 「切换数字员工」：回到选人页。 */
  onSwitchAgent: () => void;
}

/** 面板头行：数字人身份、会话切换与新建会话。全屏为单行、停靠为两行。 */
export function ClarificationHeader({
  fullscreen,
  headerPrefix,
  headerSuffix,
  displayName,
  conversationId,
  conversations,
  conversationsLoading,
  isCreatingConversation,
  onSelectConversation,
  onCreateConversation,
  onSwitchAgent,
}: ClarificationHeaderProps) {
  const { t } = useTranslation('workitem');

  // 数字人名称列：basis 0 + minWidth 0 让它只占「剩余空间」而不是内容宽度，
  // 名称再长也只是 ellipsis，不会把同行控件挤变形（停靠与全屏共用）。
  // 停靠态名称在「返回/全屏」之间视觉居中：flex 占满中段 + 文本居中。
  const agentNameNode = (centered: boolean) => (
    <Typography.Text
      strong
      ellipsis={{ tooltip: displayName }}
      title={displayName}
      style={{ flex: '1 1 0%', minWidth: 0, whiteSpace: 'nowrap', textAlign: centered ? 'center' : 'start' }}
    >
      {displayName}
    </Typography.Text>
  );

  const conversationSelectOptions = conversations.map((item) => ({
    value: item.id,
    label: (
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
        <span>{t('clarification.conversationNumber', { id: item.id })}</span>
        <Typography.Text type="secondary" style={{ fontSize: 13 }}>
          {t('clarification.lastActive', { date: formatDateTime(item.lastTurnAt ?? item.gmtCreate, { year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' }) })}
        </Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 13 }}>
          {t('clarification.createdAt', { date: formatDateTime(item.gmtCreate, { year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' }) })}
        </Typography.Text>
      </span>
    ),
  }));

  return (
    <div
      data-testid="clarification-panel-header"
      style={{
        padding: fullscreen ? '12px 24px' : '10px 14px',
        borderBottom: `1px solid ${CLARIFICATION_THEME.hairline}`,
        flexShrink: 0,
      }}
    >
      {fullscreen ? (
        // 全屏：外层的「返回/退出全屏」与本面板控件融合为一行，永不换行。
        <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'nowrap', gap: 8 }}>
          {headerPrefix}
          <Button
            size="middle"
            icon={<UserSwitchOutlined />}
            aria-label={t('clarification.switchAgent')}
            disabled={isCreatingConversation}
            style={{ flexShrink: 0 }}
            onClick={onSwitchAgent}
          >
            {t('clarification.switchAgent')}
          </Button>
          {agentNameNode(false)}
          <Select
            data-testid="clarification-conversation-select"
            aria-label={t('clarification.selectClarificationConversation')}
            size="middle"
            value={conversationId ?? undefined}
            placeholder={t('clarification.selectConversation')}
            loading={conversationsLoading}
            disabled={isCreatingConversation}
            onChange={onSelectConversation}
            options={conversationSelectOptions}
            style={{ width: 320, maxWidth: '100%', flexShrink: 0 }}
          />
          <Button
            size="middle"
            icon={<PlusOutlined />}
            style={{ flexShrink: 0 }}
            onClick={onCreateConversation}
            loading={isCreatingConversation}
            disabled={isCreatingConversation}
          >
            {t('clarification.newConversation')}
          </Button>
          {headerSuffix}
        </div>
      ) : (
        // 停靠（窄面板）：固定两行——导航/身份一行、会话操作一行，
        // 各自适应列（名称与会话选择 flex 收缩 + ellipsis），
        // 任何名称长度都不会把控件挤变形或互相重叠。
        <>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
            {headerPrefix}
            {agentNameNode(true)}
            {headerSuffix}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Button
              size="small"
              icon={<UserSwitchOutlined />}
              aria-label={t('clarification.switchAgent')}
              disabled={isCreatingConversation}
              style={{ flexShrink: 0 }}
              onClick={onSwitchAgent}
            >
              {t('clarification.switchAgent')}
            </Button>
            <Select
              data-testid="clarification-conversation-select"
              aria-label={t('clarification.selectClarificationConversation')}
              size="small"
              value={conversationId ?? undefined}
              placeholder={t('clarification.selectConversation')}
              loading={conversationsLoading}
              disabled={isCreatingConversation}
              onChange={onSelectConversation}
              options={conversationSelectOptions}
              style={{ flex: '1 1 240px', minWidth: 120, maxWidth: '100%' }}
            />
            <Button
              size="small"
              icon={<PlusOutlined />}
              style={{ flexShrink: 0 }}
              onClick={onCreateConversation}
              loading={isCreatingConversation}
              disabled={isCreatingConversation}
            >
              {t('clarification.newConversation')}
            </Button>
          </div>
        </>
      )}
    </div>
  );
}
