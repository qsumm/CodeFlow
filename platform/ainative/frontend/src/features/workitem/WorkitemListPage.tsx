import { workitemDeleteReason } from './runtimeLabels';
import { PageHeading } from '@/shared/ui/PageHeading';
import { Toolbar } from '@/shared/ui/PageShell';
import { Trans, useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { formatDateTime } from '@/shared/i18n/format';
import { useMemo, useState } from 'react';
import { Button, Tag, Space, Select, Card, Segmented, Popconfirm, Tooltip, Input, AutoComplete, Typography, Empty } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { PlusOutlined, AppstoreOutlined, UnorderedListOutlined, TableOutlined, DeleteOutlined, StarOutlined, StarFilled, ExclamationCircleFilled, CloseCircleFilled, SearchOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useDeleteWorkitem, useWorkitemList, useWorkitemKanbanColumns, useToggleWatch, useWorkitemTagOptions } from './hooks';
import { WorkitemTagsCell } from './components/WorkitemTagsCell';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { WorkitemKanban } from './components/WorkitemKanban';
import { WorkitemFlowList } from './components/WorkitemFlowList';
import { EMPTY_ADVANCED_FILTER, WorkitemAdvancedFilter, type WorkitemAdvancedFilterState } from './components/WorkitemAdvancedFilter';
import { WorkitemHealthBadge } from './components/WorkitemHealthBadge';
import { HumanInterventionBadge } from './components/HumanInterventionBadge';
import { ScheduledExecutionBadge } from './components/ScheduledExecutionBadge';
import { workTypeMap, STATUS_COLUMNS, STATUS_FILTER_COLUMNS, getPriorityMeta } from './constants';
import { displayNameWithoutId } from './nameDisplay';
import { readWorkitemViewPreference, writeWorkitemViewPreference, type WorkitemViewMode } from './viewPreference';
import { toCreatedTimeQuery } from './createdTimeFilter';
import { serializeTagConditions } from './tagConditionFilter';
import { usePageSizePreference } from '@/shared/lib/usePageSizePreference';
import type { Workitem } from '@/shared/types/workitem';
import type { WorkitemStatusCategory } from './api';
import type { ColumnsType } from 'antd/es/table';
import { useAuthStore } from '@/shared/auth/store';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

import { useKanbanTransition } from './useKanbanTransition';

type ViewMode = WorkitemViewMode;

const FLOW_VIEW = 'flow';
const KANBAN_VIEW = 'kanban';
const TABLE_VIEW = 'table';
type Scope = 'ALL' | 'PENDING' | 'CREATED' | 'ASSIGNED' | 'WATCHED' | 'COMMENTED';
type StatusCategory = WorkitemStatusCategory;

/** 看板每列首屏加载条数，点「加载更多」按此步长递增 */
const KANBAN_COLUMN_PAGE_SIZE = 50;
const ALL_STATUS_KEYS = STATUS_COLUMNS.map(col => col.key as StatusCategory);

const SCOPE_STORAGE_KEY = 'ainative.workitems.scope';
const LEGACY_PENDING_KEY = 'ainative.workitems.onlyMyPendingDecision';

function readScopePreference(): Scope {
  try {
    const stored = window.localStorage.getItem(SCOPE_STORAGE_KEY);
    if (stored === 'ALL' || stored === 'PENDING' || stored === 'CREATED' || stored === 'ASSIGNED' || stored === 'WATCHED' || stored === 'COMMENTED') {
      return stored;
    }
    if (window.localStorage.getItem(LEGACY_PENDING_KEY) === 'true') {
      window.localStorage.setItem(SCOPE_STORAGE_KEY, 'PENDING');
      window.localStorage.removeItem(LEGACY_PENDING_KEY);
      return 'PENDING';
    }
  } catch {
    // ignore
  }
  return 'ALL';
}

function writeScopePreference(value: Scope) {
  try {
    window.localStorage.setItem(SCOPE_STORAGE_KEY, value);
    window.localStorage.removeItem(LEGACY_PENDING_KEY);
  } catch {
    // ignore
  }
}

function scopeToQuery(scope: Scope): { pendingDecisionOnly?: boolean; mineScope?: 'CREATED' | 'ASSIGNED' | 'WATCHED' | 'COMMENTED' } {
  switch (scope) {
    case 'PENDING': return { pendingDecisionOnly: true };
    case 'CREATED': return { mineScope: 'CREATED' };
    case 'ASSIGNED': return { mineScope: 'ASSIGNED' };
    case 'WATCHED': return { mineScope: 'WATCHED' };
    case 'COMMENTED': return { mineScope: 'COMMENTED' };
    default: return {};
  }
}


function creatorCellText(record: Workitem): string {
  if (record.sourceType === 'EXTERNAL') {
    const sourceCreator = record.sourceCreator;
    if (!sourceCreator) return i18n.t('workitem:list.missingSourceCreator');
    return displayNameWithoutId(sourceCreator.displayName) || sourceCreator.subjectId || i18n.t('workitem:list.missingSourceCreator');
  }
  return displayNameWithoutId(record.creatorDisplayName, record.creatorName) || '-';
}

/** 日期+时间双行单元格，列表时间列共用。 */
function renderDateTimeCell(value: string | null | undefined) {
  if (!value) return '-';
  const d = new Date(value);
  const date = formatDateTime(d, { year: 'numeric', month: 'numeric', day: 'numeric' });
  const time = formatDateTime(d, { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
  return (
    <div style={{ whiteSpace: 'nowrap' }}>
      <div>{date}</div>
      <div>{time}</div>
    </div>
  );
}

/** 表头单行显示，禁止逐字换行。 */
function withNowrapHeader(columns: ColumnsType<Workitem>): ColumnsType<Workitem> {
  return columns.map(column => ({ ...column, align: 'dataIndex' in column && column.dataIndex === 'title' ? 'left' : 'center', onHeaderCell: () => ({ style: { whiteSpace: 'nowrap', textAlign: 'center' } }) }));
}

function WatchWorkitemButton({
  record,
  loading,
  onToggle,
}: {
  record: Workitem;
  loading: boolean;
  onToggle: (record: Workitem) => void;
}) {
  const { t } = useTranslation('workitem');
  const watched = !!record.watched;
  return (
    <Tooltip title={watched ? t('actions.unwatch') : t('actions.watch')}>
      <Button
        size="small"
        type="text"
        data-testid="workitem-watch-toggle"
        aria-label={watched ? t('actions.unwatchAria') : t('actions.watchAria')}
        icon={watched ? <StarFilled aria-hidden="true" className="cf-watch-active" /> : <StarOutlined aria-hidden="true" />}
        loading={loading}
        onClick={(event) => {
          event.stopPropagation();
          onToggle(record);
        }}
      />
    </Tooltip>
  );
}

function DeleteWorkitemButton({
  record,
  loading,
  onDelete,
}: {
  record: Workitem;
  loading: boolean;
  onDelete: (id: number) => void;
}) {
  const { t } = useTranslation('workitem');
  if (record.deletable === false) {
    return (
      <Tooltip title={workitemDeleteReason(record)}>
        <span>
          <Button
            danger
            size="small"
            aria-label={t('actions.delete')}
            icon={<DeleteOutlined aria-hidden="true" />}
            disabled
            onClick={(event) => event.stopPropagation()}
          />
        </span>
      </Tooltip>
    );
  }
  return (
    <Popconfirm
      icon={<ExclamationCircleFilled aria-hidden="true" />}
      title={t('actions.delete')}
      description={t('list.deleteDescription')}
      okText={t('list.deleteConfirm')}
      okButtonProps={{ danger: true }}
      cancelText={t('list.cancel')}
      onConfirm={() => onDelete(record.id)}
    >
      <Button
        danger
        size="small"
        aria-label={t('actions.delete')}
        icon={<DeleteOutlined aria-hidden="true" />}
        loading={loading}
        onClick={(event) => event.stopPropagation()}
      />
    </Popconfirm>
  );
}

export function WorkitemListPage() {
  const { t } = useTranslation('workitem');
  const scopeOptions: { value: Scope; label: string }[] = [
    { value: 'ALL', label: t('scope.all') },
    { value: 'PENDING', label: t('scope.pending') },
    { value: 'CREATED', label: t('scope.created') },
    { value: 'ASSIGNED', label: t('scope.assigned') },
    { value: 'WATCHED', label: t('scope.watched') },
    { value: 'COMMENTED', label: t('scope.commented') },
  ];
  const navigate = useNavigate();
  const user = useAuthStore(state => state.user);
  const accessCommand = useAccessCommand();
  const kanbanTransition = useKanbanTransition();
  const [page, setPage] = useState(1);
  const [size, setSize] = usePageSizePreference('ainative.workitems.pageSize', [10, 20, 50, 100, 200], 10);
  const [workType, setWorkType] = useState<string | undefined>();
  const [statusCategory, setStatusCategory] = useState<StatusCategory | undefined>();
  const [viewMode, setViewMode] = useState<ViewMode>(readWorkitemViewPreference);
  const [scope, setScope] = useState<Scope>(readScopePreference);
  const [keyword, setKeyword] = useState<string | undefined>();
  const [tag, setTag] = useState<string | undefined>();
  const [tagInput, setTagInput] = useState('');
  const [advancedFilter, setAdvancedFilter] = useState<WorkitemAdvancedFilterState>(EMPTY_ADVANCED_FILTER);
  const [columnSizes, setColumnSizes] = useState<Record<string, number>>({});
  const { data: tagOptions, isError: tagOptionsError } = useWorkitemTagOptions();

  const isKanban = viewMode === 'kanban';
  const createdQuery = useMemo(() => toCreatedTimeQuery(advancedFilter.created), [advancedFilter.created]);
  const creatorQuery = advancedFilter.creatorIds.length
    ? { creatorIds: advancedFilter.creatorIds.join(',') }
    : {};
  const tagConditionsQuery = useMemo(
    () => serializeTagConditions(advancedFilter.tagConditions),
    [advancedFilter.tagConditions],
  );
  // 高级筛选与其他筛选条件以 AND 组合，表格与看板共用同一份查询条件
  const baseQuery = {
    workType,
    ...scopeToQuery(scope),
    keyword,
    tag,
    ...(tagConditionsQuery ? { tagConditions: tagConditionsQuery } : {}),
    ...createdQuery,
    ...creatorQuery,
  };

  // 表格视图用全量分页查询；看板视图下只取 total 给页面标题用
  const { data, isLoading } = useWorkitemList(
    { ...baseQuery, statusCategory, page: isKanban ? 1 : page, size: isKanban ? 1 : size },
  );
  const items = data?.list ?? [];
  const total = data?.total ?? 0;

  // 看板每列各自带 statusCategory 去服务端查，列内容不再取决于全量列表的页码
  const visibleColumnKeys = statusCategory ? [statusCategory] : ALL_STATUS_KEYS;
  const kanbanColumns = useWorkitemKanbanColumns(
    baseQuery, visibleColumnKeys, columnSizes, KANBAN_COLUMN_PAGE_SIZE, isKanban,
  );
  const showDecisionSummary = isKanban && scope === 'ALL' && !!user && visibleColumnKeys.includes('PENDING_DECISION');
  const [myPendingColumn] = useWorkitemKanbanColumns(
    {
      workType,
      keyword,
      tag,
      ...(tagConditionsQuery ? { tagConditions: tagConditionsQuery } : {}),
      ...createdQuery,
      ...creatorQuery,
      pendingDecisionOnly: true,
    },
    showDecisionSummary ? ['PENDING_DECISION'] : [], {}, 1, showDecisionSummary,
  );
  const kanbanItems = useMemo(() => {
    const byId = new Map<number | string, Workitem>();
    kanbanColumns.forEach(col => col.items.forEach(item => byId.set(item.id, item)));
    return Array.from(byId.values());
  }, [kanbanColumns]);
  const columnTotals = Object.fromEntries(kanbanColumns.map(col => [col.key, col.total]));
  const columnHasMore = Object.fromEntries(kanbanColumns.map(col => [col.key, col.hasMore]));
  const kanbanLoading = kanbanColumns.some(col => col.isLoading);

  const deleteMutation = useDeleteWorkitem();
  const watchMutation = useToggleWatch();

  const columns: ColumnsType<Workitem> = withNowrapHeader([
    { title: 'ID', dataIndex: 'id', className: 'cf-column-numeric', width: 80 },
    {
      title: t('fields.title'), dataIndex: 'title', className: 'cf-column-title',
      render: (text: string, record: Workitem) => (
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 4, minWidth: 0 }}>
          <EllipsisText lines={2} tooltip={text} placement="topLeft" style={{ flex: '1 1 auto', wordBreak: 'break-all' }}>
            <a onClick={() => navigate(`/workitems/${record.id}`)}>{text}</a>
          </EllipsisText>
          <ScheduledExecutionBadge
            scheduledStartAt={record.scheduledStartAt}
            scheduledStartTriggeredAt={record.scheduledStartTriggeredAt}
            origin={record.origin}
            gmtCreate={record.gmtCreate}
          />
        </div>
      ),
    },
    {
      title: t('fields.type'), dataIndex: 'workType', width: 80,
      render: (t: string) => <Tag color={workTypeMap[t]?.color}>{workTypeMap[t]?.label || t}</Tag>,
    },
    {
      title: t('fields.status'), dataIndex: 'statusName', width: 150,
      render: (s: string | null, record: Workitem) => (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
          {s ? <Tag color="processing" style={{ margin: 0 }}>{s}</Tag> : <Tag style={{ margin: 0 }}>-</Tag>}
          <HumanInterventionBadge item={record} />
          <WorkitemHealthBadge item={record} />
        </div>
      ),
    },
    {
      title: t('fields.priority'), dataIndex: 'priority', width: 96,
      render: (p: number) => {
        const meta = getPriorityMeta(p);
        return <Tag color={meta.color} style={{ margin: 0 }}>{meta.label}</Tag>;
      },
    },
    {
      title: t('fields.tags'), dataIndex: 'tags', width: 160,
      render: (_: unknown, record: Workitem) => <WorkitemTagsCell workitem={record} maxVisible={3} />,
    },
    {
      title: t('fields.assignee'), dataIndex: 'assigneeName', width: 120,
      render: (_: string | null, record: Workitem) => {
        const text = displayNameWithoutId(record.assigneeDisplayName, record.assigneeName);
        return (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, minWidth: 0, maxWidth: '100%' }}>
            {record.assigneeType === 'AGENT' ? <Tag color="purple" style={{ margin: 0 }}>AI</Tag> : null}
            <EllipsisText tooltip={text ?? undefined}>
              {text ?? t('assignee.unassigned')}
            </EllipsisText>
          </span>
        );
      },
    },
    {
      title: t('fields.creator'), dataIndex: 'creatorDisplayName', width: 120,
      render: (_: string | null, record: Workitem) => {
        const text = creatorCellText(record);
        const hasCreator = record.sourceType === 'EXTERNAL'
          ? record.sourceCreator?.displayName || record.sourceCreator?.subjectId
          : record.creatorDisplayName || record.creatorName;
        return <EllipsisText tooltip={hasCreator ? text : undefined}>{text}</EllipsisText>;
      },
    },
    {
      title: t('fields.created'), dataIndex: 'gmtCreate', className: 'cf-column-numeric', width: 108,
      render: (value: string) => renderDateTimeCell(value),
    },
    ...(scope === 'COMMENTED'
      ? [{
        title: t('fields.myLastCommentAt'), dataIndex: 'myLastCommentAt', className: 'cf-column-numeric', width: 132,
        render: (value: string | null | undefined) => renderDateTimeCell(value),
      } as ColumnsType<Workitem>[number]]
      : []),
    {
      title: t('fields.actions'),
      dataIndex: 'operation',
      width: 112,
      render: (_: unknown, record: Workitem) => (
        <Space size={4}>
          <WatchWorkitemButton
            record={record}
            loading={watchMutation.isPending}
            onToggle={(target) => watchMutation.mutate({ id: target.id, watched: !!target.watched })}
          />
          <DeleteWorkitemButton
            record={record}
            loading={deleteMutation.isPending}
            onDelete={(workitemId) => accessCommand(
              'READ_WRITE',
              t('actions.delete'),
              () => deleteMutation.mutate({ id: workitemId }),
            )}
          />
        </Space>
      ),
    },
  ]);

  const handleScopeChange = (value: string | number) => {
    const next = value as Scope;
    setScope(next);
    writeScopePreference(next);
    setPage(1);
    setColumnSizes({});
  };

  const handlePageChange = (nextPage: number, nextSize: number) => {
    setPage(nextPage);
    setSize(nextSize);
  };

  // 应用创建者条件后顶部范围的「我的」类筛选不再有意义，统一回到「全部」；
  // handleScopeChange 负责写偏好并重置页码与列宽。
  const handleAdvancedFilterApply = (next: WorkitemAdvancedFilterState) => {
    setAdvancedFilter(next);
    if (next.creatorIds.length > 0 && scope !== 'ALL') {
      handleScopeChange('ALL');
    } else {
      setPage(1);
      setColumnSizes({});
    }
  };

  return (
    <Card
      className="cf-content-card cf-workitems"
      title={<PageHeading title={t('list.title')} description={<Trans t={t} i18nKey="list.total" count={total} values={{ total }} components={{ number: <span className="cf-heading-number" /> }} />} />}
      extra={
        <Space>
          <Segmented
            value={viewMode}
            onChange={(v) => {
              const next = v as ViewMode;
              setViewMode(next);
              writeWorkitemViewPreference(next);
              setPage(1);
            }}
            options={[
              { value: FLOW_VIEW, icon: <UnorderedListOutlined aria-label={t('list.flowView')} /> },
              { value: KANBAN_VIEW, icon: <AppstoreOutlined aria-label={t('list.kanbanView')} /> },
              { value: TABLE_VIEW, icon: <TableOutlined aria-label={t('list.tableView')} /> },
            ]}
          />
          <Button type="primary" className="wi-create-btn" icon={<PlusOutlined aria-hidden="true" />}
            onClick={() => accessCommand('READ_WRITE', t('actions.new'), () => navigate('/workitems/new'))}>
            {t('actions.new')}
          </Button>
        </Space>
      }
    >
      <Toolbar
        filters={<>
          <Segmented
            value={scope}
            onChange={handleScopeChange}
            options={scopeOptions}
            aria-label={t('scope.label')}
          />
          <Select
            placeholder={t('list.typeAll')}
            value={workType}
            allowClear
            style={{ width: 180 }}
            onChange={(v) => { setWorkType(v); setPage(1); setColumnSizes({}); }}
            options={Object.entries(workTypeMap).map(([value, meta]) => ({ value, label: meta.label }))}
          />
          <Select
            placeholder={t('list.statusAll')}
            allowClear
            style={{ width: 180 }}
            value={statusCategory}
            onChange={(v) => { setStatusCategory(v); setPage(1); setColumnSizes({}); }}
            options={STATUS_FILTER_COLUMNS.map(col => ({ value: col.key as StatusCategory, label: col.title }))}
          />
          <Input.Search
            enterButton={<Button aria-label={t('list.search')} icon={<SearchOutlined aria-hidden="true" />} />}
            allowClear={{ clearIcon: <CloseCircleFilled aria-label={t('list.clearSearch')} /> }}
            placeholder={t('list.search')}
            onSearch={(v) => { setKeyword(v || undefined); setPage(1); setColumnSizes({}); }}
            style={{ width: 240 }}
          />
          <AutoComplete
            value={tagInput}
            options={(tagOptions ?? []).map((value) => ({ value }))}
            onChange={(value) => {
              setTagInput(value);
              if (!value) {
                setTag(undefined);
                setPage(1);
                setColumnSizes({});
              }
            }}
            onSelect={(value) => {
              setTagInput(value);
              setTag(value);
              setPage(1);
              setColumnSizes({});
            }}
            style={{ width: 240 }}
          >
            <Input.Search
              enterButton={<Button aria-label={t('list.tagFilter')} icon={<SearchOutlined aria-hidden="true" />} />}
              allowClear={{ clearIcon: <CloseCircleFilled aria-label={t('list.clearTag')} /> }}
              placeholder={t('list.tagFilter')}
              onSearch={(v) => {
                const next = v?.trim() || undefined;
                setTag(next);
                setTagInput(v ?? '');
                setPage(1);
                setColumnSizes({});
              }}
            />
          </AutoComplete>
          {tagOptionsError ? (
            <Typography.Text type="warning" data-testid="workitem-tag-options-error">
              {t('tags.loadFailed')}
            </Typography.Text>
          ) : null}
          <WorkitemAdvancedFilter applied={advancedFilter} onApply={handleAdvancedFilterApply} />
        </>}
      />

      {kanbanTransition.dialog}
      {viewMode === FLOW_VIEW ? (
        <WorkitemFlowList items={items} />
      ) : viewMode === KANBAN_VIEW ? (
        <WorkitemKanban
          boardEmpty={kanbanColumns.length > 0 && kanbanColumns.every(col => !col.total) && !isLoading}
          onMove={kanbanTransition.move}
          transitionBusy={kanbanTransition.busy}
          items={kanbanItems}
          loading={kanbanLoading}
          pendingDecisionSummary={showDecisionSummary && user && myPendingColumn && !myPendingColumn.isLoading ? {
            name: displayNameWithoutId(user.nickname, user.username) ?? user.username,
            count: myPendingColumn.total,
          } : undefined}
          columnKeys={visibleColumnKeys}
          columnTotals={columnTotals}
          columnHasMore={columnHasMore}
          onLoadMore={(key) => setColumnSizes(prev => ({
            ...prev,
            [key]: (prev[key] ?? KANBAN_COLUMN_PAGE_SIZE) + KANBAN_COLUMN_PAGE_SIZE,
          }))}
        />
      ) : (
        <div className="cfds-table-wrap">
          <Table
            rowKey="id"
            columns={columns}
            dataSource={items}
            loading={isLoading}
            tableLayout="fixed"
            scroll={{ x: 1200 }}
            locale={{ emptyText: (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('list.tableEmpty')}>
                <span>{t('list.tableEmptyHint')}</span>
              </Empty>
            ) }}
            pagination={{
              current: page,
              pageSize: size,
              total,
              onChange: handlePageChange,
              showSizeChanger: true,
              showTotal: (itemTotal) => t('list.pagination', { count: itemTotal }),
            }}
          />
        </div>
      )}
    </Card>
  );
}
