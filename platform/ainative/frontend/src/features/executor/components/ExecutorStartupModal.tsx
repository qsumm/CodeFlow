import { Alert, Button, Form, Input, InputNumber, Modal, Select, Space, Tag, Tooltip, Typography } from 'antd';
import type { FormInstance } from 'antd';
import { CheckCircleFilled, CloseCircleFilled, InfoCircleFilled } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { restartMessage } from '../executorStatus';
import { CREATABLE_CLIENT_KINDS } from '../executorClientKinds';
import { resolveQoderLaunchOptions } from '../qoderOptions';
import type { QoderModelOptions, QoderSelectOption } from '../qoderOptions';
import { CopyCommandActions, CommandPreview, ModeSegmented, OsSegmented } from './ExecutorCommandControls';
import type { DebugShell, ExecutorLaunchMode, ExecutorVO, StartupOs } from '../api';

/**
 * 启动命令弹窗：读服务端配置 → 改参数 → 预览命令 → 在线应用。
 * 命令与令牌同属凭据面，仅管理员可见；草稿只参与预览，复制与应用仍先保存。
 */
export function ExecutorStartupModal({
  target, form, configError, hasConfig, kindMissing, configured, modelInvalid,
  saving, commandSaving, commandDraft, preview, unsavedPreview, previewValid,
  mode, onModeChange, os, onOsChange,
  modelOptions, qoderOptions, qoderProvider,
  applyBlockReason, commandRestart, restartLabels,
  onClose, onCancel, onSaveConfig, onCopy, onEditCommand, onSaveCommand,
  onCommandDraftChange, onBackToParameters, onOpenRestartPolicy,
}: {
  target: ExecutorVO | null;
  form: FormInstance;
  configError: string | null;
  /** 服务端已读回配置（配置缺失时为 false，不弹「未配置」提示）。 */
  hasConfig: boolean;
  kindMissing: boolean;
  configured: boolean;
  modelInvalid: boolean;
  saving: boolean;
  commandSaving: boolean;
  commandDraft: string | null;
  preview: string;
  unsavedPreview: boolean;
  previewValid: boolean;
  mode: ExecutorLaunchMode;
  onModeChange: (mode: ExecutorLaunchMode) => void;
  os: StartupOs;
  onOsChange: (os: StartupOs) => void;
  modelOptions: QoderSelectOption[];
  qoderOptions: QoderModelOptions;
  qoderProvider: boolean;
  applyBlockReason: string | null;
  commandRestart: ExecutorVO['restart'];
  restartLabels: Record<string, string>;
  /** 关闭弹窗（连带收起叠在上面的重启策略弹窗）。 */
  onClose: () => void;
  /** 页脚「取消」：只收起本弹窗。 */
  onCancel: () => void;
  onSaveConfig: () => void;
  onCopy: (shell?: DebugShell) => void;
  onEditCommand: () => void;
  onSaveCommand: () => void;
  onCommandDraftChange: (value: string) => void;
  onBackToParameters: () => void;
  onOpenRestartPolicy: () => void;
}) {
  const { t } = useTranslation('executor');
  return (
    <Modal closable={{ 'aria-label': t('close') }} title={t('namedStartup', { name: target?.name ?? '' })} open={!!target} width={720}
      onCancel={onClose}
      footer={(
        <Space size={8} wrap>
          {commandDraft !== null ? <>
            <Button disabled={commandSaving} onClick={onBackToParameters}>{t('backToParameters')}</Button>
            <Button loading={commandSaving} disabled={!commandDraft.trim()} onClick={onSaveCommand}>{t('saveCommand')}</Button>
          </> : <>
          <Button onClick={onCancel}>{t('cancel')}</Button>
          <Button loading={saving}
            onClick={onSaveConfig}>{t('saveConfig')}</Button>
          <CopyCommandActions disabled={Boolean(configError) || !configured}
            onCopy={(shell) => onCopy(shell)} />
          <Tooltip title={applyBlockReason}>
            <span><Button type="primary" loading={saving || commandSaving}
              disabled={Boolean(configError) || !configured || Boolean(applyBlockReason)}
              onClick={onOpenRestartPolicy}>{t('applyCommand')}</Button></span>
          </Tooltip>
          </>}
        </Space>
      )}>
      {configError && (
        <Alert type="error" showIcon style={{ marginBottom: 16 }} message={configError} />
      )}
      {kindMissing && (
        <Alert type="warning" showIcon style={{ marginBottom: 16 }}
          message={t('missingKindHelp')} />
      )}
      {!configError && hasConfig && !configured && (
        <Alert type="warning" showIcon style={{ marginBottom: 16 }}
          message={t('notConfiguredHelp')} />
      )}
      {modelInvalid && (
        <Alert type="warning" showIcon style={{ marginBottom: 16 }}
          message={t('modelUnavailable')} />
      )}
      <div hidden={commandDraft !== null}>
      <Form form={form} layout="vertical" initialValues={{ maxConcurrentDispatches: 5 }}>
        {kindMissing && (
          <Form.Item label={t('clientKind')} name="clientKind"
            rules={[{ required: true, message: t('selectClient') }]}
            extra={t('missingKindExtra')}>
            <Select options={CREATABLE_CLIENT_KINDS.map(({ value, labelKey }) => ({ value, label: t(labelKey) }))} />
          </Form.Item>
        )}
        <Form.Item label={t('maxConcurrent')} name="maxConcurrentDispatches"
          rules={[{ required: true, type: 'integer', min: 1, max: 10, message: t('concurrentValidation') }]}
          extra={<span style={{ whiteSpace: 'nowrap' }}>{t('concurrentHelp')}</span>}>
          <InputNumber controls={false} min={1} max={10} precision={0} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item label={t('memoryMode')} name="memoryMode" rules={[{ required: true, message: t('required') }]}>
          <Select options={[
            { value: 'platform', label: t('platformMemory') },
            { value: 'provider-local', label: t('localMemory') },
            { value: 'none', label: t('noMemory') },
          ]} />
        </Form.Item>
        {qoderProvider && (
          <>
            <Form.Item label={t('qoderModel')} name="model" rules={[{ required: true, message: t('required') }]}>
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
      </Form>
      </div>
      {commandDraft !== null ? <>
        <Alert type="info" showIcon icon={<InfoCircleFilled aria-hidden="true" />} message={t('editableCommandHelp')} style={{ marginBottom: 12 }} />
        <Form.Item label={t('fullStartupCommand')}>
          <Input.TextArea aria-label={t('fullStartupCommand')} value={commandDraft} onChange={(event) => onCommandDraftChange(event.target.value)}
            disabled={commandSaving} autoSize={{ minRows: 6, maxRows: 14 }} spellCheck={false} style={{ fontFamily: 'monospace' }} />
        </Form.Item>
      </> : <>
      <Typography.Text strong>{t('commandPreview')}</Typography.Text>
      {unsavedPreview && <Tag style={{ marginLeft: 8 }}>{t('unsavedPreview')}</Tag>}
      {target && <ModeSegmented value={mode} onChange={onModeChange} executorId={target.id} />}
      <OsSegmented value={os} onChange={onOsChange} />
      <CommandPreview text={preview} />
      <Button style={{ marginTop: 12 }} loading={commandSaving} disabled={Boolean(configError) || !configured || !previewValid}
        onClick={onEditCommand}>{t('editFullCommand')}</Button>
      <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>{t('onlineCommandHelp')}</Typography.Paragraph>
      {applyBlockReason && <Typography.Paragraph type="secondary">{applyBlockReason}</Typography.Paragraph>}
      {commandRestart && <div>
        <Typography.Text strong>{t('lastCommandApplication')}</Typography.Text>
        <Alert showIcon style={{ marginTop: 12 }}
          icon={commandRestart.status === 'COMPLETED' ? <CheckCircleFilled aria-hidden="true" /> : ['FAILED', 'TIMED_OUT'].includes(commandRestart.status) ? <CloseCircleFilled aria-hidden="true" /> : <InfoCircleFilled aria-hidden="true" />}
          type={commandRestart.status === 'COMPLETED' ? 'success' : ['FAILED', 'TIMED_OUT'].includes(commandRestart.status) ? 'error' : 'info'}
          message={Object.prototype.hasOwnProperty.call(restartLabels, commandRestart.status) ? restartLabels[commandRestart.status] : commandRestart.status}
          description={restartMessage(commandRestart)} />
      </div>}
      </>}
    </Modal>
  );
}
