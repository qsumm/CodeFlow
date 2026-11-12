import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { Alert, Button, Empty, Segmented, Space, Tag, Tooltip, Typography } from 'antd';
import { CloseCircleFilled, InfoCircleOutlined, LoadingOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { Table } from '@/shared/theme/ThemedTable';
import { PageHeading } from '@/shared/ui/PageHeading';
import { StatTile, SurfaceCard, Toolbar } from '@/shared/ui/PageShell';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { formatDateTime, formatNumber } from '@/shared/i18n/format';
import { getPriorityMeta } from '@/features/workitem/constants';
import { Sparkline } from '@/features/insights/components/Sparkline';
import { useCreatedWorkitems, useDeliveryOverview, usePendingWorkitems } from './hooks';
import type { CreatedWorkitem, DeliveryOverviewFailureReason, PendingWorkitem } from './types';
import './DeliveryOverviewPage.css';

const DATE_TIME_OPTIONS = { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false } as const;
/** 与后端 daysInRange / 上一等长周期口径一致：含首尾。 */
const RANGE_DAYS = { week: 7, month: 30 } as const;
const TIMEZONE = 'Asia/Shanghai';
/** 「标题」列显式宽度：无 width 的列在固定表格布局下会被其余定宽列挤成 0 宽、标题不可见（人工反馈 141916）。 */
const TITLE_COLUMN_WIDTH = 220;
/** 两列卡片下表格仅剩半栏宽；全列宽和（110+220+140+96+130+180，含标题列）作为横向滚动下限，既避免表格溢出卡片、也避免列被挤压。 */
const PENDING_TABLE_WIDTH = 876;
/** 全列宽和（110+220+120+96+180，含标题列），同待处理表格的横向滚动兜底。 */
const CREATED_TABLE_WIDTH = 726;
/** 失败原因表全列宽和（300+96+96+180），同上横向滚动兜底。 */
const FAILURES_TABLE_WIDTH = 672;

const REASON_KEYS = {
  AWAITING_DECISION: 'pending.reasonAWAITING_DECISION',
  AWAITING_HANDLING: 'pending.reasonAWAITING_HANDLING',
  IN_PROGRESS: 'pending.reasonIN_PROGRESS',
} as const;
const REASON_COLORS: Record<string, string> = {
  AWAITING_DECISION: 'gold',
  AWAITING_HANDLING: 'blue',
  IN_PROGRESS: 'default',
};

function reasonKey(value: string): (typeof REASON_KEYS)[keyof typeof REASON_KEYS] | 'pending.reasonUnknown' {
  return Object.prototype.hasOwnProperty.call(REASON_KEYS, value)
    ? REASON_KEYS[value as keyof typeof REASON_KEYS]
    : 'pending.reasonUnknown';
}

function percent(value: number) {
  return `${value > 0 ? '+' : ''}${formatNumber(value, { minimumFractionDigits: 1, maximumFractionDigits: 1 })}%`;
}

/** 率值展示（不带环比符号），null 由调用方处理为「—」。 */
function rate(value: number) {
  return `${formatNumber(value, { minimumFractionDigits: 1, maximumFractionDigits: 1 })}%`;
}

function today() {
  return dayjs(new Intl.DateTimeFormat('en-CA', { timeZone: TIMEZONE, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()));
}

function createdStatusColor(category: string | null) {
  if (category === 'DONE') return 'success';
  if (category === 'CANCELED') return 'default';
  return 'processing';
}

export function DeliveryOverviewPage() {
  const { t } = useTranslation('overview');
  const navigate = useNavigate();
  const [rangeKey, setRangeKey] = useState<'week' | 'month'>('week');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [createdPage, setCreatedPage] = useState(1);
  const [createdPageSize, setCreatedPageSize] = useState(10);
  const days = RANGE_DAYS[rangeKey];
  const { start, end } = useMemo(() => {
    const endDay = today();
    return { start: endDay.subtract(days - 1, 'day').format('YYYY-MM-DD'), end: endDay.format('YYYY-MM-DD') };
  }, [days]);

  const overviewQuery = useDeliveryOverview(start, end);
  const pendingQuery = usePendingWorkitems(page, pageSize);
  const createdQuery = useCreatedWorkitems(start, end, createdPage, createdPageSize);
  const data = overviewQuery.data;
  const pendingPage = pendingQuery.data;
  const createdPageData = createdQuery.data;
  const trend = (data?.created.daily ?? []).map((day) => day.count);
  // 区间内无任何新增时不画趋势线(空图是假信息)
  const hasTrend = trend.some((count) => count > 0);
  const rangeLabel = t(rangeKey === 'week' ? 'toolbar.lastWeek' : 'toolbar.lastMonth');
  const refresh = () => {
    void overviewQuery.refetch();
    void pendingQuery.refetch();
    void createdQuery.refetch();
  };

  const columns: ColumnsType<PendingWorkitem> = [
    {
      title: t('pending.workitem'), dataIndex: 'id', width: 110, align: 'left',
      render: (id: number) => (
        <a onClick={(event) => { event.stopPropagation(); navigate(`/workitems/${id}`); }} style={{ fontVariantNumeric: 'tabular-nums' }}>#{id}</a>
      ),
    },
    {
      title: t('pending.name'), dataIndex: 'title', width: TITLE_COLUMN_WIDTH, align: 'left', ellipsis: { showTitle: false },
      render: (title: string, record: PendingWorkitem) => (
        <EllipsisText tooltip={title}>
          <a onClick={(event) => { event.stopPropagation(); navigate(`/workitems/${record.id}`); }}>{title}</a>
        </EllipsisText>
      ),
    },
    {
      title: t('pending.status'), dataIndex: 'statusName', width: 140,
      render: (name: string | null) => name ? <Tag color="processing" style={{ margin: 0 }}>{name}</Tag> : '—',
    },
    {
      title: t('pending.priority'), dataIndex: 'priority', width: 96,
      render: (priority: number) => {
        const meta = getPriorityMeta(priority);
        return <Tag color={meta.color} style={{ margin: 0 }}>{meta.label}</Tag>;
      },
    },
    {
      title: t('pending.reason'), dataIndex: 'reason', width: 130,
      render: (reason: string) => <Tag color={REASON_COLORS[reason] ?? 'default'} style={{ margin: 0 }}>{t(reasonKey(reason))}</Tag>,
    },
    {
      title: t('pending.updatedAt'), dataIndex: 'gmtModified', width: 180,
      render: (value: string) => formatDateTime(value, DATE_TIME_OPTIONS),
    },
  ];

  const createdColumns: ColumnsType<CreatedWorkitem> = [
    {
      title: t('createdList.workitem'), dataIndex: 'id', width: 110, align: 'left',
      render: (id: number) => (
        <a onClick={(event) => { event.stopPropagation(); navigate(`/workitems/${id}`); }} style={{ fontVariantNumeric: 'tabular-nums' }}>#{id}</a>
      ),
    },
    {
      title: t('createdList.name'), dataIndex: 'title', width: TITLE_COLUMN_WIDTH, align: 'left', ellipsis: { showTitle: false },
      render: (title: string, record: CreatedWorkitem) => (
        <EllipsisText tooltip={title}>
          <a onClick={(event) => { event.stopPropagation(); navigate(`/workitems/${record.id}`); }}>{title}</a>
        </EllipsisText>
      ),
    },
    {
      title: t('createdList.status'), dataIndex: 'statusName', width: 120,
      render: (name: string | null, record: CreatedWorkitem) => name
        ? <Tag color={createdStatusColor(record.statusCategory)} style={{ margin: 0 }}>{name}</Tag>
        : '—',
    },
    {
      title: t('createdList.priority'), dataIndex: 'priority', width: 96,
      render: (priority: number) => {
        const meta = getPriorityMeta(priority);
        return <Tag color={meta.color} style={{ margin: 0 }}>{meta.label}</Tag>;
      },
    },
    {
      title: t('createdList.createdAt'), dataIndex: 'createdAt', width: 180,
      render: (value: string) => formatDateTime(value, DATE_TIME_OPTIONS),
    },
  ];

  const failureColumns: ColumnsType<DeliveryOverviewFailureReason> = [
    {
      title: t('failures.reason'), dataIndex: 'reason', width: 300, align: 'left', ellipsis: { showTitle: false },
      render: (reason: string) => reason === 'UNKNOWN'
        ? <Typography.Text type="secondary">{t('failures.unknown')}</Typography.Text>
        : <EllipsisText tooltip={reason}>{reason}</EllipsisText>,
    },
    {
      title: t('failures.count'), dataIndex: 'count', width: 96,
      render: (count: number) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{formatNumber(count)}</span>,
    },
    {
      title: t('failures.share'), dataIndex: 'sharePercent', width: 96,
      render: (share: number) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{rate(share)}</span>,
    },
    {
      title: t('failures.lastTime'), dataIndex: 'lastTime', width: 180,
      render: (value: string | null) => value ? formatDateTime(value, DATE_TIME_OPTIONS) : '—',
    },
  ];

  return (
    <div className="delivery-overview-page">
      <PageHeading title={t('title')} description={t('description')} extra={
        <Segmented
          aria-label={t('toolbar.rangeAria')}
          value={rangeKey}
          onChange={(value) => { setRangeKey(value as 'week' | 'month'); setPage(1); setCreatedPage(1); }}
          options={[
            { label: t('toolbar.lastWeek'), value: 'week' },
            { label: t('toolbar.lastMonth'), value: 'month' },
          ]}
        />
      } />

      <Toolbar
        filters={<>
          <Typography.Text type="secondary">{t('toolbar.rangeSummary', { start, end })}</Typography.Text>
          <Typography.Text type="secondary">{t('toolbar.timezone', { timezone: data?.timezone ?? TIMEZONE })}</Typography.Text>
          {data && <Typography.Text type="secondary">{t('toolbar.dataThrough', { date: data.dataThrough })}</Typography.Text>}
        </>}
        actions={<Button
          aria-label={t('toolbar.refreshAria')}
          loading={overviewQuery.isFetching || pendingQuery.isFetching || createdQuery.isFetching ? { icon: <LoadingOutlined aria-hidden="true" /> } : false}
          onClick={refresh}
        >{t('toolbar.refresh')}</Button>}
      />

      {overviewQuery.isError && (
        <Alert
          type="error" showIcon icon={<CloseCircleFilled aria-hidden="true" />} message={t('toolbar.loadFailure')}
          action={<Button size="small" onClick={() => { void overviewQuery.refetch(); }}>{t('toolbar.retry')}</Button>}
        />
      )}

      <div className="cfds-stat-row cfds-stat-row--quad">
        <div data-testid="overview-created">
          <StatTile
            label={t('created.title')}
            value={data ? formatNumber(data.created.count) : '—'}
            hint={<div className="overview-stat-detail">
              <div className="overview-trend">
                {/* 无数据时不画趋势线:一条孤零零的蓝线是假信息 */}
                {hasTrend && (
                  <span className="overview-sparkline" role="img" aria-label={t('created.trendAria', { days: data?.daysInRange ?? days })}>
                    <Sparkline data={trend} color="var(--cf-accent)" width={120} height={32} />
                  </span>
                )}
                <span>{t('created.caption', { days: data?.daysInRange ?? days })}</span>
              </div>
            </div>}
          />
        </div>
        <div data-testid="overview-delivered">
          <StatTile
            label={<Space size={6}>{t('delivered.title')}
              <Tooltip title={t('delivered.method')} trigger={['hover', 'focus']} styles={{ root: { maxWidth: 420 } }}>
                <Button type="text" size="small" aria-label={t('delivered.methodAria')} icon={<InfoCircleOutlined aria-hidden="true" />} style={{ color: 'var(--cf-muted)' }} />
              </Tooltip>
            </Space>}
            value={data ? formatNumber(data.delivered.count) : '—'}
            hint={data && <>
              <div><span>{t('delivered.dailyAverage', { value: formatNumber(data.delivered.dailyAverage, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) })}</span>{t('separator')}<span>{data.delivered.changePercent != null ? t('delivered.change', { value: percent(data.delivered.changePercent) }) : t('delivered.noBase')}</span></div>
              <div>{t('delivered.previous', { count: data.delivered.previous.count, start: data.delivered.previous.startDate, end: data.delivered.previous.endDate })}</div>
            </>}
          />
        </div>

        <div data-testid="overview-lead-time">
          <StatTile
            label={<Space size={6}>{t('leadTime.title')}
              <Tooltip title={t('leadTime.method')} trigger={['hover', 'focus']} styles={{ root: { maxWidth: 420 } }}>
                <Button type="text" size="small" aria-label={t('leadTime.methodAria')} icon={<InfoCircleOutlined aria-hidden="true" />} style={{ color: 'var(--cf-muted)' }} />
              </Tooltip>
            </Space>}
            value={data ? (data.leadTimeDays != null ? t('leadTime.value', { value: formatNumber(data.leadTimeDays, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }) : '—') : '—'}
            hint={data && <>
              <div>{data.leadTimeChangePercent != null ? t('delivered.change', { value: percent(data.leadTimeChangePercent) }) : t('leadTime.noBase')}</div>
              <div>{t('leadTime.caption')}</div>
            </>}
          />
        </div>

        <div data-testid="overview-in-progress">
          <StatTile
            label={t('inProgress.title')}
            value={data ? formatNumber(data.inProgressCount) : '—'}
            hint={<div className="overview-stat-detail">{t('inProgress.caption')}</div>}
          />
        </div>

        <div data-testid="overview-first-pass">
          <StatTile
            label={<Space size={6}>{t('firstPass.title')}
              <Tooltip title={t('firstPass.method')} trigger={['hover', 'focus']} styles={{ root: { maxWidth: 420 } }}>
                <Button type="text" size="small" aria-label={t('firstPass.methodAria')} icon={<InfoCircleOutlined aria-hidden="true" />} style={{ color: 'var(--cf-muted)' }} />
              </Tooltip>
            </Space>}
            value={data ? (data.firstPassRate != null ? rate(data.firstPassRate) : '—') : '—'}
            hint={<div className="overview-stat-detail">{data?.firstPassRate == null && data ? t('firstPass.noData') : t('firstPass.caption')}</div>}
          />
        </div>

        <div data-testid="overview-failed">
          <StatTile
            label={t('failed.title')}
            tone={data && data.failedDispatches.count > 0 ? 'warn' : 'default'}
            value={data ? formatNumber(data.failedDispatches.count) : '—'}
            hint={data && <>
              <div><span>{t('failed.share', { value: formatNumber(data.failedDispatches.sharePercent, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) })}</span>{t('separator')}<span>{data.failedDispatches.changePercent != null ? t('failed.change', { value: percent(data.failedDispatches.changePercent) }) : t('failed.noBase')}</span></div>
              <div>{t('failed.previous', { count: data.failedDispatches.previous.count, start: data.failedDispatches.previous.startDate, end: data.failedDispatches.previous.endDate })}</div>
            </>}
          />
        </div>

        <div data-testid="overview-rework">
          <StatTile
            label={<Space size={6}>{t('rework.title')}
              <Tooltip title={t('rework.method')} trigger={['hover', 'focus']} styles={{ root: { maxWidth: 420 } }}>
                <Button type="text" size="small" aria-label={t('rework.methodAria')} icon={<InfoCircleOutlined aria-hidden="true" />} style={{ color: 'var(--cf-muted)' }} />
              </Tooltip>
            </Space>}
            value={data ? (data.reworkRate != null ? rate(data.reworkRate) : '—') : '—'}
            hint={<div className="overview-stat-detail">{data?.reworkRate == null && data ? t('rework.noData') : t('rework.caption')}</div>}
          />
        </div>

        <div data-testid="overview-tokens">
          <StatTile
            label={t('tokens.title')}
            value={data ? formatNumber(data.tokenUsage.count) : '—'}
            hint={data && <>
              <div className="overview-trend">
                <span className="overview-sparkline" role="img" aria-label={t('tokens.trendAria', { days: data.daysInRange })}>
                  <Sparkline data={data.tokenUsage.daily.map((day) => day.count)} color="var(--cf-accent)" width={120} height={32} />
                </span>
                <span>{data.tokenUsage.changePercent != null ? t('tokens.change', { value: percent(data.tokenUsage.changePercent) }) : t('tokens.noBase')}</span>
              </div>
              <div>{t('tokens.previous', { count: data.tokenUsage.previous.count, start: data.tokenUsage.previous.startDate, end: data.tokenUsage.previous.endDate })}</div>
            </>}
          />
        </div>
      </div>

      <div className="overview-lists">
        <SurfaceCard testId="overview-pending" title={<Space size={8}>{t('pending.title')}<Tag>{t('pending.caption')}</Tag></Space>}>
          <p className="overview-caption">{t('pending.hint')}</p>
          {pendingQuery.isError && (
            <Alert
              type="error" showIcon icon={<CloseCircleFilled aria-hidden="true" />} message={t('pending.loadFailure')}
              action={<Button size="small" onClick={() => { void pendingQuery.refetch(); }}>{t('pending.retry')}</Button>}
              style={{ marginBottom: 12 }}
            />
          )}
          <div className="cfds-table-wrap">
            <Table<PendingWorkitem>
              rowKey="id"
              dataSource={pendingPage?.list ?? []}
              loading={pendingQuery.isLoading}
              columns={columns}
              scroll={{ x: PENDING_TABLE_WIDTH }}
              rowClassName={(record) => record.statusCategory === 'PENDING_DECISION' ? 'overview-row-decision' : ''}
              onRow={(record) => ({ onClick: () => navigate(`/workitems/${record.id}`), style: { cursor: 'pointer' } })}
              locale={{ emptyText: (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={pendingQuery.isLoading ? t('pending.loading') : pendingQuery.isError ? t('pending.loadFailure') : t('pending.empty')}>
                  <span>{t('pending.emptyHint')}</span>
                </Empty>
              ) }}
              pagination={{
                current: page,
                pageSize,
                total: pendingPage?.total ?? 0,
                showSizeChanger: false,
                showTotal: (total) => t('pending.total', { count: total }),
                onChange: (nextPage, nextPageSize) => { setPage(nextPage); setPageSize(nextPageSize); },
              }}
            />
          </div>
        </SurfaceCard>
        <SurfaceCard testId="overview-created-list" title={<Space size={8}>{t('createdList.title', { range: rangeLabel })}<Tag>{t('createdList.caption')}</Tag></Space>}>
          <p className="overview-caption">{t('createdList.hint')}</p>
          {createdQuery.isError && (
            <Alert
              type="error" showIcon icon={<CloseCircleFilled aria-hidden="true" />} message={t('createdList.loadFailure')}
              action={<Button size="small" onClick={() => { void createdQuery.refetch(); }}>{t('createdList.retry')}</Button>}
              style={{ marginBottom: 12 }}
            />
          )}
          <div className="cfds-table-wrap">
            <Table<CreatedWorkitem>
              rowKey="id"
              dataSource={createdPageData?.list ?? []}
              loading={createdQuery.isLoading}
              columns={createdColumns}
              scroll={{ x: CREATED_TABLE_WIDTH }}
              onRow={(record) => ({ onClick: () => navigate(`/workitems/${record.id}`), style: { cursor: 'pointer' } })}
              locale={{ emptyText: (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={createdQuery.isLoading ? t('createdList.loading') : createdQuery.isError ? t('createdList.loadFailure') : t('createdList.empty')}>
                  <span>{t('createdList.emptyHint')}</span>
                </Empty>
              ) }}
              pagination={{
                current: createdPage,
                pageSize: createdPageSize,
                total: createdPageData?.total ?? 0,
                showSizeChanger: false,
                showTotal: (total) => t('createdList.total', { count: total }),
                onChange: (nextPage, nextPageSize) => { setCreatedPage(nextPage); setCreatedPageSize(nextPageSize); },
              }}
            />
          </div>
        </SurfaceCard>

        <SurfaceCard testId="overview-failures" title={<Space size={8}>{t('failures.title')}<Tag>{t('failures.caption')}</Tag></Space>}>
          {overviewQuery.isError ? (
            <Alert
              type="error" showIcon icon={<CloseCircleFilled aria-hidden="true" />} message={t('failures.loadFailure')}
              action={<Button size="small" onClick={() => { void overviewQuery.refetch(); }}>{t('failures.retry')}</Button>}
              style={{ marginBottom: 12 }}
            />
          ) : (
            <div className="cfds-table-wrap">
              <Table<DeliveryOverviewFailureReason>
                rowKey="reason"
                dataSource={data?.failureReasons ?? []}
                loading={overviewQuery.isLoading}
                columns={failureColumns}
                scroll={{ x: FAILURES_TABLE_WIDTH }}
                locale={{ emptyText: (
                  <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description={overviewQuery.isLoading ? t('failures.loading') : t('failures.empty')}>
                    <span>{t('failures.emptyHint')}</span>
                  </Empty>
                ) }}
                pagination={false}
              />
            </div>
          )}
        </SurfaceCard>
      </div>
    </div>
  );
}
