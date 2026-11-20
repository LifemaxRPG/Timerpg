#!/usr/bin/env python3
"""
Texture and pixel-art generator for THE GREAT MERIDIAN MONUMENT (4 Historical States + Far LOD).
Strict contract compliance (docs/CHATGPT_MODEL_GUIDE.md & docs/GEMINI_MERIDIAN_REVISION.md):
- Every texture is painted in whole art pixels and exported at exact 2x2 texels
  (0.0388 m/texel -> 0.0776 m/art pixel).
- LimeZu-harmonized architectural palette with 1-art-px dark outlines, top-edge highlights,
  2-4 step flat shading + subtle dither, and state-specific weathering:
    * stone_limestone_clean.png (1926 dedication & 2000+ restored)
    * stone_limestone_mature.png (1941-1975 darkened patina)
    * stone_limestone_worn.png (1976-1999 cornice water-stain streaks)
    * copper_fresh.png (1926 fresh warm copper spire)
    * copper_verdigris.png (1941+ fully green patinated copper spire)
- guardians_atlas.png: Four 7 m stylized Art Deco stone guardian figures
  (DEFENSE/sword, LEARNING/book, INDUSTRY/gear wheel, HARVEST/wheat sheaf) + dark slate trim.
- inscriptions.png: Flush carved friezes, West Memorial Hall dedication wall, made-up blue-and-gold
  compass-star civic flag, 1926 bunting swags, 4 m open bronze door reliefs, 1941-45 war plaque,
  4 brass sky-deck direction plates, zodiac ecliptic engraving, Earth globe map & visitor centre sign.
- glow_monument_mask.png: Pure #000000 black everywhere outside strictly budgeted emissive points (< 20 m2).
"""
import json
import math
import struct
import zlib
from pathlib import Path

from pixel_font import FONT_5X7, FONT_3X5, measure_text

R = Path(__file__).resolve().parent
TEX_DIR = R / "textures"
TEX_DIR.mkdir(parents=True, exist_ok=True)


def write_png_rgba(path, w, h, pixels_2d):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        row = pixels_2d[y]
        for x in range(w):
            r, g, b, a = row[x]
            raw.extend((r & 255, g & 255, b & 255, a & 255))

    def chunk(ctype, payload):
        crc = zlib.crc32(ctype + payload) & 0xFFFFFFFF
        return struct.pack(">I", len(payload)) + ctype + payload + struct.pack(">I", crc)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    idat = zlib.compress(bytes(raw), 9)
    out = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")
    Path(path).write_bytes(out)


def h2(x, y, seed=0):
    v = (x * 374761393 + y * 668265263 + seed * 1442695041) & 0xFFFFFFFF
    v = ((v ^ (v >> 13)) * 1274126177) & 0xFFFFFFFF
    return (v ^ (v >> 16)) & 0xFF


class ArtCanvas:
    """1x art-pixel canvas exporting at exact 2x2 texels per art pixel."""

    def __init__(self, w_art, h_art, bg=(0, 0, 0, 255)):
        self.w = w_art
        self.h = h_art
        self.px = [[bg for _ in range(w_art)] for _ in range(h_art)]

    def put(self, x, y, col):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = col

    def rect(self, x0, y0, w, h, col):
        for y in range(y0, y0 + h):
            for x in range(x0, x0 + w):
                self.put(x, y, col)

    def hline(self, x0, x1, y, col):
        for x in range(x0, x1 + 1):
            self.put(x, y, col)

    def vline(self, x, y0, y1, col):
        for y in range(y0, y1 + 1):
            self.put(x, y, col)

    def box_outline(self, x0, y0, w, h, col):
        self.hline(x0, x0 + w - 1, y0, col)
        self.hline(x0, x0 + w - 1, y0 + h - 1, col)
        self.vline(x0, y0, y0 + h - 1, col)
        self.vline(x0 + w - 1, y0, y0 + h - 1, col)

    def draw_text(self, x0, y0, text, col, font="5x7", scale=1, spacing=1, shadow=None):
        glyphs = FONT_5X7 if font == "5x7" else FONT_3X5
        gw = 5 if font == "5x7" else 3
        cx = x0
        for ch in text.upper():
            g = glyphs.get(ch, glyphs[" "])
            for ry, row in enumerate(g):
                for rx, bit in enumerate(row):
                    if bit == "1":
                        for sy in range(scale):
                            for sx in range(scale):
                                px = cx + rx * scale + sx
                                py = y0 + ry * scale + sy
                                if shadow is not None:
                                    self.put(px, py + 1, shadow)
                                self.put(px, py, col)
            cx += (gw + spacing) * scale

    def save_2x(self, path):
        w2, h2_dim = self.w * 2, self.h * 2
        out = [[None] * w2 for _ in range(h2_dim)]
        for y in range(self.h):
            r0, r1 = out[y * 2], out[y * 2 + 1]
            src = self.px[y]
            for x in range(self.w):
                c = src[x]
                r0[x * 2] = c
                r0[x * 2 + 1] = c
                r1[x * 2] = c
                r1[x * 2 + 1] = c
        write_png_rgba(path, w2, h2_dim, out)


# ==============================================================================
# PALETTE (warm Beaux-Arts limestone, granite, steel, fresh/verdigris copper, bronze)
# ==============================================================================
OUTLINE = (50, 50, 68, 255)
DEEP_INK = (32, 34, 48, 255)

# Clean pale limestone (1926 dedication & 2000+ restored)
LIME_HI = (242, 234, 214, 255)
LIME_MID = (224, 214, 192, 255)
LIME_LOW = (198, 186, 164, 255)
LIME_SHD = (162, 148, 128, 255)
LIME_MORT = (122, 112, 98, 255)

# Mature darkened limestone (1941-1975)
MAT_HI = (218, 208, 188, 255)
MAT_MID = (196, 184, 164, 255)
MAT_LOW = (170, 158, 140, 255)
MAT_SHD = (138, 128, 112, 255)
MAT_MORT = (102, 94, 84, 255)

# Worn water-streaked limestone (1976-1999)
WRN_HI = (204, 194, 174, 255)
WRN_MID = (182, 172, 154, 255)
WRN_LOW = (154, 144, 128, 255)
WRN_SHD = (122, 114, 102, 255)
WRN_STAIN = (96, 92, 86, 255)
WRN_MORT = (86, 82, 78, 255)

# Monumental Dressed Granite
GRAN_HI = (192, 188, 182, 255)
GRAN_MID = (164, 160, 156, 255)
GRAN_LOW = (136, 134, 132, 255)
GRAN_SHD = (102, 100, 104, 255)
GRAN_MORT = (76, 76, 86, 255)

# Riveted Bridge Steel
STEEL_HI = (108, 120, 136, 255)
STEEL_MID = (76, 86, 102, 255)
STEEL_LOW = (54, 62, 76, 255)
STEEL_RIV = (146, 158, 174, 255)

# Fresh Copper (1926 dedication)
FCOP_HI = (226, 152, 106, 255)
FCOP_MID = (192, 114, 74, 255)
FCOP_LOW = (148, 82, 52, 255)
FCOP_SHD = (106, 56, 36, 255)

# Patinated Verdigris Copper (1941+)
COP_HI = (132, 208, 184, 255)
COP_MID = (92, 172, 152, 255)
COP_LOW = (62, 134, 120, 255)
COP_SHD = (42, 96, 90, 255)

# Burnished Art Deco Gilded Bronze
BRZ_SPEC = (248, 224, 144, 255)
BRZ_HI = (224, 184, 92, 255)
BRZ_MID = (182, 140, 62, 255)
BRZ_LOW = (132, 96, 42, 255)
BRZ_SHD = (86, 62, 32, 255)

# Reflecting Pool Water
WAT_HI = (142, 204, 232, 255)
WAT_MID = (76, 144, 192, 255)
WAT_DEEP = (46, 104, 156, 255)
WAT_SHD = (32, 74, 120, 255)

# Dark Architectural Slate
SLATE_HI = (94, 98, 114, 255)
SLATE_MID = (68, 72, 86, 255)
SLATE_LOW = (48, 52, 66, 255)

# Ceremonial Lawn Turf / Tree Foliage
LAWN_HI = (114, 168, 86, 255)
LAWN_MID = (88, 144, 68, 255)
LAWN_LOW = (68, 118, 54, 255)

# Timber (benches, scaffolding, trunks)
WOOD_HI = (176, 122, 78, 255)
WOOD_MID = (144, 94, 56, 255)
WOOD_LOW = (108, 68, 40, 255)


def make_limestone_variant(filename, hi, mid, low, shd, mort, water_streaks=False, seed=11):
    """64x64 art px dressed monumental ashlar limestone in clean / mature / worn variants."""
    c = ArtCanvas(64, 64, mid)
    course_h = 8
    for row_idx in range(8):
        y0 = row_idx * course_h
        offset = 0 if row_idx % 2 == 0 else 16
        for blk in range(-1, 3):
            x0 = blk * 32 + offset
            tone_v = h2(blk + 10, row_idx, seed) % 3
            base = (mid, hi, low)[tone_v]
            for dy in range(course_h):
                for dx in range(32):
                    px = (x0 + dx) % 64
                    py = y0 + dy
                    if dy == 0:
                        col = mort
                    elif dy == 1:
                        col = hi
                    elif dy == course_h - 1:
                        col = shd
                    elif dx == 0:
                        col = mort
                    elif dx == 1:
                        col = hi if base != hi else mid
                    elif dx == 31:
                        col = low
                    else:
                        n = h2(px, py, seed + 12)
                        if n < 24:
                            col = low
                        elif n > 234:
                            col = hi
                        else:
                            col = base
                    c.put(px, py, col)

    if water_streaks:
        # Prominent dark rainwater runoff streaks down ashlar courses for 1976-1999 worn state!
        for sx, width, length in ((6, 4, 54), (19, 3, 44), (33, 5, 58), (48, 4, 48), (58, 3, 38)):
            for py in range(length):
                fade = py / max(1, length)
                for dx in range(width):
                    if h2(sx + dx, py, 91) > int(fade * 195):
                        edge = (dx == 0 or dx == width - 1)
                        c.put((sx + dx) % 64, py, shd if edge else WRN_STAIN)
    c.save_2x(TEX_DIR / filename)


def make_stone_granite():
    c = ArtCanvas(64, 64, GRAN_MID)
    course_h = 8
    for row_idx in range(8):
        y0 = row_idx * course_h
        offset = 0 if row_idx % 2 == 0 else 16
        for blk in range(-1, 3):
            x0 = blk * 32 + offset
            tv = h2(blk + 5, row_idx, 41) % 3
            base = (GRAN_MID, GRAN_LOW, GRAN_HI)[tv]
            for dy in range(course_h):
                for dx in range(32):
                    px = (x0 + dx) % 64
                    py = y0 + dy
                    if dy == 0 or dx == 0:
                        col = GRAN_MORT
                    elif dy == 1:
                        col = GRAN_HI
                    elif dy == course_h - 1:
                        col = GRAN_SHD
                    else:
                        n = h2(px, py, 47)
                        col = GRAN_SHD if n < 34 else (GRAN_HI if n > 224 else base)
                    c.put(px, py, col)
    c.save_2x(TEX_DIR / "stone_granite.png")


def make_steel_truss():
    c = ArtCanvas(64, 64, STEEL_MID)
    for y in range(64):
        for x in range(64):
            bx, by = x % 16, y % 16
            if bx == 0 or by == 0:
                col = DEEP_INK
            elif by == 1 or bx == 1:
                col = STEEL_HI
            elif by == 15 or bx == 15:
                col = STEEL_LOW
            elif (bx in (3, 12) and by in (3, 12)) or (bx in (3, 12) and by == 8) or (by in (3, 12) and bx == 8):
                col = STEEL_RIV
            elif bx in (3, 12) and by in (4, 13):
                col = DEEP_INK
            elif (x + y) % 9 == 0:
                col = STEEL_LOW
            elif h2(x, y, 71) > 230:
                col = STEEL_HI
            else:
                col = STEEL_MID
            c.put(x, y, col)
    c.save_2x(TEX_DIR / "steel_truss.png")


def make_copper_variant(filename, hi, mid, low, shd, seed=97):
    c = ArtCanvas(64, 64, mid)
    for y in range(64):
        for x in range(64):
            sx, sy = x % 8, y % 16
            if sx == 0 or sy == 0:
                col = shd
            elif sx == 1 or sy == 1:
                col = hi
            elif sx == 7:
                col = low
            else:
                n = h2(x, y, seed)
                if n < 42:
                    col = low
                elif n > 218:
                    col = hi
                elif (x + y * 2) % 13 == 0:
                    col = low
                else:
                    col = mid
            c.put(x, y, col)
    c.save_2x(TEX_DIR / filename)


def make_bronze_gilded():
    c = ArtCanvas(64, 64, BRZ_MID)
    for y in range(64):
        for x in range(64):
            bx, by = x % 16, y % 16
            if by == 0 or bx == 0:
                col = BRZ_SHD
            elif by == 1 or bx == 1:
                col = BRZ_SPEC
            elif by == 15 or bx == 15:
                col = BRZ_LOW
            elif by in (3, 12) and (bx % 4 == 2):
                col = BRZ_SPEC
            elif abs(bx - 8) + abs(by - 8) == 4:
                col = BRZ_HI
            elif abs(bx - 8) + abs(by - 8) == 5:
                col = BRZ_LOW
            else:
                n = h2(x, y, 131)
                col = BRZ_LOW if n < 36 else (BRZ_HI if n > 224 else BRZ_MID)
            c.put(x, y, col)
    c.save_2x(TEX_DIR / "bronze_gilded.png")


def make_water_pool():
    c = ArtCanvas(64, 64, WAT_DEEP)
    for y in range(64):
        for x in range(64):
            band = (y // 4) % 4
            wave = int(2.2 * math.sin(x * 0.25 + y * 0.18))
            v = (y + wave) % 8
            if v == 0 and (x % 7 in (1, 2, 3)):
                col = WAT_HI
            elif v in (1, 2) or band == 1:
                col = WAT_MID
            elif v == 6 and h2(x, y, 151) < 90:
                col = WAT_SHD
            else:
                col = WAT_DEEP
            c.put(x, y, col)
    c.save_2x(TEX_DIR / "water_pool.png")


def make_marble_plaza():
    c = ArtCanvas(64, 64, LIME_MID)
    for y in range(64):
        for x in range(64):
            tx, ty = x % 16, y % 16
            checker = ((x // 16) + (y // 16)) % 2
            if tx == 0 or ty == 0:
                col = GRAN_MORT
            elif tx == 1 or ty == 1:
                col = LIME_HI
            elif tx == 15 or ty == 15:
                col = LIME_SHD
            elif tx in (2, 14) and ty in (2, 14):
                col = SLATE_MID
            else:
                base = LIME_HI if checker == 0 else LIME_MID
                n = h2(x, y, 173)
                col = LIME_LOW if n < 22 else (LIME_HI if n > 238 else base)
            c.put(x, y, col)
    c.save_2x(TEX_DIR / "marble_plaza.png")


def make_glass_cathedral():
    """128x128 art px BLEND glass: amber-and-slate pier slot glass + semi-clear lantern glass."""
    c = ArtCanvas(128, 128, (36, 52, 74, 188))
    G_DARK = (44, 58, 82, 210)
    G_MID = (78, 108, 142, 188)
    G_AMBER = (224, 174, 88, 196)
    G_AMBER_HI = (248, 214, 134, 208)
    MULL = (42, 44, 58, 255)

    # Region 1: win_cathedral (0, 0, 64, 128) — tall narrow Art Deco amber-and-slate glass
    for y in range(128):
        for x in range(64):
            if x in (0, 1, 62, 63) or y in (0, 1, 126, 127):
                col = MULL
            elif x in (21, 42) or y % 20 == 0:
                col = MULL
            elif (x + y) % 14 < 2:
                col = G_AMBER
            elif (x + y) % 16 == 6:
                col = G_MID
            else:
                col = G_DARK
            c.put(x, y, col)

    # Region 2: win_lantern (64, 0, 64, 128) — translucent amber lantern glass so gears & lens read inside!
    for y in range(128):
        for x in range(64):
            px = 64 + x
            if x in (0, 1, 62, 63) or y in (0, 1, 126, 127):
                col = MULL
            elif x in (16, 32, 48) or y % 18 == 0:
                col = MULL
            elif abs(x - 32) < 6 and y % 9 < 3:
                col = (248, 214, 134, 168)
            elif (x + y) % 12 < 3:
                col = (224, 174, 88, 148)
            else:
                col = (188, 142, 72, 132)
            c.put(px, y, col)

    c.save_2x(TEX_DIR / "glass_cathedral.png")
    return {
        "win_cathedral": [0, 0, 64, 128],
        "win_lantern": [64, 0, 64, 128],
    }


def make_detail_atlas():
    """128x128 art px 4 strict 64x64 quadrants: lawn_turf, dark_iron, compass_mosaic, timber_bench."""
    c = ArtCanvas(128, 128, OUTLINE)

    # Q0 (0, 0, 64, 64): lawn_turf (also used for stylized pixel-art tree canopies)
    for y in range(64):
        for x in range(64):
            n = h2(x, y, 211)
            stripe = (x // 8) % 2
            if n < 32:
                col = LAWN_LOW
            elif n > 220:
                col = LAWN_HI
            else:
                col = LAWN_MID if stripe == 0 else (98, 154, 76, 255)
            c.put(x, y, col)

    # Q1 (64, 0, 64, 64): dark_iron (lamp posts, elevator cage, broken lamp heads, flood fixtures)
    for y in range(64):
        for x in range(64):
            px = 64 + x
            if x % 16 == 0 or y % 16 == 0:
                col = (34, 38, 48, 255)
            elif x % 16 == 1 or y % 16 == 1:
                col = (84, 92, 108, 255)
            else:
                n = h2(x, y, 223)
                col = (48, 54, 66, 255) if n < 90 else (62, 68, 82, 255)
            c.put(px, y, col)

    # Q2 (0, 64, 64, 64): compass_mosaic — rich 8-point inlaid gilded-bronze & navy-slate Compass Rose!
    cx, cy = 31.5, 31.5
    for y in range(64):
        for x in range(64):
            dx, dy = x - cx, y - cy
            r = math.hypot(dx, dy)
            ang = math.atan2(dy, dx)
            star_k = abs(math.cos(4.0 * ang))
            star_r = 10.0 + 18.0 * (star_k ** 2.2)
            if x in (0, 63) or y in (0, 63):
                col = DEEP_INK
            elif x in (1, 2, 61, 62) or y in (1, 2, 61, 62):
                col = BRZ_HI
            elif abs(r - 28.0) < 1.6:
                col = BRZ_MID
            elif r < 5.0:
                col = BRZ_SPEC
            elif r < star_r:
                col = BRZ_HI if math.sin(4.0 * ang) >= 0 else BRZ_LOW
            elif r < 27.0:
                col = SLATE_LOW if (x + y) % 2 == 0 else SLATE_MID
            else:
                col = LIME_HI if ((x // 4 + y // 4) % 2 == 0) else LIME_MID
            c.put(x, 64 + y, col)

    # Q3 (64, 64, 64, 64): timber_bench (plaza benches, 1976 timber scaffolding planks, tree trunks)
    for y in range(64):
        for x in range(64):
            py_l = y % 8
            if py_l == 0:
                col = (74, 46, 28, 255)
            elif py_l == 1:
                col = WOOD_HI
            elif py_l == 7:
                col = WOOD_LOW
            else:
                n = h2(x, y, 241)
                col = WOOD_LOW if n < 40 else (WOOD_HI if n > 225 else WOOD_MID)
            c.put(64 + x, 64 + y, col)

    c.save_2x(TEX_DIR / "detail_atlas.png")
    return {
        "lawn_turf": [0, 0, 64, 64],
        "dark_iron": [64, 0, 64, 64],
        "compass_mosaic": [0, 64, 64, 64],
        "timber_bench": [64, 64, 64, 64],
    }


def make_guardians_atlas():
    """
    256x128 art px sculpted Art Deco stone atlas for the FOUR 7 m COLOSSAL GUARDIANS
    (DEFENSE/sword, LEARNING/book, INDUSTRY/gear wheel, HARVEST/wheat sheaf) + dark slate trim!
    Each guardian sits in a crisp 56x96 art px relief panel painted in bold stepped limestone +
    burnished gilded-bronze attribute highlights so they read unmistakably close up and afar!
    """
    c = ArtCanvas(256, 128, LIME_MID)

    def paint_guardian_base(x0, y0, kind):
        w, h = 56, 96
        # Recessed limestone niche backdrop
        for dy in range(h):
            for dx in range(w):
                px, py = x0 + dx, y0 + dy
                if dx in (0, 1, w - 2, w - 1) or dy in (0, 1, h - 2, h - 1):
                    col = LIME_MORT
                elif dx in (2, 3) or dy in (2, 3):
                    col = LIME_SHD
                elif dx in (w - 4, w - 3):
                    col = LIME_HI
                else:
                    n = h2(px, py, 401)
                    col = LIME_SHD if n < 50 else LIME_LOW
                c.put(px, py, col)

        # Art Deco stepped pedestal at bottom (dy = 82..93)
        c.rect(x0 + 8, y0 + 82, 40, 11, LIME_MID)
        c.hline(x0 + 8, x0 + 47, y0 + 82, LIME_HI)
        c.box_outline(x0 + 8, y0 + 82, 40, 11, OUTLINE)

        # Vertical fluted Art Deco draped robe / legs (dy = 46..82)
        for dy in range(46, 82):
            half_w = 13 if dy > 60 else 15
            for dx in range(28 - half_w, 28 + half_w):
                flute = (dx - 28) % 4
                if dx == 28 - half_w or dx == 28 + half_w - 1:
                    col = OUTLINE
                elif flute == 0:
                    col = LIME_SHD
                elif flute == 1:
                    col = LIME_HI
                else:
                    col = LIME_MID
                c.put(x0 + dx, y0 + dy, col)

        # Broad sculpted Art Deco shoulders, chest & folded stylized wings/collar (dy = 24..46)
        for dy in range(24, 46):
            hw = 19 - int(max(0, (dy - 30) * 0.25))
            for dx in range(28 - hw, 28 + hw):
                if dx == 28 - hw or dx == 28 + hw - 1 or dy == 24:
                    col = OUTLINE
                elif dy in (25, 26):
                    col = LIME_HI
                elif abs(dx - 28) > hw - 5:
                    col = LIME_LOW
                else:
                    col = LIME_HI if (dx < 26) else LIME_MID
                c.put(x0 + dx, y0 + dy, col)

        # Stylized Art Deco head, brow & sunburst crown (dy = 7..24)
        c.rect(x0 + 21, y0 + 10, 14, 14, LIME_HI)
        c.box_outline(x0 + 21, y0 + 10, 14, 14, OUTLINE)
        # Crown rays above head
        for rx in (22, 25, 28, 31, 34):
            c.vline(x0 + rx, y0 + 5, y0 + 9, BRZ_HI if rx == 28 else LIME_HI)
        # Stylized stern Art Deco brow, nose ridge & jawline
        c.hline(x0 + 23, x0 + 32, y0 + 14, OUTLINE)
        c.vline(x0 + 28, y0 + 14, y0 + 19, LIME_SHD)
        c.hline(x0 + 25, x0 + 30, y0 + 21, LIME_SHD)

        # Forearms & clasped hands resting centrally at dy = 38..46
        c.rect(x0 + 17, y0 + 38, 22, 7, LIME_HI)
        c.box_outline(x0 + 17, y0 + 38, 22, 7, OUTLINE)

        # ICONIC ATTRIBUTE HELD IN FRONT (gilded-bronze & crisp dark ink outline so unmistakable!)
        if kind == "defense":
            # Colossal vertical Art Deco Broadsword! (pommel at dy=34, crossguard at dy=41..44, long blade dy=44..81)
            c.rect(x0 + 26, y0 + 33, 4, 6, BRZ_SPEC)
            c.box_outline(x0 + 26, y0 + 33, 4, 6, DEEP_INK)
            c.rect(x0 + 15, y0 + 40, 26, 5, BRZ_HI)
            c.box_outline(x0 + 15, y0 + 40, 26, 5, DEEP_INK)
            for dy in range(45, 81):
                bw = 4 if dy < 74 else max(1, (81 - dy) // 2)
                c.hline(x0 + 28 - bw, x0 + 28 + bw - 1, y0 + dy, BRZ_HI)
                c.put(x0 + 28 - bw, y0 + dy, DEEP_INK)
                c.put(x0 + 28 + bw - 1, y0 + dy, DEEP_INK)
                c.put(x0 + 28, y0 + dy, BRZ_SPEC)
        elif kind == "learning":
            # Large Open Stone & Gilded Tome held at chest + laurel columns! (dy = 37..60)
            c.rect(x0 + 13, y0 + 38, 30, 22, BRZ_MID)
            c.box_outline(x0 + 13, y0 + 38, 30, 22, DEEP_INK)
            c.rect(x0 + 15, y0 + 40, 12, 18, LIME_HI)
            c.rect(x0 + 29, y0 + 40, 12, 18, LIME_HI)
            c.vline(x0 + 28, y0 + 39, y0 + 58, DEEP_INK)
            for ry in (43, 46, 49, 52, 55):
                c.hline(x0 + 17, x0 + 25, y0 + ry, LIME_MORT)
                c.hline(x0 + 31, x0 + 39, y0 + ry, LIME_MORT)
        elif kind == "industry":
            # Giant Industrial Cog Gear Wheel resting in front of legs (center 28, 60, r=14)!
            gcx, gcy = 28.0, 60.0
            for dy in range(42, 78):
                for dx in range(10, 46):
                    r = math.hypot(dx - gcx, dy - gcy)
                    ang = math.atan2(dy - gcy, dx - gcx)
                    tooth = 2.4 if math.cos(8.0 * ang) > 0.25 else 0.0
                    if r < 4.5:
                        c.put(x0 + dx, y0 + dy, DEEP_INK if r > 3.2 else LIME_SHD)
                    elif r < 11.5 + tooth:
                        if r > 10.2 + tooth:
                            c.put(x0 + dx, y0 + dy, DEEP_INK)
                        elif abs(r - 7.5) < 1.5 and abs(math.cos(4.0 * ang)) < 0.45:
                            c.put(x0 + dx, y0 + dy, LIME_SHD)
                        else:
                            c.put(x0 + dx, y0 + dy, BRZ_SPEC if dx < 27 else BRZ_HI)
        elif kind == "harvest":
            # Tied Golden Sheaf of Wheat flaring generously at top & bottom (dy = 34..78)!
            for dy in range(34, 78):
                # Hourglass tied waist at dy=56
                flare = abs(dy - 56) * 0.38
                hw = int(5 + flare)
                for dx in range(28 - hw, 28 + hw):
                    if dx == 28 - hw or dx == 28 + hw - 1:
                        col = DEEP_INK
                    elif 53 <= dy <= 59:
                        col = BRZ_LOW if dy % 2 == 0 else BRZ_SHD
                    elif dy < 44:
                        # Individual grain heads at top of sheaf
                        col = BRZ_SPEC if (dx + dy) % 3 == 0 else BRZ_HI
                    else:
                        col = BRZ_HI if (dx % 3 != 0) else BRZ_MID
                    c.put(x0 + dx, y0 + dy, col)

    paint_guardian_base(4, 4, "defense")
    paint_guardian_base(68, 4, "learning")
    paint_guardian_base(132, 4, "industry")
    paint_guardian_base(196, 4, "harvest")

    # Dark architectural slate strip (4, 104, 64, 20) for rotunda inlay & roof trim
    for dy in range(20):
        for dx in range(64):
            ox = (dx + (8 if (dy // 5) % 2 else 0)) % 16
            oy = dy % 5
            if oy == 0 or ox == 0:
                col = DEEP_INK
            elif oy == 1:
                col = SLATE_HI
            else:
                col = SLATE_LOW if h2(dx, dy, 509) < 70 else SLATE_MID
            c.put(4 + dx, 104 + dy, col)

    c.save_2x(TEX_DIR / "guardians_atlas.png")
    return {
        "guardian_defense": [4, 4, 56, 96],
        "guardian_learning": [68, 4, 56, 96],
        "guardian_industry": [132, 4, 56, 96],
        "guardian_harvest": [196, 4, 56, 96],
        "slate_trim": [4, 104, 64, 20],
    }


def make_inscriptions():
    """256x256 art px inscriptions, carved friezes, dedication wall, flag, bunting, doors, zodiac & direction plates."""
    c = ArtCanvas(256, 256, LIME_MID)

    def paint_stone_bg(x0, y0, w, h):
        for dy in range(h):
            for dx in range(w):
                px, py = x0 + dx, y0 + dy
                if dy == 0:
                    col = LIME_HI
                elif dy == h - 1:
                    col = LIME_SHD
                elif dy == 1 or dy == h - 2:
                    col = LIME_LOW
                else:
                    n = h2(px, py, 307)
                    col = LIME_HI if n > 228 else (LIME_LOW if n < 26 else LIME_MID)
                c.put(px, py, col)

    # 1. frieze_main (4, 4, 236, 32): Flush carved directly in limestone
    paint_stone_bg(4, 4, 236, 32)
    t1 = "THE GREAT MERIDIAN"
    w1, _ = measure_text(t1, "5x7", scale=2, spacing=1)
    assert w1 <= 226
    c.draw_text(4 + (236 - w1) // 2, 8, t1, (70, 62, 54, 255), "5x7", scale=2, spacing=1, shadow=LIME_HI)
    t2 = "DEDICATED MCMXXVI"
    w2, _ = measure_text(t2, "5x7", scale=1, spacing=1)
    assert w2 <= 216
    c.draw_text(4 + (236 - w2) // 2, 25, t2, (88, 78, 68, 255), "5x7", scale=1, spacing=1, shadow=LIME_HI)

    # 2. frieze_east (4, 40, 180, 24) & frieze_west (4, 68, 180, 24)
    for reg_y, txt in ((40, "MERIDIAN EAST"), (68, "MERIDIAN WEST")):
        paint_stone_bg(4, reg_y, 180, 24)
        tw, th = measure_text(txt, "5x7", scale=2, spacing=1)
        assert tw <= 168
        c.draw_text(4 + (180 - tw) // 2, reg_y + (24 - th) // 2, txt, (72, 64, 56, 255), "5x7", scale=2, spacing=1, shadow=LIME_HI)

    # 3. civic_flag (188, 40, 44, 26): MADE-UP CIVIC FLAG — Deep Royal Blue field with crisp Golden 8-point Compass Star!
    bx, by, bw, bh = 188, 40, 44, 26
    NAVY_HI = (46, 74, 134, 255)
    NAVY_MID = (30, 52, 102, 255)
    NAVY_LOW = (22, 38, 78, 255)
    for dy in range(bh):
        for dx in range(bw):
            wave = int(1.0 * math.sin(dx * 0.28))
            rx, ry = dx - 21.5, dy - 12.5
            r = math.hypot(rx, ry)
            ang = math.atan2(ry, rx)
            star_k = abs(math.cos(4.0 * ang))
            star_r = 3.2 + 6.4 * (star_k ** 2.2)
            if dx == 0 or dx == bw - 1 or dy == 0 or dy == bh - 1:
                col = DEEP_INK
            elif dx in (2, bw - 3) or dy in (2, bh - 3):
                col = BRZ_HI
            elif r < 2.2:
                col = BRZ_SPEC
            elif r < star_r:
                col = BRZ_SPEC if math.sin(4.0 * ang) >= 0 else BRZ_HI
            elif abs(r - 9.8) < 0.85:
                col = BRZ_MID
            else:
                col = NAVY_HI if ((dx + wave) % 10 < 3) else (NAVY_LOW if (dx + wave) % 10 == 8 else NAVY_MID)
            c.put(bx + dx, by + dy, col)

    # 4. bunting_swag (188, 72, 56, 22): 1926 Dedication festive pleated red, white & blue fabric bunting!
    for dy in range(22):
        for dx in range(56):
            # Scalloped bottom swag profile (two arches)
            u_arc = ((dx % 28) - 13.5) / 13.5
            max_y = int(12 + 8.5 * math.sqrt(max(0.0, 1.0 - u_arc * u_arc)))
            if dy > max_y:
                col = LIME_MID
            elif dy == max_y or dy == 0:
                col = DEEP_INK
            else:
                band = int((dy / max(1.0, max_y)) * 3.0)
                pleat = (dx % 4 == 0)
                if band == 0:
                    col = (34, 58, 118, 255) if not pleat else (24, 42, 86, 255)
                elif band == 1:
                    col = (242, 236, 222, 255) if not pleat else (206, 198, 184, 255)
                else:
                    col = (182, 46, 52, 255) if not pleat else (138, 34, 40, 255)
            c.put(188 + dx, 72 + dy, col)

    # 5. dedication_wall (4, 98, 72, 52): West Memorial Hall carved wall + anonymous carved name blocks!
    # Sized 72x52 art px so 100% of every word fits on the 73x52 art px interior wall with ZERO clipping!
    paint_stone_bg(4, 98, 72, 52)
    c.box_outline(4, 98, 72, 52, LIME_MORT)
    c.box_outline(6, 100, 68, 48, LIME_HI)
    for li, dline in enumerate(("TO THOSE WHO", "BUILT FARMED", "TAUGHT AND", "DEFENDED")):
        dlw, _ = measure_text(dline, "3x5", scale=1, spacing=1)
        assert dlw <= 60, f"Dedication line '{dline}' overflow: {dlw} > 60"
        c.draw_text(4 + (72 - dlw) // 2, 102 + li * 6, dline, (64, 56, 48, 255), "3x5", scale=1, spacing=1, shadow=LIME_HI)
    c.hline(10, 69, 127, BRZ_MID)
    # 4 neat carved rows of anonymous recessed stone name blocks (no real names!)
    for row_i in range(4):
        ry = 130 + row_i * 4
        for col_i in range(4):
            rx = 10 + col_i * 14
            c.rect(rx, ry, 11, 2, LIME_SHD)
            c.hline(rx, rx + 10, ry + 2, LIME_HI)

    # 6. bronze_door_panel (188, 98, 32, 52): 4 m tall embossed Art Deco open bronze door leaf!
    dx0, dy0, dw, dh = 188, 98, 32, 52
    c.rect(dx0, dy0, dw, dh, BRZ_LOW)
    c.box_outline(dx0, dy0, dw, dh, DEEP_INK)
    c.box_outline(dx0 + 2, dy0 + 2, dw - 4, dh - 4, BRZ_HI)
    for py_off in (5, 19, 33):
        c.rect(dx0 + 5, dy0 + py_off, 22, 12, BRZ_MID)
        c.box_outline(dx0 + 5, dy0 + py_off, 22, 12, BRZ_SHD)
        c.box_outline(dx0 + 7, dy0 + py_off + 2, 18, 8, BRZ_SPEC)
        c.hline(dx0 + 10, dx0 + 21, dy0 + py_off + 6, BRZ_HI)

    # 7. plaque_war (224, 98, 24, 18): 1941-1945 cast-bronze War Years plaque!
    c.rect(224, 98, 24, 18, BRZ_SHD)
    c.box_outline(224, 98, 24, 18, DEEP_INK)
    c.box_outline(225, 99, 22, 16, BRZ_HI)
    tw_w, _ = measure_text("41-45", "3x5", scale=1, spacing=1)
    c.draw_text(224 + (24 - tw_w) // 2, 101, "41-45", BRZ_SPEC, "3x5", scale=1, spacing=1)
    for ry in (108, 111, 113):
        c.hline(228, 243, ry, BRZ_HI)

    # 8. plaque_dedication (224, 120, 24, 18): Original 1926 dedication bronze plaque!
    c.rect(224, 120, 24, 18, BRZ_SHD)
    c.box_outline(224, 120, 24, 18, DEEP_INK)
    c.box_outline(225, 121, 22, 16, BRZ_HI)
    tw_d, _ = measure_text("1926", "3x5", scale=1, spacing=1)
    c.draw_text(224 + (24 - tw_d) // 2, 123, "1926", BRZ_SPEC, "3x5", scale=1, spacing=1)
    for ry in (130, 133, 135):
        c.hline(228, 243, ry, BRZ_HI)

    # 9. Four Engraved Brass Corner Direction Plates for the 58 m Observation Deck! (All 56x14 art px!)
    dir_specs = [
        ("dir_capitol", 4, 160, 56, 14, "TO CAPITOL"),
        ("dir_river", 68, 160, 56, 14, "TO RIVER"),
        ("dir_mountains", 132, 160, 56, 14, "TO MOUNTAINS"),
        ("dir_sea", 196, 160, 56, 14, "TO THE SEA"),
    ]
    for _, rx, ry, rw, rh, label in dir_specs:
        c.rect(rx, ry, rw, rh, BRZ_MID)
        c.box_outline(rx, ry, rw, rh, DEEP_INK)
        c.box_outline(rx + 1, ry + 1, rw - 2, rh - 2, BRZ_SPEC)
        tw_l, _ = measure_text(label, "3x5", scale=1, spacing=1)
        assert tw_l <= rw - 4, f"Direction plate '{label}' overflow: {tw_l} > {rw - 4}"
        c.draw_text(rx + (rw - tw_l) // 2, ry + 4, label, DEEP_INK, "3x5", scale=1, spacing=1, shadow=BRZ_SPEC)

    # 10. zodiac_band (4, 180, 128, 16): Engraved celestial star & zodiac glyph belt for armillary_ecliptic!
    c.rect(4, 180, 128, 16, BRZ_MID)
    c.hline(4, 131, 180, BRZ_SHD)
    c.hline(4, 131, 181, BRZ_SPEC)
    c.hline(4, 131, 194, BRZ_SPEC)
    c.hline(4, 131, 195, BRZ_SHD)
    for gi in range(12):
        gx = 8 + gi * 10
        # Alternating celestial star, crescent, diamond & cross zodiac engravings
        c.box_outline(gx, 183, 8, 10, BRZ_LOW)
        if gi % 3 == 0:
            c.hline(gx + 2, gx + 5, 188, BRZ_SPEC)
            c.vline(gx + 3, 185, 191, BRZ_SPEC)
        elif gi % 3 == 1:
            c.box_outline(gx + 2, 185, 4, 6, BRZ_SPEC)
        else:
            c.put(gx + 3, 185, BRZ_SPEC)
            c.hline(gx + 2, gx + 5, 188, BRZ_SPEC)
            c.put(gx + 3, 191, BRZ_SPEC)

    # 11. earth_globe (136, 180, 64, 20): Stylized verdigris-copper continents & dark patinated ocean for armillary_globe!
    for dy in range(20):
        for dx in range(64):
            # Latitude/longitude grid + stylized patinated land masses
            lon_grid = (dx % 8 == 0)
            lat_grid = (dy % 5 == 0)
            land = (math.sin(dx * 0.16 + 0.5) * math.cos(dy * 0.22 - 0.3) + 0.35 * math.sin(dx * 0.31 - dy * 0.25)) > 0.12
            if lon_grid or lat_grid:
                col = BRZ_LOW
            elif land:
                col = COP_HI if (dx + dy) % 4 == 0 else COP_MID
            else:
                col = COP_SHD if h2(dx, dy, 613) < 120 else COP_LOW
            c.put(136 + dx, 180 + dy, col)

    # 12. Carved Stone Guardian Name Bands directly beneath each 7 m Guardian!
    for reg_x, reg_w, gtxt in (
        (4, 60, "DEFENSE"),
        (68, 60, "LEARNING"),
        (132, 60, "INDUSTRY"),
        (196, 56, "HARVEST"),
    ):
        paint_stone_bg(reg_x, 202, reg_w, 14)
        c.box_outline(reg_x, 202, reg_w, 14, LIME_MORT)
        tw_g, _ = measure_text(gtxt, "5x7", scale=1, spacing=1)
        assert tw_g <= reg_w - 6
        c.draw_text(reg_x + (reg_w - tw_g) // 2, 205, gtxt, (68, 60, 52, 255), "5x7", scale=1, spacing=1, shadow=LIME_HI)

    # 13. visitor_sign (4, 224, 96, 18): 2000+ Restored Visitor Centre stone fascia inscription!
    paint_stone_bg(4, 224, 96, 18)
    c.box_outline(4, 224, 96, 18, LIME_MORT)
    vs = "VISITOR CENTER"
    tw_v, _ = measure_text(vs, "5x7", scale=1, spacing=1)
    assert tw_v <= 88
    c.draw_text(4 + (96 - tw_v) // 2, 229, vs, (64, 58, 50, 255), "5x7", scale=1, spacing=1, shadow=LIME_HI)

    c.save_2x(TEX_DIR / "inscriptions.png")
    return {
        "frieze_main": [4, 4, 236, 32],
        "frieze_east": [4, 40, 180, 24],
        "frieze_west": [4, 68, 180, 24],
        "civic_flag": [188, 40, 44, 26],
        "bunting_swag": [188, 72, 56, 22],
        "dedication_wall": [4, 98, 72, 52],
        "bronze_door_panel": [188, 98, 32, 52],
        "plaque_war": [224, 98, 24, 18],
        "plaque_dedication": [224, 120, 24, 18],
        "dir_capitol": [4, 160, 56, 14],
        "dir_river": [68, 160, 56, 14],
        "dir_mountains": [132, 160, 56, 14],
        "dir_sea": [196, 160, 56, 14],
        "zodiac_band": [4, 180, 128, 16],
        "earth_globe": [136, 180, 64, 20],
        "label_defense": [4, 202, 60, 14],
        "label_learning": [68, 202, 60, 14],
        "label_industry": [132, 202, 60, 14],
        "label_harvest": [196, 202, 56, 14],
        "visitor_sign": [4, 224, 96, 18],
    }


def make_glow_monument():
    """
    64x64 art px base + pure-black (#000000) emissive mask strictly for the ONLY 6 permitted emissives:
    beacon orb, 8 lantern lancets, narrow pier window slots, globe lamp heads, eternal flames,
    reflecting pool fountain jets, and thin ecliptic star line (< 20 m2 total!).
    """
    base = ArtCanvas(64, 64, (28, 30, 38, 255))
    mask = ArtCanvas(64, 64, (0, 0, 0, 255))

    # 1. flame_brazier (0, 0, 16, 16)
    for y in range(16):
        for x in range(16):
            dx = (x - 7.5) / 5.8
            width_at_y = max(0.0, 1.0 - max(0.0, (13.0 - y) / 13.0) ** 1.35)
            d_fl = abs(dx) / max(0.18, width_at_y + 0.14)
            if 2 <= y <= 14 and d_fl < 0.92:
                if y >= 7 and d_fl < 0.42:
                    col = (255, 250, 212, 255)
                elif y >= 5 and d_fl < 0.70:
                    col = (255, 198, 76, 255)
                else:
                    col = (238, 118, 36, 255)
                base.put(x, y, col)
                mask.put(x, y, col)
            else:
                base.put(x, y, (34, 36, 44, 255))

    # 2. lamp_globe (16, 0, 16, 16)
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - 7.5, y - 7.5)
            if r <= 5.6:
                col = (255, 246, 214, 255) if r < 3.2 else (246, 214, 136, 255)
                base.put(16 + x, y, col)
                mask.put(16 + x, y, col)
            else:
                base.put(16 + x, y, (44, 48, 58, 255))

    # 3. beacon_orb (32, 0, 16, 16)
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - 7.5, y - 7.5)
            if r <= 6.6:
                col = (255, 254, 238, 255) if r < 3.8 else (255, 224, 136, 255)
                base.put(32 + x, y, col)
                mask.put(32 + x, y, col)
            else:
                base.put(32 + x, y, (48, 42, 34, 255))

    # 4. fountain_jet (48, 0, 16, 16): thin sparkling reflecting pool water-jet strip!
    for y in range(16):
        for x in range(16):
            if 5 <= x <= 10 and 1 <= y <= 14:
                col = (224, 248, 255, 255) if (x in (7, 8) or (x + y) % 3 == 0) else (128, 206, 238, 255)
                base.put(48 + x, y, col)
                mask.put(48 + x, y, col)
            else:
                base.put(48 + x, y, (34, 58, 86, 255))

    # 5. lantern_lancet_glow (0, 16, 16, 48): warm amber glowing lantern lancet core!
    for y in range(48):
        for x in range(16):
            if x in (0, 1, 14, 15) or y in (0, 1, 46, 47) or y % 12 == 0:
                base.put(x, 16 + y, (42, 44, 58, 255))
            else:
                col = (255, 226, 134, 255) if 5 <= x <= 10 else (232, 174, 76, 255)
                base.put(x, 16 + y, col)
                mask.put(x, 16 + y, col)

    # 6. pier_slot_glow (16, 16, 16, 48): narrow warm amber pier window slot!
    for y in range(48):
        for x in range(16):
            if x in (0, 1, 2, 13, 14, 15) or y in (0, 1, 46, 47) or y % 16 == 0:
                base.put(16 + x, 16 + y, (42, 44, 58, 255))
            else:
                col = (248, 206, 114, 255) if 6 <= x <= 9 else (216, 156, 68, 255)
                base.put(16 + x, 16 + y, col)
                mask.put(16 + x, 16 + y, col)

    # 7. ecliptic_glow (32, 16, 32, 8): thin glowing star-band line for the Armillary ecliptic ring!
    for y in range(8):
        for x in range(32):
            if y in (3, 4):
                col = (255, 236, 156, 255) if x % 4 != 0 else (246, 198, 92, 255)
                base.put(32 + x, 16 + y, col)
                mask.put(32 + x, 16 + y, col)
            else:
                base.put(32 + x, 16 + y, BRZ_LOW)

    # Remaining bottom-right area (32..63, 24..63) stays pure #000000 black in mask!
    base.save_2x(TEX_DIR / "glow_monument_base.png")
    mask.save_2x(TEX_DIR / "glow_monument_mask.png")
    return {
        "flame_brazier": [0, 0, 16, 16],
        "lamp_globe": [16, 0, 16, 16],
        "beacon_orb": [32, 0, 16, 16],
        "fountain_jet": [48, 0, 16, 16],
        "lantern_lancet_glow": [0, 16, 16, 48],
        "pier_slot_glow": [16, 16, 16, 48],
        "ecliptic_glow": [32, 16, 32, 8],
    }


def main():
    # Clean any stale obsolete textures from previous single-state build
    for old_name in ("stone_limestone.png", "roof_slate.png"):
        old_p = TEX_DIR / old_name
        if old_p.exists():
            old_p.unlink()

    make_limestone_variant("stone_limestone_clean.png", LIME_HI, LIME_MID, LIME_LOW, LIME_SHD, LIME_MORT, False, 11)
    make_limestone_variant("stone_limestone_mature.png", MAT_HI, MAT_MID, MAT_LOW, MAT_SHD, MAT_MORT, False, 19)
    make_limestone_variant("stone_limestone_worn.png", WRN_HI, WRN_MID, WRN_LOW, WRN_SHD, WRN_MORT, True, 29)
    make_stone_granite()
    make_steel_truss()
    make_copper_variant("copper_fresh.png", FCOP_HI, FCOP_MID, FCOP_LOW, FCOP_SHD, 83)
    make_copper_variant("copper_verdigris.png", COP_HI, COP_MID, COP_LOW, COP_SHD, 97)
    make_bronze_gilded()
    make_water_pool()
    make_marble_plaza()
    glass_regions = make_glass_cathedral()
    detail_regions = make_detail_atlas()
    guard_regions = make_guardians_atlas()
    inscr_regions = make_inscriptions()
    glow_regions = make_glow_monument()

    regions = {
        "glass_cathedral": glass_regions,
        "detail_atlas": detail_regions,
        "guardians_atlas": guard_regions,
        "inscriptions": inscr_regions,
        "glow_monument": glow_regions,
    }
    (R / "art-regions.json").write_text(json.dumps(regions, indent=2) + "\n", encoding="utf-8")
    print("Generated all 16 Great Meridian Monument 2x2 art-pixel textures + pure-black emissive mask.")


if __name__ == "__main__":
    main()
