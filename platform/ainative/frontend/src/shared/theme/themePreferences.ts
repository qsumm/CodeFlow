/**
 * 用户级主题偏好:主题强调色 + 自定义背景图(2026-11-10)。
 *
 * 与明暗外观(APPEARANCE_KEY)同层,存 localStorage:
 * - accent 为空 = 跟随平台品牌色(branding 接口),不为空则覆盖;
 * - 背景存 { dataUrl, tint },dataUrl 已在前端压缩,超限拒绝;tint 为遮罩浓度 0-90。
 * 参考实现:TestStar useTheme 的「主色 + 色阶」思路,落在我们的 --cf-* 变量体系上。
 */

export const ACCENT_KEY = 'ainative.accent';
export const BACKGROUND_KEY = 'ainative.background';
export const DEFAULT_ACCENT = '#2383e2';

/** 预设色板:契合墨色工具风,避免默认蓝的"AI 味"(首屏默认仍走平台品牌色)。 */
export const ACCENT_PRESETS = [
  { value: '#2383e2', key: 'presetStream' },
  { value: '#0e9888', key: 'presetJade' },
  { value: '#22a06b', key: 'presetMoss' },
  { value: '#7c5cbf', key: 'presetIris' },
  { value: '#b65fc3', key: 'presetOrchid' },
  { value: '#e2483d', key: 'presetCinnabar' },
  { value: '#e07b39', key: 'presetAmber' },
  { value: '#b8b092', key: 'presetSand' },
  { value: '#5e6ad2', key: 'presetLinear' },
  { value: '#0f766e', key: 'presetPine' },
] as const;

export interface BackgroundPreference {
  /** 压缩后的 dataURL(仅本机浏览器持久化,不随账号漫游)。 */
  dataUrl: string;
  /** 遮罩浓度 0-90:0 是原样透出,越高越接近纯色背景,保证前景可读。 */
  tint: number;
}

const HEX_RE = /^#[\da-f]{6}$/i;
export const isAccent = (value: unknown): value is string =>
  typeof value === 'string' && HEX_RE.test(value);

export function normalizeAccent(value: unknown): string | null {
  return isAccent(value) ? value.toLowerCase() : null;
}

export function readStoredAccent(): string | null {
  try {
    return normalizeAccent(localStorage.getItem(ACCENT_KEY));
  } catch {
    return null;
  }
}

export function writeStoredAccent(value: string | null): void {
  try {
    if (value) localStorage.setItem(ACCENT_KEY, normalizeAccent(value) ?? DEFAULT_ACCENT);
    else localStorage.removeItem(ACCENT_KEY);
  } catch { /* 隐私模式下写不进也不阻断当前页生效 */ }
}

export const MIN_TINT = 0;
export const MAX_TINT = 90;
export function clampTint(value: unknown): number {
  const n = typeof value === 'number' ? Math.round(value) : NaN;
  if (Number.isNaN(n)) return 72;
  return Math.min(MAX_TINT, Math.max(MIN_TINT, n));
}

export function normalizeBackground(value: unknown): BackgroundPreference | null {
  if (!value || typeof value !== 'object') return null;
  const raw = value as { dataUrl?: unknown; tint?: unknown };
  if (typeof raw.dataUrl !== 'string'
    || !raw.dataUrl.startsWith('data:image/')
    || raw.dataUrl.length > 2_600_000) return null;
  return { dataUrl: raw.dataUrl, tint: clampTint(raw.tint) };
}

export function readStoredBackground(): BackgroundPreference | null {
  try {
    return normalizeBackground(JSON.parse(localStorage.getItem(BACKGROUND_KEY) ?? 'null'));
  } catch {
    return null;
  }
}

export function writeStoredBackground(value: BackgroundPreference | null): void {
  try {
    if (value) localStorage.setItem(BACKGROUND_KEY, JSON.stringify(value));
    else localStorage.removeItem(BACKGROUND_KEY);
  } catch { /* 配额满等场景:保持当前页生效,持久化失败可接受 */ }
}

/**
 * 上传文件 → 压缩 dataURL。
 * 最长边压到 1920,JPEG 0.82;PNG 透明图会转成白底 JPEG(背景用途可接受)。
 * 超出持久化安全预算时抛错,由调用方给出可读提示。
 */
export const MAX_BACKGROUND_BYTES = 1_200_000;
export async function fileToBackgroundDataUrl(file: File): Promise<string> {
  if (!file.type.startsWith('image/')) {
    throw new Error('not-an-image');
  }
  const dataUrl = await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error('read-failed'));
    reader.readAsDataURL(file);
  });
  const image = await new Promise<HTMLImageElement>((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('decode-failed'));
    img.src = dataUrl;
  });
  const longest = Math.max(image.naturalWidth, image.naturalHeight);
  const scale = Math.min(1, 1920 / longest);
  const width = Math.max(1, Math.round(image.naturalWidth * scale));
  const height = Math.max(1, Math.round(image.naturalHeight * scale));
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext('2d');
  if (!context) throw new Error('canvas-unavailable');
  context.fillStyle = '#ffffff';
  context.fillRect(0, 0, width, height);
  context.drawImage(image, 0, 0, width, height);
  const compressed = canvas.toDataURL('image/jpeg', 0.82);
  // dataURL 字节数 ≈ (长度 - 头部) * 3 / 4,留出 JSON 引号与 tint 的余量
  const bytes = Math.floor((compressed.length - 22) * 3 / 4);
  if (bytes > MAX_BACKGROUND_BYTES) throw new Error('too-large');
  return compressed;
}
