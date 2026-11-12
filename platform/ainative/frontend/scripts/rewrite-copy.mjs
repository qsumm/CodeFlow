/**
 * 批量改写文案工具。
 *
 * 用法:node scripts/rewrite-copy.mjs <mapping.json>
 * mapping 形如 { "<namespace>": { "<base key>": { "zh": "...", "en": "..." } } }
 *
 * 守卫:
 * - base key 可命中同名键或复数变体(_one/_other/...);变体按语言分别写入。
 * - 占位符集合 {{...}} 与组件占位符 <x/> 必须在改写前后完全一致,否则报错退出。
 * - 单次替换用 token 化方式,避免新值里含旧值时被二次替换。
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const LOCALES = resolve(root, 'src/shared/i18n/locales');

const variables = value => [...new Set([...String(value).matchAll(/\{\{\s*([^{}]+?)\s*\}\}/g)].map(m => m[1].split(',')[0].trim()))].sort().join('|');
const components = value => [...new Set([...String(value).matchAll(/<\/?([A-Za-z][\w.-]*|\d+)(?:\s[^<>]*?)?\/?>/g)].map(m => m[1]))].sort().join('|');
const PLURAL = /_(zero|one|two|few|many|other)$/;

function readLocale(locale, ns) {
  return JSON.parse(readFileSync(resolve(LOCALES, locale, `${ns}.json`), 'utf8'));
}
function writeLocale(locale, ns, data) {
  writeFileSync(resolve(LOCALES, locale, `${ns}.json`), `${JSON.stringify(data, null, 2)}\n`);
}
function getPath(obj, path) {
  const parts = path.split('.');
  let cur = obj;
  for (const part of parts) {
    if (!cur || typeof cur !== 'object' || !(part in cur)) return undefined;
    cur = cur[part];
  }
  return cur;
}
function setPath(obj, path, value) {
  const parts = path.split('.');
  let cur = obj;
  for (const part of parts.slice(0, -1)) {
    if (!cur[part] || typeof cur[part] !== 'object') cur[part] = {};
    cur = cur[part];
  }
  cur[parts.at(-1)] = value;
}
function deletePath(obj, path) {
  const parts = path.split('.');
  let cur = obj;
  for (const part of parts.slice(0, -1)) {
    if (!cur[part] || typeof cur[part] !== 'object') return;
    cur = cur[part];
  }
  delete cur[parts.at(-1)];
}
function flattenPaths(obj, prefix = '', out = []) {
  for (const [key, value] of Object.entries(obj)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (value && typeof value === 'object' && !Array.isArray(value)) flattenPaths(value, path, out);
    else out.push(path);
  }
  return out;
}

const mapping = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const errors = [];
const report = [];

for (const [ns, entries] of Object.entries(mapping)) {
  const zh = readLocale('zh-CN', ns);
  const en = readLocale('en-US', ns);
  const zhPaths = flattenPaths(zh);
  const enPaths = flattenPaths(en);
  for (const [base, spec] of Object.entries(entries)) {
    const matches = paths => paths.filter(k => k === base || (PLURAL.test(k) && k.replace(PLURAL, '') === base));
    const zhKeys = matches(zhPaths);
    const enKeys = matches(enPaths);
    if (!zhKeys.length) { errors.push(`${ns}.${base}: zh key not found`); continue; }
    if (!enKeys.length) { errors.push(`${ns}.${base}: en key not found`); continue; }

    const zhValue = spec.zh;
    const enValue = spec.en;
    const enByCategory = typeof enValue === 'object' ? enValue : null;
    const enPlain = enByCategory ? null : enValue;
    const enFor = key => {
      if (!enByCategory) return enPlain;
      const category = key.match(PLURAL)?.[1] ?? 'other';
      return enByCategory[category] ?? enByCategory.other;
    };
    if (!enByCategory && variables(zhValue) !== variables(enValue)) { errors.push(`${ns}.${base}: zh/en variable mismatch (${variables(zhValue)} vs ${variables(enValue)})`); continue; }
    if (!enByCategory && components(zhValue) !== components(enValue)) { errors.push(`${ns}.${base}: zh/en component mismatch (${components(zhValue)} vs ${components(enValue)})`); continue; }

    for (const key of zhKeys) {
      const before = getPath(zh, key);
      if (typeof before !== 'string') { errors.push(`${ns}.${key}: not a string`); continue; }
      if (variables(before) !== variables(zhValue)) { errors.push(`${ns}.${key}: variable set changed`); continue; }
      if (components(before) !== components(zhValue)) { errors.push(`${ns}.${key}: component set changed`); continue; }
      setPath(zh, key, zhValue);
    }
    for (const key of enKeys) {
      const before = getPath(en, key);
      const target = enFor(key);
      if (typeof before !== 'string') { errors.push(`${ns}.${key}: not a string`); continue; }
      if (typeof target !== 'string') { errors.push(`${ns}.${key}: no en value for category`); continue; }
      if (variables(before) !== variables(target)) { errors.push(`${ns}.${key}: variable set changed`); continue; }
      if (components(before) !== components(target)) { errors.push(`${ns}.${key}: component set changed`); continue; }
      setPath(en, key, target);
    }
    report.push(`${ns}.${base} -> zh:${zhKeys.length} en:${enKeys.length}`);
  }
  writeLocale('zh-CN', ns, zh);
  writeLocale('en-US', ns, en);
}

if (errors.length) {
  console.error(errors.join('\n'));
  process.exit(1);
}
console.log(report.join('\n'));
console.log(`\nrewrote ${report.length} base keys`);
