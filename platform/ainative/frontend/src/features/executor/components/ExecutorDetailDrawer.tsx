import { Alert, Button, Descriptions, Drawer, Space, Typography } from 'antd';
import { CheckCircleFilled, CloseCircleFilled, CopyOutlined, EyeInvisibleOutlined, EyeOutlined, InfoCircleFilled } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { formatDateTime } from '@/shared/i18n/format';
import { ModelUpgradePolicyEditor } from '../ModelUpgradePolicyEditor';
import { restartMessage } from '../executorStatus';
import type { ExecutorVO } from '../api';

/** 运行器详情抽屉:运行时信息 + 升级策略 + 重启 + 令牌。 */
export function ExecutorDetailDrawer({
  detail, open, onClose, revealedTokens, loadingTokenId, restarting,
  onRevealToken, onCopyToken, onRestart,
}: {
  detail: ExecutorVO | null | undefined;
  open: boolean;
  onClose: () => void;
  revealedTokens: Record<number, string>;
  loadingTokenId: number | null;
  restarting: boolean;
  onRevealToken: (id: number) => void;
  onCopyToken: (id: number) => void;
  onRestart: (update: boolean) => void;
}) {
  const { t } = useTranslation('executor');
  const formatTime = (value?: string | null) => value ? formatDateTime(value) : t('notReported');
  const restartLabels: Record<string, string> = { REQUESTED: t('restartWaiting'), WAITING_IDLE: t('restartWaitingIdle'), UPDATING: t('restartUpdating'), RESTARTING: t('restartRunning'), COMPLETED: t('restartSucceeded'), FAILED: t('restartFailed'), TIMED_OUT: t('restartTimedOut') };
  const restartPending = Boolean(detail?.restart && ['REQUESTED', 'WAITING_IDLE', 'UPDATING', 'RESTARTING'].includes(detail.restart.status));
  return (
      <Drawer closable={{ 'aria-label': t('close') }} title={detail ? t('namedDetail', { name: detail.name }) : t('detailTitle')} open={open}
        width={520} onClose={onClose}>
        {detail && <Space direction="vertical" size={24} style={{ width: '100%' }}>
          <Descriptions column={1} size="small" title={t('runtimeInfo')}>
            <Descriptions.Item label={t('executorId')}>{detail.id}</Descriptions.Item>
            <Descriptions.Item label={t('agent')}>{detail.agentName ?? '-'}</Descriptions.Item>
            <Descriptions.Item label={t('currentVersion')}>{detail.version ?? t('notReported')}</Descriptions.Item>
            <Descriptions.Item label={t('currentModel')}>{detail.modelName ?? detail.model ?? t('notReported')}</Descriptions.Item>
            <Descriptions.Item label={t('lastStartedTime')}>{formatTime(detail.lastStartedAt)}</Descriptions.Item>
            <Descriptions.Item label={t('lastHeartbeat')}>{formatTime(detail.lastHeartbeat)}</Descriptions.Item>
            <Descriptions.Item label={t('connectIp')}>{detail.lastConnectIp ?? '-'}</Descriptions.Item>
            <Descriptions.Item label={t('hostname')}>{detail.lastConnectHostname ?? t('notReported')}</Descriptions.Item>
            <Descriptions.Item label={t('createdAt')}>{formatTime(detail.gmtCreate)}</Descriptions.Item>
          </Descriptions>
          <ModelUpgradePolicyEditor key={detail.id} executor={detail} />
          <div>
            <Typography.Title level={5}>{t('clientMaintenance')}</Typography.Title>
            <Typography.Paragraph type="secondary">{t('maintenanceHelp')}</Typography.Paragraph>
            <Space wrap>
              <Button disabled={detail.status !== 'ONLINE' || !detail.restartSupported || restartPending} loading={restarting}
                onClick={() => onRestart(false)}>{t('restartClient')}</Button>
              <Button type="primary" disabled={detail.status !== 'ONLINE' || !detail.updateRestartSupported || restartPending} loading={restarting}
                onClick={() => onRestart(true)}>{t('updateRestart')}</Button>
            </Space>
            {!detail.restartSupported && <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>{t('restartUnsupported')}</Typography.Paragraph>}
            {detail.restart && <Alert style={{ marginTop: 16 }} showIcon
              icon={detail.restart.status === 'COMPLETED' ? <CheckCircleFilled aria-hidden="true" /> : ['FAILED', 'TIMED_OUT'].includes(detail.restart.status) ? <CloseCircleFilled aria-hidden="true" /> : <InfoCircleFilled aria-hidden="true" />}
              type={detail.restart.status === 'COMPLETED' ? 'success' : ['FAILED', 'TIMED_OUT'].includes(detail.restart.status) ? 'error' : 'info'}
              message={Object.prototype.hasOwnProperty.call(restartLabels, detail.restart.status) ? restartLabels[detail.restart.status] : detail.restart.status} description={restartMessage(detail.restart)} />}
          </div>
          <div>
            <Typography.Title level={5}>{t('credentials')}</Typography.Title>
            <Space wrap>
              <Typography.Text code style={{ wordBreak: 'break-all' }}>{revealedTokens[detail.id] ?? '••••••••••••'}</Typography.Text>
              <Button size="small" loading={loadingTokenId === detail.id} icon={revealedTokens[detail.id] ? <EyeInvisibleOutlined aria-hidden="true" /> : <EyeOutlined aria-hidden="true" />}
                onClick={() => onRevealToken(detail.id)}>{revealedTokens[detail.id] ? t('hide') : t('show')}</Button>
              <Button size="small" icon={<CopyOutlined aria-hidden="true" />} onClick={() => onCopyToken(detail.id)}>{t('copyToken')}</Button>
            </Space>
          </div>
        </Space>}
      </Drawer>
  );
}
