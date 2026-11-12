#!/usr/bin/env python3
"""真实需求完整实录(单条连续录像,不剪辑,真实时长)。

一条 take 走完:平台登录 → 禅道 UI 真实建需求 → 平台自动拉取(真实等待)
→ AI 需求澄清 → 启动交付 → 真实执行全程(实时上镜,MiniMax-M3)
→ 交付产物/时间线检查 → 禅道回写验证。

用法: python3 scripts/record_real_flow.py
产出: docs/demo/realtake/take.webm (原始连续录像)
      docs/demo/real-take-timeline.txt (各阶段墙钟时间戳,供后期剪辑定位)
"""
from __future__ import annotations

import datetime as dt
import os
import re
import sys
import time
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = os.environ.get("AINATIVE_BASE_URL", "http://localhost:7001")
ZT = os.environ.get("ZENTAO_BASE_URL", "http://localhost:8888")
USER = os.environ.get("AINATIVE_ADMIN_USER", "admin")
PASSWORD = os.environ.get("AINATIVE_ADMIN_PASSWORD", "Admin@2026")
ZT_USER = os.environ.get("ZENTAO_ACCOUNT", "zentao-int")
ZT_PASS = os.environ.get("ZENTAO_PASSWORD", "Zentao@2026")

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "demo" / "realtake"
OUT.mkdir(parents=True, exist_ok=True)
TL = ROOT / "docs" / "demo" / "real-take-timeline.txt"

STORY_TITLE = "会员登录页支持回车键提交登录表单"
STORY_SPEC = ("作为平台用户,我希望在会员登录页输入账号和密码后,直接按回车键即可提交登录表单,"
              "而不必移动鼠标点击登录按钮。\n\n验收标准:\n"
              "1) 用户名或密码输入框内按 Enter 键,触发表单登录提交;\n"
              "2) 回车提交失败时,错误提示行为与点击登录按钮完全一致;\n"
              "3) 不影响现有「记住我」功能与其他登录逻辑;\n"
              "4) 其他页面不受影响。")
MAX_WAIT_MIN = int(os.environ.get("REAL_TAKE_MAX_WAIT", "75"))

_tl_fp = TL.open("w", encoding="utf-8")
T0 = time.time()


def log(msg: str) -> None:
    now = dt.datetime.now().strftime("%H:%M:%S")
    line = f"[{now}] (+{int(time.time()-T0)}s) {msg}"
    print(line, flush=True)
    _tl_fp.write(line + "\n")
    _tl_fp.flush()


def human(page, lo=0.6, hi=1.3) -> None:
    page.wait_for_timeout(int((lo + (hi - lo) * ((time.time() * 997) % 1000) / 1000) * 1000))


def close_zt_promo(page) -> None:
    """关掉禅道 IPD 营销弹窗(挡指针)。"""
    try:
        page.evaluate("""() => {
            const m = document.querySelector('#triggerModal');
            if (m) m.remove();
            const bk = document.querySelector('.modal-backdrop');
            if (bk) bk.remove();
            document.body.classList.remove('modal-open');
        }""")
    except Exception:
        pass


def main() -> None:
    log("TAKE 开始:真实需求完整实录(单条连续,不剪辑)")
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        ctx = b.new_context(
            viewport={"width": 1440, "height": 900},
            locale="zh-CN",
            record_video_dir=str(OUT),
            record_video_size={"width": 1440, "height": 900},
        )
        page = ctx.new_page()
        page.set_default_timeout(30000)

        # ── 1. 平台登录(全片唯一一次) ──────────────────────────────
        log("阶段1:平台登录")
        page.goto(BASE + "/login", wait_until="networkidle")
        page.wait_for_timeout(1500)
        page.fill('input[placeholder*="用户名"], input[type="text"]', "")
        page.type('input[placeholder*="用户名"], input[type="text"]', USER, delay=60)
        human(page, 0.7, 1.2)
        page.fill('input[type="password"]', "")
        page.type('input[type="password"]', PASSWORD, delay=60)
        human(page, 0.8, 1.3)
        page.click('button[type="submit"]')
        page.wait_for_url(lambda u: "/login" not in u, timeout=40000)
        page.wait_for_load_state("networkidle")
        page.wait_for_timeout(1200)
        log("登录成功,进入工作空间选择页")
        page.locator("text=演示项目").first.click()
        page.wait_for_load_state("networkidle")
        page.wait_for_timeout(1500)
        log("已进入 演示项目 工作空间")

        # ── 2. 禅道建需求(新标签页,真实 UI 操作) ──────────────────
        log("阶段2:切换到禅道,以产品视角真实创建需求")
        zt_page = ctx.new_page()
        zt_page.set_default_timeout(30000)
        zt_page.goto(ZT + "/user-login.html", wait_until="networkidle")
        zt_page.wait_for_timeout(1000)
        zt_page.fill('input[name="account"]', ZT_USER)
        zt_page.fill('input[name="password"]', ZT_PASS)
        human(zt_page, 0.6, 1.0)
        zt_page.click('#submit')
        zt_page.wait_for_load_state("networkidle")
        zt_page.wait_for_timeout(1500)
        close_zt_promo(zt_page)
        log("禅道登录完成:" + zt_page.url)
        zt_page.goto(ZT + "/story-create-1-0-0.html", wait_until="networkidle")
        zt_page.wait_for_timeout(1800)
        close_zt_promo(zt_page)
        fr = zt_page.frame_locator('iframe[name="app-product"]')
        fr.locator('input[name="title"]').first.click()
        fr.locator('input[name="title"]').first.type(STORY_TITLE, delay=45)
        human(zt_page, 0.8, 1.2)
        ed = fr.locator('iframe.ke-edit-iframe').first
        ed.content_frame.locator('body').click()
        ed.content_frame.locator('body').type(STORY_SPEC, delay=12)
        human(zt_page, 0.8, 1.4)
        log("需求标题与描述已填写,点击保存")
        fr.locator('#saveButton').click()
        zt_page.wait_for_load_state("networkidle")
        zt_page.wait_for_timeout(2500)
        story_id = None
        m = re.search(r'story-view-(\d+)', zt_page.url)
        if m:
            story_id = m.group(1)
        else:
            # 保存后回到需求列表,点开新建的需求
            try:
                lst = zt_page.frame_locator('iframe[name="app-product"]')
                lst.locator(f'a:has-text("{STORY_TITLE}")').first.click()
                zt_page.wait_for_load_state("networkidle")
                zt_page.wait_for_timeout(1500)
                m = re.search(r'story-view-(\d+)', zt_page.url)
                story_id = m.group(1) if m else None
            except Exception:
                pass
        log(f"需求已在禅道创建: story-{story_id} 「{STORY_TITLE}」")
        zt_page.wait_for_timeout(2500)

        # ── 3. 平台自动拉取(真实等待,≤5 分钟) ────────────────────
        log("阶段3:切回平台,等待自动拉取入库(≤60s 同步周期)")
        page.bring_to_front()
        page.goto(BASE + "/workitems", wait_until="networkidle")
        wid = None
        deadline = time.time() + 300
        card_sel = f'text="{STORY_TITLE}"'
        while time.time() < deadline and not wid:
            page.reload(wait_until="networkidle")
            page.wait_for_timeout(1500)
            if page.locator(card_sel).count():
                page.locator(card_sel).first.click()
                page.wait_for_load_state("networkidle")
                page.wait_for_timeout(2000)
                mm = re.search(r"/workitems/(\d+)", page.url)
                if mm:
                    wid = mm.group(1)
                break
            page.wait_for_timeout(9000)
        if not wid:
            log("!! 5 分钟内未拉取到,中止(数据检查后再录)")
            video = page.video
            ctx.close()
            b.close()
            _tl_fp.close()
            sys.exit(2)
        log(f"平台已自动拉取入库:工单 {wid}「{STORY_TITLE}」")
        page.wait_for_timeout(2000)

        # ── 4. AI 需求澄清 → 启动交付 ───────────────────────────────
        log("阶段4:启动交付(AI 需求澄清 → 选小队)")
        page.click('button:has-text("启动交付")')
        page.wait_for_timeout(1500)
        remind = page.locator('.ant-modal', has_text='建议先完成需求澄清')
        clarified = False
        if remind.count() and remind.first.is_visible():
            log("出现澄清提醒,选择「确认,去澄清」")
            human(page, 0.7, 1.1)
            remind.locator('button:has-text("确认"), .ant-btn-primary').first.click()
            page.wait_for_timeout(2500)
            clarified = True
        if clarified:
            # 澄清对话真实等待:轮询面板文本,稳定后若仍是提问则作答
            log("AI 需求澄清进行中(真实等待)…")
            stable = 0
            last = ""
            for _ in range(40):  # 最多 10 分钟
                page.wait_for_timeout(15000)
                try:
                    txt = page.locator("body").inner_text()[-3000:]
                except Exception:
                    txt = last
                core = re.sub(r"\s+", "", txt[-1500:])
                if core == re.sub(r"\s+", "", last[-1500:] if last else ""):
                    stable += 1
                else:
                    stable = 0
                last = txt
                if stable >= 2:
                    break
            log("澄清输出趋于稳定")
            # 若澄清在提问,给一个真实回答
            try:
                if ("?" in last[-800:] or "？" in last[-800:]) and page.locator(
                        'textarea[placeholder*="澄清"], input[placeholder*="澄清"]').count():
                    ans = ("按验收标准执行即可;登录错误提示沿用现有逻辑,无额外约束。")
                    box = page.locator('textarea[placeholder*="澄清"], input[placeholder*="澄清"]').first
                    box.click()
                    box.type(ans, delay=30)
                    page.keyboard.press("Enter")
                    log("已回复澄清问题,继续等待澄清收敛")
                    page.wait_for_timeout(90000)
            except Exception as e:
                log(f"澄清答疑步骤跳过:{e}")
        page.wait_for_timeout(1200)
        # 再次启动交付
        try:
            page.click('button:has-text("启动交付")')
        except Exception:
            pass
        page.wait_for_timeout(1500)
        remind2 = page.locator('.ant-modal', has_text='建议先完成需求澄清')
        if remind2.count() and remind2.first.is_visible():
            log("澄清提醒再次出现,勾选不再提醒并跳过")
            try:
                remind2.locator('.ant-checkbox-input, input[type=checkbox]').first.check()
            except Exception:
                pass
            human(page, 0.5, 0.9)
            remind2.locator('button:has-text("跳 过"), button:has-text("跳过")').first.click()
            page.wait_for_timeout(1200)
        modal = page.locator(".ant-modal:visible")
        modal.locator(".ant-select").first.click()
        page.wait_for_timeout(700)
        page.locator(".ant-select-item-option", has_text="开发与评审小队").first.click()
        human(page)
        modal.locator(".ant-select").nth(1).click()
        page.wait_for_timeout(700)
        page.locator(".ant-select-item-option", has_text="全栈开发").first.click()
        human(page, 0.8, 1.3)
        log("已选 开发与评审小队 + 全栈开发,点击 启动交付")
        modal.locator('button:has-text("启动交付"), .ant-btn-primary').first.click()
        # 关键:等待弹窗真正关闭(≤12s);不关则强制刷新关闭
        modal_gone = False
        for _ in range(6):
            page.wait_for_timeout(2000)
            try:
                if page.locator(".ant-modal:visible").count() == 0:
                    modal_gone = True
                    break
            except Exception:
                modal_gone = True
                break
        if not modal_gone:
            log("!! 弹窗提交后未关闭,强制刷新关闭")
            page.reload(wait_until="domcontentloaded")
            page.wait_for_timeout(2500)
        log("交付已启动(弹窗已关闭),进入真实执行等待(全程上镜)")

        # ── 5. 真实执行等待(实时上镜) ───────────────────────────────
        log("阶段5:数字员工真实执行中(录制策略:每 20s 做一次可见的页面交互——滚动进度/查看时间线/刷新,避免长静止)")
        # 把交付进度跟踪面板滚进视野
        try:
            page.locator("text=交付进度跟踪").first.scroll_into_view_if_needed()
            page.wait_for_timeout(800)
        except Exception:
            pass
        # API 状态探测(页面上下文内 fetch,带登录态)
        async_status_js = """async (wid) => {
          const tok = localStorage.getItem('ainative.accessToken') || localStorage.getItem('accessToken') || '';
          const keys = Object.keys(localStorage);
          let t = tok;
          for (const k of keys) { const v = localStorage.getItem(k) || '';
            if (v.includes('accessToken')) { try { const o = JSON.parse(v); if (o.accessToken) t = o.accessToken; } catch(e){} } }
          const r = await fetch('/api/workitems/' + wid, { headers: { 'Authorization': 'Bearer ' + t } });
          const j = await r.json();
          const w = (j.data || {});
          return (w.statusNodeId ?? '') + '|' + (w.title || '');
        }"""
        deadline = time.time() + MAX_WAIT_MIN * 60
        prev_sig = ""
        retried = 0
        keepalive_cycle = 0
        while time.time() < deadline:
            page.wait_for_timeout(20000)
            keepalive_cycle += 1
            try:
                if keepalive_cycle % 3 == 0:
                    # 每 60s 刷新一次,拿最新状态
                    page.reload(wait_until="domcontentloaded")
                    page.wait_for_timeout(2500)
                else:
                    # 每 20s 一次可见交互:滚动到不同区域,让录像持续有帧
                    targets = ["交付进度跟踪", "时间线", "产物"]
                    t = targets[keepalive_cycle % len(targets)]
                    try:
                        page.locator(f"text={t}").first.scroll_into_view_if_needed(timeout=4000)
                    except Exception:
                        try:
                            page.mouse.wheel(0, 300)
                        except Exception:
                            pass
                page.wait_for_timeout(800)
            except Exception as e:
                log(f"刷新异常(继续):{e}")
            try:
                body = page.locator("body").inner_text()
            except Exception:
                body = ""
            sig = "|".join(re.findall(r"(准备执行上下文|执行中|已完成|成功|失败|异常|待评审|代码评审|已发布|验证中|交接)", body))[:200]
            status_m = re.search(r"状态\s*\n\s*(\S+)", body)
            status = status_m.group(1) if status_m else "?"
            try:
                api_status = page.evaluate(async_status_js, wid).split("|")[0]
            except Exception:
                api_status = ""
            if sig != prev_sig or api_status:
                log(f"执行状态:工单状态={status}(api={api_status});面板标记={sig[:120]}")
                prev_sig = sig
            if "失败" in body and ("重试" in body) and retried < 3:
                try:
                    page.locator('button:has-text("重试")').first.click()
                    page.wait_for_timeout(2000)
                    retried += 1
                    log(f"检测到失败,点击重试(第 {retried} 次)")
                    continue
                except Exception:
                    pass
            # 收敛判定:无执行中 且 到达终态
            if ("执行中" not in body) and any(k in body for k in ("已发布", "已完成", "待决策")) and "重试" not in body:
                log(f"执行收敛:工单状态={status}")
                break
        else:
            log(f"!! 等待超时({MAX_WAIT_MIN} 分钟),以当前状态收尾")

        # ── 6. 交付结果检查 ─────────────────────────────────────────
        log("阶段6:检查交付产物与时间线")
        page.wait_for_timeout(1500)
        for label in ("产物", "时间线"):
            try:
                el = page.locator(f'text={label}').first
                el.scroll_into_view_if_needed()
                page.wait_for_timeout(1200)
            except Exception:
                pass
        page.wait_for_timeout(1500)

        # ── 7. 禅道回写验证 ─────────────────────────────────────────
        log("阶段7:切回禅道验证回写")
        zt_page.bring_to_front()
        if story_id:
            zt_page.goto(ZT + f"/story-view-{story_id}.html", wait_until="networkidle")
        zt_page.wait_for_timeout(1800)
        close_zt_promo(zt_page)
        zt_page.wait_for_timeout(2500)
        log("禅道需求页已展示(回写可见)")
        page.bring_to_front()
        page.wait_for_timeout(1500)

        video = page.video
        ctx.close()
        b.close()
        if video:
            path = video.path()
            log(f"录像文件:{path}")
    log("TAKE 结束")


if __name__ == "__main__":
    main()
