import { useTranslation } from 'react-i18next';
import { SkillTestRuntimeModal } from './components/SkillTestRuntimeModal';
import { SkillToolsModal } from './components/SkillToolsModal';
import { SkillBatchCategoryModal } from './components/SkillBatchCategoryModal';
import { SkillFormModal } from './components/SkillFormModal';
import { SkillDetailModal } from './components/SkillDetailModal';
import { SkillCategoryManagerModal } from './components/SkillCategoryManagerModal';
import {
  errorMessage, formatConnectionResult, skillTypeLabel, typeColor, UNCATEGORIZED,
} from './skillFormat';
import { formatNumber } from '@/shared/i18n/format';
import { i18n } from '@/shared/i18n';
import { PageHeading } from '@/shared/ui/PageHeading';
import { Toolbar } from '@/shared/ui/PageShell';
import { usePageSizePreference } from '@/shared/lib/usePageSizePreference';
import { useEffect, useMemo, useState } from 'react';
import type { AriaAttributes, Key, ReactNode } from 'react';
import {
  Card, Collapse, Tag, Button, Space, Segmented, Popconfirm, message,
  Typography, TreeSelect, Spin, Switch, Empty,
} from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import {
  PlusOutlined, EditOutlined, DeleteOutlined, FileTextOutlined,
  CloseCircleFilled, ApartmentOutlined,
} from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  listSkills, deleteSkill, testSkillConnection, setSkillCategory, listCategories,
  batchSetSkillCategory, listAllSkills,
} from './api';
import type {
  Skill, SkillConnectionTestResult, Category, BatchSkillCategoryResult,
} from './api';
import type { ColumnsType } from 'antd/es/table';
import type { CheckboxProps } from 'antd';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { useAuthStore } from '@/shared/auth/store';
import { listExecutors } from '@/features/executor/api';
import type { ExecutorVO } from '@/features/executor/api';
import { buildCategorySkillGroups } from './categoryTree';
import type { CategorySkillGroup } from './categoryTree';
import {
  isCategoryGroupingEnabled, setCategoryGroupingEnabled,
} from './categoryDisplayPreference';
import { useSkillCategories } from './hooks/useSkillCategories';
import { useSkillPackage } from './hooks/useSkillPackage';
import { useSkillForm } from './hooks/useSkillForm';

// useQuery 未决/失败时的兜底必须是稳定引用：若每次渲染都新建 []，
// 依赖 categories 的 useEffect 会每轮都触发 setState，形成无限重渲染。
const NO_CATEGORIES: Category[] = [];

export function SkillListPage() {
  const { t } = useTranslation('skill');
  const filterTypeOptions = [
    { value: '', label: t('all') },
    { value: 'SKILL', label: t('types.skill') },
    { value: 'MCP', label: t('types.mcp') },
    { value: 'HOOK', label: t('types.hook') },
  ];

  const queryClient = useQueryClient();
  const runWithAccess = useAccessCommand();
  const [page, setPage] = useState(1);
  const [size, setSize] = usePageSizePreference('ainative.skills.pageSize', [10, 20, 50, 100], 10);
  const [typeFilter, setTypeFilter] = useState('');

  const [detailSkill, setDetailSkill] = useState<Skill | null>(null);
  const [pendingDeleteId, setPendingDeleteId] = useState<number | null>(null);
  const [connectionResults, setConnectionResults] = useState<Record<number, SkillConnectionTestResult>>({});
  const [toolListResult, setToolListResult] = useState<SkillConnectionTestResult | null>(null);
  const [testingSkillId, setTestingSkillId] = useState<number | null>(null);
  const [testTargetSkill, setTestTargetSkill] = useState<Skill | null>(null);
  const [testExecutorId, setTestExecutorId] = useState<number | undefined>();

  const workspaceId = useAuthStore((s) => s.currentWorkspace?.id ?? null);
  const currentUserId = useAuthStore((s) => s.user?.id ?? null);
  // 「按分类展示」按用户 + 项目维度持久化；默认关闭，保持原有平铺表格
  const [groupByCategory, setGroupByCategory] = useState(
    () => isCategoryGroupingEnabled(workspaceId, currentUserId),
  );
  const [selectedRowKeys, setSelectedRowKeys] = useState<Key[]>([]);
  const [batchCategoryOpen, setBatchCategoryOpen] = useState(false);
  const [batchCategoryValue, setBatchCategoryValue] = useState<string>(UNCATEGORIZED);
  const [batchApplying, setBatchApplying] = useState(false);
  const [savingCategoryIds, setSavingCategoryIds] = useState<number[]>([]);
  const [activeCategorySkillId, setActiveCategorySkillId] = useState<number | null>(null);
  useEffect(() => {
    setActiveCategorySkillId(null);
  }, [workspaceId, currentUserId, page, typeFilter, groupByCategory]);

  // 切换项目 / 账号后重新读取各自的展示偏好
  useEffect(() => {
    setGroupByCategory(isCategoryGroupingEnabled(workspaceId, currentUserId));
  }, [workspaceId, currentUserId]);

  const { data, isLoading } = useQuery({
    queryKey: ['skills', page, size, typeFilter],
    queryFn: () => listSkills({ page, size, type: typeFilter || undefined }),
    enabled: !groupByCategory,
  });
  const skills = data?.list ?? [];
  const total = data?.total ?? 0;
  const { data: executors = [] } = useQuery<ExecutorVO[]>({ queryKey: ['executors'], queryFn: () => listExecutors() });

  // 分类数据供打标下拉、管理弹窗与分组视图共用；页面挂载即拉取（只读接口）
  const { data: categories = NO_CATEGORIES } = useQuery<Category[]>({ queryKey: ['categories'], queryFn: () => listCategories() });
  // 分组视图按完整筛选结果分组，单独翻页取全量；平铺视图仍走分页查询
  const { data: groupedSkills = [], isLoading: groupedSkillsLoading } = useQuery({
    queryKey: ['skills', 'all', typeFilter],
    queryFn: () => listAllSkills(typeFilter || undefined),
    enabled: groupByCategory,
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['skills'] });
  const invalidateCategories = () => queryClient.invalidateQueries({ queryKey: ['categories'] });

  const categoryManager = useSkillCategories({
    categories,
    invalidateSkills: invalidate,
    invalidateCategories,
  });
  const packageView = useSkillPackage(detailSkill);
  const skillForm = useSkillForm({ invalidateSkills: invalidate });

  const categoryGrouping = useMemo(
    () => buildCategorySkillGroups(categoryManager.categoryTreeData, groupedSkills),
    [categoryManager.categoryTreeData, groupedSkills],
  );

  const handleToggleGroupByCategory = (checked: boolean) => {
    setGroupByCategory(checked);
    setCategoryGroupingEnabled(workspaceId, currentUserId, checked);
    setSelectedRowKeys([]);
  };

  const renderCategoryGroup = (group: CategorySkillGroup): ReactNode => (
    <Collapse
      key={`category-${group.category.id}`}
      defaultActiveKey={[`category-${group.category.id}`]}
      items={[{
        key: `category-${group.category.id}`,
        label: (
          <Space size={8}>
            <Typography.Text strong>{group.category.name}</Typography.Text>
            <Typography.Text type="secondary">{t('items', { count: group.totalCount })}</Typography.Text>
          </Space>
        ),
        children: (
          <>
            {group.direct.length > 0 && (
              <div className="cfds-table-wrap" style={{ marginBottom: 8 }}>
                <Table
                  rowKey="id"
                  columns={columns}
                  dataSource={group.direct}
                  pagination={false}
                  size="small"
                  scroll={{ x: 1520 }}
                />
              </div>
            )}
            {group.children.length > 0 && (
              <Space direction="vertical" size={8} style={{ width: '100%', marginLeft: 12 }}>
                {group.children.map(renderCategoryGroup)}
              </Space>
            )}
          </>
        ),
      }]}
    />
  );

  const renderGroupedView = (): ReactNode => {
    // 首次加载时由外层 Spin 呈现加载态，避免闪现「暂无能力」空态
    if (groupedSkillsLoading && groupedSkills.length === 0) return null;
    if (categoryGrouping.groups.length === 0 && categoryGrouping.uncategorized.length === 0) {
      return <Empty description={i18n.t('skill:empty')} />;
    }
    return (
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        {categoryGrouping.groups.map(renderCategoryGroup)}
        {categoryGrouping.uncategorized.length > 0 && (
          <Collapse
            defaultActiveKey={['uncategorized']}
            items={[{
              key: 'uncategorized',
              label: (
                <Space size={8}>
                  <Typography.Text strong>{i18n.t('skill:uncategorized')}</Typography.Text>
                  <Typography.Text type="secondary">{t('items', { count: categoryGrouping.uncategorized.length })}</Typography.Text>
                </Space>
              ),
              children: (
                <div className="cfds-table-wrap">
                  <Table
                    rowKey="id"
                    columns={columns}
                    dataSource={categoryGrouping.uncategorized}
                    pagination={false}
                    size="small"
                    scroll={{ x: 1520 }}
                  />
                </div>
              ),
            }]}
          />
        )}
        <Typography.Text type="secondary">
          {t('grouped', { count: groupedSkills.length })}
        </Typography.Text>
      </Space>
    );
  };

  const closeDetail = () => {
    setDetailSkill(null);
    packageView.resetPackagePreview();
  };

  const deleteMut = useMutation({
    mutationFn: deleteSkill,
    onSuccess: () => {
      invalidate();
      setPendingDeleteId(null);
      message.success(i18n.t('skill:deleted'));
    },
  });

  const testConnectionMut = useMutation({
    mutationFn: ({ skillId, executorId }: { skillId: number; executorId?: number }) => testSkillConnection(skillId, executorId),
    onSuccess: (result, variables) => {
      setConnectionResults((prev) => ({ ...prev, [variables.skillId]: result }));
      if (result.success) {
        message.success(formatConnectionResult(result));
      } else {
        message.error(formatConnectionResult(result));
      }
    },
    onError: (error, variables) => {
      const result: SkillConnectionTestResult = error instanceof Error && error.message
        ? { success: false, message: error.message }
        : { success: false, message: '', ownedFailureCode: 'CONNECTION_FAILED' };
      setConnectionResults((prev) => ({ ...prev, [variables.skillId]: result }));
      message.error(formatConnectionResult(result));
    },
    onSettled: () => setTestingSkillId(null),
  });

  const handleTestConnection = (skill: Skill) => {
    runWithAccess('READ_WRITE', i18n.t('skill:connection.testAction'), () => {
      setTestTargetSkill(skill);
      setTestExecutorId(executors.find((executor) => executor.status === 'ONLINE')?.id);
    });
  };

  const startConnectionTest = (skill: Skill, executorId?: number) => {
    setTestingSkillId(skill.id);
    setConnectionResults((prev) => {
      const next = { ...prev };
      delete next[skill.id];
      return next;
    });
    testConnectionMut.mutate({ skillId: skill.id, executorId });
  };

  const handleBatchApply = async () => {
    if (selectedRowKeys.length === 0) return;
    await runWithAccess('READ_WRITE', i18n.t('skill:category.batchAction'), async () => {
      const categoryId = batchCategoryValue === UNCATEGORIZED ? null : Number(batchCategoryValue);
      setBatchApplying(true);
      try {
        const results: BatchSkillCategoryResult[] = await batchSetSkillCategory(
          selectedRowKeys.map(Number),
          categoryId,
        );
        const failed = results.filter((item) => !item.success);
        if (failed.length === 0) {
          message.success(i18n.t('skill:category.batchSaved', { count: results.length }));
        } else {
          message.warning(
            i18n.t('skill:category.batchFailed', { succeeded: formatNumber(results.length - failed.length), failed: formatNumber(failed.length), reasons: failed.map((item) => `#${item.skillId} ${item.message ?? ''}`.trim()).join(i18n.t('skill:category.failureSeparator')) }),
          );
        }
        setBatchCategoryOpen(false);
        setSelectedRowKeys([]);
        invalidate();
      } catch (error) {
        message.error(errorMessage(error));
      } finally {
        setBatchApplying(false);
      }
    });
  };

  const columns: ColumnsType<Skill> = [
    { title: t('id'), dataIndex: 'id', width: 70 },
    {
      title: t('name'), dataIndex: 'name', align: 'left',
      render: (v: string) => <EllipsisText tooltip={v}>{v}</EllipsisText>,
    },
    {
      // 描述内容普遍很长，悬停提示意义不大：纯省略，不加 tooltip
      title: t('description'), dataIndex: 'description', align: 'left', ellipsis: true,
      render: (v: string) => v ?? '—',
    },
    {
      title: t('type'), dataIndex: 'type', width: 90,
      render: (type: Skill['type']) => <Tag color={Object.prototype.hasOwnProperty.call(typeColor, type) ? typeColor[type] : undefined}>{skillTypeLabel(type)}</Tag>,
    },
    {
      title: t('category.label'), key: 'category', width: 160,
      render: (_, record) => activeCategorySkillId === record.id ? (
        <TreeSelect
          autoFocus
          open
          onDropdownVisibleChange={(open) => { if (!open) setActiveCategorySkillId(null); }}
          onBlur={() => setActiveCategorySkillId(null)}
          onKeyDown={(event) => { if (event.key === 'Escape') setActiveCategorySkillId(null); }}
          aria-label={t('category.rowLabel', { name: record.name })}
          style={{ width: 130 }}
          dropdownMatchSelectWidth={260}
          showSearch
          treeDefaultExpandAll
          treeLine
          treeNodeFilterProp="path"
          treeNodeLabelProp="path"
          placeholder={t('category.select')}
          allowClear={{ clearIcon: <CloseCircleFilled role="button" aria-label={t('clearCategory')} /> }}
          value={record.categoryId == null ? undefined : String(record.categoryId)}
          treeData={categoryManager.categorySelectTree}
          loading={savingCategoryIds.includes(record.id)}
          disabled={savingCategoryIds.includes(record.id)}
          onChange={(value) => runWithAccess('READ_WRITE', t('category.set'), async () => {
            setActiveCategorySkillId(null);
            setSavingCategoryIds((ids) => [...ids, record.id]);
            try {
              await setSkillCategory(record.id, value == null ? null : Number(value));
              await invalidate();
              message.success(i18n.t('skill:category.saved'));
            } catch (error) {
              message.error(errorMessage(error));
            } finally {
              setSavingCategoryIds((ids) => ids.filter((id) => id !== record.id));
            }
          })}
        />
      ) : (
        <button
          type="button"
          aria-label={t('category.rowEdit', { name: record.name })}
          title={record.categoryPath || t('category.select')}
          disabled={savingCategoryIds.includes(record.id)}
          onClick={() => runWithAccess('READ_WRITE', t('category.set'), () => setActiveCategorySkillId(record.id))}
          style={{ border: 0, background: 'none', padding: 0, font: 'inherit',
            color: record.categoryId == null ? 'var(--cf-muted)' : 'inherit', cursor: 'pointer',
            maxWidth: 130, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', textAlign: 'left' }}
        >
          {savingCategoryIds.includes(record.id) ? t('saving') : (record.categoryPath || '—')}
        </button>
      ),
    },
    {
      title: t('actions'), width: 280, fixed: 'right',
      render: (_, record) => {
        const connectionResult = connectionResults[record.id];
        return (
          <Space size={4} wrap>
            <Button type="link" size="small" icon={<FileTextOutlined aria-hidden="true" />} onClick={() => setDetailSkill(record)}>{t('detail')}</Button>
            {record.type === 'MCP' && (
              <Button
                type="link"
                size="small"
                loading={testingSkillId === record.id}
                onClick={() => handleTestConnection(record)}
              >
                {t('connection.test')}</Button>
            )}
            {record.type === 'MCP' && connectionResult && (
              <Tag color={connectionResult.success ? 'success' : 'error'}>
                {formatConnectionResult(connectionResult)}
              </Tag>
            )}
            {record.type === 'MCP' && connectionResult?.success && connectionResult.tools && (
              <Button type="link" size="small" onClick={() => setToolListResult(connectionResult)}>
                {t('connection.viewTools', { count: connectionResult.tools.length })}
              </Button>
            )}
            <Button type="link" size="small" icon={<EditOutlined aria-hidden="true" />} onClick={() => skillForm.openEdit(record)}>{t('editButton')}</Button>
            <Popconfirm
              title={t('deleteConfirm')}
              open={pendingDeleteId === record.id}
              onOpenChange={(open) => {
                if (!open) setPendingDeleteId(null);
              }}
              onConfirm={() => runWithAccess(
                'READ_WRITE',
                t('deleteAction'),
                () => deleteMut.mutate(record.id),
              )}
            >
              <Button
                type="link"
                size="small"
                danger
                icon={<DeleteOutlined aria-hidden="true" />}
                onClick={() => runWithAccess(
                  'READ_WRITE',
                  t('deleteAction'),
                  () => setPendingDeleteId(record.id),
                )}
              >
                {t('delete')}</Button>
            </Popconfirm>
          </Space>
        );
      },
    },
  ];

  return (
    <>
      <Card
        className="cf-content-card" title={<PageHeading title={t('title')} />}
        extra={<Button type="primary" icon={<PlusOutlined aria-hidden="true" />} onClick={skillForm.openCreate}>{t('create')}</Button>}
      >
        <Toolbar filters={<>
          <Segmented
            options={filterTypeOptions}
            value={typeFilter}
            onChange={(v) => { setTypeFilter(v as string); setPage(1); }}
          />
        </>} actions={<>
          <Space size={8}>
            <Typography.Text type="secondary">{t('category.group')}</Typography.Text>
            <Switch
              checked={groupByCategory}
              onChange={handleToggleGroupByCategory}
              aria-label={t('category.group')}
            />
          </Space>
          <Button icon={<ApartmentOutlined aria-hidden="true" />} onClick={categoryManager.openCategoryManage}>{t('category.manage')}</Button>
        </>} />
        {groupByCategory ? (
          <Spin spinning={groupedSkillsLoading}>
            {renderGroupedView()}
          </Spin>
        ) : (
          <>
            {selectedRowKeys.length > 0 && (
              <div style={{ marginBottom: 12 }}>
                <Space>
                  <Typography.Text type="secondary">{t('selected', { count: selectedRowKeys.length })}</Typography.Text>
                  <Button
                    size="small"
                    onClick={() => runWithAccess('READ_WRITE', t('category.batchAction'), () => setBatchCategoryOpen(true))}
                  >
                    {t('category.batch')}</Button>
                  <Button size="small" type="text" onClick={() => setSelectedRowKeys([])}>{t('clearSelection')}</Button>
                </Space>
              </div>
            )}
            <div className="cfds-table-wrap">
            <Table
              rowKey="id"
              columns={columns}
              dataSource={skills}
              loading={isLoading}
              rowSelection={{
                getCheckboxProps: (record): CheckboxProps & Pick<AriaAttributes, 'aria-label'> => ({ 'aria-label': t('selection.row', { name: record.name }) }),
                getTitleCheckboxProps: () => ({ 'aria-label': t('selection.all') }),
                selectedRowKeys,
                onChange: (keys) => setSelectedRowKeys(keys),
              }}
              pagination={{
                current: page, pageSize: size, total,
                onChange: (p, ps) => { setPage(p); setSize(ps); },
                showSizeChanger: true,
                showTotal: (count) => t('count', { count }),
              }}
              scroll={{ x: 900 }}
            />
            </div>
          </>
        )}
      </Card>

      <SkillTestRuntimeModal
        skill={testTargetSkill}
        executorId={testExecutorId}
        executors={executors}
        onExecutorChange={setTestExecutorId}
        onConfirm={() => {
          if (!testTargetSkill || !testExecutorId) return;
          const skill = testTargetSkill;
          setTestTargetSkill(null);
          startConnectionTest(skill, testExecutorId);
        }}
        onCancel={() => setTestTargetSkill(null)}
      />
      <SkillDetailModal
        detailSkill={detailSkill}
        onClose={closeDetail}
        packageFiles={packageView.packageFiles}
        packageFilesLoading={packageView.packageFilesLoading}
        packageFilesError={packageView.packageFilesError}
        packageTree={packageView.packageTree}
        selectedPackagePath={packageView.selectedPackagePath}
        selectedPackageEntry={packageView.selectedPackageEntry}
        packageFileContent={packageView.packageFileContent}
        packageFileLoading={packageView.packageFileLoading}
        downloadingPackage={packageView.downloadingPackage}
        onPackageSelect={packageView.handlePackageSelect}
        onDownloadPackage={packageView.handleDownloadPackage}
      />

      <SkillFormModal
        open={skillForm.formOpen}
        editingSkill={skillForm.editingSkill}
        accessMode={skillForm.accessMode}
        packageReading={skillForm.packageReading}
        zipFile={skillForm.zipFile}
        selectedType={skillForm.selectedType}
        form={skillForm.form}
        fileInputRef={skillForm.fileInputRef}
        zipInputRef={skillForm.zipInputRef}
        categorySelectOptions={categoryManager.categorySelectOptions}
        submitting={skillForm.submitting}
        onAccessModeChange={skillForm.setAccessMode}
        onResetPackageSelection={skillForm.resetPackageSelection}
        onPackageSelectFiles={skillForm.handlePackageSelectFiles}
        onSubmit={skillForm.handleSubmit}
        onCancel={skillForm.closeForm}
      />
      <SkillToolsModal result={toolListResult} onClose={() => setToolListResult(null)} />

      <SkillCategoryManagerModal
        open={categoryManager.categoryManageOpen}
        onClose={() => categoryManager.setCategoryManageOpen(false)}
        categories={categories}
        manageTreeData={categoryManager.manageTreeData}
        parentCategoryOptions={categoryManager.parentCategoryOptions}
        editingCategoryId={categoryManager.editingCategoryId}
        pendingDeleteCategoryId={categoryManager.pendingDeleteCategoryId}
        categoryExpandedKeys={categoryManager.categoryExpandedKeys}
        form={categoryManager.categoryForm}
        saving={categoryManager.createCategoryMut.isPending || categoryManager.updateCategoryMut.isPending}
        deleting={categoryManager.deleteCategoryMut.isPending}
        onCreate={categoryManager.startCreateCategory}
        onEdit={categoryManager.editCategory}
        onRequestDelete={(id) => categoryManager.setPendingDeleteCategoryId(id)}
        onDelete={categoryManager.handleDeleteCategory}
        onCancelDelete={() => categoryManager.setPendingDeleteCategoryId(null)}
        onExpand={categoryManager.setCategoryExpandedKeys}
        onSubmit={categoryManager.handleCategorySubmit}
      />

      <SkillBatchCategoryModal
        open={batchCategoryOpen}
        selectedCount={selectedRowKeys.length}
        value={batchCategoryValue}
        options={categoryManager.categorySelectOptions}
        applying={batchApplying}
        onChange={setBatchCategoryValue}
        onApply={handleBatchApply}
        onClose={() => setBatchCategoryOpen(false)}
      />
    </>
  );
}
