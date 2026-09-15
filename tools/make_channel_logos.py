#!/usr/bin/env python3
"""Generate channel logo tiles for the store-screenshot demo playlist.

Pure stdlib, same technique as make_play_assets.py (supersampled PNG writer).
Each logo is a rounded tile in its channel's colour with the channel initials
in white, so the demo channel list looks like a real lineup instead of a wall
of identical placeholder icons - without borrowing anyone's actual branding.

Output: store/logos/<slug>.png (192x192)
"""
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "..", "store", "logos"))
SIZE = 192
SS = 4

WHITE = (255, 255, 255, 255)
NONE = (0, 0, 0, 0)

# --------------------------------------------------------------- 5x7 font
FONT = {
    "A": ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    "B": ["11110", "10001", "10001", "11110", "10001", "10001", "11110"],
    "C": ["01110", "10001", "10000", "10000", "10000", "10001", "01110"],
    "D": ["11110", "10001", "10001", "10001", "10001", "10001", "11110"],
    "E": ["11111", "10000", "10000", "11110", "10000", "10000", "11111"],
    "F": ["11111", "10000", "10000", "11110", "10000", "10000", "10000"],
    "G": ["01110", "10001", "10000", "10111", "10001", "10001", "01111"],
    "H": ["10001", "10001", "10001", "11111", "10001", "10001", "10001"],
    "I": ["11111", "00100", "00100", "00100", "00100", "00100", "11111"],
    "J": ["00111", "00010", "00010", "00010", "00010", "10010", "01100"],
    "K": ["10001", "10010", "10100", "11000", "10100", "10010", "10001"],
    "L": ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
    "M": ["10001", "11011", "10101", "10101", "10001", "10001", "10001"],
    "N": ["10001", "11001", "10101", "10011", "10001", "10001", "10001"],
    "O": ["01110", "10001", "10001", "10001", "10001", "10001", "01110"],
    "P": ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
    "Q": ["01110", "10001", "10001", "10001", "10101", "10010", "01101"],
    "R": ["11110", "10001", "10001", "11110", "10100", "10010", "10001"],
    "S": ["01111", "10000", "10000", "01110", "00001", "00001", "11110"],
    "T": ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
    "U": ["10001", "10001", "10001", "10001", "10001", "10001", "01110"],
    "V": ["10001", "10001", "10001", "10001", "10001", "01010", "00100"],
    "W": ["10001", "10001", "10001", "10101", "10101", "11011", "10001"],
    "X": ["10001", "10001", "01010", "00100", "01010", "10001", "10001"],
    "Y": ["10001", "10001", "01010", "00100", "00100", "00100", "00100"],
    "Z": ["11111", "00001", "00010", "00100", "01000", "10000", "11111"],
    "0": ["01110", "10001", "10011", "10101", "11001", "10001", "01110"],
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "2": ["01110", "10001", "00001", "00010", "00100", "01000", "11111"],
    "3": ["11111", "00010", "00100", "00010", "00001", "10001", "01110"],
    "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    "5": ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
    "6": ["00110", "01000", "10000", "11110", "10001", "10001", "01110"],
    "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    "8": ["01110", "10001", "10001", "01110", "10001", "10001", "01110"],
    "9": ["01110", "10001", "10001", "01111", "00001", "00010", "01100"],
    " ": ["00000", "00000", "00000", "00000", "00000", "00000", "00000"],
}
GW, GH = 5, 7

# (slug, initials, tile colour, pale tint for the inner ring)
CHANNELS = [
    ("world-news-hd",    "WN",  (37, 99, 235)),
    ("headlines-24",     "H24", (30, 64, 175)),
    ("global-report",    "GR",  (14, 116, 144)),
    ("cinema-one",       "C1",  (147, 51, 234)),
    ("classic-films",    "CF",  (126, 34, 206)),
    ("prime-movies",     "PM",  (88, 28, 135)),
    ("sports-live",      "SL",  (22, 163, 74)),
    ("motorsport-tv",    "MT",  (21, 128, 61)),
    ("extreme-sports",   "ES",  (5, 150, 105)),
    ("kids-club",        "KC",  (234, 88, 12)),
    ("cartoon-cave",     "CC",  (249, 115, 22)),
    ("wild-earth",       "WE",  (101, 163, 13)),
    ("nature-4k",        "N4",  (77, 124, 15)),
    ("history-vault",    "HV",  (120, 113, 108)),
    ("music-videos",     "MV",  (219, 39, 119)),
    ("dance-hits",       "DH",  (190, 24, 93)),
    ("retro-tunes",      "RT",  (162, 28, 175)),
    ("the-kitchen",      "TK",  (245, 158, 11)),
    ("travel-guide",     "TG",  (8, 145, 178)),
    ("home-and-garden",  "HG",  (16, 185, 129)),
    ("tech-today",       "TT",  (59, 130, 246)),
    ("laugh-track",      "LT",  (250, 204, 21)),
    ("drama-zone",       "DZ",  (220, 38, 38)),
    ("sci-fi-world",     "SW",  (99, 102, 241)),
]


def in_rounded_rect(px, py, x, y, w, h, r):
    if not (x <= px < x + w and y <= py < y + h):
        return False
    cx = min(max(px, x + r), x + w - r - 1)
    cy = min(max(py, y + r), y + h - r - 1)
    dx, dy = px - cx, py - cy
    return dx * dx + dy * dy <= r * r


def glyph_pixel(px, py, text, scale):
    """1 when the point lands on a lit font pixel of the centred text."""
    tw = len(text) * GW + (len(text) - 1)
    x0 = (SIZE - tw * scale) / 2.0
    y0 = (SIZE - GH * scale) / 2.0
    lx = (px - x0) / scale
    ly = (py - y0) / scale
    if lx < 0 or ly < 0:
        return 0
    col = int(lx) // (GW + 1)
    if col >= len(text):
        return 0
    gx = int(lx) - col * (GW + 1)
    gy = int(ly)
    if gx >= GW or gy >= GH:
        return 0
    return 1 if FONT[text[col]][gy][gx] == "1" else 0


def render_logo(initials, colour):
    def pixel(px, py):
        # tile
        if not in_rounded_rect(px, py, 6, 6, SIZE - 12, SIZE - 12, 46):
            return NONE
        # initials win over the tile
        scale = 9 if len(initials) == 1 else (7 if len(initials) == 2 else 5)
        if glyph_pixel(px, py, initials, scale):
            return WHITE
        # slightly lighter band across the top for depth
        shade = tuple(min(255, int(c * 1.18)) for c in colour[:3]) + (255,)
        if py < SIZE * 0.34:
            return shade
        return colour + (255,)
    return pixel


def png_encode(raw_rgba, w, h):
    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)
    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    idat = zlib.compress(
        b"".join(b"\x00" + raw_rgba[y * w * 4:(y + 1) * w * 4] for y in range(h)), 9)
    return sig + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")


def render(pixel_fn):
    rows = []
    for y in range(SIZE):
        row = bytearray()
        for x in range(SIZE):
            r = g = b = a = 0
            for sy in range(SS):
                for sx in range(SS):
                    pr, pg, pb, pa = pixel_fn((x * SS + sx) / SS, (y * SS + sy) / SS)
                    r += pr * pa; g += pg * pa; b += pb * pa; a += pa
            n = SS * SS
            row += bytes(NONE) if a == 0 else bytes((r // a, g // a, b // a, a // n))
        rows.append(bytes(row))
    return b"".join(rows)


def main():
    os.makedirs(OUT, exist_ok=True)
    total = 0
    for slug, initials, colour in CHANNELS:
        raw = render(render_logo(initials, colour))
        path = os.path.join(OUT, f"{slug}.png")
        with open(path, "wb") as fh:
            fh.write(png_encode(raw, SIZE, SIZE))
        total += os.path.getsize(path)
    print(f"wrote {len(CHANNELS)} logos to store/logos ({total:,} bytes total)")
    print("  " + ", ".join(s for s, _, _ in CHANNELS[:4]) + ", ...")


if __name__ == "__main__":
    main()
