import { theme, type ThemeConfig } from 'antd';

export type AppearanceMode = 'dark' | 'light' | 'system';
export type ResolvedAppearance = Exclude<AppearanceMode, 'system'>;
export const APPEARANCE_KEY = 'ainative.appearance';
export const DEFAULT_APPEARANCE = 'light' as const;
export function normalizeAppearance(value: unknown): AppearanceMode {
  return value === 'dark' || value === 'light' || value === 'system' ? value : DEFAULT_APPEARANCE;
}
export function resolveAppearance(mode: AppearanceMode, systemDark: boolean): ResolvedAppearance {
  return mode === 'system' ? (systemDark ? 'dark' : 'light') : mode;
}
function rgb(hex: string) { return [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16)); }
function luminance(hex: string) {
  const c = rgb(hex).map(v => { const n = v / 255; return n <= .04045 ? n / 12.92 : ((n + .055) / 1.055) ** 2.4; });
  return c[0] * .2126 + c[1] * .7152 + c[2] * .0722;
}
export function contrastRatio(a: string, b: string) {
  const x = luminance(a), y = luminance(b);
  return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
}
function mix(hex: string, target: string, amount: number) {
  const end = rgb(target);
  return '#' + rgb(hex).map((v, i) => Math.round(v * (1 - amount) + end[i] * amount).toString(16).padStart(2, '0')).join('');
}
export function getAppearanceVariables(mode: ResolvedAppearance, color: string): Record<string, string> {
  const dark = mode === 'dark';
  const accent = /^#[\da-f]{6}$/i.test(color) ? color.toLowerCase() : '#2383e2';
  const panel = dark ? '#1b1d20' : '#ffffff';
  // Preserve the chosen brand color in both modes; adapt foreground ink, not the fill.
  const darkInk = contrastRatio(accent, '#0e0f11') >= 4.5 ? '#0e0f11' : '#000000';
  const ink = contrastRatio(accent, '#ffffff') >= contrastRatio(accent, darkInk) ? '#ffffff' : darkInk;
  const away = ink === '#ffffff' ? '#000000' : '#ffffff';
  const primary = accent;
  const accentText = accent;
  // aiops-platform 色板(ops.css):dark [data-theme=dark] / light [data-theme=light],逐值对齐
  return {
    '--cf-accent': accent, '--cf-accent-rgb': rgb(accent).join(','), '--cf-accent-text': accentText,
    '--cf-primary': primary, '--cf-primary-hover': mix(primary, away, .07), '--cf-primary-active': mix(primary, away, .14), '--cf-primary-ink': ink,
    '--cf-bg': dark ? '#0e0f11' : '#f2f2f7',
    '--cf-panel': panel, // dark #1b1d20(=rgba(28,29,32,.72) 合成值,保证对比度可计算)
    '--cf-sidebar': dark ? '#141619' : '#ffffff',
    '--cf-raised': dark ? '#1e1f23' : '#f3f6fa', '--cf-field': dark ? '#16171a' : '#ffffff',
    '--cf-border': dark ? 'rgba(255,255,255,.08)' : 'rgba(0,0,0,.06)',
    '--cf-text': dark ? '#f5f5f7' : '#1d1d1f', '--cf-muted': dark ? '#9a9aa2' : '#6e6e73',
    '--cf-button': dark ? '#1e1f23' : '#ffffff', '--cf-button-hover': dark ? '#26272c' : '#f3f6fa', '--cf-button-active': dark ? '#303136' : '#eceff4',
    // info 跟随主题 accent(信息提示与品牌同色系);warning 回归暖橙语义——
    // 此前被定义成蓝色,绿色主题下效能分析成本卡/备份提示整片蓝,与主题不搭。
    '--cf-info': 'var(--cf-accent)', '--cf-success': dark ? '#3FB950' : '#0F7B0F',
    '--cf-error': dark ? '#F85149' : '#D3383C', '--cf-warning': dark ? '#f59e0b' : '#b45309',
    '--cf-glass': dark ? 'rgba(20,22,26,.95)' : 'rgba(255,255,255,.96)',
  };
}
export function createAppearanceTheme(mode: ResolvedAppearance, accent: string): ThemeConfig {
  const v = getAppearanceVariables(mode, accent);
  return {
    algorithm: (seed, map) => ({
      ...(mode === 'dark' ? theme.darkAlgorithm : theme.defaultAlgorithm)({ ...seed, colorPrimary: accent, colorLink: accent }, map),
      colorPrimary: v['--cf-accent-text'], colorPrimaryHover: v['--cf-accent-text'], colorPrimaryActive: v['--cf-accent-text'],
      colorLink: v['--cf-accent-text'], colorLinkHover: v['--cf-accent-text'], colorLinkActive: v['--cf-accent-text'],
    }),
    token: {
      // Keep popup lifecycle independent of OS motion settings and runtime toggles.
      motion: false,
      colorPrimary: v['--cf-accent-text'], colorLink: v['--cf-accent-text'], colorLinkHover: v['--cf-accent-text'], colorLinkActive: v['--cf-accent-text'], colorBgBase: v['--cf-bg'], colorBgLayout: v['--cf-bg'],
      colorBgContainer: v['--cf-panel'], colorBgElevated: v['--cf-panel'], colorText: v['--cf-text'], colorTextSecondary: v['--cf-muted'],
      colorBorder: v['--cf-border'], colorBorderSecondary: v['--cf-border'], colorFillAlter: v['--cf-raised'],
      colorError: v['--cf-error'], colorSuccess: v['--cf-success'],
      // info 跟随主题 accent:Alert info 等信息件不再固定蓝,与主题色统一
      colorInfo: v['--cf-accent-text'],
      // warning 回归暖橙:底/边框派生色同步换暖,不再残留旧的蓝底蓝边
      colorWarning: v['--cf-warning'],
      colorWarningBg: mode === 'dark' ? 'rgba(245,158,11,.10)' : '#fdf3e2',
      colorWarningBorder: mode === 'dark' ? 'rgba(245,158,11,.32)' : '#f0d9b5',
      borderRadius: 8, borderRadiusLG: 12,
      // 单行控件统一 34px:与 PageShell/.cfds-form 设计系统同源。
      // 38px 会让表单输入框比工具栏筛选件(34px)高一截,同一页大小不一。
      controlHeight: 34, fontSize: 14,
      fontFamily: '-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif',
    },
    components: {
      Input: { colorBgContainer: v['--cf-field'], hoverBg: v['--cf-field'], activeBg: v['--cf-field'] },
      Segmented: { trackBg: v['--cf-field'], itemSelectedBg: `rgba(${v['--cf-accent-rgb']},.13)`, itemSelectedColor: v['--cf-accent-text'], itemHoverBg: `rgba(${v['--cf-accent-rgb']},.07)`, itemHoverColor: v['--cf-text'] },
      Button: { primaryColor: v['--cf-primary-ink'], colorPrimary: v['--cf-primary'], colorPrimaryHover: v['--cf-primary-hover'], colorPrimaryActive: v['--cf-primary-active'], primaryShadow: 'none', dangerShadow: 'none', defaultShadow: 'none', defaultBg: v['--cf-button'], defaultHoverBg: v['--cf-button-hover'], defaultActiveBg: v['--cf-button-active'], defaultColor: v['--cf-text'], defaultHoverColor: v['--cf-text'], defaultActiveColor: v['--cf-text'], defaultBorderColor: v['--cf-border'], defaultHoverBorderColor: v['--cf-muted'] },
      Layout: { headerBg: v['--cf-sidebar'], siderBg: v['--cf-sidebar'], bodyBg: v['--cf-bg'] },
      Table: { headerBg: v['--cf-raised'], headerColor: v['--cf-muted'], rowHoverBg: v['--cf-button'], cellPaddingBlock: 14, cellPaddingInline: 14 },
      Menu: { itemBg: 'transparent', subMenuItemBg: 'transparent', itemSelectedBg: `rgba(${v['--cf-accent-rgb']},.13)`, itemSelectedColor: v['--cf-accent-text'], itemHoverBg: `rgba(${v['--cf-accent-rgb']},${mode === 'dark' ? '.09' : '.065'})`, itemHeight: 35, itemBorderRadius: 7, groupTitleFontSize: 13, groupTitleColor: v['--cf-accent-text'] },
      Card: { headerFontSize: 18, headerFontSizeSM: 15 },
      Checkbox: { colorPrimary: v['--cf-primary'], colorPrimaryHover: v['--cf-primary-hover'], colorWhite: v['--cf-primary-ink'] },
      Switch: { colorPrimary: v['--cf-primary'], colorPrimaryHover: v['--cf-primary-hover'], colorTextLightSolid: v['--cf-primary-ink'] },
      Radio: { buttonSolidCheckedBg: v['--cf-primary'], buttonSolidCheckedHoverBg: v['--cf-primary-hover'], buttonSolidCheckedActiveBg: v['--cf-primary-active'], buttonSolidCheckedColor: v['--cf-primary-ink'] },
      Select: { selectorBg: v['--cf-field'], optionSelectedBg: `rgba(${v['--cf-accent-rgb']},.14)` },
    },
  };
}
