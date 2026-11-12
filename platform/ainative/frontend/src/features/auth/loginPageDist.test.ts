import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

// 工作项 #10022(同源 #10018/#10019)· 缺陷 A-03 —— 「登录页回车提交」在**源码**里达成,却可能在
// **产物**里缺席,而这两者之间原本没有任何门禁:
//
//   * 平台默认构建路径 platform/build.sh = REUSE_DIST:把「随库分发的 frontend/dist」直接拷进
//     target/classes/static 打成 jar、再烘进运行镜像;只有 FULL_BUILD=1 才从源码重建。
//   * 仓库约定是 dist 随源码一并提交(1124b86 就随 UI 修复提交过 dist),但这条约定没有守卫。
//
// 于是「源码改了、dist 没重建」会产出一个**登录页没有回车提交**的镜像,而源码单测与 auth 域
// 全绿 ⇒ 该缺陷对源码完全隐形,还能一路进入交付产物。实测:特性提交 4d9a4af 未带 dist,
// 已提交的 dist 里查不到回车闸门特征串,而在线实例之所以正常,是因为它服务的是工作区里
// 一份未提交的重建产物。
//
// 本用例把「产物面」变成可执行门禁:直接读盘上的 dist/,断言随库分发的产物里确实带着
// onFormKeyDown 的三道闸门(与 src/features/auth/LoginPage.tsx 一一对应),并断言 index.html
// 引用的资源都真实存在(防止 dist 被打残后仍被当作可交付产物)。
//
// 路径用 dirname/resolve 拼接而非 new URL('./x', import.meta.url):Vite 会把后者改写成 http
// 资源 URL,fileURLToPath 会拒绝(同 features/auth/workspaceLifecycle.css.test.ts)。
const here = dirname(fileURLToPath(import.meta.url));
// 默认盯住「随库分发的」前端产物;AINATIVE_DIST_DIR 可指向别的产物目录(回归验证:把 HEAD 的
// 已提交 dist 解出来指过去,门禁必须变红 —— 这正是 A-03 的可观测形态)。
const distDir = process.env.AINATIVE_DIST_DIR || resolve(here, '../../../dist');
const loginPageSource = resolve(here, 'LoginPage.tsx');

/**
 * onFormKeyDown 三道闸门在产物中的指纹。
 *
 * 「只认 INPUT 元素」是锚点(全产物仅此一处),其余三条是同段代码里的相邻特征。
 * 判定要求四条**落在同一个窗口**里,而不是各自「出现在文件某处」—— 压缩产物里
 * `nativeEvent.isComposing` 有 4 处(AutoComplete / TextArea / 其它组件 / 登录页),
 * 只做「文件内包含」判定会被别处的同名字符串顶替,门禁就成了摆设。
 * 压缩后的闸门是一段连续代码(实测锚点到组字特征仅 94 字符),窗口放宽到 600 字符足以容纳
 * 变量名与压缩器差异,又不会跨到别的组件。
 */
const LOGIN_ENTER_SUBMIT_MARKERS: { id: string; pattern: RegExp }[] = [
  { id: 'input-element-gate', pattern: /tagName\s*!==?\s*["'`]INPUT["'`]/ },
  { id: 'input-type-normalize', pattern: /\|\|\s*["'`]text["'`]\s*\)\s*\.toLowerCase\(\)/ },
  { id: 'password-type-gate', pattern: /!==?\s*["'`]password["'`]/ },
  { id: 'ime-composing-gate', pattern: /nativeEvent\.isComposing/ },
];

const ANCHOR = LOGIN_ENTER_SUBMIT_MARKERS[0]!;
const GATE_WINDOW = 600;

interface Asset {
  name: string;
  content: string;
}

function readJsAssets(): Asset[] {
  const assetsDir = resolve(distDir, 'assets');
  return readdirSync(assetsDir)
    .filter(name => name.endsWith('.js'))
    .map(name => ({ name, content: readFileSync(resolve(assetsDir, name), 'utf8') }));
}

/** 锚点所在的那段窗口(闸门所在处);锚点不存在则返回 null。 */
function gateWindow(content: string): { start: number; text: string } | null {
  const start = content.search(ANCHOR.pattern);
  if (start < 0) return null;
  return { start, text: content.slice(start, start + GATE_WINDOW) };
}

/** 返回第一个「四条指纹全中(且落在同一窗口)」的资源;没有则返回 null。 */
function findAssetShippingLoginGate(assets: Asset[]): string | null {
  for (const asset of assets) {
    const window = gateWindow(asset.content);
    if (window && LOGIN_ENTER_SUBMIT_MARKERS.every(marker => marker.pattern.test(window.text))) return asset.name;
  }
  return null;
}

/** index.html 里引用到的 /assets/ 资源(产物完整性:引用了就得存在)。 */
function referencedAssets(html: string): string[] {
  return [...html.matchAll(/(?:src|href)="(\/assets\/[^"]+)"/g)].map(match => match[1]!);
}

describe('登录页回车提交 · 产物面(dist)同步门禁', () => {
  it('随库分发的 dist 里带着登录页回车提交的闸门', () => {
    expect(existsSync(resolve(distDir, 'index.html')), 'dist/index.html 缺失:仓库约定 dist 随库分发,缺失则默认 REUSE_DIST 构建必然失败').toBe(true);
    const assets = readJsAssets();
    expect(assets.length).toBeGreaterThan(0);
    const shipping = findAssetShippingLoginGate(assets);
    expect(
      shipping,
      '随库分发的 dist 里找不到登录页回车闸门(「tagName!==INPUT」+「type 归一化后只放行 text/password」+「组字态放行」必须同处一个产物文件的同一段代码里):dist 与 src 已漂移,按默认 REUSE_DIST 构建出的镜像将没有回车提交',
    ).not.toBeNull();
  });

  it('dist/index.html 引用的资源都真实存在(产物没被打残)', () => {
    const html = readFileSync(resolve(distDir, 'index.html'), 'utf8');
    const refs = referencedAssets(html);
    expect(refs.length).toBeGreaterThan(0);
    const dangling = refs.filter(ref => !existsSync(resolve(distDir, ref.replace(/^\//, ''))));
    expect(dangling, `dist/index.html 引用了不存在的资源:${dangling.join(', ')}`).toEqual([]);
  });

  it('源码侧仍然声明着同一道闸门(产物与源码必须谈的是同一件事)', () => {
    const source = readFileSync(loginPageSource, 'utf8');
    expect(source, 'LoginPage.tsx 不再声明「只认 INPUT 元素」的闸门').toMatch(/tagName\s*!==?\s*['"`]INPUT['"`]/);
    expect(source, 'LoginPage.tsx 不再声明「组字态放行」的闸门').toMatch(/nativeEvent\.isComposing/);
  });

  it('门禁有牙齿:产物缺少闸门时必须判为缺失', () => {
    // 模拟 A-03 的历史形态:产物里没有登录页的 onFormKeyDown(其余内容照常)。
    const withoutGate: Asset[] = [{
      name: 'index-STALE.js',
      content: 'const a="hello";function e(t){return t.key==="Enter"&&(t.preventDefault(),d())}function d(){return fetch("/api/auth/login")}',
    }];
    expect(findAssetShippingLoginGate(withoutGate)).toBeNull();
    // 对照:闸门齐全时必须命中。
    const withGate: Asset[] = [{
      name: 'index-FRESH.js',
      content: 'Q=U=>{if(U.key!=="Enter")return;const H=U.target;if(!H||H.tagName!=="INPUT")return;const W=(H.type||"text").toLowerCase();W!=="text"&&W!=="password"||U.nativeEvent.isComposing||(U.preventDefault(),D())}',
    }];
    expect(findAssetShippingLoginGate(withGate)).toBe('index-FRESH.js');
  });

  it('门禁有牙齿:四条指纹缺任意一条都判为缺失(逐条变异)', () => {
    const real = readJsAssets().find(asset => findAssetShippingLoginGate([asset]) === asset.name);
    expect(real, '先要有一个真实命中闸门的产物,才能对它做逐条变异').toBeDefined();
    for (const marker of LOGIN_ENTER_SUBMIT_MARKERS) {
      // 只在闸门所在的那段窗口里抹掉该指纹 —— 抹掉的是「这道闸门的一部分」,
      // 而不是文件里别处的同名字符串。
      const anchor = real!.content.search(ANCHOR.pattern);
      const before = real!.content.slice(0, anchor);
      const window = real!.content.slice(anchor, anchor + GATE_WINDOW);
      const after = real!.content.slice(anchor + GATE_WINDOW);
      const mutatedWindow = window.replace(marker.pattern, '');
      expect(mutatedWindow, `闸门窗口里找不到「${marker.id}」`).not.toBe(window);
      const mutated: Asset[] = [{ name: real!.name, content: before + mutatedWindow + after }];
      expect(
        findAssetShippingLoginGate(mutated),
        `抹掉「${marker.id}」后门禁仍判定命中 ⇒ 该指纹没有判别力`,
      ).toBeNull();
    }
  });
});
