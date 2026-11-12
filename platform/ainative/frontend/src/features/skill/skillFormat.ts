import { i18n } from '@/shared/i18n';
import { formatDateTime as formatSharedDateTime, formatNumber } from '@/shared/i18n/format';
import type { Skill, SkillConnectionTestResult } from './api';

export const AUTHORIZATION_MASK = '********';

// antd Select 对 null/undefined 值会按空值处理，分类下拉用字符串哨兵表示「未分类」，
// 提交时再换算回显式 null（取消打标）。
export const UNCATEGORIZED = 'none';

export const typeColor: Record<string, string> = {
  MCP: 'blue', SKILL: 'green', PLUGIN: 'orange', HOOK: 'purple',
};

export function skillTypeLabel(type: string): string {
  switch (type) {
    case 'MCP': return i18n.t('skill:types.mcp');
    case 'SKILL': return i18n.t('skill:types.skill');
    case 'PLUGIN': return i18n.t('skill:types.plugin');
    case 'HOOK': return i18n.t('skill:types.hook');
    default: return type;
  }
}

export function accessLabel(record: Skill) {
  if (record.sourceType === 'OSS_ZIP') {
    if (record.type === 'HOOK') return i18n.t('skill:access.hook');
    return i18n.t('skill:access.package');
  }
  if (record.type === 'SKILL') {
    return i18n.t('skill:access.builtin');
  }
  return i18n.t('skill:access.command');
}

/** 与后端 packageRef 的前置校验保持一致：只有存在 packageOssRef 的 OSS_ZIP 才有包可看。 */
export function isPackageSkill(skill: Skill | null): skill is Skill {
  return !!skill && skill.sourceType === 'OSS_ZIP' && !!skill.packageOssRef;
}

export function errorMessage(error: unknown): string {
  return error instanceof Error && error.message ? error.message : i18n.t('skill:errors.operation');
}

export function modifierName(skill: Skill): string {
  return skill.modifierNameKind === 'USER_ID_FALLBACK' && skill.modifierId != null
    ? i18n.t('skill:modifierFallback', { id: skill.modifierId })
    : skill.modifierName || (skill.modifierId ? i18n.t('skill:modifierFallback', { id: skill.modifierId }) : '-');
}

export function formatDateTime(value?: string) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return formatSharedDateTime(value, { hour12: false });
}

export function formatConnectionResult(result: SkillConnectionTestResult) {
  if (result.success) {
    return typeof result.durationMs === 'number'
      ? i18n.t('skill:connection.successDuration', { duration: formatNumber(result.durationMs) })
      : i18n.t('skill:connection.success');
  }
  const code = result.ownedFailureCode;
  const failureKeys = {
    SELECT_RUNTIME: 'connection.failures.SELECT_RUNTIME',
    RUNTIME_UNAVAILABLE: 'connection.failures.RUNTIME_UNAVAILABLE',
    EMPTY_CONFIG: 'connection.failures.EMPTY_CONFIG',
    INVALID_CONFIG_JSON: 'connection.failures.INVALID_CONFIG_JSON',
    SECRET_CRYPTO_UNAVAILABLE: 'connection.failures.SECRET_CRYPTO_UNAVAILABLE',
    CONNECTION_TIMEOUT: 'connection.failures.CONNECTION_TIMEOUT',
    CONNECTION_FAILED: 'connection.failures.CONNECTION_FAILED',
    LEGACY_RESULT_INCOMPATIBLE: 'connection.failures.LEGACY_RESULT_INCOMPATIBLE',
    TEST_INTERRUPTED: 'connection.failures.TEST_INTERRUPTED',
    RUNTIME_WAIT_TIMEOUT: 'connection.failures.RUNTIME_WAIT_TIMEOUT',
    INVALID_RESULT: 'connection.failures.INVALID_RESULT',
    REQUEST_CREATE_FAILED: 'connection.failures.REQUEST_CREATE_FAILED',
    DISPATCH_FAILED: 'connection.failures.DISPATCH_FAILED',
  } as const;
  const reason = code && Object.prototype.hasOwnProperty.call(failureKeys, code)
    && (code !== 'RUNTIME_WAIT_TIMEOUT' || typeof result.ownedFailureArgs?.seconds === 'number' && Number.isFinite(result.ownedFailureArgs.seconds))
    ? i18n.t(`skill:${failureKeys[code]}`, { seconds: formatNumber(result.ownedFailureArgs?.seconds ?? 0) })
    : result.message || i18n.t('skill:connection.unknown');
  return i18n.t('skill:connection.failureReason', { reason });
}

export function detailAccessText(skill: Skill) {
  if (skill.sourceType === 'OSS_ZIP') {
    const lines = [
      i18n.t('skill:package.fileName', { name: skill.packageFileName || '-' }),
      i18n.t('skill:package.size', { size: skill.packageSize ? `${formatNumber(Math.ceil(skill.packageSize / 1024))} KB` : '-' }),
      i18n.t('skill:package.md5', { md5: skill.packageMd5 || '-' }),
    ];
    return lines.join('\n');
  }
  if (skill.type !== 'MCP') {
    return skill.installSpec || i18n.t('skill:access.noInstall');
  }
  try {
    const config = JSON.parse(skill.installSpec || '{}') as Record<string, unknown>;
    const headers = config.headers as Record<string, unknown> | undefined;
    if (headers) {
      config.headers = Object.fromEntries(Object.entries(headers).map(([name, value]) => [
        name, typeof value === 'object' && value !== null ? AUTHORIZATION_MASK : value,
      ]));
    }
    const env = config.env as Record<string, unknown> | undefined;
    if (env) config.env = Object.fromEntries(Object.entries(env).map(([name, value]) => [
      name, typeof value === 'object' && value !== null ? AUTHORIZATION_MASK : value,
    ]));
    return JSON.stringify(config, null, 2);
  } catch {
    return skill.installSpec || i18n.t('skill:access.noInstall');
  }
}
export function mcpValues(raw: unknown) {
  return Object.entries((raw || {}) as Record<string, unknown>).map(([name, rawValue]) => {
    const secret = typeof rawValue === 'object' && rawValue !== null
      && ((rawValue as Record<string, unknown>).secret === true || (rawValue as Record<string, unknown>).kind === 'secretRef');
    return {
      name,
      secret,
      value: secret ? AUTHORIZATION_MASK : String(rawValue ?? ''),
    };
  });
}

export function serializeMcpValues(entries: Array<{ name?: string; value?: string; secret?: boolean | string }> = []) {
  return Object.fromEntries(entries.filter((entry) => entry.name?.trim()).map((entry) => [
    entry.name!.trim(), (entry.secret === true || entry.secret === 'true')
      ? { secret: true, value: entry.value === AUTHORIZATION_MASK ? '' : (entry.value || '') }
      : (entry.value || ''),
  ]));
}

export function pluginProviders(installSpec?: string): string[] {
  try {
    const parsed = JSON.parse(installSpec || '{}') as { providers?: string[] };
    return Array.isArray(parsed.providers) ? parsed.providers : [];
  } catch {
    return [];
  }
}
