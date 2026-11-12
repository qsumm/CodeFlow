#!/usr/bin/env python3
"""重截帮助中心配图(旧截图是上游阿里橙 UI,含旧品牌痕迹)。

场景(与 help/content*.json 的引用一一对应):
  02  个人中心 → MCP 令牌           /profile/settings?tab=mcp
  06  工单澄清对话                 /workitems/10003
  09  启动交付                     /workitems/10007
  14  平台配置 → 消息渠道(项目机器人) /platform/branding

每场景 × {zh-CN,en-US} × {light,dark} 共 8 张,覆盖 public/help/assets/。
凭据走环境变量,不写入仓库。
"""
from __future__ import annotations

import os
import sys
import time
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = os.environ.get("AINATIVE_BASE_URL", "http://localhost:7001")
USER = os.environ.get("AINATIVE_ADMIN_USER", "admin")
PASSWORD = os.environ.get("AINATIVE_ADMIN_PASSWORD", "")
ASSETS = Path(__file__).resolve().parent.parent / "platform/ainative/frontend/public/help/assets"

SCENES = [
    ("02", "/profile/settings?tab=mcp", "mcp"),
    ("06", "/workitems/10003", "workitem"),
    ("09", "/workitems/10007", "workitem"),
    ("14", "/workspaces/branding", "platform"),
]


WORKSPACE_ID = os.environ.get("AINATIVE_WORKSPACE_ID", "10000")


def switch_workspace(page):
    """切到工作空间上下文(否则 /workitems/:id 会被重定向到空间选择页)。

    走 UI 点击(空间卡片)而不是裸 API:平台用 Bearer 头鉴权,页面请求上下文里
    才带得上 token。
    """
    page.goto(f"{BASE}/workspaces", wait_until="networkidle")
    time.sleep(1.2)
    card = page.locator("text=演示项目").first
    if card.count():
        card.click()
        page.wait_for_url(lambda u: "/workspaces" not in u, timeout=20000)
        page.wait_for_load_state("networkidle")
        print("  workspace switched via UI")


def login(page):
    page.goto(f"{BASE}/login", wait_until="networkidle")
    if "/login" not in page.url:
        return
    page.fill('input[placeholder*="用户名"], input[name="username"], input[type="text"]', USER)
    page.fill('input[type="password"]', PASSWORD)
    page.click('button[type="submit"]')
    page.wait_for_url(lambda u: "/login" not in u, timeout=30000)
    page.wait_for_load_state("networkidle")


def shot(page, name):
    page.wait_for_timeout(1200)
    page.screenshot(path=str(ASSETS / name), full_page=False)
    print(f"  saved {name}")


def main() -> int:
    if not PASSWORD:
        print("need AINATIVE_ADMIN_PASSWORD", file=sys.stderr)
        return 2
    ASSETS.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        for locale in ("zh-CN", "en-US"):
            for mode in ("light", "dark"):
                ctx = browser.new_context(viewport={"width": 1440, "height": 900},
                                        locale="zh-CN",
                                        color_scheme="dark" if mode == "dark" else "light")
                page = ctx.new_page()
                page.add_init_script(
                    f"try {{ localStorage.setItem('aw.locale', '{locale}');"
                    f" localStorage.setItem('ainative.appearance', '{mode}'); }} catch (e) {{}}")
                page.goto(f"{BASE}/login", wait_until="domcontentloaded")
                login(page)
                switch_workspace(page)
                for scene, path, kind in SCENES:
                    page.goto(BASE + path, wait_until="networkidle")
                    time.sleep(1.5)
                    if kind == "mcp":
                        # 个人中心的 MCP 令牌页签
                        for label in ("MCP", "MCP 令牌", "MCP Tokens"):
                            if page.get_by_role("tab", name=label).count():
                                page.get_by_role("tab", name=label).first.click()
                                break
                        page.wait_for_timeout(800)
                    if kind == "platform":
                        # 平台配置 → 消息渠道(项目机器人配置)
                        for label in ("消息渠道", "Message channels"):
                            if page.get_by_role("tab", name=label).count():
                                page.get_by_role("tab", name=label).first.click()
                                break
                        page.wait_for_timeout(1200)
                    shot(page, f"{scene}-{locale}-{mode}.png")
                ctx.close()
        browser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
