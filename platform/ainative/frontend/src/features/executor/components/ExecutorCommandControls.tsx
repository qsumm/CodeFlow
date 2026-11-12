import { Button, Dropdown, Segmented, Space, Tag, Tooltip, Typography } from 'antd';
import { BugOutlined, DownOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import type { DebugShell, ExecutorLaunchMode, StartupOs } from '../api';

export const debugShellItems = (): { key: DebugShell; label: string }[] => [
  { key: 'bash', label: i18n.t('executor:debugBash') },
  { key: 'powershell', label: i18n.t('executor:debugPowershell') },
];

export function CopyCommandActions({ onCopy, disabled }: { onCopy: (shell?: DebugShell) => void; disabled?: boolean }) {
  const { t } = useTranslation('executor');
  return (
    <Space>
      <Button type="primary" disabled={disabled} onClick={() => onCopy()}>{t('copyStartup')}</Button>
      <Tooltip title={t('debugWarning')}>
        <span style={{ display: 'inline-flex' }}>
        <Dropdown
          disabled={disabled}
          trigger={['click']}
          menu={{ items: debugShellItems(), onClick: ({ key }) => onCopy(key as DebugShell) }}
        >
          <Button disabled={disabled} icon={<BugOutlined aria-hidden="true" />}>{t('copyDebug')} <DownOutlined aria-hidden="true" /></Button>
        </Dropdown>
        </span>
      </Tooltip>
    </Space>
  );
}

export function OsSegmented({ value, onChange }: { value: StartupOs; onChange: (os: StartupOs) => void }) {
  const { t } = useTranslation('executor');
  return (
    <Segmented
      aria-label={t('startup')}
      size="small"
      value={value}
      onChange={(os) => onChange(os as StartupOs)}
      options={[
        { value: 'windows', label: t('windows') },
        { value: 'posix', label: t('posix') },
      ]}
      style={{ display: 'block', marginTop: 8 }}
    />
  );
}

// 启动方式三档：与后端 resolveMode 的 foreground/nohup/service 同源；service 附带卸载提示。
export function ModeSegmented({ value, onChange, executorId }: {
  value: ExecutorLaunchMode; onChange: (mode: ExecutorLaunchMode) => void; executorId: number;
}) {
  const { t } = useTranslation('executor');
  const hintKey = value === 'foreground' ? 'launchModeForegroundHint'
    : value === 'nohup' ? 'launchModeNohupHint' : 'launchModeServiceHint';
  const isService = value === 'service';
  return (
    <div style={{ marginTop: 12 }}>
      <Typography.Text strong>{t('launchModeLabel')}</Typography.Text>
      <Segmented
        aria-label={t('launchModeLabel')}
        size="small"
        value={value}
        onChange={(mode) => onChange(mode as ExecutorLaunchMode)}
        options={[
          { value: 'foreground', label: t('launchModeForeground') },
          { value: 'nohup', label: t('launchModeNohup') },
          { value: 'service', label: (
            <span>
              {t('launchModeService')}
              <Tag color="success" style={{ marginInlineStart: 6, marginInlineEnd: 0 }}>{t('launchModeRecommended')}</Tag>
            </span>
          ) },
        ]}
        style={{ display: 'block', marginTop: 8 }}
      />
      <Typography.Paragraph type="secondary" style={{ marginTop: 6, marginBottom: 0 }}>{t(hintKey)}</Typography.Paragraph>
      {isService && (
        <Typography.Paragraph type="secondary" style={{ marginTop: 4, marginBottom: 0 }} data-testid="uninstall-hint">
          {t('uninstallHint', { executorId })}
        </Typography.Paragraph>
      )}
    </div>
  );
}

export function CommandPreview({ text }: { text: string }) {
  const { t } = useTranslation('executor');
  return (
    <div role="region" aria-label={t('commandPreview')} style={{
      marginTop: 4, padding: '8px 12px',
      background: 'var(--cf-raised)', borderRadius: 6, fontFamily: 'monospace', fontSize: 13,
      wordBreak: 'break-all', lineHeight: 1.6,
    }}>
      {text}
    </div>
  );
}
