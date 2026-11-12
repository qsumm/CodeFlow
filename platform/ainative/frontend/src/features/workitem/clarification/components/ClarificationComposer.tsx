import type { CSSProperties, DragEvent, MutableRefObject, RefObject } from 'react';
import { useCallback, useRef, useState } from 'react';
import { Button, Select, Typography } from 'antd';
import {
  PaperClipOutlined, SendOutlined, StopOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { ResizeHandle } from '@/shared/ui/ResizeHandle';
import { transferContainsFiles, documentsFromDataTransfer } from '@/shared/lib/documentUpload';
import { PendingAttachments } from '../Attachments';
import { MarkdownComposer } from '../MarkdownComposer';
import type { MarkdownComposerHandle } from '../MarkdownComposer';
import { SlashCommandPicker } from '../SlashCommandPicker';
import { ElicitationWizard } from '../cards/ElicitationWizard';
import type { ElicitationReply } from '../cards/ElicitationCard';
import { SEND_MODE_OPTIONS } from '../sendMode';
import type { SendMode } from '../sendMode';
import type { AcpSlashCommand } from '../types';
import type { PendingClarificationAttachment } from '../attachmentUpload';

export const CLARIFICATION_INPUT_MIN_HEIGHT = 32;
export const CLARIFICATION_INPUT_MAX_RATIO = 0.6;
/** 面板高度未知时的保底高度（与 50386 面板最小高一致） */
export const CLARIFICATION_PANEL_FALLBACK_HEIGHT = 280;
/** 自动模式默认行数：默认给 6 行高度，保证足够的输入空间（工单评论反馈） */
export const CLARIFICATION_INPUT_DEFAULT_ROWS = 6;
export function computeInputHeightMax(panelHeight: number): number {
  const safeHeight = Number.isFinite(panelHeight) && panelHeight > 0
    ? panelHeight
    : CLARIFICATION_PANEL_FALLBACK_HEIGHT;
  return Math.max(CLARIFICATION_INPUT_MIN_HEIGHT, Math.floor(safeHeight * CLARIFICATION_INPUT_MAX_RATIO));
}

/** 自动模式：默认 6 行高度（反馈：默认输入框太小）；手动模式：关闭 autoSize，由固定 height 接管 */
export function clarificationInputAutoSize(inputHeight: number | null):
  { minRows: number; maxRows: number } | false {
  if (inputHeight == null) {
    return { minRows: CLARIFICATION_INPUT_DEFAULT_ROWS, maxRows: CLARIFICATION_INPUT_DEFAULT_ROWS };
  }
  return false;
}

/** 挂起中的问题卡片：整块输入行被它接管，卡片自身就是「回复目标」。 */
export interface ClarificationElicitationTarget {
  requestId: string;
  message?: string;
  schema: unknown;
}

interface ClarificationComposerProps {
  fullscreen?: boolean;
  /** 全屏时输入列铺满视口；停靠态保持自适应窄列。 */
  fullscreenColumnStyle: CSSProperties;
  /** 当前会话：null = 尚未落到具体会话，输入区整体不渲染。 */
  conversationId: number | null;
  /** 输入行整体禁用口径：发送门禁、附件选择/上传/移除共用。 */
  composerDisabled: boolean;
  /** 输入草稿（受控）。 */
  inputValue: string;
  onInputValueChange: (value: string) => void;
  /** 手动固定高度（像素）；null = 自动高度。 */
  manualInputHeight: number | null;
  /** 输入高度上限：随面板实测高度动态计算。 */
  inputHeightMax: number;
  onInputHeightChange: (height: number) => void;
  /** 双击拖拽条：回到自动高度。 */
  onInputHeightReset: () => void;
  /** 待发送附件：上传即落服务端，发送成功才绑定轮次。 */
  pendingAttachments: PendingClarificationAttachment[];
  onRemoveAttachment: (key: string) => void;
  /** 选中/拖入本地文件：交给父级走上传与权限校验。 */
  onSelectFiles: (files: File[], info?: { containsDirectory?: boolean }) => void;
  /** 斜杠命令候选：实时上报优先，会话详情快照兜底。 */
  slashCommands: AcpSlashCommand[];
  /** 当前补全查询；null = 不在补全态。 */
  slashQuery: string | null;
  onSlashSelect: (command: AcpSlashCommand) => void;
  /** 编辑器实例：父级用它归还焦点。 */
  composerRef: MutableRefObject<MarkdownComposerHandle | null>;
  sendMode: SendMode;
  onSendModeChange: (mode: SendMode) => void;
  /** 恢复身份未落定：输入框显示「恢复中」占位文案。 */
  agentRestorePending: boolean;
  onSend: () => void;
  /** 流已终态但详情还在同步：显示「结算中」提示与手动重试入口。 */
  streamTerminalAwaitingDetail: boolean;
  onRetryConversation: () => void;
  /** 回复进行中：把「发送」按钮换成「终止响应」。 */
  showReplyingIndicator: boolean;
  /** 执行器是否支持终止响应。 */
  cancelSupported: boolean;
  /** 正在处理的轮次：null 时不提供终止入口。 */
  processingTurnId: number | null;
  onCancelReply: () => void;
  cancelPending: boolean;
  isSubmittingCurrentConversation: boolean;
  /** 有附件仍在上传/失败：拦住发送，不让消息静默丢附件。 */
  hasBlockingAttachments: boolean;
  /** 输入非空或已有可用附件。 */
  canSendMessage: boolean;
  /** 面板容器：输入高度上限的测量基准。 */
  panelRef: RefObject<HTMLDivElement | null>;
  /** 挂起中的问题卡片：存在时整块输入行被它替换。 */
  displayedElicitation: ClarificationElicitationTarget | null;
  /** 正在提交作答的问题卡片 id。 */
  replyingRequestId: string | null;
  onReplyElicitation: (requestId: string, reply: ElicitationReply) => void;
  /** 卡片结算后的淡出态：不可交互，随后由父级卸载。 */
  dismissingElicitation: boolean;
}

/** 底部输入区：Markdown 编辑器 + 附件 + 斜杠命令 + 发送/终止按钮；问题卡片挂起时接管整行。 */
export function ClarificationComposer({
  fullscreen,
  fullscreenColumnStyle,
  conversationId,
  composerDisabled,
  inputValue,
  onInputValueChange,
  manualInputHeight,
  inputHeightMax,
  onInputHeightChange,
  onInputHeightReset,
  pendingAttachments,
  onRemoveAttachment,
  onSelectFiles,
  slashCommands,
  slashQuery,
  onSlashSelect,
  composerRef,
  sendMode,
  onSendModeChange,
  agentRestorePending,
  onSend,
  streamTerminalAwaitingDetail,
  onRetryConversation,
  showReplyingIndicator,
  cancelSupported,
  processingTurnId,
  onCancelReply,
  cancelPending,
  isSubmittingCurrentConversation,
  hasBlockingAttachments,
  canSendMessage,
  panelRef,
  displayedElicitation,
  replyingRequestId,
  onReplyElicitation,
  dismissingElicitation,
}: ClarificationComposerProps) {
  const { t } = useTranslation('workitem');
  const fileInputRef = useRef<HTMLInputElement>(null);
  const inputWrapRef = useRef<HTMLDivElement>(null);
  const [dragActive, setDragActive] = useState(false);
  const dragDepthRef = useRef(0);

  const handleComposerDragEnter = useCallback((event: DragEvent<HTMLDivElement>) => {
    if (composerDisabled || !transferContainsFiles(event.dataTransfer)) return;
    event.preventDefault();
    dragDepthRef.current += 1;
    setDragActive(true);
  }, [composerDisabled]);

  const handleComposerDragOver = useCallback((event: DragEvent<HTMLDivElement>) => {
    if (!transferContainsFiles(event.dataTransfer)) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = composerDisabled ? 'none' : 'copy';
  }, [composerDisabled]);

  const handleComposerDragLeave = useCallback((event: DragEvent<HTMLDivElement>) => {
    if (!transferContainsFiles(event.dataTransfer)) return;
    dragDepthRef.current = Math.max(0, dragDepthRef.current - 1);
    if (dragDepthRef.current === 0) setDragActive(false);
  }, []);

  const handleComposerDrop = useCallback((event: DragEvent<HTMLDivElement>) => {
    if (!transferContainsFiles(event.dataTransfer)) return;
    event.preventDefault();
    dragDepthRef.current = 0;
    setDragActive(false);
    if (composerDisabled) return;
    const target = event.target as HTMLElement | null;
    // 编辑器内的 drop 已由 MarkdownComposer 提取并回调 onFiles，这里跳过防重复上传。
    if (target?.closest('.ProseMirror')) return;
    const { files, containsDirectory } = documentsFromDataTransfer(event.dataTransfer);
    onSelectFiles(files, { containsDirectory });
  }, [composerDisabled, onSelectFiles]);

  if (!conversationId) return null;

  if (displayedElicitation) {
    return (
      <ElicitationWizard
        key={displayedElicitation.requestId}
        requestId={displayedElicitation.requestId}
        message={displayedElicitation.message}
        schema={displayedElicitation.schema}
        submitting={replyingRequestId === displayedElicitation.requestId}
        fullscreen={fullscreen}
        collapsible
        dismissing={dismissingElicitation}
        onReply={(reply) => onReplyElicitation(displayedElicitation.requestId, reply)}
      />
    );
  }

  return (
    <div style={{
      padding: fullscreen ? '12px 24px' : '8px 12px',
      position: 'relative', flexShrink: 0,
    }}>
      <ResizeHandle
        direction="vertical"
        showIndicator={false}
        value={manualInputHeight ?? CLARIFICATION_INPUT_MIN_HEIGHT}
        measureValue={() =>
          inputWrapRef.current?.getBoundingClientRect().height
          || manualInputHeight
          || CLARIFICATION_INPUT_MIN_HEIGHT}
        min={CLARIFICATION_INPUT_MIN_HEIGHT}
        max={inputHeightMax}
        measureMax={() => computeInputHeightMax(
          panelRef.current?.getBoundingClientRect().height || 0,
        )}
        onChange={onInputHeightChange}
        onDoubleClick={onInputHeightReset}
        aria-label={t('clarification.resizeInput')}
      />
      {streamTerminalAwaitingDetail && (
        <div
          data-testid="clarification-settling-notice"
          style={{
            display: 'flex',
            flexDirection: 'row',
            alignItems: 'center',
            gap: 8,
            marginBottom: 8,
          }}
        >
          <Typography.Text type="secondary">
            {t('clarification.replyEndedSyncing')}
          </Typography.Text>
          <Button
            size="small"
            data-testid="clarification-settling-retry"
            onClick={onRetryConversation}
          >
            {t('clarification.retry')}
          </Button>
        </div>
      )}
      <div
        data-testid="clarification-composer"
        style={{
          ...fullscreenColumnStyle,
          display: 'flex',
          flexDirection: 'column',
          gap: 8,
          position: 'relative',
        }}
        onDragEnter={handleComposerDragEnter}
        onDragOver={handleComposerDragOver}
        onDragLeave={handleComposerDragLeave}
        onDrop={handleComposerDrop}
      >
        <input
          ref={fileInputRef}
          type="file"
          multiple
          data-testid="clarification-file-input"
          style={{ display: 'none' }}
          onChange={(event) => {
            const files = Array.from(event.target.files ?? []);
            // 先清空再走异步上传：同一个文件连续选两次也要各自触发 change。
            event.target.value = '';
            if (files.length > 0) onSelectFiles(files);
          }}
        />
        <PendingAttachments
          attachments={pendingAttachments}
          disabled={composerDisabled}
          onRemove={onRemoveAttachment}
        />
        <div ref={inputWrapRef} style={{ minWidth: 0, position: 'relative' }}>
          <SlashCommandPicker
            commands={slashCommands}
            query={slashQuery}
            onSelect={onSlashSelect}
          />
          <MarkdownComposer
            handleRef={composerRef}
            value={inputValue}
            onChange={onInputValueChange}
            onSendKey={onSend}
            onFiles={onSelectFiles}
            sendMode={sendMode}
            disabled={composerDisabled}
            placeholder={agentRestorePending ? t('clarification.restoring') : t('clarification.messagePlaceholder')}
            height={manualInputHeight}
            minHeight={Math.round(CLARIFICATION_INPUT_DEFAULT_ROWS * 16 * 1.7) + 26}
            maxHeight={inputHeightMax}
          />
        </div>
        {dragActive ? (
          <div className="cf-clarify-drag-overlay" data-testid="clarification-drag-overlay">
            <PaperClipOutlined style={{ marginRight: 8 }} />
            {t('clarification.attachmentDropHint')}
          </div>
        ) : null}
        <div
          data-testid="clarification-input-actions"
          style={{
            width: '100%',
            display: 'flex',
            flexDirection: 'row',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 8,
          }}
        >
          <div style={{
            display: 'flex',
            flex: '1 1 auto',
            alignItems: 'center',
            gap: 4,
            minWidth: 0,
            maxWidth: 'calc(100% - 112px)',
          }}>
            <Button
              size="small"
              type="text"
              icon={<PaperClipOutlined />}
              aria-label={t('clarification.attachFile')}
              title={t('clarification.attachFile')}
              data-testid="clarification-attach"
              disabled={composerDisabled}
              onClick={() => fileInputRef.current?.click()}
            />
            <Select<SendMode>
              size="small"
              value={sendMode}
              onChange={onSendModeChange}
              options={SEND_MODE_OPTIONS.map(option => ({ value: option.value, label: option.label }))}
              aria-label={t('clarification.sendMode')}
              data-testid="clarification-send-mode-select"
              style={{ width: 180, maxWidth: '100%' }}
            />
          </div>
          {showReplyingIndicator && cancelSupported && processingTurnId != null ? (
            <Button
              danger
              icon={<StopOutlined />}
              style={{ minWidth: 104 }}
              onClick={onCancelReply}
              loading={cancelPending}
            >
              {t('clarification.stopResponse')}
            </Button>
          ) : (
            <Button
              type="primary"
              icon={<SendOutlined />}
              aria-label={t('clarification.sendMessage')}
              style={{ minWidth: 104 }}
              onClick={onSend}
              loading={isSubmittingCurrentConversation}
              disabled={composerDisabled || hasBlockingAttachments || !canSendMessage}
            >
              {t('clarification.send')}
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
