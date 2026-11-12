import { Alert, Modal, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { describeUpdateAllResult, updateSkipReason } from '../executorStatus';
import type { ExecutorUpdateAllResultVO } from '../api';

/** 批量升级确认:说清影响面与目标版本。 */
export function ExecutorBulkUpdateConfirmModal({
  open, targetVersion, count, autoUpdateEnabled, pending, onIssue, onClose,
}: {
  open: boolean;
  targetVersion: string | null | undefined;
  count: number;
  autoUpdateEnabled: boolean | undefined;
  pending: boolean;
  onIssue: () => void;
  onClose: () => void;
}) {
  const { t } = useTranslation('executor');
  return (
      <Modal closable={{ 'aria-label': t('close') }} title={t('bulkUpdate')} open={open} okText={t('issueUpdate')}
        onOk={onIssue} onCancel={onClose}
        confirmLoading={pending}>
        <Alert type="warning" showIcon style={{ marginBottom: 16 }}
          message={t('bulkWarning', { version: targetVersion ?? '-', count })}
          description={t('bulkUpdateHelp')} />
        {autoUpdateEnabled === false && (
          <Typography.Paragraph type="secondary">
            {t('autoUpdateOffHelp')}
          </Typography.Paragraph>
        )}
      </Modal>
  );
}

/** 批量升级结果:被跳过的机器逐条给出原因。 */
export function ExecutorBulkIssuedModal({ result, onClose }: {
  result: ExecutorUpdateAllResultVO | null;
  onClose: () => void;
}) {
  const { t } = useTranslation('executor');
  return (
      <Modal closable={{ 'aria-label': t('close') }} title={t('bulkIssued')} open={Boolean(result)} width={640}
        onOk={onClose} onCancel={onClose}
        cancelButtonProps={{ style: { display: 'none' } }}>
        {result && (
          <>
            <Alert type={result.skipped.length > 0 ? 'info' : 'success'} showIcon style={{ marginBottom: 16 }}
              message={describeUpdateAllResult(result)} />
            {result.skipped.length > 0 && (
              <div>
                <Typography.Text strong>{t('skippedExecutors')}</Typography.Text>
                <ul style={{ marginTop: 8, paddingLeft: 20 }}>
                  {result.skipped.map((skip) => (
                    <li key={skip.executorId}>
                      <Typography.Text>{skip.executorName}</Typography.Text>
                      <Typography.Text type="secondary"> · {updateSkipReason(skip)}</Typography.Text>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </>
        )}
      </Modal>
  );
}
