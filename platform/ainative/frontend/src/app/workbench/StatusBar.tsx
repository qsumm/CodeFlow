import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import { LanguageSwitch } from '@/shared/i18n/LanguageSwitch';
import { AppearanceSwitch } from '@/shared/theme/AppearanceProvider';
import { useAuthStore } from '@/shared/auth/store';
import { BRANDING_QUERY_KEY, DEFAULT_BRANDING, getPublicBranding, type PlatformBranding } from '@/features/platform/brandingApi';

/** branding 未配置时的版本哨兵(与 DEFAULT_BRANDING 一致) */
const UNSET_VERSION = 'x.x.x';

interface StatusBarProps {
  onOpenPalette: () => void;
}

/** 底部状态栏:⌘K · 项目 · 运行环境版本 | 语言/外观切换(IDE 惯例位置) */
export function StatusBar({ onOpenPalette }: StatusBarProps) {
  const { t } = useTranslation('common');
  const currentWorkspace = useAuthStore((s) => s.currentWorkspace);

  const { data: branding } = useQuery<PlatformBranding>({
    queryKey: BRANDING_QUERY_KEY,
    queryFn: getPublicBranding,
    staleTime: Infinity,
  });
  const version = branding?.deploymentVersion && branding.deploymentVersion !== UNSET_VERSION
    ? branding.deploymentVersion
    : DEFAULT_BRANDING.deploymentVersion;

  return (
    <footer className="wb-status">
      <button type="button" className="wb-status__cmd" onClick={onOpenPalette}>
        <span className="lit lit--acc" aria-hidden="true" />
        {t('navigation.commandPalette')}
      </button>
      {currentWorkspace && <span>{t('navigation.statusProj')}{currentWorkspace.name}</span>}
      <span className="sp" />
      <LanguageSwitch />
      <AppearanceSwitch />
      {version && version !== UNSET_VERSION && <span>{t('navigation.versionPrefix')}{version}</span>}
    </footer>
  );
}
