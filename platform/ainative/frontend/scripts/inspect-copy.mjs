/**
 * 查看某个渲染字面量由哪些 locale 键产出,以及各自的新文案。
 * 用法:node scripts/inspect-copy.mjs <字面量> [--from <git ref>]
 */
import { readFileSync, readdirSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = resolve(root, '../..');
const LOCALES = resolve(root, 'src/shared/i18n/locales');
const args = process.argv.slice(2);
const fromIdx = args.indexOf('--from');
const from = fromIdx >= 0 ? args[fromIdx + 1] : 'HEAD';
const literal = args[0];

function flatten(obj, prefix = '', out = {}) {
  for (const [key, value] of Object.entries(obj)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (value && typeof value === 'object' && !Array.isArray(value)) flatten(value, path, out);
    else out[path] = value;
  }
  return out;
}
function gitShow(ref, path) {
  try { return execFileSync('git', ['show', `${ref}:${path}`], { cwd: repoRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }); }
  catch { return ''; }
}
const escapeRegExp = text => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const PLACEHOLDER = /^\{\{\s*[^{}]+?\s*\}\}$/;
function buildMatcher(template) {
  const parts = template.split(/(\{\{\s*[^{}]+?\s*\}\})/g);
  let source = ''; let placeholders = 0;
  for (const part of parts) {
    if (PLACEHOLDER.test(part)) { source += "([^'\"\\n]+?)"; placeholders += 1; }
    else if (part) source += escapeRegExp(part);
  }
  return { regex: new RegExp(source), placeholders };
}
function fill(template, captured) {
  const parts = template.split(/(\{\{\s*[^{}]+?\s*\}\})/g);
  let index = 0;
  return parts.map(part => (PLACEHOLDER.test(part) ? captured[index++] ?? '' : part)).join('');
}
for (const locale of ['zh-CN', 'en-US']) {
  const dir = resolve(LOCALES, locale);
  for (const file of readdirSync(dir).filter(n => n.endsWith('.json'))) {
    const ns = file.replace(/\.json$/, '');
    const oldRaw = gitShow(from, `platform/ainative/frontend/src/shared/i18n/locales/${locale}/${file}`);
    if (!oldRaw) continue;
    const oldFlat = flatten(JSON.parse(oldRaw));
    const newFlat = flatten(JSON.parse(readFileSync(resolve(dir, file), 'utf8')));
    for (const [key, before] of Object.entries(oldFlat)) {
      if (typeof before !== 'string' || !before.trim()) continue;
      // 占位符里的实际值可能含标点,匹配时放宽成宽松通配再逐字校验渲染结果。
      const loose = before.split(/(\{\{\s*[^{}]+?\s*\}\})/g)
        .map(part => (PLACEHOLDER.test(part) ? '(.+?)' : escapeRegExp(part))).join('');
      const match = new RegExp(`^${loose}$`).exec(literal);
      if (!match) continue;
      const captured = match.slice(1, 1 + (before.match(/\{\{\s*[^{}]+?\s*\}\}/g) ?? []).length);
      if (fill(before, captured) !== literal) continue;
      const after = newFlat[key];
      const changed = after !== before;
      console.log(`${changed ? 'CHANGED' : 'same   '} ${locale} ${ns}.${key}\n          old: ${before}\n          new: ${after}`);
    }
  }
}
