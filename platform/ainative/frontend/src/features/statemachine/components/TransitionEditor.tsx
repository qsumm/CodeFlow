import { useTranslation } from 'react-i18next';
import { useState } from 'react';
import { Button, Popconfirm, Alert, Space } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import type { StatusNode, StatusTransition, NodeCategory } from '../types';
import { TransitionFormModal } from './TransitionFormModal';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

// 语义色只用于小圆点,不再用于标签底色/边框
const categoryDot: Record<string, string> = {
  INIT: 'var(--cf-info)', IN_PROGRESS: 'var(--cf-warning)', DONE: 'var(--cf-success)', CANCELED: 'var(--cf-error)',
};

interface Props {
  transitions: StatusTransition[];
  nodes: StatusNode[];
  onCreate: (data: { fromNodeId: number; toNodeId: number; name: string }) => void;
  onUpdate: (tid: number, data: { fromNodeId: number; toNodeId: number; name: string }) => void;
  onDelete: (tid: number) => void;
  createLoading?: boolean;
  updateLoading?: boolean;
}

export function TransitionEditor({ transitions, nodes, onCreate, onUpdate, onDelete, createLoading, updateLoading }: Props) {
  const { t } = useTranslation('statemachine');
  const accessCommand = useAccessCommand();
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<StatusTransition | null>(null);

  const nodeMap = Object.fromEntries(nodes.map((n) => [n.id, n]));

  const openCreate = () => accessCommand('READ_WRITE', t('actions.createTransition'), () => {
    setEditing(null);
    setModalOpen(true);
  });
  const openEdit = (tr: StatusTransition) => accessCommand('READ_WRITE', t('actions.editTransition'), () => {
    setEditing(tr);
    setModalOpen(true);
  });

  const handleSubmit = (values: { fromNodeId: number; toNodeId: number; name: string }) => {
    if (editing) {
      onUpdate(editing.id, values);
    } else {
      onCreate(values);
    }
    setModalOpen(false);
  };

  const renderNodeTag = (nodeId: number) => {
    const node = nodeMap[nodeId];
    if (!node) return <span style={{ padding: '2px 8px', background: 'var(--cf-raised)', border: '1px solid var(--cf-border)', borderRadius: 4, fontSize: 11 }}>?</span>;
    const cat = node.category as NodeCategory;
    // 中性胶囊 + 前置语义色点:语义信息保留,但不再整块染色(视觉评审:同屏五色标签过杂)
    return (
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '2px 8px', background: 'var(--cf-field)', border: '1px solid var(--cf-border)', borderRadius: 999, fontSize: 11 }}>
        <span style={{ width: 6, height: 6, borderRadius: '50%', background: categoryDot[cat] || 'var(--cf-muted)', display: 'inline-block' }} />
        {node.code}
      </span>
    );
  };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 8, marginBottom: 12 }}>
        <span style={{ fontWeight: 600, fontSize: 14 }}>{t('transitions.title')}</span>
        <span style={{ marginLeft: 8, fontSize: 12, color: 'var(--cf-muted)' }}>{t('transitions.help')}</span>
        <Button icon={<PlusOutlined aria-hidden="true" />} onClick={openCreate} style={{ marginLeft: 'auto' }}>{t('transitions.add')}</Button>
      </div>
      <div className="cfds-table-wrap">
        <Table<StatusTransition>
          rowKey="id"
          dataSource={transitions}
          pagination={false}
          scroll={{ x: 'max-content' }}
          columns={[
            { title: t('transitions.fromColumn'), dataIndex: 'fromNodeId', render: renderNodeTag },
            { title: t('transitions.toColumn'), dataIndex: 'toNodeId', render: renderNodeTag },
            { title: t('transitions.name'), dataIndex: 'name', align: 'left' },
            { title: t('transitions.actions'), width: 180, render: (_, tr) => (
              <Space>
                <Button type="link" size="small" icon={<EditOutlined aria-hidden="true" />} onClick={() => openEdit(tr)}>{t('common.edit')}</Button>
                <Popconfirm title={t('transitions.confirmDelete')} onConfirm={() => onDelete(tr.id)} okText={t('common.delete')} cancelText={t('common.cancel')}>
                  <Button type="link" size="small" danger icon={<DeleteOutlined aria-hidden="true" />}>{t('common.delete')}</Button>
                </Popconfirm>
              </Space>
            ) },
          ]}
        />
      </div>
      {/* 说明条改中性 info(视觉评审:整块黄底+橙色图标在全页多色里又添一色) */}
      <Alert
        type="info"
        showIcon
        style={{ marginTop: 12 }}
        message={t('transitions.warning')}
      />
      <TransitionFormModal
        open={modalOpen}
        editing={editing}
        nodes={nodes}
        onSubmit={handleSubmit}
        onCancel={() => setModalOpen(false)}
        loading={createLoading || updateLoading}
      />
    </div>
  );
}
