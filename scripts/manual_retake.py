#!/usr/bin/env python3
"""手册补拍:小队详情、执行器展开、禅道绑定列表区。"""
from __future__ import annotations
import os, sys
from pathlib import Path
from playwright.sync_api import sync_playwright

BASE = os.environ.get("AINATIVE_BASE_URL", "http://localhost:7001")
USER = os.environ.get("AINATIVE_ADMIN_USER", "admin")
PASSWORD = os.environ.get("AINATIVE_ADMIN_PASSWORD", "")
SHOTS = Path("/Users/summer/AINative/docs/images/manual")
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

        # 小队详情:数字员工管理 → 小队 → 详情
        page.goto(BASE + "/agents", wait_until="networkidle"); page.wait_for_timeout(1000)
        try:
            page.click('text=小队', timeout=3000); page.wait_for_timeout(800)
            page.click('a:has-text("详情"), button:has-text("详情"), text=详情', timeout=4000)
            page.wait_for_load_state("networkidle"); page.wait_for_timeout(1200)
        except Exception as e:
            print("  squad detail nav:", str(e)[:80])
        shot(page, "44-squad-detail")

        # 执行器:展开小队分组
        page.goto(BASE + "/executors", wait_until="networkidle"); page.wait_for_timeout(1200)
        try:
            page.click('text=开发与评审小队', timeout=4000)
            page.wait_for_timeout(1200)
        except Exception as e:
            print("  executor expand:", str(e)[:80])
        shot(page, "47-executors-online")

        # 禅道:滚动到已建绑定,干净截图
        page.goto(BASE + "/integrations", wait_until="networkidle"); page.wait_for_timeout(1200)
        try:
            page.locator('text=已建绑定').first.scroll_into_view_if_needed()
            page.wait_for_timeout(600)
            page.evaluate("window.scrollBy(0, -80)")
            page.wait_for_timeout(400)
        except Exception as e:
            print("  scroll:", str(e)[:80])
        shot(page, "49-zentao-bindings")

        browser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
