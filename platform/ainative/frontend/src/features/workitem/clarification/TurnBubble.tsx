import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Tag, Typography } from 'antd';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import { RobotHeadIcon } from '@/shared/ui/RobotHeadIcon';
import type { Artifact } from '@/shared/types/workitem';
import { CopyMessageButton } from './CopyMessageButton';
import { SentAttachments } from './Attachments';
import { TurnDetailToggle } from './TurnDetailToggle';
import { CLARIFICATION_THEME, agentBlockStyle, userBubbleStyle } from './theme';
import { conversationNotice } from './conversationNotice';
import { findPairedInboundTurnId, isOutboundTurnDirection } from './hooks';
import type { ClarificationTurn } from './types';

export function OutboundTurnDetailToggle({ workitemId, conversationId, turns, index, coveredQuestionIds }: {
  workitemId: string;
  conversationId: number;
  turns: ClarificationTurn[];
  index: number;
  coveredQuestionIds?: ReadonlySet<string>;
}) {
  const turn = turns[index];
  if (!isOutboundTurnDirection(turn.direction)) return null;
  // 「已取消」气泡是服务端补的通知，自身没有执行事件；而配对逻辑会跳过 CANCELED
  // 的 IN 轮次，挂上去只会指到别的轮次的详情。
  if ((turn.status ?? '').toUpperCase() === 'CANCELED') return null;
  const eventTurnId = findPairedInboundTurnId(turns, index);
  if (eventTurnId == null) return null;
  return (
    <TurnDetailToggle
      workitemId={workitemId}
      conversationId={conversationId}
      turnId={turn.id}
      eventTurnId={eventTurnId}
      coveredQuestionIds={coveredQuestionIds}
    />
  );
}

export function TurnBubble({
  turn, agentName, detailToggle, workitemId, conversationId, artifacts, onArtifactClick,
}: {
  turn: ClarificationTurn;
  agentName?: string;
  detailToggle?: ReactNode;
  workitemId: string;
  conversationId: number | null;
  artifacts: Artifact[];
  onArtifactClick: (artifact: Artifact) => void;
}) {
  const { t } = useTranslation('workitem');
  const isUser = turn.direction === 'IN' || turn.direction === 'INBOUND';
  const hasContent = !!turn.content && turn.content.trim().length > 0;

  const statusTag = turn.status === 'CANCELED' ? (
    <Tag
      style={{
        display: 'inline-block', marginTop: 6, fontSize: 13,
        color: CLARIFICATION_THEME.textSecondary,
        borderColor: CLARIFICATION_THEME.hairline,
        backgroundColor: 'transparent',
      }}
    >
      {t('clarification.stopped')}
    </Tag>
  ) : null;

  const ownedContent = conversationNotice(turn.display?.content, 'content', t);
  const ownedError = conversationNotice(turn.display?.error, 'error', t);
  const errorText = turn.error ? (
    <Typography.Text type="danger" style={{ display: 'block', fontSize: 13, marginTop: 4 }}>
      {ownedError ?? turn.error}
    </Typography.Text>
  ) : null;

  // 内容为空（AI 返回占位）时没有可复制的东西。
  // 两侧给不同的无障碍名称：一屏几十条消息全叫「复制」，读屏用户按按钮名导航时
  // 分不出哪个是哪条。
  const renderCopyAction = (label: string) => (hasContent ? (
    <span className="cf-clarify-msg-actions" style={{ flexShrink: 0 }}>
      <CopyMessageButton text={turn.content} label={label} />
    </span>
  ) : null);

  if (isUser) {
    return (
      <div
        className="cf-clarify-msg"
        style={{
          marginBottom: 16,
          display: 'flex',
          justifyContent: 'flex-end',
          alignItems: 'center',
          gap: 4,
        }}
      >
        {renderCopyAction(t('clarification.copyMyMessage'))}
        <div style={{ maxWidth: '80%' }}>
          {/* 输入框已是 Markdown 编辑器，用户消息同样走 MarkdownView 渲染
              （whiteSpace 由 MarkdownView 内部设为 normal，不继承气泡的 pre-wrap）。 */}
          <div data-testid="clarification-user-bubble" style={userBubbleStyle()}>
            {conversationId != null && turn.attachments && turn.attachments.length > 0 ? (
              <SentAttachments
                workitemId={workitemId}
                conversationId={conversationId}
                attachments={turn.attachments}
              />
            ) : null}
            {/* 纯附件消息没有文字内容：附件块就是整条消息，不渲染空 Markdown。 */}
            {hasContent ? <MarkdownView className="cf-clarify-md" content={turn.content} /> : null}
            {statusTag}
            {errorText}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="cf-clarify-msg" style={{ marginBottom: 16 }}>
      <div
        style={{
          fontSize: 13,
          color: CLARIFICATION_THEME.textMuted,
          marginBottom: 6,
          display: 'flex',
          alignItems: 'center',
        }}
      >
        <span style={{ fontSize: 14, marginRight: 4 }}><RobotHeadIcon /></span>
        <span style={{ flex: 1, minWidth: 0 }}>{agentName || 'AI'}</span>
        {renderCopyAction(t('clarification.copyReply'))}
      </div>
      <div data-testid="clarification-agent-block" style={agentBlockStyle()}>
        {ownedContent != null
          ? <div style={{ whiteSpace: 'pre-wrap' }}>{ownedContent}</div>
          : hasContent
            ? (
              <MarkdownView
                className="cf-clarify-md"
                content={turn.content}
                artifacts={artifacts}
                onArtifactClick={onArtifactClick}
              />
            )
            : <Typography.Text type="secondary">{t('clarification.noResponse')}</Typography.Text>}
        {statusTag}
        {errorText}
        {detailToggle}
      </div>
    </div>
  );
}

/** requestedSchema 是后端原样透传的 JSON 字符串。解析不了就把原文交给
 *  parseElicitationSchema，让它降级成 JSON 文本域，而不是白屏。 */
export function parseSchemaJson(schema: string | null): unknown {
  if (!schema) return null;
  try {
    return JSON.parse(schema);
  } catch {
    return schema;
  }
}
