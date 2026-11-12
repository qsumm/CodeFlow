import { Form, Modal, Select, Typography } from 'antd';
import { useTranslation } from 'react-i18next';

/** 批量设置分类。 */
export function SkillBatchCategoryModal({
  open, selectedCount, value, options, applying, onChange, onApply, onClose,
}: {
  open: boolean;
  selectedCount: number;
  value: string | undefined;
  options: Array<{ value: string; label: string }>;
  applying: boolean;
  onChange: (value: string) => void;
  onApply: () => void;
  onClose: () => void;
}) {
  const { t } = useTranslation('skill');
  return (
      <Modal
        closable={{ 'aria-label': t('close') }}
        title={t('category.batch')}
        open={open}
        onCancel={onClose}
        onOk={onApply}
        confirmLoading={applying}
        okText={t('apply')}
        width={520}
      >
        <Typography.Paragraph type="secondary">
          {t('category.batchHint', { count: selectedCount })}
        </Typography.Paragraph>
        <Form layout="vertical">
          <Form.Item label={t('category.target')}>
            <Select
              value={value}
              onChange={onChange}
              options={options}
              aria-label={t('category.target')}
            />
          </Form.Item>
        </Form>
      </Modal>
  );
}
