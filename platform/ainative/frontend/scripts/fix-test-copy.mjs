/**
 * 测试断言同步:把测试里按旧文案渲染出来的字面量,按「旧模板 → 新模板」回填。
 *
 * 用法:node scripts/fix-test-copy.mjs [--from <git ref>]
 *
 * 判定链:
 * 1. 字面量必须落在断言位置(查询/匹配调用,含其参数对象里的文案属性),
 *    否则是测试自造的夹具数据,不动;
 * 2. 字面量必须能被某个旧模板完整渲染出来;
 * 3. 若还有**未改写**的键能渲染出同一段文字,说明这段话本身没变,不动;
 * 4. 所有能渲染它的键改写后结果一致时才改写,否则报告出来人工处理。
 */
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = resolve(root, '../..');
const LOCALES = resolve(root, 'src/shared/i18n/locales');
const args = process.argv.slice(2);
const fromIdx = args.indexOf('--from');
const from = fromIdx >= 0 ? args[fromIdx + 1] : 'HEAD';

function flatten(obj, prefix = '', out = {}) {
  for (const [key, value] of Object.entries(obj)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (value && typeof value === 'object' && !Array.isArray(value)) flatten(value, path, out);
    else out[path] = value;
  }
  return out;
}

function gitShow(ref, path) {
  try {
    return execFileSync('git', ['show', `${ref}:${path}`], { cwd: repoRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  } catch {
    return '';
  }
}

const PLACEHOLDER = /^\{\{\s*[^{}]+?\s*\}\}$/;

/** 模板 → 分段序列(固定片段与占位符交替)。 */
function templateParts(template) {
  return template.split(/(\{\{\s*[^{}]+?\s*\}\})/g).filter(part => part !== '');
}

function fill(template, captured) {
  const parts = template.split(/(\{\{\s*[^{}]+?\s*\}\})/g);
  let index = 0;
  return parts.map(part => (PLACEHOLDER.test(part) ? captured[index++] ?? '' : part)).join('');
}

/**
 * 判断 literal 是否由该模板渲染出来;能则返回按占位符顺序的捕获值。
 * 逐段回溯匹配,固定片段必须逐字相等。
 *
 * 回溯顺序很关键:占位符先取最短值,让紧随其后的固定片段有机会对上。
 * 否则 "{{metric}}: {{value}}; current maximum: {{max}}" 会把
 * "Completed: 5 tasks; current maximum: 5 tasks" 整段交给 {{metric}} 吃掉。
 */
function matchTemplate(parts, literal) {
  const captured = [];
  function walk(index, position) {
    if (index === parts.length) return position === literal.length ? captured.slice() : null;
    const part = parts[index];
    if (PLACEHOLDER.test(part)) {
      // 短值优先:占位符后面若跟着固定文本(如 ": {{value}}; current maximum:"),
      // 先让固定片段去匹配,匹配不上再让占位符多吃。
      for (let end = position + 1; end <= literal.length; end += 1) {
        const value = literal.slice(position, end);
        captured.push(value);
        const result = walk(index + 1, end);
        captured.pop();
        if (result) return result;
      }
      return null;
    }
    if (!literal.startsWith(part, position)) return null;
    return walk(index + 1, position + part.length);
  }
  return walk(0, 0);
}

/**
 * 断言位置白名单:只有出现在这些查询/匹配调用里的字面量才是界面文案。
 * 夹具数据(prompt: '...' 之类)即便与模板同形也不该改。
 */
const ASSERTION_CALLS = new Set([
  'getByText', 'findByText', 'queryByText', 'getAllByText', 'findAllByText', 'queryAllByText',
  'getByRole', 'findByRole', 'queryByRole', 'getAllByRole', 'findAllByRole',
  'getByLabelText', 'findByLabelText', 'queryByLabelText',
  'getByPlaceholderText', 'findByPlaceholderText',
  'getByAltText', 'getByTitle', 'getByDescription',
  'toHaveTextContent', 'toContain', 'toHaveAttribute', 'toHaveAccessibleName',
  'toHaveAccessibleDescription',
]);

/** 断言参数对象里可能承载文案的属性名。 */
const ASSERTION_PROPERTIES = new Set(['name', 'label', 'value', 'title', 'description', 'placeholder', 'text', 'content', 'message', 'alt']);

function isAssertionLiteral(source, literal) {
  const before = source.slice(Math.max(0, literal.start - 200), literal.start);
  // 往前找最近的白名单调用;字面量必须落在它的参数列表内
  // (直接作为参数,或作为参数对象的文案属性,如 getByRole('img', { name: '...' }))。
  const callPattern = /([A-Za-z_$][\w$]*)\s*\(/g;
  let best = null;
  let match;
  while ((match = callPattern.exec(before)) !== null) {
    if (ASSERTION_CALLS.has(match[1])) best = match;
  }
  if (!best) return false;
  // 参数列表内只允许普通参数与对象字面量:括号与花括号必须配平。
  const between = before.slice(best.index + best[0].length);
  let depth = 0;
  for (const char of between) {
    if (char === '(' || char === '{' || char === '[') depth += 1;
    else if (char === ')' || char === '}' || char === ']') depth -= 1;
  }
  return depth >= 0;
}

function collectTestFiles(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    const full = resolve(dir, entry);
    if (statSync(full).isDirectory()) { if (!/node_modules|\.git/.test(full)) collectTestFiles(full, out); continue; }
    if (/\.test\.(ts|tsx)$/.test(entry)) out.push(full);
  }
  return out;
}

/** 取出源码里所有正则字面量及其区间(断言里的 /片段/ 形式)。 */
function regexLiterals(source) {
  const out = [];
  for (let i = 0; i < source.length; i += 1) {
    if (source[i] !== '/') continue;
    // 排除注释与除号
    const before = source.slice(Math.max(0, i - 40), i);
    if (/[\w)\]}\s]$/.test(before) && !/(?:=|,|\(|\[|:|\breturn|\bcase|&&|\|\||\?)\s*$/.test(before)) continue;
    let end = i + 1;
    let inClass = false;
    let closed = false;
    while (end < source.length) {
      const char = source[end];
      if (char === '\\') { end += 2; continue; }
      if (char === '[') inClass = true;
      else if (char === ']') inClass = false;
      else if (char === '/' && !inClass) { closed = true; break; }
      else if (char === '\n') break;
      end += 1;
    }
    if (!closed) continue;
    let flags = end + 1;
    while (flags < source.length && /[a-z]/.test(source[flags])) flags += 1;
    const body = source.slice(i + 1, end);
    if (!body || body.length > 300) continue;
    out.push({ start: i + 1, end, text: body, flags: source.slice(end + 1, flags) });
    i = flags - 1;
  }
  return out;
}

/** 取出源码里所有字符串字面量及其区间。 */
function stringLiterals(source) {
  const out = [];
  for (let i = 0; i < source.length; i += 1) {
    const quote = source[i];
    if (!['"', "'", '`'].includes(quote)) continue;
    let end = i + 1;
    while (end < source.length) {
      if (source[end] === '\\') { end += 2; continue; }
      if (source[end] === quote) break;
      if (source[end] === '\n' && quote !== '`') break;
      end += 1;
    }
    if (end >= source.length) break;
    out.push({ quote, start: i + 1, end, text: source.slice(i + 1, end) });
    i = end;
  }
  return out;
}

const locales = ['zh-CN', 'en-US'];
const templates = [];

for (const locale of locales) {
  const localeDir = resolve(LOCALES, locale);
  for (const file of readdirSync(localeDir).filter(name => name.endsWith('.json'))) {
    const ns = file.replace(/\.json$/, '');
    const oldRaw = gitShow(from, `platform/ainative/frontend/src/shared/i18n/locales/${locale}/${file}`);
    if (!oldRaw) continue;
    const oldFlat = flatten(JSON.parse(oldRaw));
    const currentFlat = flatten(JSON.parse(readFileSync(resolve(localeDir, file), 'utf8')));
    for (const [key, before] of Object.entries(oldFlat)) {
      if (typeof before !== 'string' || !before.trim()) continue;
      const current = currentFlat[key];
      const parts = templateParts(before);
      templates.push({
        locale, ns, key, before,
        after: current === before || typeof current !== 'string' ? null : current,
        parts,
        // 固定片段全为空的模板(纯占位符)能渲染出任意文字,不能用来定位字面量。
        usable: parts.some(part => !PLACEHOLDER.test(part) && part.trim().length > 0),
        // 无占位符的模板只能原样渲染出自己,是字面量来源的最强证据。
        exact: !before.includes('{{'),
        // 固定文本量:用来在多个候选里挑出最贴切的来源。
        // "{{label}} {{count}}" 这类通用模板几乎能匹配任何文字,
        // 固定文本越多的模板越可能是这段话真正的出处。
        fixedLength: parts.filter(part => !PLACEHOLDER.test(part)).reduce((sum, part) => sum + part.length, 0),
      });
    }
  }
}
const changed = templates.filter(t => t.after !== null);
console.log(`changed strings vs ${from}: ${changed.length}`);

const ambiguous = [];
let touched = 0;
let replacements = 0;

for (const file of collectTestFiles(resolve(root, 'src'))) {
  let source = readFileSync(file, 'utf8');
  let changedFile = false;

  const edits = [];
  for (const literal of stringLiterals(source)) {
    if (literal.text.length < 2) continue;
    // 只处理断言位置上的字面量:提示词、夹具数据等自造内容
    // 即便与 locale 模板同形,也不是界面文案,不能动。
    if (!isAssertionLiteral(source, literal)) continue;

    const renderedBy = [];
    for (const template of templates) {
      if (!template.usable) continue;
      const captured = matchTemplate(template.parts, literal.text);
      if (!captured) continue;
      renderedBy.push({ template, replacement: template.after ? fill(template.after, captured) : literal.text });
    }
    if (!renderedBy.length) continue;
    // 来源判据:先看无占位符的模板(只能原样渲染出自己),
    // 否则取固定文本量最大的一组。固定文本量差太远的候选直接排除 ——
    // "{{label}} {{count}}" 能匹配几乎任何文字,不该和 "{{metric}}: {{value}}…" 抢来源。
    const exact = renderedBy.filter(item => item.template.exact);
    let origin = exact.length ? exact : renderedBy;
    if (!exact.length) {
      const best = Math.max(...origin.map(item => item.template.fixedLength));
      origin = origin.filter(item => item.template.fixedLength * 2 >= best);
    }
    // 若还有未改写的键能渲染出同一段文字,说明这段话本身没变,不动。
    if (origin.some(item => item.template.after === null)) continue;
    const replacementSet = new Set(origin.map(item => item.replacement));
    if (replacementSet.size > 1) {
      ambiguous.push(`${[...new Set(origin.map(item => `${item.template.locale} ${item.template.ns}.${item.template.key}`))].join(' / ')} @${file}:${literal.start} “${literal.text}”`);
      continue;
    }
    const replacement = [...replacementSet][0];
    if (replacement === literal.text) continue;
    if (/\{\{|\}\}/.test(replacement)) continue;
    edits.push({ start: literal.start, end: literal.end, replacement });
  }
  for (const edit of edits.sort((a, b) => b.start - a.start)) {
    source = source.slice(0, edit.start) + edit.replacement + source.slice(edit.end);
    changedFile = true;
    replacements += 1;
  }

  if (changedFile) { writeFileSync(file, source); touched += 1; }
}
console.log(`synced ${replacements} literals across ${touched} test files`);
if (ambiguous.length) {
  console.log(`\nambiguous (skipped, ${ambiguous.length}):`);
  for (const line of ambiguous.slice(0, 40)) console.log(`  ${line}`);
  if (ambiguous.length > 40) console.log(`  … 还有 ${ambiguous.length - 40} 条`);
}
