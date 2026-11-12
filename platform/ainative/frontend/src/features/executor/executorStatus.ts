import { i18n } from '@/shared/i18n';
import type { ExecutorUpdateAllResultVO, ExecutorUpdateSkipVO, ExecutorVO } from './api';

/** 重启状态文案:系统生成的说明走本地化,其余保留服务端原文。 */
export function restartMessage(restart: NonNullable<ExecutorVO['restart']>): string {
  if (restart.messageOrigin !== 'SYSTEM') return restart.message;
  switch (restart.status) {
    case 'REQUESTED': return i18n.t('executor:restartSystemRequested');
    case 'FAILED': return i18n.t('executor:restartSystemSendFailed');
    case 'TIMED_OUT': return restart.restartMode ? i18n.t('executor:restartPolicyTimedOut') : i18n.t('executor:restartSystemTimedOut');
    case 'COMPLETED': return i18n.t('executor:restartSystemCompleted');
    default: return restart.message;
  }
}

export function updateSkipReason(skip: ExecutorUpdateSkipVO): string {
  switch (skip.reasonCode) {
    case 'ACTIVE_TASK': return i18n.t('executor:skipActiveTask');
    case 'UNSUPPORTED_CLIENT': return i18n.t('executor:skipUnsupportedClient');
    default: return skip.reason;
  }
}

/**
 * 批量升级结果的一句话总结。只报「成功 N 个」会让操作者漏掉被跳过的机器，所以跳过数
 * 必须出现在总结里，具体原因在结果弹窗中逐条展开。
 */
export function describeUpdateAllResult(result: ExecutorUpdateAllResultVO): string {
  const parts = [i18n.t('executor:bulkTarget', { version: result.targetVersion }), i18n.t('executor:scheduled', { count: result.scheduled })];
  if (result.alreadyUpToDate > 0) parts.push(i18n.t('executor:upToDateCount', { count: result.alreadyUpToDate }));
  if (result.skipped.length > 0) parts.push(i18n.t('executor:skippedCount', { count: result.skipped.length }));
  return parts.join(i18n.t('executor:resultSeparator'));
}
