#!/usr/bin/env python3
"""手册深水区截图:真实数据场景的第二轮采集。"""
from __future__ import annotations
import os, sys, time
from pathlib import Path
from playwright.sync_api import sync_playwright

BASE = os.environ.get("AINATIVE_BASE_URL", "http://localhost:7001")
USER = os.environ.get("AINATIVE_ADMIN_USER", "admin")
PASSWORD = os.environ.get("AINATIVE_ADMIN_PASSWORD", "")
SHOTS = Path(__file__).resolve().parent.parent / "docs" / "images" / "manual"
SHOTS.mkdir(parents=True, exist_ok=True)


def shot(page, name):
    page.wait_for_timeout(700)
    page.screenshot(path=str(SHOTS / f"{name}.png"))
    print(f"  shot {name}.png")


def login(page):
    page.goto(BASE + "/login", wait_until="networkidle")
    if "/login" in page.url:
        page.fill('input[placeholder*="用户名"], input[type="text"]', USER)
        page.fill('input[type="password"]', PASSWORD)
        page.click('button[type="submit"]')
        page.wait_for_url(lambda u: "/login" not in u, timeout=30000)
    page.wait_for_load_state("networkidle")


def switch(page):
    page.goto(BASE + "/workspaces", wait_until="networkidle")
    page.wait_for_timeout(1000)
    card = page.locator("text=演示项目").first
    if card.count():
        card.click()
        page.wait_for_load_state("networkidle")
        page.wait_for_timeout(800)


def main() -> int:
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        ctx = browser.new_context(viewport={"width": 1440, "height": 900}, locale="zh-CN")
        page = ctx.new_page()
        page.add_init_script(
            "try{localStorage.setItem('ainative.locale','zh-CN');"
            "localStorage.setItem('ainative.appearance','light');}catch(e){}")
        login(page); switch(page)

        # 真实工单 10012:详情 + 交付进度跟踪 + 评论时间线
        page.goto(BASE + "/workitems/10012", wait_until="networkidle")
        page.wait_for_timeout(1500)
        shot(page, "40-workitem-detail-real")
        try:
            page.click('text=交付进度跟踪', timeout=3000)
            page.wait_for_timeout(1200)
            shot(page, "41-workitem-delivery-tracking")
        except Exception as e:
            print("  delivery tracking:", str(e)[:80])
        try:
            box = page.locator('textarea[placeholder*="输入评论"]').first
            box.scroll_into_view_if_needed()
            page.wait_for_timeout(500)
            shot(page, "42-workitem-timeline-comments")
        except Exception as e:
            print("  timeline:", str(e)[:80])
        # AI 需求澄清面板
        try:
            page.click('text=AI 需求澄清', timeout=3000)
            page.wait_for_timeout(1200)
            shot(page, "43-workitem-clarify")
        except Exception as e:
            print("  clarify:", str(e)[:80])

        # 小队详情
        page.goto(BASE + "/squads", wait_until="networkidle"); page.wait_for_timeout(1000)
        card = page.locator(".ant-card, [class*=card], tr").filter(has_text="全栈").first
        if card.count():
            card.click(); page.wait_for_load_state("networkidle"); page.wait_for_timeout(1200)
            shot(page, "44-squad-detail")
        else:
            shot(page, "44-squad-detail")

        # SDLC 详情
        page.goto(BASE + "/sdlcs", wait_until="networkidle"); page.wait_for_timeout(1000)
        card = page.locator("text=分析与分支准备").first
        if card.count():
            card.click(); page.wait_for_load_state("networkidle"); page.wait_for_timeout(1200)
        shot(page, "45-sdlc-detail")

        # 数字员工详情
        page.goto(BASE + "/agents", wait_until="networkidle"); page.wait_for_timeout(1000)
        page.goto(BASE + "/agents/10001", wait_until="networkidle"); page.wait_for_timeout(1200)
        shot(page, "46-agent-detail")

        # 执行器(双执行器在线)
        page.goto(BASE + "/executors", wait_until="networkidle"); page.wait_for_timeout(1200)
        shot(page, "47-executors-online")

        # 禅道绑定抽屉 + 测试连接
        page.goto(BASE + "/integrations", wait_until="networkidle"); page.wait_for_timeout(1200)
        try:
            page.click('button:has-text("新建绑定"), button:has-text("添加绑定"), button:has-text("绑定")', timeout=4000)
            page.wait_for_timeout(1000)
            shot(page, "48-zentao-bind-drawer")
            page.keyboard.press("Escape")
            page.wait_for_timeout(500)
        except Exception as e:
            print("  bind drawer:", str(e)[:80])
        try:
            btn = page.locator('button:has-text("测试")').first
            btn.click()
            page.wait_for_timeout(4000)
            shot(page, "49-zentao-test-connection")
        except Exception as e:
            print("  test conn:", str(e)[:80])

        # 禅道来源标签在工单卡上的样子(列表已有)→ 高级筛选
        page.goto(BASE + "/workitems", wait_until="networkidle"); page.wait_for_timeout(1000)
        try:
            page.click('text=高级筛选', timeout=3000)
            page.wait_for_timeout(800)
            shot(page, "50-workitem-advanced-filter")
        except Exception as e:
            print("  adv filter:", str(e)[:80])

        browser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
