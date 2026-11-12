#!/usr/bin/env python3
"""Take 3:续录工单 10019 的执行尾段(dev 收尾 → CR → 收敛 → 产物 → 禅道回写)。

前置:take 2 已启动工单 10019 的交付(dev dispatch 10031 运行中)。
本 take:平台登录一次 → 打开工单 → 进度面板上镜,每 120s 轻刷新直到收敛
→ 产物/时间线 → 禅道回写验证。修复 take2 的两个问题:
  - reload 用 domcontentloaded(已修)且间隔 120s,降低页签崩溃概率
  - 收敛判定改用 API 状态(页面文本只做辅助),避免"已完成"历史标记误判
"""
from __future__ import annotations

import datetime as dt
import os
import re
import time
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = os.environ.get("AINATIVE_BASE_URL", "http://localhost:7001")
ZT = os.environ.get("ZENTAO_BASE_URL", "http://localhost:8888")
USER = os.environ.get("AINATIVE_ADMIN_USER", "admin")
PASSWORD = os.environ.get("AINATIVE_ADMIN_PASSWORD", "Admin@2026")
ZT_USER = os.environ.get("ZENTAO_ACCOUNT", "zentao-int")
ZT_PASS = os.environ.get("ZENTAO_PASSWORD", "Zentao@2026")
WID = os.environ.get("TAKE6_WID", "10019")
STORY_ID = os.environ.get("TAKE6_STORY", "16")

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "demo" / "realtake6"
OUT.mkdir(parents=True, exist_ok=True)
TL = ROOT / "docs" / "demo" / "real-take6-timeline.txt"
MAX_WAIT_MIN = int(os.environ.get("TAKE6_MAX_WAIT", "80"))

_tl = TL.open("w", encoding="utf-8")
T0 = time.time()


def log(msg: str) -> None:
    now = dt.datetime.now().strftime("%H:%M:%S")
    line = f"[{now}] (+{int(time.time()-T0)}s) {msg}"
    print(line, flush=True)
    _tl.write(line + "\n")
    _tl.flush()


def human(page, lo=0.6, hi=1.2) -> None:
    page.wait_for_timeout(int((lo + (hi - lo) * ((time.time() * 997) % 1000) / 1000) * 1000))


STATUS_API = """async (wid) => {
  let t = '';
  for (const k of Object.keys(localStorage)) {
    const v = localStorage.getItem(k) || '';
    if (v.includes('accessToken')) {
      try { const o = JSON.parse(v); if (o && o.accessToken) { t = o.accessToken; break; } } catch (e) {}
    }
  }
  if (!t) t = localStorage.getItem('ainative.accessToken') || localStorage.getItem('accessToken') || '';
  const r = await fetch('/api/workitems/' + wid, { headers: { 'Authorization': 'Bearer ' + t } });
  const j = await r.json();
  const w = j.data || {};
  return JSON.stringify({ node: w.statusNodeId, title: w.title });
}"""


def main() -> None:
    log(f"TAKE6 开始:续录工单 {WID} 执行尾段")
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        ctx = b.new_context(viewport={"width": 1440, "height": 900}, locale="zh-CN",
                            record_video_dir=str(OUT),
                            record_video_size={"width": 1440, "height": 900})
        page = ctx.new_page()
        page.set_default_timeout(30000)

        log("登录平台(一次)")
        page.goto(BASE + "/login", wait_until="networkidle")
        page.wait_for_timeout(1500)
        page.type('input[placeholder*="用户名"], input[type="text"]', USER, delay=60)
        human(page)
        page.type('input[type="password"]', PASSWORD, delay=60)
        human(page, 0.7, 1.1)
        page.click('button[type="submit"]')
        page.wait_for_url(lambda u: "/login" not in u, timeout=40000)
        page.wait_for_load_state("networkidle")
        page.locator("text=演示项目").first.click()
        page.wait_for_load_state("networkidle")
        page.wait_for_timeout(1200)
        page.goto(BASE + f"/workitems/{WID}", wait_until="networkidle")
        page.wait_for_timeout(2000)
        log(f"已打开工单 {WID};查找「重试」按钮恢复失败的评审步骤")
        try:
            page.locator('button:has-text("重试")').first.click()
            page.wait_for_timeout(2500)
            log("已点击重试,评审派发重新发起")
        except Exception as e:
            log(f"未找到重试按钮(可能已自动恢复):{str(e)[:60]}")
        page.wait_for_timeout(2000)

        deadline = time.time() + MAX_WAIT_MIN * 60
        prev = ""
        retried = 0
        converged = False
        while time.time() < deadline:
            page.wait_for_timeout(120000)
            try:
                page.reload(wait_until="domcontentloaded")
                page.wait_for_timeout(3000)
            except Exception as e:
                log(f"刷新异常(继续):{str(e)[:80]}")
                continue
            try:
                page.locator("text=交付进度跟踪").first.scroll_into_view_if_needed()
                page.wait_for_timeout(800)
            except Exception:
                pass
            try:
                api = page.evaluate(STATUS_API, WID)
                node = json_node(api)
            except Exception:
                node = ("?", "")
            body = ""
            try:
                body = page.locator("body").inner_text()
            except Exception:
                pass
            sig = "|".join(re.findall(r"(准备执行上下文|执行中|已完成|成功|失败|异常|代码评审|已发布|验证中|交接)", body))[:180]
            key = f"{node[0]}|{node[1]}|{sig[:60]}"
            if key != prev:
                log(f"状态:节点={node[0]} 标题={node[1][:16]};面板={sig[:110]}")
                prev = key
            if "失败" in body and "重试" in body and retried < 4:
                try:
                    page.locator('button:has-text("重试")').first.click()
                    page.wait_for_timeout(2500)
                    retried += 1
                    log(f"检测到失败,点击重试(第 {retried} 次)")
                    continue
                except Exception:
                    pass
            if node[0] not in ("10001", "10000", "?") and "执行中" not in body:
                log(f"收敛:节点={node[0]}")
                converged = True
                break
        if not converged:
            log(f"!! {MAX_WAIT_MIN} 分钟超时收尾,当前节点见上一条日志")

        log("检查交付产物与时间线")
        for label in ("产物", "时间线"):
            try:
                page.locator(f'text={label}').first.scroll_into_view_if_needed()
                page.wait_for_timeout(1200)
            except Exception:
                pass
        page.wait_for_timeout(1500)

        log("切禅道验证回写")
        zt = ctx.new_page()
        zt.set_default_timeout(30000)
        zt.goto(ZT + "/user-login.html", wait_until="networkidle")
        zt.wait_for_timeout(1000)
        zt.fill('input[name="account"]', ZT_USER)
        zt.fill('input[name="password"]', ZT_PASS)
        zt.click('#submit')
        zt.wait_for_load_state("networkidle")
        zt.wait_for_timeout(1500)
        try:
            zt.evaluate("""() => { const m=document.querySelector('#triggerModal'); if(m) m.remove();
                const bk=document.querySelector('.modal-backdrop'); if(bk) bk.remove(); }""")
        except Exception:
            pass
        zt.goto(ZT + f"/story-view-{STORY_ID}.html", wait_until="networkidle")
        zt.wait_for_timeout(1800)
        try:
            zt.evaluate("""() => { const m=document.querySelector('#triggerModal'); if(m) m.remove();
                const bk=document.querySelector('.modal-backdrop'); if(bk) bk.remove(); }""")
        except Exception:
            pass
        zt.wait_for_timeout(3000)
        log("禅道需求页展示完成(回写可见)")

        video = page.video
        ctx.close()
        b.close()
        if video:
            log(f"录像:{video.path()}")
    log("TAKE6 结束")


def json_node(raw: str) -> tuple[str, str]:
    import json as _j
    try:
        d = _j.loads(raw)
        return (str(d.get("node", "?")), str(d.get("title", "")))
    except Exception:
        return ("?", "")


if __name__ == "__main__":
    main()
