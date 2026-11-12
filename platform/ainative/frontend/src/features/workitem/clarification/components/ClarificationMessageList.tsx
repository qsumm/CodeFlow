import type { CSSProperties, RefObject } from 'react';
import { Fragment } from 'react';
import { Button, Empty, Typography } from 'antd';
import { VerticalAlignBottomOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { AnswerHistory } from '../cards/AnswerHistory';
import { ConversationEventView } from '../ConversationEventView';
import { ReplyingIndicator } from '../ReplyingIndicator';
import { OutboundTurnDetailToggle, TurnBubble } from '../TurnBubble';
import { agentBlockStyle, CLARIFICATION_THEME } from '../theme';
import type { QuestionHistoryEntry } from '../answerHistory';
import type { TimelineNode } from '../timeline';
import type { ClarificationTurn } from '../types';
import type { Artifact } from '@/shared/types/workitem';

/** 加载中/加载失败提示的居中样式：与 Empty 同视觉重量，不抢占消息流。 */
const CLARIFICATION_LOAD_HINT_STYLE: CSSProperties = {
  display: 'flex',
  justifyContent: 'center',
  alignItems: 'center',
  gap: 8,
  minHeight: 120,
};

/** 历史加载失败/降级提示（工单 55411 缺陷一）：错误可见 + 显式重试入口，
 *  代替把失败渲染成「没有历史」的空白态。降级态用 warning 色区分整页失败。 */
function ClarificationLoadError({ testId, description, tone = 'danger', onRetry }: {
  testId: string;
  description: string;
  tone?: 'danger' | 'warning';
  onRetry: () => void;
}) {
  const { t } = useTranslation('workitem');
  return (
    <div data-testid={testId} style={CLARIFICATION_LOAD_HINT_STYLE}>
      <Typography.Text type={tone}>{description}</Typography.Text>
      <Button size="small" onClick={onRetry}>{t('clarification.retry')}</Button>
    </div>
  );
}

interface ClarificationMessageListProps {
  fullscreen?: boolean;
  /** 全屏时正文列铺满视口；停靠态保持自适应窄列。 */
  fullscreenColumnStyle: CSSProperties;
  /** 滚动容器：父级用它做「跟随底部/回到底部」测量。 */
  scrollRef: RefObject<HTMLDivElement>;
  /** 滚动事件：判断用户是否离开底部，从而暂停自动跟随。 */
  onScroll: () => void;
  /** 用户已向上滚动离开底部：显示「回到底部」浮标。 */
  followBottom: boolean;
  onScrollToBottom: () => void;
  /** 当前会话：null = 尚未落到具体会话（此时展示会话列表失败/空态）。 */
  conversationId: number | null;
  /** 会话列表加载失败：展示整页错误 + 重试。 */
  conversationsFailed: boolean;
  onRetryConversations: () => void;
  /** 历史详情整页加载失败：展示整页错误 + 重试，且禁止发送。 */
  conversationHistoryFailed: boolean;
  /** 历史详情失败但已有内容：保留原内容并降级提示。 */
  conversationHistoryStale: boolean;
  onRetryConversation: () => void;
  isConversationLoading: boolean;
  /** 会话详情拉取成功（用于区分「没有历史」与「还没加载完」）。 */
  conversationSucceeded: boolean;
  /** 历史轮次：按时间顺序渲染气泡与问答记录。 */
  turns: ClarificationTurn[];
  /** 当前数字人名称：气泡抬头与「正在回复」提示共用。 */
  displayName: string;
  workitemId: string;
  artifacts: Artifact[];
  onArtifactClick: (artifact: Artifact) => void;
  /** 每个轮次对应的问答记录：已结算的卡片挂在对应气泡下面。 */
  questionHistories: Map<number, QuestionHistoryEntry[]>;
  /** 已被问答记录覆盖的问题：不再在流式区重复出现。 */
  coveredQuestionIds: ReadonlySet<string>;
  /** 流式事件时间线（已剔除被问答记录覆盖的节点）。 */
  streamingNodes: TimelineNode[];
  /** 流式文本：落库前的临时回复内容。 */
  streamedText: string;
  /** 正在处理且尚未落库：渲染流式气泡。 */
  showProcessingEvents: boolean;
  /** 流已给出终态但还没落库：渲染临时的数字人回复气泡。 */
  awaitingAgentReply: boolean;
  /** 回复进行中：底部显示「正在回复」状态行。 */
  showReplyingIndicator: boolean;
  /** 是否允许从已过期问题卡片「继续追问」。 */
  canContinue: boolean;
  /** 「继续追问」：把问题上下文追加进输入框。 */
  onContinueQuestion: (turn: ClarificationTurn, entry: QuestionHistoryEntry) => void;
}

/** 消息/事件流区：滚动容器 + 内容列 + 回到底部浮标。 */
export function ClarificationMessageList({
  fullscreen,
  fullscreenColumnStyle,
  scrollRef,
  onScroll,
  followBottom,
  onScrollToBottom,
  conversationId,
  conversationsFailed,
  onRetryConversations,
  conversationHistoryFailed,
  conversationHistoryStale,
  onRetryConversation,
  isConversationLoading,
  conversationSucceeded,
  turns,
  displayName,
  workitemId,
  artifacts,
  onArtifactClick,
  questionHistories,
  coveredQuestionIds,
  streamingNodes,
  streamedText,
  showProcessingEvents,
  awaitingAgentReply,
  showReplyingIndicator,
  canContinue,
  onContinueQuestion,
}: ClarificationMessageListProps) {
  const { t } = useTranslation('workitem');
  return (
    <div style={{ flex: 1, position: 'relative', minHeight: 0 }}>
      <div
        ref={scrollRef}
        data-testid="clarification-message-scroll"
        onScroll={onScroll}
        style={{ height: '100%', overflow: 'auto', padding: fullscreen ? '20px 24px' : '8px 12px' }}
      >
        {/* 这层列节点必须在滚动容器内部：滚动元素仍是 scrollRef 那层，
            否则跟随底部/回到底部的滚动测量会落到不滚动的节点上。 */}
        <div data-testid="clarification-content-column" style={fullscreenColumnStyle}>
          {!conversationId ? (
            conversationsFailed ? (
              <ClarificationLoadError
                testId="clarification-list-error"
                description={t('clarification.listFailed')}
                onRetry={onRetryConversations}
              />
            ) : (
              <Empty description={t('clarification.noConversations')} image={Empty.PRESENTED_IMAGE_SIMPLE} />
            )
          ) : conversationHistoryFailed ? (
            <ClarificationLoadError
              testId="clarification-history-error"
              description={t('clarification.historyFailed')}
              onRetry={onRetryConversation}
            />
          ) : (
            <>
              {conversationHistoryStale && (
                <ClarificationLoadError
                  testId="clarification-history-stale-notice"
                  tone="warning"
                  description={t('clarification.historyStale')}
                  onRetry={onRetryConversation}
                />
              )}
              {isConversationLoading && turns.length === 0 && (
                <div
                  data-testid="clarification-history-loading"
                  style={CLARIFICATION_LOAD_HINT_STYLE}
                >
                  <Typography.Text type="secondary">{t('clarification.loadingHistory')}</Typography.Text>
                </div>
              )}
              {turns.length === 0 && conversationSucceeded && !isConversationLoading
                && !showReplyingIndicator && !showProcessingEvents && !awaitingAgentReply && (
                <Empty description={t('clarification.noHistory')} image={Empty.PRESENTED_IMAGE_SIMPLE} />
              )}
              {turns.map((turn, index) => (
                <Fragment key={turn.id}>
                <TurnBubble
                  turn={turn}
                  agentName={displayName}
                  workitemId={workitemId}
                  conversationId={conversationId}
                  artifacts={artifacts}
                  onArtifactClick={onArtifactClick}
                  detailToggle={
                    <OutboundTurnDetailToggle
                      workitemId={workitemId}
                      conversationId={conversationId}
                      turns={turns}
                      index={index}
                      coveredQuestionIds={coveredQuestionIds}
                    />
                  }
                />
                <AnswerHistory entries={questionHistories.get(turn.id) ?? []}
                  canContinue={canContinue}
                  onContinue={entry => {
                    if (!canContinue) return;
                    onContinueQuestion(turn, entry);
                  }} />
                </Fragment>
              ))}
              {showProcessingEvents && (
                // 「等待回复」提示由底部的 ReplyingIndicator 统一承担，避免双份动效。
                // 保证的是文字块样式一致：流式区与完成态 TurnBubble 共用 agentBlockStyle()，
                // 字号/行高/颜色不会在回复落地时跳变（延续 f608cd4a4 的意图）。
                // 尚未消除的差异：TurnBubble 多一行数字人名字灰标，流式区没有，
                // 因此回复落地时内容仍会整体下移约一行，不是像素级等同。
                <div style={{ marginBottom: 16, display: 'flex' }}>
                  <div style={{ minWidth: 0 }}>
                    <div
                      data-testid="clarification-streaming-bubble"
                      style={agentBlockStyle()}
                    >
                      <ConversationEventView
                        nodes={streamingNodes}
                        isProcessing={false}
                        artifacts={artifacts}
                        onArtifactClick={onArtifactClick}
                      />
                    </div>
                  </div>
                </div>
              )}
              {awaitingAgentReply && (
                <TurnBubble
                  turn={{ id: -1, direction: 'OUTBOUND' as const, content: streamedText, status: '', error: null, gmtCreate: '' }}
                  agentName={displayName}
                  workitemId={workitemId}
                  conversationId={conversationId}
                  artifacts={artifacts}
                  onArtifactClick={onArtifactClick}
                />
              )}
              {showReplyingIndicator && <ReplyingIndicator agentName={displayName} />}
            </>
          )}
        </div>
      </div>
      {!followBottom && conversationId ? (
        <Button
          size="small"
          shape="circle"
          aria-label={t('clarification.bottom')}
          title={t('clarification.bottom')}
          icon={<VerticalAlignBottomOutlined />}
          onClick={onScrollToBottom}
          style={{
            position: 'absolute', right: 16, bottom: 12,
            boxShadow: '0 2px 8px rgba(0, 0, 0, 0.08)',
            backgroundColor: CLARIFICATION_THEME.surface,
            borderColor: CLARIFICATION_THEME.hairline,
          }}
        />
      ) : null}
    </div>
  );
}
