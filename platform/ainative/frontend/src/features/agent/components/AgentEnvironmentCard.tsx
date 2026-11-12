import { Alert, Button, Card, Space, Spin } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { Table } from '@/shared/theme/ThemedTable';
import type { ColumnsType } from 'antd/es/table';
import type { AgentEnvironmentVariableRef } from '../api';

function errorMessage(e: unknown, fallback: string) {
  return e instanceof Error && e.message ? e.message : fallback;
}

/** 环境变量卡:只展示已挂载元数据,值永不下发明文。 */
export function AgentEnvironmentCard({
  canManage, columns, dataSource, loading, error, fetching, errorValue, onRetry,
  bindingError, onDismissBindingError, onMount, pending, versionReady,
}: {
  canManage: boolean;
  columns: ColumnsType<AgentEnvironmentVariableRef>;
  dataSource: AgentEnvironmentVariableRef[];
  loading: boolean;
  error: boolean;
  fetching: boolean;
  errorValue: unknown;
  onRetry: () => void;
  bindingError: { error: unknown } | undefined;
  onDismissBindingError: () => void;
  onMount: () => void;
  pending: boolean;
  versionReady: boolean;
}) {
  const { t } = useTranslation('agent');
  return (
      <Card title={t('environment.title')} className="cfds-card-shell"
        extra={canManage ? (
          <Button
            size="small"
            icon={<PlusOutlined aria-hidden="true" />}
            onClick={onMount}
            disabled={pending || !versionReady}
          >
            {t('environment.mount')}
          </Button>
        ) : null}
      >
        <Alert
          type="info"
          showIcon
          message={t('environment.draftHint')}
          description={t('environment.valueHint')}
          style={{ marginBottom: 12 }}
        />
        {bindingError && (
          <Alert
            type="error"
            showIcon
            message={errorMessage(bindingError.error, t('errors.unmount'))}
            closable
            onClose={onDismissBindingError}
            style={{ marginBottom: 12 }}
          />
        )}
        {loading ? (
          <Space>
            <Spin size="small" />
            <span>{t('environment.loading')}</span>
          </Space>
        ) : error ? (
          <Alert
            type="error"
            showIcon
            message={t('errors.bindingsLoad')}
            description={errorMessage(errorValue, t('errors.versionLoad'))}
            action={(
              <Button size="small" loading={fetching} onClick={onRetry}>
                {t('actions.retry')}
              </Button>
            )}
          />
        ) : (
          <div className="cfds-table-wrap">
            <Table
              rowKey="id"
              columns={columns}
              dataSource={dataSource}
              pagination={false}
              size="small"
              locale={{ emptyText: t('environment.empty') }}
            />
          </div>
        )}
      </Card>
  );
}
