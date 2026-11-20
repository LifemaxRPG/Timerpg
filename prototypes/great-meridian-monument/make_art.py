#!/usr/bin/env python3
"""
Texture and pixel-art generator for THE GREAT MERIDIAN MONUMENT (Dedicated 1926).
94 m national monument on a 120 m x 120 m ceremonial acropolis & reflecting pool plaza.

Strict contract compliance (docs/CHATGPT_MODEL_GUIDE.md):
- Every texture is painted in whole art pixels and exported at exact 2x2 texels
  (0.0388 m/texel -> 0.0776 m/art pixel).
- LimeZu-harmonized warm palette with crisp 1-art-px dark outlines (#343448),
  top-edge highlights, 2-4 step flat shading + dither, and architectural wear.
- Separate pure-black emissive mask (glow_monument_mask.png) so ONLY eternal flames,
  Art Deco lamp globes, the 94 m Astral Beacon orb, and tiny uplight lenses glow!
- All carved inscriptions measured strictly against their frieze bounds (zero overflow).
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
# LIMEZU-HARMONIZED MONUMENTAL PALETTE (warm, rich, outlined, 2-4 stepped tones)
# ==============================================================================
OUTLINE = (52, 52, 72, 255)
DEEP_INK = (34, 36, 50, 255)

# 1. Warm Cream Monument Limestone (Indiana / Beaux-Arts travertine-limestone)
LIME_HI = (238, 228, 206, 255)
LIME_MID = (218, 206, 182, 255)
LIME_LOW = (194, 180, 156, 255)
LIME_SHD = (158, 144, 124, 255)
LIME_MORT = (118, 108, 96, 255)

# 2. Monumental Dressed Granite (plinth, stairs, pool coping)
GRAN_HI = (192, 188, 182, 255)
GRAN_MID = (164, 160, 156, 255)
GRAN_LOW = (136, 134, 132, 255)
GRAN_SHD = (102, 100, 104, 255)
GRAN_MORT = (76, 76, 86, 255)

# 3. Riveted Bridge Steel (cathedral open-truss arches & structural girders)
STEEL_HI = (106, 118, 134, 255)
STEEL_MID = (76, 86, 102, 255)
STEEL_LOW = (54, 62, 76, 255)
STEEL_RIV = (142, 154, 170, 255)

# 4. Patinated Verdigris Copper (spire, crown, outer meridian rings)
COP_HI = (132, 208, 184, 255)
COP_MID = (92, 172, 152, 255)
COP_LOW = (62, 134, 120, 255)
COP_SHD = (42, 96, 90, 255)

# 5. Burnished Art Deco Gilded Bronze (armillary sphere, sunbursts, keystones, finial)
BRZ_SPEC = (248, 224, 144, 255)
BRZ_HI = (224, 184, 92, 255)
BRZ_MID = (182, 140, 62, 255)
BRZ_LOW = (132, 96, 42, 255)
BRZ_SHD = (86, 62, 32, 255)

# 6. Cerulean Reflecting Pool Water
WAT_HI = (142, 204, 232, 255)
WAT_MID = (76, 144, 192, 255)
WAT_DEEP = (46, 104, 156, 255)
WAT_SHD = (32, 74, 120, 255)

# 7. Dark Architectural Slate / Marble Trim
SLATE_HI = (94, 98, 114, 255)
SLATE_MID = (68, 72, 86, 255)
SLATE_LOW = (48, 52, 66, 255)

# 8. Ceremonial Lawn Turf (LimeZu green)
LAWN_HI = (114, 168, 86, 255)
LAWN_MID = (88, 144, 68, 255)
LAWN_LOW = (68, 118, 54, 255)

# 9. Cedar Bench Timber
WOOD_HI = (176, 122, 78, 255)
WOOD_MID = (144, 94, 56, 255)
WOOD_LOW = (108, 68, 40, 255)


def make_stone_limestone():
    """64x64 art px (128x128 texels) dressed monumental cream ashlar limestone."""
    c = ArtCanvas(64, 64, LIME_MID)
    course_h = 8
    for row_idx in range(8):
        y0 = row_idx * course_h
        offset = 0 if row_idx % 2 == 0 else 16
        for blk in range(-1, 3):
            x0 = blk * 32 + offset
            tone_v = h2(blk + 10, row_idx, 11) % 3
            base = (LIME_MID, LIME_HI, LIME_LOW)[tone_v]
            for dy in range(course_h):
                for dx in range(32):
                    px = (x0 + dx) % 64
                    py = y0 + dy
                    if dy == 0:
                        col = LIME_MORT
                    elif dy == 1:
                        col = LIME_HI
                    elif dy == course_h - 1:
                        col = LIME_SHD
                    elif dx == 0:
                        col = LIME_MORT
                    elif dx == 1:
                        col = LIME_HI if base != LIME_HI else LIME_MID
                    elif dx == 31:
                        col = LIME_LOW
                    else:
                        n = h2(px, py, 23)
                        if n < 24:
                            col = LIME_LOW
                        elif n > 236:
                            col = LIME_HI
                        elif n == 128 and dy > 3:
                            col = LIME_SHD
                        else:
                            col = base
                    c.put(px, py, col)
    # Subtle vertical rain-weathering trace on two joints
    for py in range(64):
        if py % 3 != 0:
            c.put(17, py, LIME_LOW)
            c.put(49, py, LIME_LOW)
    c.save_2x(TEX_DIR / "stone_limestone.png")


def make_stone_granite():
    """64x64 art px (128x128 texels) monumental dressed granite blocks for acropolis & steps."""
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
                        if n < 34:
                            col = GRAN_SHD
                        elif n > 224:
                            col = GRAN_HI
                        else:
                            col = base
                    c.put(px, py, col)
    c.save_2x(TEX_DIR / "stone_granite.png")


def make_steel_truss():
    """64x64 art px (128x128 texels) riveted structural bridge steel for soaring open arches."""
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
            else:
                # Diagonal subtle metallic sheen + corner rivets
                if (bx in (3, 12) and by in (3, 12)) or (bx in (3, 12) and by == 8) or (by in (3, 12) and bx == 8):
                    col = STEEL_RIV
                elif (bx in (3, 12) and by in (4, 13)):
                    col = DEEP_INK
                elif (x + y) % 9 == 0:
                    col = STEEL_LOW
                elif h2(x, y, 71) > 230:
                    col = STEEL_HI
                else:
                    col = STEEL_MID
            c.put(x, y, col)
    c.save_2x(TEX_DIR / "steel_truss.png")


def make_copper_verdigris():
    """64x64 art px (128x128 texels) standing-seam oxidized verdigris monument copper."""
    c = ArtCanvas(64, 64, COP_MID)
    for y in range(64):
        for x in range(64):
            sx = x % 8
            sy = y % 16
            if sx == 0:
                col = COP_SHD
            elif sx == 1:
                col = COP_HI
            elif sx == 7:
                col = COP_LOW
            elif sy == 0:
                col = COP_SHD
            elif sy == 1:
                col = COP_HI
            else:
                n = h2(x, y, 97)
                if n < 42:
                    col = COP_LOW
                elif n > 218:
                    col = COP_HI
                elif (x + y * 2) % 13 == 0:
                    col = COP_LOW
                else:
                    col = COP_MID
            c.put(x, y, col)
    c.save_2x(TEX_DIR / "copper_verdigris.png")


def make_bronze_gilded():
    """64x64 art px (128x128 texels) burnished Art Deco gilded architectural bronze."""
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
                # Embossed Art Deco diamond filigree motif
                col = BRZ_HI
            elif abs(bx - 8) + abs(by - 8) == 5:
                col = BRZ_LOW
            else:
                n = h2(x, y, 131)
                if n < 36:
                    col = BRZ_LOW
                elif n > 224:
                    col = BRZ_HI
                else:
                    col = BRZ_MID
            c.put(x, y, col)
    c.save_2x(TEX_DIR / "bronze_gilded.png")


def make_water_pool():
    """64x64 art px (128x128 texels) deep cerulean monumental reflecting pool water."""
    c = ArtCanvas(64, 64, WAT_DEEP)
    for y in range(64):
        for x in range(64):
            # Layered stepped horizontal reflections + gentle sparkle glints
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
    """64x64 art px (128x128 texels) ceremonial inlaid travertine & slate plaza paving."""
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
                # Inlaid dark slate corner diamonds
                col = SLATE_MID
            else:
                base = LIME_HI if checker == 0 else LIME_MID
                n = h2(x, y, 173)
                if n < 22:
                    col = LIME_LOW
                elif n > 238:
                    col = LIME_HI
                else:
                    col = base
            c.put(x, y, col)
    c.save_2x(TEX_DIR / "marble_plaza.png")


def make_roof_slate():
    """64x64 art px (128x128 texels) dark dressed architectural slate & shadow marble trim."""
    c = ArtCanvas(64, 64, SLATE_MID)
    for y in range(64):
        for x in range(64):
            row = y // 8
            ox = (x + (8 if row % 2 else 0)) % 16
            oy = y % 8
            if oy == 0 or ox == 0:
                col = DEEP_INK
            elif oy == 1:
                col = SLATE_HI
            elif oy == 7:
                col = SLATE_LOW
            else:
                n = h2(x, y, 199)
                col = SLATE_LOW if n < 45 else (SLATE_HI if n > 225 else SLATE_MID)
            c.put(x, y, col)
    c.save_2x(TEX_DIR / "roof_slate.png")


def make_glass_cathedral():
    """128x128 art px (256x256 texels) leaded Art Deco cathedral & sky-lantern glass (BLEND)."""
    c = ArtCanvas(128, 128, (36, 52, 74, 210))
    G_DARK = (38, 56, 82, 224)
    G_MID = (64, 96, 134, 206)
    G_HI = (132, 176, 214, 198)
    G_AMBER = (226, 178, 92, 218)
    G_AMBER_HI = (248, 216, 138, 224)
    MULL = (42, 44, 58, 255)

    # Region 1: win_cathedral (0, 0, 64, 128) — tall Art Deco arched cathedral mullioned glass
    for y in range(128):
        for x in range(64):
            if x in (0, 1, 62, 63) or y in (0, 1, 126, 127):
                col = MULL
            elif x in (21, 42) or y % 20 == 0:
                col = MULL
            elif (x + y) % 16 == 0 or (x - y) % 16 == 0:
                col = G_MID
            elif (x + y) % 24 < 3:
                col = G_HI
            elif y < 26 and (x % 4 == 0 or y % 4 == 0):
                # Subtle golden Art Deco chevron top transom
                col = G_AMBER
            else:
                col = G_DARK
            c.put(x, y, col)

    # Region 2: win_lantern (64, 0, 64, 128) — warm golden-amber Art Deco sky lantern lancet
    for y in range(128):
        for x in range(64):
            px = 64 + x
            if x in (0, 1, 62, 63) or y in (0, 1, 126, 127):
                col = MULL
            elif x in (16, 32, 48) or y % 16 == 0:
                col = MULL
            elif abs(x - 32) < 8 and y % 8 < 4:
                col = G_AMBER_HI
            elif (x + y) % 10 < 2:
                col = G_AMBER_HI
            elif (x + y) % 10 < 7:
                col = G_AMBER
            else:
                col = (164, 118, 56, 220)
            c.put(px, y, col)

    c.save_2x(TEX_DIR / "glass_cathedral.png")
    return {
        "win_cathedral": [0, 0, 64, 128],
        "win_lantern": [64, 0, 64, 128],
    }


def make_detail_atlas():
    """128x128 art px (256x256 texels) 4 clean 64x64 quadrants: lawn, dark iron, compass gold, cedar wood."""
    c = ArtCanvas(128, 128, OUTLINE)

    # Q0 (0, 0, 64, 64): lawn_turf — manicured ceremonial grass with subtle dither
    for y in range(64):
        for x in range(64):
            n = h2(x, y, 211)
            stripe = (x // 8) % 2
            if n < 30:
                col = LAWN_LOW
            elif n > 222:
                col = LAWN_HI
            else:
                col = LAWN_MID if stripe == 0 else (98, 154, 76, 255)
            c.put(x, y, col)

    # Q1 (64, 0, 64, 64): dark_iron — wrought architectural ironwork
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

    # Q2 (0, 64, 64, 64): compass_mosaic — rich inlaid marble, deep navy slate & gilded bronze 8-point star tile!
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
                # Alternating light/dark gilded facets of 8-point compass rose!
                col = BRZ_HI if math.sin(4.0 * ang) >= 0 else BRZ_LOW
            elif r < 27.0:
                col = SLATE_LOW if (x + y) % 2 == 0 else SLATE_MID
            else:
                col = LIME_HI if ((x // 4 + y // 4) % 2 == 0) else LIME_MID
            c.put(x, 64 + y, col)

    # Q3 (64, 64, 64, 64): timber_bench — warm horizontal varnished cedar planks
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


def make_inscriptions():
    """256x128 art px (512x256 texels) carved-in-limestone friezes, bronze plaque, sunburst & civic flag."""
    c = ArtCanvas(256, 128, LIME_MID)

    def paint_limestone_bg(x0, y0, w, h):
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

    # 1. frieze_main (4, 4, 236, 32): Flush carved directly in limestone — NO sign-plate frame!
    #    Line 1: "THE GREAT MERIDIAN" (18 chars x scale 2 -> 214x14 px)
    #    Line 2: "DEDICATED MCMXXVI"  (17 chars x scale 1 -> 101x7 px)
    fx, fy, fw, fh = 4, 4, 236, 32
    paint_limestone_bg(fx, fy, fw, fh)
    t1 = "THE GREAT MERIDIAN"
    w1, h1 = measure_text(t1, "5x7", scale=2, spacing=1)
    assert w1 <= fw - 10, f"frieze_main line 1 overflow: {w1} > {fw - 10}"
    c.draw_text(fx + (fw - w1) // 2, fy + 4, t1, (74, 66, 58, 255), "5x7", scale=2, spacing=1, shadow=LIME_HI)

    t2 = "DEDICATED MCMXXVI"
    w2, h2_t = measure_text(t2, "5x7", scale=1, spacing=1)
    assert w2 <= fw - 20, f"frieze_main line 2 overflow: {w2} > {fw - 20}"
    c.draw_text(fx + (fw - w2) // 2, fy + 21, t2, (92, 82, 72, 255), "5x7", scale=1, spacing=1, shadow=LIME_HI)

    # 2. frieze_east (4, 40, 180, 24) & frieze_west (4, 68, 180, 24) — flush carved limestone!
    for reg_y, txt in ((40, "MERIDIAN EAST"), (68, "MERIDIAN WEST")):
        paint_limestone_bg(4, reg_y, 180, 24)
        tw, th = measure_text(txt, "5x7", scale=2, spacing=1)
        assert tw <= 180 - 12, f"{txt} overflow: {tw}"
        c.draw_text(4 + (180 - tw) // 2, reg_y + (24 - th) // 2, txt, (76, 68, 60, 255), "5x7", scale=2, spacing=1, shadow=LIME_HI)

    # 3. relief_sunburst (192, 40, 56, 56) — Art Deco carved limestone & gilded bronze sunburst!
    sx0, sy0, sw = 192, 40, 56
    paint_limestone_bg(sx0, sy0, sw, sw)
    c.box_outline(sx0, sy0, sw, sw, LIME_MORT)
    c.box_outline(sx0 + 2, sy0 + 2, sw - 4, sw - 4, BRZ_MID)
    scx, scy = sx0 + sw / 2.0 - 0.5, sy0 + sw / 2.0 - 0.5
    for dy in range(4, sw - 4):
        for dx in range(4, sw - 4):
            px, py = sx0 + dx, sy0 + dy
            rx, ry = px - scx, py - scy
            r = math.hypot(rx, ry)
            ang = math.atan2(ry, rx)
            ray = math.cos(12.0 * ang)
            if r < 6.0:
                c.put(px, py, BRZ_SPEC)
            elif r < 8.0:
                c.put(px, py, BRZ_SHD)
            elif r < 22.0 and ray > 0.35:
                c.put(px, py, BRZ_HI if ray > 0.7 else BRZ_MID)
            elif abs(r - 22.0) < 1.3:
                c.put(px, py, BRZ_LOW)

    # 4. civic_flag (4, 98, 44, 26) — ceremonial deep navy, crimson & gold meridian star banner!
    bx, by, bw, bh = 4, 98, 44, 26
    for dy in range(bh):
        for dx in range(bw):
            wave = int(1.0 * math.sin(dx * 0.25))
            # Horizontal ceremonial stripes (crimson & cream) with navy-and-gold meridian canton
            if dx < 16 and dy < 15:
                # Deep navy canton with golden 8-point meridian star
                rx, ry = dx - 7.5, dy - 7.0
                r = math.hypot(rx, ry)
                ang = math.atan2(ry, rx)
                if r < 2.2 or (r < 5.5 and abs(math.cos(4.0 * ang)) > 0.72):
                    col = BRZ_SPEC
                else:
                    col = (34, 52, 92, 255)
            else:
                stripe = ((dy + wave) // 4) % 2
                col = (168, 48, 52, 255) if stripe == 0 else (232, 224, 204, 255)
            if dx == 0 or dx == bw - 1 or dy == 0 or dy == bh - 1:
                col = DEEP_INK
            c.put(bx + dx, by + dy, col)

    # 5. plaque_dedication (56, 98, 16, 12) — small eye-height cast bronze historical plaque!
    px0, py0, pw, ph = 56, 98, 16, 12
    c.rect(px0, py0, pw, ph, BRZ_SHD)
    c.box_outline(px0, py0, pw, ph, DEEP_INK)
    c.box_outline(px0 + 1, py0 + 1, pw - 2, ph - 2, BRZ_HI)
    for ry in (3, 5, 7, 9):
        c.hline(px0 + 3, px0 + pw - 4, py0 + ry, BRZ_SPEC if ry == 3 else BRZ_HI)

    # 6. cardinal_stone (80, 98, 24, 12) — "1926" chiseled cornerstone block!
    paint_limestone_bg(80, 98, 24, 12)
    c.box_outline(80, 98, 24, 12, LIME_MORT)
    tw_yr, _ = measure_text("1926", "3x5", scale=1, spacing=1)
    c.draw_text(80 + (24 - tw_yr) // 2, 102, "1926", (68, 62, 54, 255), "3x5", scale=1, spacing=1, shadow=LIME_HI)

    c.save_2x(TEX_DIR / "inscriptions.png")
    return {
        "frieze_main": [4, 4, 236, 32],
        "frieze_east": [4, 40, 180, 24],
        "frieze_west": [4, 68, 180, 24],
        "relief_sunburst": [192, 40, 56, 56],
        "civic_flag": [4, 98, 44, 26],
        "plaque_dedication": [56, 98, 16, 12],
        "cornerstone_1926": [80, 98, 24, 12],
    }


def make_glow_monument():
    """64x32 art px (128x64 texels) base + pure-black emissive mask for flames, globes, beacon & lenses."""
    base = ArtCanvas(64, 32, (28, 30, 38, 255))
    mask = ArtCanvas(64, 32, (0, 0, 0, 255))

    # 1. flame_brazier (0, 0, 16, 16): layered warm pixel flame surrounded by dark iron rim (black mask!)
    for y in range(16):
        for x in range(16):
            dx = (x - 7.5) / 6.0
            dy = (y - 11.0) / 8.5
            # Teardrop flame shape tapering toward y=1
            width_at_y = max(0.0, 1.0 - max(0.0, (13.0 - y) / 13.0) ** 1.35)
            d_fl = abs(dx) / max(0.18, width_at_y + 0.15)
            if 2 <= y <= 14 and d_fl < 0.95:
                if y >= 7 and d_fl < 0.42:
                    col = (255, 250, 212, 255)
                elif y >= 5 and d_fl < 0.72:
                    col = (255, 198, 76, 255)
                else:
                    col = (238, 118, 36, 255)
                base.put(x, y, col)
                mask.put(x, y, col)
            else:
                base.put(x, y, (34, 36, 44, 255))
                mask.put(x, y, (0, 0, 0, 255))

    # 2. lamp_globe (16, 0, 16, 16): frosted Art Deco globe with iron frame border (black mask on frame!)
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - 7.5, y - 7.5)
            if r <= 5.8:
                col = (255, 246, 214, 255) if r < 3.2 else (246, 214, 136, 255)
                base.put(16 + x, y, col)
                mask.put(16 + x, y, col)
            else:
                base.put(16 + x, y, (44, 48, 58, 255))
                mask.put(16 + x, y, (0, 0, 0, 255))

    # 3. beacon_orb (32, 0, 16, 16): radiant golden-white 94 m Astral Beacon jewel!
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - 7.5, y - 7.5)
            if r <= 6.8:
                col = (255, 254, 238, 255) if r < 3.8 else (255, 224, 136, 255)
                base.put(32 + x, y, col)
                mask.put(32 + x, y, col)
            else:
                base.put(32 + x, y, (48, 42, 34, 255))
                mask.put(32 + x, y, (0, 0, 0, 255))

    # 4. flood_lens (48, 0, 16, 16): compact warm architectural uplight lens
    for y in range(16):
        for x in range(16):
            if 3 <= x <= 12 and 3 <= y <= 12:
                col = (255, 232, 168, 255) if (5 <= x <= 10 and 5 <= y <= 10) else (238, 188, 96, 255)
                base.put(48 + x, y, col)
                mask.put(48 + x, y, col)
            else:
                base.put(48 + x, y, (42, 46, 56, 255))
                mask.put(48 + x, y, (0, 0, 0, 255))

    # Bottom half (y = 16..31) remains pure black in emissive mask!
    base.save_2x(TEX_DIR / "glow_monument_base.png")
    mask.save_2x(TEX_DIR / "glow_monument_mask.png")
    return {
        "flame_brazier": [0, 0, 16, 16],
        "lamp_globe": [16, 0, 16, 16],
        "beacon_orb": [32, 0, 16, 16],
        "flood_lens": [48, 0, 16, 16],
    }


def main():
    make_stone_limestone()
    make_stone_granite()
    make_steel_truss()
    make_copper_verdigris()
    make_bronze_gilded()
    make_water_pool()
    make_marble_plaza()
    make_roof_slate()
    glass_regions = make_glass_cathedral()
    detail_regions = make_detail_atlas()
    inscr_regions = make_inscriptions()
    glow_regions = make_glow_monument()

    regions = {
        "glass_cathedral": glass_regions,
        "detail_atlas": detail_regions,
        "inscriptions": inscr_regions,
        "glow_monument": glow_regions,
    }
    (R / "art-regions.json").write_text(json.dumps(regions, indent=2) + "\n", encoding="utf-8")
    print("Generated all 12 Great Meridian Monument pixel-art textures (exact 2x2 art-pixel grid) + pure-black emissive mask.")


if __name__ == "__main__":
    main()
