import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Alert, Button, Form, Input, InputNumber, message, Select, Table } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { SurfaceCard } from '@/shared/ui/PageShell';
import {
  createZentaoBinding,
  listZentaoBindings,
  searchZentaoProjects,
  testZentaoConnection,
  type ZentaoBinding,
  type ZentaoBindingRequest,
  type ZentaoProject,
} from './zentaoApi';

/** 禅道绑定面板:连接 → 选产品 → 保存;列表展示已建绑定。 */
export function ZentaoIntegrationPanel() {
  const { t } = useTranslation('integration');
  const [form] = Form.useForm();
  useLocalizedValidation(form);
  const queryClient = useQueryClient();
  const [testResult, setTestResult] = useState<string | null>(null);
  const [projects, setProjects] = useState<ZentaoProject[]>([]);

  const { data: bindings = [], isLoading: bindingsLoading } = useQuery({
    queryKey: ['zentao-bindings'],
    queryFn: listZentaoBindings,
  });

  const testMutation = useMutation({
    mutationFn: () => testZentaoConnection(credentials()),
    onSuccess: (result) => {
      setTestResult(result.message);
      if (result.success) message.success(t('zentao.connectionSucceeded'));
      else message.error(result.message);
    },
    onError: (error: Error) => message.error(error.message),
  });

  const searchMutation = useMutation({
    mutationFn: () => searchZentaoProjects(credentials()),
    onSuccess: (result) => {
      setProjects(result);
      message.success(t('zentao.projectsFound', { count: result.length }));
    },
    onError: (error: Error) => message.error(error.message),
  });

  const saveMutation = useMutation({
    mutationFn: async () => {
      const values = await form.validateFields();
      return createZentaoBinding({
        baseUrl: values.baseUrl,
        account: values.account,
        password: values.password,
        externalProjectId: values.externalProjectId,
        externalProjectName: projectName(values.externalProjectId),
        pollIntervalSeconds: values.pollIntervalSeconds,
        enabled: true,
      });
    },
    onSuccess: () => {
      message.success(t('zentao.bindingSaved'));
      queryClient.invalidateQueries({ queryKey: ['zentao-bindings'] });
    },
    onError: (error: Error) => message.error(error.message),
  });

  function credentials(): ZentaoBindingRequest {
    const values = form.getFieldsValue(['baseUrl', 'account', 'password']);
    if (!values.baseUrl || !values.account || !values.password) {
      throw new Error(t('zentao.credentialsRequired'));
    }
    return { baseUrl: values.baseUrl, account: values.account, password: values.password };
  }

  function projectName(externalId?: string) {
    return projects.find((p) => p.externalId === externalId)?.name ?? externalId;
  }

  return (
    <div className="cfds-list-layout">
      <SurfaceCard title={t('zentao.formTitle')} className="ant-card">
        <Form form={form} layout="vertical" className="cfds-form">
          <Form.Item label={t('zentao.baseUrl')} name="baseUrl"
            rules={[{ required: true, message: t('zentao.baseUrlRequired') }]}>
            <Input placeholder={t("zentao.baseUrlPlaceholder")} />
          </Form.Item>
          <Form.Item label={t('zentao.account')} name="account"
            rules={[{ required: true, message: t('zentao.accountRequired') }]}>
            <Input autoComplete="off" />
          </Form.Item>
          <Form.Item label={t('zentao.password')} name="password"
            rules={[{ required: true, message: t('zentao.passwordRequired') }]}>
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item label={t('zentao.pollInterval')} name="pollIntervalSeconds" initialValue={60}>
            <InputNumber min={15} max={3600} style={{ width: '100%' }} />
          </Form.Item>
          <div style={{ display: 'flex', gap: 8 }}>
            <Button onClick={() => testMutation.mutate()} loading={testMutation.isPending}>
              {t('zentao.testConnection')}
            </Button>
            <Button onClick={() => searchMutation.mutate()} loading={searchMutation.isPending}>
              {t('zentao.loadProducts')}
            </Button>
          </div>
          {testResult && <Alert style={{ marginTop: 12 }} type={testResult.includes('成功') ? 'success' : 'error'} showIcon message={testResult} />}
        </Form>
      </SurfaceCard>
      <SurfaceCard title={t('zentao.productTitle')} className="ant-card">
        <Form form={form} layout="vertical" className="cfds-form">
          <Form.Item label={t('zentao.product')} name="externalProjectId"
            rules={[{ required: true, message: t('zentao.productRequired') }]}>
            <Select
              placeholder={t('zentao.productPlaceholder')}
              options={projects.map((p) => ({ value: p.externalId, label: p.name || p.externalId }))}
              notFoundContent={t('zentao.loadProductsFirst')}
            />
          </Form.Item>
          <Button type="primary" onClick={() => saveMutation.mutate()} loading={saveMutation.isPending}>
            {t('zentao.saveBinding')}
          </Button>
        </Form>
      </SurfaceCard>
      <SurfaceCard title={t('zentao.bindingsTitle')} className="ant-card">
        <div className="cfds-table-wrap">
          <Table<ZentaoBinding>
            rowKey={(r) => String(r.id)}
            loading={bindingsLoading}
            dataSource={bindings}
            columns={[
              { title: t('zentao.productColumn'), dataIndex: 'externalProjectName', render: (v, r) => v || r.externalProjectId },
              { title: t('zentao.urlColumn'), dataIndex: 'baseUrl', ellipsis: true },
              { title: t('zentao.statusColumn'), dataIndex: 'enabled',
                render: (v) => (v ? t('zentao.enabled') : t('zentao.disabled')) },
            ]}
          />
        </div>
      </SurfaceCard>
    </div>
  );
}
