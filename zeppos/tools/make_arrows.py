"""Gera as setas do GluView para o Zepp OS (64x64, branco, fundo transparente) em assets/common.{r,s}/arrow/.
Uso (na pasta zeppos): python -I tools/make_arrows.py"""
import math
import os
from PIL import Image, ImageDraw

SIZE, SS = 64, 4  # desenha 4x maior e reduz (borda suave)


def arrow_points(cx, cy, s, deg):
    t, w, h = s / 7.0, s / 2.6, s / 2.0
    pts = [(-h, -t), (0, -t), (0, -w), (h, 0), (0, w), (0, t), (-h, t)]
    a = math.radians(deg)
    c, n = math.cos(a), math.sin(a)
    return [(cx + x * c - y * n, cy - (x * n + y * c)) for x, y in pts]


def make(deg, double):
    big = SIZE * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if double:
        s = big * 0.48
        for cx in (big * 0.27, big * 0.73):
            d.polygon(arrow_points(cx, big / 2, s, deg), fill=(255, 255, 255, 255))
    else:
        d.polygon(arrow_points(big / 2, big / 2, big * 0.8, deg), fill=(255, 255, 255, 255))
    return img.resize((SIZE, SIZE), Image.LANCZOS)


ARROWS = {"double_up": (90, True), "up": (90, False), "up45": (45, False), "flat": (0, False),
          "down45": (-45, False), "down": (-90, False), "double_down": (-90, True)}

for target in ("common.r", "common.s"):
    out = os.path.join("assets", target, "arrow")
    os.makedirs(out, exist_ok=True)
    for name, (deg, double) in ARROWS.items():
        make(deg, double).save(os.path.join(out, name + ".png"))
print("ok")
