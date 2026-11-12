import { useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import type { ReactNode, DragEvent } from 'react';
import { Button, Card, Typography, Spin } from 'antd';
import { LinkOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { workTypeMap, getPriorityMeta, CANCELED_STATUS_COLUMN, STATUS_COLUMNS, statusCategoryOf } from '../constants';
import { displayNameWithoutId } from '../nameDisplay';
import { groupPendingDecisionsByAssignee, isMyPendingDecision } from '../decisionGrouping';
import { WorkitemHealthBadge } from './WorkitemHealthBadge';
import { ScheduledExecutionBadge } from './ScheduledExecutionBadge';
import { WorkitemTagsCell } from './WorkitemTagsCell';
import type { Workitem } from '@/shared/types/workitem';
import './WorkitemKanban.css';

const { Text, Paragraph } = Typography;

function localCreatorLabel(item: Workitem) {
  return displayNameWithoutId(item.creatorDisplayName, item.creatorName) ?? i18n.t('workitem:kanban.notProvided');
}

function reporterLabel(item: Workitem) {
  const reporter = item.sourceCreator;
  if (!reporter) return i18n.t('workitem:kanban.notReturned');
  return displayNameWithoutId(reporter.displayName) || reporter.subjectId || i18n.t('workitem:kanban.notReturned');
}

function sourceProviderLabel(provider?: string | null) {
  if (!provider) return i18n.t('workitem:kanban.externalWorkitem');
  if (provider.toUpperCase() === 'ZENTAO') return 'Zentao';
  return provider.charAt(0).toUpperCase() + provider.slice(1).toLowerCase();
}

const WORK_TYPE_BADGE: Record<string, string> = { REQ: 'wi-badge--req', BUG: 'wi-badge--bug', TASK: 'wi-badge--task' };
const PRIORITY_CLASS: Record<number, string> = { 0: 'wi-priority--p0', 1: 'wi-priority--p1', 2: 'wi-priority--p2' };

interface CardDragProps {
  draggable?: boolean;
  onDragStart?: (event: DragEvent<HTMLDivElement>) => void;
  onDragEnd?: () => void;
}

function WorkitemCard({ item, ...dragProps }: { item: Workitem } & CardDragProps) {
  const { t } = useTranslation('workitem');
  const navigate = useNavigate();
  const priority = getPriorityMeta(item.priority);
  const workType = workTypeMap[item.workType] || { color: 'default', label: item.workType };
  const creatorText = item.sourceType === 'EXTERNAL'
    ? t('kanban.sourceReporter', { name: reporterLabel(item) })
    : t('kanban.creator', { name: localCreatorLabel(item) });
  const assigneeText = displayNameWithoutId(item.assigneeDisplayName, item.assigneeName) ?? t('assignee.unassigned');
  const category = statusCategoryOf(item);
  const categoryColor = (category === 'CANCELED' ? CANCELED_STATUS_COLUMN : STATUS_COLUMNS.find(col => col.key === category))?.color;
  const sourceBody = (
    <>
      <LinkOutlined aria-hidden="true" />
      {t('kanban.source', { provider: sourceProviderLabel(item.sourceProvider) })}
    </>
  );

  return (
    <Card
      {...dragProps}
      data-testid={`workitem-card-${item.id}`}
      size="small"
      hoverable
      className={`wi-card${category ? ` wi-card--${category.toLowerCase()}` : ''}`}
      onClick={() => navigate(`/workitems/${item.id}`)}
      style={{ marginBottom: 0, flex: '0 0 300px', cursor: dragProps.draggable ? 'grab' : 'pointer', ['--wi-accent' as string]: categoryColor }}
      styles={{ body: { padding: '12px 12px 10px 14px' } }}
    >
      <div className="wi-card__top" style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 8 }}>
        <span className={`wi-badge ${WORK_TYPE_BADGE[item.workType] ?? 'wi-badge--task'}`}>{workType.label}</span>
        <WorkitemHealthBadge item={item} />
        <span className={`wi-priority ${PRIORITY_CLASS[item.priority] ?? ''}`} title={t('fields.priority')}>
          <i aria-hidden="true" />{priority.label}
        </span>
      </div>
      <Paragraph ellipsis={{ rows: 2 }} className="wi-card__title">
        {item.title}{' '}
        <ScheduledExecutionBadge
          scheduledStartAt={item.scheduledStartAt}
          scheduledStartTriggeredAt={item.scheduledStartTriggeredAt}
          origin={item.origin}
          gmtCreate={item.gmtCreate}
        />
      </Paragraph>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 6, marginBottom: 8 }}>
        <span className="wi-status" title={t('kanban.status', { name: item.statusName || t('kanban.notProvided') })} style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          <i aria-hidden="true" />
          {item.statusName || i18n.t('workitem:kanban.notProvided')}
        </span>
        {item.executionStatus === 'PENDING' && category !== 'DONE' && (
          <span className="wi-badge" style={{ color: '#b45309', background: 'rgba(217, 119, 6, .10)' }}>{t('status.queued')}</span>
        )}
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 4, marginBottom: 4 }}>
        <WorkitemTagsCell workitem={item} maxVisible={2} hideEmpty />
      </div>
      {item.sourceType === 'EXTERNAL' && (
        <div style={{ marginBottom: 2 }} onClick={(event) => event.stopPropagation()}>
          {item.sourceUrl ? (
            <Typography.Link className="wi-source" href={item.sourceUrl} target="_blank" rel="noreferrer">
              {sourceBody}
            </Typography.Link>
          ) : (
            <span className="wi-source">{sourceBody}</span>
          )}
        </div>
      )}
      <div className="wi-meta">
        <Text type="secondary" ellipsis title={creatorText} style={{ minWidth: 0, fontSize: 12 }}>
          {creatorText}
        </Text>
        <Text type="secondary" ellipsis title={t('kanban.assignee', { name: assigneeText })} style={{ minWidth: 0, fontSize: 12, textAlign: 'right' }}>
          {t('kanban.assignee', { name: assigneeText })}
        </Text>
      </div>
    </Card>
  );
}

/**
 * 待决策列内容：按决策人（指派人）分组展示。
 * onlyMine 为真且无命中时给出专属空态。
 */
function PendingColumnContent({ items, onlyMine, renderCard }: { items: Workitem[]; onlyMine?: boolean; renderCard: (item: Workitem) => ReactNode }) {
  const { t } = useTranslation('workitem');
  const groups = useMemo(() => groupPendingDecisionsByAssignee(items), [items, t]);
  if (groups.length === 0) {
    // 与其它列同款空态(虚线小方块):此前这一列用 antd Empty 大图标,
    // 其余列用小方块,同屏两套空态显得割裂(2026-11-11 视觉评审)。
    return <span className="wi-row-empty">{onlyMine ? t('kanban.emptyDecision') : t('kanban.empty')}</span>;
  }
  return (
    <>
      {groups.map(g => (
        <div key={g.key} style={{ marginBottom: 12 }}>
          {g.items.map(renderCard)}
        </div>
      ))}
    </>
  );
}

interface WorkitemKanbanProps {
  /** 父级依据服务端 total===0 判定整个看板为空(区分『筛选后为空』——后者仍渲染列)。 */
  boardEmpty?: boolean;
  items: Workitem[];
  loading?: boolean;
  /** 仅展示需要当前登录人决策的待决策工单 */
  onlyMine?: boolean;
  /** 当前登录人 id，用于 onlyMine 过滤 */
  currentUserId?: number | null;
  /** 需要展示的状态列，默认展示全部四列 */
  columnKeys?: string[];
  /** 全部筛选下当前用户的待决策汇总，数量来自服务端。 */
  pendingDecisionSummary?: { name: string; count: number };
  /** 各列的服务端真实总数，与当前加载条数无关 */
  columnTotals?: Record<string, number>;
  /** 各列是否还有未加载的工单 */
  columnHasMore?: Record<string, boolean>;
  onLoadMore?: (key: string) => void;
  onMove?: (item: Workitem, columnKey: string) => void;
  transitionBusy?: boolean;
}

export function WorkitemKanban({
  boardEmpty,
  items,
  loading,
  onlyMine = false,
  currentUserId = null,
  columnKeys,
  pendingDecisionSummary,
  columnTotals,
  columnHasMore,
  onLoadMore,
  onMove,
  transitionBusy = false,
}: WorkitemKanbanProps) {
  const { t } = useTranslation('workitem');
  const dragged = useRef<Workitem | null>(null);
  const [overColumn, setOverColumn] = useState<string | null>(null);
  const resetDrag = () => { dragged.current = null; setOverColumn(null); };
  const renderCard = (item: Workitem) => (
    <WorkitemCard key={String(item.id)} item={item}
      draggable={!!onMove && !loading && !transitionBusy}
      onDragStart={(event) => {
        // Links within a card keep their native behavior, but must not move the card.
        if (!onMove || loading || transitionBusy || (event.target as HTMLElement).closest('a')) {
          event.preventDefault();
          return;
        }
        dragged.current = item;
        event.dataTransfer.effectAllowed = 'move';
        event.dataTransfer.setData('text/plain', String(item.id));
      }}
      onDragEnd={resetDrag}
    />
  );
  const visibleColumns = useMemo(
    () => (columnKeys
      ? [...STATUS_COLUMNS, CANCELED_STATUS_COLUMN].filter(col => columnKeys.includes(col.key))
      : STATUS_COLUMNS),
    [columnKeys],
  );
  const grouped = useMemo(() => {
    const groups: Record<string, Workitem[]> = {};
    visibleColumns.forEach(col => { groups[col.key] = []; });
    items.forEach(item => {
      // 未渲染的列（默认看板不含 CANCELED）与未知分类不落列。
      const key = statusCategoryOf(item);
      if (!groups[key]) return;
      groups[key].push(item);
    });
    return groups;
  }, [items, visibleColumns]);

  return (
    <Spin spinning={!!loading || transitionBusy} tip={transitionBusy ? t('kanban.updating') : undefined}>
    {/* 空库:所有列都没有数据时不渲染四条横带 */}
    {boardEmpty && !loading && !transitionBusy ? (
      <div className="wi-kanban-empty">
                <p className="wi-kanban-empty__title">{t('kanban.boardEmptyTitle')}</p>
        <p className="wi-kanban-empty__desc">{t('kanban.boardEmptyDesc')}</p>
      </div>
    ) : (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10, padding: '4px 0', minHeight: 320 }}>
      {visibleColumns.map(col => {
        const isPending = col.key === 'PENDING_DECISION';
        const colItems = isPending && onlyMine
          ? grouped[col.key].filter(i => isMyPendingDecision(i, currentUserId))
          : grouped[col.key];
        // onlyMine 会在本地二次过滤，此时服务端总数不再代表可见条数
        const serverTotal = isPending && onlyMine ? undefined : columnTotals?.[col.key];
        const badgeCount = serverTotal ?? colItems.length;
        return (
          <div
            key={col.key}
            data-testid={`kanban-column-${col.key}`}
            className="wi-row"
            data-over={overColumn === col.key || undefined}
            onDragOver={(event) => {
              if (!dragged.current || transitionBusy || statusCategoryOf(dragged.current) === col.key) return;
              event.preventDefault();
              event.dataTransfer.dropEffect = 'move';
              setOverColumn(col.key);
            }}
            onDragLeave={(event) => {
              if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setOverColumn(null);
            }}
            onDrop={(event) => {
              event.preventDefault();
              const item = dragged.current;
              resetDrag();
              if (item && !transitionBusy && statusCategoryOf(item) !== col.key) onMove?.(item, col.key);
            }}
          >
            <div className="wi-row-head">
              <div className="wi-row-dot" style={{ background: col.color }} />
              <Text strong className="wi-row-title" style={{ minWidth: 0 }}>{col.title}</Text>
              <span className="wi-row-count" title={String(badgeCount)}>{badgeCount}</span>
              {isPending && !onlyMine && pendingDecisionSummary && (
                <div aria-label={t('kanban.decisionSummary')} style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 'auto', minWidth: 0 }}>
                  <Text type="secondary" strong ellipsis title={pendingDecisionSummary.name} style={{ minWidth: 0, maxWidth: 120, fontSize: 12 }}>{pendingDecisionSummary.name}</Text>
                  <span className="wi-row-count" title={String(pendingDecisionSummary.count)} style={{ color: 'var(--cf-warning)', borderColor: 'rgba(217, 119, 6, .35)' }}>{pendingDecisionSummary.count}</span>
                </div>
              )}
            </div>
            <div className="wi-row-cards" style={{ display: 'flex', gap: 12, overflowX: 'auto' }}>
              {isPending ? (
                <PendingColumnContent items={colItems} onlyMine={onlyMine} renderCard={renderCard} />
              ) : colItems.length > 0 ? (
                colItems.map(renderCard)
              ) : (
                <span className="wi-row-empty">{t('kanban.empty')}</span>
              )}
              {columnHasMore?.[col.key] && onLoadMore ? (
                <Button
                  size="small"
                  type="link"
                  className="wi-row-more"
                  onClick={() => onLoadMore(col.key)}
                >
                  {t('kanban.loadMore', { shown: colItems.length, total: badgeCount })}
                </Button>
              ) : null}
            </div>
          </div>
        );
      })}
    </div>
    )}
    </Spin>
  );
}
