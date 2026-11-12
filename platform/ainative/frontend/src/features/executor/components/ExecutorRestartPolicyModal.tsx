import { Alert, Button, Modal, Space, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { ExecutorRestartMode } from '../api';

/** 重启策略子弹窗：在线应用启动命令时选「立即重启」还是「空闲时重启」。 */
export function ExecutorRestartPolicyModal({
  open, applyBlockReason, busy, onCancel, onApply,
}: {
  open: boolean;
  /** 不可应用时的原因（直接显示在按钮 tooltip 上），null 表示可以应用。 */
  applyBlockReason: string | null;
  busy: boolean;
  onCancel: () => void;
  onApply: (mode: ExecutorRestartMode) => void;
}) {
  const { t } = useTranslation('executor');
  return (
    <Modal title={t('restartPolicyTitle')} open={open}
      closable={{ 'aria-label': t('close') }} onCancel={onCancel}
      footer={<Space wrap>
        <Button onClick={onCancel}>{t('cancel')}</Button>
        <Button danger disabled={Boolean(applyBlockReason) || busy}
          onClick={() => onApply('FORCE')}>{t('restartForce')}</Button>
        <Button type="primary" disabled={Boolean(applyBlockReason) || busy}
          onClick={() => onApply('WHEN_IDLE')}>{t('restartWhenIdle')}</Button>
      </Space>}>
      <Typography.Paragraph>{t('restartWhenIdleHelp')}</Typography.Paragraph>
      <Alert type="warning" showIcon message={t('restartForceHelp')} />
    </Modal>
  );
}
