# -*- coding: utf-8 -*-
from PIL import Image, ImageDraw, ImageFont
import os

BASE = r"C:\Projects\AudioClipper\app\src\main\res"
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

PRIMARY = (63, 81, 181)       # 3F51B5
PRIMARY_DARK = (48, 63, 159)
WHITE = (255, 255, 255)
ACCENT = (255, 87, 34)        # FF5722

def make_icon(size):
    # 2倍超采样抗锯齿
    S = size * 4
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 圆角矩形背景
    r = S // 5
    d.rounded_rectangle([0, 0, S - 1, S - 1], radius=r, fill=PRIMARY)

    # 音波竖条（居中）
    bars = [(0.26, 0.30, 0.46), (0.40, 0.20, 0.60), (0.54, 0.14, 0.66), (0.68, 0.24, 0.56)]
    # (x中心比例, 顶比例, 底比例)
    for cx, top, bot in bars:
        x = int(S * cx)
        w = max(S // 28, 2)
        y1 = int(S * top)
        y2 = int(S * bot)
        d.rounded_rectangle([x - w // 2, y1, x + w // 2, y2], radius=w // 3, fill=WHITE)

    # 橙色进度线（波形编辑意象）
    ly = int(S * 0.5)
    d.rectangle([int(S * 0.20), ly - S // 120, int(S * 0.80), ly + S // 120], fill=ACCENT)

    img = img.resize((size, size), Image.LANCZOS)
    return img

for dpi, size in DENSITIES.items():
    folder = os.path.join(BASE, "mipmap-" + dpi)
    os.makedirs(folder, exist_ok=True)
    make_icon(size).save(os.path.join(folder, "ic_launcher.png"))
    print(dpi, size, "OK")

print("全部图标生成完毕")