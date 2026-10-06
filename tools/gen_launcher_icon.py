#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PanelFM 启动图标（自适应前景层）生成器 —— 按「真机可见区」口径摆放。

为什么不能按 108dp 摆
----------------------
自适应图标（adaptive icon）的画布是 **108dp × 108dp**，但启动器交给蒙版的
只有**正中的 72dp × 72dp**（外圈各 18dp 是留给视差/动效的，会被裁掉）。
换句话说：画布进蒙版时会被放大约 **1.5 倍**（108 / 72）。

所以「设计稿 1:1 摆进 108dp 画布」的结果，在真机上看到的是设计稿的 **1.5 倍大** ——
这也是 v1.3.9 真机比模拟图大一圈的原因：那张模拟图把蒙版直接套在了整张 108dp 上。

摆放约定
--------
ART_DP = 设计稿画布放在 108dp 画布上的边长（居中、四周 #272727 全幅不透明）：

    ART_DP = 72   设计稿 1:1 落在「可见区」内 —— 真机所见 == 设计稿所见
    ART_DP = 64   比 1:1 再小 11%（文件夹约占蒙版宽 51%）
    ART_DP = 54   比 1:1 再小 25%（文件夹约占蒙版宽 43%，即「再缩一半」的那档）

用法
----
    python3 tools/gen_launcher_icon.py              # 默认 ART_DP=72
    ART_DP=54 python3 tools/gen_launcher_icon.py

素材：tools/art/ic_launcher_art_108dp.png —— 设计稿画布（432px = 108dp@xxxhdpi，
#272727 底 + 文件夹/FM，无黑角、无透明区）。
"""
import os
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'app/src/main/res')
ART = os.path.join(ROOT, 'tools/art/ic_launcher_art_108dp.png')

ART_DP = float(os.environ.get('ART_DP', '72'))   # 设计稿画布在 108dp 画布上的边长
FACE = (39, 39, 39, 255)                         # #272727 设计底色（全幅不透明，杜绝白边）
CANVAS_DP = 108.0
DENS = [('mdpi', 1.0), ('hdpi', 1.5), ('xhdpi', 2.0), ('xxhdpi', 3.0), ('xxxhdpi', 4.0)]


def main():
    master = Image.open(ART).convert('RGBA')
    if ART_DP > CANVAS_DP:
        raise SystemExit('ART_DP 不能大于 108dp（会超出画布）')
    for name, scale in DENS:
        canvas = int(round(CANVAS_DP * scale))
        art = int(round(ART_DP * scale))
        im = Image.new('RGBA', (canvas, canvas), FACE)
        off = (canvas - art) // 2
        im.paste(master.resize((art, art), Image.LANCZOS), (off, off))
        out = os.path.join(RES, 'mipmap-%s/ic_launcher_foreground.png' % name)
        im.save(out)
        print('%-8s canvas=%3dpx  art=%3dpx (%.0f%% of visible 72dp) -> %s'
              % (name, canvas, art, ART_DP / 72 * 100, os.path.relpath(out, ROOT)))


if __name__ == '__main__':
    main()
