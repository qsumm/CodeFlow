#!/usr/bin/env python3
"""生成《用户操作手册》封面页(HTML→PNG,1440×810)。"""
from pathlib import Path
from playwright.sync_api import sync_playwright

OUT = Path("/Users/summer/AINative/docs/images/manual")
OUT.mkdir(parents=True, exist_ok=True)

HTML = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<style>
  * { margin:0; padding:0; box-sizing:border-box; }
  body { width:1440px; height:810px; font-family:"Hiragino Sans GB","PingFang SC",sans-serif;
         background:#0e0f11; color:#f0f2f6; position:relative; overflow:hidden; }
  .glow { position:absolute; width:900px; height:900px; border-radius:50%;
          background:radial-gradient(circle, rgba(60,120,255,.14) 0%, transparent 62%);
          top:-380px; right:-260px; }
  .glow2 { position:absolute; width:700px; height:700px; border-radius:50%;
           background:radial-gradient(circle, rgba(60,120,255,.08) 0%, transparent 60%);
           bottom:-360px; left:-200px; }
  .grid { position:absolute; inset:0;
          background-image:linear-gradient(rgba(255,255,255,.025) 1px, transparent 1px),
                           linear-gradient(90deg, rgba(255,255,255,.025) 1px, transparent 1px);
          background-size:72px 72px; }
  .kicker { position:absolute; top:96px; left:110px; font-size:15px; letter-spacing:7px;
            color:#8fa8d8; opacity:.85; }
  .rule { position:absolute; top:140px; left:110px; width:64px; height:2px; background:#3c78ff; }
  .brand { position:absolute; top:210px; left:110px; font-size:74px; font-weight:700;
           letter-spacing:2px; }
  .brand .ai { color:#3c78ff; }
  .subtitle { position:absolute; top:318px; left:112px; font-size:30px; font-weight:600;
              color:#e8ecf4; letter-spacing:1px; }
  .subtitle-en { position:absolute; top:366px; left:113px; font-size:15px; color:#7a8699;
                 letter-spacing:2px; }
  .summary { position:absolute; top:448px; left:112px; width:760px; font-size:16.5px;
             line-height:1.75; color:#a9b4c6; }
  .meta { position:absolute; bottom:150px; left:112px; display:flex; gap:44px; }
  .meta .item .k { font-size:12.5px; color:#6b7688; letter-spacing:2px; margin-bottom:8px; }
  .meta .item .v { font-size:17px; color:#dde3ee; }
  .footer { position:absolute; bottom:56px; left:110px; right:110px; display:flex;
            justify-content:space-between; font-size:12.5px; color:#5a6474;
            border-top:1px solid rgba(255,255,255,.08); padding-top:18px; letter-spacing:1px; }
  .badge { position:absolute; top:96px; right:110px; font-size:13px; color:#9db4dd;
           border:1px solid rgba(60,120,255,.45); border-radius:999px; padding:7px 18px;
           letter-spacing:2px; background:rgba(60,120,255,.08); }
</style></head>
<body>
  <div class="grid"></div><div class="glow"></div><div class="glow2"></div>
  <div class="kicker">U S E R &nbsp; M A N U A L</div>
  <div class="badge">v1.1 · 2026-11</div>
  <div class="rule"></div>
  <div class="brand">Code<span class="ai">Flow</span></div>
  <div class="subtitle">用户操作手册 · AI Native 软件需求交付平台</div>
  <div class="subtitle-en">AI-NATIVE SOFTWARE DELIVERY PLATFORM · USER OPERATION MANUAL</div>
  <div class="summary">
    需求进、交付出:数字员工按 SDLC 流水线自动完成分析、开发、测试、验证与交接,人只做需求澄清与验收。
    本手册覆盖平台全部功能页面的操作方式——工单交付主线、禅道闭环、执行器与模型接入、MCP 令牌、
    平台配置与审计——并给出三条推荐工作流与常见问题排查。全部 31 张配图取自当前构建实机。
  </div>
  <div class="meta">
    <div class="item"><div class="k">版本</div><div class="v">v1.1</div></div>
    <div class="item"><div class="k">适用读者</div><div class="v">研发 · 产品 · 测试 · 管理员</div></div>
    <div class="item"><div class="k">章节</div><div class="v">19 章 · 31 图</div></div>
    <div class="item"><div class="k">发布日期</div><div class="v">2026-11-04</div></div>
  </div>
  <div class="footer">
    <span>CodeFlow · 数字员工交付平台</span>
    <span>随附交付:平台演示视频(docs/demo) · 帮助中心(平台内置)</span>
  </div>
</body></html>"""

Path("/tmp/manual-cover.html").write_text(HTML, encoding="utf-8")
with sync_playwright() as pw:
    b = pw.chromium.launch()
    page = b.new_page(viewport={"width":1440, "height":810}, device_scale_factor=2)
    page.goto("file:///tmp/manual-cover.html")
    page.wait_for_timeout(500)
    page.screenshot(path=str(OUT / "00-cover.png"))
    b.close()
print("cover saved:", OUT / "00-cover.png")
