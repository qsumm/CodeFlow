import type { Key, ReactNode } from 'react';
import { Button, Divider, Form, Input, Modal, Popconfirm, Space, Tree, TreeSelect, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import type { Category } from '../api';
import type { CategorySelectNode } from '../hooks/useSkillCategories';
import { UNCATEGORIZED } from '../skillFormat';

/** 分类管理弹窗：左侧分类树 + 右侧分类表单。 */
export function SkillCategoryManagerModal({
  open, onClose, categories, manageTreeData, parentCategoryOptions,
  editingCategoryId, pendingDeleteCategoryId, categoryExpandedKeys,
  form, saving, deleting,
  onCreate, onEdit, onRequestDelete, onDelete, onCancelDelete, onExpand, onSubmit,
}: {
  open: boolean;
  onClose: () => void;
  categories: Category[];
  manageTreeData: import('antd/es/tree').DataNode[];
  parentCategoryOptions: CategorySelectNode[];
  editingCategoryId: number | null;
  pendingDeleteCategoryId: number | null;
  categoryExpandedKeys: Key[];
  form: import('antd').FormInstance;
  saving: boolean;
  deleting: boolean;
  onCreate: (parentId?: number | null) => void;
  onEdit: (id: number) => void;
  onRequestDelete: (id: number) => void;
  onDelete: (id: number) => void;
  onCancelDelete: () => void;
  onExpand: (keys: Key[]) => void;
  onSubmit: () => void;
}) {
  const { t } = useTranslation('skill');
  const runWithAccess = useAccessCommand();
  return (
      <Modal
        closable={{ 'aria-label': t('close') }}
        title={t('category.manage')}
        open={open}
        onCancel={onClose}
        footer={null}
        width={860}
        forceRender
      >
        <div style={{ display: 'flex', gap: 16, minHeight: 320 }}>
          <div
            data-testid="category-manage-tree"
            style={{
              width: 300,
              flexShrink: 0,
              maxHeight: 420,
              overflow: 'auto',
              border: '1px solid var(--cf-border)',
              borderRadius: 8,
              padding: 12,
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
              <Typography.Text type="secondary">{t('category.directory')}</Typography.Text>
              <Button
                size="small"
                icon={<PlusOutlined aria-hidden="true" />}
                onClick={() => runWithAccess('ADMIN', t('category.create'), () => onCreate(editingCategoryId))}
              >
                {t('category.add')}</Button>
            </div>
            {categories.length === 0 ? (
              <Typography.Text type="secondary">{t('category.empty')}</Typography.Text>
            ) : (
              <Tree
                treeData={manageTreeData}
                titleRender={(node) => (
                  <span style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                    <span style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {node.title as ReactNode}
                    </span>
                    {editingCategoryId === node.key && (
                      <span onClick={(event) => event.stopPropagation()} onKeyDown={(event) => event.stopPropagation()}>
                        <Popconfirm
                          title={t('category.deleteConfirm', { name: node.title as string })}
                          description={t('category.deleteHint')}
                          open={pendingDeleteCategoryId === node.key}
                          onOpenChange={(open) => { if (!open) onCancelDelete(); }}
                          onConfirm={() => runWithAccess('ADMIN', t('category.delete'),
                            () => onDelete(Number(node.key)))}
                          okText={t('delete')}
                          cancelText={t('cancel')}
                          okButtonProps={{ danger: true, loading: deleting }}
                        >
                          <Button type="text" size="small" danger icon={<DeleteOutlined aria-hidden="true" />}
                            aria-label={t('category.deleteLabel', { name: node.title as string })}
                            title={t('category.delete')}
                            loading={deleting}
                            onClick={() => runWithAccess('ADMIN', t('category.delete'),
                              () => onRequestDelete(Number(node.key)))}
                          />
                        </Popconfirm>
                      </span>
                    )}
                  </span>
                )}
                selectedKeys={editingCategoryId != null ? [editingCategoryId] : []}
                expandedKeys={categoryExpandedKeys}
                onExpand={onExpand}
                onSelect={(keys) => {
                  // 取消选中不重置表单，避免误触丢失正在编辑的内容
                  if (keys.length === 0) return;
                  onEdit(Number(keys[0]));
                }}
                blockNode
              />
            )}
            <Divider style={{ margin: '12px 0 8px' }} />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('category.systemView')}</Typography.Text>
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {editingCategoryId != null
                ? t('category.current', { path: categories.find((item) => item.id === editingCategoryId)?.path ?? '' })
                : t('category.add')}
            </Typography.Text>
            <Form name="skillCategory" form={form} layout="vertical" style={{ marginTop: 8 }}>
              <Form.Item
                name="name"
                label={t('category.name')}
                rules={[
                  { required: true, message: t('validation.categoryName') },
                  { max: 50, message: t('validation.categoryNameMax') },
                ]}
              >
                <Input maxLength={50} placeholder={t('placeholder.categoryName')} showCount />
              </Form.Item>
              <Form.Item
                name="parentId"
                label={t('category.parent')}
                initialValue={UNCATEGORIZED}
                extra={t('category.parentHint')}
              >
                <TreeSelect
                  treeData={parentCategoryOptions}
                  treeDefaultExpandAll
                  treeLine
                  showSearch
                  treeNodeFilterProp="path"
                  treeNodeLabelProp="path"
                />
              </Form.Item>
              <Form.Item
                name="description"
                label={t('category.description')}
                extra={t('category.descriptionHint')}
              >
                <Input.TextArea rows={3} maxLength={1000} placeholder={t('placeholder.categoryDescription')} />
              </Form.Item>
              <Form.Item style={{ marginBottom: 0 }}>
                <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center',
                  borderTop: '1px solid var(--cf-border)', paddingTop: 16 }}>
                  <Space>
                    <Button onClick={onClose}>{t('cancel')}</Button>
                    <Button
                      type="primary"
                      loading={saving}
                      onClick={onSubmit}
                    >
                      {t('category.save')}</Button>
                  </Space>
                </div>
              </Form.Item>
            </Form>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('category.editHint')}</Typography.Text>
          </div>
        </div>
      </Modal>
  );
}
