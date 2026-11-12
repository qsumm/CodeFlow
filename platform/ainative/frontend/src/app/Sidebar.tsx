import { i18n } from '@/shared/i18n';
import {
  FileTextOutlined,
  TeamOutlined,
  ThunderboltOutlined,
  BranchesOutlined,
  BookOutlined,
  BulbOutlined,
  ApartmentOutlined,
  NodeIndexOutlined,
  SettingOutlined,
  AuditOutlined,
  LineChartOutlined,
  HistoryOutlined,
  ClockCircleOutlined,
  ApiOutlined,
  DashboardOutlined,
  ExperimentOutlined,
  KeyOutlined,
  RestOutlined,
} from '@ant-design/icons';
import { RobotHeadIcon } from '@/shared/ui/RobotHeadIcon';

/**
 * 工作台导航配置(设计契约 v5):左树是唯一模块导航,按动线分组;
 * 树底固定「文档中心 / 设置」。路由 key 即 URL,全部页面保持可达。
 */
export interface NavItem {
  key: string;
  label: string;
  icon: React.ReactNode;
  aliases?: string[];
  /** 入口不在树中渲染，但仍保留路由选中态与标题解析 */
  hidden?: boolean;
}

export interface NavSection {
  key: string;
  label: string;
  items: NavItem[];
}

export const NAV_SECTIONS: NavSection[] = [
  {
    key: 'delivery',
    get label() { return i18n.t('shell:nav.delivery'); },
    items: [
      { key: '/workitems', get label() { return i18n.t('shell:nav.workitems'); }, icon: <FileTextOutlined aria-hidden="true" /> },
      { key: '/sdlcs', get label() { return i18n.t('shell:nav.sdlc'); }, icon: <ApartmentOutlined aria-hidden="true" /> },
      { key: '/overview', get label() { return i18n.t('shell:nav.overview'); }, icon: <DashboardOutlined aria-hidden="true" /> },
      { key: '/executions', get label() { return i18n.t('shell:nav.executions'); }, icon: <HistoryOutlined aria-hidden="true" /> },
      { key: '/workitems/recycled', get label() { return i18n.t('shell:nav.recycled'); }, icon: <RestOutlined aria-hidden="true" /> },
    ],
  },
  {
    key: 'analysis',
    get label() { return i18n.t('shell:nav.analysis'); },
    items: [
      { key: '/insights', get label() { return i18n.t('shell:nav.insights'); }, icon: <LineChartOutlined aria-hidden="true" /> },
      { key: '/audit-logs', get label() { return i18n.t('shell:nav.audit'); }, icon: <AuditOutlined aria-hidden="true" /> },
      { key: '/evolution', get label() { return i18n.t('shell:nav.evolution'); }, icon: <ExperimentOutlined aria-hidden="true" />, hidden: true },
    ],
  },
  {
    key: 'automation',
    get label() { return i18n.t('shell:nav.automation'); },
    items: [
      { key: '/scheduled-tasks', get label() { return i18n.t('shell:nav.scheduledTasks'); }, icon: <ClockCircleOutlined aria-hidden="true" /> },
      { key: '/executors', get label() { return i18n.t('shell:nav.executors'); }, icon: <ThunderboltOutlined aria-hidden="true" /> },
      { key: '/agents', get label() { return i18n.t('shell:nav.agents'); }, icon: <RobotHeadIcon aria-hidden="true" /> },
    ],
  },
  {
    key: 'assets',
    get label() { return i18n.t('shell:nav.assets'); },
    items: [
      { key: '/repos', get label() { return i18n.t('shell:nav.repos'); }, icon: <BranchesOutlined aria-hidden="true" /> },
      { key: '/memories', get label() { return i18n.t('shell:nav.memories'); }, icon: <BookOutlined aria-hidden="true" /> },
      { key: '/skills', get label() { return i18n.t('shell:nav.skills'); }, icon: <BulbOutlined aria-hidden="true" /> },
      { key: '/status-templates', get label() { return i18n.t('shell:nav.statusTemplates'); }, icon: <NodeIndexOutlined aria-hidden="true" /> },
    ],
  },
  {
    key: 'project',
    get label() { return i18n.t('shell:nav.projectGroup'); },
    items: [
      { key: '/integrations', get label() { return i18n.t('shell:nav.integrations'); }, icon: <ApiOutlined aria-hidden="true" /> },
      { key: '/settings/members', get label() { return i18n.t('shell:nav.members'); }, icon: <TeamOutlined aria-hidden="true" />, aliases: ['/settings/members-roles', '/settings/roles'] },
      { key: '/settings/backups', get label() { return i18n.t('shell:nav.backups'); }, icon: <HistoryOutlined aria-hidden="true" /> },
      { key: '/settings/environment-variables', get label() { return i18n.t('shell:nav.environment'); }, icon: <KeyOutlined aria-hidden="true" /> },
      // 入口移至树底「设置」,路由元数据保留供选中态/标题解析
      { key: '/settings', get label() { return i18n.t('shell:nav.settings'); }, icon: <SettingOutlined aria-hidden="true" />, hidden: true },
    ],
  },
];

/** 兼容旧引用(路由标题解析等) */
export const NAV_GROUPS: NavSection[] = NAV_SECTIONS;

/** 树底固定入口 */
export const NAV_DOCS = { key: '/help', get label() { return i18n.t('shell:nav.docs'); } };
export const NAV_SETTINGS = {
  key: '/settings',
  get label() { return i18n.t('shell:nav.settings'); },
  icon: <SettingOutlined aria-hidden="true" />,
};
export const NAV_ABOUT = { key: '/about', get label() { return i18n.t('shell:nav.about'); } };

export function navItemMatchesPath(item: NavItem, pathname: string) {
  return [item.key, ...(item.aliases ?? [])].some((key) => pathname.startsWith(key));
}

export function resolveSelectedNavKey(pathname: string) {
  const allItems = NAV_SECTIONS.flatMap((section) => section.items);
  return [...allItems]
    .filter((item) => navItemMatchesPath(item, pathname))
    .sort((a, b) => b.key.length - a.key.length)[0]?.key || '';
}

/** 供 ⌘K 命令面板枚举全部跳转目标 */
export function allNavItems(): NavItem[] {
  return NAV_SECTIONS.flatMap((section) => section.items.filter((item) => !item.hidden));
}
