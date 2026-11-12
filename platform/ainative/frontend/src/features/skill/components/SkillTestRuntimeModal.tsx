import { Alert, Modal, Select } from 'antd';
import { useTranslation } from 'react-i18next';
import type { Skill } from '../api';
import type { ExecutorVO } from '@/features/executor/api';

/** 选择运行器后触发连接测试。 */
export function SkillTestRuntimeModal({
  skill, executorId, executors, onExecutorChange, onConfirm, onCancel,
}: {
  skill: Skill | null;
  executorId: number | undefined;
  executors: ExecutorVO[];
  onExecutorChange: (id: number) => void;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation('skill');
  return (
      <Modal
        closable={{ 'aria-label': t('close') }}
        title={t('connection.runtimeTitle')}
        open={Boolean(skill)}
        onCancel={onCancel}
        onOk={onConfirm}
        okButtonProps={{ disabled: !executorId }}
      >
        <Alert type="info" showIcon message={t('connection.runtimeHint')} style={{ marginBottom: 16 }} />
        <Select
          style={{ width: '100%' }}
          value={executorId}
          onChange={onExecutorChange}
          placeholder={t('connection.runtimeSelect')}
          options={executors.filter((executor) => executor.status === 'ONLINE').map((executor) => ({
            value: executor.id,
            label: t('connection.runtimeOption', { name: executor.name, agent: executor.agentName || t('connection.unnamedAgent'), id: executor.id }),
          }))}
          notFoundContent={t('connection.noRuntime')}
        />
      </Modal>
  );
}
