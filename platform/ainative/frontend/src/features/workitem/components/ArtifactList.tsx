import { useTranslation } from 'react-i18next';
import { Button, Collapse, ConfigProvider, Space, Spin, Tooltip, Typography } from 'antd';
import { DownloadOutlined, EyeOutlined, FileTextOutlined } from '@ant-design/icons';
import type { Artifact, DispatchAttempt } from '@/shared/types/workitem';
import { basename } from '@/shared/lib/artifactLinking';
import { artifactTypeLabel } from '../runtimeLabels';
import { artifactCategories, artifactsForReadingCategory, groupArtifactsByExecution } from '../artifactGrouping';
import { getArtifactDownloadUrl } from '../api';
import type { ConvergedDeliveryStep } from './DeliveryProgress';

const { Text } = Typography;

/** 产物下载:走 a[download] 触发,不新开标签页。 */
export async function downloadArtifact(artifact: Artifact) {
  const url = await getArtifactDownloadUrl(artifact.id);
  const link = document.createElement('a');
  link.href = url;
  link.download = basename(artifact.name);
  link.rel = 'noreferrer';
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  link.remove();
}

export function ArtifactRows({
  artifacts,
  loading,
  onPreview,
}: {
  artifacts: Artifact[];
  loading?: boolean;
  onPreview: (artifact: Artifact) => void;
}) {
  const { t } = useTranslation('workitem');
  if (loading) {
    return <Spin size="small" />;
  }
  if (artifacts.length === 0) {
    return <Text type="secondary" style={{ fontSize: 12 }}>{t('artifacts.none')}</Text>;
  }
  return (
    <Space direction="vertical" size={4} style={{ width: '100%' }}>
      {artifacts.map((artifact) => (
        <Tooltip
          key={String(artifact.id)}
          title={t('artifacts.fileSummary', { name: artifact.name, type: artifactTypeLabel(artifact.type), size: artifact.size != null ? t('artifacts.bytes', { size: artifact.size }) : '' })}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 4, width: '100%' }}>
            <FileTextOutlined aria-hidden="true" style={{ color: 'var(--cf-muted)', flexShrink: 0 }} />
            <Text
              style={{
                flex: 1,
                minWidth: 0,
                fontSize: 12,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {basename(artifact.name)}
            </Text>
            <Button
              type="text"
              size="small"
              aria-label={t('artifacts.previewName', { name: artifact.name })}
              icon={<EyeOutlined aria-hidden="true" />}
              onClick={() => onPreview(artifact)}
              style={{ flexShrink: 0 }}
            />
            <Button
              type="text"
              size="small"
              aria-label={t('artifacts.downloadName', { name: artifact.name })}
              icon={<DownloadOutlined aria-hidden="true" />}
              onClick={() => void downloadArtifact(artifact)}
              style={{ flexShrink: 0 }}
            />
          </div>
        </Tooltip>
      ))}
    </Space>
  );
}

export function ArtifactGroup({ artifacts, steps, executions: attempts, loading, onPreview }: {
  artifacts: Artifact[];
  steps: ConvergedDeliveryStep[];
  executions?: DispatchAttempt[];
  loading?: boolean;
  onPreview: (artifact: Artifact) => void;
}) {
  const { t } = useTranslation('workitem');
  if (loading) return <Spin size="small" />;
  const executions = groupArtifactsByExecution(steps, artifacts, attempts);
  if (!artifacts.length) return <Text type="secondary">{t('artifacts.none')}</Text>;
  return <ConfigProvider theme={{
    token: { fontSize: 12, lineHeight: 1.5 },
    components: { Collapse: { headerPadding: '4px 0', contentPadding: '0 0 4px 12px' } },
  }}><Collapse
    ghost
    size="small"
    defaultActiveKey={executions[0] ? [String(executions[0].dispatchId)] : []}
    items={executions.map((execution, index) => ({
      key: String(execution.dispatchId),
      label: <span title={`Dispatch ${execution.dispatchId}`} style={{ fontSize: 12, lineHeight: '18px', overflowWrap: 'anywhere' }}>{t('artifacts.executionSummary', { latest: index === 0 ? t('artifacts.latest') : '', label: execution.label, count: execution.artifacts.length })}</span>,
      children: execution.artifacts.length === 0 ? <Text type="secondary">{t('artifacts.executionEmpty')}</Text> : <Collapse
        ghost
        size="small"
        defaultActiveKey={['handoff', 'evidence']}
        items={artifactCategories.flatMap(category => {
          const files = artifactsForReadingCategory(execution.artifacts, category.key);
          return files.length ? [{
            key: category.key,
            label: <Tooltip title={category.hint}><Text style={{ fontSize: 12 }}>{t('artifacts.categoryCount', { label: category.label, count: files.length })}</Text></Tooltip>,
            children: <>
              <ArtifactRows artifacts={files} onPreview={onPreview} />
            </>,
          }] : [];
        })}
      />,
    }))}
  /></ConfigProvider>;
}
