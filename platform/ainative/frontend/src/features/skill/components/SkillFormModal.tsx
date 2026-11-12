import type { MutableRefObject } from 'react';
import { Alert, Button, Checkbox, Form, Input, InputNumber, Modal, Radio, Select, Space, Spin, Tooltip } from 'antd';
import {
  FileTextOutlined, FolderOpenOutlined, MinusCircleOutlined, PlusOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { FormInstance, CheckboxProps } from 'antd';
import type { Skill } from '../api';
import { AUTHORIZATION_MASK, UNCATEGORIZED } from '../skillFormat';
import { formatBytes } from '../skillPackage';

/** 技能新建/编辑弹窗：类型、接入方式、包上传与 MCP 配置。 */
export function SkillFormModal({
  open, editingSkill, accessMode, packageReading, zipFile, selectedType, form,
  fileInputRef, zipInputRef, categorySelectOptions, submitting,
  onAccessModeChange, onResetPackageSelection, onPackageSelectFiles, onSubmit, onCancel,
}: {
  open: boolean;
  editingSkill: Skill | null;
  accessMode: 'manual' | 'package';
  packageReading: boolean;
  zipFile: File | null;
  selectedType: string | undefined;
  form: FormInstance;
  fileInputRef: MutableRefObject<HTMLInputElement | null>;
  zipInputRef: MutableRefObject<HTMLInputElement | null>;
  categorySelectOptions: Array<{ value: string; label: string }>;
  submitting: boolean;
  onAccessModeChange: (mode: 'manual' | 'package') => void;
  onResetPackageSelection: () => void;
  onPackageSelectFiles: (files: FileList | null, directory: boolean) => void;
  onSubmit: () => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation('skill');

  const creatableTypeOptions = [
    { value: 'SKILL', label: t('types.skill') },
    { value: 'MCP', label: t('types.mcp') },
    { value: 'HOOK', label: t('types.hook') },
  ];

  return (
      <Modal
        closable={{ 'aria-label': t('close') }}
        title={editingSkill ? t('edit') : t('create')}
        open={open}
        width={680}
        forceRender
        onOk={onSubmit}
        onCancel={onCancel}
        confirmLoading={submitting}
      >
        <Form name="skillEditor" form={form} layout="vertical">
          <Form.Item name="type" label={t('type')} rules={[{ required: true }]}>
			<Select disabled={!!editingSkill} onChange={(value) => {
              onResetPackageSelection();
              onAccessModeChange(value === 'PLUGIN' || value === 'HOOK' ? 'package' : 'manual');
            }}
              options={creatableTypeOptions} />
          </Form.Item>
          {(selectedType === 'SKILL' || selectedType === 'PLUGIN' || selectedType === 'HOOK') && (
            <Form.Item label={t('access.label')}>
              <Radio.Group
                value={accessMode}
                onChange={(e) => {
                  onAccessModeChange(e.target.value);
                  onResetPackageSelection();
                }}
                options={selectedType === 'PLUGIN' || selectedType === 'HOOK'
                  ? [{ value: 'package', label: selectedType === 'HOOK' ? t('package.uploadHook') : t('package.uploadPlugin') }]
                  : [{ value: 'manual', label: t('access.manual') }, { value: 'package', label: t('package.uploadFolder') }]}
              />
            </Form.Item>
          )}
          {accessMode === 'package' && selectedType !== 'MCP' && (
            <Form.Item label={t('package.label')} required>
              <Space direction="vertical" style={{ width: '100%' }}>
                <Alert type="info" showIcon message={t('package.limitsTitle')} description={t('package.limit')} />
                <Space>
                  <Button icon={<FolderOpenOutlined aria-hidden="true" />} onClick={() => fileInputRef.current?.click()}>
                    {t('package.chooseFolder')}</Button>
                  <Button icon={<FileTextOutlined aria-hidden="true" />} onClick={() => zipInputRef.current?.click()}>
                    {t('package.chooseZip')}</Button>
                </Space>
                <input
                  ref={(node) => {
                    fileInputRef.current = node;
                    node?.setAttribute('webkitdirectory', '');
                    node?.setAttribute('directory', '');
                  }}
                  aria-label={t('package.folderInput')}
                  type="file"
                  multiple
                  style={{ display: 'none' }}
                  onChange={(e) => onPackageSelectFiles(e.target.files, true)}
                />
                <input
                  ref={zipInputRef}
                  aria-label={t('package.zipInput')}
                  type="file"
                  accept=".zip,application/zip"
                  style={{ display: 'none' }}
                  onChange={(e) => onPackageSelectFiles(e.target.files, false)}
                />
                {packageReading ? <Spin /> : zipFile ? (
                  <Alert type="success" showIcon message={t('package.selected', { name: zipFile.name })} description={formatBytes(zipFile.size)} />
                ) : (
                  <Alert type="info" showIcon
                    message={selectedType === 'SKILL' ? t('package.skillRequired')
                      : selectedType === 'HOOK' ? t('package.hookRequired') : t('package.pluginRequired')}
                    description={editingSkill?.sourceType === 'OSS_ZIP'
                      ? t('package.current', { name: editingSkill.packageFileName || editingSkill.packageOssRef || t('package.alreadyUploaded') })
                      : selectedType === 'SKILL' ? t('package.frontmatter') : undefined}
                  />
                )}
              </Space>
            </Form.Item>
          )}
          {selectedType === 'PLUGIN' && (
            <Form.Item name="providers" label={t('providers')} rules={[{ required: true, message: t('validation.provider') }]}>
              <Select mode="multiple" options={[{ value: 'claude' }, { value: 'qoder' }]} />
            </Form.Item>
          )}
          <Form.Item name="name" label={t('name')}
            rules={selectedType === 'HOOK' ? [] : [{ required: true, message: t('validation.name') }]}>
            <Input disabled={accessMode === 'package' && selectedType === 'SKILL'} placeholder={t('placeholder.name')} />
          </Form.Item>
          <Form.Item
            name="categoryId"
            label={t('category.optional')}
            initialValue={UNCATEGORIZED}
            extra={t('category.tagHint')}
          >
            <Select options={categorySelectOptions} placeholder={t('uncategorized')} />
          </Form.Item>
          {selectedType === 'MCP' && (
            <>
              <Form.Item name="mcpTransport" label={t('mcp.serverType')} initialValue="http">
                <Select options={[
                  { value: 'http', label: t('mcp.http') },
                  { value: 'sse', label: t('mcp.sse') },
                  { value: 'stdio', label: t('mcp.stdio') },
                ]} />
              </Form.Item>
              <Form.Item noStyle shouldUpdate={(prev, next) => prev.mcpTransport !== next.mcpTransport}>
                {({ getFieldValue }) => getFieldValue('mcpTransport') === 'stdio' ? (
                  <>
                    <Form.Item name="mcpCommand" label={t('mcp.executable')} rules={[{ required: true }]}><Input placeholder={t('placeholder.command')} /></Form.Item>
                    <Form.List name="mcpArgs">
                      {(fields, { add, remove }) => <Form.Item label={t('mcp.args')}>
                        {fields.map((field) => <div key={field.key} style={{ display: 'flex', width: '100%', gap: 8, alignItems: 'center', marginBottom: 8 }}>
                          <Form.Item {...field} name={[field.name, 'value']} rules={[{ required: true, message: t('validation.argument') }]} style={{ flex: 1, minWidth: 0, marginBottom: 0 }}>
                            <Input aria-label={t('mcp.argument')} placeholder={t('placeholder.argument')} />
                          </Form.Item>
                          <Button type="text" aria-label={t('mcp.removeArgument')} onClick={() => remove(field.name)} icon={<MinusCircleOutlined aria-hidden="true" />} />
                        </div>)}
                        <Button block type="dashed" onClick={() => add()} icon={<PlusOutlined aria-hidden="true" />}>{t('mcp.addArgument')}</Button>
                      </Form.Item>}
                    </Form.List>
                    <Form.List name="mcpEnv">
                      {(fields, { add, remove }) => <Form.Item label={t('mcp.env')}>
                        {fields.map((field) => <div key={field.key} style={{ display: 'flex', width: '100%', gap: 8, alignItems: 'center', marginBottom: 8 }}>
                          <Form.Item {...field} name={[field.name, 'name']} rules={[{ required: true, message: t('validation.envName') }]} style={{ flex: '0 1 240px', minWidth: 0, marginBottom: 0 }}>
                            <Input aria-label={t('placeholder.envName')} placeholder={t('placeholder.envName')} />
                          </Form.Item>
                          <Form.Item {...field} name={[field.name, 'value']} rules={[{ required: true, message: t('validation.envValue') }]} style={{ flex: 1, minWidth: 0, marginBottom: 0 }}>
                            <Input aria-label={t('placeholder.envValue')} placeholder={t('placeholder.envValue')} autoComplete="new-password" onFocus={(event) => { if (form.getFieldValue(['mcpEnv', field.name, 'secret']) && event.currentTarget.value === AUTHORIZATION_MASK) form.setFieldValue(['mcpEnv', field.name, 'value'], ''); }} />
                          </Form.Item>
                          <Form.Item {...field} name={[field.name, 'secret']} valuePropName="checked" style={{ flex: 'none', marginBottom: 0 }}>
                            <SecretCheckbox label={t('mcp.private')} hint={t('mcp.secretHint')} />
                          </Form.Item>
                          <Button type="text" aria-label={t('mcp.removeEnv')} onClick={() => remove(field.name)} icon={<MinusCircleOutlined aria-hidden="true" />} />
                        </div>)}
                        <Button block type="dashed" onClick={() => add()} icon={<PlusOutlined aria-hidden="true" />}>{t('mcp.addEnv')}</Button>
                      </Form.Item>}
                    </Form.List>
                  </>
                ) : (
                  <>
                    <Form.Item name="mcpUrl" label={t('mcp.url')} rules={[{ required: true, type: 'url' }]}><Input placeholder="https://example.com/mcp" /></Form.Item>
                    <Form.List name="mcpHeaders">
                      {(fields, { add, remove }) => (
                        <Form.Item label={t('mcp.headers')}>
                          {fields.map((field) => (
                            <div key={field.key} style={{ display: 'flex', width: '100%', gap: 8, alignItems: 'center', marginBottom: 8 }}>
                              <Form.Item {...field} name={[field.name, 'name']} rules={[{ required: true, message: t('validation.headerName') }]} style={{ flex: '0 1 180px', minWidth: 0, marginBottom: 0 }}>
                                <Input aria-label={t('mcp.headerName')} placeholder="Authorization" />
                              </Form.Item>
                              <Form.Item {...field} name={[field.name, 'value']} rules={[{ required: true, message: t('validation.headerValue') }]} style={{ flex: 1, minWidth: 0, marginBottom: 0 }}>
                                <Input
                                  aria-label={t('mcp.headerValue')}
                                  placeholder="Bearer your-token"
                                  autoComplete="new-password"
                                  onFocus={(event) => {
                                    if (form.getFieldValue(['mcpHeaders', field.name, 'secret']) && event.currentTarget.value === AUTHORIZATION_MASK) {
                                      form.setFieldValue(['mcpHeaders', field.name, 'value'], '');
                                    }
                                  }}
                                />
                              </Form.Item>
                              <Form.Item {...field} name={[field.name, 'secret']} valuePropName="checked" style={{ flex: 'none', marginBottom: 0 }}>
                                <SecretCheckbox label={t('mcp.private')} hint={t('mcp.secretHint')} />
                              </Form.Item>
                              <Button type="text" aria-label={t('mcp.removeHeader')} onClick={() => remove(field.name)} icon={<MinusCircleOutlined aria-hidden="true" />} />
                            </div>
                          ))}
                          <Button block type="dashed" onClick={() => add()} icon={<PlusOutlined aria-hidden="true" />}>{t('mcp.addHeader')}</Button>
                        </Form.Item>
                      )}
                    </Form.List>
                    <Form.Item name="mcpTimeoutSeconds" label={t('mcp.timeout')} initialValue={60}
                      rules={[{ required: true, message: t('validation.timeout') }]} extra={t('mcp.timeoutHint')}>
                      <InputNumber min={1} max={600} precision={0} style={{ width: '100%' }} />
                    </Form.Item>
                  </>
                )}
              </Form.Item>
            </>
          )}
          {accessMode === 'manual' && selectedType === 'SKILL' && (
            <Form.Item name="installSpec" label={t('access.install')} rules={[{ required: true, message: t('validation.install') }]}
              tooltip={t('placeholder.installHint')}>
              <Input.TextArea rows={2} placeholder={t('placeholder.install')} />
            </Form.Item>
          )}
          <Form.Item name="description" label={t('description')}>
            <Input.TextArea disabled={accessMode === 'package' && selectedType === 'SKILL'} rows={2} placeholder={t('placeholder.description')} />
          </Form.Item>
        </Form>
      </Modal>
  );
}

/** Tooltip 只透传子元素自身 props，Form.Item 注入的 checked/onChange 必须由组件显式接收再交给 Checkbox。 */
function SecretCheckbox({ checked, onChange, label, hint }: {
  checked?: boolean;
  onChange?: CheckboxProps['onChange'];
  label: string;
  hint: string;
}) {
  return (
    <Tooltip title={hint}>
      <Checkbox checked={checked} onChange={onChange} style={{ whiteSpace: 'nowrap' }}>{label}</Checkbox>
    </Tooltip>
  );
}
