import { Alert, Button, Card } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { Table } from '@/shared/theme/ThemedTable';
import type { ColumnsType } from 'antd/es/table';
import type { SkillRow } from '../agentEditTypes';

/** 能力绑定卡:内建能力说明 + 已挂载能力表。 */
export function AgentCapabilitiesCard({ columns, dataSource, onAdd }: {
  columns: ColumnsType<SkillRow>;
  dataSource: SkillRow[];
  onAdd: () => void;
}) {
  const { t } = useTranslation('agent');
  return (
      <Card title={t('capability.title')} className="cfds-card-shell"
        extra={<Button size="small" icon={<PlusOutlined aria-hidden="true" />}
          onClick={onAdd}>
          {t('capability.add')}
        </Button>}
      >
        <Alert
          type="info"
          showIcon
          message={t('capability.builtIn')}
          description={t('capability.builtInHint')}
          style={{ marginBottom: 12 }}
        />
        <div className="cfds-table-wrap">
          <Table rowKey="skillId" columns={columns} dataSource={dataSource} pagination={false} size="small" />
        </div>
      </Card>
  );
}
