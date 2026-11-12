import { createContext, useContext, useEffect, useLayoutEffect, useMemo, useState, type ReactNode } from 'react';
import { Button, ColorPicker, ConfigProvider, Dropdown, message, Popover, Slider, Typography, Upload } from 'antd';
import { BgColorsOutlined, CheckOutlined, CloseOutlined, DeleteOutlined, DesktopOutlined, MoonOutlined, PictureOutlined, SunOutlined } from '@ant-design/icons';
import zhCN from 'antd/locale/zh_CN';
import enUS from 'antd/locale/en_US';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import { useTranslation } from 'react-i18next';
import { useLocale } from '@/shared/i18n/LocaleProvider';
import { i18n } from '@/shared/i18n';
import { APPEARANCE_KEY, DEFAULT_APPEARANCE, createAppearanceTheme, getAppearanceVariables, normalizeAppearance, resolveAppearance, type AppearanceMode, type ResolvedAppearance } from './appearance';
import { ACCENT_KEY, BACKGROUND_KEY, DEFAULT_ACCENT, ACCENT_PRESETS, MAX_BACKGROUND_BYTES, MIN_TINT, MAX_TINT, clampTint, fileToBackgroundDataUrl, readStoredAccent, readStoredBackground, writeStoredAccent, writeStoredBackground, type BackgroundPreference } from './themePreferences';
import './crystal.css';
// CodeFlow 控制台视觉规范(发丝边/圆角/阴影/徽章/表头/焦点环)
import './console.css';

const AppearanceContext = createContext<{
  mode: AppearanceMode;
  resolved: ResolvedAppearance;
  setMode: (mode: AppearanceMode) => void;
  /** 生效中的强调色(用户覆盖 ?? 平台品牌色)。 */
  accent: string;
  accentOverride: string | null;
  setAccent: (value: string | null) => void;
  background: BackgroundPreference | null;
  setBackground: (value: BackgroundPreference | null) => void;
}>({ mode: DEFAULT_APPEARANCE, resolved: DEFAULT_APPEARANCE, setMode: () => {}, accent: DEFAULT_ACCENT, accentOverride: null, setAccent: () => {}, background: null, setBackground: () => {} });
export const useAppearance = () => useContext(AppearanceContext);
function readMode() { try { return normalizeAppearance(localStorage.getItem(APPEARANCE_KEY)); } catch { return DEFAULT_APPEARANCE; } }
export function AppearanceProvider({ accent, children }: { accent: string; children: ReactNode }) {
  const { locale } = useLocale();
  const antLocale = locale === 'en-US' ? enUS : zhCN;
  const { t } = useTranslation('common');
  const closeLabel = t('overlay.close');
  const overlay = useMemo(() => ({ closable: { 'aria-label': closeLabel }, closeIcon: <CloseOutlined aria-hidden="true" /> }), [closeLabel]);
  useLayoutEffect(() => { dayjs.locale(locale === 'en-US' ? 'en' : 'zh-cn'); }, [locale]);
  const [mode, updateMode] = useState<AppearanceMode>(readMode);
  // 用户自选主题色覆盖平台品牌色;null = 跟随平台。跨标签由 storage 事件同步。
  const [accentOverride, setAccentOverride] = useState<string | null>(readStoredAccent);
  const [background, setBackground] = useState<BackgroundPreference | null>(readStoredBackground);
  const [systemDark, setSystemDark] = useState(() => window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false);
  const resolved = resolveAppearance(mode, systemDark);
  const effectiveAccent = accentOverride ?? accent;
  const variables = useMemo(() => getAppearanceVariables(resolved, effectiveAccent), [resolved, effectiveAccent]);
  const config = useMemo(() => createAppearanceTheme(resolved, effectiveAccent), [resolved, effectiveAccent]);
  useEffect(() => {
    const media = window.matchMedia?.('(prefers-color-scheme: dark)');
    const syncSystem = () => setSystemDark(media?.matches ?? false);
    const syncStorage = (event: StorageEvent) => {
      if (event.key === APPEARANCE_KEY || event.key === null) updateMode(normalizeAppearance(event.newValue));
      if (event.key === ACCENT_KEY) setAccentOverride(readStoredAccent());
      if (event.key === BACKGROUND_KEY) setBackground(readStoredBackground());
    };
    media?.addEventListener('change', syncSystem);
    window.addEventListener('storage', syncStorage);
    syncSystem();
    return () => { media?.removeEventListener('change', syncSystem); window.removeEventListener('storage', syncStorage); };
  }, []);
  useLayoutEffect(() => {
    document.documentElement.dataset.appearance = resolved;
    Object.entries(variables).forEach(([key, value]) => document.documentElement.style.setProperty(key, value));
  }, [resolved, variables]);
  // 自定义背景:遮罩色跟随明暗模式,浓度由用户调节,保证前景面板可读
  useLayoutEffect(() => {
    const root = document.body;
    if (!background) {
      root.classList.remove('cf-custom-bg');
      root.style.removeProperty('--cf-bg-image');
      root.style.removeProperty('--cf-bg-overlay');
      return;
    }
    root.classList.add('cf-custom-bg');
    const overlayBase = resolved === 'dark' ? '14,15,17' : '255,255,255';
    root.style.setProperty('--cf-bg-image', `url("${background.dataUrl}")`);
    root.style.setProperty('--cf-bg-overlay', `rgba(${overlayBase}, ${background.tint / 100})`);
  }, [background, resolved]);
  useLayoutEffect(() => {
    ConfigProvider.config({ holderRender: holder => <ConfigProvider locale={antLocale} theme={config} modal={overlay} drawer={overlay}>{holder}</ConfigProvider> });
  }, [config, antLocale, overlay]);
  function setMode(next: AppearanceMode) {
    updateMode(next);
    try { localStorage.setItem(APPEARANCE_KEY, next); } catch { /* Browser policy may deny persistence; current-tab switching still works. */ }
  }
  function setAccent(next: string | null) {
    setAccentOverride(next);
    writeStoredAccent(next);
  }
  function setBackgroundPreference(next: BackgroundPreference | null) {
    setBackground(next);
    writeStoredBackground(next);
  }
  return <AppearanceContext.Provider value={{ mode, resolved, setMode, accent: effectiveAccent, accentOverride, setAccent, background, setBackground: setBackgroundPreference }}><ConfigProvider locale={antLocale} theme={config} modal={overlay} drawer={overlay}>{children}</ConfigProvider></AppearanceContext.Provider>;
}
export function AppearanceSwitch() {
  const { mode, resolved, setMode } = useAppearance();
  const { t } = useTranslation('common');
  const options = [
    { key: 'dark', title: t('appearance.dark'), icon: <MoonOutlined aria-hidden="true" /> },
    { key: 'light', title: t('appearance.light'), icon: <SunOutlined aria-hidden="true" /> },
    { key: 'system', title: t('appearance.system'), icon: <DesktopOutlined aria-hidden="true" /> },
  ];
  return <Dropdown trigger={['click']} placement="bottomRight" menu={{ selectable: true, selectedKeys: [mode], onClick: ({ key }) => {
    setMode(normalizeAppearance(key));
    message.info({ key: 'appearance-scope', content: t('appearance.scope') });
  }, items: options.map(({ key, title, icon }) => ({ key, icon, label: <span className="appearance-option">{title}{mode === key && <CheckOutlined aria-hidden="true" />}</span> })) }}>
    <Button className="appearance-switch" aria-label={t('appearance.switch')} title={t('appearance.current', { mode: options.find(item => item.key === mode)?.title })} icon={mode === 'system' ? <DesktopOutlined aria-hidden="true" /> : resolved === 'dark' ? <MoonOutlined aria-hidden="true" /> : <SunOutlined aria-hidden="true" />} />
  </Dropdown>;
}

/** 主题工作台:预设色板 + 自定义强调色 + 自定义背景图(本机持久化)。 */
export function ThemeStudioSwitch() {
  const { accent, accentOverride, setAccent, background, setBackground } = useAppearance();
  const { t } = useTranslation('common');
  const [open, setOpen] = useState(false);
  const [notice, contextHolder] = message.useMessage();
  // 受控的自定义色:面板开着时跟随色票,关闭不改存储
  const [draft, setDraft] = useState<string | null>(null);
  const customDraft = draft ?? accentOverride ?? accent;

  const applyAccent = (value: string | null) => {
    setAccent(value);
    notice.success({ key: 'accent-applied', content: value ? t('appearance.accentApplied') : t('appearance.accentReset') });
  };
  const beforeUpload = async (file: File) => {
    try {
      const dataUrl = await fileToBackgroundDataUrl(file);
      setBackground({ dataUrl, tint: background?.tint ?? 72 });
    } catch (error) {
      const reason = error instanceof Error && error.message === 'too-large'
        ? t('appearance.backgroundTooLarge', { limit: Math.round(MAX_BACKGROUND_BYTES / 1000) })
        : error instanceof Error && error.message === 'not-an-image'
          ? t('appearance.backgroundNotImage')
          : t('appearance.backgroundFailed');
      notice.error({ key: 'bg-error', content: reason, duration: 6 });
    }
    return Upload.LIST_IGNORE;
  };

  return (
    <Popover
      trigger={['click']}
      placement="bottomRight"
      arrow={false}
      open={open}
      onOpenChange={(next) => { setOpen(next); if (next) setDraft(null); }}
      overlayClassName="theme-studio-popover"
      content={(
        <div className="theme-studio" role="group" aria-label={t('appearance.studio')}>
          <div className="theme-studio__sec">{t('appearance.accentSection')}</div>
          <div className="theme-studio__swatches">
            {ACCENT_PRESETS.map(({ value, key }) => {
              // 预设名是运行期拼出来的键,走 i18n.t(命名空间前缀)而不是类型化 t()
              const presetName = i18n.t(`common:appearance.${key}`);
              return (
                <button key={value} type="button"
                  className={`theme-studio__swatch${accentOverride === value ? ' on' : ''}`}
                  style={{ background: value }}
                  title={presetName}
                  aria-label={t('appearance.accentNamed', { color: presetName })}
                  aria-pressed={accentOverride === value}
                  onClick={() => applyAccent(accentOverride === value ? null : value)}>
                  {accentOverride === value && <CheckOutlined aria-hidden="true" className="theme-studio__check" />}
                </button>
              );
            })}
          </div>
          <div className="theme-studio__row">
            <ColorPicker
              size="small"
              value={customDraft}
              onChange={(color) => setDraft(color.toHexString())}
              disabledAlpha
              aria-label={t('appearance.accentCustom')}
            />
            <Typography.Text className="theme-studio__hex">{customDraft.toUpperCase()}</Typography.Text>
            <Button size="small" type="primary" disabled={draft === null || draft.toUpperCase() === accentOverride?.toUpperCase()}
              onClick={() => { if (draft) applyAccent(draft); setDraft(null); }}>
              {t('appearance.accentApply')}
            </Button>
            {accentOverride && (
              <Button size="small" onClick={() => applyAccent(null)}>{t('appearance.accentFollowPlatform')}</Button>
            )}
          </div>
          <div className="theme-studio__sec">{t('appearance.backgroundSection')}</div>
          {background ? (
            <div className="theme-studio__preview" aria-label={t('appearance.backgroundCurrent')}>
              <img src={background.dataUrl} alt="" />
            </div>
          ) : (
            <Typography.Paragraph type="secondary" className="theme-studio__hint">{t('appearance.backgroundHint')}</Typography.Paragraph>
          )}
          <div className="theme-studio__row">
            <Upload accept="image/*" showUploadList={false} beforeUpload={beforeUpload}>
              <Button size="small" icon={<PictureOutlined aria-hidden="true" />}>{t('appearance.backgroundUpload')}</Button>
            </Upload>
            {background && (
              <Button size="small" danger icon={<DeleteOutlined aria-hidden="true" />}
                onClick={() => { setBackground(null); notice.success({ key: 'bg-cleared', content: t('appearance.backgroundCleared') }); }}>
                {t('appearance.backgroundClear')}
              </Button>
            )}
          </div>
          {background && (
            <div className="theme-studio__tint">
              <Typography.Text type="secondary" className="theme-studio__tintLabel">{t('appearance.backgroundTint')}</Typography.Text>
              <Slider min={MIN_TINT} max={MAX_TINT} value={background.tint}
                onChange={(value) => setBackground({ ...background, tint: clampTint(value) })}
                tooltip={{ formatter: (v) => t('appearance.tintPercent', { value: v ?? 0 }) }}
                aria-label={t('appearance.backgroundTint')} />
              <Typography.Text type="secondary" className="theme-studio__tintValue">{t('appearance.tintPercent', { value: background.tint })}</Typography.Text>
            </div>
          )}
          <Typography.Paragraph type="secondary" className="theme-studio__note">{t('appearance.studioNote')}</Typography.Paragraph>
        </div>
      )}>
      <Button
        className="appearance-switch"
        aria-label={t('appearance.studio')}
        title={t('appearance.studio')}
        icon={<BgColorsOutlined aria-hidden="true" />}
        onClick={() => setOpen((v) => !v)}
      />
      {contextHolder}
    </Popover>
  );
}
