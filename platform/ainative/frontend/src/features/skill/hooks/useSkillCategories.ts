import { useEffect, useMemo, useState } from 'react';
import type { Key } from 'react';
import { Form, message } from 'antd';
import { useTranslation } from 'react-i18next';
import { useMutation } from '@tanstack/react-query';
import { i18n } from '@/shared/i18n';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { createCategory, deleteCategory, updateCategory } from '../api';
import type { Category } from '../api';
import { buildCategoryTree, categoryMoveExclusions } from '../categoryTree';
import type { CategoryNode } from '../categoryTree';
import { errorMessage, UNCATEGORIZED } from '../skillFormat';
import type { DataNode } from 'antd/es/tree';

/** 分类下拉/树选择用的节点结构：value 恒为字符串 ID，path 用于搜索与回显。 */
export interface CategorySelectNode {
  value: string;
  title: string;
  path: string;
  children?: CategorySelectNode[];
}

/**
 * 分类管理：增删改状态 + 分类树派生数据。
 * 分类数据由页面统一查询后传入，这里只负责维护弹窗与树的交互。
 */
export function useSkillCategories({
  categories,
  invalidateSkills,
  invalidateCategories,
}: {
  categories: Category[];
  invalidateSkills: () => void;
  invalidateCategories: () => void;
}) {
  const { t } = useTranslation('skill');
  const runWithAccess = useAccessCommand();
  const [categoryManageOpen, setCategoryManageOpen] = useState(false);
  const [editingCategoryId, setEditingCategoryId] = useState<number | null>(null);
  const [pendingDeleteCategoryId, setPendingDeleteCategoryId] = useState<number | null>(null);
  const [categoryExpandedKeys, setCategoryExpandedKeys] = useState<Key[]>([]);
  const [categoryForm] = Form.useForm();
  useLocalizedValidation(categoryForm);

  const categoryTreeData = useMemo(() => buildCategoryTree(categories), [categories]);

  const categorySelectOptions = useMemo(() => [
    { value: UNCATEGORIZED, label: i18n.t('skill:uncategorized') },
    ...categories.map((category) => ({
      value: String(category.id),
      label: category.path || category.name,
    })),
  ], [categories, t]);

  const categorySelectTree = useMemo(() => {
    const toSelectTree = (nodes: CategoryNode[]): CategorySelectNode[] => nodes.map((node) => ({
      value: String(node.id),
      title: node.name,
      path: node.path || node.name,
      children: node.children.length ? toSelectTree(node.children) : undefined,
    }));
    return toSelectTree(categoryTreeData);
  }, [categoryTreeData]);

  const startCreateCategory = (parentId?: number | null) => {
    setEditingCategoryId(null);
    setPendingDeleteCategoryId(null);
    categoryForm.resetFields();
    categoryForm.setFieldsValue({
      parentId: parentId == null ? UNCATEGORIZED : String(parentId),
    });
  };

  const editCategory = (id: number) => {
    const category = categories.find((item) => item.id === id);
    if (!category) return;
    setEditingCategoryId(id);
    setPendingDeleteCategoryId(null);
    categoryForm.setFieldsValue({
      name: category.name,
      parentId: category.parentId == null ? UNCATEGORIZED : String(category.parentId),
      description: category.description || '',
    });
  };

  // 修改分类时上级选项要排除自身及其后代，防止移动到自己的子树下形成环
  const parentCategoryOptions = useMemo(() => {
    const blocked = editingCategoryId != null
      ? categoryMoveExclusions(categories, editingCategoryId)
      : new Set<number>();
    const filterNodes = (nodes: CategorySelectNode[]): CategorySelectNode[] => nodes
      .filter((node) => !blocked.has(Number(node.value)))
      .map((node) => ({
        ...node,
        children: node.children ? filterNodes(node.children) : undefined,
      }));
    return [
      { value: UNCATEGORIZED, title: i18n.t('skill:category.top'), path: i18n.t('skill:category.top') },
      ...filterNodes(categorySelectTree),
    ];
  }, [categories, categorySelectTree, editingCategoryId, t]);

  // 分类树变更（新增/删除/移动）后默认全部展开，方便继续维护
  useEffect(() => {
    setCategoryExpandedKeys(categories.map((category) => category.id));
  }, [categories]);

  // 分类目录保留纯数据，选中行通过 titleRender 展示删除入口。
  const manageTreeData = useMemo(() => {
    const toTreeData = (nodes: CategoryNode[]): DataNode[] => nodes.map((node) => ({
      key: node.id,
      title: node.name,
      children: node.children.length > 0 ? toTreeData(node.children) : undefined,
    }));
    return toTreeData(categoryTreeData);
  }, [categoryTreeData]);

  const openCategoryManage = () => {
    runWithAccess('ADMIN', i18n.t('skill:category.manage'), () => {
      startCreateCategory(null);
      setCategoryManageOpen(true);
    });
  };

  const createCategoryMut = useMutation({
    mutationFn: createCategory,
    onError: (error) => message.error(errorMessage(error)),
  });

  const updateCategoryMut = useMutation({
    mutationFn: ({ id, data }: {
      id: number;
      data: Parameters<typeof updateCategory>[1];
    }) => updateCategory(id, data),
    onError: (error) => message.error(errorMessage(error)),
  });

  const deleteCategoryMut = useMutation({
    mutationFn: deleteCategory,
    onSuccess: () => {
      // 分类增删改会改变能力列表里的分类路径回显，技能查询要一并失效
      invalidateCategories();
      invalidateSkills();
      message.success(i18n.t('skill:category.deleted'));
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const handleCategorySubmit = async () => {
    await runWithAccess('ADMIN', editingCategoryId ? i18n.t('skill:category.update') : i18n.t('skill:category.create'), async () => {
      // 校验失败由 antd 在字段上内联提示，静默返回即可，不能让拒绝逃逸成 unhandled rejection
      const values = await categoryForm.validateFields().catch(() => null);
      if (!values) return;
      const data = {
        name: values.name as string,
        parentId: values.parentId === UNCATEGORIZED || values.parentId == null
          ? null
          : Number(values.parentId),
        description: ((values.description as string | undefined) || '').trim() || null,
      };
      try {
        const saved = editingCategoryId
          ? await updateCategoryMut.mutateAsync({ id: editingCategoryId, data })
          : await createCategoryMut.mutateAsync(data);
        // 分类变更会让能力列表的分类路径回显失效
        invalidateCategories();
        invalidateSkills();
        message.success(editingCategoryId ? i18n.t('skill:category.saved') : i18n.t('skill:category.created'));
        // 保存后停在编辑态，直接用响应回填表单（列表刷新前 categories 里可能还没有新节点）
        setEditingCategoryId(saved.id);
        categoryForm.setFieldsValue({
          name: saved.name,
          parentId: saved.parentId == null ? UNCATEGORIZED : String(saved.parentId),
          description: saved.description || '',
        });
      } catch {
        // onError 已提示
      }
    });
  };

  const handleDeleteCategory = async (id: number) => {
    try {
      await deleteCategoryMut.mutateAsync(id);
      if (editingCategoryId === id) {
        startCreateCategory(null);
      }
    } catch {
      // onError 已提示（非空删除等服务端拒绝场景）
    }
  };

  return {
    categoryManageOpen,
    setCategoryManageOpen,
    editingCategoryId,
    pendingDeleteCategoryId,
    setPendingDeleteCategoryId,
    categoryExpandedKeys,
    setCategoryExpandedKeys,
    categoryForm,
    categoryTreeData,
    categorySelectOptions,
    categorySelectTree,
    parentCategoryOptions,
    manageTreeData,
    createCategoryMut,
    updateCategoryMut,
    deleteCategoryMut,
    startCreateCategory,
    editCategory,
    openCategoryManage,
    handleCategorySubmit,
    handleDeleteCategory,
  };
}
