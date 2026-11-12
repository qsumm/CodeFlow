import { useTranslation } from 'react-i18next';
import { ZentaoIntegrationPanel } from './ZentaoIntegrationPanel';
import { ChannelIntegrationPage } from './channels/ChannelIntegrationPage';
import { HubPageShell, resolveHubTab, useHubTabParam } from '@/shared/ui/HubPage';

export const INTEGRATIONS_HUB_TAB_KEYS = ['zentao', 'channels'] as const;
export const DEFAULT_INTEGRATIONS_HUB_TAB = 'zentao';

export function resolveIntegrationsHubTab(tabParam: string | null): string {
  return resolveHubTab(tabParam, INTEGRATIONS_HUB_TAB_KEYS, DEFAULT_INTEGRATIONS_HUB_TAB);
}

const TAB_DESCRIPTIONS: Record<string, 'channelsDescription' | 'zentaoDescription'> = {
  channels: 'channelsDescription',
  zentao: 'zentaoDescription',
};

export function IntegrationsHubPage() {
  const { t } = useTranslation('integration');
  const { activeTab, setTab } = useHubTabParam(INTEGRATIONS_HUB_TAB_KEYS, DEFAULT_INTEGRATIONS_HUB_TAB);

  return (
    <HubPageShell
      title={t('title')}
      description={t(TAB_DESCRIPTIONS[activeTab])}
      activeTab={activeTab}
      onTabChange={setTab}
      items={[
        { key: 'zentao', label: t('zentaoTab'), children: <ZentaoIntegrationPanel /> },
        { key: 'channels', label: t('channelsTab'), children: <ChannelIntegrationPage /> },
      ]}
    />
  );
}
