#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PanelFM · Clean UI 1.0 视觉稿生成器（纯 PIL，无外部依赖）。

用途：在没有真机 / 模拟器的沙箱里，把「现状」与「Clean 1.0」画成可对比的静态稿。
输出：docs/design/*.png（390×844 dp，2x 输出）。

注意：这是设计稿，不是 UI 代码；数值与 docs/UI-CLEAN.md 的 token 表一致。
"""
import os
from PIL import Image, ImageDraw, ImageFont

S = 3          # 超采样倍率（先按 3x 画，再降采样到 2x）
OUT = 2        # 输出倍率
W, H = 390, 844
FONT_PATH = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
HERE = os.path.dirname(os.path.abspath(__file__))


def px(v):
    return int(round(v * S))


# ----------------------------------------------------------------------------- 调色板
LIGHT = dict(
    bg="#FFFFFF", surface="#FFFFFF", alt="#F6F7F8",
    text="#17181A", muted="#6B7075", faint="#9AA0A6",
    accent="#2F6FEB", on_accent="#FFFFFF",
    divider="#E7E9EC", icon="#5F6368", danger="#D93025",
    sel="#EEF3FD",
)
DARK = dict(
    bg="#0B0C0E", surface="#121316", alt="#17181B",
    text="#ECEDEF", muted="#9BA1A6", faint="#7C8288",
    accent="#6EA0FF", on_accent="#0B1220",
    divider="#25272C", icon="#A8AEB4", danger="#FF6B5F",
    sel="#171E2B",
)

# Linear 主题（与 core/ui/ThemePresets.kt 的 LINEAR 一致）
LINEAR_LIGHT = dict(
    bg="#FBFBFB", surface="#FFFFFF", alt="#F4F5F6",
    text="#282A30", muted="#6B6F76", faint="#8A8F98",
    accent="#5E6AD2", on_accent="#FFFFFF",
    divider="#E9EAEE", icon="#5E6470", danger="#E5484D",
    sel="#F0F1FC",
)
LINEAR_DARK = dict(
    bg="#08090A", surface="#0F1011", alt="#16171A",
    text="#F7F8F8", muted="#8A8F98", faint="#6E747D",
    accent="#7C87E8", on_accent="#0B0D1A",
    divider="#23252A", icon="#A8AEB4", danger="#FF6369",
    sel="#1A1D33",
)

# 现状（MT 复刻）调色板
MT = dict(
    bg="#FFFFFF", surface="#FFFFFF",
    topbar="#151515", topbar_text="#F2F2F2", topbar_sub="#B9B9B9",
    text="#000000", muted="#6E6E6E",
    accent="#1976D2", divider="#BBBBBB",
    folder="#2B2B2B", image="#43A047", archive="#FB8C00",
    apk="#00C853", pdf="#D32F2F", code="#1E88E5", textfile="#546E7A",
    sel="#E8F0FB",
)


class Phone:
    def __init__(self, pal, dark=False):
        self.p = pal
        self.dark = dark
        self.img = Image.new("RGB", (px(W), px(H)), pal["bg"])
        self.d = ImageDraw.Draw(self.img)

    # ---- 基础绘制 -----------------------------------------------------------
    def text(self, x, y, s, size, color, weight="r", anchor="lm"):
        f = ImageFont.truetype(FONT_PATH, px(size))
        sw = px(0.42) if weight in ("m", "s", "b") else 0
        self.d.text((px(x), px(y)), s, font=f, fill=color, anchor=anchor,
                    stroke_width=sw, stroke_fill=color)

    def rect(self, x, y, w, h, r=0, fill=None, outline=None, width=1):
        box = [px(x), px(y), px(x + w), px(y + h)]
        if r:
            self.d.rounded_rectangle(box, radius=px(r), fill=fill, outline=outline,
                                     width=px(width))
        else:
            self.d.rectangle(box, fill=fill, outline=outline, width=px(width))

    def hline(self, x, y, w, color, t=1):
        self.rect(x, y, w, t, 0, color)

    def vline(self, x, y, h, color, t=1):
        self.rect(x, y, t, h, 0, color)

    def circle(self, cx, cy, r, fill=None, outline=None, width=1):
        self.d.ellipse([px(cx - r), px(cy - r), px(cx + r), px(cy + r)],
                       fill=fill, outline=outline, width=px(width))

    # ---- 图标（24×24 视框，x/y 为左上角）-----------------------------------
    def icon(self, name, x, y, size, color):
        u = size / 24.0
        def P(pts):
            return [(px(x + a * u), px(y + b * u)) for a, b in pts]
        def ln(pts, w=2.0):
            self.d.line(P(pts), fill=color, width=max(1, px(w * u)), joint="curve")
        def rr(a, b, c, d, r=2.0, fill=None, outline=color, w=2.0):
            box = [px(x + a * u), px(y + b * u), px(x + c * u), px(y + d * u)]
            self.d.rounded_rectangle(box, radius=px(r * u), fill=fill,
                                     outline=outline, width=max(1, px(w * u)))
        if name == "menu":
            for yy in (6, 12, 18):
                ln([(3, yy), (21, yy)], 2.0)
        elif name == "dots":
            for yy in (5, 12, 19):
                self.circle(x + 12 * u, y + yy * u, 1.7 * u, fill=color)
        elif name == "chevron_r":
            ln([(9, 5), (16, 12), (9, 19)], 2.0)
        elif name == "chevron_l":
            ln([(15, 5), (8, 12), (15, 19)], 2.0)
        elif name == "chevron_u":
            ln([(5, 15), (12, 8), (19, 15)], 2.0)
        elif name == "folder":
            rr(3, 7, 21, 20, 2.4, outline=color, w=1.9)
            ln([(3.6, 7), (9.4, 7), (11.4, 9.4), (20.4, 9.4)], 1.9)
        elif name == "folder_fill":
            self.d.rounded_rectangle([px(x + 3 * u), px(y + 7 * u), px(x + 21 * u), px(y + 20 * u)],
                                     radius=px(2.4 * u), fill=color)
            ln([(3.6, 7), (9.4, 7), (11.4, 9.4), (20.4, 9.4)], 1.9)
        elif name == "file":
            rr(5, 3, 19, 21, 2.0, outline=color, w=1.8)
            ln([(14, 3.4), (18.6, 8), (14, 8), (14, 3.4)], 1.6)
        elif name == "image":
            rr(3, 4.5, 21, 19.5, 2.2, outline=color, w=1.8)
            self.circle(x + 8 * u, y + 9.2 * u, 1.5 * u, fill=color)
            ln([(5.2, 17.4), (10.2, 12.2), (13.8, 16.0), (16.8, 13.0), (19.0, 15.6)], 1.7)
        elif name == "archive":
            rr(3, 5, 21, 19.5, 2.2, outline=color, w=1.8)
            ln([(3.6, 10), (20.4, 10)], 1.6)
            rr(10, 12.6, 14, 16.2, 0.8, outline=color, w=1.5)
        elif name == "package":
            rr(4, 8, 20, 20, 2.0, outline=color, w=1.8)
            ln([(4.4, 8.4), (12, 3.6), (19.6, 8.4)], 1.8)
            ln([(12, 3.8), (12, 8.2)], 1.6)
        elif name == "code":
            ln([(9, 7), (4, 12), (9, 17)], 1.8)
            ln([(15, 7), (20, 12), (15, 17)], 1.8)
        elif name == "pdf":
            rr(5, 3, 19, 21, 2.0, outline=color, w=1.8)
            ln([(8, 11), (16, 11)], 1.5)
            ln([(8, 14.5), (16, 14.5)], 1.5)
        elif name == "plus":
            ln([(12, 5), (12, 19)], 1.9)
            ln([(5, 12), (19, 12)], 1.9)
        elif name == "arrow_up":
            ln([(12, 19), (12, 5)], 1.9)
            ln([(6, 11), (12, 5), (18, 11)], 1.9)
        elif name == "swap":
            ln([(4, 9), (18, 9)], 1.7)
            ln([(14, 5), (18, 9), (14, 13)], 1.7)
            ln([(20, 15), (6, 15)], 1.7)
            ln([(10, 11), (6, 15), (10, 19)], 1.7)
        elif name == "clipboard":
            rr(6, 4.5, 18, 21, 2.0, outline=color, w=1.7)
            rr(9, 2.5, 15, 6.5, 1.2, outline=color, w=1.5)
        elif name == "settings":
            for yy, knob in ((7, 15), (12, 9), (17, 16)):
                ln([(4, yy), (20, yy)], 1.5)
                self.circle(x + knob * u, y + yy * u, 2.0 * u, fill=color)
        elif name == "trash":
            ln([(4, 7), (20, 7)], 1.7)
            ln([(9, 7), (9.5, 4), (14.5, 4), (15, 7)], 1.7)
            rr(6, 7.5, 18, 20.5, 1.6, outline=color, w=1.6)
        elif name == "bookmark":
            ln([(6, 4), (18, 4), (18, 20), (12, 15.5), (6, 20), (6, 4)], 1.7)
        elif name == "wifi":
            self.d.arc([px(x + 4 * u), px(y + 3 * u), px(x + 20 * u), px(y + 19 * u)],
                       start=205, end=335, fill=color, width=max(1, px(1.7 * u)))
            self.d.arc([px(x + 7.5 * u), px(y + 7.5 * u), px(x + 16.5 * u), px(y + 16.5 * u)],
                       start=205, end=335, fill=color, width=max(1, px(1.7 * u)))
            self.circle(x + 12 * u, y + 17.5 * u, 1.4 * u, fill=color)
        elif name == "signal":
            for i, hh in enumerate((4, 7, 10, 13)):
                self.rect(x + 4 + i * 4.2, y + 18 - hh, 2.6, hh, 0.8, color)
        elif name == "battery":
            rr(4, 8, 20, 16, 1.6, outline=color, w=1.4)
            self.rect(20.6, 10.6, 1.8, 2.8, 0.6, color)
            self.rect(5.6, 9.6, 11, 4.8, 0.8, color)
        elif name == "cloud":
            self.d.arc([px(x + 4 * u), px(y + 6 * u), px(x + 14 * u), px(y + 16 * u)],
                       180, 360, fill=color, width=max(1, px(1.6 * u)))
            self.d.arc([px(x + 9 * u), px(y + 3 * u), px(x + 20 * u), px(y + 15 * u)],
                       270, 90, fill=color, width=max(1, px(1.6 * u)))
            ln([(6, 15.5), (18, 15.5)], 1.6)
        elif name == "server":
            rr(4, 4, 20, 10, 1.4, outline=color, w=1.5)
            rr(4, 12.5, 20, 18.5, 1.4, outline=color, w=1.5)
            self.circle(x + 7 * u, y + 7 * u, 0.9 * u, fill=color)
            self.circle(x + 7 * u, y + 15.5 * u, 0.9 * u, fill=color)
        elif name == "copy":
            rr(8, 3, 20, 15, 1.8, outline=color, w=1.6)
            rr(4, 8, 16, 20, 1.8, outline=color, w=1.6)
        elif name == "move":
            rr(3, 7, 21, 20, 2.4, outline=color, w=1.8)
            ln([(3.6, 7), (9.4, 7), (11.4, 9.4), (20.4, 9.4)], 1.8)
            ln([(9, 14.5), (16, 14.5)], 1.6)
            ln([(13.5, 11.5), (16.5, 14.5), (13.5, 17.5)], 1.6)
        elif name == "check":
            ln([(5, 12.5), (10, 17.5), (19, 6.5)], 2.0)
        elif name == "pencil":
            ln([(5, 19), (6.5, 14.5), (16, 5), (19, 8), (9.5, 17.5), (5, 19)], 1.6)
        elif name == "info":
            self.circle(x + 12 * u, y + 12 * u, 8.5 * u, outline=color, width=1.6 * u)
            self.circle(x + 12 * u, y + 8.2 * u, 1.0 * u, fill=color)
            ln([(12, 11.5), (12, 16.5)], 1.6)
        elif name == "filter":
            ln([(3, 5), (21, 5), (14, 13), (14, 20), (10, 17), (10, 13), (3, 5)], 1.6)
        elif name == "x":
            ln([(6, 6), (18, 18)], 1.7)
            ln([(18, 6), (6, 18)], 1.7)
        elif name == "folder_open":
            ln([(3, 19), (3, 7), (9, 7), (11, 9.5), (18, 9.5)], 1.7)
            ln([(3, 19), (7, 10.5), (22, 10.5), (18, 19), (3, 19)], 1.6)

    # ---- 通用控件 -----------------------------------------------------------
    def status_bar(self, color):
        self.text(22, 14, "9:41", 13, color, "s")
        self.icon("signal", 300, 6, 15, color)
        self.icon("wifi", 320, 6, 15, color)
        self.icon("battery", 340, 6, 17, color)

    def hairline(self, y, color, x=0, w=None):
        self.hline(x, y, W if w is None else w, color, 0.6)

    def switch(self, x, y, on, p):
        w, h = 40, 24
        bg = p["accent"] if on else (p["divider"] if not self.dark else "#3A3D43")
        self.rect(x, y - h / 2, w, h, h / 2, bg)
        cx = x + w - 12 if on else x + 12
        self.circle(cx, y, 10, p["surface"] if not self.dark else "#F2F3F5")

    def nav_bar(self, p):
        # 手势条
        self.rect(W / 2 - 54, H - 8, 108, 3.2, 1.6, p["text"] if not self.dark else "#E6E8EA")


# ----------------------------------------------------------------------------- 现状（MT）
def frame_before():
    p = MT
    ph = Phone(p)
    d = ph.d
    # 状态栏（深色顶栏铺满）
    ph.rect(0, 0, W, 28, 0, p["topbar"])
    ph.status_bar(p["topbar_text"])
    # 顶栏 56
    ph.rect(0, 28, W, 56, 0, p["topbar"])
    ph.icon("menu", 14, 43, 26, p["topbar_text"])
    ph.text(W / 2, 48, "/storage/emulated/0/Download", 17, p["topbar_text"], "r", "mm")
    ph.text(W / 2, 68, "文件夹: 6  文件: 5  储存: 41.2G/128G", 11, p["topbar_sub"], "r", "mm")
    ph.icon("dots", 352, 43, 26, p["topbar_text"])
    ph.hline(0, 84, W, "#000000", 0.6)

    rows = [
        ("folder", "Android", "2026-10-02 09:12", p["folder"]),
        ("folder", "Documents", "2026-09-28 21:40", p["folder"]),
        ("folder", "Movies", "2026-08-11 18:03", p["folder"]),
        ("folder", "Music", "2026-07-30 11:22", p["folder"]),
        ("folder", "Pictures", "2026-10-04 12:00", p["folder"]),
        ("code", "notes.md", "2026-10-03 23:51", p["code"]),
        ("image", "IMG_2041.jpg", "2026-09-19 14:06", p["image"]),
        ("archive", "backup.zip", "2026-06-01 08:30", p["archive"]),
        ("package", "app-release.apk", "2026-09-30 17:45", p["apk"]),
        ("pdf", "report.pdf", "2026-05-12 10:11", p["pdf"]),
    ]
    y = 85
    # .. 行
    ph.text(40, y + 20, "..", 13, p["text"], "r")
    ph.hline(0, y + 40, W, p["divider"], 0.6)
    y += 40
    for i, (ic, name, t, col) in enumerate(rows):
        # 图标底板
        ph.rect(6, y + 6, 28, 28, 4, col)
        ph.icon(ic, 6 + 4, y + 6 + 4, 20, "#FFFFFF")
        ph.text(40, y + 13, name, 13, p["text"], "r")
        ph.text(40, y + 28, t, 10, p["muted"], "r")
        ph.hline(0, y + 40, W, p["divider"], 0.6)
        y += 40

    # 底栏
    by = H - 64
    ph.rect(0, by, W, 64, 0, p["surface"])
    ph.hline(0, by, W, p["divider"], 0.6)
    for i, ic in enumerate(("chevron_l", "chevron_r", "plus", "swap", "arrow_up")):
        ph.icon(ic, 39 + i * 78, by + 20, 24, "#3C3C3C")
    ph.nav_bar(p)
    return ph.img


# ----------------------------------------------------------------------------- Clean 双列
ROWS_LEFT = [
    ("folder", "Android"),
    ("folder", "Documents"),
    ("folder", "Movies"),
    ("folder", "Music"),
    ("folder", "Pictures"),
    ("code", "notes.md"),
    ("image", "IMG_2041.jpg"),
    ("archive", "backup.zip"),
    ("package", "app-release.apk"),
    ("pdf", "report.pdf"),
]
ROWS_RIGHT = [
    ("folder", "2026-11"),
    ("folder", "2026-10"),
    ("image", "cover.jpg"),
    ("image", "DSC_0142.jpg"),
    ("image", "DSC_0143.jpg"),
    ("code", "edit.tiff"),
    ("file", "readme.txt"),
]


def frame_dual(dark=False, single=False, selection=False, empty=False, pal=None):
    p = pal or (DARK if dark else LIGHT)
    ph = Phone(p, dark)
    ph.rect(0, 0, W, H, 0, p["bg"])
    ph.status_bar(p["text"])

    top_h = 56
    y0 = 28
    mid = W / 2 if not single else W
    # 活动窗格路径（空态时右窗格为活动，带过滤 chip）
    if empty:
        ph.text(52, y0 + 14, "SFTP · nas", 12.5, p["faint"])
        w1 = ph.d.textlength("SFTP · nas", font=ImageFont.truetype(FONT_PATH, px(12.5))) / S
        ph.text(52 + w1 + 4, y0 + 14, "/photo", 15, p["text"], "s")
        # 过滤 chip（右对齐）
        ph.rect(286, y0 + 5, 46, 19, 9.5, p["sel"])
        ph.icon("filter", 290, y0 + 8.5, 12, p["accent"])
        ph.text(305, y0 + 14.5, "过滤", 10.5, p["accent"], "m")
        ph.icon("x", 320, y0 + 9, 10, p["accent"])
        ph.text(52, y0 + 38, "内部存储", 12.5, p["faint"])
        w2 = ph.d.textlength("内部存储", font=ImageFont.truetype(FONT_PATH, px(12.5))) / S
        ph.text(52 + w2 + 4, y0 + 38, "/ Download", 12.5, p["faint"])
    elif selection:
        ph.text(52, y0 + 14, "已选 3", 15, p["text"], "s")
        for i, ic in enumerate(("copy", "move", "trash")):
            ph.icon(ic, 244 + i * 30, y0 + 7, 20,
                    p["danger"] if ic == "trash" else p["text"])
        ph.text(52, y0 + 38, "SFTP · nas", 12.5, p["faint"])
        w2 = ph.d.textlength("SFTP · nas", font=ImageFont.truetype(FONT_PATH, px(12.5))) / S
        ph.text(52 + w2 + 6, y0 + 38, "/photo", 12.5, p["faint"])
    else:
        if not single:
            ph.text(52, y0 + 14, "内部存储", 12.5, p["faint"])
            w1 = ph.d.textlength("内部存储", font=ImageFont.truetype(FONT_PATH, px(12.5))) / S
            ph.text(52 + w1 + 4, y0 + 14, "/", 12.5, p["faint"])
            ph.text(52 + w1 + 14, y0 + 14, "Download", 15, p["text"], "s")
        if not single:
            ph.text(52, y0 + 38, "SFTP · nas", 12.5, p["faint"])
            w2 = ph.d.textlength("SFTP · nas", font=ImageFont.truetype(FONT_PATH, px(12.5))) / S
            ph.text(52 + w2 + 6, y0 + 38, "/photo", 12.5, p["faint"])
        else:
            # 单列：只保留一行路径，垂直居中（不写统计/副标题）
            ph.text(52, y0 + 28, "内部存储", 12.5, p["faint"])
            w1b = ph.d.textlength("内部存储", font=ImageFont.truetype(FONT_PATH, px(12.5))) / S
            ph.text(52 + w1b + 4, y0 + 28, "/", 12.5, p["faint"])
            ph.text(52 + w1b + 14, y0 + 28, "Download", 15, p["text"], "s")

    ph.icon("menu", 16, y0 + 15, 24, p["text"])
    ph.icon("dots", 352, y0 + 15, 24, p["text"])
    ph.hairline(28 + top_h, p["divider"])

    pane_top = 28 + top_h + 0.6
    pane_bottom = H - 56 - 16
    if not single:
        ph.vline(mid, pane_top, pane_bottom - pane_top, p["divider"], 0.6)

    row_h = 44
    times = {
        "Android": "10-02", "Documents": "09-28", "Movies": "08-11", "Music": "07-30",
        "Pictures": "10-04", "notes.md": "10-03", "IMG_2041.jpg": "09-19",
        "backup.zip": "06-01", "app-release.apk": "09-30", "report.pdf": "05-12",
    }

    def draw_pane(x0, width, rows, active, meta=False, filtered=False):
        y = pane_top + 4
        ph.icon("arrow_up", x0 + 18, y + 4, 18, p["faint"])
        y += 32
        if not rows:
            cx = x0 + width / 2
            cy = (pane_top + pane_bottom) / 2 - 26
            title = "无匹配项" if filtered else "空文件夹"
            ph.text(cx, cy, title, 15, p["muted"], "r", "mm")
            label = "清除过滤" if filtered else "新建"
            tw = ph.d.textlength(label, font=ImageFont.truetype(FONT_PATH, px(14))) / S
            ph.rect(cx - tw / 2 - 14, cy + 16, tw + 28, 32, 8, p["sel"])
            ph.text(cx, cy + 32, label, 14, p["accent"], "m", "mm")
            return
        for ic, name, *rest in rows:
            if y + row_h > pane_bottom:
                break
            is_sel = selection and active and name in ("Documents", "Music", "Pictures")
            if is_sel:
                ph.rect(x0, y, width - (0 if single else 0.6), row_h, 0, p["sel"])
                ph.rect(x0, y + 6, 2, row_h - 12, 1, p["accent"])
            col = (p["text"] if ic == "folder" else p["icon"]) if active else p["icon"]
            ph.icon(ic, x0 + 18, y + 12, 20, col)
            ph.text(x0 + 48, y + row_h / 2, name, 14.5, p["text"] if active else p["muted"])
            if meta:
                ph.text(x0 + width - 20, y + row_h / 2, times.get(name, ""), 12,
                        p["faint"], "r", "rm")
            if is_sel:
                ph.icon("check", x0 + width - 26, y + 14, 16, p["accent"])
            y += row_h

    if single:
        draw_pane(0, W, ROWS_LEFT, True, meta=True)
    else:
        if empty:
            draw_pane(0, mid, [], False)
            draw_pane(mid, mid, [], True, filtered=True)
        else:
            draw_pane(0, mid, ROWS_LEFT, True)
            draw_pane(mid, mid, ROWS_RIGHT, False)

    # 剪贴板 FAB（只在非选择态 / 非空态出现）
    if not selection and not empty:
        fx, fy = (mid - 56 if not single else W - 56), pane_bottom - 52
        ph.circle(fx + 20, fy + 20, 22, p["alt"])
        ph.circle(fx + 20, fy + 19, 20, p["surface"])
        ph.icon("clipboard", fx + 8, fy + 7, 24, p["accent"])

    # 底栏
    by = H - 56 - 16
    ph.rect(0, by, W, 56, 0, p["surface"])
    ph.hairline(by, p["divider"])
    if selection:
        for i, label in enumerate(("全选", "反选", "类选", "同步", "取消")):
            ph.text(39 + i * 78, by + 28, label, 14,
                    p["accent"] if label == "取消" else p["text"], "r", "mm")
    else:
        for i, ic in enumerate(("chevron_l", "chevron_r", "plus", "swap", "arrow_up")):
            ph.icon(ic, 39 + i * 78, by + 16, 24, p["icon"])
    ph.nav_bar(p)
    return ph.img


# ----------------------------------------------------------------------------- 长按动作单
def frame_sheet(dark=False):
    p = DARK if dark else LIGHT
    base = frame_dual(dark).copy()
    # 压暗
    scrim = Image.new("RGBA", base.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(scrim)
    sd.rectangle([0, 0, px(W), px(H)], fill=(0, 0, 0, 110))
    img = Image.alpha_composite(base.convert("RGBA"), scrim).convert("RGB")
    ph = Phone(p, dark)
    ph.img = img
    ph.d = ImageDraw.Draw(img)

    top = 410
    ph.rect(0, top, W, H - top, 18, p["surface"])
    ph.rect(W / 2 - 18, top + 10, 36, 4, 2, p["divider"])

    ph.text(24, top + 44, "IMG_2041.jpg", 16, p["text"], "s")
    ph.text(24, top + 65, "2.4 MB · 2026-09-19", 12, p["faint"])
    ph.hairline(top + 84, p["divider"])

    rows = [
        ("copy", "复制到…", p["text"]),
        ("move", "移动到…", p["text"]),
        ("pencil", "重命名", p["text"]),
        ("archive", "压缩", p["text"]),
        ("info", "属性", p["text"]),
        ("trash", "删除", p["danger"]),
    ]
    y = top + 92
    for ic, label, col in rows:
        ph.icon(ic, 24, y + 14, 20, col)
        ph.text(56, y + 24, label, 15, col)
        y += 48
    ph.nav_bar(p)
    return ph.img


# ----------------------------------------------------------------------------- 抽屉
def frame_drawer(dark=False):
    p = DARK if dark else LIGHT
    ph = Phone(p, dark)
    # 背景：被压暗的双列（右边缘露出的内容）
    ph.rect(0, 0, W, H, 0, p["bg"])
    widths = [56, 78, 48, 62, 70, 44, 84, 52, 66]
    for i, wbar in enumerate(widths):
        yb = 128 + i * 44
        ph.rect(336, yb - 7, 14, 14, 3, "#C9CDD2")
        ph.rect(358, yb - 4, wbar, 8, 4, "#C9CDD2")

    # 半透明遮罩（RGBA 合成）
    scrim = Image.new("RGBA", ph.img.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(scrim)
    sd.rectangle([px(320), 0, px(W), px(H)], fill=(0, 0, 0, 96))
    ph.img = Image.alpha_composite(ph.img.convert("RGBA"), scrim).convert("RGB")
    ph.d = ImageDraw.Draw(ph.img)

    dw = 320
    ph.rect(0, 0, dw, H, 0, p["surface"])
    ph.status_bar(p["text"])
    # 头部
    ph.text(20, 56, "PanelFM", 17, p["text"], "s")
    ph.hairline(76, p["divider"])

    y = 92
    # 存储
    ph.text(20, y, "存储", 11, p["faint"], "m")
    y += 22
    items = [("folder", "内部存储", "83 GB 可用"), ("folder", "应用目录", "2.1 GB")]
    for ic, name, meta in items:
        ph.icon(ic, 20, y + 14, 20, p["text"])
        ph.text(52, y + 24, name, 15, p["text"])
        ph.text(dw - 20, y + 24, meta, 12.5, p["muted"], "r", "rm")
        y += 52
    y += 14

    # 连接
    ph.text(20, y, "连接", 11, p["faint"], "m")
    y += 22
    conns = [("server", "nas", "SFTP"), ("cloud", "Dav", "WebDAV")]
    for ic, name, proto in conns:
        ph.icon(ic, 20, y + 14, 20, p["text"])
        ph.text(52, y + 24, name, 15, p["text"])
        ph.text(dw - 20, y + 24, proto, 12.5, p["muted"], "r", "rm")
        y += 48
    ph.icon("plus", 20, y + 14, 20, p["faint"])
    ph.text(52, y + 24, "添加存储", 15, p["faint"])
    y += 62

    # 工具
    ph.text(20, y, "工具", 11, p["faint"], "m")
    y += 22
    tools = [("swap", "传输任务", "2"), ("trash", "回收站", None), ("bookmark", "书签", None),
             ("wifi", "局域网", None), ("code", "工具箱", None), ("settings", "设置", None)]
    for ic, name, meta in tools:
        ph.icon(ic, 20, y + 14, 20, p["icon"])
        ph.text(52, y + 24, name, 15, p["text"])
        if meta:
            ph.text(dw - 20, y + 24, meta, 12.5, p["muted"], "r", "rm")
        y += 48
    ph.nav_bar(p)
    return ph.img


# ----------------------------------------------------------------------------- 设置
SETTINGS = [
    ("外观", [
        ("主题", "跟随系统", "chev"),
        ("配色", "MT 蓝", "chev"),
        ("字体大小", "适中", "chev"),
        ("动态取色", None, "switch:0"),
    ]),
    ("浏览", [
        ("默认视图", "自动", "chev"),
        ("启动位置", "上次位置", "chev"),
        ("显示隐藏文件", None, "switch:0"),
    ]),
    ("手势", [
        ("点击连选", None, "switch:0"),
        ("底栏上滑书签", None, "switch:1"),
    ]),
    ("传输", [
        ("校验", "仅大小", "chev"),
        ("并发任务", "3", "chev"),
    ]),
    ("关于", [
        ("版本", "2.4.4", None),
        ("开源许可", None, "chev"),
    ]),
]


def frame_settings(dark=False):
    p = DARK if dark else LIGHT
    ph = Phone(p, dark)
    ph.rect(0, 0, W, H, 0, p["bg"])
    ph.status_bar(p["text"])
    # 顶栏：返回 + 左对齐标题
    ph.icon("chevron_l", 16, 40, 22, p["text"])
    ph.text(48, 52, "设置", 20, p["text"], "s")
    ph.hairline(84, p["divider"])

    y = 102
    for group, rows in SETTINGS:
        ph.text(20, y + 8, group, 11, p["faint"], "m")
        y += 20
        for name, value, kind in rows:
            ph.text(20, y + 21, name, 15, p["text"])
            if value:
                ph.text(346, y + 21, value, 13, p["muted"], "r", "rm")
            if kind and kind.startswith("switch"):
                ph.switch(330, y + 21, kind.endswith("1"), p)
            elif kind == "chev":
                ph.icon("chevron_r", 362, y + 12, 18, p["faint"])
            y += 42
        y += 10
        ph.hairline(y - 5, p["divider"], x=20, w=W - 40)
        y += 6
    ph.nav_bar(p)
    return ph.img


# ----------------------------------------------------------------------------- 新设置页（Linear）
SETTINGS_LINEAR = [
    ("外观", [
        ("主题", "跟随系统", "chev"),
        ("配色", "Linear", "chev"),
        ("字体大小", "适中", "chev"),
        ("动态取色", "off", "switch"),
    ]),
    ("浏览", [
        ("默认视图", "自动切换", "chev"),
        ("显示隐藏文件", "off", "switch"),
        ("时间显示到秒", "off", "switch"),
        ("列表副标题", "时间", "chev"),
    ]),
    ("手势", [
        ("点击连选", "off", "switch"),
        ("底栏上滑书签", "on", "switch"),
    ]),
    ("文件", [
        ("启动时进入首页", "off", "switch"),
        ("保留修改时间", "on", "switch"),
        ("保存时生成 .bak", "off", "switch"),
        ("退出前二次确认", "off", "switch"),
    ]),
    ("媒体", [
        ("移动数据下加载缩略图", "off", "switch"),
        ("缩略图上限", "10 MB", "chev"),
        ("自动后台播放", "off", "switch"),
    ]),
    ("传输", [
        ("信任自签证书", "off", "switch"),
        ("传输校验", "仅大小", "chev"),
        ("并发任务", "3", "chev"),
    ]),
    ("回收站", [
        ("保留天数", "30 天", "chev"),
        ("容量上限", "不限", "chev"),
    ]),
    ("网络", [
        ("全局 User-Agent", "默认", "chev"),
    ]),
    ("关于", [
        ("版本", "2.4.4", "chev"),
    ]),
]


def frame_settings_linear(dark=False, sheet=False):
    p = LINEAR_DARK if dark else LINEAR_LIGHT
    ph = Phone(p, dark)
    ph.rect(0, 0, W, H, 0, p["bg"])
    ph.status_bar(p["text"])
    # 二级页顶栏（surface 底）
    ph.rect(0, 28, W, 56, 0, p["surface"])
    ph.icon("chevron_l", 16, 40, 22, p["text"])
    ph.text(48, 52, "设置", 20, p["text"], "s")
    ph.hairline(84, p["divider"])

    y = 96
    for group, rows in SETTINGS_LINEAR:
        if y > H:
            break
        y += 20
        ph.text(20, y + 6, group, 11, p["faint"], "m")
        y += 24
        for i, (name, value, kind) in enumerate(rows):
            ph.text(20, y + 24, name, 15, p["text"])
            if kind == "switch":
                ph.switch(330, y + 24, value == "on", p)
            else:
                ph.text(346, y + 24, value, 13, p["muted"], "r", "rm")
                ph.icon("chevron_r", 362, y + 15, 18, p["faint"])
            if i != len(rows) - 1:
                ph.hairline(y + 47, p["divider"], x=20, w=W - 40)
            y += 48
        y += 10

    if sheet:
        # 压暗 + 配色底部 sheet
        scrim = Image.new("RGBA", ph.img.size, (0, 0, 0, 0))
        sd = ImageDraw.Draw(scrim)
        sd.rectangle([0, 0, px(W), px(H)], fill=(0, 0, 0, 96))
        ph.img = Image.alpha_composite(ph.img.convert("RGBA"), scrim).convert("RGB")
        ph.d = ImageDraw.Draw(ph.img)
        top = 404
        ph.rect(0, top, W, H - top, 18, p["surface"])
        ph.rect(W / 2 - 18, top + 10, 36, 4, 2, p["divider"])
        ph.text(20, top + 38, "配色", 15, p["text"], "s")
        presets = [
            ("MT 蓝", "#FFFFFF", "#1976D2", "#151515"),
            ("墨笺", "#FBF8F2", "#B23A26", "#1D1B18"),
            ("黛青", "#FBFDFF", "#2C5A78", "#1A2733"),
            ("松烟", "#FBFDFB", "#3B6A4E", "#1A241E"),
            ("赭石", "#FFFDF8", "#875B22", "#241C14"),
            ("Linear", "#FFFFFF", "#5E6AD2", "#FFFFFF"),
        ]
        yy = top + 56
        for name, surf, prim, bar in presets:
            for j, c in enumerate((surf, prim, bar)):
                ph.circle(28 + j * 20, yy + 20, 8, c if c != "#FFFFFF" else "#FFFFFF")
                ph.circle(28 + j * 20, yy + 20, 8, None, p["divider"], 1)
            ph.text(100, yy + 20, name, 15, p["text"])
            if name == "Linear":
                ph.icon("check", 346, yy + 12, 18, p["accent"])
            yy += 56
    ph.nav_bar(p)
    return ph.img


# ----------------------------------------------------------------------------- 输出
def save(img, name, scale=OUT):
    out = img.resize((int(W * scale), int(H * scale)), Image.LANCZOS)
    path = os.path.join(HERE, name)
    out.save(path)
    print("wrote", path, out.size)
    return out


def label(img, text, dark=False):
    """在图片上方加一条标题栏（仅用于拼图）。"""
    band = 44
    canvas = Image.new("RGB", (img.width, img.height + band), "#0F1115" if dark else "#F2F3F5")
    d = ImageDraw.Draw(canvas)
    f = ImageFont.truetype(FONT_PATH, 24)
    d.text((18, band // 2), text, font=f, fill="#E8EAED" if dark else "#4A4F55", anchor="lm")
    canvas.paste(img, (0, band))
    return canvas


def pair(a, b, la, lb, name, dark_band=False):
    gap = 28
    pa = label(a, la, dark=dark_band)
    pb = label(b, lb, dark=dark_band)
    out = Image.new("RGB", (pa.width * 2 + gap, pa.height), "#E9EBEE" if not dark_band else "#0F1115")
    out.paste(pa, (0, 0))
    out.paste(pb, (pa.width + gap, 0))
    path = os.path.join(HERE, name)
    out.save(path)
    print("wrote", path, out.size)


def main():
    before = save(frame_before(), "clean-00-before.png")
    dual_l = save(frame_dual(False), "clean-01-dual-light.png")
    dual_d = save(frame_dual(True), "clean-02-dual-dark.png")
    drawer = save(frame_drawer(False), "clean-03-drawer.png")
    settings = save(frame_settings(False), "clean-04-settings.png")
    selection = save(frame_dual(False, selection=True), "clean-05-selection.png")
    single = save(frame_dual(False, single=True), "clean-06-single.png")
    empty = save(frame_dual(False, empty=True), "clean-07-empty.png")
    sheet = save(frame_sheet(False), "clean-08-sheet.png")
    dual_linear = save(frame_dual(pal=LINEAR_LIGHT), "clean-12-dual-linear.png")
    linear_settings = save(frame_settings_linear(False), "settings-linear-light.png")
    linear_settings_d = save(frame_settings_linear(True), "settings-linear-dark.png")
    linear_palette = save(frame_settings_linear(False, sheet=True), "settings-linear-palette.png")
    sheet_d = save(frame_sheet(True), "clean-09-sheet-dark.png")

    pair(before, dual_l, "现状 · MT 复刻", "Clean 1.0 · 双列", "clean-10-before-after.png")
    pair(dual_l, selection, "双列 · 普通", "双列 · 已选 3", "preview-01-dual-selection.png")
    pair(single, empty, "单列 · 时间右对齐", "空态 · 空文件夹 / 无匹配项", "preview-02-single-empty.png")
    pair(linear_settings, linear_palette, "设置 · Linear 主题", "设置 · 配色选择", "preview-05-linear-settings.png")
    pair(linear_settings_d, sheet_d, "设置 · Linear 深色", "长按动作单 · 深色", "preview-06-linear-dark.png", dark_band=True)
    pair(drawer, settings, "抽屉 · 取代首页", "旧版设置 · 对照", "preview-03-drawer-settings.png")
    pair(dual_d, sheet_d, "双列 · 深色", "长按动作单 · 无标题废话", "preview-04-dark-sheet.png", dark_band=True)

    # 四联总览
    gap = 24
    cols, rows = 2, 2
    cw, ch = dual_l.width, dual_l.height + 44
    sheet4 = Image.new("RGB", (cw * cols + gap, ch * rows + gap), "#E9EBEE")
    cells = [
        (label(dual_l, "双列 · 浅色"), 0, 0),
        (label(dual_d, "双列 · 深色"), 1, 0),
        (label(drawer, "抽屉 · 取代首页"), 0, 1),
        (label(settings, "设置 · 无说明段"), 1, 1),
    ]
    for im, cx, cy in cells:
        sheet4.paste(im, (cx * (cw + gap), cy * (ch + gap)))
    path = os.path.join(HERE, "clean-11-screens.png")
    sheet4.save(path)
    print("wrote", path, sheet4.size)


if __name__ == "__main__":
    main()
