import { useState } from 'react';
import { Modal } from 'antd';
import { useTranslation } from 'react-i18next';
import { AiSettingsTab, NotifySettingsTab, SystemSettingsTab } from './SettingsPage';
import './SettingsModal.css';

const SECTION_KEYS = { ai: 'AI', notify: 'NOTIFY', system: 'SYSTEM' } as const;
type SettingsSection = typeof SECTION_KEYS[keyof typeof SECTION_KEYS];

/**
 * 设置模态(设计契约 v5):左类目 + 右内容,内容与 /settings 路由页共用同一套 tab 组件,
 * 因此不存在两套实现。关闭按钮/主题由 AppearanceProvider 统一注入。
 */
export function SettingsModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useTranslation('settings');
  const [section, setSection] = useState<SettingsSection>(SECTION_KEYS.ai);

  const sections: Array<{ key: SettingsSection; label: string }> = [
    { key: SECTION_KEYS.ai, label: t('aiTab') },
    { key: SECTION_KEYS.notify, label: t('notifyTab') },
    { key: SECTION_KEYS.system, label: t('systemTab') },
  ];

  return (
    <Modal
      open={open}
      onCancel={onClose}
      footer={null}
      width={760}
      destroyOnHidden
      title={t('settings')}
      className="settings-modal"
    >
      <div className="settings-modal__body">
        <nav className="settings-modal__nav" aria-label={t('settings')}>
          {sections.map((item) => (
            <button
              key={item.key}
              type="button"
              className={item.key === section ? 'settings-modal__item on' : 'settings-modal__item'}
              aria-current={item.key === section ? 'page' : undefined}
              onClick={() => setSection(item.key)}
            >
              {item.label}
            </button>
          ))}
        </nav>
        <div className="settings-modal__content">
          {section === SECTION_KEYS.ai && <AiSettingsTab />}
          {section === SECTION_KEYS.notify && <NotifySettingsTab />}
          {section === SECTION_KEYS.system && <SystemSettingsTab />}
        </div>
      </div>
    </Modal>
  );
}
