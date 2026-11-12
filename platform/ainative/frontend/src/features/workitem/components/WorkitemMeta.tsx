import { externalPrincipalName } from '../runtimeLabels';
import type { ExternalPrincipalNameFallback } from '@/shared/types/workitem';
import { EditOutlined } from '@ant-design/icons';
import { Button, Tag } from 'antd';
import { getPriorityMeta, workTypeMap } from '../constants';
import { displayNameWithoutId } from '../nameDisplay';
import { useTranslation } from 'react-i18next';
import { WorkitemTagList } from './WorkitemTagList';

interface WorkitemMetaProps {
  workType: string;
  statusName: string | null;
  priority: number;
  assigneeName: string | null;
  assigneeDisplayName?: string | null;
  assigneeNameFallback?: ExternalPrincipalNameFallback | null;
  assigneeType: string;
  creatorDisplayName?: string | null;
  sdlcName: string | null;
  tags?: string[];
  tagsEditable?: boolean;
  onEditTags?: () => void;
}

export function WorkitemMeta({ workType, statusName, priority, assigneeName, assigneeDisplayName, assigneeNameFallback, assigneeType, creatorDisplayName, sdlcName, tags, tagsEditable = false, onEditTags }: WorkitemMetaProps) {
  const { t } = useTranslation('workitem');
  const priorityMeta = getPriorityMeta(priority);
  const assigneeText = externalPrincipalName(displayNameWithoutId(assigneeDisplayName, assigneeName), assigneeNameFallback);
  const isAgent = assigneeType === 'AGENT';
  const hasTags = !!tags && tags.length > 0;

  return (
    <div className="cf-workitem-meta wi-meta-strip">
      <span className="wi-meta-cell">
        <span className="wi-meta-label">{t('meta.typeLabel')}</span>
        <Tag style={{ margin: 0 }}>{workTypeMap[workType]?.label ?? workType}</Tag>
      </span>
      <span className="wi-meta-cell">
        <span className="wi-meta-label">{t('meta.statusLabel')}</span>
        <Tag style={{ margin: 0, color: 'var(--cf-accent-text)', borderColor: 'var(--cf-accent)', background: 'rgba(var(--cf-accent-rgb),.10)' }}>{statusName ?? t('status.unknown')}</Tag>
      </span>
      <span className="wi-meta-cell">
        <span className="wi-meta-label">{t('meta.priorityLabel')}</span>
        <Tag color={priorityMeta.color} style={{ margin: 0 }}>{priorityMeta.label}</Tag>
      </span>
      {creatorDisplayName && (
        <span className="wi-meta-cell">
          <span className="wi-meta-label">{t('meta.creatorLabel')}</span>
          <span>{displayNameWithoutId(creatorDisplayName)}</span>
        </span>
      )}
      <span className="wi-meta-cell">
        <span className="wi-meta-label">{t('meta.assigneeLabel')}</span>
        <span>{isAgent && <Tag color="purple" style={{ margin: 0 }}>AI</Tag>}{isAgent ? ' ' : ''}{assigneeText ?? t('assignee.unassigned')}</span>
      </span>
      {sdlcName && (
        <span className="wi-meta-cell">
          <span className="wi-meta-label">{t('meta.sdlcLabel')}</span>
          <span>{sdlcName}</span>
        </span>
      )}
      {tagsEditable || hasTags ? (
        <span className="wi-meta-cell" style={{ display: 'inline-flex', gap: 4, alignItems: 'center', flexWrap: 'wrap', minWidth: 0 }}>
          <span className="wi-meta-label">{t('meta.tagsLabel')}</span>
          <WorkitemTagList tags={tags} showEmpty={tagsEditable} />
          {tagsEditable ? (
            <Button
              type="text"
              size="small"
              aria-label={hasTags ? t('tags.editAria') : t('tags.add')}
              title={hasTags ? t('tags.edit') : t('tags.add')}
              icon={<EditOutlined aria-hidden="true" />}
              onClick={onEditTags}
            >
              {hasTags ? null : t('tags.add')}
            </Button>
          ) : null}
        </span>
      ) : null}
    </div>
  );
}
