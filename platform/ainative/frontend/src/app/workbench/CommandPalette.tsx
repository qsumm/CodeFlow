import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Input, Modal } from 'antd';
import { useTranslation } from 'react-i18next';
import { allNavItems, NAV_DOCS, NAV_SETTINGS } from '@/app/Sidebar';

interface CommandPaletteProps {
  open: boolean;
  onClose: () => void;
}

/** ⌘K 命令面板:全部导航目标的模糊跳转(方向键+回车) */
export function CommandPalette({ open, onClose }: CommandPaletteProps) {
  const { t } = useTranslation('common');
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [activeIndex, setActiveIndex] = useState(0);
  const listRef = useRef<HTMLDivElement | null>(null);

  const items = useMemo(() => {
    const all = [...allNavItems()];
    const q = query.trim().toLowerCase();
    if (!q) return all;
    return all.filter((item) =>
      item.label.toLowerCase().includes(q) || item.key.toLowerCase().includes(q));
  }, [query]);

  useEffect(() => {
    if (open) {
      setQuery('');
      setActiveIndex(0);
    }
  }, [open]);

  useEffect(() => {
    setActiveIndex((prev) => Math.min(prev, Math.max(0, items.length - 1)));
  }, [items.length]);

  const go = (key: string) => {
    onClose();
    navigate(key);
  };

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActiveIndex((prev) => (items.length ? (prev + 1) % items.length : 0));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActiveIndex((prev) => (items.length ? (prev - 1 + items.length) % items.length : 0));
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const target = items[activeIndex];
      if (target) go(target.key);
    }
  };

  return (
    <Modal
      open={open}
      onCancel={onClose}
      footer={null}
      width={480}
      className="wb-palette-modal"
      title={null}
      aria-label={t('navigation.commandPalette')}
    >
      <Input
        autoFocus
        variant="borderless"
        aria-label={t('navigation.commandPalette')}
        placeholder={t('navigation.commandPaletteSearch')}
        value={query}
        onChange={(event) => setQuery(event.target.value)}
        onKeyDown={onKeyDown}
      />
      <div className="wb-palette-list" ref={listRef} role="listbox" aria-label={t('navigation.commandPalette')}>
        {items.length === 0 && <div className="wb-palette-empty">{t('navigation.paletteEmpty')}</div>}
        {items.map((item, index) => (
          <div
            key={item.key}
            role="option"
            aria-selected={index === activeIndex}
            className={index === activeIndex ? 'wb-palette-item on' : 'wb-palette-item'}
            onMouseEnter={() => setActiveIndex(index)}
            onClick={() => go(item.key)}
          >
            {item.icon}
            <span>{item.label}</span>
            <span className="wb-palette__path">{item.key}</span>
          </div>
        ))}
      </div>
      <div className="wb-palette-empty" style={{ padding: '8px 12px', textAlign: 'left', borderTop: '1px solid var(--cf-border)', marginTop: 6 }}>
        {t('navigation.paletteHint')}
      </div>
    </Modal>
  );
}

/** 全局快捷键:⌘K/Ctrl+K 面板 · F1 文档 · ⌘,/Ctrl+, 设置 */
export function useGlobalShortcuts(onOpenPalette: () => void, onOpenSettings?: () => void) {
  const navigate = useNavigate();
  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      const metaK = (event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k';
      const settings = (event.metaKey || event.ctrlKey) && event.key === ',';
      if (metaK) {
        event.preventDefault();
        onOpenPalette();
      } else if (event.key === 'F1') {
        event.preventDefault();
        window.open('/help', '_blank', 'noopener');
      } else if (settings) {
        event.preventDefault();
        if (onOpenSettings) onOpenSettings();
        else navigate(NAV_SETTINGS.key);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [onOpenPalette, onOpenSettings, navigate]);
}

// NAV_DOCS 供面板扩展位使用(当前仅快捷键直达)
void NAV_DOCS;
