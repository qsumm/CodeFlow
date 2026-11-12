#!/usr/bin/env python3
"""AINative 全流程实录录屏(需求开发 → 代码评审 → 缺陷修复 → 测试交付)。

分段录制真实 UI 操作(Playwright video),真实执行等待不录制,后期 ffmpeg 组装。

用法:
  python3 scripts/record_demo_flow.py <stage>
    title      生成标题卡/章节卡 PNG
    s1         登录 → 工作空间 → 工单看板
    s2         禅道需求页 → 平台自动拉取 → 打开工单详情
    s3         启动交付(选小队/Agent)→ 派发执行中
    wait-dev   等待开发派发完成(不录制)
    s4         交付结果:进度跟踪/产物/时间线
    wait-cr    等待代码评审完成(不录制)
    s5         工单完成态 + 禅道回写验证
    s6         禅道建缺陷 → 平台拉取 → 指派修复
    wait-bug   等待缺陷修复完成(不录制)
    s7         缺陷已解决 + 禅道回写
    s8         总览大盘 + 洞察 收尾
    assemble   ffmpeg 组装成片

凭据走环境变量(AINATIVE_ADMIN_PASSWORD / ZENTAO_PASSWORD)。
"""
from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = os.environ.get("AINATIVE_BASE_URL", "http://localhost:7001")
ZT = os.environ.get("ZENTAO_BASE_URL", "http://localhost:8888")
USER = os.environ.get("AINATIVE_ADMIN_USER", "admin")
PASSWORD = os.environ.get("AINATIVE_ADMIN_PASSWORD", "")
ZT_USER = os.environ.get("ZENTAO_ACCOUNT", "zentao-int")
ZT_PASS = os.environ.get("ZENTAO_PASSWORD", "")
ROOT = Path(__file__).resolve().parent.parent
SEG = ROOT / "docs" / "demo" / "segs"
OUT = ROOT / "docs" / "demo"
SEG.mkdir(parents=True, exist_ok=True)
OUT.mkdir(parents=True, exist_ok=True)
STORY_ID = int(os.environ.get("DEMO_STORY_ID", "13"))
BUG_ID = os.environ.get("DEMO_BUG_ID", "")
WORKITEM = {"story": None, "bug": None}


def out(name: str) -> Path:
    return OUT / name


def segs(name: str) -> Path:
    d = SEG / (sys.argv[1] if len(sys.argv) > 1 else name)
    d.mkdir(exist_ok=True)
    return d


def human(page, lo=0.6, hi=1.2):
    import random
    page.wait_for_timeout(int(random.uniform(lo, hi) * 1000))


def new_ctx(browser, record: bool):
    kw = dict(viewport={"width": 1440, "height": 900}, locale="zh-CN")
    if record:
        kw.update(record_video_dir=str(segs(sys.argv[2] if len(sys.argv) > 2 else "misc")),
                  record_video_size={"width": 1440, "height": 900})
    ctx = browser.new_context(**kw)
    page = ctx.new_page()
    page.add_init_script(
        "try{localStorage.setItem('ainative.locale','zh-CN');"
        "localStorage.setItem('ainative.appearance','light');}catch(e){}")
    return ctx, page


def login_platform(page):
    page.goto(BASE + "/login", wait_until="networkidle")
    if "/login" in page.url:
        page.fill('input[placeholder*="用户名"], input[type="text"]', USER)
        human(page)
        page.fill('input[type="password"]', PASSWORD)
        human(page)
        page.click('button[type="submit"]')
        page.wait_for_url(lambda u: "/login" not in u, timeout=30000)
    page.wait_for_load_state("networkidle")


def enter_workspace(page):
    page.goto(BASE + "/workspaces", wait_until="networkidle")
    page.wait_for_timeout(900)
    page.locator("text=演示项目").first.click()
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(800)


def login_zentao(page):
    page.goto(ZT + "/user-login.html", wait_until="networkidle")
    if page.locator('input[name="account"]').count():
        page.fill('input[name="account"]', ZT_USER)
        page.fill('input[name="password"]', ZT_PASS)
        page.click('#submit, button[type="submit"], input[type="submit"]')
        page.wait_for_load_state("networkidle")
    page.wait_for_timeout(900)


def db_q(sql: str) -> str:
    r = subprocess.run(
        ["docker", "exec", "platform-mysql-1", "mysql", "-uainative", "-pchange-me",
         "-N", "-e", sql, "ainative"], capture_output=True, text=True)
    return (r.stdout or "").strip()


def workitem_id_for(external: str) -> int | None:
    row = db_q(f"select workitem_id from external_workitem_link where external_workitem_id='{external}' order by id desc limit 1;")
    return int(row) if row else None


def latest_dispatch(workitem_id: int) -> tuple[int, str]:
    row = db_q(f"select id,status from dispatch where workitem_id={workitem_id} order by id desc limit 1;")
    if not row or "\t" not in row:
        return 0, "NONE"
    i, s = row.split("\t")
    return int(i), s


def wait_terminal(workitem_id: int, timeout_s: int, label: str) -> str:
    print(f"等待 {label} (workitem {workitem_id}) ...", flush=True)
    deadline = time.time() + timeout_s
    last = ""
    while time.time() < deadline:
        _, st = latest_dispatch(workitem_id)
        if st != last:
            print(f"  dispatch status: {st}", flush=True)
            last = st
        if st in ("SUCCEEDED", "FAILED", "CANCELED", "COMPLETED"):
            return st
        time.sleep(20)
    return "TIMEOUT"


def zt_cli(*args: str) -> str:
    env = dict(os.environ,
               AINATIVE_BASE_URL=BASE, AINATIVE_ADMIN_USER=USER, AINATIVE_ADMIN_PASSWORD=PASSWORD,
               ZENTAO_BASE_URL=ZT, ZENTAO_ACCOUNT=ZT_USER, ZENTAO_PASSWORD=ZT_PASS,
               ZENTAO_PRODUCT_ID="1")
    r = subprocess.run([sys.executable, str(ROOT / "scripts" / "zentao_e2e.py"), *args],
                       capture_output=True, text=True, env=env)
    print(r.stdout[-500:], r.stderr[-300:] if r.returncode else "", flush=True)
    return r.stdout


# ── 标题卡 ────────────────────────────────────────────────────────────
def stage_title():
    from PIL import Image, ImageDraw, ImageFont
    W, H = 1440, 900
    BG, FG, ACC, MUT = (14, 15, 20), (240, 242, 246), (70, 140, 255), (120, 128, 140)
    f_brand = ImageFont.truetype("/System/Library/Fonts/Hiragino Sans GB.ttc", 96)
    f_sub = ImageFont.truetype("/System/Library/Fonts/Hiragino Sans GB.ttc", 40)
    f_cap = ImageFont.truetype("/System/Library/Fonts/Hiragino Sans GB.ttc", 34)
    f_chip = ImageFont.truetype("/System/Library/Fonts/Hiragino Sans GB.ttc", 30)

    def base():
        img = Image.new("RGB", (W, H), BG)
        d = ImageDraw.Draw(img)
        d.rectangle([0, H - 6, W, H], fill=ACC)
        return img, d

    def center(d, y, text, font, fill):
        w = d.textlength(text, font=font)
        d.text(((W - w) / 2, y), text, font=font, fill=fill)

    # 主标题卡
    img, d = base()
    center(d, 300, "AINative", f_brand, FG)
    center(d, 430, "数字员工交付平台 · 全流程实录", f_sub, ACC)
    chips = ["需求开发", "代码评审", "缺陷修复", "测试交付"]
    x = W / 2 - (len(chips) * 200 - 60) / 2
    for c in chips:
        d.rounded_rectangle([x, 560, x + 170, 620], 12, outline=MUT, width=2)
        cw = d.textlength(c, font=f_chip)
        d.text((x + (170 - cw) / 2, 572), c, font=f_chip, fill=FG)
        x += 200
    center(d, 760, "禅道接入 · 真实执行 · 结果回写", f_cap, MUT)
    img.save(out("card-title.png"))

    # 章节卡
    chapters = [("01", "需求接入", "禅道建需求,平台自动拉取入库"),
                ("02", "数字员工开发", "澄清对齐 → SDLC 四步 → 独立代码评审"),
                ("03", "缺陷修复", "禅道缺陷自动入库,数字员工修复回写"),
                ("04", "交付与洞察", "全链路留痕,回写可验证")]
    for num, name, desc in chapters:
        img, d = base()
        center(d, 330, num, ImageFont.truetype("/System/Library/Fonts/Hiragino Sans GB.ttc", 120), ACC)
        center(d, 480, name, f_sub, FG)
        center(d, 560, desc, f_cap, MUT)
        img.save(out(f"card-{num}.png"))
    # 结尾卡
    img, d = base()
    center(d, 330, "需求进,交付出", f_sub, FG)
    center(d, 430, "AINative · AI Native 软件需求交付平台", f_cap, ACC)
    center(d, 560, "本片所有执行均由平台数字员工真实完成", f_cap, MUT)
    img.save(out("card-end.png"))
    print("title cards done")


# ── S1 登录与看板 ─────────────────────────────────────────────────────
def stage_s1():
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        ctx, page = new_ctx(b, True)
        page.goto(BASE + "/login", wait_until="networkidle")
        page.wait_for_timeout(1500)
        page.fill('input[placeholder*="用户名"], input[type="text"]', USER)
        page.type('input[placeholder*="用户名"], input[type="text"]', "", delay=40)
        human(page)
        page.fill('input[type="password"]', PASSWORD)
        human(page, 0.8, 1.4)
        page.click('button[type="submit"]')
        page.wait_for_url(lambda u: "/login" not in u, timeout=30000)
        page.wait_for_load_state("networkidle")
        page.wait_for_timeout(1200)
        # 工作空间
        page.goto(BASE + "/workspaces", wait_until="networkidle")
        page.wait_for_timeout(1200)
        page.locator("text=演示项目").first.click()
        page.wait_for_load_state("networkidle")
        page.wait_for_timeout(1500)
        # 总览大盘
        page.goto(BASE + "/workspaces/dashboard", wait_until="networkidle")
        page.wait_for_timeout(2000)
        # 工单看板
        page.goto(BASE + "/workitems", wait_until="networkidle")
        page.wait_for_timeout(2200)
        ctx.close()


# ── S2 需求接入 ───────────────────────────────────────────────────────
def stage_s2():
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        ctx, page = new_ctx(b, True)
        # 禅道:需求列表与详情
        login_zentao(page)
        page.goto(ZT + f"/story-view-{STORY_ID}.html", wait_until="networkidle")
        page.wait_for_timeout(2000)
        page.goto(ZT + "/story-browse-1.html", wait_until="networkidle")
        page.wait_for_timeout(1800)
        # 平台:自动拉取
        login_platform(page)
        enter_workspace(page)
        page.goto(BASE + "/workitems", wait_until="networkidle")
        page.wait_for_timeout(1200)
        card = page.locator("text=发布检查清单文档").first
        for _ in range(20):
            if card.count():
                break
            page.wait_for_timeout(3000)
            page.reload(wait_until="networkidle")
            page.wait_for_timeout(1200)
        page.wait_for_timeout(1500)
        if card.count():
            card.click()
            page.wait_for_load_state("networkidle")
            page.wait_for_timeout(1800)
        ctx.close()
        wid = workitem_id_for(f"story-{STORY_ID}")
        print("workitem:", wid, flush=True)


# ── S3 启动交付 ───────────────────────────────────────────────────────
def stage_s3():
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        ctx, page = new_ctx(b, True)
        login_platform(page)
        enter_workspace(page)
        wid = workitem_id_for(f"story-{STORY_ID}")
        page.goto(BASE + f"/workitems/{wid}", wait_until="networkidle")
        page.wait_for_timeout(1800)
        page.click('button:has-text("启动交付")')
        page.wait_for_timeout(1200)
        # 澄清提醒(首次启动):勾选不再提醒并跳过,进入启动交付弹窗
        remind = page.locator('.ant-modal', has_text='建议先完成需求澄清')
        if remind.count() and remind.first.is_visible():
            try:
                remind.locator('.ant-checkbox-input, input[type=checkbox]').first.check()
            except Exception:
                pass
            human(page)
            remind.locator('button:has-text("跳 过"), button:has-text("跳过")').first.click()
            page.wait_for_timeout(1000)
        # 选小队
        modal = page.locator(".ant-modal:visible")
        modal.locator(".ant-select").first.click()
        page.wait_for_timeout(600)
        page.locator(".ant-select-item-option", has_text="开发与评审小队").first.click()
        human(page)
        # 选 Agent
        modal.locator(".ant-select").nth(1).click()
        page.wait_for_timeout(600)
        page.locator(".ant-select-item-option", has_text="全栈开发").first.click()
        human(page, 0.8, 1.4)
        modal.locator('button:has-text("启动交付"), .ant-btn-primary').first.click()
        page.wait_for_timeout(2500)
        page.wait_for_load_state("networkidle")
        page.wait_for_timeout(3000)
        ctx.close()


# ── S4 交付结果 ───────────────────────────────────────────────────────
def stage_s4():
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        ctx, page = new_ctx(b, True)
        login_platform(page)
        enter_workspace(page)
        wid = workitem_id_for(f"story-{STORY_ID}")
        page.goto(BASE + f"/workitems/{wid}", wait_until="networkidle")
        page.wait_for_timeout(2000)
        # 交付进度跟踪(右栏)
        page.locator("text=交付进度跟踪").first.scroll_into_view_if_needed()
        page.wait_for_timeout(1200)
        # 时间线:交付评论
        page.locator('textarea[placeholder*="输入评论"]').first.scroll_into_view_if_needed()
        page.wait_for_timeout(1200)
        page.mouse.wheel(0, -600)
        page.wait_for_timeout(800)
        ctx.close()


# ── S5 完成态 + 禅道回写 ──────────────────────────────────────────────
def stage_s5():
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        ctx, page = new_ctx(b, True)
        login_platform(page)
        enter_workspace(page)
        wid = workitem_id_for(f"story-{STORY_ID}")
        page.goto(BASE + f"/workitems/{wid}", wait_until="networkidle")
        page.wait_for_timeout(2000)
        page.screenshot(path=str(out("shot-final-story.png")))
        page.goto(BASE + "/workitems", wait_until="networkidle")
        page.wait_for_timeout(1800)
        # 禅道侧:需求详情(keywords 回写)
        login_zentao(page)
        page.goto(ZT + f"/story-view-{STORY_ID}.html", wait_until="networkidle")
        page.wait_for_timeout(2000)
        ctx.close()


# ── S6 缺陷接入与指派 ────────────────────────────────────────────────
def stage_s6():
    global BUG_ID
    if not BUG_ID:
        txt = zt_cli("create-bug", "--title", "发布检查清单缺少回滚章节",
                     "--steps", "docs/release-checklist.md 已创建,但缺少「回滚」章节;发布前检查清单应包含回滚步骤,请补充该章节并复核其余条目。",
                     "--severity", "3")
        for part in txt.replace("'", '"').split():
            if part.startswith('{"id"') or part.startswith('id'):
                pass
        import re
        m = re.search(r'"id":\s*(\d+)', txt)
        BUG_ID = m.group(1) if m else ""
        print("bug id:", BUG_ID, flush=True)
        os.environ["DEMO_BUG_ID"] = BUG_ID
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        ctx, page = new_ctx(b, True)
        login_zentao(page)
        page.goto(ZT + f"/bug-view-{BUG_ID}.html", wait_until="networkidle")
        page.wait_for_timeout(2000)
        login_platform(page)
        enter_workspace(page)
        page.goto(BASE + "/workitems", wait_until="networkidle")
        page.wait_for_timeout(1200)
        card = page.locator("text=发布检查清单缺少回滚章节").first
        for _ in range(20):
            if card.count():
                break
            page.wait_for_timeout(3000)
            page.reload(wait_until="networkidle")
            page.wait_for_timeout(1200)
        if card.count():
            card.click()
            page.wait_for_load_state("networkidle")
            page.wait_for_timeout(1800)
        # 指派修复(同 S3 流程)
        page.click('button:has-text("启动交付")')
        page.wait_for_timeout(1200)
        remind = page.locator('.ant-modal', has_text='建议先完成需求澄清')
        if remind.count() and remind.first.is_visible():
            try:
                remind.locator('.ant-checkbox-input, input[type=checkbox]').first.check()
            except Exception:
                pass
            human(page)
            remind.locator('button:has-text("跳 过"), button:has-text("跳过")').first.click()
            page.wait_for_timeout(1000)
        modal = page.locator(".ant-modal:visible")
        modal.locator(".ant-select").first.click()
        page.wait_for_timeout(600)
        page.locator(".ant-select-item-option", has_text="开发与评审小队").first.click()
        human(page)
        modal.locator(".ant-select").nth(1).click()
        page.wait_for_timeout(600)
        page.locator(".ant-select-item-option", has_text="全栈开发").first.click()
        human(page, 0.8, 1.4)
        modal.locator('button:has-text("启动交付"), .ant-btn-primary').first.click()
        page.wait_for_timeout(2500)
        page.wait_for_load_state("networkidle")
        page.wait_for_timeout(2500)
        ctx.close()
        wid = workitem_id_for(f"bug-{BUG_ID}")
        print("bug workitem:", wid, flush=True)


# ── S7 缺陷解决 + 回写 ────────────────────────────────────────────────
def stage_s7():
    bug_id = os.environ.get("DEMO_BUG_ID", BUG_ID)
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        ctx, page = new_ctx(b, True)
        login_platform(page)
        enter_workspace(page)
        wid = workitem_id_for(f"bug-{bug_id}")
        page.goto(BASE + f"/workitems/{wid}", wait_until="networkidle")
        page.wait_for_timeout(2000)
        page.locator("text=交付进度跟踪").first.scroll_into_view_if_needed()
        page.wait_for_timeout(1200)
        page.goto(BASE + "/workitems", wait_until="networkidle")
        page.wait_for_timeout(1500)
        login_zentao(page)
        page.goto(ZT + f"/bug-view-{bug_id}.html", wait_until="networkidle")
        page.wait_for_timeout(2200)
        page.screenshot(path=str(out("shot-zentao-bug-resolved.png")))
        ctx.close()


# ── S8 收尾 ───────────────────────────────────────────────────────────
def stage_s8():
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        ctx, page = new_ctx(b, True)
        login_platform(page)
        enter_workspace(page)
        page.goto(BASE + "/workspaces/dashboard", wait_until="networkidle")
        page.wait_for_timeout(2000)
        page.goto(BASE + "/insights", wait_until="networkidle")
        page.wait_for_timeout(2200)
        page.goto(BASE + "/audit-logs", wait_until="networkidle")
        page.wait_for_timeout(1800)
        ctx.close()


# ── 组装 ──────────────────────────────────────────────────────────────
def stage_assemble():
    work = OUT / "assemble"
    work.mkdir(exist_ok=True)
    # 1) webm → mp4
    mp4s = []
    for d in sorted(SEG.iterdir()):
        for v in sorted(d.glob("*.webm")):
            o = work / (d.name + ".mp4")
            if not o.exists():
                subprocess.run(["ffmpeg", "-y", "-i", v, "-c:v", "libx264", "-preset", "medium",
                                "-crf", "23", "-pix_fmt", "yuv420p", "-r", "30",
                                "-an", str(o)], check=True, capture_output=True)
            mp4s.append(o)
            break  # 每阶段取一段
    # 2) 标题卡 → mp4
    def card_png_mp4(png, mp4, sec=3):
        subprocess.run(["ffmpeg", "-y", "-loop", "1", "-i", png, "-t", str(sec),
                        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-r", "30", str(mp4)],
                       check=True, capture_output=True)
    order = [out("card-title.png"), "s1", "card-01.png", "s2", "s3",
             "card-02.png", "s4", "card-03.png", "s6", "card-04.png", "s5", "s7", "s8",
             out("card-end.png")]
    parts = []
    for item in order:
        if isinstance(item, Path):
            mp4 = work / (item.stem + ".mp4")
            card_png_mp4(item, mp4, 3 if item.stem == "card-title" else 2.2)
        else:
            mp4 = work / (item + ".mp4")
        parts.append(mp4)
    lst = work / "list.txt"
    lst.write_text("".join(f"file '{p}'\n" for p in parts))
    final = out("AINative-全流程实录.mp4")
    subprocess.run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(lst),
                    "-c:v", "libx264", "-preset", "medium", "-crf", "23",
                    "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(final)],
                   check=True, capture_output=True)
    print("final:", final, final.stat().st_size // 1024, "KB")


def stage_s4a():

    with sync_playwright() as pw:
        b = pw.chromium.launch()
        ctx, page = new_ctx(b, True)
        login_platform(page)
        enter_workspace(page)
        wid = workitem_id_for(f"story-{STORY_ID}")
        page.goto(BASE + f"/workitems/{wid}", wait_until="networkidle")
        page.wait_for_timeout(2000)
        panel = page.locator("text=交付进度跟踪").first
        panel.scroll_into_view_if_needed()
        page.wait_for_timeout(1500)
        retry = page.locator('button:has-text("重试")').first
        if retry.count() and retry.is_visible():
            human(page)
            retry.click()
            page.wait_for_timeout(1500)
            conf = page.locator('.ant-popover .ant-btn-primary, .ant-popconfirm .ant-btn-primary, .ant-modal .ant-btn-primary').first
            if conf.count() and conf.is_visible():
                conf.click()
            page.wait_for_timeout(2500)
        page.wait_for_load_state("networkidle")
        page.wait_for_timeout(2000)
        ctx.close()


STAGES = {n: f for n, f in [
    ("title", stage_title), ("s1", stage_s1), ("s2", stage_s2), ("s3", stage_s3),
    ("s4", stage_s4), ("s5", stage_s5), ("s6", stage_s6), ("s7", stage_s7),
    ("s8", stage_s8), ("s4a", stage_s4a), ("assemble", stage_assemble)]}

if __name__ == "__main__":
    stage = sys.argv[1]
    if stage == "wait-dev":
        wid = workitem_id_for(f"story-{STORY_ID}")
        print("dev result:", wait_terminal(wid, 5400, "开发派发"))
    elif stage == "wait-cr":
        wid = workitem_id_for(f"story-{STORY_ID}")
        print("workitem status:", db_q(f"select status from workitem where id={wid};"))
        print("cr result:", wait_terminal(wid, 3600, "评审派发"))
    elif stage == "wait-bug":
        bug_id = os.environ.get("DEMO_BUG_ID", BUG_ID)
        wid = workitem_id_for(f"bug-{bug_id}")
        print("bug result:", wait_terminal(wid, 5400, "缺陷修复派发"))
    else:
        STAGES[stage]()