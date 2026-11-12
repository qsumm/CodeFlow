import { AnswerDeliveryStatus } from './cards/AnswerDeliveryStatus';
import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
import { projectQuestionHistory, questionIdentity, withoutCoveredQuestions } from './answerHistory';
import type { QuestionHistoryEntry } from './answerHistory';
import { appendQuestionContext } from './continueQuestion';
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { message } from 'antd';
import { ApiError, ErrorCodes } from '@/shared/types/common';
import { useAuthStore } from '@/shared/auth/store';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import {
  CLARIFICATION_ATTACHMENT_MAX_FILE_BYTES,
  createPendingAttachment,
  oversizeAttachments,
  releasePendingAttachment,
} from './attachmentUpload';
import { DOCUMENT_UPLOAD_MESSAGES } from '@/shared/lib/documentUpload';
import type { PendingClarificationAttachment } from './attachmentUpload';
import type { MarkdownComposerHandle } from './MarkdownComposer';
import { slashCommandQuery } from './SlashCommandPicker';
import { parseSchemaJson } from './TurnBubble';
import type { SquadAgentSelection } from './SquadAgentSelector';
import type { ElicitationReply } from './cards/ElicitationCard';
import { writeClarificationPrefill } from './prefill';
import type { ClarificationElicitation, ClarificationTurn } from './types';
import type { ClarifyContext } from '../clarifyView';
import {
  useSubmitClarificationTurn,
  useCancelClarificationTurn,
  useClarificationEvents,
  useReplyElicitation,
  useUploadClarificationAttachment,
  useDeleteClarificationAttachment,
  hasPersistedReplyForTurn,
  isOutboundTurnDirection,
  isClarificationReplyingStatus,
} from './hooks';
import {
  CLARIFICATION_SEND_MODE_KEY,
  CLARIFICATION_SEND_MODE_QUERY_KEY,
  DEFAULT_SEND_MODE,
  parseSendMode,
} from './sendMode';
import type { SendMode } from './sendMode';
import { getMySetting, putMySetting } from '@/features/profile/userSettingApi';
import type { UserSetting } from '@/features/profile/userSettingApi';
import * as api from './api';
import type { Artifact } from '@/shared/types/workitem';
import { ArtifactPreviewDrawer } from './ArtifactPreviewDrawer';
import { ClarificationSelectionView } from './ClarificationSelectionView';
import { ClarificationHeader } from './components/ClarificationHeader';
import { ClarificationMessageList } from './components/ClarificationMessageList';
import { ClarificationComposer } from './components/ClarificationComposer';
import type { ClarificationElicitationTarget } from './components/ClarificationComposer';
// 输入区的高度口径常量与计算：随输入区一起搬到了子组件，这里保留对外导出。
import {
  CLARIFICATION_INPUT_MIN_HEIGHT,
  CLARIFICATION_INPUT_MAX_RATIO,
  CLARIFICATION_PANEL_FALLBACK_HEIGHT,
  CLARIFICATION_INPUT_DEFAULT_ROWS,
  computeInputHeightMax,
  clarificationInputAutoSize,
} from './components/ClarificationComposer';
import { useClarificationBootstrap } from './hooks/useClarificationBootstrap';
import './clarification.css';

export {
  CLARIFICATION_INPUT_MIN_HEIGHT,
  CLARIFICATION_INPUT_MAX_RATIO,
  CLARIFICATION_PANEL_FALLBACK_HEIGHT,
  CLARIFICATION_INPUT_DEFAULT_ROWS,
  computeInputHeightMax,
  clarificationInputAutoSize,
};

/** 距底部小于该阈值视为“在底部”，用于自动跟随/回到底部判断（R4） */
export const CLARIFICATION_BOTTOM_FOLLOW_THRESHOLD = 8;

/** 问题卡片结算后先播一遍淡出再卸载的保留时长（工单 56330），与卡片淡出动画同长。 */
export const CLARIFICATION_CARD_DISMISS_MS = 160;

export function isNearScrollBottom(
  el: { scrollTop: number; clientHeight: number; scrollHeight: number },
  threshold: number = CLARIFICATION_BOTTOM_FOLLOW_THRESHOLD,
): boolean {
  return el.scrollTop + el.clientHeight >= el.scrollHeight - threshold;
}

interface WorkitemClarificationPanelProps {
  workitemId: string;
  artifacts?: Artifact[];
  /** 每次数字人从“未确定”变为“已确定”时触发，供外层自动进入全屏。
   *  同一次挂载内可能触发多次：URL 恢复落定、用户重新选择都会让派生值经过一次
   *  null 后重新确定，每次都是一次真实的“选定”。 */
  onAgentConfirmed?: () => void;
  /** 全屏渲染时正文、选人页与输入行横向铺满视口，只由外层内距留出小幅左右边距。 */
  fullscreen?: boolean;
  /** 从 URL 恢复的数字人：与 URL 会话成对使用，身份由会话详情或小队成员查询落定，
   *  不依赖数字人是否出现在交付进度里（工单 56281）。 */
  initialAgentId?: number | null;
  /** 从 URL 恢复的会话；会话详情落地后它的归属数字人就是权威恢复身份。 */
  initialConversationId?: number | null;
  /** 当前数字人/会话发生变化时上报，供外层写回 URL 以支持刷新恢复。
   *  只带「已经落定」的字段：恢复还没落定的字段一旦上报 null，
   *  外层就会把 URL 里的恢复源自己抹掉（CR53035-001）。 */
  onContextChange?: (context: Partial<ClarifyContext>) => void;
  /** 全屏合并头行插槽：外层（RightPanel）把「返回/标题」塞在本面板头行开头，
   *  与「切换数字人/会话/新对话」融为同一行，替代原先上下两条头行。 */
  headerPrefix?: ReactNode;
  /** 全屏合并头行插槽：「退出全屏」等尾部动作挂在头行末尾。 */
  headerSuffix?: ReactNode;
}

export function WorkitemClarificationPanel({
  workitemId,
  artifacts = [],
  onAgentConfirmed,
  fullscreen,
  initialAgentId,
  initialConversationId,
  onContextChange,
  headerPrefix,
  headerSuffix,
}: WorkitemClarificationPanelProps) {
  const { t } = useTranslation('workitem');
  const [previewArtifact, setPreviewArtifact] = useState<Artifact | null>(null);
  // null = 自动模式（默认 6 行高度）；数字 = 手动固定高度（像素）
  const [inputHeight, setInputHeight] = useState<number | null>(null);
  const [inputValue, setInputValue] = useState('');
  const composerRef = useRef<MarkdownComposerHandle | null>(null);

  // 启动与恢复：URL 上下文落定、会话自动选择/自动创建、恢复源校验与上下文上报。
  const {
    selection,
    setSelection,
    conversationId,
    setConversationId,
    conversationIdRef,
    conversation,
    conversations,
    createMutation,
    effectiveAgentId,
    agentRestorePending,
    userSelectedAgentRef,
  } = useClarificationBootstrap({
    workitemId,
    initialAgentId,
    initialConversationId,
    onAgentConfirmed,
    onContextChange,
    inputValue,
    onInputValueChange: setInputValue,
  });

  const convData = conversation.data;
  // 待发送附件：上传即落服务端（turn_id 为空），发送成功才绑定轮次。
  const [pendingAttachments, setPendingAttachments] = useState<PendingClarificationAttachment[]>([]);
  const pendingAttachmentsRef = useRef<PendingClarificationAttachment[]>(pendingAttachments);
  pendingAttachmentsRef.current = pendingAttachments;
  const uploadControllersRef = useRef(new Map<string, AbortController>());
  // 同一次草稿的失败重试复用 clientMessageId：服务端据此去重，超时重发不会产生两条消息。
  const sendAttemptRef = useRef<{ signature: string; clientMessageId: string } | null>(null);
  const accessCommand = useAccessCommand();
  const canWrite = useAuthStore(state => state.hasAccess('READ_WRITE'));

  /** 清空待发送附件：中止在飞上传并释放本地预览 URL。 */
  const clearPendingAttachments = useCallback(() => {
    pendingAttachmentsRef.current.forEach(releasePendingAttachment);
    pendingAttachmentsRef.current = [];
    setPendingAttachments([]);
    uploadControllersRef.current.forEach((controller) => controller.abort());
    uploadControllersRef.current.clear();
    sendAttemptRef.current = null;
  }, []);

  useEffect(() => () => {
    pendingAttachmentsRef.current.forEach(releasePendingAttachment);
    uploadControllersRef.current.forEach((controller) => controller.abort());
  }, []);
  // 「提交中的会话」集合：pending 按发起提交的会话记账，而不是读 mutation 的
  // isPending——mutation 观察者是面板级单例，旧会话的 POST 未返回时会把所有
  // 会话（含新建的目标会话）一起禁用（工单 55851）。
  const [pendingSubmitConversationIds, setPendingSubmitConversationIds] = useState<ReadonlySet<number>>(
    () => new Set(),
  );
  // 发送方式偏好（FR-005~FR-010）。初值就是默认值：偏好请求返回前按回车必须已经有
  // 确定行为，不能出现「先按默认发、拉回来后又改口径」的抖动。
  const [sendMode, setSendMode] = useState<SendMode>(DEFAULT_SEND_MODE);
  const sendModeSetting = useQuery({
    queryKey: CLARIFICATION_SEND_MODE_QUERY_KEY,
    queryFn: () => getMySetting(CLARIFICATION_SEND_MODE_KEY),
    staleTime: 5 * 60_000,
  });
  // sendMode 的镜像：保存任务异步落定时要读「此刻界面上的值」，
  // 闭包里的 state 只是发起保存那一刻的快照，判断不了失败是否已经过时。
  const sendModeRef = useRef<SendMode>(DEFAULT_SEND_MODE);
  // 最近一次确认落库成功的值：连续切换全部失败时回滚到它，而不是回滚到
  // 同样没保存上的上一次乐观选择（工单 55511：连续切换不被旧失败覆盖）。
  const savedSendModeRef = useRef<SendMode>(DEFAULT_SEND_MODE);
  // 用户是否已动过发送方式：水合时区分「补服务端值」与「不覆盖排队落库中的本地选择」。
  const sendModeTouchedRef = useRef(false);
  // 保存任务队列：串行执行 PUT，同一时刻只有一个请求在飞，最后一个任务保存最后的选择。
  const sendModeSaveQueueRef = useRef<Promise<void>>(Promise.resolve());
  // 只在偏好首次落定（成功或失败）时水合一次。此后本地 state 是唯一事实来源，
  // 否则窗口重新聚焦触发的后台重新拉取会把用户刚改的选择盖回服务端旧值。
  const sendModeHydratedRef = useRef(false);
  useEffect(() => {
    if (sendModeHydratedRef.current || sendModeSetting.isLoading) return;
    sendModeHydratedRef.current = true;
    // 用户在水合前就切过发送方式：本地选择已在保存队列里，服务端旧值不能覆盖它。
    if (sendModeTouchedRef.current) return;
    const hydrated = parseSendMode(sendModeSetting.data?.valueJson);
    setSendMode(hydrated);
    sendModeRef.current = hydrated;
    savedSendModeRef.current = hydrated;
  }, [sendModeSetting.isLoading, sendModeSetting.data]);
  const scrollRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  // R4: 用户向上滚动离开底部时暂停自动跟随；回到底部后恢复
  const [followBottom, setFollowBottom] = useState(true);
  const followBottomRef = useRef(true);
  // 程序化滚动标记：跟随/回到底部触发的 scrollTo 会派发 scroll 事件，
  // 在内容持续增长时这些事件可能短暂落在“非底部”位置，若直接参与
  // 判定会把跟随误关掉导致流式回复不再自动滚动（工单问题3）。
  const programmaticScrollPendingRef = useRef(false);

  const submitMutation = useSubmitClarificationTurn(workitemId, conversationId);
  const cancelMutation = useCancelClarificationTurn(workitemId, conversationId);
  const uploadAttachmentMutation = useUploadClarificationAttachment(workitemId, conversationId);
  const deleteAttachmentMutation = useDeleteClarificationAttachment(workitemId, conversationId);
  const {
    streamedEvents, streamedText, streamedTurnTerminated, resetStreamedEvents,
    timeline, availableCommands, streamedTurnId,
  } = useClarificationEvents(
    workitemId,
    conversationId,
    conversation.data?.processingTurnId,
  );

  const isCreatingConversation = createMutation.isPending;
  const isConversationLoading = conversationId !== null && conversation.isLoading;
  // 历史详情三态：加载中 / 成功（含确实没有消息）/ 失败。失败再细分为
  // 还没有任何内容（整页错误 + 禁止发送）与已有内容（保留原内容 + 降级提示），
  // 两者都不能被渲染成「没有历史」（工单 55411 缺陷一）。
  const conversationHistoryFailed = conversationId !== null
    && conversation.isError && convData == null;
  const conversationHistoryStale = conversationId !== null
    && conversation.isError && convData != null;
  const isProcessing = convData?.processingStatus === 'PROCESSING';
  // R1: 排队中（QUEUED）同样算“回复未结束”；与轮询兜底共用同一判定口径
  const isReplying = isClarificationReplyingStatus(convData?.processingStatus);
  // 提交中只按「当前会话自己的在飞请求」判定：切到/新建的会话必须立即可编辑，
  // 旧会话 POST 的落地只影响它自己（工单 55851）。
  const isSubmittingCurrentConversation = conversationId != null
    && pendingSubmitConversationIds.has(conversationId);
  const cancelSupported = convData?.cancelSupported === true;
  const acpInteractionSupported = convData?.acpInteractionSupported === true;
  // 逻辑表达式直接当依赖会每次渲染都变，滚动跟随的 effect 会被无谓地反复触发
  const turns = useMemo(() => convData?.turns ?? [], [convData?.turns]);

  // 输入行整体禁用口径：发送门禁、附件选择/上传/移除共用，避免某一入口在回复中仍可用。
  const composerDisabled = isSubmittingCurrentConversation || isCreatingConversation || isReplying
    || isConversationLoading || conversationHistoryFailed || agentRestorePending;
  const readyAttachments = pendingAttachments.filter((item) => !!item.attachment);
  // 上传中或上传失败的附件都不能随消息发送：宁可拦住，也不让消息静默丢附件。
  const hasBlockingAttachments = pendingAttachments.some((item) => !item.attachment);
  const canSendMessage = inputValue.trim().length > 0 || readyAttachments.length > 0;

  const addPendingFiles = useCallback((files: File[], info?: { containsDirectory?: boolean }) => {
    if (info?.containsDirectory) {
      message.error(DOCUMENT_UPLOAD_MESSAGES.directory);
      return;
    }
    if (files.length === 0) {
      message.error(DOCUMENT_UPLOAD_MESSAGES.noLocalFile);
      return;
    }
    const rejected = oversizeAttachments(files);
    if (rejected.length > 0) {
      // 单文件上限：超限文件不进入上传队列，其余合法文件照常添加。
      message.error(t('clarification.attachmentTooLarge', {
        name: rejected[0].name,
        max: Math.round(CLARIFICATION_ATTACHMENT_MAX_FILE_BYTES / (1024 * 1024)),
      }));
    }
    const accepted = files.filter((file) => file.size <= CLARIFICATION_ATTACHMENT_MAX_FILE_BYTES);
    if (accepted.length === 0 || !conversationIdRef.current) return;
    const created = accepted.map(createPendingAttachment);
    setPendingAttachments((current) => [...current, ...created]);
    // 选中即上传：发送时只带 attachmentIds，失败重试不必重传大文件。
    for (const item of created) {
      const controller = new AbortController();
      uploadControllersRef.current.set(item.key, controller);
      void uploadAttachmentMutation.mutateAsync({ file: item.file, signal: controller.signal })
        .then((attachment) => {
          uploadControllersRef.current.delete(item.key);
          setPendingAttachments((current) => current.map((pending) => (
            pending.key === item.key ? { ...pending, attachment } : pending
          )));
        })
        .catch((error) => {
          uploadControllersRef.current.delete(item.key);
          if (controller.signal.aborted) return;
          setPendingAttachments((current) => current.map((pending) => (
            pending.key === item.key
              ? {
                ...pending,
                error: error instanceof ApiError && error.message
                  ? error.message
                  : t('clarification.attachmentUploadFailed'),
              }
              : pending
          )));
        });
    }
  }, [t, uploadAttachmentMutation]);

  const selectAttachmentFiles = useCallback((
    files: File[],
    info?: { containsDirectory?: boolean },
  ) => {
    if (composerDisabled) return;
    accessCommand('READ_WRITE', t('clarification.attachFile'), () => addPendingFiles(files, info));
  }, [accessCommand, addPendingFiles, composerDisabled, t]);

  const removePendingAttachment = useCallback((key: string) => {
    const target = pendingAttachmentsRef.current.find((item) => item.key === key);
    if (!target) return;
    uploadControllersRef.current.get(key)?.abort();
    uploadControllersRef.current.delete(key);
    if (!target.attachment) {
      // 未上传成功（上传中/失败）：本地移除即可；已落库但中断的行由服务端 24h 孤儿清理兜底。
      releasePendingAttachment(target);
      setPendingAttachments((current) => current.filter((item) => item.key !== key));
      return;
    }
    const attachmentId = target.attachment.id;
    accessCommand('READ_WRITE', t('clarification.attachmentRemoveAction'), () => {
      void deleteAttachmentMutation.mutateAsync(attachmentId)
        .then(() => {
          releasePendingAttachment(target);
          setPendingAttachments((current) => current.filter((item) => item.key !== key));
        })
        .catch((error) => {
          message.error(error instanceof ApiError && error.message
            ? error.message
            : t('clarification.attachmentDeleteFailed'));
        });
    });
  }, [accessCommand, deleteAttachmentMutation, t]);

  // 切换工作项/会话时丢弃草稿附件：它们属于原会话，跨会话发送会串味。
  const attachmentScopeRef = useRef({ workitemId, conversationId });
  useEffect(() => {
    const previous = attachmentScopeRef.current;
    if (previous.workitemId === workitemId && previous.conversationId === conversationId) return;
    attachmentScopeRef.current = { workitemId, conversationId };
    clearPendingAttachments();
  }, [workitemId, conversationId, clearPendingAttachments]);

  const turnCountAtSubmitRef = useRef(turns.length);
  const prevIsProcessingRef = useRef(false);

  useEffect(() => {
    if (isSubmittingCurrentConversation) {
      turnCountAtSubmitRef.current = turns.length;
    }
  }, [isSubmittingCurrentConversation, turns.length]);

  useEffect(() => {
    if (prevIsProcessingRef.current && !isProcessing) {
      // processing just ended — turn count will be checked in render
    }
    prevIsProcessingRef.current = isProcessing;
  }, [isProcessing]);

  const hasPersistedStreamedReply = hasPersistedReplyForTurn(turns, streamedTurnId);
  const awaitingAgentReply = !!streamedText
    && !hasPersistedStreamedReply
    && (streamedTurnTerminated || (!isProcessing && turns.length === turnCountAtSubmitRef.current));
  const latestTurn = turns[turns.length - 1];
  const latestTurnIsAgentReply = !!latestTurn && isOutboundTurnDirection(latestTurn.direction);
  const showProcessingEvents = isProcessing && !streamedTurnTerminated && !latestTurnIsAgentReply;
  // 流式回复一旦以相同内容落库，累积的流式事件就完成使命：立即重置，
  // 幽灵流式气泡随持久化气泡同帧消失；下一轮从干净状态重新累积
  // （工单 50720 返工：不重置会让跨轮残留事件在后续渲染中再次拼接）。
  // 用 useLayoutEffect 保证在绘制前完成，避免持久化气泡与流式气泡同屏闪烁一帧。
  useLayoutEffect(() => {
    if (hasPersistedStreamedReply && !!streamedText) {
      resetStreamedEvents();
    }
  }, [hasPersistedStreamedReply, streamedText, resetStreamedEvents]);
  // R1: 发送后立即出现（含提交中/排队中），任意终态事件后消失
  const showReplyingIndicator = !!conversationId
    && !streamedTurnTerminated
    && (isSubmittingCurrentConversation || isReplying);

  // 流已给出本轮终态、详情却还停在 PROCESSING/QUEUED：回复指示已经隐藏，输入却仍
  // 禁用——用户看到的是一个没有任何解释的禁用输入框。这里补一个明确、可恢复的
  // 「结算中」状态与手动重试入口（事件触发的失效与 3s 轮询仍会自动收敛）。
  // 不在此开放发送：服务端尚未结算就解除并发门禁会造成重复提交（工单 55851）。
  const streamTerminalAwaitingDetail = !!conversationId && streamedTurnTerminated && isReplying;

  const queryClient = useQueryClient();
  const previousProcessingStatusRef = useRef<string | null>(null);
  useEffect(() => {
    const previous = previousProcessingStatusRef.current;
    const current = convData?.processingStatus ?? null;
    if (previous && isClarificationReplyingStatus(previous) && !isClarificationReplyingStatus(current)) {
      queryClient.invalidateQueries({ queryKey: ['workitem', workitemId, 'artifacts'] });
      queryClient.invalidateQueries({ queryKey: ['workitem', workitemId, 'requirement-documents'] });
    }
    previousProcessingStatusRef.current = current;
  }, [convData?.processingStatus, queryClient, workitemId]);

  const replyMutation = useReplyElicitation(workitemId, conversationId);
  const [replyingRequestId, setReplyingRequestId] = useState<string | null>(null);
  // 后端已结算（取消/过期/并发）但 resolved 事件不落库时，前端时间线仍把卡片当 pending。
  // 提交吃到 409 CONFLICT 就把该 requestId 记为已结算并据此撤下卡片，避免用户对着死卡片反复 409、对话卡死。
  const [settledElicitationIds, setSettledElicitationIds] = useState<ReadonlySet<string>>(() => new Set());

  const handleReplyElicitation = useCallback(
    (requestId: string, reply: ElicitationReply) => {
      if (!conversationId || replyMutation.isPending) return;
      setReplyingRequestId(requestId);
      replyMutation.mutate(
        { requestId, action: reply.action, content: reply.content },
        {
          onSettled: () => setReplyingRequestId(null),
          onError: (error) => {
            if (error instanceof ApiError && error.code === ErrorCodes.CONFLICT) {
              setSettledElicitationIds((prev) => new Set(prev).add(requestId));
              queryClient.invalidateQueries({
                queryKey: ['workitem', workitemId, 'clarification-conversation', conversationId],
              });
              message.info(i18n.t('workitem:clarification.questionExpired'));
              return;
            }
            message.error(i18n.t('workitem:clarification.answerFailed'));
          },
        },
      );
    },
    [conversationId, replyMutation, queryClient, workitemId],
  );

  const streamedRequestIds = useMemo(
    () => new Set(
      timeline
        .filter((node) => node.kind === 'elicitation')
        .map((node) => node.elicitation!.requestId),
    ),
    [timeline],
  );

  /** 刷新后 WS 流已断，未解决卡片只能从会话详情恢复；流里已有同一 requestId
   *  时以流为准，否则会并排出现两张一样的卡片。 */
  const restoredElicitations = useMemo<ClarificationElicitation[]>(() => {
    if (!acpInteractionSupported) return [];
    return (convData?.pendingElicitations ?? [])
      .filter((card) => !streamedRequestIds.has(card.requestId));
  }, [acpInteractionSupported, convData?.pendingElicitations, streamedRequestIds]);

  /** 进行中的问题停靠在输入框位置分步作答：优先用流里的 pending 节点，否则用刷新恢复的。
   *  同一时刻只显示一个；并发多个时取最早的一个，其余等当前这条 resolved 后再显示。
   *  已被后端结算（409）的 requestId 一律排除——其 resolved 事件可能没落库，时间线会一直当它 pending。 */
  const deliveredRequestIds = useMemo(() => new Set([...(convData?.answerDeliveries ?? []), ...(convData?.questionHistory ?? [])].map(card => card.requestId)), [convData?.answerDeliveries, convData?.questionHistory]);
  const questionHistories = useMemo(() => new Map(turns.filter(turn => turn.direction === 'IN' || turn.direction === 'INBOUND')
    .map(turn => [turn.id, projectQuestionHistory(turn.id, convData?.questionHistory ?? convData?.answerDeliveries, timeline)])),
  [turns, convData?.questionHistory, convData?.answerDeliveries, timeline]);
  const allQuestionHistory = useMemo(() => [...questionHistories.values()].flat(), [questionHistories]);
  const coveredQuestionIds = useMemo(() => new Set(allQuestionHistory.map(entry => questionIdentity(entry.turnId, entry.requestId))), [allQuestionHistory]);
  const currentQuestionTurnId = convData?.processingTurnId ?? [...turns].reverse().find(turn => turn.direction === 'IN' || turn.direction === 'INBOUND')?.id;
  const activeElicitation = useMemo(() => {
    if (!acpInteractionSupported) return null;
    const streamed = timeline.find(
      (node) => node.kind === 'elicitation' && node.elicitation
        && !node.elicitation.resolved
        && !settledElicitationIds.has(node.elicitation.requestId)
        && !deliveredRequestIds.has(node.elicitation.requestId),
    )?.elicitation;
    if (streamed) {
      return {
        requestId: streamed.requestId,
        message: streamed.message ?? undefined,
        schema: streamed.requestedSchema,
      };
    }
    const restored = restoredElicitations.find((card) => !settledElicitationIds.has(card.requestId) && !deliveredRequestIds.has(card.requestId));
    if (restored) {
      return {
        requestId: restored.requestId,
        message: restored.message ?? undefined,
        schema: parseSchemaJson(restored.requestedSchema),
      };
    }
    return null;
  }, [acpInteractionSupported, timeline, restoredElicitations, settledElicitationIds, deliveredRequestIds]);

  /** 卡片结算后先淡出再卸载（工单 56330）：结算瞬间保留上一张卡片 CLARIFICATION_CARD_DISMISS_MS，
   *  期间输入行不提前出现，避免「卡片与输入框双 present」；新一组卡片到来时立即换新。
   *  渲染期直接完成承接（不放到 effect），否则会出现一帧「卡片已卸、输入框未回」的闪空。 */
  const activeElicitationId = activeElicitation?.requestId ?? null;
  const [heldElicitation, setHeldElicitation] = useState(activeElicitation);
  const [heldRequestId, setHeldRequestId] = useState<string | null>(activeElicitationId);
  if (heldRequestId !== activeElicitationId) {
    setHeldRequestId(activeElicitationId);
    setHeldElicitation(activeElicitation ?? heldElicitation);
  }
  const displayedElicitation: ClarificationElicitationTarget | null = activeElicitationId
    ? activeElicitation
    : heldElicitation;
  const dismissingElicitation = !activeElicitationId && !!heldElicitation;
  useEffect(() => {
    if (!dismissingElicitation) return undefined;
    const timer = window.setTimeout(() => setHeldElicitation(null), CLARIFICATION_CARD_DISMISS_MS);
    return () => window.clearTimeout(timer);
  }, [dismissingElicitation]);

  /** 斜杠命令候选：实时 acp_commands（耐久态）优先，会话详情带回的快照种子兜底，
   *  这样打开会话敲 `/` 立刻有候选，不必等探针结果经 WS 回来。
   *  null = 尚未上报 → 用种子；[] = 执行器权威结论「没有命令」→ 不能再用种子盖回去。 */
  const seededCommands = convData?.availableCommands ?? [];
  const slashCommands = availableCommands ?? seededCommands;

  // 回复期间输入框是禁用的，浏览器会把焦点从禁用元素上摘掉，恢复可用后没人归还，
  // 用户必须手动点一次才能接着打字（工单 53305）。这里在「本轮回复进行中」结束时把
  // 焦点还给输入框。触发口径不含会话加载与创建：面板首次拉取会话时输入框同样会经历
  // 一次禁用→可用，算进来会让「打开一个早已回复完成的历史会话」也抢焦点。
  // 问题卡片挂起时整个输入行被 ElicitationWizard 替换、焦点归卡片，所以卡片也算
  // 「回复进行中」，等它结算、输入行重新出现后才由这次跃迁归还焦点。
  // 取消响应（R3）走的是同一条跃迁，不再需要单独的焦点归还路径。
  // 淡出中的卡片同样算「回复进行中」：焦点等它真正卸载、输入行重新出现后再归还。
  const replyInProgress = isSubmittingCurrentConversation || isReplying || !!displayedElicitation;
  const prevReplyInProgressRef = useRef(replyInProgress);
  useEffect(() => {
    const wasReplying = prevReplyInProgressRef.current;
    prevReplyInProgressRef.current = replyInProgress;
    if (wasReplying && !replyInProgress) {
      composerRef.current?.focus();
    }
  }, [replyInProgress]);

  // 输入行只有在选中会话、没有问题卡片挂起、且不在禁用态时才真正存在于 DOM 里，
  // 自动聚焦必须等这些条件同时成立（FR-004）。
  const inputRowFocusable = !!conversationId
    && !displayedElicitation
    && !isSubmittingCurrentConversation
    && !isCreatingConversation
    && !isReplying
    && !isConversationLoading;

  // 「进入面板」的计数：挂载是第 0 次，切入全屏再各算一次。RightPanel 全屏与非全屏
  // 复用同一个实例，切全屏只是 prop 变化、不会重新挂载，靠这个计数才能覆盖 FR-002。
  const [focusEpoch, setFocusEpoch] = useState(0);
  const prevFullscreenRef = useRef(fullscreen);
  useEffect(() => {
    const wasFullscreen = prevFullscreenRef.current;
    prevFullscreenRef.current = fullscreen;
    if (fullscreen && !wasFullscreen) setFocusEpoch((epoch) => epoch + 1);
  }, [fullscreen]);

  // 每个 epoch 只聚焦一次，所以切换会话（FR-003）、回复结束都不会由这里再抢焦点：
  // 输入行重新可用时 epoch 没变，effect 直接返回。回复结束的归还走上面那条 effect。
  const focusedEpochRef = useRef(-1);
  useEffect(() => {
    if (focusedEpochRef.current === focusEpoch || !inputRowFocusable) return;
    focusedEpochRef.current = focusEpoch;
    // 推到绘制之后再聚焦：输入行刚出现时布局还没稳定，此刻聚焦会带着页面滚一次。
    const frame = requestAnimationFrame(() => {
      composerRef.current?.focus({ preventScroll: true });
    });
    return () => cancelAnimationFrame(frame);
  }, [focusEpoch, inputRowFocusable]);

  const slashQuery = slashCommandQuery(inputValue);

  const handleSelectionChange = useCallback(
    (next: SquadAgentSelection | null) => {
      resetStreamedEvents();
      setInputValue('');
      userSelectedAgentRef.current = true;
      setSelection(next);
      setConversationId(null);
      if (next && next.agentId > 0) {
        writeClarificationPrefill(workitemId, {
          squadId: next.squadId,
          agentId: next.agentId,
        });
      }
    },
    [workitemId, resetStreamedEvents],
  );

  const handleSelectConversation = useCallback((nextConversationId: number) => {
    if (nextConversationId === conversationIdRef.current) return;
    resetStreamedEvents();
    setConversationId(nextConversationId);
    setInputValue('');
  }, [resetStreamedEvents]);

  const handleCreateConversation = useCallback(() => {
    const agentId = effectiveAgentId;
    if (!agentId) return;
    resetStreamedEvents();
    setInputValue('');
    createMutation.mutate(agentId, {
      onSuccess: (conv) => {
        setConversationId(conv.id);
      },
    });
  }, [effectiveAgentId, createMutation, resetStreamedEvents]);

  const handleSend = useCallback(() => {
    const content = inputValue.trim();
    const submittedConversationId = conversationId;
    const currentAttachments = pendingAttachmentsRef.current;
    const attachmentIds = currentAttachments
      .map((item) => item.attachment?.id)
      .filter((id): id is number => id != null);
    // 上传中/上传失败的附件不能随消息发送：拦住而不是静默丢附件。
    if (currentAttachments.some((item) => !item.attachment)) {
      message.error(t('clarification.attachmentSendBlocked'));
      return;
    }
    if (!content && attachmentIds.length === 0) return;
    // R2: 回复期间禁止发送；历史加载失败/恢复身份未落定时禁止发送：
    // 前者不能在未知历史上凭空对话（缺陷一），后者会话归属还没校验完就发消息
    // 可能把消息发进错误数字人的会话（缺陷二）。
    // 提交中只拦当前会话自己的在飞请求：旧会话的 POST 不应阻塞新会话（工单 55851）。
    if (!submittedConversationId || isSubmittingCurrentConversation || isReplying
      || isConversationLoading || conversationHistoryFailed || agentRestorePending) return;
    // 同一次草稿的失败重试复用 clientMessageId：服务端据此去重，超时重发不会产生两条消息。
    const signature = `${content}\u0000${attachmentIds.join(',')}`;
    let attempt = sendAttemptRef.current;
    if (!attempt || attempt.signature !== signature) {
      attempt = { signature, clientMessageId: api.createClientMessageId() };
      sendAttemptRef.current = attempt;
    }
    const { clientMessageId } = attempt;
    setInputValue('');
    // R4: 自己发送消息时恢复底部跟随
    followBottomRef.current = true;
    setFollowBottom(true);
    setPendingSubmitConversationIds((prev) => new Set(prev).add(submittedConversationId));
    // 结算走本次调用自己返回的 promise：多个会话的提交可以同时在飞，mutation
    // 观察者上的按次回调在并发下无法与单次提交一一对应；promise 与这次提交绑定。
    void submitMutation.mutateAsync({ content, attachmentIds, clientMessageId })
      .then(() => {
        // 成功才清空附件草稿：失败时输入框与附件都必须原样保留，供直接重试。
        // 提交期间切走的会话，其草稿已由作用域切换释放，不能误清新会话的附件。
        if (conversationIdRef.current === submittedConversationId) clearPendingAttachments();
      })
      .catch(() => {
        if (conversationIdRef.current === submittedConversationId) {
          setInputValue((current) => current || content);
        }
        message.error(i18n.t('workitem:clarification.sendFailed'));
      })
      .finally(() => {
        setPendingSubmitConversationIds((prev) => {
          if (!prev.has(submittedConversationId)) return prev;
          const next = new Set(prev);
          next.delete(submittedConversationId);
          return next;
        });
      });
  }, [inputValue, conversationId, submitMutation, isSubmittingCurrentConversation, isReplying,
    isConversationLoading, conversationHistoryFailed, agentRestorePending,
    clearPendingAttachments, t]);

  const handleCancelReply = useCallback(() => {
    const turnId = convData?.processingTurnId;
    if (!conversationId || turnId == null || cancelMutation.isPending) return;
    cancelMutation.mutate(turnId, {
      onError: () => {
        message.error(i18n.t('workitem:clarification.stopFailed'));
      },
    });
  }, [conversationId, convData?.processingTurnId, cancelMutation]);

  // 切换发送方式：先乐观生效（按键行为与入口文案必须立刻一致），再串行落用户级偏好。
  // 保存任务排进队列逐个执行：同一时刻只有一个 PUT 在飞，快速连切时最后一个任务
  // 保存最终选择，并发乱序完成的 PUT 不会让界面与落库值倒挂（工单 55511 验收 2/5）。
  const handleSendModeChange = useCallback((next: SendMode) => {
    const valueJson = JSON.stringify(next);
    sendModeRef.current = next;
    sendModeTouchedRef.current = true;
    setSendMode(next);
    queryClient.setQueryData<UserSetting>(
      CLARIFICATION_SEND_MODE_QUERY_KEY,
      { key: CLARIFICATION_SEND_MODE_KEY, valueJson },
    );
    sendModeSaveQueueRef.current = sendModeSaveQueueRef.current.then(async () => {
      try {
        await putMySetting(CLARIFICATION_SEND_MODE_KEY, valueJson);
        savedSendModeRef.current = next;
      } catch {
        // 失败时用户已切走：这是过时的失败，不回滚也不提示，新任务会落库新选择
        if (sendModeRef.current !== next) return;
        // 回滚到最后成功落库的值并同步缓存，否则界面标着「回车发送」而实际按回车只换行
        const rollback = savedSendModeRef.current;
        sendModeRef.current = rollback;
        setSendMode(rollback);
        queryClient.setQueryData<UserSetting>(
          CLARIFICATION_SEND_MODE_QUERY_KEY,
          { key: CLARIFICATION_SEND_MODE_KEY, valueJson: JSON.stringify(rollback) },
        );
        message.error(i18n.t('workitem:clarification.sendModeFailed'));
      }
    });
  }, [queryClient]);

  // R4: 仅在跟随底部时自动滚动；向上滚动离开底部后暂停跟随。
  // 用瞬时滚动（非 smooth）：流式内容持续增长时，平滑动画的中间态
  // 会触发“不在底部”的 scroll 判定，把跟随误关后就再也不再滚动。
  useEffect(() => {
    const el = scrollRef.current;
    if (!el || typeof el.scrollTo !== 'function') return;
    if (!followBottomRef.current) return;
    programmaticScrollPendingRef.current = true;
    el.scrollTo({ top: el.scrollHeight, behavior: 'auto' });
  }, [turns, streamedEvents]);

  const handleMessageScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const nearBottom = isNearScrollBottom(el);
    if (programmaticScrollPendingRef.current) {
      programmaticScrollPendingRef.current = false;
      // 程序化滚动到达底部：保持跟随开启，不让中间态/底部事件反复改写状态；
      // 若此时已不在底部（内容刚好又增长），落回正常判定。
      if (nearBottom) return;
    }
    followBottomRef.current = nearBottom;
    setFollowBottom(nearBottom);
  }, []);

  const scrollToBottomAndFollow = useCallback(() => {
    followBottomRef.current = true;
    setFollowBottom(true);
    const el = scrollRef.current;
    if (el && typeof el.scrollTo === 'function') {
      programmaticScrollPendingRef.current = true;
      el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
    }
  }, []);

  const needsAgentSelection = !effectiveAgentId;

  // 全屏时正文、选人页与输入行共用同一条铺满列：横向跟着视口走，
  // 左右留白完全交给外层容器已有的内距（工单 53035：超宽屏不该空出大半横向空间）。
  // minWidth: 0 让 flex 子项可收缩，overflowWrap 兜住不换行的长链接，
  // 两者共同保证视口变窄时列跟着收缩而不是撑出横向溢出。
  // 必须算在选人早返回之前：切换数字人 / 交付进度落地都会在全屏态下回到选人页，
  // 否则那一屏会与对话屏宽度不一致。
  const fullscreenColumnStyle: CSSProperties = fullscreen
    ? { width: '100%', minWidth: 0, overflowWrap: 'break-word' }
    : {};

  if (needsAgentSelection) {
    return (
      <ClarificationSelectionView
        fullscreen={fullscreen}
        headerPrefix={headerPrefix}
        headerSuffix={headerSuffix}
        selection={selection}
        onSelectionChange={handleSelectionChange}
      />
    );
  }

  const displayName = convData?.agentName ?? t('clarification.agent');

  // 上限在每次渲染时按面板容器实测高度动态计算：面板被 50386 拖大拖小后，
  // 父级尺寸变化会触发重渲染，下一次拖拽自动跟随新上限。
  const panelHeight = panelRef.current?.getBoundingClientRect().height || 0;
  const inputHeightMax = computeInputHeightMax(panelHeight);
  const manualInputHeight = inputHeight == null
    ? null
    : Math.min(inputHeight, inputHeightMax);

  return (
    <div ref={panelRef} className={fullscreen ? 'cf-clarification-panel is-fullscreen' : 'cf-clarification-panel'} style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <ClarificationHeader
        fullscreen={fullscreen}
        headerPrefix={headerPrefix}
        headerSuffix={headerSuffix}
        displayName={displayName}
        conversationId={conversationId}
        conversations={conversations.data ?? []}
        conversationsLoading={conversations.isLoading}
        isCreatingConversation={isCreatingConversation}
        onSelectConversation={handleSelectConversation}
        onCreateConversation={handleCreateConversation}
        onSwitchAgent={() => {
          userSelectedAgentRef.current = true;
          setSelection(null);
        }}
      />

      <ClarificationMessageList
        fullscreen={fullscreen}
        fullscreenColumnStyle={fullscreenColumnStyle}
        scrollRef={scrollRef}
        onScroll={handleMessageScroll}
        followBottom={followBottom}
        onScrollToBottom={scrollToBottomAndFollow}
        conversationId={conversationId}
        conversationsFailed={conversations.isError}
        onRetryConversations={() => { conversations.refetch(); }}
        conversationHistoryFailed={conversationHistoryFailed}
        conversationHistoryStale={conversationHistoryStale}
        onRetryConversation={() => { conversation.refetch(); }}
        isConversationLoading={isConversationLoading}
        conversationSucceeded={conversation.isSuccess}
        turns={turns}
        displayName={displayName}
        workitemId={workitemId}
        artifacts={artifacts}
        onArtifactClick={setPreviewArtifact}
        questionHistories={questionHistories}
        coveredQuestionIds={coveredQuestionIds}
        streamingNodes={withoutCoveredQuestions(timeline, allQuestionHistory)}
        streamedText={streamedText}
        showProcessingEvents={showProcessingEvents}
        awaitingAgentReply={awaitingAgentReply}
        showReplyingIndicator={showReplyingIndicator}
        canContinue={canWrite && !composerDisabled && !displayedElicitation}
        onContinueQuestion={(turn: ClarificationTurn, entry: QuestionHistoryEntry) => {
          if (!canWrite || composerDisabled || displayedElicitation) return;
          setInputValue(draft => appendQuestionContext(draft, turn.content, entry));
          composerRef.current?.focus();
        }}
      />

      <AnswerDeliveryStatus cards={convData?.answerDeliveries} currentTurnId={currentQuestionTurnId} />

      <ClarificationComposer
        fullscreen={fullscreen}
        fullscreenColumnStyle={fullscreenColumnStyle}
        conversationId={conversationId}
        composerDisabled={composerDisabled}
        inputValue={inputValue}
        onInputValueChange={setInputValue}
        manualInputHeight={manualInputHeight}
        inputHeightMax={inputHeightMax}
        onInputHeightChange={setInputHeight}
        onInputHeightReset={() => setInputHeight(null)}
        pendingAttachments={pendingAttachments}
        onRemoveAttachment={removePendingAttachment}
        onSelectFiles={selectAttachmentFiles}
        slashCommands={slashCommands}
        slashQuery={slashQuery}
        onSlashSelect={(command) => {
          // ACP 没有专门的 invoke 方法，选中命令就是把 `/name ` 当普通文本发出去
          setInputValue(`/${command.name} `);
          composerRef.current?.focus();
        }}
        composerRef={composerRef}
        sendMode={sendMode}
        onSendModeChange={handleSendModeChange}
        agentRestorePending={agentRestorePending}
        onSend={handleSend}
        streamTerminalAwaitingDetail={streamTerminalAwaitingDetail}
        onRetryConversation={() => { conversation.refetch(); }}
        showReplyingIndicator={showReplyingIndicator}
        cancelSupported={cancelSupported}
        processingTurnId={convData?.processingTurnId ?? null}
        onCancelReply={handleCancelReply}
        cancelPending={cancelMutation.isPending}
        isSubmittingCurrentConversation={isSubmittingCurrentConversation}
        hasBlockingAttachments={hasBlockingAttachments}
        canSendMessage={canSendMessage}
        panelRef={panelRef}
        displayedElicitation={displayedElicitation}
        replyingRequestId={replyingRequestId}
        onReplyElicitation={handleReplyElicitation}
        dismissingElicitation={dismissingElicitation}
      />

      {previewArtifact && (
        <ArtifactPreviewDrawer
          key={previewArtifact.id}
          artifact={previewArtifact}
          onClose={() => setPreviewArtifact(null)}
        />
      )}
    </div>
  );
}

/** 执行详情语义上属于 Agent 的这次作答（OUT 气泡），但事件按配对的 IN 轮次落库。 */
