import type { Key } from 'react';
import { Button, Descriptions, Divider, Modal, Space, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { SkillPackagePreview } from './SkillPackagePreview';
import type { SkillPackagePreviewProps } from './SkillPackagePreview';
import type { Skill } from '../api';
import {
  accessLabel, detailAccessText, formatDateTime, isPackageSkill, modifierName, skillTypeLabel, typeColor,
} from '../skillFormat';

/** 技能详情弹窗：档案信息 + （OSS_ZIP 才有）能力包清单。 */
export function SkillDetailModal({
  detailSkill, onClose,
  packageFiles, packageFilesLoading, packageFilesError, packageTree,
  selectedPackagePath, selectedPackageEntry, packageFileContent, packageFileLoading,
  downloadingPackage, onPackageSelect, onDownloadPackage,
}: {
  detailSkill: Skill | null;
  onClose: () => void;
  onPackageSelect: (keys: Key[]) => void;
  onDownloadPackage: () => void;
} & Omit<SkillPackagePreviewProps, 'onSelect' | 'onDownload'>) {
  const { t } = useTranslation('skill');
  return (
      <Modal
        closable={{ 'aria-label': t('close') }}
        title={t('detailTitle')}
        open={!!detailSkill}
        onCancel={onClose}
        footer={<Button onClick={onClose}>{t('close')}</Button>}
        width={720}
      >
        {detailSkill && (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Descriptions column={2} size="small" bordered>
              <Descriptions.Item label={t('name')} span={2}>{detailSkill.name}</Descriptions.Item>
              <Descriptions.Item label={t('type')}>
                <Tag color={Object.prototype.hasOwnProperty.call(typeColor, detailSkill.type) ? typeColor[detailSkill.type] : undefined}>{skillTypeLabel(detailSkill.type)}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('access.label')}>{accessLabel(detailSkill)}</Descriptions.Item>
              <Descriptions.Item label={t('category.label')}>{detailSkill.categoryPath || t('uncategorized')}</Descriptions.Item>
              <Descriptions.Item label={t('version')}>{detailSkill.version}</Descriptions.Item>
              <Descriptions.Item label={t('modifier')}>
                {modifierName(detailSkill)}
              </Descriptions.Item>
              <Descriptions.Item label={t('modifiedAt')} span={2}>{formatDateTime(detailSkill.gmtModified)}</Descriptions.Item>
            </Descriptions>
            <div>
              <Typography.Text strong>{t('description')}</Typography.Text>
              <div style={{
                marginTop: 8,
                padding: 12,
                background: 'var(--cf-raised)',
                border: '1px solid var(--cf-border)',
                borderRadius: 8,
                whiteSpace: 'pre-wrap',
              }}>
                {detailSkill.description || t('noDescription')}
              </div>
            </div>
            <div>
              <Typography.Text strong>{detailSkill.sourceType === 'OSS_ZIP' ? t('package.info') : t('access.installCommand')}</Typography.Text>
              <pre style={{
                marginTop: 8,
                padding: 12,
                background: 'var(--cf-raised)',
                color: 'var(--cf-text)',
                borderRadius: 8,
                overflowX: 'auto',
                whiteSpace: 'pre-wrap',
              }}>
                {detailAccessText(detailSkill)}
              </pre>
            </div>
            {detailSkill.packageOssRef && (
              <>
                <Divider style={{ margin: 0 }} />
                <Typography.Text type="secondary">
                  {t('package.ossRef', { ref: detailSkill.packageOssRef })}
                </Typography.Text>
              </>
            )}
            {isPackageSkill(detailSkill) && (
              <SkillPackagePreview
                packageFiles={packageFiles}
                packageFilesLoading={packageFilesLoading}
                packageFilesError={packageFilesError}
                packageTree={packageTree}
                selectedPackagePath={selectedPackagePath}
                selectedPackageEntry={selectedPackageEntry}
                packageFileContent={packageFileContent}
                packageFileLoading={packageFileLoading}
                downloadingPackage={downloadingPackage}
                onSelect={onPackageSelect}
                onDownload={onDownloadPackage}
              />
            )}
          </Space>
        )}
      </Modal>
  );
}
