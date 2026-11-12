import { Form, Input, InputNumber, Modal, Select } from 'antd';
import type { FormInstance } from 'antd';
import { useTranslation } from 'react-i18next';
import type { Agent } from '@/features/agent/api';
import { qoderOptionsForModel, resolveQoderLaunchOptions } from '../qoderOptions';
import type { QoderModelOptions, QoderSelectOption } from '../qoderOptions';
import { ClientKindSelect } from '../executorClientKinds';
import { isQoderClientKind } from '../executorClientKind';

/**
 * 新建运行器弹窗：归属 Agent / 客户端类型 / 并发数 / 记忆模式 /（Qoder 系）模型三项 / 名称。
 * 启动参数随创建请求一并落库，浏览器不留偏好。
 */
export function ExecutorCreateModal({
  open, form, agents, agentOptionLabel, clientKind, modelOptions, qoderOptions, submitting, onOk, onCancel,
}: {
  open: boolean;
  form: FormInstance;
  agents: Agent[];
  agentOptionLabel: (agent: Agent) => string;
  clientKind?: string;
  modelOptions: QoderSelectOption[];
  qoderOptions: QoderModelOptions;
  submitting: boolean;
  onOk: () => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation('executor');
  return (
    <Modal closable={{ 'aria-label': t('close') }} title={t('create')} open={open}
      onOk={onOk} onCancel={onCancel}
      confirmLoading={submitting}>
      <Form form={form} layout="vertical" initialValues={{
        clientKind: 'QODER_CLI', memoryMode: 'platform', maxConcurrentDispatches: 5, model: 'auto',
        reasoningEffort: qoderOptionsForModel('auto').defaultReasoningEffort,
        contextWindow: qoderOptionsForModel('auto').defaultContextWindow,
      }}>
        <Form.Item label={t('owningAgent')} name="agentId" rules={[{ required: true, message: t('selectOwningAgent') }]}>
          <Select placeholder={t('selectAgent')} showSearch optionFilterProp="label"
            options={agents.map(a => ({ value: a.id, label: agentOptionLabel(a) }))}
          />
        </Form.Item>
        <Form.Item label={t('clientKind')} name="clientKind" rules={[{ required: true, message: t('selectType') }]}>
          <ClientKindSelect />
        </Form.Item>
        {/* ponytail: typing/keyboard retained; restore pointer controls when rc-input-number supports localized handler labels. */}
        <Form.Item label={t('maxConcurrent')} name="maxConcurrentDispatches"
          rules={[{ required: true, type: 'integer', min: 1, max: 10, message: t('concurrentValidation') }]}
          extra={<span style={{ whiteSpace: 'nowrap' }}>{t('concurrentHelp')}</span>}>
          <InputNumber controls={false} min={1} max={10} precision={0} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item label={t('memoryMode')} name="memoryMode" rules={[{ required: true, message: t('selectMemory') }]}>
          <Select options={[
            { value: 'platform', label: t('platformMemory') },
            { value: 'provider-local', label: t('localMemory') },
            { value: 'none', label: t('noMemory') },
          ]} />
        </Form.Item>
        {isQoderClientKind(clientKind) && (
          <>
            <Form.Item label={t('qoderModel')} name="model" rules={[{ required: true, message: t('selectModel') }]}>
              <Select options={modelOptions} onChange={(model) => {
                form.setFieldsValue(resolveQoderLaunchOptions(model, form.getFieldsValue()));
              }} />
            </Form.Item>
            <Form.Item label={t('reasoning')} name="reasoningEffort" rules={[{ required: true, message: t('required') }]}>
              <Select options={qoderOptions.reasoningEfforts} />
            </Form.Item>
            <Form.Item label={t('context')} name="contextWindow" rules={[{ required: true, message: t('required') }]}>
              <Select options={qoderOptions.contextWindows} />
            </Form.Item>
          </>
        )}
        <Form.Item label={t('name')} name="name" rules={[{ required: true, message: t('enterName') }]}>
          <Input placeholder={t('nameExample')} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
