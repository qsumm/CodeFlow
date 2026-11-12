import { useTranslation } from 'react-i18next';
import type { ParseKeys } from 'i18next';
import { i18n } from '@/shared/i18n';
import { formatNumber } from '@/shared/i18n/format';
import { PageHeading } from '@/shared/ui/PageHeading';
import { useEffect, useMemo } from 'react';
import { Card, Tabs, Form, Input, InputNumber, Button, Spin, message, Switch } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { SaveOutlined, EyeOutlined, EyeInvisibleOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { FormInstance } from 'antd/es/form';
import { StatTile, SurfaceCard } from '@/shared/ui/PageShell';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getAiQuota,
  listAiUsage,
  listNotifyPrefs,
  listSettingsByGroup,
  updateAiQuota,
  updateNotifyPrefs,
  updateSettings,
} from './api';
import type { AiUsageVO, SettingItem, SettingVO, UpdateQuotaRequest } from './api';
import { getPlatformImChannels, PLATFORM_IM_CHANNELS_QUERY_KEY, selectedImProvider } from '@/features/platform/brandingApi';
import { Alert } from 'antd';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';


type SettingSchema = {
  key: string;
  labelKey: ParseKeys<'settings'>;
  hintKey?: ParseKeys<'settings'>;
  secret?: boolean;
  placeholderKey?: ParseKeys<'settings'>;
  /**
   * 字段类型。服务端统一把设置存成字符串，boolean 字段存的是 'true' / 'false'，
   * 但界面必须用开关呈现 —— 让用户去文本框里手打 true/false 是把存储格式漏给了用户。
   */
  type?: 'text' | 'boolean';
};

const AI_SETTINGS: SettingSchema[] = [
  { key: 'default_model', labelKey: 'defaultModel', hintKey: 'defaultModelHint' },
  { key: 'request_timeout_seconds', labelKey: 'timeout', hintKey: 'secondsHint' },
  { key: 'api_key', labelKey: 'apiKey', hintKey: 'secretHint', secret: true, placeholderKey: 'replaceSecret' },
];

const NOTIFY_SETTINGS: SettingSchema[] = [
  { key: 'dingtalk_enabled', labelKey: 'enableDingTalk', hintKey: 'booleanHint', type: 'boolean' },
];

const SYSTEM_SETTINGS: SettingSchema[] = [
  { key: 'default_sdlc_id', labelKey: 'defaultSdlc', hintKey: 'defaultSdlcHint' },
  { key: 'default_status_template_id', labelKey: 'defaultTemplate', hintKey: 'defaultTemplateHint' },
  { key: 'artifact_bucket', labelKey: 'bucket', hintKey: 'bucketHint' },
  { key: 'upload_max_size_mb', labelKey: 'uploadLimit', hintKey: 'mbHint' },
];

const NOTIFY_TYPES = [
  { type: 'WORKITEM_ASSIGNED', labelKey: 'assigned' },
  { type: 'DELIVERY_BLOCKED', labelKey: 'deliveryBlocked' },
  { type: 'AI_SESSION_DONE', labelKey: 'aiDone' },
  { type: 'REVIEW_REQUIRED', labelKey: 'reviewRequired' },
] as const;

function settingsToInitialValues(schema: SettingSchema[], settings: SettingVO[]) {
  const values = new Map(settings.map((item) => [item.key, item.valueJson]));
  return Object.fromEntries(schema.map((item) => {
    const raw = values.get(item.key) ?? '';
    // 布尔字段落库是 'true'/'false' 字符串,回填时转成真正的布尔值给开关用
    return [item.key, item.type === 'boolean' ? raw === 'true' : raw];
  }));
}

function buildSettingItems(schema: SettingSchema[], values: Record<string, string | boolean | undefined>): SettingItem[] {
  return schema.flatMap((item) => {
    const raw = values[item.key];
    if (item.secret && (!raw || /^\*+$/.test(String(raw)))) {
      return [];
    }
    // 回写仍是字符串:服务端契约是 valueJson,布尔值统一存 'true'/'false'
    const valueJson = item.type === 'boolean' ? String(Boolean(raw)) : String(raw ?? '');
    return [{ key: item.key, valueJson, secret: Boolean(item.secret) }];
  });
}

function SettingFields({ schema }: { schema: SettingSchema[] }) {
  const { t } = useTranslation('settings');
  // 控件按类型分派:敏感项用密码框,布尔项用开关,其余用文本框。
  // 放在 JSX 之外,避免把内部实现用的类型字面量混进 JSX 表达式。
  const control = (item: SettingSchema) => {
    if (item.secret) {
      return <Input.Password placeholder={item.placeholderKey ? t(item.placeholderKey) : undefined} iconRender={(visible) => <Button type="text" htmlType="button" aria-label={visible ? t('hideSecret') : t('showSecret')} icon={visible ? <EyeOutlined aria-hidden="true" /> : <EyeInvisibleOutlined aria-hidden="true" />} />} />;
    }
    if (item.type === 'boolean') {
      return <Switch aria-label={t(item.labelKey)} />;
    }
    return <Input />;
  };
  return (
    <>
      {schema.map((item) => (
        <Form.Item key={item.key} label={t(item.labelKey)} name={item.key}
          valuePropName={item.type === 'boolean' ? 'checked' : 'value'}
          extra={item.hintKey ? t(item.hintKey) : undefined}>
          {control(item)}
        </Form.Item>
      ))}
    </>
  );
}

function useSettingsGroup(group: string, form: FormInstance<Record<string, string | boolean>>, schema: SettingSchema[]) {
  const query = useQuery({
    queryKey: ['settings', group],
    queryFn: () => listSettingsByGroup(group),
  });

  useEffect(() => {
    if (query.data) {
      form.setFieldsValue(settingsToInitialValues(schema, query.data));
    }
  }, [form, query.data, schema]);

  return query;
}

export function AiSettingsTab() {
  const { t } = useTranslation('settings');
  const queryClient = useQueryClient();
  const runAccessCommand = useAccessCommand();
  const [quotaForm] = Form.useForm<UpdateQuotaRequest>();
  const [settingsForm] = Form.useForm<Record<string, string | boolean>>();

  const quotaQuery = useQuery({ queryKey: ['ai-quota'], queryFn: getAiQuota });
  const usageQuery = useQuery({ queryKey: ['ai-usage'], queryFn: listAiUsage });
  const settingsQuery = useSettingsGroup('AI', settingsForm, AI_SETTINGS);

  useEffect(() => {
    if (quotaQuery.data) {
      quotaForm.setFieldsValue({
        maxCalls: quotaQuery.data.maxCalls,
        maxTokens: quotaQuery.data.maxTokens,
        concurrencyLimit: quotaQuery.data.concurrencyLimit,
      });
    }
  }, [quotaForm, quotaQuery.data]);

  const saveMut = useMutation({
    mutationFn: async () => {
      const quotaValues = await quotaForm.validateFields();
      const settingValues = settingsForm.getFieldsValue();
      await Promise.all([
        updateAiQuota({
          maxCalls: quotaValues.maxCalls ?? null,
          maxTokens: quotaValues.maxTokens ?? null,
          concurrencyLimit: quotaValues.concurrencyLimit ?? null,
        }),
        updateSettings('AI', buildSettingItems(AI_SETTINGS, settingValues)),
      ]);
    },
    onSuccess: () => {
      message.success(i18n.t('settings:aiSaved'));
      queryClient.invalidateQueries({ queryKey: ['ai-quota'] });
      queryClient.invalidateQueries({ queryKey: ['settings', 'AI'] });
    },
  });

  if (quotaQuery.isLoading || usageQuery.isLoading || settingsQuery.isLoading) {
    return <Spin />;
  }

  const usage = usageQuery.data ?? [];
  const totalCalls = usage.reduce((sum, item) => sum + item.callCount, 0);
  const totalTokens = usage.reduce((sum, item) => sum + item.inputTokens + item.outputTokens, 0);

  const usageColumns: ColumnsType<AiUsageVO> = [
    { title: t('scene'), dataIndex: 'scene' },
    { title: t('callCount'), dataIndex: 'callCount', render: (value: number) => formatNumber(value) },
    { title: t('inputTokens'), dataIndex: 'inputTokens', render: (value: number) => formatNumber(value) },
    { title: t('outputTokens'), dataIndex: 'outputTokens', render: (value: number) => formatNumber(value) },
  ];

  return (
    <div className="cfds-list-layout">
      <div className="cfds-stat-row">
        <StatTile label={t('monthCalls')} value={formatNumber(totalCalls)} />
        <StatTile label={t('tokenUsage')} value={formatNumber(totalTokens)} />
        <StatTile label={t('activeScenes')} value={formatNumber(usage.length)} />
      </div>

      <div className="cfds-grid-2">
        <SurfaceCard title={t('aiQuota')} className="ant-card">
          <Form form={quotaForm} layout="vertical" className="cfds-form">
            {/* ponytail: upstream steppers hardcode English aria labels; restore pointer controls when the public API supports localized parent names. */}
            <Form.Item label={t('monthlyCalls')} name="maxCalls">
              <InputNumber controls={false} min={0} />
            </Form.Item>
            <Form.Item label={t('monthlyTokens')} name="maxTokens">
              <InputNumber controls={false} min={0} />
            </Form.Item>
            <Form.Item label={t('concurrency')} name="concurrencyLimit">
              <InputNumber controls={false} min={0} />
            </Form.Item>
          </Form>
        </SurfaceCard>
        <SurfaceCard title={t('modelDefaults')} className="ant-card">
          <Form form={settingsForm} layout="vertical" className="cfds-form">
            <SettingFields schema={AI_SETTINGS} />
          </Form>
        </SurfaceCard>
      </div>

      <SurfaceCard title={t('sceneUsage')} className="ant-card">
        <div className="cfds-table-wrap">
          <Table rowKey={(row) => row.scene} columns={usageColumns} dataSource={usage} pagination={false} size="small" />
        </div>
      </SurfaceCard>

      <div>
        <Button type="primary" icon={<SaveOutlined aria-hidden="true" />} aria-label={t('saveAi')} loading={saveMut.isPending} onClick={() =>
          runAccessCommand('ADMIN', t('saveAi'), () => saveMut.mutate())
        }>
          {t('saveAi')}
        </Button>
      </div>
    </div>
  );
}

export function NotifySettingsTab() {
  const { t } = useTranslation('settings');
  const queryClient = useQueryClient();
  const runAccessCommand = useAccessCommand();
  const [prefForm] = Form.useForm<Record<string, boolean>>();
  const [settingsForm] = Form.useForm<Record<string, string | boolean>>();

  const channelQuery = useQuery({ queryKey: PLATFORM_IM_CHANNELS_QUERY_KEY, queryFn: getPlatformImChannels });
  const provider = selectedImProvider(channelQuery.data ?? []);
  const providerKey = provider === 'FEISHU' ? 'feishu' : 'dingtalk';
  const providerLabel = provider === 'FEISHU' ? t('feishu') : t('dingtalk');
  const notifySettings = useMemo(() => NOTIFY_SETTINGS.map((item) => ({
    ...item, key: `${providerKey}_enabled`, labelKey: providerKey === 'feishu' ? 'enableFeishu' as const : item.labelKey,
  })), [providerKey]);
  const prefsQuery = useQuery({ queryKey: ['notify-prefs'], queryFn: listNotifyPrefs });
  const settingsQuery = useSettingsGroup('NOTIFY', settingsForm, notifySettings);

  useEffect(() => {
    const prefs = prefsQuery.data ?? [];
    const byType = new Map(prefs.map((item) => [item.type, item]));
    const values = Object.fromEntries(NOTIFY_TYPES.flatMap(({ type }) => {
      const pref = byType.get(type);
      return [
        [`${type}.inApp`, pref?.inApp ?? true],
        [`${type}.${providerKey}`, pref?.[providerKey] ?? false],
      ];
    }));
    prefForm.setFieldsValue(values);
  }, [prefForm, prefsQuery.data, providerKey]);

  const saveMut = useMutation({
    mutationFn: async () => {
      const prefValues = prefForm.getFieldsValue();
      const settingValues = settingsForm.getFieldsValue();
      await Promise.all([
        updateNotifyPrefs(NOTIFY_TYPES.map(({ type }) => ({
          type,
          inApp: Boolean(prefValues[`${type}.inApp`]),
          dingtalk: providerKey === 'dingtalk' && Boolean(prefValues[`${type}.dingtalk`]),
          feishu: providerKey === 'feishu' && Boolean(prefValues[`${type}.feishu`]),
        }))),
        updateSettings('NOTIFY', buildSettingItems(notifySettings, settingValues)),
      ]);
    },
    onSuccess: () => {
      message.success(i18n.t('settings:notifySaved'));
      queryClient.invalidateQueries({ queryKey: ['notify-prefs'] });
      queryClient.invalidateQueries({ queryKey: ['settings', 'NOTIFY'] });
    },
  });

  if (channelQuery.isLoading || prefsQuery.isLoading || settingsQuery.isLoading) {
    return <Spin />;
  }

  if (channelQuery.isError || prefsQuery.isError || settingsQuery.isError) return <Alert type="error" message={t('notifyLoadFailed')} />;

  const prefColumns: ColumnsType<{ type: string; label: string }> = [
    { title: t('eventType'), dataIndex: 'label' },
    {
      title: t('inApp'),
      render: (_, row) => (
        <Form.Item name={`${row.type}.inApp`} valuePropName="checked" noStyle>
          <Switch aria-label={t('preferenceName', { event: row.label, channel: t('inApp') })} />
        </Form.Item>
      ),
    },
    {
      title: providerLabel,
      render: (_, row) => (
        <Form.Item name={`${row.type}.${providerKey}`} valuePropName="checked" noStyle>
          <Switch aria-label={t('preferenceName', { event: row.label, channel: providerLabel })} />
        </Form.Item>
      ),
    },
  ];

  return (
    <div className="cfds-list-layout">
      <div className="cfds-grid-2">
        <SurfaceCard title={t('notifyPrefs')} className="ant-card">
          <Form form={prefForm}>
            <div className="cfds-table-wrap">
              <Table rowKey="type" columns={prefColumns} dataSource={NOTIFY_TYPES.map(item => ({ ...item, label: t(item.labelKey) }))} pagination={false} size="small" />
            </div>
          </Form>
        </SurfaceCard>
        <SurfaceCard title={t('notifyChannel')} className="ant-card" testId="notify-settings-panel">
          <Form form={settingsForm} layout="vertical" className="cfds-form">
            <Alert type="info" message={t('providerHint', { provider: providerLabel })} style={{ marginBottom: 16 }} />
            <SettingFields schema={notifySettings} />
          </Form>
        </SurfaceCard>
      </div>

      <div>
        <Button type="primary" icon={<SaveOutlined aria-hidden="true" />} aria-label={t('saveNotify')} loading={saveMut.isPending} onClick={() =>
          runAccessCommand('ADMIN', t('saveNotify'), () => saveMut.mutate())
        }>
          {t('saveNotify')}
        </Button>
      </div>
    </div>
  );
}

export function SystemSettingsTab() {
  const { t } = useTranslation('settings');
  const queryClient = useQueryClient();
  const runAccessCommand = useAccessCommand();
  const [form] = Form.useForm<Record<string, string | boolean>>();
  const settingsQuery = useSettingsGroup('SYSTEM', form, SYSTEM_SETTINGS);

  const saveMut = useMutation({
    mutationFn: () => updateSettings('SYSTEM', buildSettingItems(SYSTEM_SETTINGS, form.getFieldsValue())),
    onSuccess: () => {
      message.success(i18n.t('settings:systemSaved'));
      queryClient.invalidateQueries({ queryKey: ['settings', 'SYSTEM'] });
    },
  });

  if (settingsQuery.isLoading) {
    return <Spin />;
  }

  return (
    <div className="cfds-list-layout">
      <SurfaceCard title={t('systemDefaults')} className="ant-card">
        <Form form={form} layout="vertical" className="cfds-form">
          <SettingFields schema={SYSTEM_SETTINGS} />
        </Form>
      </SurfaceCard>
      <div>
        <Button type="primary" icon={<SaveOutlined aria-hidden="true" />} aria-label={t('saveSystem')} loading={saveMut.isPending} onClick={() =>
          runAccessCommand('ADMIN', t('saveSystem'), () => saveMut.mutate())
        }>
          {t('saveSystem')}
        </Button>
      </div>
    </div>
  );
}

export function SettingsPage() {
  const { t } = useTranslation('settings');
  return (
    <Card className="cf-content-card" title={<PageHeading title={t('settings')} description={t('settingsDescription')} />}>
      <Tabs
        items={[
          { key: 'AI', label: t('aiTab'), children: <AiSettingsTab /> },
          { key: 'NOTIFY', label: t('notifyTab'), children: <NotifySettingsTab /> },
          { key: 'SYSTEM', label: t('systemTab'), children: <SystemSettingsTab /> },
        ]}
      />
    </Card>
  );
}
