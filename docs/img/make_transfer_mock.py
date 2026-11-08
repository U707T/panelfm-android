#!/usr/bin/env python3
"""生成传输任务 UI 的视觉对照图（v2.3.0 内联条 → v2.3.2 模态对话框）。

这不是截图（沙箱无 /dev/kvm，adb 起不来），而是按代码里的实际尺寸/文案 1:1 画的
示意图：宽 393dp（Pixel 类机型）、2x 缩放。所有文案取自真实格式化函数会产出的字符串。
"""
from PIL import Image, ImageDraw, ImageFont

SCALE = 2
W, H = 393, 720

FONT = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"

def f(size, bold=False):
    return ImageFont.truetype(FONT, int(size * SCALE))

# 浅色主题（MT 蓝）取色：与 MaterialTheme.colorScheme 默认档一致
SURFACE = (255, 255, 255)
SURFACE_VARIANT = (232, 234, 240)
ON_SURFACE = (26, 27, 30)
ON_VARIANT = (90, 94, 102)
PRIMARY = (25, 103, 210)
ERROR = (186, 26, 26)
TERTIARY = (0, 110, 92)
DIVIDER = (224, 226, 232)
TRACK = (238, 240, 244)


def dp(v):
    return int(v * SCALE)


def text(d, xy, s, size, color=ON_SURFACE, bold=False, anchor="la"):
    d.text((dp(xy[0]), dp(xy[1])), s, font=f(size, bold), fill=color, anchor=anchor)


def rect(d, box, color, radius=0):
    x0, y0, x1, y1 = [dp(v) for v in box]
    if radius:
        d.rounded_rectangle([x0, y0, x1, y1], radius=dp(radius), fill=color)
    else:
        d.rectangle([x0, y0, x1, y1], fill=color)


def bar(d, box, progress, color, height, track=TRACK):
    x0, y0, x1, y1 = box
    rect(d, (x0, y0, x1, y0 + height), track, radius=height / 2)
    w = (x1 - x0) * max(0.0, min(1.0, progress))
    if w > 0.5:
        rect(d, (x0, y0, x0 + w, y0 + height), color, radius=height / 2)


def phone(title):
    img = Image.new("RGB", (dp(W), dp(H)), SURFACE)
    d = ImageDraw.Draw(img)
    # 状态栏 + 顶栏（MT 56dp）
    rect(d, (0, 0, W, 24), SURFACE_VARIANT)
    text(d, (16, 6), "9:41", 10, ON_VARIANT)
    rect(d, (0, 24, W, 80), SURFACE)
    text(d, (W / 2, 32), title, 15, ON_SURFACE, bold=True, anchor="ma")
    text(d, (W / 2, 58), "复制 -> · 移动 -> · 删除 · 重命名", 9, ON_VARIANT, anchor="ma")
    rect(d, (0, 80, W, 81), DIVIDER)
    # 两栏内容（示意）
    rect(d, (0, 81, W / 2 - 1, 470), SURFACE)
    rect(d, (W / 2 + 1, 81, W, 470), SURFACE)
    rect(d, (W / 2 - 1, 81, W / 2 + 1, 470), DIVIDER)
    for i, name in enumerate(["Vedios", "DCIM", "Download", "兄弟三人行.mp4", "ez122814.mp4"]):
        y = 92 + i * 40
        text(d, (10, y + 6), name, 13, ON_SURFACE)
        text(d, (10, y + 24), "26-11-07 14:0%d" % i, 10, ON_VARIANT)
    for i, name in enumerate(["一键另存", "backup"]):
        y = 92 + i * 40
        text(d, (W / 2 + 12, y + 6), name, 13, ON_SURFACE)
        text(d, (W / 2 + 12, y + 24), "26-11-07 14:0%d" % i, 10, ON_VARIANT)
    return img, d


def bottom_bar(d):
    rect(d, (0, 664, W, 720), SURFACE)
    rect(d, (0, 664, W, 665), DIVIDER)
    labels = ["新建", "排序", "过滤", "返回"]
    for i, l in enumerate(labels):
        text(d, (W / 8 + i * W / 4, 686), l, 10, ON_VARIANT, anchor="ma")


# ============================================================ 旧：v2.3.0 内联任务条
def draw_old():
    img, d = phone("PanelFM · v2.3.0")
    bottom_bar(d)

    # 任务条容器：surface + 顶部 1px 分隔线，内边距 h8 v6，任务行 10dp 圆角
    rect(d, (0, 500, W, 664), SURFACE)
    rect(d, (0, 500, W, 501), DIVIDER)

    rows = [
        ("复制 · 兄弟三人行.mp4", "30%", 0.30, PRIMARY, "1/2 项 · 282.82 M / 949.47 M · 49.81 M/s · 剩 0:13"),
        ("复制 · ez122814.mp4", "62%", 0.62, PRIMARY, "1/1 项 · 588.20 M / 950.58 M · 66.05 M/s · 剩 0:06"),
    ]
    y = 507
    for name, pct, prog, accent, detail in rows:
        rect(d, (8, y, W - 8, y + 74), SURFACE_VARIANT, radius=10)
        rect(d, (18, y + 16, 24, y + 22), accent, radius=3)
        text(d, (31, y + 11), name, 12, ON_SURFACE, bold=True)
        text(d, (W - 108, y + 11), pct, 12, accent, bold=True)
        # 图标按钮 28dp（⏸ / ✕）
        text(d, (W - 74, y + 10), "⏸", 13, ON_VARIANT)
        text(d, (W - 40, y + 10), "✕", 13, ON_VARIANT)
        text(d, (31, y + 32), detail, 10, ON_VARIANT)
        bar(d, (31, y + 62, W - 18, y + 64), prog, accent, 2, track=(240, 242, 246))
        y += 80

    text(d, (W / 2, 660 - 4), "● 缺点：两行卡片 × 2 = 148dp，把底栏顶出屏幕", 9, ERROR, anchor="ma")
    return img


# ============================================================ 新：v2.3.2 模态对话框
def draw_new():
    img, d = phone("PanelFM · v2.3.2")
    bottom_bar(d)

    # 底部胶囊（单行，32dp）
    cap_w = 150
    cx = (W - cap_w) / 2
    rect(d, (cx, 620, cx + cap_w, 650), SURFACE_VARIANT, radius=15)
    rect(d, (cx + 10, 632, cx + 16, 638), PRIMARY, radius=3)
    text(d, (cx + 23, 628), "进行中 · 共 2 个", 10, ON_SURFACE, bold=True)
    text(d, (cx + 103, 628), "30%", 10, PRIMARY, bold=True)
    bar(d, (cx + 126, 632, cx + 140, 635), 0.30, PRIMARY, 3, track=(250, 250, 252))

    # 遮罩
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 90))
    img.paste(Image.alpha_composite(img.convert("RGBA"), overlay).convert("RGB"), (0, 0))
    d = ImageDraw.Draw(img)

    # 对话框（16dp 圆角，宽约 340dp）
    x0, y0, x1, y1 = 26, 224, W - 26, 434
    rect(d, (x0, y0, x1, y1), SURFACE, radius=16)

    # 标题行：徽标 + 标题 + 百分比
    rect(d, (x0 + 16, y0 + 16, x0 + 16 + 44, y0 + 16 + 20), (232, 240, 254), radius=10)
    text(d, (x0 + 22, y0 + 20), "进行中", 9, PRIMARY)
    text(d, (x0 + 68, y0 + 16), "复制 1 项", 13, ON_SURFACE, bold=True)
    text(d, (x1 - 16, y0 + 16), "30%", 13, PRIMARY, bold=True, anchor="ra")

    # 键值明细：标签 40dp，值左缘对齐
    rows = [
        ("名称", "兄弟三人行.mp4", ON_SURFACE),
        ("从", "Vedios", ON_SURFACE),
        ("到", "一键另存", ON_SURFACE),
        ("进度", "282.82 M / 949.47 M", ON_SURFACE),
        ("速度", "49.81 M/s · 剩 0:13", ON_SURFACE),
    ]
    y = y0 + 50
    for label, value, color in rows:
        text(d, (x0 + 16, y), label, 9, ON_VARIANT)
        text(d, (x0 + 16 + 40, y - 1), value, 11, color)
        y += 21

    # 进度条贴底（总进度 4dp）
    bar(d, (x0 + 16, y + 6, x1 - 16, y + 10), 0.30, PRIMARY, 4, track=(238, 240, 244))

    # 动作行
    ay = y + 24
    text(d, (x0 + 22, ay), "暂停", 12, PRIMARY)
    text(d, (x0 + 78, ay), "取消", 12, PRIMARY)
    text(d, (x1 - 92, ay), "另有 1 个", 11, ON_VARIANT, anchor="ra")
    text(d, (x1 - 16, ay), "后台运行", 12, PRIMARY, anchor="ra")

    text(d, (W / 2, 500), "● 一次只说一件事：一个任务 + 贴底进度条", 9, TERTIARY, anchor="ma")
    text(d, (W / 2, 660), "● 关掉后底部只留一行胶囊（32dp），点它回来", 9, TERTIARY, anchor="ma")
    return img


def main():
    old = draw_old()
    new = draw_new()
    pad, header = 16, 34
    Wt = dp(W) * 2 + pad * 3
    Ht = dp(H) + header + pad * 2
    canvas = Image.new("RGB", (Wt, Ht), (247, 248, 250))
    d = ImageDraw.Draw(canvas)
    text(d, (pad, 8), "v2.3.0 内联任务条（旧）", 13, ERROR, bold=True)
    text(d, (pad * 2 + dp(W), 8), "v2.3.2 传输模态对话框（新）", 13, TERTIARY, bold=True)
    canvas.paste(old, (pad, header + pad))
    canvas.paste(new, (pad * 2 + dp(W), header + pad))
    out = "/workspace/panelfm-android/docs/img/transfer-dialog-v2.3.2.png"
    canvas.save(out)
    print(out, canvas.size)


if __name__ == "__main__":
    main()
