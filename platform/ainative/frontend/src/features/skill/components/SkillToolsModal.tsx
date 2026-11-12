import { Collapse, Modal, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { SkillConnectionTestResult } from '../api';

/** 连接测试返回的工具清单。 */
export function SkillToolsModal({ result, onClose }: {
  result: SkillConnectionTestResult | null;
  onClose: () => void;
}) {
  const { t } = useTranslation('skill');
  return (
      <Modal closable={{ 'aria-label': t('close') }} title={t('connection.tools', { count: result?.tools?.length || 0 })} open={result !== null}
        footer={null} onCancel={onClose} width={680}>
        {(result?.tools || []).length > 0 && <Collapse
          expandIconPosition="start"
          items={(result?.tools || []).map((tool, index) => ({
            key: `${tool.name || 'tool'}-${index}`,
            collapsible: 'icon',
            label: <Typography.Text strong>{tool.name || '-'}</Typography.Text>,
            children: <>
              {tool.description && <Typography.Paragraph>{tool.description}</Typography.Paragraph>}
              {tool.inputSchema !== undefined && <Typography.Paragraph code style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
                {JSON.stringify(tool.inputSchema, null, 2)}
              </Typography.Paragraph>}
            </>,
          }))}
        />}
        {result?.tools?.length === 0 && <Typography.Text type="secondary">{t('connection.emptyTools')}</Typography.Text>}
      </Modal>
  );
}
