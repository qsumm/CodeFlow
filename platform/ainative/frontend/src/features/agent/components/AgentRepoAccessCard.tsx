import { Alert, Button, Card } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { Table } from '@/shared/theme/ThemedTable';
import type { ColumnsType } from 'antd/es/table';
import type { RepoPermRow } from '../agentEditTypes';

/** 仓库授权卡:平台内置代理只读展示,标准代理可增删。 */
export function AgentRepoAccessCard({ isPlatform, columns, dataSource, onAdd }: {
  isPlatform: boolean;
  columns: ColumnsType<RepoPermRow>;
  dataSource: RepoPermRow[];
  onAdd: () => void;
}) {
  const { t } = useTranslation('agent');
  return (
      <Card title={t('repository.title')} className="cfds-card-shell"
        extra={isPlatform ? null : (
          <Button size="small" icon={<PlusOutlined aria-hidden="true" />}
            onClick={onAdd}>
            {t('repository.add')}
          </Button>
        )}
      >
        {isPlatform && (
          <Alert
            type="info"
            showIcon
            message={t('repository.platformTitle')}
            description={t('repository.platformHint')}
            style={{ marginBottom: 12 }}
          />
        )}
        <div className="cfds-table-wrap">
          <Table rowKey="repoId" columns={columns} dataSource={dataSource} pagination={false} size="small" />
        </div>
      </Card>
  );
}
