#!/usr/bin/env python3
"""UI 全量回归 + 用户操作手册配图采集(一体脚本)。

对 AINative 平台逐页面、逐关键交互做浏览器级回归,每个 case 记录 PASS/FAIL,
同时在合适的时机截图到 docs/images/manual/ 供《用户操作手册》引用。

凭据走环境变量,不写入仓库:
  AINATIVE_BASE_URL / AINATIVE_ADMIN_USER / AINATIVE_ADMIN_PASSWORD
"""
from __future__ import annotations

import os
import sys
import time
from pathlib import Path

from playwright.sync_api import sync_playwright, expect

BASE = os.environ.get("AINATIVE_BASE_URL", "http://localhost:7001")
USER = os.environ.get("AINATIVE_ADMIN_USER", "admin")
PASSWORD = os.environ.get("AINATIVE_ADMIN_PASSWORD", "")
ROOT = Path(__file__).resolve().parent.parent
SHOTS = ROOT / "docs" / "images" / "manual"
SHOTS.mkdir(parents=True, exist_ok=True)

RESULTS: list[tuple[str, str, str]] = []  # (case, verdict, detail)


def record(name: str, ok: bool, detail: str = "") -> None:
    RESULTS.append((name, "PASS" if ok else "FAIL", detail))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail and not ok else ""))


def shot(page, name: str) -> None:
    page.wait_for_timeout(600)
    page.screenshot(path=str(SHOTS / f"{name}.png"), full_page=False)
    print(f"    shot: {name}.png")


def goto(page, path: str, wait: float = 1.2):
    page.goto(BASE + path, wait_until="networkidle")
    page.wait_for_timeout(int(wait * 1000))


def no_error_boundary(page) -> bool:
    """页面没有白屏/错误边界。"""
    body = page.locator("body").inner_text()
    bad = ["Application error", "ChunkLoadError", "Oops", "页面崩溃", "渲染失败"]
    return not any(b in body for b in bad)


def login(page) -> None:
    page.goto(BASE + "/login", wait_until="networkidle")
    if "/login" not in page.url:
        return
    page.fill('input[placeholder*="用户名"], input[name="username"], input[type="text"]', USER)
    page.fill('input[type="password"]', PASSWORD)
    page.click('button[type="submit"]')
    page.wait_for_url(lambda u: "/login" not in u, timeout=30000)
    page.wait_for_load_state("networkidle")


def switch_workspace(page) -> None:
    page.goto(BASE + "/workspaces", wait_until="networkidle")
    page.wait_for_timeout(1200)
    card = page.locator("text=演示项目").first
    if card.count():
        card.click()
        page.wait_for_url(lambda u: "/workspaces" not in u or "/workspaces/dashboard" in u, timeout=20000)
        page.wait_for_load_state("networkidle")
        page.wait_for_timeout(800)


def visit(page, path: str, must_contain: list[str], shot_name: str | None = None) -> None:
    try:
        goto(page, path)
        body = page.locator("body").inner_text()
        ok = no_error_boundary(page) and any(m in body for m in must_contain)
        record(f"页面渲染 {path}", ok, "缺关键词或白屏: " + " / ".join(must_contain))
        if shot_name and ok:
            shot(page, shot_name)
    except Exception as e:  # noqa: BLE001
        record(f"页面渲染 {path}", False, str(e)[:150])


def main() -> int:
    if not PASSWORD:
        print("need AINATIVE_ADMIN_PASSWORD", file=sys.stderr)
        return 2
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        ctx = browser.new_context(viewport={"width": 1440, "height": 900}, locale="zh-CN")
        page = ctx.new_page()
        page.add_init_script(
            "try { localStorage.setItem('ainative.locale','zh-CN');"
            " localStorage.setItem('ainative.appearance','light'); } catch (e) {}")

        # ── 01 登录页 ────────────────────────────────────────────────
        try:
            page.goto(BASE + "/login", wait_until="networkidle")
            page.wait_for_timeout(1000)
            ok = page.locator('input[type="password"]').count() > 0 and no_error_boundary(page)
            record("01 登录页渲染", ok)
            shot(page, "01-login")
        except Exception as e:  # noqa: BLE001
            record("01 登录页渲染", False, str(e)[:150])

        # ── 02 登录 ─────────────────────────────────────────────────
        try:
            login(page)
            record("02 登录成功", "/login" not in page.url, page.url)
        except Exception as e:  # noqa: BLE001
            record("02 登录成功", False, str(e)[:150])

        # ── 03 工作空间列表与切换 ─────────────────────────────────────
        try:
            goto(page, "/workspaces")
            body = page.locator("body").inner_text()
            ok = "演示项目" in body and no_error_boundary(page)
            record("03 工作空间列表", ok)
            shot(page, "03-workspaces")
            switch_workspace(page)
            record("04 切换工作空间", "/login" not in page.url, page.url)
        except Exception as e:  # noqa: BLE001
            record("03 工作空间列表", False, str(e)[:150])

        # ── 05 总览大盘 ─────────────────────────────────────────────
        visit(page, "/workspaces/dashboard", ["工单", "交付"], "05-dashboard")

        # ── 06 工单列表 ─────────────────────────────────────────────
        visit(page, "/workitems", ["工单"], "06-workitem-list")

        # ── 07 新建工单(全流程:创建→列表可见→详情可开) ────────────────
        title = f"UI回归-验证工单-{int(time.time()) % 100000}"
        try:
            goto(page, "/workitems/new")
            page.wait_for_timeout(800)
            page.fill('input[placeholder*="标题"], input[id="title"]', title)
            page.fill('textarea[placeholder*="描述"], textarea[id="contentMd"], textarea',
                      "UI 回归自动化创建的验证工单,验证后会被移入回收站。")
            page.click('button[type="submit"]')
            page.wait_for_url(lambda u: "/workitems/new" not in u, timeout=20000)
            page.wait_for_load_state("networkidle")
            record("07 新建工单", True, page.url)
            shot(page, "07-workitem-detail-created")
        except Exception as e:  # noqa: BLE001
            record("07 新建工单", False, str(e)[:200])

        created_id = None
        m = [seg for seg in page.url.split("/") if seg.isdigit()]
        if m:
            created_id = m[-1]

        # ── 08 工单详情(评论/时间线/元信息) ───────────────────────────
        try:
            target = created_id or "10012"
            goto(page, f"/workitems/{target}")
            body = page.locator("body").inner_text()
            ok = no_error_boundary(page) and ("评论" in body or "时间线" in body or title in body)
            record("08 工单详情渲染", ok)
            shot(page, "08-workitem-detail")
            # 发评论
            box = page.locator('textarea[placeholder*="输入评论"]').first
            if box.count():
                box.fill("UI 回归验证评论(自动,稍后清理)")
                page.click('button:has-text("发送")')
                page.wait_for_timeout(1500)
                body = page.locator("body").inner_text()
                record("09 发表评论", "UI 回归验证评论" in body)
            else:
                record("09 发表评论", False, "找不到评论输入框")
            shot(page, "08b-workitem-comment")
        except Exception as e:  # noqa: BLE001
            record("08 工单详情渲染", False, str(e)[:200])

        # ── 10~26 各功能页渲染 ──────────────────────────────────────
        visit(page, "/squads", ["小队"], "10-squads")
        visit(page, "/sdlcs", ["SDLC"], "11-sdlcs")
        visit(page, "/agents", ["数字员工"], "12-agents")
        visit(page, "/executors", ["执行器"], "13-executors")
        visit(page, "/repos", ["仓库"], "14-repos")
        visit(page, "/skills", ["技能"], "15-skills")
        visit(page, "/profile/settings?tab=mcp", ["MCP"], "16-mcp-tokens")
        visit(page, "/integrations", ["禅道", "平台集成"], "17-integrations-zentao")
        visit(page, "/integrations/channels", ["渠道", "消息"], "18-integrations-channels")
        visit(page, "/workspaces/branding", ["品牌", "平台配置"], "19-branding")
        visit(page, "/audit-logs", ["审计"], "20-audit")
        visit(page, "/insights", ["洞察", "交付"], "21-insights")
        visit(page, "/scheduled-tasks", ["定时任务"], "22-scheduled-tasks")
        visit(page, "/settings/members", ["成员"], "23-members")
        visit(page, "/settings/environment-variables", ["环境变量"], "24-env-vars")
        visit(page, "/status-templates", ["状态"], "25-status-templates")
        visit(page, "/help", ["帮助"], "26-help")
        visit(page, "/notifications", ["通知"], "27-notifications")
        visit(page, "/memories", ["记忆"], "28-memories")

        # ── 禅道绑定测试连接按钮存在且可点(只读校验) ────────────────────
        try:
            goto(page, "/integrations")
            btn = page.locator('button:has-text("测试")').first
            record("29 禅道绑定-测试连接入口", btn.count() > 0)
        except Exception as e:  # noqa: BLE001
            record("29 禅道绑定-测试连接入口", False, str(e)[:150])

        # ── 回收站 + 删除清理 ───────────────────────────────────────
        try:
            if created_id:
                goto(page, f"/workitems/{created_id}")
                page.wait_for_timeout(800)
                del_btn = page.locator('button:has-text("删除"), [data-testid*="delete"]').first
                if del_btn.count():
                    del_btn.click()
                    page.wait_for_timeout(600)
                    confirm = page.locator('.ant-popconfirm button:has-text("确"), .ant-modal button:has-text("确"), button:has-text("确定")').first
                    if confirm.count():
                        confirm.click()
                    page.wait_for_timeout(1500)
                    record("30 删除工单(入回收站)", True)
            goto(page, "/workitems/recycled")
            body = page.locator("body").inner_text()
            record("31 回收站可见", no_error_boundary(page))
            shot(page, "31-recycle-bin")
        except Exception as e:  # noqa: BLE001
            record("30/31 回收站", False, str(e)[:150])

        # ── 暗色主题抽查 ────────────────────────────────────────────
        try:
            ctx2 = browser.new_context(viewport={"width": 1440, "height": 900}, locale="zh-CN",
                                       color_scheme="dark")
            p2 = ctx2.new_page()
            p2.add_init_script(
                "try { localStorage.setItem('ainative.locale','zh-CN');"
                " localStorage.setItem('ainative.appearance','dark'); } catch (e) {}")
            login(p2)
            switch_workspace(p2)
            goto(p2, "/workspaces/dashboard")
            record("32 暗色主题渲染", no_error_boundary(p2))
            shot(p2, "32-dashboard-dark")
            ctx2.close()
        except Exception as e:  # noqa: BLE001
            record("32 暗色主题渲染", False, str(e)[:150])

        browser.close()

    fails = [r for r in RESULTS if r[1] == "FAIL"]
    print(f"\n===== 汇总: {len(RESULTS) - len(fails)}/{len(RESULTS)} PASS, {len(fails)} FAIL =====")
    for name, verdict, detail in fails:
        print(f"FAIL {name}: {detail}")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
