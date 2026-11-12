import { useNavigate, useLocation } from 'react-router-dom';
import { Tooltip } from 'antd';
import { useTranslation } from 'react-i18next';
import { CompassOutlined } from '@ant-design/icons';
import { NAV_SECTIONS, NAV_DOCS, NAV_SETTINGS, NAV_ABOUT, navItemMatchesPath, type NavItem } from '@/app/Sidebar';
import { useAuthStore } from '@/shared/auth/store';
import { useScheduledTaskCapability } from '@/features/scheduledTask/hooks';
import { isScheduledTaskCapabilityQueryReady } from '@/features/scheduledTask/ScheduledTaskCapabilityGate';
import './workbench.css';

const SCHEDULED_TASKS_KEY = '/scheduled-tasks';

/** 敲击动效与 CSS 的 wb-menu-knock 同一套曲线。 */
const KNOCK_KEYFRAMES: Keyframe[] = [
  { transform: 'translateY(0) rotate(0deg)' },
  { transform: 'translateY(-3px) rotate(-14deg)', offset: 0.28 },
  { transform: 'translateY(1px) rotate(7deg)', offset: 0.52 },
  { transform: 'translateY(0) rotate(-3deg)', offset: 0.72 },
  { transform: 'translateY(0) rotate(0deg)' },
];

/**
 * 树底动作项(文档中心/设置/关于平台)没有 .on 选中态,CSS 敲击罩不到,
 * 点击时用 Web Animations 补一次同样的敲击;尊重系统减少动效偏好。
 * jsdom 未实现 Element.animate,必须先判存在再调用。
 */
function knockIcon(event: { currentTarget: EventTarget & HTMLElement }) {
  if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;
  const icon = event.currentTarget.querySelector('svg, .anticon');
  if (icon && typeof icon.animate === 'function') {
    icon.animate(KNOCK_KEYFRAMES, { duration: 450, easing: 'cubic-bezier(.34, 1.3, .64, 1)' });
  }
}

interface WorkbenchTreeProps {
  badges?: Record<string, number>;
}

/** 左树:唯一模块导航(分组按交付动线),树底固定文档中心/设置/关于 */
export function WorkbenchTree({ badges, onOpenSettings }: WorkbenchTreeProps & { onOpenSettings?: () => void }) {
  const navigate = useNavigate();
  const location = useLocation();
  const { t } = useTranslation('common');
  const currentWorkspace = useAuthStore((s) => s.currentWorkspace);
  const scheduledTaskCapability = useScheduledTaskCapability();
  // V037 门控沿用:能力未就绪(请求中/不可用/失败)时不出「定时任务」入口
  const scheduledTaskReady = isScheduledTaskCapabilityQueryReady(scheduledTaskCapability)
    && scheduledTaskCapability.data?.available === true;

  const renderItem = (item: NavItem) => {
    const active = navItemMatchesPath(item, location.pathname);
    const count = badges?.[item.key] ?? 0;
    return (
      <button
        key={item.key}
        type="button"
        className={active ? 'wb-tree__it on' : 'wb-tree__it'}
        aria-current={active ? 'page' : undefined}
        onClick={() => navigate(item.key)}
        data-nav={item.key}
      >
        {item.icon}
        <span>{item.label}</span>
        {count > 0 && <span className="wb-tree__n" aria-label={String(count)}>{count}</span>}
      </button>
    );
  };

  return (
    <nav className="wb-tree" aria-label={t('navigation.tree')}>
      <div className="wb-tree__proj">
        <b>{currentWorkspace?.name || t('workspace.unselected')}</b>
        {currentWorkspace && <span>{t('navigation.projectIdPrefix')}{currentWorkspace.id}</span>}
      </div>
      {NAV_SECTIONS.map((section) => (
        <div key={section.key}>
          <div className="wb-tree__sec">{section.label}</div>
          {section.items
            .filter((item) => !item.hidden)
            .filter((item) => item.key !== SCHEDULED_TASKS_KEY || scheduledTaskReady)
            .map(renderItem)}
        </div>
      ))}
      <div className="wb-tree__bottom">
        <Tooltip title={t('navigation.docsHint')} placement="right">
          <button type="button" className="wb-tree__it" data-nav="/help" onClick={(e) => { knockIcon(e); window.open('/help', '_blank', 'noopener'); }}>
            <BookGlyph />
            <span>{NAV_DOCS.label}</span>
            <kbd className="wb-tree__kbd">{t('navigation.kbdDocs')}</kbd>
          </button>
        </Tooltip>
        <button type="button" className="wb-tree__it" data-nav="/settings" onClick={(e) => { knockIcon(e); (onOpenSettings ? onOpenSettings() : navigate(NAV_SETTINGS.key)); }}>
          {NAV_SETTINGS.icon}
          <span>{NAV_SETTINGS.label}</span>
          <kbd className="wb-tree__kbd">{t('navigation.kbdSettings')}</kbd>
        </button>
        <button type="button" className="wb-tree__it" data-nav="/about" onClick={(e) => { knockIcon(e); navigate(NAV_ABOUT.key); }}>
          <CompassOutlined aria-hidden="true" />
          <span>{NAV_ABOUT.label}</span>
        </button>
      </div>
    </nav>
  );
}

function BookGlyph() {
  // 文档中心图标:与树内 antd 图标同尺寸的线性书形
  return (
    <svg viewBox="0 0 1024 1024" width="13" height="13" aria-hidden="true" focusable="false"
      style={{ color: 'var(--cf-muted)', flexShrink: 0 }}>
      <path d="M832 64H192a32 32 0 0 0-32 32v832a32 32 0 0 0 32 32h640a32 32 0 0 0 32-32V96a32 32 0 0 0-32-32z m-260 72h96v544l-46.3-34.5a32 32 0 0 0-38.4 0.1L536 680V136z m220 752H232V136h208v556.3c0 25.9 28.6 41.6 50.5 27.9l73.5-46.1 73.6 46.2c21.9 13.6 50.4-2 50.4-27.9V136h104v752z" fill="currentColor" />
    </svg>
  );
}
