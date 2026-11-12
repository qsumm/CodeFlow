import type { Key } from 'react';
import { Alert, Button, Divider, Space, Spin, Tree, Typography } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import type { SkillPackageFile, SkillPackageFileContent } from '../api';
import type { SkillPackageTreeNode } from '../skillPackage';
import { formatBytes } from '../skillPackage';

function isMarkdown(path: string): boolean {
  return /\.(md|markdown)$/i.test(path);
}

export interface SkillPackagePreviewProps {
  packageFiles: SkillPackageFile[];
  packageFilesLoading: boolean;
  packageFilesError: { message?: string } | null;
  packageTree: SkillPackageTreeNode[];
  selectedPackagePath: string | null;
  selectedPackageEntry: SkillPackageFile | null;
  packageFileContent: SkillPackageFileContent | null;
  packageFileLoading: boolean;
  downloadingPackage: boolean;
  onSelect: (keys: Key[]) => void;
  onDownload: () => void;
}

/** 技能包清单树与右侧文件预览。 */
export function SkillPackagePreview({
  packageFiles, packageFilesLoading, packageFilesError, packageTree,
  selectedPackagePath, selectedPackageEntry, packageFileContent, packageFileLoading,
  downloadingPackage, onSelect, onDownload,
}: SkillPackagePreviewProps) {
  const { t } = useTranslation('skill');

  const renderPackagePreview = () => {
    if (!selectedPackagePath || !selectedPackageEntry) {
      return <Typography.Text type="secondary">{i18n.t('skill:package.selectPreview')}</Typography.Text>;
    }
    if (selectedPackageEntry.dir) {
      const prefix = `${selectedPackagePath}/`;
      const childCount = packageFiles.filter((file) => file.path.startsWith(prefix)
        && !file.path.substring(prefix.length).includes('/')).length;
      return (
        <Space direction="vertical" size={4}>
          <Typography.Text strong>{selectedPackagePath}</Typography.Text>
          <Typography.Text type="secondary">{t('package.directoryCount', { count: childCount })}</Typography.Text>
        </Space>
      );
    }
    if (selectedPackageEntry.kind !== 'TEXT') {
      return (
        <Space direction="vertical" size={4}>
          <Typography.Text strong>{selectedPackageEntry.name}</Typography.Text>
          <Typography.Text type="secondary">
            {t('package.noPreview', { kind: selectedPackageEntry.kind === 'IMAGE' ? t('package.image') : t('package.binary'), size: formatBytes(selectedPackageEntry.size) })}
          </Typography.Text>
        </Space>
      );
    }
    if (packageFileLoading) {
      return <Spin />;
    }
    if (!packageFileContent) {
      return <Typography.Text type="secondary">{i18n.t('skill:package.loadFailed')}</Typography.Text>;
    }
    return isMarkdown(packageFileContent.path) ? (
      <MarkdownView content={packageFileContent.content} />
    ) : (
      // pre 的 UA 默认 white-space:pre 会关闭折行，超长行会撑破弹窗，这里强制折行
      <pre style={{
        margin: 0,
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-word',
        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
        fontSize: 12,
      }}>
        {packageFileContent.content}
      </pre>
    );
  };

  return (
    <>
      <Divider style={{ margin: 0 }} />
      <div>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <Typography.Text strong>{t('package.contents')}</Typography.Text>
          <Button
            size="small"
            icon={<DownloadOutlined aria-hidden="true" />}
            loading={downloadingPackage}
            onClick={onDownload}
          >
            {t('package.download')}</Button>
        </div>
        {packageFilesError && (
          <Alert type="error" showIcon style={{ marginTop: 8 }} message={packageFilesError.message || t('errors.operation')} />
        )}
        <Spin spinning={packageFilesLoading}>
          <div style={{ display: 'flex', gap: 12, marginTop: 8 }}>
            <div
              data-testid="skill-package-tree"
              style={{
                width: 260,
                flexShrink: 0,
                maxHeight: 300,
                overflow: 'auto',
                border: '1px solid var(--cf-border)',
                borderRadius: 8,
                padding: 8,
              }}
            >
              {packageTree.length > 0 ? (
                <Tree
                  treeData={packageTree}
                  selectedKeys={selectedPackagePath ? [selectedPackagePath] : []}
                  onSelect={onSelect}
                  showLine
                  blockNode
                  defaultExpandAll
                />
              ) : packageFilesLoading || packageFilesError ? null : (
                // 清单拉取失败时上方已有错误 Alert，再显示空态会被误读为包本身没内容
                <Typography.Text type="secondary">{t('package.empty')}</Typography.Text>
              )}
            </div>
            <div
              data-testid="skill-package-preview"
              style={{
                flex: 1,
                minWidth: 0,
                maxHeight: 300,
                overflow: 'auto',
                border: '1px solid var(--cf-border)',
                borderRadius: 8,
                padding: 12,
              }}
            >
              {renderPackagePreview()}
            </div>
          </div>
        </Spin>
      </div>
    </>
  );
}
