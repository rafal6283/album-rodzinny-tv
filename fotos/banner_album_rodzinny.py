#!/usr/bin/env python3
"""Baner (640×360) i ikona (192×192) aplikacji „Album Rodzinny” w stylu interfejsu: granatowe tło z ciepłą poświatą,
logo z czterech kolorowych kafelków (jak w bocznej szynie, Thema.kt/Logo) i nazwa szeryfem (Instrument Serif, OFL).
Nazwa jest własna i ta sama w każdym języku, więc baner trafia do wszystkich map drawable-*-nodpi.

    python banner_album_rodzinny.py [src/main/res]
"""
import os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

RES = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "src/main/res")
SERIF = os.path.join(RES, "font/instrument_serif.ttf")
S = 4  # supersampling


def tlo(w, h):
    im = Image.new("RGB", (w, h), (9, 9, 15))
    glow = Image.new("RGB", (w, h), (0, 0, 0))
    d = ImageDraw.Draw(glow)
    d.ellipse([int(w * 0.35), int(-h * 0.9), int(w * 1.4), int(h * 0.9)], fill=(110, 40, 60))
    d.ellipse([int(-w * 0.3), int(h * 0.4), int(w * 0.5), int(h * 1.6)], fill=(40, 45, 95))
    glow = glow.filter(ImageFilter.GaussianBlur(w // 6))
    return Image.blend(im, glow, 0.75)


def logo(im, x, y, g):
    """Wachlarz trzech zdjęć z serduszkiem (jak Logo w Thema.kt); (x, y) = lewy górny róg kwadratu g×g."""
    g = int(g)
    kw, kh, r = int(g * 0.56), int(g * 0.68), int(g * 0.09)
    for kolor, kat, dx in [((239, 91, 91), 16, -0.08), ((52, 179, 160), -16, 0.08), ((245, 184, 61), 0, 0.0)]:
        warstwa = Image.new("RGBA", (g, g), (0, 0, 0, 0))
        dd = ImageDraw.Draw(warstwa)
        lx = g / 2 - kw / 2 + dx * g
        dd.rounded_rectangle([lx, g * 0.16, lx + kw, g * 0.16 + kh], radius=r, fill=kolor + (255,))
        # PIL obraca przeciwnie do Compose, stąd odwrócone kąty; środek obrotu u dołu karty
        warstwa = warstwa.rotate(kat, resample=Image.BICUBIC, center=(g / 2 + dx * g, g * 0.92))
        im.paste(warstwa, (int(x), int(y)), warstwa)
    # serduszko z klasycznej krzywej (gładkie, bez schodków)
    import math
    d = ImageDraw.Draw(im)
    cx, cy, s = x + g / 2, y + g * 0.50, g * 0.15 / 16
    pkt = []
    for i in range(200):
        t = 2 * math.pi * i / 200
        hx = 16 * math.sin(t) ** 3
        hy = 13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t)
        pkt.append((cx + hx * s * 1.1, cy - hy * s * 1.1 + s * 2))
    d.polygon(pkt, fill="white")


def baner(nazwa="Album Rodzinny"):
    w, h = 640 * S, 360 * S
    im = tlo(w, h)
    d = ImageDraw.Draw(im)
    logo(im, 56 * S, 112 * S, 136 * S)
    f = ImageFont.truetype(SERIF, 70 * S)
    slowa = nazwa.split(" ")
    d.text((214 * S, 96 * S), slowa[0], font=f, fill=(241, 241, 246))
    d.text((214 * S, 174 * S), " ".join(slowa[1:]), font=f, fill=(241, 241, 246))
    return im.resize((640, 360), Image.LANCZOS)


def ikona(px=192):
    w = 192 * S
    im = tlo(w, w)
    m = Image.new("L", (w, w), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w - 1, w - 1], radius=40 * S, fill=255)
    d = ImageDraw.Draw(im)
    logo(im, 34 * S, 30 * S, 124 * S)
    out = Image.new("RGBA", (w, w), (0, 0, 0, 0))
    out.paste(im, (0, 0), m)
    return out.resize((px, px), Image.LANCZOS)


if __name__ == "__main__":
    # Własne nazwy zasobów (nie ic_fotos / banner z aplikacji źródłowej): launcher Android TV trzyma ikony i banery w
    # pamięci podręcznej i przy tych samych nazwach potrafił dalej pokazywać stare logo, także po reinstalacji.
    b = baner()
    b.save(os.path.join(RES, "drawable-nodpi/banner_album_rodzinny.png"))
    print("baner: drawable-nodpi/banner_album_rodzinny.png")
    # ikona w każdej gęstości (xhdpi = 192 px jak dotąd), żeby launcher nie skalował ani nie sięgał po inny zasób
    for gestosc, px in [("mdpi", 96), ("hdpi", 144), ("xhdpi", 192), ("xxhdpi", 288), ("xxxhdpi", 384)]:
        d = os.path.join(RES, "mipmap-" + gestosc)
        os.makedirs(d, exist_ok=True)
        ikona(px).save(os.path.join(d, "ic_album_rodzinny.png"))
        print("ikona:", d, px)
