import { Alert, Button, Input, Modal, Typography } from 'antd';
import { CopyOutlined, EyeInvisibleOutlined, EyeOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { CommandPreview, CopyCommandActions, ModeSegmented, OsSegmented } from './ExecutorCommandControls';
import type { DebugShell, ExecutorLaunchMode, IssuedExecutorVO, StartupOs } from '../api';

/** 创建成功后的令牌与启动命令弹窗(令牌只显示这一次)。 */
export function ExecutorCreatedTokenModal({
  result, startupOs, onStartupOsChange, launchMode, onLaunchModeChange, onPreview, issuedPreview, onCopyToken, onCopyCommand, onClose,
}: {
  result: IssuedExecutorVO | null;
  startupOs: StartupOs;
  onStartupOsChange: (os: StartupOs) => void;
  launchMode: ExecutorLaunchMode;
  onLaunchModeChange: (mode: ExecutorLaunchMode) => void;
  /** 按 mode 重新生成预览命令(防抖由父层负责)。 */
  onPreview: (mode: ExecutorLaunchMode) => void;
  issuedPreview: string;
  onCopyToken: () => void;
  onCopyCommand: (shell?: DebugShell) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation('executor');
  return (
      <Modal closable={{ 'aria-label': t('close') }} title={t('created')} open={Boolean(result)} width={640}
        onOk={onClose} onCancel={onClose}
        cancelButtonProps={{ style: { display: 'none' } }}>
        {result?.token ? (
          <Alert type="warning" showIcon style={{ marginBottom: 16 }} message={t('saveTokenWarning')} />
        ) : (
          <Alert type="success" showIcon style={{ marginBottom: 16 }} message={t('createdWithoutToken')} />
        )}
        <Typography.Text strong>{t('namePrefix')} </Typography.Text>
        <Typography.Text>{result?.name}</Typography.Text>
        {result?.token && (
          <div style={{ marginTop: 12 }}>
            <Typography.Text strong>{t('tokenPrefix')}</Typography.Text>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 4 }}>
              <Input.Password aria-label={t('tokenPrefix')} value={result.token} readOnly style={{ flex: 1 }}
                iconRender={(visible) => <Button type="text" htmlType="button" aria-label={visible ? t('hide') : t('show')}
                  icon={visible ? <EyeOutlined aria-hidden="true" /> : <EyeInvisibleOutlined aria-hidden="true" />}
                  style={{ padding: 0, width: 16, height: 16 }} />} />
              <Button icon={<CopyOutlined aria-hidden="true" />} onClick={onCopyToken}>{t('copy')}</Button>
            </div>
          </div>
        )}
        {result?.token && (
          <div style={{ marginTop: 16 }}>
            <Typography.Text strong>{t('startupPrefix')}</Typography.Text>
            <OsSegmented value={startupOs} onChange={onStartupOsChange} />
            <ModeSegmented value={launchMode} onChange={(mode) => { onLaunchModeChange(mode); onPreview(mode); }} executorId={result.id} />
            <CommandPreview text={issuedPreview} />
            <div style={{ marginTop: 12 }}>
              <CopyCommandActions onCopy={onCopyCommand} />
            </div>
          </div>
        )}
      </Modal>
  );
}
