#!/usr/bin/env python3
"""用户精华版后期管线:分段裁剪 → 时间跳剪(真实时长角标)→ 中文字幕 → 合成。

输入:realtake/ 下最新一轮 take 的平台页 + 禅道页 webm
输出:docs/demo/CodeFlow-真实需求交付演示-带字幕.mp4
"""
import json, os, subprocess, sys
from pathlib import Path

FF = "/Users/summer/.local/bin/ffmpeg"
ROOT = Path("/Users/summer/CodeFlow/docs/demo")
SRC = ROOT / "realtake"
OUT = ROOT / "user-cut"
OUT.mkdir(exist_ok=True)
FONT = "/System/Library/Fonts/Hiragino Sans GB.ttc"

# ── 1. 分段定义(剪辑决策:事件起止 + 字幕 + 是否跳剪) ──────────────
# 由 real-take-timeline.txt 的时间戳人工/脚本对齐后填入;这里给出骨架,
# 实际秒数以 take 时间线为准 —— build 时用 --timeline 覆盖。
CUTS = json.loads(sys.argv[1]) if len(sys.argv) > 1 else []
if not CUTS:
    print("usage: build_user_cut.py '<json cuts>'")
    sys.exit(1)

def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print("ERR:", " ".join(cmd[:6]), r.stderr[-300:])
    return r

# ── 2. 每段:裁剪 + (可选)2x/4x 加速 + 角标 ────────────────────────
for i, c in enumerate(CUTS):
    src = SRC / c["src"]
    out = OUT / f"seg{i:02d}.mp4"
    speed = c.get("speed", 1)
    badge = c.get("badge", "")
    vf = f"scale=1440:900:force_original_aspect_ratio=decrease,pad=1440:900:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30"
    if speed != 1:
        vf += f",setpts={1/speed:.4f}*PTS"
    # 角标(跳剪提示)drawtext
    if badge:
        esc = badge.replace(":", r"\:").replace(",", r"\,").replace("'", "")
        vf += (f",drawtext=fontfile={FONT}:text='{esc}':fontsize=34:fontcolor=white:"
               f"borderw=2:bordercolor=black@0.6:x=(w-text_w)/2:y=60:enable='between(t,0,99h)'")
    run([FF, "-y", "-ss", str(c["from"]), "-to", str(c["to"]), "-i", str(src),
         "-vf", vf, "-an", "-c:v", "libx264", "-preset", "fast", "-crf", "23", str(out)])
    print(f"seg{i:02d}: {c['from']}s-{c['to']}s x{speed} {badge} → {out.name}")

# ── 3. ASS 字幕(全局时间轴) ────────────────────────────────────────
def ts(sec):
    h = int(sec // 3600); m = int(sec % 3600 // 60); s = sec % 60
    return f"{h}:{m:02d}:{s:05.2f}"

# 逐段时长(经 speed 换算)
durs = []
for c in CUTS:
    raw = c["to"] - c["from"]
    durs.append(raw / c.get("speed", 1))

events = []
offset = 0.0
for i, c in enumerate(CUTS):
    for (a, b, text) in c.get("subs", []):
        events.append((offset + a, offset + min(b, durs[i] - 0.3), text))
    offset += durs[i]

ass = OUT / "subs.ass"
with open(ass, "w", encoding="utf-8") as f:
    f.write(f"""[Script Info]
ScriptType: v4.00+
PlayResX: 1440
PlayResY: 900

[V4+ Styles]
Format: Name, Fontname, Fontfile, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Sub,Hiragino Sans GB,{FONT},34,&H00FFFFFF,&H00FFFFFF,&H90000000,&H7F000000,1,0,0,0,100,100,0,0,3,2,1,2,80,80,34,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
""")
    for a, b, text in events:
        f.write(f"Dialogue: 0,{ts(a)},{ts(b)},Sub,,0,0,0,,{text}\n")
print(f"subs: {len(events)} 条")

# ── 4. 烧字幕逐段重编码(简单可靠:concat 后整体烧)────────────────
concat_list = OUT / "concat.txt"
with open(concat_list, "w") as f:
    for i in range(len(CUTS)):
        f.write(f"file '{OUT}/seg{i:02d}.mp4'\n")
joined = OUT / "joined.mp4"
run([FF, "-y", "-f", "concat", "-safe", "0", "-i", str(concat_list), "-c", "copy", str(joined)])
final = ROOT / "CodeFlow-真实需求交付演示-带字幕.mp4"
run([FF, "-y", "-i", str(joined), "-vf",
     f"subtitles={ass}:fontsdir=/System/Library/Fonts", "-c:v", "libx264",
     "-preset", "medium", "-crf", "23", "-movflags", "+faststart", str(final)])
print("final:", final)
