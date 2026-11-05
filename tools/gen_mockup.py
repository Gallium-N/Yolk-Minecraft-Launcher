#!/usr/bin/env python3
"""生成 YMCL 四个页面的 SVG 稿（亮/暗各一份）+ 预览 HTML。
用法: python3 gen_mockup.py <tokens目录> <输出目录>
"""
import json, sys, os

TOK_DIR = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/.hermes/cache/scratch/ymcl")
OUT_DIR = sys.argv[2] if len(sys.argv) > 2 else os.path.expanduser("~/.hermes/cache/scratch/ymcl/out")

W, H = 1280, 800
RAIL = 88
PAD = 24
CX = RAIL + PAD          # 112
CW = W - CX - PAD        # 1144


def set_canvas(w, h):
    """切换画布尺寸；页面函数读取全局 W/H/CX/CW。"""
    global W, H, CX, CW
    W, H = w, h
    CX = RAIL + PAD
    CW = W - CX - PAD


def clamp(lo, hi, v):
    return max(lo, min(hi, v))
FONT = "'Noto Sans CJK SC','Source Han Sans SC','Inter',sans-serif"
MONO = "'JetBrains Mono','DejaVu Sans Mono',monospace"


def load(mode):
    for name in (f"{mode}.json", f"ymcl-{mode}.json"):
        p = os.path.join(TOK_DIR, name)
        if os.path.exists(p):
            d = json.load(open(p, encoding="utf-8"))
            return {k: v["$value"] for k, v in d["color"].items()}
    raise SystemExit(f"找不到 {mode} 的 token 文件：{TOK_DIR}/[{ 'ymcl-' }]{mode}.json")


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def txt(x, y, s, size, fill, weight=400, anchor="start", opacity=1.0, ls="0", family=None):
    return (f'<text x="{x}" y="{y}" font-family="{family or FONT}" font-size="{size}" font-weight="{weight}" '
            f'fill="{fill}" text-anchor="{anchor}" letter-spacing="{ls}" opacity="{opacity}">{esc(s)}</text>')


def mono(x, y, s, size, fill, weight=400, anchor="start", opacity=1.0):
    return txt(x, y, s, size, fill, weight, anchor, opacity, "0", MONO)


def rect(x, y, w, h, r, fill, opacity=1.0):
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{fill}" opacity="{opacity}"/>'


def icon(kind, cx, cy, color):
    if kind == "play":
        return (f'<path d="M {cx-7} {cy-10} L {cx+11} {cy} L {cx-7} {cy+10} Z" fill="{color}" '
                f'stroke="{color}" stroke-width="3" stroke-linejoin="round"/>')
    if kind == "stack":
        return (f'<g fill="none" stroke="{color}" stroke-width="2" stroke-linejoin="round">'
                f'<rect x="{cx-10}" y="{cy-9}" width="20" height="13" rx="3"/>'
                f'<path d="M {cx-7} {cy+4} v 4 a 3 3 0 0 0 3 3 h 8 a 3 3 0 0 0 3 -3 v -4" /></g>')
    if kind == "pulse":
        return (f'<path d="M {cx-11} {cy+1} h 5 l 3 -8 l 4 15 l 3 -7 h 7" fill="none" stroke="{color}" '
                f'stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>')
    if kind == "gear":
        teeth = "".join(
            f'<line x1="{cx}" y1="{cy-6.5}" x2="{cx}" y2="{cy-9.5}" transform="rotate({a} {cx} {cy})"/>'
            for a in range(0, 360, 45))
        return (f'<g stroke="{color}" stroke-linecap="round" fill="none">'
                f'<circle cx="{cx}" cy="{cy}" r="5.5" stroke-width="2.2"/>{teeth}</g>')
    if kind == "download":
        return (f'<g stroke="{color}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" fill="none">'
                f'<path d="M {cx} {cy-10} V {cy+3}"/><path d="M {cx-5.5} {cy-2.5} L {cx} {cy+3} L {cx+5.5} {cy-2.5}"/>'
                f'<path d="M {cx-9} {cy+8} H {cx+9}"/></g>')
    if kind == "grid":
        cells = [(cx-9.5, cy-9.5), (cx+1.5, cy-9.5), (cx-9.5, cy+1.5), (cx+1.5, cy+1.5)]
        return "".join(f'<rect x="{gx}" y="{gy}" width="8" height="8" rx="2" fill="{color}"/>' for gx, gy in cells)
    if kind == "folder":
        return (f'<path d="M {cx-9.5} {cy+7.5} V {cy-5.5} a 1.6 1.6 0 0 1 1.6 -1.6 h 5.4 l 2.6 3 h 8.3 '
                f'a 1.6 1.6 0 0 1 1.6 1.6 v 10 a 1.6 1.6 0 0 1 -1.6 1.6 h -16.3 a 1.6 1.6 0 0 1 -1.6 -1.6 Z" '
                f'fill="none" stroke="{color}" stroke-width="2" stroke-linejoin="round"/>')
    if kind == "package":
        return (f'<g stroke="{color}" stroke-width="2" stroke-linejoin="round" fill="none">'
                f'<rect x="{cx-9}" y="{cy-8}" width="18" height="16" rx="2.5"/>'
                f'<path d="M {cx-9} {cy-2} H {cx+9}"/><path d="M {cx} {cy-8} V {cy-2}"/></g>')
    if kind == "dots":
        return "".join(f'<circle cx="{cx}" cy="{cy + dy}" r="1.7" fill="{color}"/>' for dy in (-7, 0, 7))
    if kind == "search":
        return (f'<g stroke="{color}" stroke-width="2" fill="none" stroke-linecap="round">'
                f'<circle cx="{cx-1}" cy="{cy-1}" r="6.5"/><path d="M {cx+4} {cy+4} L {cx+9} {cy+9}"/></g>')
    if kind == "plus":
        return (f'<g stroke="{color}" stroke-width="2.2" stroke-linecap="round">'
                f'<path d="M {cx-8} {cy} H {cx+8}"/><path d="M {cx} {cy-8} V {cy+8}"/></g>')
    return ""


def chevron(cx, cy, color, size=5):
    return (f'<path d="M {cx-size/2} {cy-size*1.2} L {cx+size/2} {cy} L {cx-size/2} {cy+size*1.2}" '
            f'fill="none" stroke="{color}" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>')


def chip(x, y, w, label, tok, fg="onSecondaryContainer", bg="secondaryContainer", h=26, size=11):
    return (rect(x, y, w, h, h / 2, tok[bg]) +
            txt(x + w / 2, y + h / 2 + 4, label, size, tok[fg], 500, "middle"))


def nav_item(x, y, kind, label, tok, selected):
    bg = rect(x, y, 64, 64, 16, tok["secondaryContainer"]) if selected else ""
    ic = tok["onSecondaryContainer"] if selected else tok["onSurfaceVariant"]
    lc = tok["onSurface"] if selected else tok["onSurfaceVariant"]
    return f'<g name="rail/{label}">{bg}{icon(kind, x+32, y+24, ic)}{txt(x+32, y+54, label, 11, lc, 500, "middle")}</g>'


def rail(t, active):
    g = ['<g name="rail">', rect(0, 0, RAIL, H, 0, t["surfaceContainer"]),
         f'<circle cx="44" cy="44" r="20" fill="{t["primary"]}"/>',
         txt(44, 51, "Y", 18, t["onPrimary"], 700, "middle")]
    items = [("play", "启动台"), ("stack", "实例"), ("pulse", "诊断"), ("gear", "设置")]
    for i, (k, label) in enumerate(items):
        g.append(nav_item(12, 96 + i * 72, k, label, t, i == active))
    g.append("</g>")
    return "".join(g)


def topbar(t, title, meta):
    return (txt(CX, 54, title, 22, t["onSurface"], 600) +
            txt(W - PAD, 54, meta, 12, t["onSurfaceVariant"], 400, "end"))


def switch_(x, y, on, tok):
    if on:
        return (rect(x, y, 44, 24, 12, tok["primary"]) +
                f'<circle cx="{x+32}" cy="{y+12}" r="9" fill="{tok["onPrimary"]}"/>')
    return (f'<rect x="{x}" y="{y}" width="44" height="24" rx="12" fill="{tok["surfaceContainerHighest"]}" '
            f'stroke="{tok["outline"]}" stroke-opacity="0.6"/>'
            f'<circle cx="{x+12}" cy="{y+12}" r="9" fill="{tok["outline"]}"/>')


def segmented(x, y, w, h, labels, active, tok):
    seg_w = w / len(labels)
    out = [rect(x, y, w, h, h / 2, tok["surfaceContainerHigh"])]
    for i, lab in enumerate(labels):
        sx = x + i * seg_w
        if i == active:
            out.append(rect(sx + 3, y + 3, seg_w - 6, h - 6, (h - 6) / 2, tok["secondaryContainer"]))
        out.append(txt(sx + seg_w / 2, y + h / 2 + 4, lab, 12,
                       tok["onSecondaryContainer"] if i == active else tok["onSurfaceVariant"], 500, "middle"))
    return "".join(out)


def card(x, y, w, h, name, sub, last, tok):
    return (f'<g name="card/{name}">{rect(x, y, w, h, 24, tok["surfaceContainerLow"])}'
            f'{txt(x+24, y+52, name, 16, tok["onSurface"], 600)}'
            f'{txt(x+24, y+78, sub, 12, tok["onSurfaceVariant"])}'
            f'{txt(x+w-52, y+78, last, 11, tok["onSurfaceVariant"], 400, "end")}'
            f'{rect(x+24, y+98, 96, 26, 13, tok["secondaryContainer"], 1)}'
            f'{txt(x+72, y+116, "就绪", 11, tok["onSecondaryContainer"], 500, "middle")}'
            f'<g name="more">{icon("dots", x+w-32, y+34, tok["onSurfaceVariant"])}</g></g>')


def entry_row(x, y, w, h, kind, title, sub, tok, divider=True):
    cy = y + h / 2
    out = [f'<g name="entry/{title}">']
    if divider:
        out.append(f'<line x1="{x}" y1="{y}" x2="{x+w}" y2="{y}" stroke="{tok["outlineVariant"]}" stroke-opacity="0.5"/>')
    out.append(f'<circle cx="{x+36}" cy="{cy}" r="20" fill="{tok["surfaceContainerHigh"]}"/>')
    out.append(icon(kind, x + 36, cy, tok["primary"] if kind == "download" else tok["onSurfaceVariant"]))
    out.append(txt(x + 72, cy - 2, title, 14, tok["onSurface"], 600))
    out.append(txt(x + 72, cy + 18, sub, 11, tok["onSurfaceVariant"]))
    out.append(chevron(x + w - 24, cy, tok["onSurfaceVariant"]))
    out.append("</g>")
    return "".join(out)


# ---------------------------------------------------------------- 页面 1：启动台
def page_home(t):
    g = ['<g name="hero">']
    g.append(f'<rect x="{CX}" y="88" width="{CW}" height="240" rx="24" fill="url(#hero)"/>')
    g.append(txt(CX + 32, 138, "主实例", 11, t["onPrimaryContainer"], 500, "start", 0.6, "0.1em"))
    g.append(txt(CX + 32, 184, "地下酒吧", 36, t["onPrimaryContainer"], 700))
    stats = [("游戏版本", "1.20.1"), ("模组加载器", "Forge 47.4.16"),
             ("总游玩", "42 小时"), ("上次启动", "2 小时前")]
    STAT_DX = round(clamp(170, 210, (CW - 300) / 4))   # 1280 宽时 210，窄窗口自动收紧
    for i, (lab, val) in enumerate(stats):
        sx = CX + 32 + i * STAT_DX
        g.append(txt(sx, 226, lab, 11, t["onPrimaryContainer"], 500, "start", 0.62))
        g.append(txt(sx, 250, val, 15, t["onPrimaryContainer"], 600, "start", 0.95))
    px, py = CX + CW - 32 - 36, 208
    ex = px - 36 - 14 - 24
    g.append(f'<circle cx="{ex}" cy="{py}" r="24" fill="{t["onPrimaryContainer"]}" fill-opacity="0.14" '
             f'stroke="{t["onPrimaryContainer"]}" stroke-opacity="0.32"/>')
    g.append(icon("gear", ex, py, t["onPrimaryContainer"]))
    g.append(f'<circle cx="{px}" cy="{py}" r="36" fill="{t["primary"]}"/>')
    g.append(icon("play", px, py, t["onPrimary"]))
    g.append("</g>")

    GAP = 24
    RW = clamp(400, 520, round(CW * 0.437))      # 右列 400~520 之间浮动，1280 宽时正好 500
    LW = CW - GAP - RW                            # 左列吃掉剩余宽度
    RX = CX + LW + GAP
    g.append(txt(CX, 368, "最近实例", 16, t["onSurface"], 600))
    g.append(txt(CX + LW, 368, "按上次启动排序", 11, t["onSurfaceVariant"], 400, "end"))
    g.append(txt(RX, 368, "获取内容", 16, t["onSurface"], 600))
    recent = [("空岛生存", "26.1 · Fabric 0.16.9", "上次启动 昨天 21:05"),
              ("红石测试场", "1.21.4 · Fabric 0.16.9", "上次启动 昨天 09:40")]
    for i, (n, s, l) in enumerate(recent):
        g.append(card(CX, 392 + i * 160, LW, 144, n, s, l, t))

    sy = 708
    g.append(f'<g name="all-instances">{rect(CX, sy, LW, 56, 16, t["surfaceContainerHigh"])}'
             f'{icon("stack", CX + 34, sy + 28, t["onSurface"])}'
             f'{txt(CX + 68, sy + 34, "所有实例", 14, t["onSurface"], 500)}'
             f'{txt(CX + LW - 52, sy + 34, "共 5 个", 11, t["onSurfaceVariant"], 400, "end")}'
             f'{chevron(CX + LW - 28, sy + 28, t["onSurfaceVariant"])}</g>')

    g.append(rect(RX, 392, RW, 372, 24, t["surfaceContainerLow"]))
    entries = [("download", "下载游戏版本", "原版 · 快照 · 旧版本"),
               ("folder", "打开实例文件夹", "主实例 · mods / config / saves"),
               ("grid", "浏览模组与整合包", "Modrinth · CurseForge"),
               ("package", "导入本地整合包", ".mrpack / .zip")]
    for i, (k, title, sub) in enumerate(entries):
        g.append(entry_row(RX, 408 + i * 84, RW, 84, k, title, sub, t, divider=(i > 0)))
    return "".join(g)


# ---------------------------------------------------------------- 页面 2：实例
def page_instances(t):
    g = []
    g.append(rect(CX, 88, 360, 44, 22, t["surfaceContainerHigh"]))
    g.append(icon("search", CX + 26, 110, t["onSurfaceVariant"]))
    g.append(txt(CX + 48, 116, "搜索实例、版本或模组…", 13, t["onSurfaceVariant"]))
    g.append(rect(CX + CW - 148, 88, 148, 44, 22, t["primary"]))
    g.append(icon("plus", CX + CW - 148 + 24, 110, t["onPrimary"]))
    g.append(txt(CX + CW - 148 + 44, 116, "新建实例", 13, t["onPrimary"], 600))
    g.append(f'<rect x="{CX + CW - 148 - 12 - 132}" y="88" width="132" height="44" rx="22" '
             f'fill="none" stroke="{t["outlineVariant"]}"/>')
    g.append(txt(CX + CW - 148 - 12 - 132 + 22, 116, "最近启动", 12, t["onSurface"]))
    g.append(chevron(CX + CW - 148 - 12 - 22, 110, t["onSurfaceVariant"], 4))

    # 列位置随宽度线性调整：1280 时与定稿一致，变宽/变窄按权重分配
    d = CW - 1144
    C_VER = round(300 + d * 0.10)
    C_PLAY = round(560 + d * 0.30)
    C_LAST = round(720 + d * 0.45)
    C_STATUS = round(880 + d * 0.66)
    C_ACT = CW - 104
    TABLE_H = H - 152 - 68        # 表格吃满可用高度，底部留 68 给汇总行
    g.append(rect(CX, 152, CW, TABLE_H, 24, t["surfaceContainerLow"]))
    for lab, dx in [("实例", 88), ("版本 · 加载器", C_VER), ("总游玩", C_PLAY),
                    ("上次启动", C_LAST), ("状态", C_STATUS), ("操作", C_ACT)]:
        g.append(txt(CX + dx, 180, lab, 11, t["onSurfaceVariant"], 500, "start", 0.9, "0.04em"))
    g.append(f'<line x1="{CX+24}" y1="196" x2="{CX+CW-24}" y2="196" stroke="{t["outlineVariant"]}" stroke-opacity="0.45"/>')

    rows = [("地下酒吧", "1.20.1", "Forge 47.4.16", "12", "42 小时", "2 小时前", "运行中", "primary"),
            ("空岛生存", "26.1", "Fabric 0.16.9", "48", "126 小时", "昨天 21:05", "就绪", "secondary"),
            ("红石测试场", "1.21.4", "Fabric 0.16.9", "6", "3 小时", "昨天 09:40", "就绪", "secondary"),
            ("Create: 机械时代", "1.20.1", "Forge 47.4.16", "214", "88 小时", "3 天前", "可更新", "tertiary"),
            ("原版 26.4 快照", "26.4", "Vanilla", "0", "未启动", "上周三", "就绪", "secondary")]
    for i, (name, ver, loader, mods, play, last, status, tone) in enumerate(rows):
        y = 196 + i * 92
        if i:
            g.append(f'<line x1="{CX+24}" y1="{y}" x2="{CX+CW-24}" y2="{y}" stroke="{t["outlineVariant"]}" stroke-opacity="0.35"/>')
        g.append(f'<g name="row/{name}">')
        g.append(rect(CX + 24, y + 22, 48, 48, 14, t["primaryContainer"]))
        g.append(txt(CX + 48, y + 54, name[0], 18, t["onPrimaryContainer"], 700, "middle"))
        g.append(txt(CX + 88, y + 42, name, 15, t["onSurface"], 600))
        g.append(txt(CX + 88, y + 64, f"{mods} 个模组", 11, t["onSurfaceVariant"]))
        g.append(txt(CX + C_VER, y + 46, ver, 13, t["onSurface"], 500))
        g.append(txt(CX + C_VER, y + 66, loader, 11, t["onSurfaceVariant"]))
        g.append(txt(CX + C_PLAY, y + 46, play, 13, t["onSurfaceVariant"]))
        g.append(txt(CX + C_LAST, y + 46, last, 13, t["onSurfaceVariant"]))
        if tone == "primary":
            g.append(chip(CX + C_STATUS, y + 32, 92, status, t, "onPrimaryContainer", "primaryContainer"))
        elif tone == "tertiary":
            g.append(chip(CX + C_STATUS, y + 32, 92, status, t, "onTertiaryContainer", "tertiaryContainer"))
        else:
            g.append(chip(CX + C_STATUS, y + 32, 92, status, t))
        g.append(f'<circle cx="{CX + C_ACT + 12}" cy="{y + 46}" r="18" fill="{t["surfaceContainerHigh"]}"/>')
        g.append(icon("play", CX + C_ACT + 12, y + 46, t["onSurface"]))
        g.append(icon("dots", CX + C_ACT + 64, y + 46, t["onSurfaceVariant"]))
        g.append("</g>")

    g.append(txt(CX, 152 + TABLE_H + 28, "共 5 个实例 · 2 个可更新 · 上次扫描 14:32", 11, t["onSurfaceVariant"]))
    return "".join(g)


# ---------------------------------------------------------------- 页面 3：诊断
def page_diagnostics(t):
    g = []
    LX = CX
    RW = clamp(340, 480, round(CW * 0.367))    # 1280 宽时 420，与定稿一致
    LW = CW - 24 - RW
    RX = CX + LW + 24

    g.append(rect(LX, 88, LW, 196, 24, t["surfaceContainerLow"]))
    g.append(txt(LX + 24, 124, "可能原因", 11, t["onSurfaceVariant"], 500, "start", 0.9, "0.08em"))
    g.append(txt(LX + 24, 164, "Flywheel 与 Embeddium 抢同一个", 22, t["onSurface"], 700))
    g.append(txt(LX + 24, 196, "Mixin 注入点 · MixinBlockRenderer", 22, t["onSurface"], 700))
    g.append(chip(LX + 24, 214, 104, "置信度 92%", t, "onPrimaryContainer", "primaryContainer"))
    g.append(txt(LX + 144, 232, "证据 3 条 · 崩溃于 昨天 21:12 · Forge 47.4.16", 12, t["onSurfaceVariant"]))

    b1, b2 = round(LW * 0.337), round(LW * 0.36)
    b3 = LW - b1 - b2 - 32
    g.append(rect(LX, 300, b1, 44, 22, t["primary"]))
    g.append(txt(LX + b1 / 2, 327, "禁用 flywheel-1.0.4", 13, t["onPrimary"], 600, "middle"))
    g.append(rect(LX + b1 + 16, 300, b2, 44, 22, t["surfaceContainerHigh"]))
    g.append(txt(LX + b1 + 16 + b2 / 2, 327, "降级 embeddium → 0.6.1", 13, t["onSurface"], 500, "middle"))
    g.append(f'<rect x="{LX + b1 + b2 + 32}" y="300" width="{b3}" height="44" rx="22" fill="none" stroke="{t["outline"]}"/>')
    g.append(txt(LX + b1 + b2 + 32 + b3 / 2, 327, "复制诊断报告", 13, t["onSurface"], 500, "middle"))

    g.append(rect(LX, 364, LW, 268, 24, t["surfaceContainerLow"]))
    g.append(txt(LX + 24, 404, "其他线索", 14, t["onSurface"], 600))
    clues = [("内存逼近上限", "Xmx 8G / 物理 16G，崩溃前 GC 停顿 4.2s", "latest.log"),
             ("OptiFine 残留在 mods 目录", "未启用，但与 Embeddium 共存有已知风险", "mods/"),
             ("同类冲突出现过一次", "3 天前同一注入点报错，禁用后恢复", "历史诊断")]
    for i, (title, body, src) in enumerate(clues):
        y = 428 + i * 68
        g.append(f'<circle cx="{LX + 32}" cy="{y + 6}" r="3.5" fill="{t["primary"]}"/>')
        g.append(txt(LX + 48, y + 11, title, 13, t["onSurface"], 600))
        g.append(txt(LX + 48, y + 32, body, 12, t["onSurfaceVariant"]))
        g.append(mono(LX + LW - 24, y + 11, src, 10, t["onSurfaceVariant"], 400, "end", 0.8))

    g.append(rect(RX, 88, RW, 608, 24, t["surfaceContainerLow"]))
    g.append(txt(RX + 24, 124, "原始堆栈", 14, t["onSurface"], 600))
    g.append(chip(RX + RW - 24 - 210, 106, 210, "crash-report_21-12.txt", t,
                  "onSurfaceVariant", "surfaceContainerHigh", 24, 10))
    lines = ["java.lang.RuntimeException: Mixin transformation of",
             "net.minecraft.client.renderer.LevelRenderer failed",
             "  at MixinProcessor.applyMixins(:392)",
             "  at MixinTransformer.transformClass(:218)",
             "Caused by: MixinApplyError:",
             "  Mixin apply failed flywheel.mixins.json:",
             "  MixinBlockRenderer -> LevelRenderer",
             "  at flywheel.impl.mixin.MixinBlockRenderer(:41)",
             "  at embeddium.mixin.render.LevelRendererMixin(:88)",
             "  ... 24 more"]
    for i, line in enumerate(lines):
        ly = 168 + i * 22
        if 5 <= i <= 7:
            g.append(rect(RX + 16, ly - 15, RW - 32, 20, 4, t["secondaryContainer"], 0.55))
        g.append(mono(RX + 24, ly, line, 10.5,
                      t["onSurface"] if 5 <= i <= 7 else t["onSurfaceVariant"]))
    g.append(f'<line x1="{RX+24}" y1="420" x2="{RX+RW-24}" y2="420" stroke="{t["outlineVariant"]}" stroke-opacity="0.4"/>')
    g.append(txt(RX + 24, 452, "自动定位", 11, t["onSurfaceVariant"], 500, "start", 0.9, "0.08em"))
    g.append(txt(RX + 24, 480, "3 个类名命中 mods 索引", 13, t["onSurface"], 500))
    g.append(mono(RX + 24, 502, "LevelRenderer → embeddium-0.6.0.jar", 11, t["onSurfaceVariant"]))
    g.append(mono(RX + 24, 522, "MixinBlockRenderer → flywheel-1.0.4.jar", 11, t["onSurfaceVariant"]))
    g.append(mono(RX + 24, 542, "MixinProcessor → (Mixin 框架)", 11, t["onSurfaceVariant"]))

    g.append(f'<rect x="{LX}" y="652" width="228" height="44" rx="22" fill="none" stroke="{t["outline"]}"/>')
    g.append(txt(LX + 114, 679, "保存为 Markdown", 13, t["onSurface"], 500, "middle"))
    g.append(f'<rect x="{LX+244}" y="652" width="196" height="44" rx="22" fill="none" stroke="{t["outline"]}"/>')
    g.append(txt(LX + 244 + 98, 679, "重新分析", 13, t["onSurface"], 500, "middle"))
    g.append(txt(LX + 460, 679, "分析耗时 0.8s · 本地规则库 v3", 11, t["onSurfaceVariant"]))
    return "".join(g)


# ---------------------------------------------------------------- 页面 4：设置
def page_settings(t):
    g = []
    LX, LW = CX, 240
    RX = CX + LW + 24
    RW = CW - LW - 24

    for i, lab in enumerate(["外观", "游戏", "启动", "下载", "关于"]):
        y = 88 + i * 48
        if i == 0:
            g.append(rect(LX, y, LW, 44, 12, t["secondaryContainer"]))
        g.append(txt(LX + 20, y + 28, lab, 13,
                     t["onSecondaryContainer"] if i == 0 else t["onSurfaceVariant"],
                     500 if i == 0 else 400))

    y = 88
    g.append(rect(RX, y, RW, 140, 24, t["surfaceContainerLow"]))
    g.append(txt(RX + 24, y + 40, "主题", 14, t["onSurface"], 600))
    g.append(txt(RX + 24, y + 62, "跟随系统时会读取桌面环境的深浅色设置", 11, t["onSurfaceVariant"]))
    g.append(segmented(RX + 24, y + 78, 320, 40, ["跟随系统", "浅色", "深色"], 0, t))

    y = 244
    g.append(rect(RX, y, RW, 160, 24, t["surfaceContainerLow"]))
    g.append(txt(RX + 24, y + 40, "从壁纸自动取色", 14, t["onSurface"], 600))
    g.append(txt(RX + 24, y + 62, "用 KMeans 抽主色生成整套路色，关闭则只用种子色", 11, t["onSurfaceVariant"]))
    g.append(switch_(RX + RW - 24 - 44, y + 26, True, t))
    for i, k in enumerate(["primary", "secondary", "tertiary", "primaryContainer", "tertiaryContainer"]):
        g.append(f'<circle cx="{RX + 36 + i * 40}" cy="{y + 108}" r="14" fill="{t[k]}"/>')
    g.append(txt(RX + 232, y + 114, "当前种子色 #EFDBB2 · 来自「暖金」预设", 11, t["onSurfaceVariant"]))

    y = 420
    g.append(rect(RX, y, RW, 120, 24, t["surfaceContainerLow"]))
    g.append(txt(RX + 24, y + 40, "减少动效", 14, t["onSurface"], 600))
    g.append(txt(RX + 24, y + 62, "关闭卡片 shape morph、弹簧与页面转场，仅保留淡入", 11, t["onSurfaceVariant"]))
    g.append(switch_(RX + RW - 24 - 44, y + 26, False, t))

    y = 556
    g.append(rect(RX, y, RW, 130, 24, t["surfaceContainerLow"]))
    g.append(txt(RX + 24, y + 40, "界面密度", 14, t["onSurface"], 600))
    g.append(txt(RX + 24, y + 62, "紧凑会让列表行高从 92 降到 72，一屏多看两行", 11, t["onSurfaceVariant"]))
    g.append(segmented(RX + 24, y + 78, 220, 40, ["舒适", "紧凑"], 0, t))
    return "".join(g)


PAGES = [
    ("home", "启动台", "3 个实例 · 1 个可更新", page_home),
    ("instances", "实例", "5 个实例 · 2 个可更新", page_instances),
    ("diagnostics", "诊断", "最近一次崩溃 · 昨天 21:12", page_diagnostics),
    ("settings", "设置", "YMCL 0.1.0 · 本地模式", page_settings),
]


def build(mode, page, w=1280, h=800):
    set_canvas(w, h)
    t = load(mode)
    key, title, meta, fn = page
    active = [p[0] for p in PAGES].index(key)
    head = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">',
            '<defs><linearGradient id="hero" x1="0" y1="0" x2="1" y2="1">'
            f'<stop offset="0" stop-color="{t["primaryContainer"]}"/>'
            f'<stop offset="1" stop-color="{t["tertiaryContainer"]}"/></linearGradient></defs>',
            f'<g name="board/{title} {mode} {w}x{h}">',
            rect(0, 0, W, H, 0, t["surface"])]
    body = [rail(t, active), topbar(t, title, meta), '<g name="content">', fn(t), "</g>", "</g></svg>"]
    return "\n".join(head + body)


# (宽, 高) 组合：最小 / 基准 / 宽屏
SIZES = [(1100, 800), (1280, 800), (1600, 800)]

os.makedirs(OUT_DIR, exist_ok=True)
svgs = {}
for key, title, meta, fn in PAGES:
    for (w, h) in SIZES:
        for mode in (("dark", "light") if (w, h) == (1280, 800) else ("dark",)):
            s = build(mode, (key, title, meta, fn), w, h)
            p = os.path.join(OUT_DIR, f"ymcl-{key}-{w}x{h}-{mode}.svg")
            open(p, "w", encoding="utf-8").write(s)
            svgs[(key, w, h, mode)] = s
            print("wrote", p)

cols = []
for (w, h), label in zip(SIZES, ["1100 窄", "1280 基准", "1600 宽"]):
    cells = "".join(
        f'<div class="cell"><h3>{title} · {w}×{h}</h3><div class="frame">{svgs[(key, w, h, "dark")]}</div></div>'
        for key, title, meta, fn in PAGES)
    cols.append(f'<div class="col"><h2>{label}</h2>{cells}</div>')
light_cells = "".join(
    f'<div class="cell"><h3>{title} · 1280×800</h3><div class="frame">{svgs[(key, 1280, 800, "light")]}</div></div>'
    for key, title, meta, fn in PAGES)
cols.append(f'<div class="col"><h2>Light 1280</h2>{light_cells}</div>')

html = f"""<!doctype html><html lang="zh"><meta charset="utf-8">
<title>YMCL 设计稿 · 四屏预览</title>
<style>
  body{{margin:0;background:#0b0b0d;color:#ddd;font-family:{FONT};padding:24px}}
  h2{{font-size:13px;font-weight:500;color:#8f8f8f;margin:0 0 12px;letter-spacing:.1em;text-transform:uppercase}}
  h3{{font-size:12px;font-weight:500;color:#6f6f6f;margin:0 0 6px}}
  .cols{{display:flex;gap:32px;align-items:flex-start;flex-wrap:wrap}}
  .col{{flex:1 1 560px;min-width:480px}}
  .cell{{margin-bottom:20px}}
  .frame{{border-radius:10px;overflow:hidden;box-shadow:0 6px 30px #0009}}
  svg{{width:100%;height:auto;display:block}}
</style>
<body><div class="cols">{''.join(cols)}</div></body></html>"""
open(os.path.join(OUT_DIR, "preview.html"), "w", encoding="utf-8").write(html)
print("wrote", os.path.join(OUT_DIR, "preview.html"))
