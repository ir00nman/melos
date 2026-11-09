#!/usr/bin/env python3
# Логотип Melos: готовая эмблема из logo-source.png (белый знак на чёрном), собранная
# на фирменной тёмной плашке со скруглением. Из неё — master png, icns, ico и ресурс иконки приложения.
import os, shutil, struct, subprocess
from PIL import Image, ImageDraw

S = 1024
BG = (7, 7, 10)         # почти чёрный, как у плашки старого лого
RADIUS = 0.225          # скругление плашки (как было)

here = os.path.dirname(os.path.abspath(__file__))
src = Image.open(os.path.join(here, "logo-source.png")).convert("RGBA")
if src.size != (S, S):
    src = src.resize((S, S), Image.LANCZOS)

# знак: яркость -> альфа (чёрный фон исчезает, края остаются гладкими), цвет — чистый белый
mark = Image.new("RGBA", (S, S), (255, 255, 255, 0))
# альфа знака — по яркости, но только внутри плашки: рендер SVG даёт белые углы вокруг
from PIL import ImageChops
mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * RADIUS), fill=255)
mark.putalpha(ImageChops.multiply(src.convert("L"), mask))

# сплошной чёрный фон (без рамки — рендер SVG на белом оставлял белые углы) + знак поверх
img = Image.new("RGBA", (S, S), BG + (255,))
img.alpha_composite(mark)

img.save(os.path.join(here, "melos-brain.png"))
print("png ok")

iconset = os.path.join(here, "melos.iconset")
os.makedirs(iconset, exist_ok=True)
for sz, name in [(16,"icon_16x16.png"),(32,"icon_16x16@2x.png"),(32,"icon_32x32.png"),(64,"icon_32x32@2x.png"),
                 (128,"icon_128x128.png"),(256,"icon_128x128@2x.png"),(256,"icon_256x256.png"),
                 (512,"icon_256x256@2x.png"),(512,"icon_512x512.png"),(1024,"icon_512x512@2x.png")]:
    img.resize((sz, sz), Image.LANCZOS).save(os.path.join(iconset, name))
subprocess.run(["iconutil", "-c", "icns", iconset, "-o", os.path.join(here, "melos.icns")], check=True)
print("icns ok")

def ico(files, out_path):
    header = struct.pack("<HHH", 0, 1, len(files))
    offset = 6 + 16 * len(files)
    entries = b""; body = b""
    for fp, sz in files:
        b = open(fp, "rb").read()
        w = 0 if sz >= 256 else sz
        entries += struct.pack("<BBBBHHII", w, w, 0, 0, 1, 32, len(b), offset)
        body += b; offset += len(b)
    open(out_path, "wb").write(header + entries + body)

tmp = os.path.join(here, "_icosizes"); os.makedirs(tmp, exist_ok=True)
files = []
for sz in (16, 24, 32, 48, 64, 128, 256):
    fp = os.path.join(tmp, f"{sz}.png")
    img.resize((sz, sz), Image.LANCZOS).save(fp)
    files.append((fp, sz))
ico(files, os.path.join(here, "melos.ico"))
print("ico ok")

# иконка окна/таскбара в самом приложении
shutil.copy(os.path.join(here, "melos-brain.png"), os.path.join(here, "..", "..", "app", "src", "main", "resources", "melos", "icon.png"))
print("app icon ok")
