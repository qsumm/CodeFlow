// 手册 PDF 导出:docs/19-用户操作手册.md → manual.html → v1.2 PDF
// 用法: node scripts/docs-tools/export-manual-pdf.mjs   (需 playwright-core + 本机 Edge)
// 前置: 先按 docs/manual-pdf/manual.html 的转换逻辑重建 manual.html(转换器见 git 历史
//       或 docs/19-用户操作手册.md §21 说明);本脚本只负责 html → pdf 渲染。
import { chromium } from 'playwright-core';
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage();
await page.goto('file:///Users/summer/AINative/docs/manual-pdf/manual.html', { waitUntil: 'networkidle', timeout: 60000 });
await page.pdf({
  path: '/Users/summer/AINative/docs/manual-pdf/CodeFlow-用户操作手册-v1.2.pdf',
  format: 'A4', printBackground: true, displayHeaderFooter: false,
});
await browser.close();
console.log('PDF done');
