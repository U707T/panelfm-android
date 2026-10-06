#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PanelFM 启动图标生成器（自适应前景层 + 旧式图标）。

两条硬规矩（都是被真机教出来的）
--------------------------------
1) **按「可见区」摆图形**：自适应画布 108dp，但启动器只显示**正中 72dp**（外圈 18dp 被
   裁掉并放大约 1.5×）。所以图形要按 72dp 摆，否则真机上会比设计稿大 1.5 倍。

2) **前景层只放图形、其余全透明**：有些桌面/主题（第三方图标包、主题图标）会把
   「自己的底板」+「App 的前景层」拼起来 —— 它们默认前景层是「图形 + 透明底」。
   如果前景层铺成满幅不透明方块，就会被原样贴上去：**深色方块 + 一圈白边**、
   而且方角由我们自己带出来（形状本该由系统蒙版给）。背景色交给
   `ic_launcher_background.png`（#272727 满幅）负责，前景层不画底。

摆放参数
--------
ART_DP = 图形画布（= 设计稿画布）放在 108dp 画布上的边长：

    ART_DP = 72   设计稿 1:1 落在「可见区」内 —— 真机所见 == 设计稿所见（当前值）
    ART_DP = 64   比 1:1 再小 11%（文件夹约占图标块 51%）
    ART_DP = 54   比 1:1 再小 25%（文件夹约占图标块 43%）

用法
----
    python3 tools/gen_launcher_icon.py              # 默认 ART_DP=72
    ART_DP=54 python3 tools/gen_launcher_icon.py

素材（都在 tools/art/）
-----------------------
* ic_launcher_glyph_108dp.png —— 图形层：文件夹/FM，透明底（前景层用它）
* ic_launcher_art_108dp.png   —— 设计稿整块：#272727 底 + 图形（旧式图标用它）
两张图几何完全一致（432px = 108dp@xxxhdpi），只是底色一个透明一个不透明。
"""
import os
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'app/src/main/res')
GLYPH = os.path.join(ROOT, 'tools/art/ic_launcher_glyph_108dp.png')
MASTER = os.path.join(ROOT, 'tools/art/ic_launcher_art_108dp.png')

ART_DP = float(os.environ.get('ART_DP', '72'))   # 图形画布在 108dp 画布上的边长
LEGACY_DP = 48.0                                 # 旧式图标（Android 7 / 无蒙版场景）
CANVAS_DP = 108.0
DENS = [('mdpi', 1.0), ('hdpi', 1.5), ('xhdpi', 2.0), ('xxhdpi', 3.0), ('xxxhdpi', 4.0)]


def main():
    if ART_DP > CANVAS_DP:
        raise SystemExit('ART_DP 不能大于 108dp（会超出画布）')
    glyph = Image.open(GLYPH).convert('RGBA')
    master = Image.open(MASTER).convert('RGBA')
    for name, scale in DENS:
        d = os.path.join(RES, 'mipmap-%s' % name)
        canvas, art = int(round(CANVAS_DP * scale)), int(round(ART_DP * scale))

        # 1) 自适应前景：图形居中，四周透明（+ 不铺底！）
        fg = Image.new('RGBA', (canvas, canvas), (0, 0, 0, 0))
        off = (canvas - art) // 2
        fg.paste(glyph.resize((art, art), Image.LANCZOS), (off, off))
        fg.save(os.path.join(d, 'ic_launcher_foreground.png'))

        # 2) 旧式图标：设计稿整块 1:1，方形满幅、不烘焙任何圆角（形状交给启动器/蒙版）
        n = int(round(LEGACY_DP * scale))
        legacy = master.resize((n, n), Image.LANCZOS)
        legacy.save(os.path.join(d, 'ic_launcher.png'))
        legacy.save(os.path.join(d, 'ic_launcher_round.png'))

        print('%-8s canvas=%3dpx  art=%3dpx (%.0f%% of visible 72dp)  legacy=%3dpx -> %s'
              % (name, canvas, art, ART_DP / 72 * 100, n, os.path.relpath(d, ROOT)))


if __name__ == '__main__':
    main()
