import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { message } from 'antd';
import { i18n } from '@/shared/i18n';
import { findSquadIdForAgent } from '../SquadAgentSelector';
import type { SquadAgentSelection } from '../SquadAgentSelector';
import {
  useClarificationConversations,
  useClarificationConversation,
  useCreateClarificationConversation,
  isClarificationReplyingStatus,
} from '../hooks';
import { readClarificationPrefill, writeClarificationPrefill } from '../prefill';
import * as api from '../api';
import type { ClarifyContext } from '../../clarifyView';

const clarificationBootstrapPrompt = (workitemId: string) =>
  i18n.t('workitem:clarification.bootstrapPrompt', { workitemId });

interface UseClarificationBootstrapParams {
  workitemId: string;
  /** 从 URL 恢复的数字人：与 URL 会话成对使用，身份由会话详情或小队成员查询落定，
   *  不依赖数字人是否出现在交付进度里（工单 56281）。 */
  initialAgentId?: number | null;
  /** 从 URL 恢复的会话；会话详情落地后它的归属数字人就是权威恢复身份。 */
  initialConversationId?: number | null;
  /** 每次数字人从“未确定”变为“已确定”时触发，供外层自动进入全屏。 */
  onAgentConfirmed?: () => void;
  /** 当前数字人/会话发生变化时上报，供外层写回 URL 以支持刷新恢复。 */
  onContextChange?: (context: Partial<ClarifyContext>) => void;
  /** 输入草稿当前值：仅用于「进入会话即预填提示语」的判定（只读）。 */
  inputValue: string;
  /** 输入草稿写入：预填提示语与清空草稿都走这里。 */
  onInputValueChange: (value: string) => void;
}

/**
 * 面板启动与恢复：URL 上下文落定、会话自动选择/自动创建、恢复源校验、上下文上报，
 * 以及进入会话时的提示语预填。渲染与交互在页面壳与子组件里，这里只管「开局」。
 */
export function useClarificationBootstrap({
  workitemId,
  initialAgentId,
  initialConversationId,
  onAgentConfirmed,
  onContextChange,
  inputValue,
  onInputValueChange,
}: UseClarificationBootstrapParams) {
  // 澄清数字人候选始终来自「小队 → 成员」选择器：交付进度只是执行历史，
  // 把它当候选来源会让未绑定 SDLC 的需求澄清数字人消失（工单 56281）。
  const [selection, setSelection] = useState<SquadAgentSelection | null>(
    () => readClarificationPrefill(workitemId),
  );

  // 刷新恢复：URL 里带着会话 id 时直接落在原会话上，
  // 否则维持 null，交给下面的自动选择逻辑挑最近一条。
  const [conversationId, setConversationId] = useState<number | null>(initialConversationId ?? null);
  const conversationIdRef = useRef<number | null>(conversationId);
  conversationIdRef.current = conversationId;
  // 创建回调的身份护栏：回调落地时核对发起时的数字人是否仍是当前数字人，
  // 过时的创建结果不能覆盖用户此间的切换（修复要求 2）。
  const effectiveAgentIdRef = useRef<number | null>(null);
  // 每次进入会话只初始化一次，避免用户清空输入后被重新填入。
  const initializedInputRef = useRef<number | null>(null);

  // 刷新恢复（URL 上下文）：恢复源只认挂载当帧从 URL 读到的值——外层会把本面板上报的
  // 上下文写回 URL 再作为 prop 回流，跟着活的 prop 走会让恢复源在落定之前就被自己抹掉
  // （CR53035-001）。URL 恢复不得依赖数字人是否在交付进度里（工单 56281）。
  const restoredAgentIdRef = useRef<number | null>(initialAgentId ?? null);
  const restoredConversationIdRef = useRef<number | null>(initialConversationId ?? null);
  // 用户主动接管选人（选了成员或点了「切换数字员工」）之后，本地选择就是唯一事实来源，
  // 迟到的 URL 恢复不得再覆盖它（恢复优先级：用户选择 > URL > 本地记忆）。
  const userSelectedAgentRef = useRef(false);

  // 会话详情先于身份派生：URL 会话的主人就是最可靠的恢复身份——这条会话已经过服务端
  // 权限/归属校验，比 URL 参数更可信。只锁定一次，之后不再跟随当前会话变化。
  const conversation = useClarificationConversation(workitemId, conversationId);
  const urlConversationAgentIdRef = useRef<number | null>(null);
  if (urlConversationAgentIdRef.current == null
      && conversationId !== null
      && conversationId === restoredConversationIdRef.current
      && conversation.data != null) {
    urlConversationAgentIdRef.current = conversation.data.agentId;
  }
  const restoredAgentId = urlConversationAgentIdRef.current ?? restoredAgentIdRef.current;
  // 会话主人是渲染期锁定的 ref，派生结论必须把它读成渲染值并让其参与 memo 依赖：
  // 它在「数字人入参没变、只是主人身份落定」的一帧里 null→N，其余依赖全不变，
  // 只读 ref 的 memo 会返回缓存的「无恢复身份」把合法会话丢掉，面板退回本地记忆
  // 并自动选上别人的会话（缺陷二 A/B 竞态）。
  const urlConversationOwnerId = urlConversationAgentIdRef.current;

  // URL 只带数字人参数（没有会话）时，靠「小队成员查询」确认它仍然可选：
  // 候选范围与选择器一致，解析不出来的数字人不恢复。查询结果同时用于恢复后
  // 同步本地选择记忆。
  const restoreSquadQuery = useQuery({
    queryKey: ['clarification', 'agent-squad', restoredAgentId],
    queryFn: () => findSquadIdForAgent(restoredAgentId as number),
    enabled: restoredAgentId != null && !userSelectedAgentRef.current,
    staleTime: 5 * 60_000,
  });

  // URL 恢复身份：pending=恢复源还没落定，identity=落定结果，两者皆空=没有可恢复的 URL 身份。
  const urlRestore = useMemo((): { pending: boolean; identity: SquadAgentSelection | null } => {
    if (restoredAgentId == null || userSelectedAgentRef.current) {
      return { pending: false, identity: null };
    }
    if (urlConversationOwnerId != null) {
      // 会话本身就是恢复源：身份直接成立。小队关系只用来同步本地记忆，
      // 已不在任何可选小队中的历史数字人不编造小队归属（squadId 0 仅作占位）。
      return {
        pending: false,
        identity: { squadId: restoreSquadQuery.data ?? 0, agentId: urlConversationOwnerId },
      };
    }
    if (restoreSquadQuery.isSuccess) {
      return {
        pending: false,
        identity: restoreSquadQuery.data == null
          ? null
          : { squadId: restoreSquadQuery.data, agentId: restoredAgentId },
      };
    }
    // 查询失败不能把面板钉在「恢复中」：落回本地选择记忆，用户仍可自己选。
    if (restoreSquadQuery.isError) {
      return { pending: false, identity: null };
    }
    return { pending: true, identity: null };
  }, [restoredAgentId, urlConversationOwnerId, restoreSquadQuery.data, restoreSquadQuery.isError,
    restoreSquadQuery.isSuccess]);

  // 有效数字人身份：用户主动选择 > 有效 URL 上下文 > 本地选择记忆（工单 56281）。
  const selectedAgentId = selection?.agentId && selection.agentId > 0 ? selection.agentId : null;
  const effectiveAgentId = userSelectedAgentRef.current
    ? selectedAgentId
    : urlRestore.identity?.agentId ?? selectedAgentId;
  effectiveAgentIdRef.current = effectiveAgentId;

  // 恢复身份落定前，effectiveAgentId 可能仍是本地记忆里的另一个数字人（与恢复目标
  // 不同域）：这一窗口里它名下的会话列表不能代表恢复目标——拿它校验恢复的会话会把
  // 合法会话误判成「不存在」，拿它自动选择/自动创建会把预填数字人的会话钉在恢复目标
  // 身上（CR53035-002 A/B 竞态）。所有「系统代用户做决定」的动作都要等身份落定；
  // 用户手动选择不受此限。
  // URL 会话的主人还没解析出来（详情还在加载）时同样未落定：这段时间按本地记忆的
  // 列表校验会把合法会话误判成不存在。
  const urlConversationUnsettled = restoredConversationIdRef.current != null
    && urlConversationAgentIdRef.current == null
    && conversationId === restoredConversationIdRef.current
    && conversation.isLoading;
  const agentRestorePending = !userSelectedAgentRef.current
    && (urlRestore.pending || urlConversationUnsettled);

  // 三条选人路径（成员选择 / 「切换数字员工」后的重选 / localStorage 记忆命中）都汇聚到
  // 这个派生值上，其中记忆命中那条没有任何点击事件，所以监听跃迁而不是挂 handler。
  // 用 useLayoutEffect：记忆命中时 useEffect 会先 paint 一帧非全屏再切换，可见闪烁。
  // 未落定的身份不确认：此刻的 effectiveAgentId 可能只是本地记忆，确认它会让外层
  // 提前进全屏，随后又被恢复目标替换。
  const agentConfirmedRef = useRef(false);
  useLayoutEffect(() => {
    if (agentRestorePending || effectiveAgentId == null) {
      agentConfirmedRef.current = false;
      return;
    }
    if (agentConfirmedRef.current) return;
    agentConfirmedRef.current = true;
    onAgentConfirmed?.();
  }, [effectiveAgentId, agentRestorePending, onAgentConfirmed]);

  // 恢复落定后把有效身份写回本地选择记忆：以后重新打开不再依赖 URL。
  // 只在数字人确实归属于某个可选小队时写——没有小队归属的历史身份不伪造预填。
  const restoredPrefillWrittenRef = useRef(false);
  useEffect(() => {
    const identity = urlRestore.identity;
    if (restoredPrefillWrittenRef.current || identity == null || identity.squadId <= 0) return;
    if (userSelectedAgentRef.current) return;
    restoredPrefillWrittenRef.current = true;
    writeClarificationPrefill(workitemId, { squadId: identity.squadId, agentId: identity.agentId });
  }, [urlRestore.identity, workitemId]);

  const conversations = useClarificationConversations(workitemId, effectiveAgentId);
  const createMutation = useCreateClarificationConversation(workitemId);

  // Guards the auto-create effect: createMutation is a new object every
  // render (react-query v5), so without this set a persistent create failure
  // would re-trigger mutate on every effect run (request storm + toast spam).
  const autoCreateAttemptedRef = useRef(new Set<number>());

  useEffect(() => {
    if (agentRestorePending) return;
    if (!conversations.data || conversationId !== null) return;
    if (conversations.data.length > 0) {
      setConversationId(conversations.data[0].id);
      return;
    }
    if (!effectiveAgentId || createMutation.isPending) return;
    if (autoCreateAttemptedRef.current.has(effectiveAgentId)) return;
    autoCreateAttemptedRef.current.add(effectiveAgentId);
    const mutatedAgentId = effectiveAgentId;
    createMutation.mutate(mutatedAgentId, {
      onSuccess: (conv) => {
        // 过时的创建回调不能覆盖当前选择：创建期间用户切了数字人或手动选了会话。
        if (effectiveAgentIdRef.current !== mutatedAgentId) return;
        if (conversationIdRef.current !== null) return;
        setConversationId(conv.id);
      },
      onError: () => {
        message.error(i18n.t('workitem:clarification.autoCreateFailed'));
      },
    });
  }, [conversations.data, conversationId, effectiveAgentId, createMutation, workitemId,
    agentRestorePending]);

  // 打开会话即补一次带外命令探针：详情里的快照只保证秒显，命令可能早已过期。
  // 每个 conversationId 只打一次（重渲染/详情重拉/StrictMode 双调用都不能重复打），
  // fire-and-forget：失败静默，结果经实时 acp_commands 事件回推，下次打开再试。
  const commandsProbedRef = useRef<number | null>(null);
  useEffect(() => {
    if (!conversationId || commandsProbedRef.current === conversationId) return;
    if (!conversation.data?.acpInteractionSupported) return; // 仅 ACP 执行器才探针
    commandsProbedRef.current = conversationId;
    api.refreshClarificationCommands(workitemId, conversationId).catch(() => {});
  }, [conversationId, conversation.data?.acpInteractionSupported, workitemId]);

  // 刷新恢复（会话）：URL 可能来自书签或旧链接，会话也许已删除或已换了数字人。
  // 等会话列表落地后校验一次，不合法就清空，让上面的自动选择逻辑接手，
  // 否则刷新后会卡在一个永远拉不到内容的空会话上。
  // 只认挂载当帧从 URL 读到的那个 id：外层会随本面板的上报把新会话写回 URL，
  // 若跟着活的 prop 走，刚自动创建的会话会被一份还没刷新的旧列表判成“不存在”而清掉。
  // 校验必须绑定恢复目标的身份与它自己的列表（缺陷二）：URL 数字人还没落定时，
  // 当前列表属于 localStorage 预填数字人，用它判定「不存在」会永久清掉合法会话；
  // 列表加载失败同样不能当成「不存在」，留待成功后重试，期间不标记已校验。
  const restoredConversationCheckedRef = useRef(false);
  useEffect(() => {
    if (restoredConversationCheckedRef.current) return;
    const restoredId = restoredConversationIdRef.current;
    if (restoredId == null || conversationId !== restoredId) return;
    if (agentRestorePending) return;
    if (!conversations.data) return;
    restoredConversationCheckedRef.current = true;
    if (!conversations.data.some((item) => item.id === restoredId)) {
      setConversationId(null);
    }
  }, [conversations.data, conversationId, agentRestorePending]);

  // 把当前上下文回报给外层写回 URL。首次必须跳过「两者都还没确定」的那一帧：
  // URL 里的 agent/conversation 正是这一帧之前读出来的，回报 null 等于自己抹掉恢复源。
  const hasReportedContextRef = useRef(false);
  useEffect(() => {
    if (!hasReportedContextRef.current && effectiveAgentId == null && conversationId == null) return;
    hasReportedContextRef.current = true;
    onContextChange?.(
      agentRestorePending ? { conversationId } : { agentId: effectiveAgentId, conversationId },
    );
  }, [effectiveAgentId, conversationId, onContextChange, agentRestorePending]);

  // inputValue 只是「当前是否为空」的读取条件，不该成为 effect 的触发源：
  // 否则用户清空输入就会被再塞一遍提示语。
  const inputValueRef = useRef(inputValue);
  inputValueRef.current = inputValue;

  useEffect(() => {
    initializedInputRef.current = null;
  }, [workitemId, conversationId]);

  const convData = conversation.data;
  const isReplying = isClarificationReplyingStatus(convData?.processingStatus);
  useEffect(() => {
    if (!conversationId || initializedInputRef.current === conversationId
        || conversation.isLoading || agentRestorePending
        || !conversation.isSuccess || convData?.id !== conversationId) return;
    initializedInputRef.current = conversationId;
    if ((convData?.turns?.length ?? 0) === 0 && !inputValueRef.current.trim() && !isReplying) {
      onInputValueChange(clarificationBootstrapPrompt(workitemId));
    }
  }, [conversationId, convData, isReplying, workitemId,
    conversation.isSuccess, conversation.isLoading, agentRestorePending, onInputValueChange]);

  return {
    /** 当前选中的数字人（小队 + 成员）。 */
    selection,
    setSelection,
    /** 当前会话：null = 尚未落到具体会话。 */
    conversationId,
    setConversationId,
    /** 当前会话的镜像 ref：回调里读「此刻」的会话 id，避免闭包快照。 */
    conversationIdRef,
    /** 会话详情查询：轮询兜底、待解决卡片与问答历史的来源。 */
    conversation,
    /** 会话列表查询：下拉候选与自动选择的来源。 */
    conversations,
    /** 创建会话 mutation：自动创建与「新建会话」共用。 */
    createMutation,
    /** 生效的数字人身份：用户选择 > URL 恢复 > 本地记忆。 */
    effectiveAgentId,
    /** 恢复身份尚未落定：期间禁用自动决策与发送。 */
    agentRestorePending,
    /** 用户是否已手动接管选人：手动选择优先于迟到的 URL 恢复。 */
    userSelectedAgentRef,
  };
}
