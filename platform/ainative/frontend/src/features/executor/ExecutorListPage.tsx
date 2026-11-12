import { i18n } from '@/shared/i18n';
import { formatDateTime } from '@/shared/i18n/format';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useTranslation } from 'react-i18next';
import { PageHeading } from '@/shared/ui/PageHeading';
import { Toolbar } from '@/shared/ui/PageShell';
import { RobotHeadIcon } from '@/shared/ui/RobotHeadIcon';
import { useEffect, useMemo, useState } from 'react';
import { Card, Tag, Badge, Button, Space, Form, Select, message, Popconfirm, Typography, Tooltip, Collapse } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { PlusOutlined, DeleteOutlined, CodeSandboxOutlined, TeamOutlined, CloudUploadOutlined, LinkOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient, keepPreviousData } from '@tanstack/react-query';
import {
  listExecutors, createExecutor, deleteExecutor, getExecutorToken, getExecutorModelCatalog, restartExecutor,
  getExecutorLaunchConfig, updateExecutorLaunchConfig, buildExecutorLaunchCommand, previewExecutorLaunchCommand,
  saveExecutorLaunchCommand, applyExecutorLaunchCommand, type ExecutorRestartMode,
  updateExecutor, updateAllExecutors, getRuntimeAutoUpdate,
} from './api';
import { listAgents } from '@/features/agent/api';
import type { Agent } from '@/features/agent/api';
import { SquadFilterBar, useSquadOptions } from '@/features/squad/SquadFilterBar';
import { SquadTags } from '@/features/squad/SquadTags';
import { groupBySquad, type SquadGroup } from '@/features/squad/squadGrouping';
import { Link } from 'react-router-dom';
import { formatAgentSquadLabel } from './agentSquadLabel';
import { buildExecutorAgentGroups, type ExecutorAgentGroup } from './executorAgentGroups';
import type { ExecutorVO, IssuedExecutorVO, ExecutorLaunchConfig, ExecutorUpdateVO, ExecutorUpdateAllResultVO, ExecutorLaunchMode } from './api';
import type { DebugShell, StartupOs } from './api';
import { ApiError } from '@/shared/types/common';
import type { ColumnsType } from 'antd/es/table';
import {
  chooseQoderModel,
  qoderOptionsForModel,
  qoderProviderForClientKind,
  resolveQoderModelOptions,
  resolveQoderLaunchOptions,
} from './qoderOptions';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { useAuthStore } from '@/shared/auth/store';
import { copyTextToClipboard } from '@/shared/lib/clipboard';
import { readViewPreference, writeViewPreference } from '@/shared/lib/viewPreference';
import { detectStartupOs } from './startupOs';
import { restartMessage } from './executorStatus';
import { ExecutorCreateModal } from './components/ExecutorCreateModal';
import { ExecutorStartupModal } from './components/ExecutorStartupModal';
import { ExecutorRestartPolicyModal } from './components/ExecutorRestartPolicyModal';
import { ExecutorDetailDrawer } from './components/ExecutorDetailDrawer';
import { ExecutorCreatedTokenModal } from './components/ExecutorCreatedTokenModal';
import { ExecutorBulkUpdateConfirmModal, ExecutorBulkIssuedModal } from './components/ExecutorBulkUpdateModals';

import { clientKindMap } from './executorClientKinds';
import { isClientKindMissing, isQoderClientKind } from './executorClientKind';

export { describeUpdateAllResult } from './executorStatus';
// 客户端类型判定与可创建类型表搬到了独立模块，页面壳继续原样导出，外部引用不受影响。
export { CREATABLE_CLIENT_KINDS } from './executorClientKinds';
export { isClientKindMissing, isQoderClientKind } from './executorClientKind';

const EXECUTORS_VIEW_STORAGE_KEY = 'ainative.executors.view';
const EXECUTORS_VIEW_OPTIONS = ['agent', 'squad'] as const;

const statusBadge: Record<string, { status: 'success' | 'processing' | 'default'; key: keyof typeof import('@/shared/i18n/locales/zh-CN/executor.json') }> = {
  ONLINE: { status: 'success', key: 'online' },
  BUSY: { status: 'processing', key: 'busy' },
  OFFLINE: { status: 'default', key: 'offline' },
};

// 升级任务状态，与 ExecutorUpdateVO.status 一一对应，颜色沿用 antd 预设状态色。
const updateStatusLabels: Record<string, { key: keyof typeof import('@/shared/i18n/locales/zh-CN/executor.json'); color: string }> = {
  PENDING: { key: 'updatePending', color: 'default' },
  DRAINING: { key: 'updateDraining', color: 'processing' },
  UPDATING: { key: 'updateRunning', color: 'processing' },
  SUCCESS: { key: 'updateSucceeded', color: 'success' },
  FAILED: { key: 'updateFailed', color: 'error' },
};

const IN_PROGRESS_UPDATE_STATUSES = ['PENDING', 'DRAINING', 'UPDATING'];

export function isUpdateInProgress(update?: ExecutorUpdateVO | null): boolean {
  return Boolean(update && IN_PROGRESS_UPDATE_STATUSES.includes(update.status));
}

export function updateStatusLabel(status?: string | null): string {
  return status && Object.prototype.hasOwnProperty.call(updateStatusLabels, status) ? i18n.t(`executor:${updateStatusLabels[status].key}`) : status || '';
}

export function updateStatusColor(status?: string | null): string {
  return status && Object.prototype.hasOwnProperty.call(updateStatusLabels, status) ? updateStatusLabels[status].color : 'default';
}

/**
 * 一键更新的禁用原因，null 表示可以升级。只把按钮置灰而不说原因的话，操作者无从判断
 * 该做什么，所以每条拒绝路径都给出一句可执行的说明。三条分支与顺序都对应服务端
 * ExecutorUpdateService.updateOne 的校验，前端不另立一套规则。
 */
export function upgradeBlockReason(executor: ExecutorVO): string | null {
  // upgradeAvailable 为 false 有两种成因：版本不低于目标版本，或版本无法比较（从未上报，或
  // 0.2.155-beta.1、dev 这类非稳定版本）。只有前一种是「无需升级」——服务端 updateOne 也只在
  // 比较成功且 >= 0 时才拒绝，所以这里认服务端算好的 versionComparable，不再用 version 是否存在来猜。
  if (!executor.upgradeAvailable && executor.versionComparable) {
    return i18n.t('executor:upToDate', { version: executor.targetVersion ?? executor.version });
  }
  // 离线时 protocolFeatures（TTL 90s）早已过期，upgradeSupported 只代表「未知」而非「不支持」。
  // 服务端也只在在线时才校验能力：离线的任务先落库，等客户端重连再下发。
  if (executor.status === 'ONLINE' && !executor.upgradeSupported) {
    return i18n.t('executor:remoteUpgradeUnsupported');
  }
  if (isUpdateInProgress(executor.update)) return i18n.t('executor:updateInProgress');
  return null;
}

const SHELL_LABEL: Record<DebugShell, string> = {
  bash: 'bash',
  powershell: 'PowerShell',
};

// 启动配置与命令生成完全由服务端负责，页面只把服务端给出的错误码翻译成可读文案
const LAUNCH_ERROR_MESSAGES: Record<string, keyof typeof import('@/shared/i18n/locales/zh-CN/executor.json')> = {
  '17001': 'executorMissing',
  '17005': 'configChanged',
  '17006': 'modelUnavailable',
  '17007': 'configIncomplete',
  '17008': 'temporaryOverrideUnsupported',
  '17011': 'clientKindMissingError',
};

// 网络中断等系统级失败被拦截器统一包成该 code，message 是英文技术文案，对操作者没有可读信息
const SYSTEM_ERROR_CODE = '10000';

export function launchErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    const mapped = Object.prototype.hasOwnProperty.call(LAUNCH_ERROR_MESSAGES, error.code) ? LAUNCH_ERROR_MESSAGES[error.code] : undefined;
    if (mapped) return i18n.t(`executor:${mapped}`);
    if (error.code === SYSTEM_ERROR_CODE) return fallback;
    return error.message || fallback;
  }
  return fallback;
}

// 从未配置过的执行器四项全为 null，此时页面展示「未配置」并禁止生成命令，绝不用默认值兜底
export function isLaunchConfigured(config: ExecutorLaunchConfig | null): boolean {
  if (!config) return false;
  return Boolean(config.model || config.reasoningEffort || config.contextWindow || config.memoryMode);
}

function ExecutorGroupHeader({ group }: { group: ExecutorAgentGroup }) {
  const { t } = useTranslation('executor');
  return (
    <Space size={8} wrap>
      <RobotHeadIcon aria-hidden="true" />
      <Typography.Text strong>{group.label}</Typography.Text>
      <Tag>{t('executorCount', { count: group.executors.length })}</Tag>
      <Badge status="success" text={t('onlineCount', { count: group.statusSummary.online })} />
      <Badge status="processing" text={t('busyCount', { count: group.statusSummary.busy })} />
      <Badge status="default" text={t('offlineCount', { count: group.statusSummary.offline })} />
    </Space>
  );
}

function ExecutorSquadGroupHeader({ group }: { group: SquadGroup<ExecutorVO> }) {
  const { t } = useTranslation('executor');
  return (
    <Space size={8} wrap>
      <TeamOutlined aria-hidden="true" style={{ color: 'var(--cf-accent-text)' }} />
      <Typography.Text strong>
        {group.label}
      </Typography.Text>
      {group.squadId != null && (
        <Tooltip title={t('viewSquad')}>
          <Link to={`/agents?tab=squads&squadId=${group.squadId}`} aria-label={t('viewSquadNamed', { name: group.label })}
            onClick={(event) => event.stopPropagation()} onKeyDown={(event) => event.stopPropagation()}>
            <LinkOutlined aria-hidden="true" />
          </Link>
        </Tooltip>
      )}
      <Tag>{t('executorCount', { count: group.items.length })}</Tag>
    </Space>
  );
}

interface CreateExecutorFormValues {
  agentId: number;
  name: string;
  clientKind: string;
  memoryMode: string;
  maxConcurrentDispatches: number;
  model?: string;
  reasoningEffort?: string;
  contextWindow?: string;
}

interface StartupFormValues {
  // 仅历史缺类型执行器出现该项：补选的客户端类型随启动配置一并保存
  clientKind?: string;
  memoryMode: string;
  maxConcurrentDispatches: number;
  model?: string;
  reasoningEffort?: string;
  contextWindow?: string;
}

export function ExecutorListPage() {
  const { t } = useTranslation('executor');
  const { t: squadT } = useTranslation('squad');
  const queryClient = useQueryClient();
  const runAccessCommand = useAccessCommand();
  // 启动命令与令牌同属凭据面：页面按管理员权限请求，避免读写成员触发必然 403。
  const isAdmin = useAuthStore(state => state.accessLevel) === 'ADMIN';
  const [selectedAgentId, setSelectedAgentId] = useState<number | undefined>();
  // 只记录用户主动展开的分组，Agent 分组默认折叠，异步加载或刷新后新出现的分组同样保持折叠。
  const [expandedAgentKeys, setExpandedAgentKeys] = useState<string[]>([]);
  const [squadFilter, setSquadFilter] = useState<number[]>([]);
  const [squadGrouped, setSquadGrouped] = useState(
    () => readViewPreference(EXECUTORS_VIEW_STORAGE_KEY, EXECUTORS_VIEW_OPTIONS, 'squad') === 'squad',
  );
  const [expandedSquadKeys, setExpandedSquadKeys] = useState<string[]>([]);
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [tokenResult, setTokenResult] = useState<IssuedExecutorVO | null>(null);
  const [form] = Form.useForm();
  const [startupForm] = Form.useForm();
  const startupFormValues = Form.useWatch([], startupForm) as Partial<StartupFormValues> | undefined;
  const [startupTarget, setStartupTarget] = useState<ExecutorVO | null>(null);
  useLocalizedValidation(form, createModalOpen);
  useLocalizedValidation(startupForm, Boolean(startupTarget));
  const [revealedTokens, setRevealedTokens] = useState<Record<number, string>>({});
  const [loadingTokenId, setLoadingTokenId] = useState<number | null>(null);
  const [deleteConfirmId, setDeleteConfirmId] = useState<number | null>(null);
  const [startupOs, setStartupOs] = useState<StartupOs>(detectStartupOs());
  // 启动方式三档：foreground 默认；切换会带 mode 重新向服务端索取命令
  const [startupMode, setStartupMode] = useState<ExecutorLaunchMode>('foreground');
  // 服务端读回的启动配置，同时携带下次保存必须回传的乐观锁版本号；读取失败时为 null
  const [startupConfig, setStartupConfig] = useState<ExecutorLaunchConfig | null>(null);
  const [startupConfigFailure, setStartupConfigError] = useState<unknown>(null);
  const startupConfigError = startupConfigFailure === null ? null : launchErrorMessage(startupConfigFailure, t('configReadFailed'));
  const [startupSaving, setStartupSaving] = useState(false);
  const [commandDraft, setCommandDraft] = useState<string | null>(null);
  const [commandSaving, setCommandSaving] = useState(false);
  const [restartPolicyOpen, setRestartPolicyOpen] = useState(false);
  const [commandRequest, setCommandRequest] = useState<ExecutorVO['restart']>(null);
  // 服务端返回的 Qoder 配置里模型已被清空（不再提供），提示用户重新选择
  const [startupModelInvalid, setStartupModelInvalid] = useState(false);
  // 一键全量更新的确认弹窗开关，以及下发完成后逐条展示跳过原因的结果。
  const [updateAllOpen, setUpdateAllOpen] = useState(false);
  const [updateAllResult, setUpdateAllResult] = useState<ExecutorUpdateAllResultVO | null>(null);
  const clientKind = Form.useWatch('clientKind', form);
  const qoderModel = Form.useWatch('model', form) ?? 'auto';
  const startupQoderModel = Form.useWatch('model', startupForm) ?? 'auto';
  // 历史缺类型执行器的模型参数由补选的表单类型驱动；正常执行器仍用列表记录里的类型
  const startupKindMissing = isClientKindMissing(startupTarget?.clientKind);
  const startupFormClientKind = Form.useWatch('clientKind', startupForm);
  const startupQoderProvider = qoderProviderForClientKind(
    startupKindMissing ? startupFormClientKind : startupTarget?.clientKind,
  );
  const createQoderProvider = createModalOpen ? qoderProviderForClientKind(clientKind) : undefined;

  const qoderCatalogQuery = useQuery({
    queryKey: ['executor-model-catalog', 'qoder'],
    queryFn: () => getExecutorModelCatalog('qoder'),
    enabled: createQoderProvider === 'qoder' || startupQoderProvider === 'qoder',
    retry: false,
  });
  const qoderCnCatalogQuery = useQuery({
    queryKey: ['executor-model-catalog', 'qodercn'],
    queryFn: () => getExecutorModelCatalog('qodercn'),
    enabled: createQoderProvider === 'qodercn' || startupQoderProvider === 'qodercn',
    retry: false,
  });
  const qoderCatalogModels = qoderCatalogQuery.isError ? undefined : qoderCatalogQuery.data?.models;
  const qoderCnCatalogModels = qoderCnCatalogQuery.isError ? undefined : qoderCnCatalogQuery.data?.models;
  const createQoderModelOptions = useMemo(() => resolveQoderModelOptions(
    createQoderProvider === 'qoder'
      ? qoderCatalogModels
      : createQoderProvider === 'qodercn'
        ? qoderCnCatalogModels
        : undefined,
  ), [createQoderProvider, qoderCatalogModels, qoderCnCatalogModels, t]);
  const startupQoderModelOptions = useMemo(() => resolveQoderModelOptions(
    startupQoderProvider === 'qoder'
      ? qoderCatalogModels
      : startupQoderProvider === 'qodercn'
        ? qoderCnCatalogModels
        : undefined,
  ), [startupQoderProvider, qoderCatalogModels, qoderCnCatalogModels, t]);

  // 创建弹窗的模型下拉依赖服务端目录，目录变化时把默认值补齐，保证提交的四个启动参数完整。
  useEffect(() => {
    if (!createQoderProvider) return;
    const model = chooseQoderModel(createQoderModelOptions, form.getFieldValue('model'));
    if (!model || model === form.getFieldValue('model')) return;
    form.setFieldsValue(resolveQoderLaunchOptions(model, form.getFieldsValue()));
  }, [createQoderModelOptions, createQoderProvider, form]);

  // 历史缺类型执行器补选客户端类型后，按所选 provider 把模型四项默认值补齐，
  // 保证恢复保存提交的启动参数完整；正常执行器不走该路径（值来自服务端读回）。
  useEffect(() => {
    if (!startupTarget || !startupKindMissing) return;
    if (!startupQoderProvider) return;
    const model = chooseQoderModel(startupQoderModelOptions, startupForm.getFieldValue('model'));
    if (!model || model === startupForm.getFieldValue('model')) return;
    startupForm.setFieldsValue(resolveQoderLaunchOptions(model, startupForm.getFieldsValue()));
  }, [startupKindMissing, startupQoderModelOptions, startupQoderProvider, startupForm, startupTarget]);

  const { data: agents = [] } = useQuery({
    queryKey: ['agents', 1, 100],
    queryFn: () => listAgents({ page: 1, size: 100 }),
  });

  const { options: squadOptions, nameById: squadNameById, isLoading: squadsLoading } = useSquadOptions();
  // 归属标签直接取 Agent 列表自带的 squadNames：与小队详情里的成员同源，但不再为每个小队各发一次详情请求。
  const agentSquadNamesById = useMemo(() => {
    const namesByAgentId = new Map<number, string[]>();
    for (const agent of agents) {
      const squadNames = agent.squadNames;
      if (squadNames?.length) {
        namesByAgentId.set(agent.id, squadNames);
      }
    }
    return namesByAgentId;
  }, [agents]);
  const agentOptionLabel = (agent: Agent) => formatAgentSquadLabel(agent.name, agentSquadNamesById.get(agent.id));

  const [detailId, setDetailId] = useState<number | null>(null);
  const restartMut = useMutation({
    mutationFn: ({ id, update }: { id: number; update: boolean }) => restartExecutor(id, update),
    onSuccess: (result) => { if (result?.status === 'FAILED') message.error(restartMessage(result)); else message.success(i18n.t('executor:restartRequested')); queryClient.invalidateQueries({ queryKey: ['executors'] }); },
    onError: (error) => message.error(launchErrorMessage(error, i18n.t('executor:restartRequestFailed'))),
  });
  // 自动升级开关对所有登录用户可读。开关本身是服务端 application.yml 里的部署配置（默认开启），
  // 面板只展示当前状态，没有写入口。
  const autoUpdateQuery = useQuery({
    queryKey: ['platform-runtime-auto-update'],
    queryFn: getRuntimeAutoUpdate,
  });
  const updateMut = useMutation({
    mutationFn: (id: number) => updateExecutor(id),
    onSuccess: (result) => {
      message.success(i18n.t('executor:updateIssued', { version: result.targetVersion }));
      queryClient.invalidateQueries({ queryKey: ['executors'] });
    },
    onError: (error) => message.error(launchErrorMessage(error, i18n.t('executor:updateRequestFailed'))),
  });
  const updateAllMut = useMutation({
    mutationFn: () => updateAllExecutors(squadFilter),
    onSuccess: (result) => {
      setUpdateAllOpen(false);
      setUpdateAllResult(result);
      queryClient.invalidateQueries({ queryKey: ['executors'] });
    },
    onError: (error) => message.error(launchErrorMessage(error, i18n.t('executor:bulkRequestFailed'))),
  });
  const { data: executors = [], isLoading } = useQuery({
    queryKey: ['executors', selectedAgentId, squadFilter],
    queryFn: () => listExecutors(selectedAgentId, squadFilter),
    // 切换筛选时保留上一份列表，避免每次筛选都先闪回空表再等新数据。
    placeholderData: keepPreviousData,
    refetchInterval: detailId !== null || (startupTarget !== null && commandRequest) ? 3000 : 10000,
  });

  const visibleExecutors = useMemo(() => {
    if (squadFilter.length === 0) {
      return executors;
    }
    // GET /api/executors already filtered these server-side; the per-agent endpoint did not.
    return executors.filter((executor) => (executor.squadIds ?? [])
      .some((squadId) => squadFilter.includes(squadId)));
  }, [executors, squadFilter]);

  // 确认弹窗要说清这次会动多少台。这里复用 upgradeBlockReason 而不是只看 upgradeAvailable：
  // 服务端 updateAll 会跳过「已是目标版本」「已有任务进行中」「在线但不支持」三类，也会受理版本
  // 无法解析的执行器，只有同一套判定才能让弹窗里的数量和逐行按钮的置灰状态一致。
  const upgradableExecutors = useMemo(
    () => visibleExecutors.filter((executor) => upgradeBlockReason(executor) === null),
    [visibleExecutors],
  );

  const agentNameById = useMemo(
    () => new Map(agents.map((agent) => [agent.id, agent.name])),
    [agents],
  );

  const executorGroups = useMemo(() => buildExecutorAgentGroups(
    visibleExecutors,
    (agentId, agentName) => {
      const name = agentName ?? agentNameById.get(agentId);
      if (!name) return null;
      return formatAgentSquadLabel(name, agentSquadNamesById.get(agentId));
    },
  ), [agentNameById, agentSquadNamesById, visibleExecutors, t]);

  const squadGroups = useMemo(
    () => groupBySquad(visibleExecutors, (executor) => executor.squadIds, squadNameById),
    [visibleExecutors, squadNameById, squadT],
  );

  const handleAgentCollapseChange = (keys: string | string[]) => {
    // 非手风琴模式下 antd 恒传数组，用 flat 归一化两种签名，避免留下不可达分支。
    setExpandedAgentKeys([keys].flat());
  };

  const handleSquadCollapseChange = (keys: string | string[]) => {
    setExpandedSquadKeys([keys].flat());
  };

  const startupConfigured = isLaunchConfigured(startupConfig);
  const startupDraftValues = startupFormValues && startupConfig && !startupKindMissing ? {
    memoryMode: startupFormValues.memoryMode,
    maxConcurrentDispatches: startupFormValues.maxConcurrentDispatches,
    ...(startupQoderProvider ? {
      model: startupFormValues.model,
      reasoningEffort: startupFormValues.reasoningEffort,
      contextWindow: startupFormValues.contextWindow,
    } : {}),
  } : null;
  const startupSavedValues = { ...startupConfig, maxConcurrentDispatches: startupConfig?.maxConcurrentDispatches ?? 5 };
  const startupPreviewConfig = startupDraftValues && Object.entries(startupDraftValues)
    .some(([key, value]) => value !== startupSavedValues[key as keyof typeof startupSavedValues])
    ? { ...startupDraftValues, version: startupConfig!.version } : null;
  const startupPreviewValid = !startupPreviewConfig || Boolean(startupPreviewConfig.memoryMode
    && Number.isInteger(startupPreviewConfig.maxConcurrentDispatches)
    && startupPreviewConfig.maxConcurrentDispatches! >= 1 && startupPreviewConfig.maxConcurrentDispatches! <= 10
    && (!startupQoderProvider || (startupPreviewConfig.model && startupPreviewConfig.reasoningEffort && startupPreviewConfig.contextWindow)));
  const requestStartupCommand = (os: StartupOs) => startupPreviewConfig
    ? previewExecutorLaunchCommand(startupTarget!.id, { os, mode: startupMode, config: startupPreviewConfig })
    : buildExecutorLaunchCommand(startupTarget!.id, { os, mode: startupMode });

  // 草稿仅参与服务端预览；复制和在线应用仍先保存，再生成正式命令。
  const startupCommandQuery = useQuery({
    queryKey: ['executor-launch-command', startupTarget?.id, startupOs, startupMode, startupPreviewConfig],
    queryFn: () => requestStartupCommand(startupOs),
    // 命令与令牌同属凭据面，仅管理员请求；草稿预览沿用 master 的有效性闸门。
    enabled: isAdmin && Boolean(startupTarget) && startupConfigured && !startupConfigError && startupPreviewValid,
    retry: false,
  });
  const [createdLaunchMode, setCreatedLaunchMode] = useState<ExecutorLaunchMode>('foreground');
  const issuedCommandQuery = useQuery({
    queryKey: ['executor-launch-command', tokenResult?.id, startupOs, createdLaunchMode],
    queryFn: () => buildExecutorLaunchCommand(tokenResult!.id, { os: startupOs, mode: createdLaunchMode }),
    enabled: isAdmin && Boolean(tokenResult?.token),
    retry: false,
  });

  const createMut = useMutation({
    // 启动配置随执行器一并落库，响应回显的就是数据库保存下来的值
    mutationFn: ({ agentId, name, clientKind: kind, memoryMode, maxConcurrentDispatches, model, reasoningEffort, contextWindow }:
      CreateExecutorFormValues) =>
      createExecutor(agentId, { name, clientKind: kind, memoryMode, maxConcurrentDispatches, model, reasoningEffort, contextWindow }),
    onSuccess: (data) => {
      setTokenResult(data);
      setCreateModalOpen(false);
      form.resetFields();
      queryClient.invalidateQueries({ queryKey: ['executors'] });
    },
  });

  const deleteMut = useMutation({
    mutationFn: deleteExecutor,
    onSuccess: () => {
      message.success(i18n.t('executor:deleted'));
      queryClient.invalidateQueries({ queryKey: ['executors'] });
    },
  });

  const handleCreate = async () => {
    runAccessCommand('READ_WRITE', t('create'), async () => {
      let values: CreateExecutorFormValues;
      try {
        values = await form.validateFields();
      } catch {
        return;
      }
      createMut.mutate(values);
    }, 'EXECUTOR');
  };

  const startupQoderOptions = qoderOptionsForModel(startupQoderModel);
  const qoderModelOptions = qoderOptionsForModel(qoderModel);

  let startupPreview = '';
  if (startupTarget) {
    if (startupConfigError) {
      startupPreview = startupConfigError;
    } else if (!startupConfig) {
      startupPreview = t('configLoading');
    } else if (!startupConfigured) {
      startupPreview = t('unconfiguredPreview');
    } else if (!isAdmin) {
      startupPreview = t('commandAdminOnly');
    } else if (!startupPreviewValid) {
      startupPreview = t('previewIncomplete');
    } else if (startupCommandQuery.isError) {
      startupPreview = launchErrorMessage(startupCommandQuery.error, i18n.t('executor:commandFailed'));
    } else {
      startupPreview = startupCommandQuery.data?.command ?? t('commandLoading');
    }
  }

  const issuedPreview = issuedCommandQuery.isError
    ? launchErrorMessage(issuedCommandQuery.error, i18n.t('executor:commandFailed'))
    : issuedCommandQuery.data?.command ?? t('commandLoading');

  const handleCopyToken = () => {
    runAccessCommand('ADMIN', t('accessCopyToken'), async () => {
      if (tokenResult?.token) {
        const copied = await copyTextToClipboard(tokenResult.token);
        if (copied) {
          message.success(i18n.t('executor:tokenClipboard'));
        } else {
          message.warning(i18n.t('executor:copyFailed'));
        }
      }
    });
  };

  const handleRevealToken = (id: number) => {
    runAccessCommand('ADMIN', t('accessViewToken'), async () => {
      if (revealedTokens[id]) {
        setRevealedTokens((prev) => { const next = { ...prev }; delete next[id]; return next; });
        return;
      }
      setLoadingTokenId(id);
      try {
        const token = await getExecutorToken(id);
        setRevealedTokens((prev) => ({ ...prev, [id]: token }));
      } catch {
        message.error(i18n.t('executor:tokenUnavailableRecreate'));
      } finally {
        setLoadingTokenId(null);
      }
    });
  };

  const handleCopyRowToken = (id: number) => {
    runAccessCommand('ADMIN', t('accessCopyToken'), async () => {
      let token = revealedTokens[id];
      if (!token) {
        try {
          token = await getExecutorToken(id);
          setRevealedTokens((prev) => ({ ...prev, [id]: token }));
        } catch {
          message.error(i18n.t('executor:tokenUnavailable'));
          return;
        }
      }
      const copied = await copyTextToClipboard(token);
      if (copied) {
        message.success(i18n.t('executor:tokenCopied'));
      } else {
        message.warning(i18n.t('executor:copyFailed'));
      }
    });
  };

  // 生成与复制都只带输出格式选项，任何启动值都由服务端从数据库读取后拼进命令
  const copyLaunchCommand = async (executorId: number, shell?: DebugShell, mode?: ExecutorLaunchMode): Promise<boolean> => {
    let command: string;
    try {
      const vo = await buildExecutorLaunchCommand(
        executorId,
        shell ? { debug: true, shell } : { os: startupOs, mode },
      );
      command = vo.command;
    } catch (error) {
      message.error(launchErrorMessage(error, i18n.t('executor:commandFailed')));
      return false;
    }
    const copied = await copyTextToClipboard(command);
    if (!copied) {
      message.warning(i18n.t('executor:copyFailed'));
      return false;
    }
    if (shell) {
      message.warning(i18n.t('executor:debugCopied', { shell: SHELL_LABEL[shell] }), 6);
    } else {
      message.success(i18n.t('executor:commandCopied'));
    }
    return true;
  };

  const loadStartupConfig = async (record: ExecutorVO, fillForm: boolean) => {
    let config: ExecutorLaunchConfig;
    try {
      config = await getExecutorLaunchConfig(record.id);
    } catch (error) {
      // 读不到数据库配置就不能生成命令，如实告知原因并阻止复制（禁止回退默认值）
      setStartupConfig(null);
      setStartupConfigError(error);
      return null;
    }
    setStartupConfigError(null);
    setStartupConfig(config);
    if (!fillForm) return config;
    if (!isLaunchConfigured(config)) return config;
    if (isQoderClientKind(record.clientKind)) {
      startupForm.setFieldsValue({
        memoryMode: config.memoryMode ?? 'platform',
        maxConcurrentDispatches: config.maxConcurrentDispatches ?? 5,
        model: config.model ?? undefined,
        reasoningEffort: config.reasoningEffort ?? undefined,
        contextWindow: config.contextWindow ?? undefined,
      });
      setStartupModelInvalid(!config.model);
    } else {
      startupForm.setFieldsValue({ memoryMode: config.memoryMode ?? 'platform', maxConcurrentDispatches: config.maxConcurrentDispatches ?? 5 });
    }
    return config;
  };

  const openStartupModal = (record: ExecutorVO) => {
    runAccessCommand('READ_WRITE', t('accessStartup'), async () => {
      startupForm.resetFields();
      setRestartPolicyOpen(false);
      setStartupMode('foreground');
      setStartupTarget(record);
      setCommandDraft(null);
      setCommandRequest(null);
      setStartupConfig(null);
      setStartupConfigError(null);
      setStartupModelInvalid(false);
      await loadStartupConfig(record, true);
    }, 'EXECUTOR'); // 配置表单入口由读写级 + EXECUTOR 只读域治理；命令预览/复制/应用仍要求管理员。
  };

  const persistStartupConfig = async (record: ExecutorVO): Promise<ExecutorLaunchConfig | null> => {
    let values: StartupFormValues;
    try {
      values = await startupForm.validateFields();
    } catch {
      // 表单本身不完整（例如模型已不可用被置空），此时既不能保存也不能生成命令
      return null;
    }
    let config = startupConfig;
    if (!config) {
      // 打开弹窗时读取失败过：保存前必须补读到配置与版本号，绝不猜测版本
      config = await loadStartupConfig(record, false);
    }
    if (!config) {
      message.error(i18n.t('executor:configReadSaveFailed'));
      return null;
    }
    const version = config.version;
    const kindMissing = isClientKindMissing(record.clientKind);
    // 缺类型执行器按表单补选的类型决定模型参数是否提交；正常执行器沿用记录里的类型
    const effectiveKind = kindMissing ? values.clientKind : record.clientKind;
    const isQoder = isQoderClientKind(effectiveKind);
    setStartupSaving(true);
    try {
      const saved = await updateExecutorLaunchConfig(record.id, {
        ...(kindMissing ? { clientKind: values.clientKind } : {}),
        memoryMode: values.memoryMode,
        maxConcurrentDispatches: values.maxConcurrentDispatches,
        ...(isQoder ? {
          model: values.model,
          reasoningEffort: values.reasoningEffort,
          contextWindow: values.contextWindow,
        } : {}),
        version,
      });
      setStartupConfig(saved);
      setStartupModelInvalid(false);
      if (kindMissing && values.clientKind) {
        // 类型补齐成功：更新弹窗内的记录并刷新列表，去掉「类型缺失」标记
        setStartupTarget({ ...record, clientKind: values.clientKind });
        queryClient.invalidateQueries({ queryKey: ['executors'] });
      }
      // 保存成功后预览必须重新向服务端索取，确保展示的就是数据库里的配置
      await queryClient.invalidateQueries({ queryKey: ['executor-launch-command', record.id] });
      return saved;
    } catch (error) {
      if (error instanceof ApiError && error.code === '17005') {
        message.error(i18n.t('executor:configChanged'));
        // 同步最新配置与版本号，用户可以直接重试；表单里刚选的值保持不动
        await loadStartupConfig(record, false);
      } else if (error instanceof ApiError && error.code === '17006') {
        // 选择的模型已下线：置空并提示重新选择，不自动回退默认值
        setStartupModelInvalid(true);
        startupForm.setFieldsValue({ model: undefined });
        message.error(i18n.t('executor:modelUnavailable'));
      } else {
        message.error(launchErrorMessage(error, i18n.t('executor:configSaveFailed')));
      }
      return null;
    } finally {
      setStartupSaving(false);
    }
  };

  const editLaunchCommand = () => {
    runAccessCommand('ADMIN', t('accessEditCommand'), async () => {
      if (!startupTarget) return;
      setCommandSaving(true);
      try {
        const command = await requestStartupCommand('posix');
        setCommandDraft(command.command);
      } catch (error) {
        message.error(launchErrorMessage(error, i18n.t('executor:commandFailed')));
      } finally {
        setCommandSaving(false);
      }
    });
  };

  const saveEditedCommand = () => {
    runAccessCommand('ADMIN', t('accessEditCommand'), async () => {
      if (!startupTarget || commandDraft === null || !startupConfig) return;
      setCommandSaving(true);
      try {
        const saved = await saveExecutorLaunchCommand(startupTarget.id, commandDraft, startupConfig.version);
        setStartupConfig(saved);
        setStartupConfigError(null);
        setStartupModelInvalid(false);
        setCommandRequest(null);
        startupForm.setFieldsValue({
          memoryMode: saved.memoryMode, maxConcurrentDispatches: saved.maxConcurrentDispatches ?? 5,
          model: saved.model ?? undefined, reasoningEffort: saved.reasoningEffort ?? undefined,
          contextWindow: saved.contextWindow ?? undefined,
        });
        await queryClient.invalidateQueries({ queryKey: ['executor-launch-command', startupTarget.id] });
        setCommandDraft(null);
        message.success(i18n.t('executor:commandSaved'));
      } catch (error) {
        message.error(launchErrorMessage(error, i18n.t('executor:commandSaveFailed')));
        if (error instanceof ApiError && error.code === '17005') await loadStartupConfig(startupTarget, false);
      } finally {
        setCommandSaving(false);
      }
    });
  };

  const applyStartupCommand = (restartMode: ExecutorRestartMode) => {
    // 在线应用按保存配置重启执行器（launch 载荷不含令牌），由读写级 + EXECUTOR 只读域治理。
    runAccessCommand('READ_WRITE', t('accessApplyCommand'), async () => {
      if (!startupTarget || commandApplyBlockReason || commandSaving || startupSaving) return;
      setRestartPolicyOpen(false);
      const saved = await persistStartupConfig(startupTarget);
      if (!saved) return;
      setCommandSaving(true);
      try {
        const result = await applyExecutorLaunchCommand(startupTarget.id, saved.version, restartMode);
        setCommandRequest(result);
        queryClient.invalidateQueries({ queryKey: ['executors'] });
        if (result?.status === 'FAILED') message.error(i18n.t('executor:commandSavedApplyFailed'));
        else message.success(i18n.t('executor:commandApplyRequested'));
      } catch (error) {
        message.error(i18n.t('executor:commandSavedApplyFailedWithReason', {
          reason: launchErrorMessage(error, i18n.t('executor:restartRequestFailed')),
        }));
      } finally {
        setCommandSaving(false);
      }
    }, 'EXECUTOR');
  };

  const saveFromStartupModal = () => {
    runAccessCommand('READ_WRITE', t('accessSaveConfig'), async () => {
      if (!startupTarget) return;
      if (await persistStartupConfig(startupTarget)) {
        message.success(i18n.t('executor:configSaved'));
      }
    }, 'EXECUTOR');
  };

  const copyFromStartupModal = (shell?: DebugShell) => {
    runAccessCommand('ADMIN', shell ? t('accessCopyDebug') : t('accessCopyStartup'), async () => {
      if (!startupTarget) return;
      // 先把当前选择写回服务端，再让服务端按数据库配置生成命令，页面与 MCP 共享同一份配置
      if (!(await persistStartupConfig(startupTarget))) return;
      if (await copyLaunchCommand(startupTarget.id, shell, startupMode)) {
        setStartupTarget(null);
      }
    });
  };

  const copyFromTokenModal = (shell?: DebugShell) => {
    runAccessCommand('ADMIN', shell ? t('accessCopyDebug') : t('accessCopyStartup'), async () => {
      if (!tokenResult) return;
      // 复制必须带上创建弹窗当前选中的运行方式,否则选了「系统服务」复制的仍是前台命令
      await copyLaunchCommand(tokenResult.id, shell, createdLaunchMode);
    });
  };

  const detail = executors.find((executor) => executor.id === detailId);
  const startupExecutor = executors.find((executor) => executor.id === startupTarget?.id) ?? startupTarget;
  const commandRestart = commandRequest && startupExecutor?.restart?.requestId === commandRequest.requestId
    ? startupExecutor.restart : commandRequest ?? startupExecutor?.restart;
  const commandApplyBlockReason = startupOs === 'windows' ? t('onlineCommandWindowsUnsupported')
    : startupExecutor?.status !== 'ONLINE' ? t('onlineCommandOffline')
    : !startupExecutor.launchCommandSupported ? t('onlineCommandUnsupported')
    : !startupExecutor.restartPolicySupported ? t('onlineRestartPolicyUnsupported')
      : [commandRestart, startupExecutor.restart].some((request) => request && ['REQUESTED', 'WAITING_IDLE', 'UPDATING', 'RESTARTING'].includes(request.status))
        ? t('onlineCommandPending') : null;
  const formatTime = (value?: string | null) => value ? formatDateTime(value) : t('notReported');
  const restartLabels: Record<string, string> = { REQUESTED: t('restartWaiting'), WAITING_IDLE: t('restartWaitingIdle'), UPDATING: t('restartUpdating'), RESTARTING: t('restartRunning'), COMPLETED: t('restartSucceeded'), FAILED: t('restartFailed'), TIMED_OUT: t('restartTimedOut') };
  const columns: ColumnsType<ExecutorVO> = [
    { title: t('executor'), dataIndex: 'name', align: 'left', width: 180, render: (name, record) => (
      <div><Button type="link" style={{ padding: 0, maxWidth: '100%' }} onClick={() => setDetailId(record.id)}>{name}</Button>
        <div style={{ color: 'var(--cf-muted)', fontSize: 12 }}>ID {record.id}</div></div>
    ) },
    { title: t('owner'), width: 180, render: (_, record) => (
      <div><div>{record.agentName ?? '-'}</div><SquadTags squadIds={record.squadIds} squadNames={record.squadNames} /></div>
    ) },
    { title: t('machine'), width: 170, render: (_, record) => (
      // 主机名是客户端自报值，老客户端为 null；两个都没有时退成单个「未上报」。
      record.lastConnectHostname || record.lastConnectIp
        ? <div><div>{record.lastConnectHostname ?? t('notReported')}</div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{record.lastConnectIp ?? t('notReported')}</Typography.Text></div>
        : t('notReported')
    ) },
    { title: t('clientVersion'), width: 150, render: (_, record) => (
      // 历史数据的 clientKind 可为 null：明确标记「类型缺失」，绝不留空白让操作者误判客户端类型。
      <div><div>{record.clientKind
        ? (Object.prototype.hasOwnProperty.call(clientKindMap, record.clientKind)
          ? i18n.t(`executor:${clientKindMap[record.clientKind].labelKey}`) : record.clientKind)
        : <Tag color="warning">{t('missingClientKind')}</Tag>}</div>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>{record.version ?? t('versionNotReported')}</Typography.Text>
        <div><Typography.Text type="secondary" style={{ fontSize: 12 }}>{record.modelName ?? record.model ?? t('modelNotReported')}</Typography.Text></div>
        {record.upgradeAvailable && <div style={{ marginTop: 2 }}><Tag color="orange">{t('newVersion')}</Tag></div>}</div>
    ) },
    { title: t('status'), dataIndex: 'status', width: 120, render: (status: string, record) => (
      <div><Badge status={Object.prototype.hasOwnProperty.call(statusBadge, status) ? statusBadge[status].status : 'default'} text={Object.prototype.hasOwnProperty.call(statusBadge, status) ? t(statusBadge[status].key) : status} />
        {record.restart && <div style={{ fontSize: 12 }}>{Object.prototype.hasOwnProperty.call(restartLabels, record.restart.status) ? restartLabels[record.restart.status] : record.restart.status}</div>}
        {record.update && <div style={{ marginTop: 2 }}>
          <Tooltip title={record.update.lastError ?? undefined}>
            <Tag color={updateStatusColor(record.update.status)}>{updateStatusLabel(record.update.status)}</Tag>
          </Tooltip>
        </div>}</div>
    ) },
    { title: t('lastStarted'), dataIndex: 'lastStartedAt', width: 170, render: (value: string | null) => formatTime(value) },
    {
      title: t('actions'), width: 340, fixed: 'right',
      render: (_: unknown, record: ExecutorVO) => {
        // 拒绝升级时只置灰按钮的话操作者不知道该做什么，所以把原因放在 Tooltip 里。
        const blockReason = upgradeBlockReason(record);
        return (
          <Space size={0}>
            <Button type="link" size="small" onClick={() => setDetailId(record.id)}>{t('details')}</Button>
            <Button type="link" size="small" icon={<CodeSandboxOutlined aria-hidden="true" />}
              onClick={() => openStartupModal(record)}>{t('startup')}</Button>
            {/* 禁用状态的 button 不触发鼠标事件，包一层 span 才能让 Tooltip 浮出拒绝原因。 */}
            <Tooltip title={blockReason ?? undefined}>
              <span>
                <Button type="link" size="small" icon={<CloudUploadOutlined aria-hidden="true" />} disabled={blockReason !== null}
                  loading={updateMut.isPending && updateMut.variables === record.id}
                  onClick={() => runAccessCommand('READ_WRITE', t('accessUpdate'), () => updateMut.mutate(record.id), 'EXECUTOR')}>{t('update')}</Button>
              </span>
            </Tooltip>
            <Popconfirm
              title={t('confirmDelete')}
              open={deleteConfirmId === record.id}
              onOpenChange={(open) => {
                if (!open) {
                  setDeleteConfirmId(null);
                  return;
                }
                runAccessCommand('READ_WRITE', t('accessDelete'), () => setDeleteConfirmId(record.id), 'EXECUTOR');
              }}
              onConfirm={() => {
                setDeleteConfirmId(null);
                runAccessCommand('READ_WRITE', t('accessDelete'), () => deleteMut.mutate(record.id), 'EXECUTOR');
              }}
            >
              <Button type="link" size="small" danger icon={<DeleteOutlined aria-hidden="true" />}>{t('delete')}</Button>
            </Popconfirm>
          </Space>
        );
      },
    },
  ];

  return (
    <div>
      <ExecutorDetailDrawer
        detail={detail}
        open={detailId !== null}
        onClose={() => { if (detailId !== null) setRevealedTokens((prev) => { const next = { ...prev }; delete next[detailId]; return next; }); setDetailId(null); }}
        revealedTokens={revealedTokens}
        loadingTokenId={loadingTokenId}
        restarting={restartMut.isPending}
        onRevealToken={handleRevealToken}
        onCopyToken={handleCopyRowToken}
        onRestart={(update) => runAccessCommand('READ_WRITE', update ? t('accessUpdateRestart') : t('accessRestart'), () => restartMut.mutate({ id: detail!.id, update }), 'EXECUTOR')}
      />
      <Card className="cf-content-card" title={<PageHeading title={t('title')} extra={
        <span className="exec-status-strip" role="status">
          <span className="exec-status-strip__item"><i data-tone="online" aria-hidden="true" />{t('onlineCount', { count: executors.filter((e) => e.status === 'ONLINE').length })}</span>
          <span className="exec-status-strip__item"><i data-tone="busy" aria-hidden="true" />{t('busyCount', { count: executors.filter((e) => e.status === 'BUSY').length })}</span>
          <span className="exec-status-strip__item"><i data-tone="offline" aria-hidden="true" />{t('offlineCount', { count: executors.filter((e) => e.status === 'OFFLINE').length })}</span>
        </span>
      } />}
        extra={
          <Space wrap>
            {/* Tooltip 要靠子元素的 ref 定位，Space 是组件不是 DOM 节点，包一层 span 最稳妥。 */}
            <Tooltip title={t('autoUpdateHelp')}>
              <span>
                <Space size={4}>
                  <Typography.Text type="secondary">{t('autoUpdate')}</Typography.Text>
                  {/* 兜底取服务端的默认值「开启」：配置还没回来或这次读取失败时，显示「关闭」会让
                      操作者以为自动升级被谁关掉了，而服务端此刻仍在自动下发。 */}
                  {autoUpdateQuery.data?.executorAutoUpdateEnabled ?? true
                    ? <Tag color="success">{t('enabled')}</Tag>
                    : <Tag>{t('disabled')}</Tag>}
                </Space>
              </span>
            </Tooltip>
            <Tooltip title={upgradableExecutors.length === 0 ? t('noneUpgradable') : undefined}>
              <span>
                <Button icon={<CloudUploadOutlined aria-hidden="true" />} disabled={upgradableExecutors.length === 0}
                  loading={updateAllMut.isPending}
                  onClick={() => runAccessCommand('READ_WRITE', t('accessBulkUpdate'), () => setUpdateAllOpen(true), 'EXECUTOR')}>{t('bulkUpdate')}</Button>
              </span>
            </Tooltip>
            <Button type="primary" icon={<PlusOutlined aria-hidden="true" />} onClick={() => {
              runAccessCommand('READ_WRITE', t('create'), () => {
                form.resetFields();
                const defaults = qoderOptionsForModel('auto');
                form.setFieldsValue({
                  agentId: selectedAgentId,
                  clientKind: 'QODER_CLI',
                  memoryMode: 'platform',
                  model: 'auto',
                  reasoningEffort: defaults.defaultReasoningEffort,
                  contextWindow: defaults.defaultContextWindow,
                });
                setCreateModalOpen(true);
              }, 'EXECUTOR');
            }}>
              {t('create')}
            </Button>
          </Space>
        }
      >
        <Toolbar filters={<>
          <SquadFilterBar
            options={squadOptions}
            value={squadFilter}
            onChange={setSquadFilter}
            grouped={squadGrouped}
            onGroupedChange={(next) => {
              setSquadGrouped(next);
              writeViewPreference(EXECUTORS_VIEW_STORAGE_KEY, next ? 'squad' : 'agent');
            }}
            loading={squadsLoading}
            listLabel={squadT('groupByAgent')}
            groupLabel={squadT('groupBySquad')}
          />
          <Select aria-label={t('selectAgent')} placeholder={t('selectAgent')} style={{ width: 180 }} value={selectedAgentId}
            onChange={setSelectedAgentId} allowClear showSearch optionFilterProp="label"
            options={agents.map(a => ({ value: a.id, label: a.name }))}
          />
        </>} />
        {squadGrouped ? (
          squadGroups.length > 0 ? (
            <Collapse
              activeKey={expandedSquadKeys}
              onChange={handleSquadCollapseChange}
              items={squadGroups.map((group) => ({
                key: group.key,
                label: <ExecutorSquadGroupHeader group={group} />,
                children: (
                  <div className="cfds-table-wrap">
                    <Table
                      rowKey="id"
                      columns={columns}
                      dataSource={group.items}
                      pagination={false}
                      scroll={{ x: 1310 }}
                    />
                  </div>
                ),
              }))}
            />
          ) : (
            <div className="cfds-table-wrap">
              <Table
                rowKey="id"
                columns={columns}
                dataSource={visibleExecutors}
                loading={isLoading}
                pagination={false}
                scroll={{ x: 1310 }}
              />
            </div>
          )
        ) : executorGroups.length > 0 ? (
          <Collapse
            activeKey={expandedAgentKeys}
            onChange={handleAgentCollapseChange}
            items={executorGroups.map((group) => ({
              key: group.key,
              label: <ExecutorGroupHeader group={group} />,
              children: (
                <div className="cfds-table-wrap">
                  <Table
                    rowKey="id"
                    columns={columns}
                    dataSource={group.executors}
                    pagination={false}
                    scroll={{ x: 1310 }}
                  />
                </div>
              ),
            }))}
          />
        ) : (
          <div className="cfds-table-wrap">
            <Table
              rowKey="id"
              columns={columns}
              dataSource={visibleExecutors}
              loading={isLoading}
              pagination={false}
              scroll={{ x: 1310 }}
            />
          </div>
        )}
      </Card>

      <ExecutorCreateModal
        open={createModalOpen}
        form={form}
        agents={agents}
        agentOptionLabel={agentOptionLabel}
        clientKind={clientKind}
        modelOptions={createQoderModelOptions}
        qoderOptions={qoderModelOptions}
        submitting={createMut.isPending}
        onOk={handleCreate}
        onCancel={() => setCreateModalOpen(false)}
      />

      <ExecutorStartupModal
        target={startupTarget}
        form={startupForm}
        configError={startupConfigError}
        hasConfig={Boolean(startupConfig)}
        kindMissing={startupKindMissing}
        configured={startupConfigured}
        modelInvalid={startupModelInvalid}
        saving={startupSaving}
        commandSaving={commandSaving}
        commandDraft={commandDraft}
        preview={startupPreview}
        unsavedPreview={Boolean(startupPreviewConfig)}
        previewValid={startupPreviewValid}
        mode={startupMode}
        onModeChange={setStartupMode}
        os={startupOs}
        onOsChange={setStartupOs}
        modelOptions={startupQoderModelOptions}
        qoderOptions={startupQoderOptions}
        qoderProvider={Boolean(startupQoderProvider)}
        applyBlockReason={commandApplyBlockReason}
        commandRestart={commandRestart}
        restartLabels={restartLabels}
        onClose={() => { setRestartPolicyOpen(false); setStartupTarget(null); }}
        onCancel={() => setStartupTarget(null)}
        onSaveConfig={saveFromStartupModal}
        onCopy={copyFromStartupModal}
        onEditCommand={editLaunchCommand}
        onSaveCommand={saveEditedCommand}
        onCommandDraftChange={setCommandDraft}
        onBackToParameters={() => setCommandDraft(null)}
        onOpenRestartPolicy={() => setRestartPolicyOpen(true)}
      />

      <ExecutorRestartPolicyModal
        open={restartPolicyOpen && Boolean(startupTarget)}
        applyBlockReason={commandApplyBlockReason}
        busy={commandSaving || startupSaving}
        onCancel={() => setRestartPolicyOpen(false)}
        onApply={applyStartupCommand}
      />

      <ExecutorCreatedTokenModal
        result={tokenResult}
        startupOs={startupOs}
        onStartupOsChange={setStartupOs}
        launchMode={createdLaunchMode}
        onLaunchModeChange={setCreatedLaunchMode}
        onPreview={() => { void issuedCommandQuery.refetch(); }}
        issuedPreview={issuedPreview}
        onCopyToken={handleCopyToken}
        onCopyCommand={copyFromTokenModal}
        onClose={() => setTokenResult(null)}
      />

      {/* 批量升级确认：先把影响面和目标版本说清楚，别让一次点击悄悄重启整个机群。 */}
      <ExecutorBulkUpdateConfirmModal
        open={updateAllOpen}
        targetVersion={autoUpdateQuery.data?.targetVersion}
        count={upgradableExecutors.length}
        autoUpdateEnabled={autoUpdateQuery.data?.executorAutoUpdateEnabled}
        pending={updateAllMut.isPending}
        onIssue={() => updateAllMut.mutate()}
        onClose={() => setUpdateAllOpen(false)}
      />

      <ExecutorBulkIssuedModal result={updateAllResult} onClose={() => setUpdateAllResult(null)} />
    </div>
  );
}
