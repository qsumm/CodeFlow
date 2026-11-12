#!/usr/bin/env python3
"""手册全量截图重采集(CodeFlow 品牌 + 真实数据)。

覆盖手册 32 张图:封面 → 登录 → 工作空间 → 大盘 → 工单列表/详情/评论/
澄清/高级筛选/重试 → 小队/SDLC/agent/执行器 → 仓库/技能/MCP/集成 →
品牌/审计/洞察/定时任务/环境变量/帮助/回收站 → 暗色大盘。
"""
from __future__ import annotations

import os
import time
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = os.environ.get("AINATIVE_BASE_URL", "http://localhost:7001")
USER = os.environ.get("AINATIVE_ADMIN_USER", "admin")
PASSWORD = os.environ.get("AINATIVE_ADMIN_PASSWORD", "Admin@2026")
SHOTS = Path(__file__).resolve().parent.parent / "docs" / "images" / "manual"
SHOTS.mkdir(parents=True, exist_ok=True)


def shot(page, name):
    page.wait_for_timeout(800)
    page.screenshot(path=str(SHOTS / f"{name}.png"))
    print(f"  ✓ {name}.png", flush=True)


def login(page):
    page.goto(BASE + "/login", wait_until="networkidle")
    page.wait_for_timeout(1000)
    page.fill('input[placeholder*="用户名"], input[type="text"]', USER)
    page.fill('input[type="password"]', PASSWORD)
    shot(page, "01-login")
    page.click('button[type="submit"]')
    page.wait_for_url(lambda u: "/login" not in u, timeout=30000)
    page.wait_for_load_state("networkidle")


def switch(page):
    page.goto(BASE + "/workspaces", wait_until="networkidle")
    page.wait_for_timeout(1200)
    shot(page, "03-workspaces")
    page.locator("text=演示项目").first.click()
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(800)


def safe(page, fn, label):
    try:
        fn()
    except Exception as e:
        print(f"  ✗ {label}: {str(e)[:90]}", flush=True)


def main() -> int:
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        ctx = browser.new_context(viewport={"width": 1440, "height": 900}, locale="zh-CN")
        page = ctx.new_page()
        page.set_default_timeout(30000)
        page.add_init_script(
            "try{localStorage.setItem('ainative.locale','zh-CN');"
            "localStorage.setItem('ainative.appearance','light');}catch(e){}")

        login(page)
        switch(page)

        # 大盘
        page.goto(BASE + "/workspaces/dashboard", wait_until="networkidle")
        page.wait_for_timeout(1800)
        shot(page, "05-dashboard")

        # 工单列表(有数据 + 外部来源标签)
        page.goto(BASE + "/workitems", wait_until="networkidle")
        page.wait_for_timeout(1800)
        shot(page, "06-workitem-list")

        # 高级筛选
        safe(page, lambda: (
            page.click('text=高级筛选', timeout=4000),
            page.wait_for_timeout(900),
            shot(page, "50-workitem-advanced-filter")), "高级筛选")
        try:
            page.keyboard.press("Escape")
        except Exception:
            pass

        # 工单详情(选已交付完成的 10022,数据最全)
        page.goto(BASE + "/workitems/10022", wait_until="networkidle")
        page.wait_for_timeout(2000)
        shot(page, "08-workitem-detail")
        shot(page, "40-workitem-detail-real")

        safe(page, lambda: (
            page.locator('text=交付进度跟踪').first.scroll_into_view_if_needed(),
            page.wait_for_timeout(900),
            shot(page, "41-workitem-delivery-tracking")), "交付进度跟踪")

        safe(page, lambda: (
            page.locator('textarea[placeholder*="评论"], textarea[placeholder*="输入评论"]').first.scroll_into_view_if_needed(),
            page.wait_for_timeout(700),
            shot(page, "08b-workitem-comment")), "评论区")

        safe(page, lambda: (
            page.click('text=AI 需求澄清', timeout=4000),
            page.wait_for_timeout(1300),
            shot(page, "43-workitem-clarify")), "澄清面板")

        # 派发重试镜头(历史派发列表/重试按钮)
        safe(page, lambda: (
            page.locator('text=重试').first.scroll_into_view_if_needed(),
            page.wait_for_timeout(700),
            shot(page, "10-dispatch-retry")), "派发重试")

        # 小队列表 + 详情
        page.goto(BASE + "/squads", wait_until="networkidle")
        page.wait_for_timeout(1400)
        shot(page, "10b-squads")
        safe(page, lambda: (
            page.locator('tr, .ant-card').filter(has_text="开发与评审").first.click(),
            page.wait_for_load_state("networkidle"),
            page.wait_for_timeout(1400),
            shot(page, "44-squad-detail")), "小队详情")

        # SDLC 列表 + 详情 + AI 生成入口
        page.goto(BASE + "/sdlcs", wait_until="networkidle")
        page.wait_for_timeout(1400)
        shot(page, "11-sdlcs")
        safe(page, lambda: (
            page.locator('text=分析与分支准备').first.click(),
            page.wait_for_load_state("networkidle"),
            page.wait_for_timeout(1400),
            shot(page, "45-sdlc-detail")), "SDLC 详情")
        page.goto(BASE + "/sdlcs", wait_until="networkidle")
        page.wait_for_timeout(1000)
        safe(page, lambda: (
            page.locator('button:has-text("AI 生成"), button:has-text("生成 SDLC")').first.click(),
            page.wait_for_timeout(1300),
            shot(page, "59-sdlc-generate"),
            page.keyboard.press("Escape")), "SDLC AI 生成")

        # 数字员工列表 + 详情
        page.goto(BASE + "/agents", wait_until="networkidle")
        page.wait_for_timeout(1400)
        shot(page, "12-agents")
        page.goto(BASE + "/agents/10001", wait_until="networkidle")
        page.wait_for_timeout(1500)
        shot(page, "46-agent-detail")

        # 执行器
        page.goto(BASE + "/executors", wait_until="networkidle")
        page.wait_for_timeout(1500)
        shot(page, "47-executors-online")

        # 仓库
        page.goto(BASE + "/repos", wait_until="networkidle")
        page.wait_for_timeout(1400)
        shot(page, "14-repos")

        # 技能
        page.goto(BASE + "/skills", wait_until="networkidle")
        page.wait_for_timeout(1400)
        shot(page, "15-skills")

        # MCP 令牌
        page.goto(BASE + "/profile/settings?tab=mcp", wait_until="networkidle")
        page.wait_for_timeout(1500)
        shot(page, "16-mcp-tokens")

        # 集成:禅道绑定 + 渠道
        page.goto(BASE + "/integrations", wait_until="networkidle")
        page.wait_for_timeout(1500)
        shot(page, "17-integrations-zentao")
        safe(page, lambda: (
            page.click('button:has-text("测试")', timeout=4000),
            page.wait_for_timeout(3500),
            shot(page, "49-zentao-test-connection")), "禅道测试连接")
        page.goto(BASE + "/integrations/channels", wait_until="networkidle")
        page.wait_for_timeout(1400)
        shot(page, "18-integrations-channels")

        # 品牌配置
        page.goto(BASE + "/workspaces/branding", wait_until="networkidle")
        page.wait_for_timeout(1500)
        shot(page, "19-branding")

        # 审计日志
        page.goto(BASE + "/audit-logs", wait_until="networkidle")
        page.wait_for_timeout(1500)
        shot(page, "20-audit")

        # 数据洞察
        page.goto(BASE + "/insights", wait_until="networkidle")
        page.wait_for_timeout(1800)
        shot(page, "21-insights")

        # 定时任务
        page.goto(BASE + "/scheduled-tasks", wait_until="networkidle")
        page.wait_for_timeout(1500)
        shot(page, "22-scheduled-tasks")

        # 环境变量
        page.goto(BASE + "/settings/environment-variables", wait_until="networkidle")
        page.wait_for_timeout(1400)
        shot(page, "24-env-vars")

        # 帮助中心
        page.goto(BASE + "/help", wait_until="networkidle")
        page.wait_for_timeout(1500)
        shot(page, "26-help")

        # 回收站
        page.goto(BASE + "/workitems/recycled", wait_until="networkidle")
        page.wait_for_timeout(1500)
        shot(page, "31-recycle-bin")

        # 暗色大盘
        page.add_init_script("try{localStorage.setItem('ainative.appearance','dark');}catch(e){}")
        page.goto(BASE + "/workspaces/dashboard", wait_until="networkidle")
        page.evaluate("try{localStorage.setItem('ainative.appearance','dark')}catch(e){}")
        page.reload(wait_until="networkidle")
        page.wait_for_timeout(1800)
        shot(page, "32-dashboard-dark")

        browser.close()
    print("done")
    return 0


if __name__ == "__main__":
    sys.exit(main()) if (sys := __import__("sys")) else None
