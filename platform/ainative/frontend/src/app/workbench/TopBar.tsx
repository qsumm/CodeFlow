import { useMemo } from 'react';
import { Dropdown } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { CheckOutlined, LoadingOutlined, SearchOutlined } from '@ant-design/icons';
import { PlatformBrand } from '@/shared/ui/PlatformBrand';
import { NAV_SECTIONS } from '@/app/Sidebar';
import { NotificationBell } from '@/shared/ui/NotificationBell';
import { ThemeStudioSwitch } from '@/shared/theme/AppearanceProvider';
import { UserMenu } from '@/shared/ui/UserMenu';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { useAuthStore } from '@/shared/auth/store';
import './workbench.css';

interface TopBarProps {
  workspaces: Array<{ id: number; name: string }>;
  onOpenPalette: () => void;
  onSwitchWorkspace: (workspace: { id: number; name: string }) => void;
  switchingWorkspaceId: number | null;
}

/** 顶栏:品牌 + mono 路径 + ⌘K + 项目切换 + 通知/用户(菜单已全部移入左树) */
export function TopBar({ workspaces, onOpenPalette, onSwitchWorkspace, switchingWorkspaceId }: TopBarProps) {
  const { t } = useTranslation('common');
  const location = useLocation();
  const navigate = useNavigate();
  const currentWorkspace = useAuthStore((s) => s.currentWorkspace);

  // 面包屑段本地化:能匹配到导航项的路径段用中文名(此前显示 workitems/executors 等原始路径,与中文界面割裂)
  const navLabelByPath = useMemo(() => {
    const map = new Map<string, string>();
    NAV_SECTIONS.forEach((section) => section.items.forEach((item) => map.set(item.key, item.label)));
    map.set('/profile/settings', i18n.t('shell:nav.environment'));
    map.set('/workspaces/branding', i18n.t('shell:nav.environment'));
    return map;
  }, []);
  const pathSegments = useMemo(
    () => location.pathname.split('/').filter(Boolean).map((segment, index, all) => {
      const full = '/' + all.slice(0, index + 1).join('/');
      return navLabelByPath.get(full) ?? segment;
    }),
    [location.pathname, navLabelByPath],
  );

  const projectMenuItems = useMemo(() => [
    ...workspaces.map((workspace) => ({
      key: `workspace-${workspace.id}`,
      label: <EllipsisText tooltip={workspace.name}>{workspace.name}</EllipsisText>,
      icon: workspace.id === currentWorkspace?.id
        ? (workspace.id === switchingWorkspaceId ? <LoadingOutlined /> : <CheckOutlined style={{ color: 'var(--cf-accent-text)' }} />)
        : (workspace.id === switchingWorkspaceId ? <LoadingOutlined /> : undefined),
      disabled: workspace.id === switchingWorkspaceId,
      onClick: () => onSwitchWorkspace(workspace),
    })),
    { type: 'divider' as const },
    { key: 'manage-projects', label: t('workspace.manage'), onClick: () => navigate('/workspaces') },
  ], [workspaces, currentWorkspace?.id, switchingWorkspaceId, onSwitchWorkspace, t, navigate]);

  return (
    <header className="wb-top">
      <span className="cf-brand-link"><PlatformBrand to="/workitems" /></span>
      <span className="wb-path">
        {pathSegments.map((segment, index) => (
          <span key={`${segment}-${index}`}>
            <span className="wb-path-sep" aria-hidden="true" />{segment}
          </span>
        ))}
      </span>
      <button type="button" className="wb-cmd" aria-label={t('navigation.commandPalette')} onClick={onOpenPalette}>
        <SearchOutlined aria-hidden="true" style={{ fontSize: 12 }} />
        {t('navigation.commandPaletteSearch')}
        <kbd>{t('navigation.kbdPalette')}</kbd>
      </button>
      <span className="spacer" />
      <Dropdown menu={{ items: projectMenuItems }} trigger={['click']} placement="bottomRight"
        overlayClassName="wb-project-menu" overlayStyle={{ width: 200, minWidth: 200, maxWidth: 200 }}>
        <button type="button" className="wb-prj" aria-label={(currentWorkspace?.name || t('workspace.unselected')) as string} title={currentWorkspace?.name}>
          <span className="wb-prj-mark" aria-hidden="true" />
          <span className="wb-prj-name">{currentWorkspace?.name || t('workspace.unselected')}</span>
        </button>
      </Dropdown>
      <NotificationBell fetchUnread />
      <ThemeStudioSwitch />
      <UserMenu />
    </header>
  );
}
