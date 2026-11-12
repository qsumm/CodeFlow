import { useTranslation } from 'react-i18next';
import { ChiefOfStaff } from '@/features/chiefOfStaff/ChiefOfStaff';
import { LanguageSwitch } from '@/shared/i18n/LanguageSwitch';
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { Button, message } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { AppearanceSwitch, ThemeStudioSwitch, useAppearance } from '@/shared/theme/AppearanceProvider';
import { PlatformAgentStatusBanner } from './PlatformAgentStatusBanner';
import { PageHeader } from '@/shared/ui/PageHeader';
import { PlatformBrand } from '@/shared/ui/PlatformBrand';
import { UserMenu } from '@/shared/ui/UserMenu';
import { useAuthStore } from '@/shared/auth/store';
import { refreshCurrentMembership } from '@/shared/auth/refreshCurrentMembership';
import { myWorkspacesQueryKey } from '@/features/auth/api';
import { apiClient } from '@/shared/api/client';
import type { WorkspaceInfo, SwitchWorkspaceResponse } from '@/shared/types/common';
import { ApiError } from '@/shared/types/common';
import { refreshTenantScopedQueries } from '@/features/workitem/queryCache';
import { WorkbenchTree } from './workbench/WorkbenchTree';
import { TopBar } from './workbench/TopBar';
import { StatusBar } from './workbench/StatusBar';
import { CommandPalette, useGlobalShortcuts } from './workbench/CommandPalette';
import { SettingsModal } from '@/features/settings/SettingsModal';
import './workbench/workbench.css';


export function getWorkspaceDeepLinkId(search: string): number | null {
  const value = new URLSearchParams(search).get('workspaceId');
  if (!value || !/^\d+$/.test(value)) {
    return null;
  }
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

export function removeWorkspaceDeepLink(search: string): string {
  const params = new URLSearchParams(search);
  params.delete('workspaceId');
  const nextSearch = params.toString();
  return nextSearch ? `?${nextSearch}` : '';
}

/** 文档中心单顶栏:把平台级操作并入文档中心自己的顶栏(避免双层顶栏)。 */
function HelpActionsPortal({ children }: { children: ReactNode }) {
  const [host, setHost] = useState<HTMLElement | null>(null);
  useEffect(() => {
    setHost(document.getElementById('help-topbar-actions'));
  }, []);
  return host ? createPortal(children, host) : null;
}

export function AppLayout({ helpCenter = false }: { helpCenter?: boolean }) {
  const { t } = useTranslation('common');
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const user = useAuthStore((s) => s.user);
  const currentWorkspace = useAuthStore((s) => s.currentWorkspace);
  const setAccessToken = useAuthStore((s) => s.setAccessToken);
  const setCurrentWorkspace = useAuthStore((s) => s.setCurrentWorkspace);

  useEffect(() => {
    if (helpCenter) return;
    const refresh = () => {
      void refreshCurrentMembership();
    };
    refresh();
    window.addEventListener('focus', refresh);
    return () => window.removeEventListener('focus', refresh);
  }, [helpCenter]);

  const { data: workspaces } = useQuery({
    queryKey: myWorkspacesQueryKey(user?.id ?? null),
    enabled: !helpCenter,
    queryFn: async () => {
      const resp = await apiClient.get<WorkspaceInfo[]>('/api/workspaces/mine');
      return resp.data;
    },
  });
  const [switchingWorkspaceId, setSwitchingWorkspaceId] = useState<number | null>(null);
  const switchingRef = useRef<number | null>(null);

  const handleWorkspaceSwitch = useCallback(async (workspace: WorkspaceInfo) => {
    if (workspace.id === currentWorkspace?.id || switchingRef.current === workspace.id) return;
    switchingRef.current = workspace.id;
    setSwitchingWorkspaceId(workspace.id);
    try {
      const resp = await apiClient.post<SwitchWorkspaceResponse>(`/api/workspaces/${workspace.id}/switch`);
      setAccessToken(resp.data.accessToken);
      setCurrentWorkspace(workspace, resp.data.accessLevel);
      await refreshTenantScopedQueries(queryClient);
      message.success(t('workspace.switched', { name: workspace.name }));
    } catch (e) {
      message.error(e instanceof ApiError ? e.message : t('workspace.switchFailed'));
    } finally {
      switchingRef.current = null;
      setSwitchingWorkspaceId(null);
    }
  }, [currentWorkspace?.id, setAccessToken, setCurrentWorkspace, queryClient, t]);

  useEffect(() => {
    if (helpCenter) return;
    const targetWorkspaceId = getWorkspaceDeepLinkId(location.search);
    if (!targetWorkspaceId) {
      return;
    }
    const cleanPath = `${location.pathname}${removeWorkspaceDeepLink(location.search)}${location.hash}`;
    if (currentWorkspace?.id === targetWorkspaceId) {
      navigate(cleanPath, { replace: true });
      return;
    }
    let cancelled = false;
    async function switchToLinkedWorkspace() {
      try {
        const workspacesResp = await apiClient.get<WorkspaceInfo[]>('/api/workspaces/mine');
        if (cancelled) {
          return;
        }
        const targetWorkspace = workspacesResp.data.find((workspace) => workspace.id === targetWorkspaceId);
        if (!targetWorkspace) {
          message.error(t('workspace.notMember'));
          return;
        }
        const switchResp = await apiClient.post<SwitchWorkspaceResponse>(`/api/workspaces/${targetWorkspaceId}/switch`);
        if (cancelled) {
          return;
        }
        setAccessToken(switchResp.data.accessToken);
        setCurrentWorkspace(targetWorkspace, switchResp.data.accessLevel);
        await refreshTenantScopedQueries(queryClient);
        navigate(cleanPath, { replace: true });
      } catch {
        if (!cancelled) {
          message.error(t('workspace.linkedSwitchFailed'));
        }
      }
    }
    void switchToLinkedWorkspace();
    return () => {
      cancelled = true;
    };
  }, [
    t,
    helpCenter,
    currentWorkspace?.id,
    location.hash,
    location.pathname,
    location.search,
    navigate,
    queryClient,
    setAccessToken,
    setCurrentWorkspace,
  ]);

  const [paletteOpen, setPaletteOpen] = useState(false);
  // 文档中心自带顶栏时不再叠一层平台顶栏;纯静态帮助页保留极简头部
  const [hasDocsTopbar, setHasDocsTopbar] = useState(false);
  useEffect(() => {
    if (!helpCenter) return;
    const detect = () => setHasDocsTopbar(Boolean(document.querySelector('.help-topbar')));
    detect();
    const timer = window.setTimeout(detect, 400);
    return () => window.clearTimeout(timer);
  }, [helpCenter]);
  const [settingsOpen, setSettingsOpen] = useState(false);
  // 自定义背景时 main 要透出壁纸层
  const { background } = useAppearance();
  const openPalette = useCallback(() => setPaletteOpen(true), []);
  const openSettings = useCallback(() => setSettingsOpen(true), []);
  useGlobalShortcuts(openPalette, openSettings);

  if (helpCenter) {
    // 文档中心自带深色顶栏与左树,不再叠一层平台顶栏(视觉评审:双层顶栏是最显眼的杂乱源);
    // 语言/主题/用户入口通过 portal 并入文档中心顶栏右侧,功能不丢。
    // 纯静态帮助页(无文档中心顶栏)保留极简头部:品牌 + 登录入口。
    return (
      <div className="help-layout" style={{ height: '100vh', minWidth: 0 }}>
        {!hasDocsTopbar && (
          <PageHeader actions={user ? <UserMenu /> : <Button href="/login">{t('account.login')}</Button>}>
            <PlatformBrand to="/workitems" />
          </PageHeader>
        )}
        <HelpActionsPortal>
          <LanguageSwitch />
          <AppearanceSwitch />
          <ThemeStudioSwitch />
          {user ? <UserMenu /> : <Button href="/login">{t('account.login')}</Button>}
        </HelpActionsPortal>
        <Outlet />
      </div>
    );
  }

  return (
    <div className="wb-shell">
      <TopBar
        workspaces={workspaces ?? []}
        onOpenPalette={openPalette}
        onSwitchWorkspace={(workspace) => { void handleWorkspaceSwitch(workspace as WorkspaceInfo); }}
        switchingWorkspaceId={switchingWorkspaceId}
      />
      <div className="wb-body">
        <WorkbenchTree onOpenSettings={openSettings} />
        {/* 自定义背景开启时 main 必须透明:内联灰底会整块挡住 body 的壁纸层 */}
        {/* display:flex 纵向:让内容框架(及其中的白卡)能纵向撑满,内容少时下方不再露大片灰底 */}
        <main className="cf-main" style={{ flex: 1, minWidth: 0, minHeight: 0, overflow: 'auto', padding: 12, display: 'flex', flexDirection: 'column', ...(background ? {} : { background: 'var(--cf-bg)' }) }}>
          <PlatformAgentStatusBanner />
          {user && <ChiefOfStaff />}
          <section className="cf-content-frame" aria-label={t('navigation.content')}><Outlet /></section>
        </main>
      </div>
      <StatusBar onOpenPalette={openPalette} />
      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} />
      <SettingsModal open={settingsOpen} onClose={() => setSettingsOpen(false)} />
    </div>
  );
}
