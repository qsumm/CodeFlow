import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { Alert, Button, Checkbox, Collapse, Empty, InputNumber, message, Modal, Select, Space, Spin, Tag, Typography } from 'antd';
import { CloseOutlined, CopyOutlined } from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { ApiError } from '@/shared/types/common';
import { getAgentRoleLabel } from '@/features/agent/constants';
import { copyTextToClipboard } from '@/shared/lib/clipboard';
import { provisionSquadExecutors } from './api';
import type { Squad, SquadExecutorSummary, SquadMember, SquadProvisionItem, SquadProvisionRequest, SquadProvisionResult } from './api';

// 与后端 BatchProvisionSquadExecutorsRequest 的缺省值一致：等于缺省的选项不随请求体发送
const PROVISION_DEFAULTS = { clientKind: 'QODER_CLI', memoryMode: 'platform', maxConcurrentDispatches: 5 };

const PROVISION_CLIENT_KINDS = ['QODER_CLI', 'QODER_CN_CLI', 'CLAUDE_CODE', 'GEMINI_CLI', 'CODEX_CLI', 'CURSOR_CLI'] as const;

const PROVISION_STATUS_COLORS: Record<SquadProvisionItem['status'], string> = {
  CREATED: 'success',
  SKIPPED_EXISTS: 'default',
  FAILED: 'error',
};

// 结果行的分支判定放在 JSX 之外的辅助函数里，模板里只留布尔值
function provisionStatusFlags(status: SquadProvisionItem['status']) {
  return {
    created: status === 'CREATED',
    skipped: status === 'SKIPPED_EXISTS',
    failed: status === 'FAILED',
  };
}

interface SquadProvisionModalProps {
  /** null 表示弹窗关闭；复用详情面板已拉好的成员与执行器列表，不另发请求。 */
  squad: Squad | null;
  members: SquadMember[];
  membersLoading: boolean;
  executors: SquadExecutorSummary[];
  onClose: () => void;
}

export function SquadProvisionModal({ squad, members, membersLoading, executors, onClose }: SquadProvisionModalProps) {
  const { t } = useTranslation('squad');
  const squadId = squad?.id ?? null;
  const open = squadId !== null;

  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [clientKind, setClientKind] = useState<string>(PROVISION_DEFAULTS.clientKind);
  const [memoryMode, setMemoryMode] = useState<string>(PROVISION_DEFAULTS.memoryMode);
  const [maxConcurrentDispatches, setMaxConcurrentDispatches] = useState<number>(PROVISION_DEFAULTS.maxConcurrentDispatches);
  const [result, setResult] = useState<SquadProvisionResult | null>(null);

  // 成员名下已有的执行器：详情面板的 executors 汇总按 agentId 归属，已有的成员行不可再配
  const existingExecutorIdByAgent = useMemo(() => {
    const byAgent = new Map<number, number>();
    for (const executor of executors) {
      if (executor.agentId != null && !byAgent.has(executor.agentId)) {
        byAgent.set(executor.agentId, executor.id);
      }
    }
    return byAgent;
  }, [executors]);

  // 打开弹窗（或成员清单首次就绪）时默认勾选「没有运行器」的成员；已有运行器的成员行灰化标注
  useEffect(() => {
    if (!squadId || members.length === 0 || result) return;
    setSelected(new Set(members
      .filter((member) => !existingExecutorIdByAgent.has(member.agentId))
      .map((member) => member.agentId)));
    setResult(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [squadId, members.length, membersLoading]);

  const provisionMut = useMutation({
    mutationFn: (payload: SquadProvisionRequest) => provisionSquadExecutors(squadId as number, payload),
    onSuccess: (data) => setResult(data),
    onError: (error) => message.error(
      i18n.t('squad:provisionSubmitFailed', {
        reason: error instanceof ApiError && error.message ? error.message : i18n.t('squad:provisionSubmitUnknown'),
      }),
    ),
  });

  const toggleMember = (agentId: number, checked: boolean) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (checked) next.add(agentId); else next.delete(agentId);
      return next;
    });
  };

  const submit = () => {
    if (!squadId || selected.size === 0) return;
    // 等于后端缺省的选项不发送，由服务端按自己的缺省值处理
    const payload: SquadProvisionRequest = { agentIds: Array.from(selected) };
    if (clientKind !== PROVISION_DEFAULTS.clientKind) payload.clientKind = clientKind;
    if (memoryMode !== PROVISION_DEFAULTS.memoryMode) payload.memoryMode = memoryMode;
    if (maxConcurrentDispatches !== PROVISION_DEFAULTS.maxConcurrentDispatches) {
      payload.maxConcurrentDispatches = maxConcurrentDispatches;
    }
    provisionMut.mutate(payload);
  };

  const statusLabel = (status: SquadProvisionItem['status']) => status === 'CREATED' ? t('provisionStatusCreated')
    : status === 'SKIPPED_EXISTS' ? t('provisionStatusSkipped') : t('provisionStatusFailed');

  // 复制全部拼成可直接粘贴执行的 shell 脚本：每条命令一行，上方 # 注释成员名
  const copyAllScript = async () => {
    if (!result) return;
    const script = result.items
      .filter((item) => item.status === 'CREATED' && item.command)
      .map((item) => `# ${item.agentName}\n${item.command}`)
      .join('\n');
    const copied = await copyTextToClipboard(script);
    if (copied) {
      message.success(i18n.t('squad:provisionCopied'));
    } else {
      message.warning(i18n.t('squad:provisionCopyFailed'));
    }
  };

  const copyCommand = async (command: string) => {
    const copied = await copyTextToClipboard(command);
    if (copied) {
      message.success(i18n.t('squad:provisionCopied'));
    } else {
      message.warning(i18n.t('squad:provisionCopyFailed'));
    }
  };

  return (
    <Modal
      closable={{ 'aria-label': t('close'), closeIcon: <CloseOutlined aria-hidden="true" /> }}
      title={t('provisionTitle', { name: squad?.name ?? '' })}
      open={open}
      onCancel={onClose}
      width={680}
      footer={result ? (
        <Button type="primary" onClick={onClose}>{t('copyDone')}</Button>
      ) : (
        <Space>
          <Button onClick={onClose}>{t('cancel')}</Button>
          <Button type="primary" disabled={selected.size === 0} loading={provisionMut.isPending}
            onClick={submit}>{t('provisionSubmit')}</Button>
        </Space>
      )}
    >
      {membersLoading ? <Spin aria-label={t('loading')} /> : members.length === 0 ? (
        <Empty description={t('emptyMembers')} />
      ) : result ? (
        <div>
          <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 12 }}>
            <Typography.Text strong>{t('provisionResultTitle')}</Typography.Text>
            <Button icon={<CopyOutlined aria-hidden="true" />} onClick={copyAllScript}>{t('provisionCopyAll')}</Button>
          </Space>
          <div style={{ display: 'grid', gap: 10 }}>
            {result.items.map((item) => {
              const { created, skipped, failed } = provisionStatusFlags(item.status);
              return (
                <div key={item.agentId} style={{
                  border: '1px solid var(--cf-border)', borderRadius: 8, padding: 12, background: 'var(--cf-panel)',
                }}>
                  <Space size={8} wrap>
                    <Tag color={PROVISION_STATUS_COLORS[item.status]}>{statusLabel(item.status)}</Tag>
                    <Typography.Text strong>{item.agentName}</Typography.Text>
                    {skipped && (
                      <Typography.Text type="secondary">
                        {t('provisionHasExecutor', { id: item.existingExecutorId })}
                      </Typography.Text>
                    )}
                  </Space>
                  {failed && item.error && (
                    <Typography.Paragraph type="danger" style={{ marginTop: 6, marginBottom: 0 }}>{item.error}</Typography.Paragraph>
                  )}
                  {created && item.command && (
                    <div style={{ marginTop: 8 }}>
                      <div role="region" aria-label={t('provisionCommand')} style={{
                        padding: '8px 12px', background: 'var(--cf-raised)', borderRadius: 6,
                        fontFamily: 'monospace', fontSize: 13, wordBreak: 'break-all', lineHeight: 1.6,
                      }}>{item.command}</div>
                      <Button size="small" icon={<CopyOutlined aria-hidden="true" />} style={{ marginTop: 6 }}
                        onClick={() => copyCommand(item.command as string)}>{t('provisionCopy')}</Button>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      ) : (
        <div>
          <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>{t('provisionHelp')}</Typography.Paragraph>
          <div style={{ display: 'grid', gap: 8 }}>
            {members.map((member) => {
              const existingExecutorId = existingExecutorIdByAgent.get(member.agentId);
              return (
                <div key={member.agentId} style={{
                  display: 'flex', alignItems: 'center', gap: 10, padding: '8px 12px',
                  border: '1px solid var(--cf-border)', borderRadius: 8, background: 'var(--cf-panel)',
                  opacity: existingExecutorId ? 0.6 : 1,
                }}>
                  <Checkbox
                    aria-label={member.agentName}
                    checked={selected.has(member.agentId)}
                    disabled={Boolean(existingExecutorId)}
                    onChange={(event) => toggleMember(member.agentId, event.target.checked)}
                  />
                  <Typography.Text strong>{member.agentName}</Typography.Text>
                  <Tag>{getAgentRoleLabel(member.roleCode, member.roleName) || t('roleUnset')}</Tag>
                  <span style={{ marginLeft: 'auto' }}>
                    {existingExecutorId
                      ? <Tag>{t('provisionHasExecutor', { id: existingExecutorId })}</Tag>
                      : <Tag color="warning">{t('provisionMissingExecutor')}</Tag>}
                  </span>
                </div>
              );
            })}
          </div>
          <Collapse ghost items={[{
            key: 'advanced',
            label: t('provisionAdvanced'),
            children: (
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12 }}>
                <div style={{ display: 'grid', gap: 4 }}>
                  <Typography.Text type="secondary">{t('provisionClientKind')}</Typography.Text>
                  <Select aria-label={t('provisionClientKind')} value={clientKind} onChange={setClientKind}
                    // 客户端类型与记忆模式的展示文案沿用 executor 命名空间，避免两处漂移
                    options={PROVISION_CLIENT_KINDS.map((kind) => ({ value: kind, label: i18n.t(`executor:clientKind${kind}`) }))} />
                </div>
                <div style={{ display: 'grid', gap: 4 }}>
                  <Typography.Text type="secondary">{t('provisionMemoryMode')}</Typography.Text>
                  <Select aria-label={t('provisionMemoryMode')} value={memoryMode} onChange={setMemoryMode}
                    options={[
                      { value: 'platform', label: i18n.t('executor:platformMemory') },
                      { value: 'provider-local', label: i18n.t('executor:localMemory') },
                      { value: 'none', label: i18n.t('executor:noMemory') },
                    ]} />
                </div>
                <div style={{ display: 'grid', gap: 4 }}>
                  <Typography.Text type="secondary">{t('provisionConcurrency')}</Typography.Text>
                  <InputNumber aria-label={t('provisionConcurrency')} min={1} max={10} precision={0}
                    value={maxConcurrentDispatches}
                    onChange={(value) => setMaxConcurrentDispatches(Number(value ?? PROVISION_DEFAULTS.maxConcurrentDispatches))} />
                </div>
              </div>
            ),
          }]} style={{ marginTop: 4 }} />
          {provisionMut.isError && (
            <Alert type="error" showIcon style={{ marginTop: 8 }} message={
              i18n.t('squad:provisionSubmitFailed', {
                reason: provisionMut.error instanceof ApiError && provisionMut.error.message
                  ? provisionMut.error.message : i18n.t('squad:provisionSubmitUnknown'),
              })
            } />
          )}
        </div>
      )}
    </Modal>
  );
}
