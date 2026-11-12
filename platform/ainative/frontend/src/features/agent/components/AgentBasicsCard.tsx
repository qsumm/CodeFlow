import type { FocusEvent, KeyboardEvent, Ref } from 'react';
import { useTranslation } from 'react-i18next';
import { AutoComplete, Button, Card, Form, Input, Select, Space } from 'antd';
import { SaveOutlined, SendOutlined } from '@ant-design/icons';
import type { FormInstance } from 'antd';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import { PageHeading } from '@/shared/ui/PageHeading';
import { PageBackButton } from '@/shared/ui/PageBackButton';
import { getAgentRoleCodeOptions, getAgentRoleNameOptions, getEvolutionModeOptions } from '../constants';
import type { Agent, AgentVersion } from '../api';
import type { SdlcTemplate } from '@/features/sdlc/api';

const { TextArea } = Input;

export interface AgentFormValues {
  name?: string;
  avatarUrl?: string;
  roleName?: string;
  roleCode?: string;
  businessBackground?: string;
  responsibilities?: string;
  sdlcId?: number | null;
  evolutionMode?: 'MANUAL' | 'ASSISTED' | 'AUTO_PROPOSAL';
}

/** 基本信息卡:名称/头像/角色/身份文件/流水线/进化模式。 */
export function AgentBasicsCard({
  agentId, agent, versionDetail, isPlatform, form, businessBackgroundRef,
  sdlcsList, sdlcStatusLabels, editConfigPending, updateAgentPending, submitForReviewPending,
  serverOperationPending, onSave, onSubmit, onRoleNameSelect, onRoleCodeSelect,
  onRoleCodeBlur, onRoleCodeKeyDown,
}: {
  agentId: number;
  agent: Agent;
  versionDetail: AgentVersion | undefined;
  isPlatform: boolean;
  form: FormInstance;
  businessBackgroundRef: Ref<TextAreaRef>;
  sdlcsList: SdlcTemplate[];
  sdlcStatusLabels: Map<string, string>;
  editConfigPending: boolean;
  updateAgentPending: boolean;
  submitForReviewPending: boolean;
  serverOperationPending: boolean;
  onSave: () => void;
  onSubmit: () => void;
  onRoleNameSelect: (value: string) => void;
  onRoleCodeSelect: (value: string) => void;
  onRoleCodeBlur: (e: FocusEvent<HTMLInputElement>) => void;
  onRoleCodeKeyDown: (e: KeyboardEvent<HTMLInputElement>) => void;
}) {
  const { t } = useTranslation('agent');
  return (
      <Card className="cfds-card-shell cf-content-card" title={<PageHeading title={<span className="cf-detail-title"><PageBackButton to={`/agents/${agentId}`} label={t('actions.backDetail')} /><span>{t('edit.title', { name: agent.name })}</span></span>} />}
        extra={
          <Space>
            <Button icon={<SaveOutlined aria-hidden="true" />} onClick={onSave}
              loading={editConfigPending || updateAgentPending}
              disabled={serverOperationPending}>
              {t('actions.saveDraft')}
            </Button>
            <Button type="primary" icon={<SendOutlined aria-hidden="true" />} onClick={onSubmit}
              loading={submitForReviewPending}
              disabled={serverOperationPending}>
              {t('actions.submitButton')}
            </Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical" className="cfds-form">
          <Form.Item
            label={t('fields.agentName')}
            name="name"
            rules={[{ required: true, message: t('validation.agentName') }]}
            extra={isPlatform
              ? t('edit.platformName')
              : t('edit.nameHint')}
          >
            <Input placeholder={t('placeholders.agentName')} maxLength={64} disabled={isPlatform} />
          </Form.Item>
          <Form.Item
            label={t('fields.avatar')}
            name="avatarUrl"
            extra={isPlatform
              ? t('edit.platformAvatar')
              : t('edit.avatarHint')}
          >
            <Input placeholder="https://..." allowClear disabled={isPlatform} />
          </Form.Item>
          <Form.Item label={t('fields.roleName')} name="roleName" rules={[{ required: true, message: t('validation.roleName') }]}>
            <AutoComplete
              options={getAgentRoleNameOptions()}
              allowClear
              placeholder={t('placeholders.roleName')}
              onSelect={onRoleNameSelect}
            />
          </Form.Item>
          <Form.Item label={t('fields.roleCode')} name="roleCode" rules={[{ required: true, message: t('validation.roleCode') }]}>
            <AutoComplete
              options={getAgentRoleCodeOptions()}
              allowClear
              placeholder={t('placeholders.roleCode')}
              onSelect={onRoleCodeSelect}
              onBlur={onRoleCodeBlur}
              onKeyDown={onRoleCodeKeyDown}
            />
          </Form.Item>
          <Form.Item label="SOUL.md" name={isPlatform ? undefined : "businessBackground"}>
            <TextArea
              readOnly={isPlatform}
              value={isPlatform ? agent?.officialConfiguration?.businessBackground ?? versionDetail?.businessBackground : undefined}
              ref={businessBackgroundRef}
              rows={4}
              placeholder={t('placeholders.background')}
            />
          </Form.Item>
          <Form.Item label="AGENT.md" name={isPlatform ? undefined : "responsibilities"}>
            <TextArea readOnly={isPlatform} value={isPlatform ? agent?.officialConfiguration?.responsibilities ?? versionDetail?.responsibilities : undefined} rows={4} placeholder={t('placeholders.responsibilities')} />
          </Form.Item>
          {!isPlatform && (
            <Form.Item label={t('fields.sdlc')} name="sdlcId">
              <Select allowClear placeholder={t('placeholders.sdlc')}
                options={sdlcsList.map(s => ({ value: s.id, label: t('namedLabel', { name: s.name, label: sdlcStatusLabels.get(s.status) || s.status }) })) || []}
              />
            </Form.Item>
          )}
          <Form.Item
            label={t('fields.evolution')}
            name="evolutionMode"
            tooltip={t('evolution.tooltip')}
          >
            <Select
              aria-label={t('fields.evolution')}
              options={getEvolutionModeOptions()}
              optionRender={(option) => {
                const descriptions: Record<string, string> = {
                  MANUAL: t('evolutionDescription.MANUAL'),
                  ASSISTED: t('evolutionDescription.ASSISTED'),
                  AUTO_PROPOSAL: t('evolutionDescription.AUTO_PROPOSAL'),
                };
                return (
                  <Space direction="vertical" size={0}>
                    <span>{option.label}</span>
                    <span style={{ fontSize: 12, color: 'var(--cf-muted)' }}>{descriptions[String(option.value)]}</span>
                  </Space>
                );
              }}
            />
          </Form.Item>
        </Form>
      </Card>
  );
}
