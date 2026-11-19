#!/usr/bin/env python3
"""
Cut shared LimeZu swatches from kotlin-street/tools/swatches/atlas/atlas.png and paint
original 1980s pixel-art textures at 1x art resolution, enlarged 2x via NEAREST.
"""
import json
import struct
import zlib
from pathlib import Path
from pixel_font import FONT

R = Path(__file__).resolve().parent
TEX_DIR = R / "textures"
SRC_DIR = R / "source-materials"
TEX_DIR.mkdir(parents=True, exist_ok=True)
SRC_DIR.mkdir(parents=True, exist_ok=True)

ATLAS_DIR = R.parent.parent / "kotlin-street" / "tools" / "swatches" / "atlas"
REG = {}


def hex_rgba(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


INK = hex_rgba("#1e2030")
OUTLINE = hex_rgba("#292c40")
MIDNIGHT = hex_rgba("#2e324a")
MIDNIGHT_HI = hex_rgba("#404666")
MIDNIGHT_SH = hex_rgba("#222538")
MAGENTA = hex_rgba("#b84674")
MAGENTA_HI = hex_rgba("#e06aa0")
MAGENTA_SH = hex_rgba("#872f54")
CYAN = hex_rgba("#388894")
CYAN_HI = hex_rgba("#64bac7")
CYAN_SH = hex_rgba("#28646e")
CYAN_DK = hex_rgba("#1c464f")
GOLD = hex_rgba("#ffe49e")
GOLD_SH = hex_rgba("#d9a752")
CREAM = hex_rgba("#eee8df")
CREAM_SH = hex_rgba("#c8bfb2")
STEEL = hex_rgba("#7d819c")
STEEL_HI = hex_rgba("#a5aac7")
STEEL_SH = hex_rgba("#595d75")
CHARCOAL = hex_rgba("#282b3d")
CHARCOAL_HI = hex_rgba("#3e435c")
CHARCOAL_SH = hex_rgba("#1b1d2b")
VIOLET = hex_rgba("#5c4480")
VIOLET_HI = hex_rgba("#8264b0")
SUN_TOP = hex_rgba("#ff6b8b")
SUN_MID = hex_rgba("#ff9e66")
SUN_BOT = hex_rgba("#ffe082")


def read_png_rgba(path):
    data = Path(path).read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    pos = 8
    w = h = depth = ctype = 0
    plte = []
    trns = b""
    idat = bytearray()
    while pos < len(data):
        length, ctype_bytes = struct.unpack(">I4s", data[pos : pos + 8])
        chunk = data[pos + 8 : pos + 8 + length]
        pos += 12 + length
        t = ctype_bytes.decode("latin1")
        if t == "IHDR":
            w, h, depth, ctype, _, _, _ = struct.unpack(">IIBBBBB", chunk)
        elif t == "PLTE":
            plte = [(chunk[i], chunk[i + 1], chunk[i + 2]) for i in range(0, len(chunk), 3)]
        elif t == "tRNS":
            trns = chunk
        elif t == "IDAT":
            idat.extend(chunk)
        elif t == "IEND":
            break
    raw = zlib.decompress(bytes(idat))
    ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    bpp = max(1, (ch * depth) // 8)
    stride = (w * ch * depth + 7) // 8
    prev = bytearray(stride)
    cur = bytearray(stride)
    out = [[(0, 0, 0, 255)] * w for _ in range(h)]
    idx = 0
    for y in range(h):
        f = raw[idx]
        idx += 1
        for i in range(stride):
            x = raw[idx]
            idx += 1
            a = cur[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if f == 0:
                v = x
            elif f == 1:
                v = (x + a) & 0xFF
            elif f == 2:
                v = (x + b) & 0xFF
            elif f == 3:
                v = (x + ((a + b) >> 1)) & 0xFF
            else:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                v = (x + pr) & 0xFF
            cur[i] = v
        for x in range(w):
            if ctype == 6:
                out[y][x] = (cur[x * 4], cur[x * 4 + 1], cur[x * 4 + 2], cur[x * 4 + 3])
            elif ctype == 2:
                out[y][x] = (cur[x * 3], cur[x * 3 + 1], cur[x * 3 + 2], 255)
            elif ctype == 3:
                per = 8 // depth
                bval = cur[x // per]
                shift = 8 - depth * (x % per + 1)
                pi = (bval >> shift) & ((1 << depth) - 1)
                r, g, b_ = plte[pi]
                al = trns[pi] if pi < len(trns) else 255
                out[y][x] = (r, g, b_, al)
        prev[:] = cur
    return w, h, out


def write_png_rgba(path, w, h, rows):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        row = rows[y]
        for x in range(w):
            r, g, b, a = row[x]
            raw.extend((r, g, b, a))

    def chunk(tag, body):
        crc = zlib.crc32(tag + body) & 0xFFFFFFFF
        return struct.pack(">I", len(body)) + tag + body + struct.pack(">I", crc)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    comp = zlib.compress(bytes(raw), 9)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", comp) + chunk(b"IEND", b"")
    Path(path).write_bytes(png)


class Canvas:
    """Art-pixel canvas (1x art pixels); saved at 2x texel resolution via NEAREST."""

    def __init__(self, w, h, fill=INK):
        self.w = w
        self.h = h
        self.px = [[fill for _ in range(w)] for _ in range(h)]

    def point(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = c

    def line_h(self, x0, x1, y, c):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            self.point(x, y, c)

    def line_v(self, x, y0, y1, c):
        for y in range(min(y0, y1), max(y0, y1) + 1):
            self.point(x, y, c)

    def rect(self, x0, y0, x1, y1, fill=None, outline=None):
        if fill is not None:
            for y in range(y0, y1 + 1):
                for x in range(x0, x1 + 1):
                    self.point(x, y, fill)
        if outline is not None:
            self.line_h(x0, x1, y0, outline)
            self.line_h(x0, x1, y1, outline)
            self.line_v(x0, y0, y1, outline)
            self.line_v(x1, y0, y1, outline)

    def stepped_panel(self, x0, y0, w, h, base, hi, sh, dk=INK, ribs=0, wear_seed=7):
        x1, y1 = x0 + w - 1, y0 + h - 1
        for y in range(y0, y1 + 1):
            ry = y - y0
            for x in range(x0, x1 + 1):
                rx = x - x0
                if rx == 0 or ry == 0 or rx == w - 1 or ry == h - 1:
                    c = dk
                elif ry == 1 or rx == 1:
                    c = hi
                elif ry == h - 2 or rx == w - 2:
                    c = sh
                else:
                    bayer = ((rx & 1) << 1) | (ry & 1)
                    chip = ((rx * 37 + ry * 73 + wear_seed * 19) & 63) == 0
                    if ribs > 0 and (rx % ribs) == 0:
                        c = sh
                    elif ribs > 0 and (rx % ribs) == 1:
                        c = hi
                    elif chip:
                        c = sh
                    elif ry < h // 4 and bayer == 0:
                        c = hi
                    elif ry > (h * 2) // 3 and bayer >= 2:
                        c = sh
                    else:
                        c = base
                self.point(x, y, c)

    def text(self, x, y, s, c, shadow=None):
        cx = x
        for ch in s.upper():
            rows = FONT.get(ch, ["00000"] * 7)
            for yy, row in enumerate(rows):
                for xx, v in enumerate(row):
                    if v == "1":
                        if shadow is not None:
                            self.point(cx + xx + 1, y + yy + 1, shadow)
                        self.point(cx + xx, y + yy, c)
            cx += 6

    def save_2x(self, filename):
        tw, th = self.w * 2, self.h * 2
        rows = [[self.px[y // 2][x // 2] for x in range(tw)] for y in range(th)]
        write_png_rgba(TEX_DIR / filename, tw, th, rows)


# 1. Cut shared LimeZu swatches from kotlin-street/tools/swatches/atlas/atlas.png
atlas_w, atlas_h, atlas_px = read_png_rgba(ATLAS_DIR / "atlas.png")
atlas_meta = json.loads((ATLAS_DIR / "atlas.json").read_text())

SWATCH_IDS = [
    "wall_slate_dark",
    "block_courses",
    "brick_red",
    "roof_membrane_grey",
    "ballast",
    "asphalt_worn",
    "pavement",
]

provenance_entries = []
for sid in SWATCH_IDS:
    entry = atlas_meta[sid]
    ax, ay, aw, ah = entry["x"], entry["y"], entry["w"], entry["h"]
    crop_rows = [atlas_px[ay + y][ax : ax + aw] for y in range(ah)]
    write_png_rgba(TEX_DIR / f"{sid}.png", aw, ah, crop_rows)
    write_png_rgba(SRC_DIR / f"{sid}.png", aw, ah, crop_rows)
    provenance_entries.append({
        "id": sid,
        "atlas_rect": [ax, ay, aw, ah],
        "source": entry.get("entry", {}),
    })

(SRC_DIR / "provenance.json").write_text(
    json.dumps(
        {
            "atlas": "kotlin-street/tools/swatches/atlas/atlas.png",
            "licence": "LimeZu: Timerpg game use only, credit required",
            "swatches": provenance_entries,
        },
        indent=2,
    )
    + "\n"
)

# 2. Supplemental original 1980s pixel-art textures (1x art pixels -> 2x texels)
# 2a. midnight.png (64x64 art -> 128x128 texels): fluted midnight-indigo stucco panels
c_mid = Canvas(64, 64, MIDNIGHT)
c_mid.stepped_panel(0, 0, 64, 32, MIDNIGHT, MIDNIGHT_HI, MIDNIGHT_SH, INK, ribs=8, wear_seed=17)
c_mid.stepped_panel(0, 32, 64, 32, MIDNIGHT, MIDNIGHT_HI, MIDNIGHT_SH, INK, ribs=8, wear_seed=29)
# Horizontal 1980s Art-Deco speed-band relief lines
for y_band in (14, 16, 46, 48):
    c_mid.line_h(1, 62, y_band, CYAN_HI)
    c_mid.line_h(1, 62, y_band + 1, INK)
c_mid.save_2x("midnight.png")

# 2b. neon_trim.png (64x64 art -> 128x128 texels): 1980s electric magenta & cyan stepped ziggurat fascia
c_trim = Canvas(64, 64, MAGENTA)
c_trim.stepped_panel(0, 0, 64, 32, MAGENTA, MAGENTA_HI, MAGENTA_SH, INK, ribs=8, wear_seed=31)
c_trim.stepped_panel(0, 32, 64, 32, CYAN, CYAN_HI, CYAN_SH, INK, ribs=8, wear_seed=43)
c_trim.line_h(1, 62, 15, GOLD)
c_trim.line_h(1, 62, 47, MAGENTA_HI)
c_trim.save_2x("neon_trim.png")

# 2c. utility.png (128x128 art -> 256x256 texels): four 64x64 quadrants (zero bleed)
c_util = Canvas(128, 128, INK)
# Quadrant 0: metal [0, 0, 64, 64] (chrome stanchions, lighting truss, stainless bar counter)
c_util.stepped_panel(0, 0, 64, 64, STEEL, STEEL_HI, STEEL_SH, OUTLINE, ribs=8, wear_seed=41)
for ry in range(4, 60, 8):
    for rx in range(4, 60, 8):
        c_util.point(rx, ry, STEEL_HI)
        c_util.point(rx + 1, ry + 1, OUTLINE)
REG["metal"] = {"rect_art": [0, 0, 64, 64]}

# Quadrant 1: rubber [64, 0, 64, 64] (matte charcoal speaker stacks, stage deck, neon cabinets)
c_util.stepped_panel(64, 0, 64, 64, CHARCOAL, CHARCOAL_HI, CHARCOAL_SH, INK, ribs=16, wear_seed=53)
for sy in range(8, 56, 12):
    c_util.rect(70, sy, 120, sy + 8, outline=INK)
REG["rubber"] = {"rect_art": [64, 0, 64, 64]}

# Quadrant 2: magenta [0, 64, 64, 64] (hot-magenta tufted bar front & VIP booth upholstery)
c_util.stepped_panel(0, 64, 64, 64, MAGENTA, MAGENTA_HI, MAGENTA_SH, INK, ribs=8, wear_seed=67)
REG["magenta"] = {"rect_art": [0, 64, 64, 64]}

# Quadrant 3: cyan [64, 64, 64, 64] (electric-cyan interior acoustic tiles & ceiling grid)
c_util.stepped_panel(64, 64, 64, 64, CYAN, CYAN_HI, CYAN_SH, INK, ribs=8, wear_seed=79)
for gy in range(70, 122, 8):
    c_util.line_h(66, 125, gy, CYAN_DK)
REG["cyan"] = {"rect_art": [64, 64, 64, 64]}
c_util.save_2x("utility.png")

# 2d. glass.png (256x128 art -> 512x256 texels): 1980s illuminated glass-block walls & VIP doors
c_glass = Canvas(256, 128, (0, 0, 0, 0))


def draw_glass_block_region(key, rect, label=None, block_step=8, neon_border=MAGENTA_HI):
    x, y, w, h = rect
    REG[key] = {"rect_art": rect}
    glass_fill = (68, 134, 156, 118)
    glass_hi = (140, 218, 235, 152)
    glass_sh = (44, 82, 108, 138)
    c_glass.rect(x, y, x + w - 1, y + h - 1, fill=glass_fill, outline=INK)
    c_glass.rect(x + 1, y + 1, x + w - 2, y + h - 2, outline=neon_border)
    # Glass-block grid mortar lines + specular L-highlights inside each block
    for gx in range(x + block_step, x + w - 2, block_step):
        c_glass.line_v(gx, y + 2, y + h - 3, glass_sh)
        c_glass.line_v(gx + 1, y + 2, y + h - 3, glass_hi)
    for gy in range(y + block_step, y + h - 2, block_step):
        c_glass.line_h(x + 2, x + w - 3, gy, glass_sh)
        c_glass.line_h(x + 2, x + w - 3, gy + 1, glass_hi)
    if label:
        lw = len(label) * 6 + 4
        lx = x + max(2, (w - lw) // 2)
        ly = y + (h - 11) // 2
        c_glass.rect(lx, ly, lx + lw - 1, ly + 10, fill=CHARCOAL_SH, outline=GOLD)
        c_glass.text(lx + 3, ly + 2, label, MAGENTA_HI, shadow=INK)


draw_glass_block_region("front_left", [0, 0, 72, 32], "LIVE DJ", 8, MAGENTA_HI)
draw_glass_block_region("front_right", [74, 0, 72, 32], "COCKTAILS", 8, CYAN_HI)
draw_glass_block_region("entry", [148, 0, 32, 36], None, 16, GOLD)
draw_glass_block_region("transom", [182, 0, 32, 12], None, 8, CYAN_HI)
draw_glass_block_region("tower_col", [216, 0, 16, 64], None, 8, MAGENTA_HI)
draw_glass_block_region("side_win", [0, 36, 48, 32], "MIRAGE", 8, CYAN_HI)
draw_glass_block_region("vip_win", [50, 36, 48, 24], "VIP CLUB", 8, MAGENTA_HI)
draw_glass_block_region("dj_glass", [100, 36, 72, 16], "SOUND BOOTH", 12, GOLD)
c_glass.save_2x("glass.png")

# 2e. signs.png (256x128 art -> 512x256 texels): neon marquees, reader board, disco dance floor & laser backdrop
c_signs = Canvas(256, 128, CHARCOAL_SH)

# Title sign: CLUB MIRAGE (112x16 art)
REG["title"] = {"rect_art": [0, 0, 112, 16]}
c_signs.stepped_panel(0, 0, 112, 16, CHARCOAL_SH, MAGENTA_HI, MAGENTA_SH, INK, wear_seed=1)
c_signs.rect(1, 1, 110, 14, outline=CYAN_HI)
c_signs.rect(2, 2, 109, 13, outline=MAGENTA_HI)
c_signs.text(22, 5, "CLUB MIRAGE", GOLD, shadow=MAGENTA_SH)

# Pylon header: MIRAGE (72x16 art)
REG["pylon_top"] = {"rect_art": [114, 0, 72, 16]}
c_signs.stepped_panel(114, 0, 72, 16, MAGENTA, MAGENTA_HI, MAGENTA_SH, INK, wear_seed=2)
c_signs.rect(115, 1, 184, 14, outline=GOLD)
c_signs.line_h(116, 183, 3, CYAN_HI)
c_signs.line_h(116, 183, 12, CYAN_HI)
c_signs.text(132, 5, "MIRAGE", CREAM, shadow=INK)

# Lounge fascia sign: SYNTH LOUNGE (64x12 art)
REG["lounge_sign"] = {"rect_art": [188, 0, 64, 12]}
c_signs.stepped_panel(188, 0, 64, 12, CYAN_DK, CYAN_HI, CYAN_SH, INK, wear_seed=3)
c_signs.rect(189, 1, 250, 10, outline=MAGENTA_HI)
c_signs.text(196, 3, "DANCE CLUB", GOLD, shadow=INK)

# Reader board: LIVE DJ / SYNTH WAVE (72x24 art)
REG["reader"] = {"rect_art": [0, 18, 72, 24]}
c_signs.stepped_panel(0, 18, 72, 24, CHARCOAL, CYAN_HI, CHARCOAL_SH, INK, wear_seed=4)
c_signs.rect(1, 19, 70, 40, outline=MAGENTA_HI)
c_signs.line_h(3, 68, 29, CYAN_SH)
c_signs.text(6, 21, "LIVE DJ 86", GOLD, shadow=INK)
c_signs.text(6, 32, "SYNTH WAVE", CYAN_HI, shadow=INK)

# VIP canopy sign: VIP ENTRY (48x12 art)
REG["vip_sign"] = {"rect_art": [74, 18, 48, 12]}
c_signs.stepped_panel(74, 18, 48, 12, MAGENTA, MAGENTA_HI, MAGENTA_SH, INK, wear_seed=5)
c_signs.rect(75, 19, 120, 28, outline=GOLD)
c_signs.text(78, 21, "VIP ENTRY", CREAM, shadow=INK)

# Downward soffit & lighting-truss spotlight lamp (16x4 art)
REG["lamp"] = {"rect_art": [124, 18, 16, 4]}
c_signs.rect(124, 18, 139, 21, fill=GOLD, outline=INK)
c_signs.line_h(125, 138, 19, CREAM)

# 1980s Synthwave Sunburst Crown Emblem (24x24 art)
REG["sunburst"] = {"rect_art": [142, 18, 24, 24]}
c_signs.stepped_panel(142, 18, 24, 24, CHARCOAL_SH, CYAN_HI, MAGENTA_SH, INK, wear_seed=6)
for sy in range(21, 39):
    ry = sy - 21
    if ry % 3 != 2:
        col = SUN_TOP if ry < 6 else (SUN_MID if ry < 12 else SUN_BOT)
        half_w = int(math.sqrt(max(0, 81 - (ry - 9) ** 2))) if "math" in globals() else 7
        c_signs.line_h(154 - 7, 154 + 7, sy, col)
c_signs.rect(145, 21, 162, 38, outline=MAGENTA_HI)

# Illuminated 1980s Light-Up Disco / Synthwave Checkerboard Dance Floor (96x64 art)
REG["dance_floor"] = {"rect_art": [0, 44, 96, 64]}
c_signs.rect(0, 44, 95, 107, fill=INK, outline=GOLD)
tile_palette = [MAGENTA_HI, CYAN_HI, GOLD, VIOLET_HI, MAGENTA, CYAN]
for ty in range(0, 8):
    for tx in range(0, 12):
        px0 = tx * 8
        py0 = 44 + ty * 8
        tcol = tile_palette[(tx * 3 + ty * 5) % len(tile_palette)]
        c_signs.rect(px0 + 1, py0 + 1, px0 + 6, py0 + 6, fill=tcol, outline=INK)
        c_signs.rect(px0 + 2, py0 + 2, px0 + 5, py0 + 5, outline=CREAM)

# DJ Stage Synthwave Sunset & Laser Grid Backdrop (128x40 art)
REG["stage_backdrop"] = {"rect_art": [98, 44, 128, 40]}
c_signs.rect(98, 44, 225, 83, fill=CHARCOAL_SH, outline=CYAN_HI)
# Horizon line at y = 68, perspective cyan laser grid below (y in 68..82)
c_signs.line_h(99, 224, 68, MAGENTA_HI)
for hy in (71, 74, 78, 81):
    c_signs.line_h(99, 224, hy, CYAN)
for vx in range(106, 222, 12):
    c_signs.line_v(vx, 68, 82, CYAN_HI)
# Segmented synthwave sunset disc above horizon (center x=162, y=54..67)
for sy in range(48, 68):
    ry = sy - 48
    if ry % 3 != 2:
        col = SUN_TOP if ry < 7 else (SUN_MID if ry < 14 else SUN_BOT)
        span = max(4, 22 - abs(ry - 12))
        c_signs.line_h(162 - span, 162 + span, sy, col)
c_signs.rect(118, 52, 205, 65, fill=CHARCOAL_SH, outline=GOLD)
c_signs.text(129, 55, "CLUB MIRAGE", MAGENTA_HI, shadow=INK)

# Illuminated Cocktail Back-Bar Bottle & Neon Mirror Display (96x24 art)
REG["back_bar"] = {"rect_art": [98, 86, 96, 24]}
c_signs.stepped_panel(98, 86, 96, 24, CHARCOAL, CYAN_HI, MAGENTA_SH, INK, wear_seed=12)
for shelf_y in (94, 102):
    c_signs.line_h(100, 191, shelf_y, GOLD)
    for bx in range(103, 189, 5):
        bcol = [MAGENTA_HI, CYAN_HI, GOLD, CREAM][(bx // 5) % 4]
        c_signs.rect(bx, shelf_y - 5, bx + 2, shelf_y - 1, fill=bcol)

c_signs.save_2x("signs.png")

(R / "art-regions.json").write_text(json.dumps(REG, indent=2) + "\n")
print("Generated 12 Club Mirage textures and art-regions.json successfully.")
