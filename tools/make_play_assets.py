#!/usr/bin/env python3
"""Generate the Google Play listing art for Internet TV Player - pure stdlib.

Same approach as SyncPlayer's make_icon.py (no PIL, no deps), with two extras:
4x supersampling so edges are antialiased, and a tiny 5x7 bitmap font so the
feature graphic can carry the app name.

Outputs (into play/):
    icon-512.png                 512x512   Play store listing icon
    feature-graphic-1024x500.png 1024x500  Play feature graphic

The artwork is the sibling of the SyncPlayer icon: near-black rounded tile,
two rounded video panels (blue in front, teal behind, blended where they
overlap) and a white play glyph on the centre of the front panel.
"""
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "play")

BG = (20, 22, 28, 255)          # #14161C near-black tile
TILE_A = (79, 156, 249, 255)    # #4F9CF9 blue  (front panel)
TILE_B = (57, 217, 142, 255)    # #39D98E teal  (back panel)
BLEND = ((TILE_A[0] + TILE_B[0]) // 2,
         (TILE_A[1] + TILE_B[1]) // 2,
         (TILE_A[2] + TILE_B[2]) // 2, 255)
WHITE = (255, 255, 255, 255)
NONE = (0, 0, 0, 0)

SS = 4                          # supersampling factor


def in_rounded_rect(px, py, x, y, w, h, r):
    """Point-in-rounded-rect, in supersampled coordinates."""
    if not (x <= px < x + w and y <= py < y + h):
        return False
    cx = min(max(px, x + r), x + w - r - 1)
    cy = min(max(py, y + r), y + h - r - 1)
    dx, dy = px - cx, py - cy
    return dx * dx + dy * dy <= r * r


def in_triangle(px, py, a, b, c):
    def sign(p1, p2, p3):
        return (p1[0] - p3[0]) * (p2[1] - p3[1]) - (p2[0] - p3[0]) * (p1[1] - p3[1])
    d1, d2, d3 = sign((px, py), a, b), sign((px, py), b, c), sign((px, py), c, a)
    neg = d1 < 0 or d2 < 0 or d3 < 0
    pos = d1 > 0 or d2 > 0 or d3 > 0
    return not (neg and pos)


# --------------------------------------------------------------- 5x7 font
# Only the glyphs the title needs: I N T E R V P L A Y and space.
FONT = {
    "I": ["11111", "00100", "00100", "00100", "00100", "00100", "11111"],
    "N": ["10001", "11001", "10101", "10011", "10001", "10001", "10001"],
    "T": ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
    "E": ["11111", "10000", "10000", "11110", "10000", "10000", "11111"],
    "R": ["11110", "10001", "10001", "11110", "10100", "10010", "10001"],
    "V": ["10001", "10001", "10001", "10001", "10001", "01010", "00100"],
    "P": ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
    "L": ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
    "A": ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    "Y": ["10001", "10001", "01010", "00100", "00100", "00100", "00100"],
    " ": ["00000", "00000", "00000", "00000", "00000", "00000", "00000"],
}
GW, GH, TRACK = 5, 7, 3         # glyph width/height and letter spacing


def text_width(text, scale):
    return len(text) * (GW + TRACK) * scale - TRACK * scale


def text_pixel(px, py, text, x0, y0, scale):
    """1 if the supersampled point lands on a lit glyph pixel."""
    local_x = (px - x0) / scale
    local_y = (py - y0) / scale
    if local_x < 0 or local_y < 0:
        return 0
    col = int(local_x) // (GW + TRACK)
    if col >= len(text):
        return 0
    ch = text[col]
    gx = int(local_x) - col * (GW + TRACK)
    gy = int(local_y)
    if gx >= GW or gy >= GH:
        return 0
    return 1 if FONT[ch][gy][gx] == "1" else 0


# --------------------------------------------------------------- renderers
def icon_pixel(px, py, size):
    """Play listing icon: rounded tile filling the square, art inside."""
    f = size / 512.0
    if not in_rounded_rect(px, py, 12 * f, 12 * f, 488 * f, 488 * f, 112 * f):
        return NONE
    teal = in_rounded_rect(px, py, 118 * f, 130 * f, 200 * f, 140 * f, 34 * f)
    blue = in_rounded_rect(px, py, 196 * f, 222 * f, 210 * f, 142 * f, 34 * f)
    if in_triangle(px, py, (256 * f, 258 * f), (346 * f, 293 * f), (256 * f, 328 * f)):
        return WHITE
    if teal and blue:
        return BLEND
    if blue:
        return TILE_A
    if teal:
        return TILE_B
    return BG


def feature_pixel(px, py, w, h, scale, art_x, art_y, art_scale, title, tx, ty, text_scale):
    """Play feature graphic: same motif, panels on the left, title on the right."""
    if text_pixel(px, py, title, tx, ty, text_scale):
        return WHITE

    ax = art_x + art_scale * 0
    teal = in_rounded_rect(px, py,
                           art_x + 118 * art_scale, art_y + 130 * art_scale,
                           200 * art_scale, 140 * art_scale, 34 * art_scale)
    blue = in_rounded_rect(px, py,
                           art_x + 196 * art_scale, art_y + 222 * art_scale,
                           210 * art_scale, 142 * art_scale, 34 * art_scale)
    if in_triangle(px, py,
                   (art_x + 256 * art_scale, art_y + 258 * art_scale),
                   (art_x + 346 * art_scale, art_y + 293 * art_scale),
                   (art_x + 256 * art_scale, art_y + 328 * art_scale)):
        return WHITE
    if teal and blue:
        return BLEND
    if blue:
        return TILE_A
    if teal:
        return TILE_B
    return BG


def render(w, h, pixel_fn):
    """Supersample by SS, then box-filter down to w x h (antialiasing).

    pixel_fn is called with *logical* float coordinates (0..w, 0..h); each of
    the SS*SS subsamples sits at a distinct fraction inside the pixel, which is
    what produces the antialiased edges.
    """
    rows = []
    for y in range(h):
        row = bytearray()
        for x in range(w):
            r = g = b = a = 0
            for sy in range(SS):
                for sx in range(SS):
                    pr, pg, pb, pa = pixel_fn((x * SS + sx) / SS, (y * SS + sy) / SS)
                    # premultiply so transparent pixels do not darken edges
                    r += pr * pa
                    g += pg * pa
                    b += pb * pa
                    a += pa
            n = SS * SS
            if a == 0:
                row += bytes(NONE)
            else:
                row += bytes((r // a, g // a, b // a, a // n))
        rows.append(bytes(row))
    return b"".join(rows)


def png_encode(raw_rgba, w, h):
    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)
    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    idat = zlib.compress(
        b"".join(b"\x00" + raw_rgba[y * w * 4:(y + 1) * w * 4] for y in range(h)), 9)
    return sig + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")


def main():
    os.makedirs(OUT, exist_ok=True)

    # ---- 512x512 listing icon
    raw = render(512, 512, lambda px, py: icon_pixel(px, py, 512))
    path = os.path.join(OUT, "icon-512.png")
    with open(path, "wb") as fh:
        fh.write(png_encode(raw, 512, 512))
    print(f"icon-512.png                 {os.path.getsize(path)} bytes")

    # ---- 1024x500 feature graphic
    W, H = 1024, 500
    art_scale = 0.62
    art_x, art_y = 40, 20
    title = "INTERNET TV PLAYER"
    text_scale = 6
    tw = text_width(title, text_scale)
    tx = 1024 - tw - 60
    ty = (500 - GH * text_scale) // 2
    raw = render(W, H, lambda px, py: feature_pixel(
        px, py, W, H, art_scale, art_x, art_y, art_scale, title, tx, ty, text_scale))
    path = os.path.join(OUT, "feature-graphic-1024x500.png")
    with open(path, "wb") as fh:
        fh.write(png_encode(raw, W, H))
    print(f"feature-graphic-1024x500.png {os.path.getsize(path)} bytes")
    print(f"title box: x={tx} y={ty} w={tw} h={GH * text_scale}")


if __name__ == "__main__":
    main()
