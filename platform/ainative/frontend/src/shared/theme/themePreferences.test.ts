import { afterEach, describe, expect, it } from 'vitest';
import {
  ACCENT_KEY, ACCENT_PRESETS, BACKGROUND_KEY, DEFAULT_ACCENT, MAX_BACKGROUND_BYTES,
  MAX_TINT, MIN_TINT, clampTint, fileToBackgroundDataUrl, normalizeAccent, normalizeBackground,
  readStoredAccent, readStoredBackground, writeStoredAccent, writeStoredBackground,
} from './themePreferences';

afterEach(() => { localStorage.clear(); });

describe('themePreferences.accent', () => {
  it('accepts 6-digit hex and lowercases it; rejects everything else', () => {
    expect(normalizeAccent('#22A06B')).toBe('#22a06b');
    expect(normalizeAccent('22a06b')).toBeNull();
    expect(normalizeAccent('#22a06')).toBeNull();
    expect(normalizeAccent('#22a06bb')).toBeNull();
    expect(normalizeAccent(null)).toBeNull();
    expect(normalizeAccent(undefined)).toBeNull();
    expect(normalizeAccent(42)).toBeNull();
  });

  it('persists accent and clears on null; read tolerates storage failures', () => {
    writeStoredAccent('#7C5CBF');
    expect(localStorage.getItem(ACCENT_KEY)).toBe('#7c5cbf');
    expect(readStoredAccent()).toBe('#7c5cbf');
    writeStoredAccent(null);
    expect(localStorage.getItem(ACCENT_KEY)).toBeNull();
    expect(readStoredAccent()).toBeNull();
    localStorage.setItem(ACCENT_KEY, 'not-a-color');
    expect(readStoredAccent()).toBeNull();
  });

  it('keeps the preset palette well-formed and distinct from the fallback accent', () => {
    const values = ACCENT_PRESETS.map((preset) => preset.value);
    expect(new Set(values).size).toBe(ACCENT_PRESETS.length);
    for (const value of values) expect(normalizeAccent(value)).toBe(value.toLowerCase());
    expect(values).not.toContain(DEFAULT_ACCENT.toLowerCase() + 'x');
  });
});

describe('themePreferences.background', () => {
  const dataUrl = 'data:image/jpeg;base64,' + 'A'.repeat(64);

  it('normalizes tint into 0-90 with a readable default', () => {
    expect(clampTint(50)).toBe(50);
    expect(clampTint(-10)).toBe(MIN_TINT);
    expect(clampTint(120)).toBe(MAX_TINT);
    expect(clampTint(12.6)).toBe(13);
    expect(clampTint('bad')).toBe(72);
    expect(clampTint(undefined)).toBe(72);
  });

  it('keeps well-formed preferences, rejects oversized or non-image payloads', () => {
    expect(normalizeBackground({ dataUrl, tint: 40 })).toEqual({ dataUrl, tint: 40 });
    expect(normalizeBackground({ dataUrl, tint: 999 })).toEqual({ dataUrl, tint: MAX_TINT });
    expect(normalizeBackground({ dataUrl: 'http://x/y.png', tint: 40 })).toBeNull();
    expect(normalizeBackground({ dataUrl: 'data:text/html;base64,AAA', tint: 40 })).toBeNull();
    expect(normalizeBackground({ dataUrl: 'data:image/png;base64,' + 'A'.repeat(2_700_000), tint: 40 })).toBeNull();
    expect(normalizeBackground(null)).toBeNull();
    expect(normalizeBackground('nope')).toBeNull();
  });

  it('persists and clears the background round-trip', () => {
    writeStoredBackground({ dataUrl, tint: 66 });
    expect(readStoredBackground()).toEqual({ dataUrl, tint: 66 });
    writeStoredBackground(null);
    expect(localStorage.getItem(BACKGROUND_KEY)).toBeNull();
    expect(readStoredBackground()).toBeNull();
  });

  // 压缩链路(FileReader→Image→canvas→JPEG)依赖真实浏览器能力,jsdom 无法执行,
  // 由 vite dev 实机走查覆盖;这里锁定纯逻辑契约,防止阈值与格式约定被误改。
  it('rejects non-image files before touching FileReader or canvas', async () => {
    await expect(fileToBackgroundDataUrl(new File(['nope'], 'x.txt', { type: 'text/plain' })))
      .rejects.toMatchObject({ message: 'not-an-image' });
  });

  it('keeps the persistence budget below the raw localStorage safety line', () => {
    expect(MAX_BACKGROUND_BYTES).toBeGreaterThan(500_000);
    expect(MAX_BACKGROUND_BYTES).toBeLessThan(2_600_000);
  });
});
