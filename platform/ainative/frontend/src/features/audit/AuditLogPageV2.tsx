import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Alert, Button, Descriptions, Drawer } from 'antd';
import { CloseOutlined, CloseCircleOutlined } from '@ant-design/icons';
import { PageHeading } from '@/shared/ui/PageHeading';
import { Toolbar } from '@/shared/ui/PageShell';
import { useDebouncedValue } from '@/shared/hooks/useDebouncedValue';
import { usePageSizePreference } from '@/shared/lib/usePageSizePreference';
import { listAllAgents } from '@/features/agent/api';
import { countAuditLogs, listAuditLogs } from './api';
import type { AuditLog } from './api';
import { EMPTY_AUDIT_FILTERS, readInitialFilters, toQueryFilters } from './auditFilters';
import type { AuditFilterState } from './auditFilters';
import { parseDetail, triggerText } from './auditDetail';
import { AuditLogFilterBar } from './AuditLogFilterBar';
import { AuditLogTable } from './AuditLogTable';
import { AuditJsonBlock } from './AuditJsonBlock';
import './AuditLogPageV2.css';

const KEYWORD_DEBOUNCE_MS = 300;

export function AuditLogPageV2() {
  const { t } = useTranslation('audit');
  const [searchParams] = useSearchParams();
  const initialFilters = useMemo(() => readInitialFilters(searchParams), [searchParams]);
  const [filters, setFilters] = useState<AuditFilterState>(initialFilters);
  const [keywordInput, setKeywordInput] = useState(initialFilters.keyword);
  const [page, setPage] = useState(1);
  const [size, setSize] = usePageSizePreference('ainative.auditLogs.pageSize', [10, 20, 50, 100, 200], 10);
  const [selected, setSelected] = useState<AuditLog | null>(null);
  const keyword = useDebouncedValue(keywordInput, KEYWORD_DEBOUNCE_MS);

  useEffect(() => {
    setPage(1);
  }, [keyword]);

  const countFilters = useMemo(() => toQueryFilters(filters, keyword), [filters, keyword]);
  const queryFilters = useMemo(() => ({ page, size, ...countFilters }), [countFilters, page, size]);

  const { data: rows = [], isFetching, isError } = useQuery({
    queryKey: ['audit-logs', queryFilters],
    queryFn: () => listAuditLogs(queryFilters),
    placeholderData: keepPreviousData,
  });

  const { data: total = 0 } = useQuery({
    queryKey: ['audit-logs-count', countFilters],
    queryFn: () => countAuditLogs(countFilters),
    placeholderData: keepPreviousData,
  });

  const { data: agents = [] } = useQuery({
    queryKey: ['audit-log-agents'],
    queryFn: () => listAllAgents({}),
    enabled: filters.actorType === 'AGENT',
  });

  const updateFilters = (next: AuditFilterState) => {
    setFilters(next);
    setPage(1);
  };

  const resetFilters = () => {
    setFilters({ ...EMPTY_AUDIT_FILTERS });
    setKeywordInput('');
    setPage(1);
  };

  const detail = selected ? parseDetail(selected) : null;

  return (
    <div className="audit-page">
      <div className="audit-page-head">
        <PageHeading title={t('title')} description={t('subtitle')} />
        <span className="audit-count-chip" aria-live="polite">{t('countChip', { count: total })}</span>
      </div>
      {/* 工具栏(筛选)不再带入场动画,不与列表卡各自形成 stacking context,弹层不会被表格遮盖。 */}
      <Toolbar
        filters={
          <AuditLogFilterBar
            value={filters}
            keywordInput={keywordInput}
            agents={agents}
            onChange={updateFilters}
            onKeywordChange={setKeywordInput}
          />
        }
        actions={<Button onClick={resetFilters}>{t('resetFilters')}</Button>}
      />
      {isError && (
        <Alert
          type="error"
          showIcon
          icon={<CloseCircleOutlined aria-hidden="true" />}
          message={t('loadFailed')}
          className="audit-error"
        />
      )}
      <div className="cfds-table-wrap audit-table-card">
        <AuditLogTable
          rows={rows}
          total={total}
          loading={isFetching}
          page={page}
          size={size}
          onPageChange={(nextPage, nextSize) => {
            setPage(nextPage);
            setSize(nextSize);
          }}
          onSelect={setSelected}
        />
      </div>
      <Drawer
        closable={{ closeIcon: <CloseOutlined aria-hidden="true" />, 'aria-label': t('close') }}
        title={t('detailTitle')}
        width="min(760px, 95vw)"
        open={selected !== null}
        onClose={() => setSelected(null)}
      >
        {selected && (
          <>
            <Descriptions column={1} bordered size="small" items={[
              { key: 'id', label: t('logId'), children: selected.id },
              { key: 'action', label: t('rawAction'), children: selected.action },
              { key: 'trigger', label: t('trigger'), children: triggerText(selected) },
              { key: 'event', label: t('eventType'), children: detail?.eventType ? String(detail.eventType) : '-' },
            ]} />
            <AuditJsonBlock titleKey="fullRecord" value={selected} />
            <AuditJsonBlock titleKey="eventDetail" value={detail ?? {}} />
          </>
        )}
      </Drawer>
    </div>
  );
}
