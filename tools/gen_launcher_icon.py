#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PanelFM 启动图标生成器（自适应前景层 + 旧式图标）。

三条硬规矩（前两条是早年真机教出来的，第三条是 2026-11-01 的统一口径）
--------------------------------------------------------------------
1) **按「可见区」摆图形**：自适应画布 108dp，但启动器只显示**正中 72dp**（外圈 18dp 被
   裁掉并放大约 1.5×）。所以图形要按 72dp 摆，否则真机上会比设计稿大 1.5 倍。

2) **前景层只放图形、其余全透明**：有些桌面/主题（第三方图标包、主题图标）会把
   「自己的底板」+「App 的前景层」拼起来 —— 它们默认前景层是「图形 + 透明底」。
   如果前景层铺成满幅不透明方块，就会被原样贴上去：**深色方块 + 一圈白边**、
   而且方角由我们自己带出来（形状本该由系统蒙版给）。背景色交给
   `ic_launcher_background.png`（#272727 满幅）负责，前景层不画底。

3) **标记占图标 41%**：与 MetaRikka 2026-11-01 定下的比例一致（那边实测
   自适应 41.0% / 传统 40.6%）。官方推荐的 50–55% 在真实桌面上偏挤。
   注意这里是**像素 bbox 宽度**占「可见区 / 图标边长」的比例，不是设计稿参数：
   本项目的设计稿画布 = 108dp，图形只占画布 57%，所以「缩设计稿画布」和
   「缩图形」不是一回事，改完必须用 `--report` 实测。

摆放参数
--------
MARK_W_RATIO = 标记（文件夹 + FM）bbox 宽度 ÷ 可见区（自适应）或图标边长（传统）

    0.41  当前口径（2026-11-01）
    0.50  Android 官方推荐区间的下沿 —— 真机上偏挤，仅作对照

用法
----
    python3 tools/gen_launcher_icon.py            # 出全部资源
    python3 tools/gen_launcher_icon.py --report   # 只打印实测比例，不写文件
    MARK_W_RATIO=0.50 python3 tools/gen_launcher_icon.py

素材（都在 tools/art/）
-----------------------
* ic_launcher_glyph_108dp.png —— 图形层：文件夹/FM，透明底（前景层用它）
* ic_launcher_art_108dp.png   —— 设计稿整块：#272727 底 + 图形（取它的底色）
两张图几何完全一致（432px = 108dp@xxxhdpi），只是底色一个透明一个不透明。
"""
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'app/src/main/res')
GLYPH = os.path.join(ROOT, 'tools/art/ic_launcher_glyph_108dp.png')
MASTER = os.path.join(ROOT, 'tools/art/ic_launcher_art_108dp.png')

CANVAS_DP = 108.0          # 自适应画布
VISIBLE_DP = 72.0          # 启动器可见区（外圈被裁掉）
LEGACY_DP = 48.0           # 旧式图标边长
MARK_W_RATIO = float(os.environ.get('MARK_W_RATIO', '0.41'))
DENS = [('mdpi', 1.0), ('hdpi', 1.5), ('xhdpi', 2.0), ('xxhdpi', 3.0), ('xxxhdpi', 4.0)]


def alpha_bbox(img, thr=8):
    """alpha > thr 的 bbox（LANCZOS 缩放会留一圈极淡的振铃，thr 太低会把它算进去）。"""
    a = img.getchannel('A').point(lambda v: 255 if v > thr else 0)
    bbox = a.getbbox()
    if not bbox:
        raise SystemExit('图形层是空的：%s' % GLYPH)
    return bbox


def mark_bbox_on_plate(img, plate, tol=6):
    """不透明旧式图标：用「与底板色的差异」找 bbox（不能看 alpha，整块都是不透明的）。"""
    import numpy as np
    a = np.asarray(img.convert('RGB')).astype(int)
    d = np.abs(a - np.array(plate)).max(2)
    ys, xs = np.nonzero(d > tol)
    if len(xs) == 0:
        raise SystemExit('旧式图标里找不到图形')
    return int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1


def scaled_glyph(glyph, bbox, target_w_px):
    """按「bbox 宽度 = target_w_px」缩放，并让 bbox 中心落在画布中心。"""
    x0, y0, x1, y1 = bbox
    k = target_w_px / float(x1 - x0)
    w, h = glyph.size
    im = glyph.resize((max(1, round(w * k)), max(1, round(h * k))), Image.LANCZOS)
    # 缩放后 bbox 中心
    cx, cy = (x0 + x1) / 2.0 * k, (y0 + y1) / 2.0 * k
    return im, cx, cy


def place(canvas, im, cx, cy):
    w, h = canvas.size
    canvas.alpha_composite(im, (round(w / 2 - cx), round(h / 2 - cy)))


def build(report_only=False):
    glyph = Image.open(GLYPH).convert('RGBA')
    master = Image.open(MASTER).convert('RGBA')
    bbox = alpha_bbox(glyph)
    plate = master.getpixel((0, 0))[:3]           # 设计稿底色（#272727）

    # 自检：设计稿整块的底色必须是纯色，否则「旧式图标 = 底板 + 图形」的拼法会露底
    if master.getchannel('A').getextrema()[0] != 255:
        raise SystemExit('设计稿整块不是满幅不透明，不能直接取底色')
    bg_colors = {master.getpixel(p)[:3] for p in ((0, 0), (431, 0), (0, 431), (431, 431), (216, 8), (8, 216))}
    if len(bg_colors) != 1:
        raise SystemExit('设计稿底色不是纯色（%s），旧式图标会露底' % bg_colors)

    measured = {}
    for name, scale in DENS:
        canvas_px = int(round(CANVAS_DP * scale))
        vis_px = VISIBLE_DP * scale
        target = MARK_W_RATIO * vis_px

        fg = Image.new('RGBA', (canvas_px, canvas_px), (0, 0, 0, 0))
        im, cx, cy = scaled_glyph(glyph, bbox, target)
        place(fg, im, cx, cy)

        n = int(round(LEGACY_DP * scale))
        legacy = Image.new('RGBA', (n, n), plate + (255,))
        im2, cx2, cy2 = scaled_glyph(glyph, bbox, MARK_W_RATIO * n)
        place(legacy, im2, cx2, cy2)

        if not report_only:
            d = os.path.join(RES, 'mipmap-%s' % name)
            os.makedirs(d, exist_ok=True)
            fg.save(os.path.join(d, 'ic_launcher_foreground.png'))
            legacy.save(os.path.join(d, 'ic_launcher.png'))
            legacy.save(os.path.join(d, 'ic_launcher_round.png'))
            Image.new('RGB', (canvas_px, canvas_px), plate).save(
                os.path.join(d, 'ic_launcher_background.png'))

        fb = alpha_bbox(fg)
        fw = fb[2] - fb[0]
        lb = mark_bbox_on_plate(legacy, plate)
        lw = lb[2] - lb[0]
        measured[name] = (fw / vis_px, lw / n, canvas_px, fw, lw)

    print('mark bbox 宽度 / 可见区(72dp) 与 / 传统图标边长：')
    for name, (fr, lr, px, fw, lw) in measured.items():
        print('  %-8s canvas=%3dpx  fg %3dpx = %.1f%%   legacy %3dpx = %.1f%%'
              % (name, px, fw, fr * 100, lw, lr * 100))
    fr, lr = measured['xxxhdpi'][0], measured['xxxhdpi'][1]
    print('目标 %.1f%% → 实测 自适应 %.1f%% / 传统 %.1f%%（xxxhdpi）'
          % (MARK_W_RATIO * 100, fr * 100, lr * 100))
    if report_only:
        return
    print('resources ->', os.path.relpath(RES, ROOT))


if __name__ == '__main__':
    build(report_only='--report' in sys.argv[1:])
