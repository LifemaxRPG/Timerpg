#!/usr/bin/env python3
"""
Procedural pixel-art texture generator for THE GREAT MERIDIAN MONUMENT — REVISION 2.
Strict 0.0388 m/texel contract: every texture is drawn at 1x art-pixel resolution
and scaled by exact 2x2 nearest-neighbor blocks on save.

Textures produced (12 materials per state across 4 eras: 1926, 1941, 1976, 2000+):
  1.  stone_limestone_1926/1941/1976/2000.png : Warm cream 0.6 m courses, 2-tone block variation, chipped corners, era cornice drips
  2.  stone_granite.png                       : Dark grey-pink granite, 1.2 x 0.6 m polished blocks, fine joints, base rain-splash
  3.  stone_ashlar.png                        : Smooth lighter pale ashlar bands (no joints) for cornices, friezes, setback caps
  4.  steel_truss.png                         : Riveted dark charcoal-steel girders
  5.  copper_1926/1941/1976/2000.png          : 1926 fresh orange-brown -> 1941 streaky brown-green -> 1976 deep green w/ black streaks -> 2000+ restored bright green
  6.  bronze_gilded.png                       : Rich burnished bronze w/ strong light-gold top edge & stepped shading
  7.  water_pool.png                          : Clean, lily-pad-free reflecting pool water
  8.  marble_plaza.png & marble_plaza_1976.png: Crisp two-tone marble paving (+ weeds in joints for 1976!)
  9.  glass_cathedral.png & _1941.png         : Translucent amber-and-slate glass (+ wartime white taped X-crosses for 1941!)
  10. detail_atlas.png                        : 4 strict 64x64 quadrants: lawn_turf, dark_iron, arch_coffer (deep recessed coffers!), timber_bench
  11. inscriptions.png (512x512 art px)       : Carved friezes, 14 m N/E/S/W Compass Rose, 6-panel 5 m Bronze Door relief, West Hall Dedication Wall + wreaths,
                                                East Hall State Map w/ Meridian Line, chevrons, voussoirs, compass keystone, cornerstone 1926, exedra text,
                                                3-tone carved Guardian stone (clean & 1976 streaked), verdigris/bronze Earth globe, 12 zodiac symbols,
                                                flags, 1926 bunting, 1941 sandbags, 1976 tarps/chain-link/"CLOSED FOR REPAIRS", 2000+ signs
  12. glow_monument_base.png + pure-black glow_monument_mask.png (< 20 m2 total!)
"""
import json
import math
import struct
import zlib
from pathlib import Path
from pixel_font import FONT_3X5, FONT_5X7, measure_text

R = Path(__file__).resolve().parent
TEX_DIR = R / "textures"
TEX_DIR.mkdir(parents=True, exist_ok=True)


def write_png_rgba(path, w, h, rows_rgba):
    raw = bytearray()
    for row in rows_rgba:
        raw.append(0)
        for r, g, b, a in row:
            raw.extend((r, g, b, a))

    def chunk(ctype, data):
        crc = zlib.crc32(ctype + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + ctype + data + struct.pack(">I", crc)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    idat = zlib.compress(bytes(raw), 6)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")
    Path(path).write_bytes(png)


class ArtCanvas:
    def __init__(self, w, h, fill=(0, 0, 0, 255)):
        self.w = w
        self.h = h
        self.px = [[fill for _ in range(w)] for _ in range(h)]

    def put(self, x, y, col):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = col

    def get(self, x, y):
        if 0 <= x < self.w and 0 <= y < self.h:
            return self.px[y][x]
        return (0, 0, 0, 255)

    def rect(self, x0, y0, w, h, col):
        for y in range(max(0, y0), min(self.h, y0 + h)):
            row = self.px[y]
            for x in range(max(0, x0), min(self.w, x0 + w)):
                row[x] = col

    def hline(self, x0, x1, y, col):
        if 0 <= y < self.h:
            for x in range(max(0, x0), min(self.w, x1 + 1)):
                self.px[y][x] = col

    def vline(self, x, y0, y1, col):
        if 0 <= x < self.w:
            for y in range(max(0, y0), min(self.h, y1 + 1)):
                self.px[y][x] = col

    def box_outline(self, x0, y0, w, h, col):
        self.hline(x0, x0 + w - 1, y0, col)
        self.hline(x0, x0 + w - 1, y0 + h - 1, col)
        self.vline(x0, y0, y0 + h - 1, col)
        self.vline(x0 + w - 1, y0, y0 + h - 1, col)

    def draw_text(self, x0, y0, text, fg, font="5x7", scale=1, spacing=1, shadow=None):
        glyphs = FONT_5X7 if font == "5x7" else FONT_3X5
        gw = 5 if font == "5x7" else 3
        cx = x0
        for ch in text.upper():
            g = glyphs.get(ch, glyphs[" "])
            for ry, rstr in enumerate(g):
                for rx, bit in enumerate(rstr):
                    if bit == "1":
                        if shadow is not None:
                            self.rect(cx + rx * scale, y0 + ry * scale + scale, scale, scale, shadow)
                        self.rect(cx + rx * scale, y0 + ry * scale, scale, scale, fg)
            cx += (gw + spacing) * scale

    def save_2x(self, path):
        out = []
        for row in self.px:
            doubled = []
            for c in row:
                doubled.append(c)
                doubled.append(c)
            out.append(doubled)
            out.append(list(doubled))
        write_png_rgba(path, self.w * 2, self.h * 2, out)


def h2(x, y, seed=0):
    n = (x * 374761393 + y * 668265263 + seed * 1442695041) & 0xFFFFFFFF
    n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
    return (n ^ (n >> 16)) & 0xFF


# ==============================================================================
# CURATED REVISION 2 PALETTE — THREE DISTINCT STONES + 4 COPPER ERAS + BRIGHT GOLD
# ==============================================================================
OUTLINE = (52, 46, 40, 255)
DEEP_INK = (26, 24, 28, 255)

# Stone 1: PALE WARM-CREAM LIMESTONE (0.6 m courses, 2-tone block variation!)
LIME_HI = (242, 232, 212, 255)
LIME_A = (228, 214, 190, 255)
LIME_B = (214, 198, 172, 255)
LIME_SHD = (178, 162, 138, 255)
LIME_MORT = (118, 106, 90, 255)

# Stone 2: DARK GREY-PINK POLISHED GRANITE (1.2 x 0.6 m big blocks, fine joints!)
GRAN_HI = (146, 132, 134, 255)
GRAN_A = (118, 104, 108, 255)
GRAN_B = (106, 94, 98, 255)
GRAN_SHD = (84, 74, 78, 255)
GRAN_JOINT = (60, 52, 56, 255)

# Stone 3: SMOOTH LIGHTER PALE ASHLAR BANDS (no heavy block joints!)
ASH_TOP = (250, 244, 230, 255)
ASH_HI = (242, 235, 220, 255)
ASH_MID = (232, 224, 206, 255)
ASH_LOW = (206, 196, 178, 255)
ASH_SHD = (162, 152, 136, 255)

# Gilded Bronze (strong bright-gold top highlight edge, warm gold mid, deep shadow underside!)
BRZ_SPEC = (255, 244, 184, 255)
BRZ_HI = (246, 210, 108, 255)
BRZ_MID = (204, 158, 66, 255)
BRZ_LOW = (146, 106, 40, 255)
BRZ_SHD = (92, 64, 24, 255)

# Verdigris Copper (2000+ clean & earth ocean)
COP_HI = (144, 214, 188, 255)
COP_MID = (94, 166, 140, 255)
COP_LOW = (62, 118, 98, 255)
COP_SHD = (42, 82, 68, 255)

# Dark Architectural Slate & Turf / Timber
SLATE_HI = (102, 112, 128, 255)
SLATE_MID = (74, 82, 96, 255)
SLATE_LOW = (48, 54, 66, 255)
LAWN_HI = (114, 168, 86, 255)
LAWN_MID = (88, 144, 68, 255)
LAWN_LOW = (62, 108, 48, 255)
WOOD_HI = (176, 128, 84, 255)
WOOD_MID = (142, 96, 58, 255)
WOOD_LOW = (102, 66, 38, 255)


def make_limestone_era(filename, era):
    """
    64x64 art px Pale Warm-Cream Limestone:
    - 0.6 m courses (8 art px tall x 16 art px wide)
    - Subtle 2-tone block variation + occasional chipped corners
    - Subtle dark rain-splash gradient at bottom of tile & era cornice drips at top
      (1926/2000: clean cream; 1941: slightly darker + faint drips; 1976: darker + heavy black drip lines!)
    """
    c = ArtCanvas(64, 64, LIME_A)
    if era == "1926":
        hi, ba, bb, shd, mort = (246, 236, 218, 255), (234, 222, 198, 255), (222, 208, 182, 255), (186, 172, 148, 255), (124, 112, 96, 255)
        drip_strength = 0
    elif era == "1941":
        hi, ba, bb, shd, mort = (224, 214, 194, 255), (208, 196, 174, 255), (196, 182, 160, 255), (160, 146, 126, 255), (104, 94, 80, 255)
        drip_strength = 1
    elif era == "1976":
        hi, ba, bb, shd, mort = (206, 194, 174, 255), (188, 176, 154, 255), (174, 160, 138, 255), (138, 126, 108, 255), (86, 76, 64, 255)
        drip_strength = 2
    else:  # 2000+ cleaned pale limestone (cleaner than 1941!)
        hi, ba, bb, shd, mort = (244, 234, 216, 255), (230, 218, 196, 255), (218, 204, 180, 255), (182, 168, 144, 255), (120, 108, 92, 255)
        drip_strength = 0

    for y in range(64):
        course = y // 8
        cy = y % 8
        off_x = 8 if (course % 2 == 1) else 0
        for x in range(64):
            bx = (x + off_x) // 16
            cx = (x + off_x) % 16
            bn = h2(bx, course, 17)
            block_base = ba if (bn % 2 == 0) else bb
            # Occasional chipped corner (deterministic)
            chipped = (bn > 224) and ((cx == 1 and cy == 1) or (cx == 14 and cy == 6))
            if cy == 0 or cx == 0:
                col = mort
            elif chipped:
                col = mort
            elif cy == 1 or cx == 1:
                col = hi
            elif cy == 7 or cx == 15:
                col = shd
            else:
                n = h2(x, y, 41)
                col = hi if n > 236 else (shd if n < 16 else block_base)

            # Gentle stepped rain-splash darkening near bottom of tile
            if y >= 59 and col != mort:
                dk = 16 if y >= 62 else 8
                col = (max(40, col[0] - dk), max(36, col[1] - dk), max(30, col[2] - dk), 255)

            # Era cornice drip lines running vertically down from top edge
            if drip_strength > 0:
                streak_seed = h2(x // 2, 7, 91)
                if drip_strength == 2 and streak_seed > 165:
                    max_d = 18 + (streak_seed % 34)
                    if y < max_d and (x % 3 != 0 or y < 14):
                        col = (max(44, col[0] - 46), max(40, col[1] - 44), max(36, col[2] - 40), 255)
                elif drip_strength == 1 and streak_seed > 205:
                    max_d = 10 + (streak_seed % 16)
                    if y < max_d:
                        col = (max(56, col[0] - 22), max(52, col[1] - 20), max(46, col[2] - 18), 255)
            c.put(x, y, col)
    c.save_2x(TEX_DIR / filename)


def make_stone_granite():
    """64x64 art px Dark Grey-Pink Granite: big polished 1.2 x 0.6 m (16 x 8 art px) blocks with fine joints & subtle pink feldspar flecks."""
    c = ArtCanvas(64, 64, GRAN_A)
    for y in range(64):
        course = y // 8
        cy = y % 8
        off_x = 8 if (course % 2 == 1) else 0
        for x in range(64):
            bx = (x + off_x) // 16
            cx = (x + off_x) % 16
            bn = h2(bx, course, 53)
            base = GRAN_A if bn % 2 == 0 else GRAN_B
            if cy == 0 or cx == 0:
                col = GRAN_JOINT
            elif cy == 1:
                col = GRAN_HI
            elif cy == 7:
                col = GRAN_SHD
            else:
                n = h2(x, y, 67)
                if n > 232:
                    col = (158, 134, 138, 255)  # subtle warm pink granite feldspar crystal!
                elif n < 24:
                    col = GRAN_SHD
                else:
                    col = base
            # Subtle base rain-splash darkening
            if y >= 58 and col != GRAN_JOINT:
                dk = 14 if y >= 61 else 7
                col = (max(36, col[0] - dk), max(32, col[1] - dk), max(34, col[2] - dk), 255)
            c.put(x, y, col)
    c.save_2x(TEX_DIR / "stone_granite.png")


def make_stone_ashlar():
    """64x64 art px Smooth Pale Ashlar Bands (lighter tone, NO block joints, pale bleached top highlight)."""
    c = ArtCanvas(64, 64, ASH_MID)
    for y in range(64):
        for x in range(64):
            if y in (0, 1):
                col = ASH_TOP
            elif y in (2, 3):
                col = ASH_HI
            elif y in (62, 63):
                col = ASH_SHD
            elif y in (60, 61):
                col = ASH_LOW
            else:
                n = h2(x, y, 79)
                col = ASH_HI if n > 218 else (ASH_LOW if n < 22 else ASH_MID)
            c.put(x, y, col)
    c.save_2x(TEX_DIR / "stone_ashlar.png")


def make_steel_truss():
    """64x64 art px riveted dark charcoal-steel girder."""
    c = ArtCanvas(64, 64, (58, 64, 78, 255))
    ST_HI = (116, 126, 144, 255)
    ST_MID = (74, 82, 98, 255)
    ST_LOW = (46, 52, 64, 255)
    for y in range(64):
        for x in range(64):
            lx, ly = x % 16, y % 16
            if lx == 0 or ly == 0:
                col = DEEP_INK
            elif lx == 1 or ly == 1:
                col = ST_HI
            elif (lx in (4, 12) and ly in (4, 12)):
                col = ST_HI
            elif (lx in (5, 13) and ly in (5, 13)):
                col = DEEP_INK
            else:
                col = ST_LOW if h2(x, y, 89) < 88 else ST_MID
            c.put(x, y, col)
    c.save_2x(TEX_DIR / "steel_truss.png")


def make_copper_era(filename, era):
    """64x64 art px ribbed copper roof/spire in 4 distinct historical patinas."""
    c = ArtCanvas(64, 64, COP_MID)
    for y in range(64):
        for x in range(64):
            sx = x % 8
            n = h2(x, y, 103)
            if era == "1926":
                # Glowing fresh orange-brown copper (not yet green!)
                hi, mid, low, shd = (232, 152, 102, 255), (194, 114, 68, 255), (146, 78, 44, 255), (96, 48, 28, 255)
                col = shd if sx == 0 else (hi if sx == 1 else (hi if n > 224 else (low if n < 42 else mid)))
            elif era == "1941":
                # Streaky brown-green transitional patina!
                is_green = ((x // 4 + h2(x // 2, 1, 11)) % 3 == 0) or (y % 20 < 8 and h2(x, y // 4, 19) > 120)
                if sx == 0:
                    col = (68, 56, 44, 255)
                elif sx == 1:
                    col = (158, 168, 132, 255) if is_green else (196, 138, 92, 255)
                elif is_green:
                    col = (104, 146, 114, 255) if n > 90 else (78, 116, 88, 255)
                else:
                    col = (156, 106, 68, 255) if n > 90 else (124, 82, 52, 255)
            elif era == "1976":
                # Deep green with heavy black vertical weathering streaks!
                black_streak = (h2(x // 2, 3, 77) > 170) and (y < 48 or (x + y) % 3 == 0)
                if sx == 0 or black_streak:
                    col = (34, 48, 44, 255) if sx != 0 else (24, 34, 32, 255)
                elif sx == 1:
                    col = (102, 152, 130, 255)
                else:
                    col = (66, 114, 96, 255) if n > 110 else (50, 92, 76, 255)
            else:
                # 2000+ Restored bright clean copper-green with lighter highlights, zero black streaks!
                if sx == 0:
                    col = COP_SHD
                elif sx == 1:
                    col = (176, 232, 210, 255)
                elif sx == 7:
                    col = COP_LOW
                else:
                    col = COP_HI if (n > 195 or (y % 16 < 3 and sx in (2, 3))) else (COP_LOW if n < 30 else COP_MID)
            c.put(x, y, col)
    c.save_2x(TEX_DIR / filename)


def make_bronze_gilded():
    """64x64 art px Gilded Bronze with strong light-gold top edge, stepped metallic sheen, and rich dark-gold underside."""
    c = ArtCanvas(64, 64, BRZ_MID)
    for y in range(64):
        for x in range(64):
            ly = y % 16
            lx = x % 16
            if ly == 0:
                col = BRZ_SPEC  # 1-pixel bright gold highlight along upper edge!
            elif ly in (1, 2):
                col = BRZ_HI
            elif ly == 15:
                col = BRZ_SHD
            elif ly in (13, 14):
                col = BRZ_LOW
            elif lx == 0:
                col = BRZ_LOW
            elif lx == 1:
                col = BRZ_HI
            else:
                n = h2(x, y, 131)
                col = BRZ_HI if n > 205 else (BRZ_LOW if n < 48 else BRZ_MID)
            c.put(x, y, col)
    c.save_2x(TEX_DIR / "bronze_gilded.png")


def make_water_pool():
    """64x64 art px crisp, clean, lily-pad-free deep reflecting pool water."""
    c = ArtCanvas(64, 64, (44, 98, 148, 255))
    W_SPEC = (174, 224, 248, 255)
    W_HI = (84, 152, 202, 255)
    W_MID = (48, 106, 158, 255)
    W_LOW = (32, 74, 118, 255)
    for y in range(64):
        for x in range(64):
            rip = (x + (y // 4) * 9) % 16
            if y % 8 == 1 and rip in (2, 3, 4):
                col = W_SPEC
            elif y % 4 == 0 and rip < 7:
                col = W_HI
            elif h2(x, y, 149) < 50:
                col = W_LOW
            else:
                col = W_MID
            c.put(x, y, col)
    c.save_2x(TEX_DIR / "water_pool.png")


def make_marble_plaza(filename, with_weeds=False):
    """64x64 art px two-tone checkerboard travertine/marble plaza paving (+ green joint weeds for 1976!)."""
    c = ArtCanvas(64, 64, ASH_MID)
    WEED_HI = (104, 144, 62, 255)
    WEED_LOW = (68, 102, 42, 255)
    for y in range(64):
        for x in range(64):
            tx, ty = x % 16, y % 16
            checker = ((x // 16) + (y // 16)) % 2
            if tx == 0 or ty == 0:
                if with_weeds and h2(x, y, 701) > 135:
                    col = WEED_HI if (x + y) % 2 == 0 else WEED_LOW
                else:
                    col = LIME_SHD
            elif with_weeds and (tx == 1 or ty == 1) and h2(x, y, 709) > 205:
                col = WEED_LOW
            elif tx in (2, 14) and ty in (2, 14):
                col = GRAN_A
            else:
                base = ASH_HI if checker == 0 else LIME_A
                n = h2(x, y, 173)
                col = LIME_B if n < 24 else (ASH_TOP if n > 236 else base)
            c.put(x, y, col)
    c.save_2x(TEX_DIR / filename)


def make_glass_cathedral(filename, taped_1941=False):
    """128x128 art px BLEND glass: win_cathedral (0,0,64,128) + win_lantern (64,0,64,128) + optional 1941 wartime taped X-crosses!"""
    c = ArtCanvas(128, 128, (36, 52, 74, 188))
    G_DARK = (44, 58, 82, 210)
    G_MID = (78, 108, 142, 188)
    G_AMBER = (224, 174, 88, 196)
    MULL = (42, 44, 58, 255)
    TAPE = (238, 232, 212, 248)

    for y in range(128):
        for x in range(64):
            if x in (0, 1, 62, 63) or y in (0, 1, 126, 127) or x in (21, 42) or y % 20 == 0:
                col = MULL
            elif (x + y) % 14 < 2:
                col = G_AMBER
            elif (x + y) % 16 == 6:
                col = G_MID
            else:
                col = G_DARK
            # 1941 wartime white blackout/shatter tape X-crosses on hall windows!
            if taped_1941 and col != MULL:
                lx, ly = x % 21, y % 20
                if abs(lx - ly) <= 1 or abs((20 - lx) - ly) <= 1:
                    col = TAPE
            c.put(x, y, col)

    # Region 2: win_lantern (64, 0, 64, 128) — translucent amber lantern lancet glass so interior gears & Fresnel lens show!
    for y in range(128):
        for x in range(64):
            px = 64 + x
            if x in (0, 1, 62, 63) or y in (0, 1, 126, 127) or x in (16, 32, 48) or y % 18 == 0:
                col = MULL
            elif abs(x - 32) < 6 and y % 9 < 3:
                col = (248, 214, 134, 168)
            elif (x + y) % 12 < 3:
                col = (224, 174, 88, 148)
            else:
                col = (188, 142, 72, 132)
            c.put(px, y, col)

    c.save_2x(TEX_DIR / filename)
    return {
        "win_cathedral": [0, 0, 64, 128],
        "win_lantern": [64, 0, 64, 128],
    }


def make_detail_atlas():
    """128x128 art px 4 STRICT 64x64 quadrants: lawn_turf, dark_iron, arch_coffer (deep recessed arch soffits!), timber_bench."""
    c = ArtCanvas(128, 128, OUTLINE)

    # Q0 (0, 0, 64, 64): lawn_turf (park lawns & tree canopies)
    for y in range(64):
        for x in range(64):
            n = h2(x, y, 211)
            stripe = (x // 8) % 2
            col = LAWN_LOW if n < 32 else (LAWN_HI if n > 220 else (LAWN_MID if stripe == 0 else (98, 154, 76, 255)))
            c.put(x, y, col)

    # Q1 (64, 0, 64, 64): dark_iron (lamp standards, railings, bike rack, trash bins, broken lamp heads)
    for y in range(64):
        for x in range(64):
            if x % 16 == 0 or y % 16 == 0:
                col = (30, 34, 44, 255)
            elif x % 16 == 1 or y % 16 == 1:
                col = (88, 96, 112, 255)
            else:
                col = (46, 52, 64, 255) if h2(x, y, 223) < 90 else (62, 68, 82, 255)
            c.put(64 + x, y, col)

    # Q2 (0, 64, 64, 64): arch_coffer — DEEP COFFERED ARCH SOFFITS (4x4 grid of 16x16 recessed stone coffers w/ dark inner outlines & bronze rosette!)
    for y in range(64):
        for x in range(64):
            cx, cy = x % 16, y % 16
            if cx in (0, 15) or cy in (0, 15):
                col = LIME_MORT
            elif cx in (1, 14) or cy in (1, 14):
                col = ASH_HI  # crisp rib bevel
            elif cx in (2, 13) or cy in (2, 13):
                col = LIME_A
            elif cx in (3, 12) or cy in (3, 12):
                col = OUTLINE  # dark recessed inner step outline!
            elif cx == 4 or cy == 4:
                col = (132, 118, 100, 255)  # deep shadow step
            elif cx == 11 or cy == 11:
                col = LIME_HI  # lit lower lip
            elif 6 <= cx <= 9 and 6 <= cy <= 9:
                col = BRZ_HI if (cx in (7, 8) and cy in (7, 8)) else BRZ_LOW  # central gilded bronze coffer rosette!
            else:
                col = LIME_SHD
            c.put(x, 64 + y, col)

    # Q3 (64, 64, 64, 64): timber_bench (plaza benches, 1926 dedication stand, 1976 scaffold planks & sawhorse)
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
        "arch_coffer": [0, 64, 64, 64],
        "timber_bench": [64, 64, 64, 64],
    }


def make_inscriptions():
    """
    512x512 art px (1024x1024 PNG) rich architectural relief & inscription atlas.
    Every region strictly non-overlapping with crisp pixel-art reliefs and incised lettering!
    """
    c = ArtCanvas(512, 512, ASH_MID)

    def paint_ashlar_bg(x0, y0, w, h):
        for dy in range(h):
            for dx in range(w):
                px, py = x0 + dx, y0 + dy
                if dy == 0:
                    col = ASH_TOP
                elif dy == h - 1:
                    col = ASH_SHD
                elif dy == 1:
                    col = ASH_HI
                else:
                    n = h2(px, py, 307)
                    col = ASH_HI if n > 224 else (ASH_LOW if n < 24 else ASH_MID)
                c.put(px, py, col)

    def paint_granite_bg(x0, y0, w, h):
        for dy in range(h):
            for dx in range(w):
                px, py = x0 + dx, y0 + dy
                if dy == 0:
                    col = GRAN_HI
                elif dy == h - 1:
                    col = GRAN_JOINT
                else:
                    n = h2(px, py, 313)
                    col = GRAN_HI if n > 232 else (GRAN_SHD if n < 30 else GRAN_A)
                c.put(px, py, col)

    # 1. frieze_main (4, 4, 236, 32): Incised dark inside with pale lower edge on smooth ashlar!
    paint_ashlar_bg(4, 4, 236, 32)
    t1 = "THE GREAT MERIDIAN"
    w1, _ = measure_text(t1, "5x7", scale=2, spacing=1)
    assert w1 <= 224
    c.draw_text(4 + (236 - w1) // 2, 7, t1, (56, 48, 42, 255), "5x7", scale=2, spacing=1, shadow=ASH_TOP)
    t2 = "DEDICATED MCMXXVI"
    w2, _ = measure_text(t2, "5x7", scale=1, spacing=1)
    assert w2 <= 216
    c.draw_text(4 + (236 - w2) // 2, 24, t2, (70, 62, 54, 255), "5x7", scale=1, spacing=1, shadow=ASH_TOP)

    # 2. exedra_text (4, 370, 240, 16): "ERECTED BY THE PEOPLE OF THE STATE MCMXXVI" on dark grey-pink granite!
    paint_granite_bg(4, 370, 240, 16)
    c.box_outline(4, 370, 240, 16, GRAN_JOINT)
    ex_txt = "ERECTED BY THE PEOPLE OF THE STATE MCMXXVI"
    ex_w, _ = measure_text(ex_txt, "3x5", scale=1, spacing=1)
    assert ex_w <= 228, f"exedra text too wide: {ex_w}"
    c.draw_text(4 + (240 - ex_w) // 2, 375, ex_txt, (242, 230, 212, 255), "3x5", scale=1, spacing=1, shadow=GRAN_JOINT)

    # 3. chevron_band (248, 24, 64, 14): 1 m tall carved Art Deco chevron / zigzag frieze for pier setbacks & crown!
    paint_ashlar_bg(248, 24, 64, 14)
    c.hline(248, 311, 24, ASH_TOP)
    c.hline(248, 311, 37, OUTLINE)
    for dx in range(64):
        p = dx % 12
        peak = abs(p - 6)  # 0..6
        for dy in range(1, 13):
            d_z = abs((dy - 3) - peak)
            if d_z == 0:
                c.put(248 + dx, 24 + dy, OUTLINE)
            elif d_z == 1:
                c.put(248 + dx, 24 + dy, ASH_TOP if (dy - 3) < peak else ASH_SHD)
            elif d_z == 2:
                c.put(248 + dx, 24 + dy, LIME_A)
            elif abs((dy - 7) - peak) == 0:
                c.put(248 + dx, 24 + dy, BRZ_LOW)

    # 4. voussoir_stone (320, 24, 64, 14): Alternating light & dark carved arch voussoir ring!
    for dx in range(64):
        blk = (dx // 8) % 2
        lx = dx % 8
        for dy in range(14):
            if lx == 0 or dy in (0, 13):
                col = OUTLINE
            elif dy == 1:
                col = ASH_TOP if blk == 0 else LIME_HI
            elif dy == 12:
                col = LIME_SHD if blk == 0 else GRAN_SHD
            else:
                col = ASH_HI if blk == 0 else LIME_SHD
            c.put(320 + dx, 24 + dy, col)

    # 5. keystone_star (392, 4, 24, 28): Projecting carved arch keystone with 8-point compass star!
    paint_ashlar_bg(392, 4, 24, 28)
    c.box_outline(392, 4, 24, 28, OUTLINE)
    c.box_outline(394, 6, 20, 24, ASH_TOP)
    kcx, kcy = 403.5, 17.5
    for dy in range(28):
        for dx in range(24):
            r = math.hypot(dx - 11.5, dy - 13.5)
            ang = math.atan2(dy - 13.5, dx - 11.5)
            sr = 2.6 + 6.0 * (abs(math.cos(4.0 * ang)) ** 2.2)
            if r < 1.8:
                c.put(392 + dx, 4 + dy, BRZ_SPEC)
            elif r < sr:
                c.put(392 + dx, 4 + dy, BRZ_HI if math.sin(4.0 * ang) >= 0 else BRZ_LOW)
            elif abs(r - 7.8) < 0.7:
                c.put(392 + dx, 4 + dy, OUTLINE)

    # 6. cornerstone_1926 (424, 4, 24, 20): NE arch corner eye-height stone ("1926" + small carved compass)!
    paint_granite_bg(424, 4, 24, 20)
    c.box_outline(424, 4, 24, 20, GRAN_JOINT)
    c.box_outline(425, 5, 22, 18, GRAN_HI)
    c.draw_text(428, 7, "1926", ASH_TOP, "3x5", scale=1, spacing=1, shadow=DEEP_INK)
    # Small carved compass star beneath "1926"
    c.hline(432, 440, 17, BRZ_HI)
    c.vline(436, 14, 20, BRZ_HI)
    c.put(436, 17, BRZ_SPEC)

    # 7. plaque_dedication (456, 4, 24, 18) & plaque_war (484, 4, 24, 18)
    for rx, txt in ((456, "1926"), (484, "41-45")):
        c.rect(rx, 4, 24, 18, BRZ_SHD)
        c.box_outline(rx, 4, 24, 18, DEEP_INK)
        c.box_outline(rx + 1, 5, 22, 16, BRZ_HI)
        tw, _ = measure_text(txt, "3x5", scale=1, spacing=1)
        c.draw_text(rx + (24 - tw) // 2, 7, txt, BRZ_SPEC, "3x5", scale=1, spacing=1)
        for ry in (14, 17, 19):
            c.hline(rx + 4, rx + 19, ry, BRZ_HI)

    # 8. frieze_east (4, 40, 180, 24) & frieze_west (4, 68, 180, 24)
    for ry, txt in ((40, "MERIDIAN EAST"), (68, "MERIDIAN WEST")):
        paint_ashlar_bg(4, ry, 180, 24)
        tw, th = measure_text(txt, "5x7", scale=2, spacing=1)
        assert tw <= 168
        c.draw_text(4 + (180 - tw) // 2, ry + (24 - th) // 2, txt, (58, 50, 44, 255), "5x7", scale=2, spacing=1, shadow=ASH_TOP)

    # 9. civic_flag (192, 40, 44, 26): Made-up Royal Blue field + Golden 8-point Compass Star!
    bx, by, bw, bh = 192, 40, 44, 26
    for dy in range(bh):
        for dx in range(bw):
            wave = int(1.0 * math.sin(dx * 0.28))
            rx, ry = dx - 21.5, dy - 12.5
            r = math.hypot(rx, ry)
            ang = math.atan2(ry, rx)
            sr = 3.2 + 6.4 * (abs(math.cos(4.0 * ang)) ** 2.2)
            if dx in (0, bw - 1) or dy in (0, bh - 1):
                col = DEEP_INK
            elif dx in (2, bw - 3) or dy in (2, bh - 3):
                col = BRZ_HI
            elif r < 2.2:
                col = BRZ_SPEC
            elif r < sr:
                col = BRZ_SPEC if math.sin(4.0 * ang) >= 0 else BRZ_HI
            elif abs(r - 9.8) < 0.85:
                col = BRZ_MID
            else:
                col = (46, 74, 134, 255) if ((dx + wave) % 10 < 3) else ((22, 38, 78, 255) if (dx + wave) % 10 == 8 else (30, 52, 102, 255))
            c.put(bx + dx, by + dy, col)

    # 10. bunting_swag (192, 70, 56, 22) & bunting_pole (256, 40, 24, 28): 1926 festive red/white/blue fabric!
    for dy in range(22):
        for dx in range(56):
            u_arc = ((dx % 28) - 13.5) / 13.5
            max_y = int(12 + 8.5 * math.sqrt(max(0.0, 1.0 - u_arc * u_arc)))
            if dy > max_y:
                col = ASH_MID
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
            c.put(192 + dx, 70 + dy, col)
    for dy in range(28):
        for dx in range(24):
            stripe = ((dx + dy) // 5) % 3
            pleat = (dx % 4 == 0)
            if dy in (0, 27):
                col = BRZ_HI
            elif stripe == 0:
                col = (182, 46, 52, 255) if not pleat else (138, 34, 40, 255)
            elif stripe == 1:
                col = (242, 236, 222, 255) if not pleat else (204, 196, 180, 255)
            else:
                col = (34, 58, 118, 255) if not pleat else (24, 42, 86, 255)
            c.put(256 + dx, 40 + dy, col)

    # 11. visitor_sign (288, 40, 96, 18)
    paint_ashlar_bg(288, 40, 96, 18)
    c.box_outline(288, 40, 96, 18, OUTLINE)
    vs = "VISITOR CENTER"
    tw_v, _ = measure_text(vs, "5x7", scale=1, spacing=1)
    c.draw_text(288 + (96 - tw_v) // 2, 45, vs, (56, 50, 44, 255), "5x7", scale=1, spacing=1, shadow=ASH_TOP)

    # 12. closed_repairs_sign (392, 40, 48, 20): 1976 hand-painted "CLOSED / FOR REPAIRS" on weathered wood sawhorse!
    c.rect(392, 40, 48, 20, (226, 214, 186, 255))
    c.box_outline(392, 40, 48, 20, (164, 42, 38, 255))
    c.box_outline(393, 41, 46, 18, (88, 62, 42, 255))
    for li, ltxt in enumerate(("CLOSED", "FOR REPAIRS")):
        tw_c, _ = measure_text(ltxt, "3x5", scale=1, spacing=1)
        assert tw_c <= 44
        c.draw_text(392 + (48 - tw_c) // 2, 44 + li * 7, ltxt, (168, 36, 32, 255), "3x5", scale=1, spacing=1)

    # 13. interp_panel (448, 40, 32, 20): 2000+ 1 m museum interpretive plaque along reflecting pool!
    c.rect(448, 40, 32, 20, (34, 44, 62, 255))
    c.box_outline(448, 40, 32, 20, BRZ_HI)
    c.hline(451, 476, 44, BRZ_SPEC)
    c.rect(451, 47, 10, 9, COP_HI)
    for ry in (47, 50, 53, 56):
        c.hline(463, 476, ry, ASH_HI)

    # 14. Four 58 m Sky Deck Engraved Brass Direction Plates (56x14 each)
    for rx, label in ((256, "TO CAPITOL"), (316, "TO RIVER"), (376, "TO MOUNTAINS"), (436, "TO THE SEA")):
        c.rect(rx, 72, 56, 14, BRZ_MID)
        c.box_outline(rx, 72, 56, 14, DEEP_INK)
        c.box_outline(rx + 1, 73, 54, 12, BRZ_SPEC)
        tw_l, _ = measure_text(label, "3x5", scale=1, spacing=1)
        assert tw_l <= 52
        c.draw_text(rx + (56 - tw_l) // 2, 76, label, DEEP_INK, "3x5", scale=1, spacing=1, shadow=BRZ_SPEC)

    # 15. dedication_wall (4, 98, 112, 64): West Memorial Hall carved wall!
    # Top heading in 0.4 m (5 art px) incised letters + carved stone wreaths at both ends + 26 rows of carved name blocks!
    paint_ashlar_bg(4, 98, 112, 64)
    c.box_outline(4, 98, 112, 64, OUTLINE)
    c.box_outline(6, 100, 108, 60, ASH_TOP)
    dl1 = "TO THOSE WHO BUILT, FARMED,"
    dl2 = "TAUGHT AND DEFENDED"
    dw1, _ = measure_text(dl1, "3x5", scale=1, spacing=1)
    dw2, _ = measure_text(dl2, "3x5", scale=1, spacing=1)
    assert dw1 <= 108 and dw2 <= 80, f"dedication overflow: {dw1}, {dw2}"
    c.draw_text(4 + (112 - dw1) // 2, 102, dl1, (54, 46, 40, 255), "3x5", scale=1, spacing=1, shadow=ASH_TOP)
    c.draw_text(4 + (112 - dw2) // 2, 110, dl2, (54, 46, 40, 255), "3x5", scale=1, spacing=1, shadow=ASH_TOP)
    # Carved stone laurel wreaths at BOTH left and right ends flanking line 2!
    for wcx in (13, 106):
        for dy in range(-5, 6):
            for dx in range(-5, 6):
                r = math.hypot(dx, dy)
                if 2.6 <= r <= 4.6:
                    c.put(wcx + dx, 113 + dy, BRZ_HI if (dx + dy) % 2 == 0 else BRZ_LOW)
    c.hline(10, 109, 119, BRZ_MID)
    # 20 dense carved rows of small illegible pixel name blocks (creating 30+ visual entries across 6 columns!)
    for row_i in range(19):
        ry = 122 + row_i * 2
        for col_i in range(6):
            rx = 10 + col_i * 16
            nw = 9 + (h2(col_i, row_i, 419) % 5)
            c.hline(rx, rx + nw, ry, LIME_SHD)
            c.hline(rx, rx + nw, ry + 1, ASH_TOP)

    # 16. state_map (124, 98, 108, 64): East Hall pixel-art Wall Map of the Made-Up State w/ Gilded Meridian Line!
    mx0, my0, mw, mh = 124, 98, 108, 64
    c.rect(mx0, my0, mw, mh, (54, 96, 138, 255))  # Eastern sea
    c.box_outline(mx0, my0, mw, mh, BRZ_LOW)
    c.box_outline(mx0 + 1, my0 + 1, mw - 2, mh - 2, BRZ_SPEC)
    for dy in range(3, mh - 3):
        coast_x = int(78 + 10 * math.sin(dy * 0.14) - 5 * math.cos(dy * 0.25))
        mtn_x = int(26 + 7 * math.sin(dy * 0.18 + 1.0))
        riv_x = int(42 + 12 * math.sin(dy * 0.11))
        for dx in range(3, mw - 3):
            if dx > coast_x:
                col = (48, 94, 138, 255) if (dx + dy) % 6 != 0 else (72, 122, 166, 255)
            elif abs(dx - coast_x) <= 1:
                col = (218, 202, 162, 255)  # sandy coastline
            elif abs(dx - riv_x) <= 1 and dy > 10:
                col = (68, 138, 192, 255)  # winding river
            elif dx < mtn_x:
                col = (128, 120, 114, 255) if ((dx + dy) % 5 < 2) else (154, 146, 138, 255)  # western mountains
                if (dx % 7 == 3) and (dy % 8 == 3):
                    col = ASH_TOP  # snow peaks
            else:
                col = (146, 174, 112, 255) if h2(dx, dy, 521) > 95 else (126, 156, 94, 255)  # fertile state plains
            c.put(mx0 + dx, my0 + dy, col)
    # Bold Gilded MERIDIAN LINE cutting dead straight north-south through the state map!
    c.vline(mx0 + mw // 2, my0 + 3, my0 + mh - 4, BRZ_SPEC)
    c.vline(mx0 + mw // 2 - 1, my0 + 3, my0 + mh - 4, BRZ_LOW)
    # Golden Capitol Star right on the Meridian Line!
    c.rect(mx0 + mw // 2 - 2, my0 + mh // 2 - 2, 5, 5, BRZ_SPEC)
    c.put(mx0 + mw // 2, my0 + mh // 2, (186, 46, 42, 255))
    c.draw_text(mx0 + 6, my0 + 5, "STATE MERIDIAN", DEEP_INK, "3x5", scale=1, spacing=1, shadow=ASH_TOP)

    # 17. bronze_door_leaf (240, 98, 28, 64): Exact 2.2 m x 5.0 m Embossed Bronze Door Leaf w/ 6 Pixel-Art Relief Panels!
    # 2 columns x 3 rows of 10x17 relief plaques: (1) Train, (2) Plough, (3) Ship, (4) Factory, (5) School, (6) Bridge!
    dx0, dy0, dw, dh = 240, 98, 28, 64
    c.rect(dx0, dy0, dw, dh, BRZ_LOW)
    c.box_outline(dx0, dy0, dw, dh, DEEP_INK)
    c.box_outline(dx0 + 1, dy0 + 1, dw - 2, dh - 2, BRZ_HI)
    panel_boxes = [
        (dx0 + 3, dy0 + 4, "train"),
        (dx0 + 15, dy0 + 4, "plough"),
        (dx0 + 3, dy0 + 23, "ship"),
        (dx0 + 15, dy0 + 23, "factory"),
        (dx0 + 3, dy0 + 42, "school"),
        (dx0 + 15, dy0 + 42, "bridge"),
    ]
    for px0, py0, icon in panel_boxes:
        c.rect(px0, py0, 10, 17, BRZ_SHD)
        c.box_outline(px0, py0, 10, 17, BRZ_SPEC)
        c.rect(px0 + 1, py0 + 1, 8, 15, BRZ_LOW)
        if icon == "train":
            # Locomotive boiler, cab, smokestack & wheels
            c.rect(px0 + 2, py0 + 7, 6, 4, BRZ_HI)
            c.rect(px0 + 2, py0 + 5, 2, 3, BRZ_SPEC)  # cab
            c.vline(px0 + 7, py0 + 4, py0 + 7, BRZ_SPEC)  # stack
            c.put(px0 + 6, py0 + 3, ASH_TOP)  # steam puff
            c.hline(px0 + 2, px0 + 7, py0 + 12, BRZ_SPEC)  # wheels/rail
        elif icon == "plough":
            # Furrowed field & curving bronze ploughshare + handles
            c.hline(px0 + 2, px0 + 7, py0 + 12, BRZ_MID)
            c.hline(px0 + 2, px0 + 7, py0 + 14, BRZ_MID)
            c.rect(px0 + 4, py0 + 7, 4, 4, BRZ_SPEC)  # blade
            c.vline(px0 + 2, py0 + 5, py0 + 10, BRZ_HI)  # handle
            c.hline(px0 + 2, px0 + 6, py0 + 7, BRZ_HI)
        elif icon == "ship":
            # Hull, deckhouse, tall masts & billowing sails over waves
            c.rect(px0 + 2, py0 + 10, 6, 3, BRZ_HI)
            c.vline(px0 + 5, py0 + 3, py0 + 10, BRZ_SPEC)
            c.rect(px0 + 3, py0 + 4, 4, 5, BRZ_SPEC)
            c.hline(px0 + 2, px0 + 7, py0 + 14, BRZ_MID)
        elif icon == "factory":
            # Sawtooth mill roof + tall tapered brick/bronze smokestack
            c.rect(px0 + 2, py0 + 8, 6, 6, BRZ_HI)
            c.vline(px0 + 7, py0 + 3, py0 + 8, BRZ_SPEC)
            c.put(px0 + 6, py0 + 2, ASH_TOP)
            c.put(px0 + 3, py0 + 10, BRZ_SHD)
            c.put(px0 + 5, py0 + 10, BRZ_SHD)
        elif icon == "school":
            # Classical pediment schoolhouse with central bell cupola
            c.rect(px0 + 2, py0 + 8, 6, 6, BRZ_HI)
            c.hline(px0 + 2, px0 + 7, py0 + 7, BRZ_SPEC)
            c.rect(px0 + 4, py0 + 4, 2, 3, BRZ_SPEC)  # bell tower
            c.rect(px0 + 4, py0 + 11, 2, 3, BRZ_SHD)  # doorway
        elif icon == "bridge":
            # Twin towers + sweeping arch suspension span
            c.vline(px0 + 3, py0 + 4, py0 + 13, BRZ_SPEC)
            c.vline(px0 + 6, py0 + 4, py0 + 13, BRZ_SPEC)
            c.hline(px0 + 1, px0 + 8, py0 + 9, BRZ_HI)
            c.hline(px0 + 3, px0 + 6, py0 + 6, BRZ_SPEC)

    # 18. zodiac_band (276, 98, 128, 16): 12 distinct crisp gilded zodiac symbols on rich bronze band!
    c.rect(276, 98, 128, 16, BRZ_MID)
    c.hline(276, 403, 98, BRZ_SPEC)
    c.hline(276, 403, 99, BRZ_HI)
    c.hline(276, 403, 112, BRZ_LOW)
    c.hline(276, 403, 113, BRZ_SHD)
    for gi in range(12):
        gx = 280 + gi * 10
        c.box_outline(gx, 101, 8, 10, BRZ_SHD)
        if gi % 4 == 0:
            c.hline(gx + 2, gx + 5, 106, BRZ_SPEC)
            c.vline(gx + 3, 103, 109, BRZ_SPEC)
        elif gi % 4 == 1:
            c.box_outline(gx + 2, 103, 4, 6, BRZ_SPEC)
        elif gi % 4 == 2:
            c.put(gx + 3, 103, BRZ_SPEC)
            c.hline(gx + 2, gx + 5, 106, BRZ_SPEC)
            c.put(gx + 3, 109, BRZ_SPEC)
        else:
            c.vline(gx + 2, 103, 109, BRZ_SPEC)
            c.vline(gx + 5, 103, 109, BRZ_SPEC)
            c.hline(gx + 2, gx + 5, 106, BRZ_HI)

    # 19. earth_globe (276, 120, 64, 32): VERDIGRIS-GREEN OCEANS with PALE BRONZE MADE-UP CONTINENTS!
    for dy in range(32):
        for dx in range(64):
            lon_grid = (dx % 8 == 0)
            lat_grid = (dy % 8 == 0)
            land = (math.sin(dx * 0.16 + 0.4) * math.cos(dy * 0.15 - 0.3) + 0.42 * math.sin(dx * 0.30 - dy * 0.22)) > 0.14
            if land:
                col = BRZ_SPEC if ((dx + dy) % 5 == 0) else BRZ_HI
            elif lon_grid or lat_grid:
                col = COP_HI
            else:
                col = COP_SHD if h2(dx, dy, 613) < 95 else COP_LOW
            c.put(276 + dx, 120 + dy, col)

    # 20. Guardian Ledge Name Bands (0.6 m = 7 art px letters on dark grey-pink granite!)
    for ry, gtxt in ((98, "DEFENSE"), (112, "LEARNING"), (126, "INDUSTRY"), (140, "HARVEST")):
        paint_granite_bg(412, ry, 56, 11)
        c.box_outline(412, ry, 56, 11, GRAN_JOINT)
        tw_g, _ = measure_text(gtxt, "5x7", scale=1, spacing=1)
        assert tw_g <= 50
        c.draw_text(412 + (56 - tw_g) // 2, ry + 2, gtxt, ASH_TOP, "5x7", scale=1, spacing=1, shadow=DEEP_INK)

    # 21. compass_rose_14m (4, 174, 180, 180): GIANT 14 m INLAID BRONZE + TWO-TONE MARBLE COMPASS ROSE w/ N/E/S/W!
    cx, cy = 89.5, 89.5
    for dy in range(180):
        for dx in range(180):
            rx, ry = dx - cx, dy - cy
            r = math.hypot(rx, ry)
            ang = math.atan2(ry, rx)
            star_main = 18.0 + 54.0 * (abs(math.cos(2.0 * ang)) ** 3.0)
            star_sub = 14.0 + 36.0 * (abs(math.cos(2.0 * ang + math.pi * 0.5)) ** 3.0)
            if r > 87.5:
                col = ASH_HI if ((dx // 8 + dy // 8) % 2 == 0) else LIME_A
            elif r > 84.0:
                col = BRZ_MID
            elif r > 74.0:
                # Outer ring dark slate-marble band with N / E / S / W inlaid letters!
                col = GRAN_SHD if (int((ang + math.pi) * 18 / math.pi) % 2 == 0) else GRAN_A
            elif r > 71.0:
                col = BRZ_SPEC
            elif r < 11.0:
                col = BRZ_SPEC if r < 5.0 else BRZ_HI
            elif r < max(star_main, star_sub):
                col = BRZ_SPEC if (math.sin(4.0 * ang) >= 0) else BRZ_LOW
            elif abs(r - 48.0) < 2.2:
                col = BRZ_MID
            else:
                col = ASH_TOP if (((dx // 10) + (dy // 10)) % 2 == 0) else GRAN_HI
            c.put(4 + dx, 174 + dy, col)
    # Crisp big N / E / S / W inlaid bronze letters in the outer ring!
    c.draw_text(4 + 85, 174 + 7, "N", BRZ_SPEC, "5x7", scale=2, spacing=1, shadow=DEEP_INK)
    c.draw_text(4 + 85, 174 + 160, "S", BRZ_SPEC, "5x7", scale=2, spacing=1, shadow=DEEP_INK)
    c.draw_text(4 + 162, 174 + 83, "E", BRZ_SPEC, "5x7", scale=2, spacing=1, shadow=DEEP_INK)
    c.draw_text(4 + 8, 174 + 83, "W", BRZ_SPEC, "5x7", scale=2, spacing=1, shadow=DEEP_INK)

    # 22. guardian_stone (192, 174, 64, 64) & guardian_stone_1976 (264, 174, 64, 64):
    #     Smooth warm sculpted Art Deco ivory-limestone (NO column stripes!) with chiseled bevel edges,
    #     subtle 3-tone carved planes, weather-bleached top highlight & 1976 dark vertical runoff streaks!
    SCULPT_TOP = (242, 236, 222, 255)
    SCULPT_HI  = (230, 222, 204, 255)
    SCULPT_MID = (212, 202, 182, 255)
    SCULPT_LOW = (188, 176, 156, 255)
    SCULPT_RIM = (124, 114, 98, 255)
    for ox, streaked in ((192, False), (264, True)):
        for dy in range(64):
            for dx in range(64):
                if dy in (0, 1):
                    col = SCULPT_TOP  # weather-bleached bright stone top
                elif dy == 63 or dx == 0 or dx == 63:
                    col = SCULPT_RIM  # subtle carved seam outline
                elif dy < 12:
                    col = SCULPT_HI if (h2(dx // 2, dy // 2, 701) > 45) else SCULPT_MID
                elif dy > 52:
                    col = SCULPT_LOW if (h2(dx // 2, dy // 2, 709) > 50) else SCULPT_MID
                else:
                    n = h2(dx // 3, dy // 3, 719)
                    col = SCULPT_HI if n > 195 else (SCULPT_LOW if n < 45 else SCULPT_MID)
                if streaked and h2(dx // 2, 13, 811) > 152 and dy > 5:
                    col = (max(54, col[0] - 54), max(48, col[1] - 50), max(42, col[2] - 46), 255)
                c.put(ox + dx, 174 + dy, col)

    # 22b. guardian_shadow (192, 250, 48, 48): natural carved limestone shadow tone for stern eye sockets,
    #      under-chin recesses & sculpted Art Deco stone hair (noble chiselled stone, never flat charcoal!)
    for dy in range(48):
        for dx in range(48):
            if dy == 0 or dx == 0 or dy == 47 or dx == 47:
                col = (112, 102, 88, 255)
            else:
                n = h2(dx // 2, dy // 2, 733)
                col = (164, 154, 138, 255) if n > 180 else ((136, 126, 110, 255) if n < 70 else (150, 140, 124, 255))
            c.put(192 + dx, 250 + dy, col)

    # 22c. guardian_leather (248, 250, 48, 48): rich dark saddle-brown forged work leather w/ bronze rivets
    #      for Industry's waist apron & suspenders (warm sculptural contrast, not a flat black slab!)
    for dy in range(48):
        for dx in range(48):
            if dy in (0, 1, 46, 47) or dx in (0, 1, 46, 47):
                col = (62, 42, 28, 255)
            elif (dx in (4, 43) and dy % 12 in (4, 5)):
                col = BRZ_HI  # brass apron rivets
            else:
                n = h2(dx // 2, dy // 2, 743)
                col = (112, 76, 48, 255) if n > 170 else ((84, 56, 36, 255) if n < 75 else (98, 66, 42, 255))
            c.put(248 + dx, 250 + dy, col)

    # 22d. guardian_drape (304, 250, 48, 48) & guardian_drape_1976 (360, 250, 48, 48):
    #      Broad, soft 3-tone sculptural drapery folds (12-px wide flutes) for robes & cloaks only!
    for ox, streaked in ((304, False), (360, True)):
        for dy in range(48):
            for dx in range(48):
                flute = dx % 12
                if dy in (0, 1):
                    col = SCULPT_TOP
                elif flute == 0:
                    col = SCULPT_RIM
                elif flute in (1, 2, 3):
                    col = SCULPT_HI
                elif flute in (9, 10, 11):
                    col = SCULPT_LOW
                else:
                    col = SCULPT_MID
                if streaked and h2(dx // 2, 17, 823) > 154 and dy > 4:
                    col = (max(52, col[0] - 52), max(46, col[1] - 48), max(40, col[2] - 44), 255)
                c.put(ox + dx, 250 + dy, col)

    # 23. scaffold_tarp (336, 174, 64, 64): 1976 weathered blue-grey canvas scaffolding tarp!
    for dy in range(64):
        for dx in range(64):
            fold = int(2.0 * math.sin(dx * 0.25 + dy * 0.08))
            if dx in (0, 63) or dy in (0, 63):
                col = (38, 48, 62, 255)
            elif (dx % 16 == 2 and dy in (2, 61)):
                col = BRZ_HI  # tarp grommets
            else:
                col = (98, 122, 146, 255) if fold > 0 else ((66, 86, 108, 255) if fold < 0 else (82, 104, 128, 255))
            c.put(336 + dx, 174 + dy, col)

    # 24. sandbag_wall (408, 174, 48, 28): 1941 stacked wartime olive-tan burlap sandbags!
    for dy in range(28):
        row = dy // 7
        ry = dy % 7
        ox = 6 if (row % 2 == 1) else 0
        for dx in range(48):
            rx = (dx + ox) % 12
            if ry == 0 or rx == 0:
                col = (72, 66, 48, 255)
            elif ry == 1 and 2 <= rx <= 9:
                col = (182, 168, 128, 255)
            elif ry == 6:
                col = (108, 98, 72, 255)
            else:
                col = (154, 142, 106, 255) if h2(dx, dy, 853) > 85 else (136, 124, 90, 255)
            c.put(408 + dx, 174 + dy, col)

    # 25. chain_fence (408, 210, 48, 32): 1976 steel chain-link mesh around scaffolded SE corner!
    for dy in range(32):
        for dx in range(48):
            if dy in (0, 1, 30, 31) or dx in (0, 47):
                col = (118, 126, 136, 255)
            elif ((dx + dy) % 6 == 0) or ((dx - dy) % 6 == 0):
                col = (154, 162, 174, 255)
            else:
                col = (44, 50, 60, 255)
            c.put(408 + dx, 210 + dy, col)

    c.save_2x(TEX_DIR / "inscriptions.png")
    regs = {
        "frieze_main": [4, 4, 236, 32],
        "exedra_text": [4, 370, 240, 16],
        "chevron_band": [248, 24, 64, 14],
        "voussoir_stone": [320, 24, 64, 14],
        "keystone_star": [392, 4, 24, 28],
        "cornerstone_1926": [424, 4, 24, 20],
        "plaque_dedication": [456, 4, 24, 18],
        "plaque_war": [484, 4, 24, 18],
        "frieze_east": [4, 40, 180, 24],
        "frieze_west": [4, 68, 180, 24],
        "civic_flag": [192, 40, 44, 26],
        "bunting_swag": [192, 70, 56, 22],
        "bunting_pole": [256, 40, 24, 28],
        "visitor_sign": [288, 40, 96, 18],
        "closed_repairs_sign": [392, 40, 48, 20],
        "interp_panel": [448, 40, 32, 20],
        "dir_capitol": [256, 72, 56, 14],
        "dir_river": [316, 72, 56, 14],
        "dir_mountains": [376, 72, 56, 14],
        "dir_sea": [436, 72, 56, 14],
        "dedication_wall": [4, 98, 112, 64],
        "state_map": [124, 98, 108, 64],
        "bronze_door_leaf": [240, 98, 28, 64],
        "zodiac_band": [276, 98, 128, 16],
        "earth_globe": [276, 120, 64, 32],
        "label_defense": [412, 98, 56, 11],
        "label_learning": [412, 112, 56, 11],
        "label_industry": [412, 126, 56, 11],
        "label_harvest": [412, 140, 56, 11],
        "compass_rose_14m": [4, 174, 180, 180],
        "guardian_stone": [192, 174, 64, 64],
        "guardian_stone_1976": [264, 174, 64, 64],
        "guardian_shadow": [192, 250, 48, 48],
        "guardian_leather": [248, 250, 48, 48],
        "guardian_drape": [304, 250, 48, 48],
        "guardian_drape_1976": [360, 250, 48, 48],
        "scaffold_tarp": [336, 174, 64, 64],
        "sandbag_wall": [408, 174, 48, 28],
        "chain_fence": [408, 210, 48, 32],
    }
    # Strict non-overlap assertion across all regions in inscriptions.png!
    items = list(regs.items())
    for i in range(len(items)):
        n1, (ax, ay, aw, ah) = items[i]
        assert 0 <= ax and ax + aw <= 512 and 0 <= ay and ay + ah <= 512, f"{n1} out of bounds"
        for j in range(i + 1, len(items)):
            n2, (bx, by, bw, bh) = items[j]
            if not (ax + aw <= bx or bx + bw <= ax or ay + ah <= by or by + bh <= ay):
                raise AssertionError(f"Region overlap in inscriptions.png: {n1} vs {n2}")
    return regs


def make_glow_monument():
    """64x64 art px base + pure-black (#000000) emissive mask (< 20 m2 total!)."""
    base = ArtCanvas(64, 64, (28, 30, 38, 255))
    mask = ArtCanvas(64, 64, (0, 0, 0, 255))

    # 1. flame_brazier (0, 0, 16, 16) — rich orange eternal flame & Learning torch flame!
    for y in range(16):
        for x in range(16):
            dx = (x - 7.5) / 5.8
            width_at_y = max(0.0, 1.0 - max(0.0, (13.0 - y) / 13.0) ** 1.35)
            d_fl = abs(dx) / max(0.18, width_at_y + 0.14)
            if 2 <= y <= 14 and d_fl < 0.92:
                col = (255, 248, 206, 255) if (y >= 7 and d_fl < 0.42) else ((255, 186, 68, 255) if (y >= 5 and d_fl < 0.70) else (242, 112, 32, 255))
                base.put(x, y, col)
                mask.put(x, y, col)
            else:
                base.put(x, y, (34, 36, 44, 255))

    # 2. lamp_globe (16, 0, 16, 16) — warm 3000K white globe
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - 7.5, y - 7.5)
            if r <= 5.6:
                col = (255, 246, 218, 255) if r < 3.2 else (246, 216, 144, 255)
                base.put(16 + x, y, col)
                mask.put(16 + x, y, col)
            else:
                base.put(16 + x, y, (44, 48, 58, 255))

    # 3. beacon_orb (32, 0, 16, 16) — bright white aviation beacon
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - 7.5, y - 7.5)
            if r <= 6.6:
                col = (255, 255, 246, 255) if r < 4.0 else (248, 238, 196, 255)
                base.put(32 + x, y, col)
                mask.put(32 + x, y, col)
            else:
                base.put(32 + x, y, (48, 42, 34, 255))

    # 4. fountain_jet (48, 0, 16, 16) — thin water-white rim glow
    for y in range(16):
        for x in range(16):
            if 5 <= x <= 10 and 1 <= y <= 14:
                col = (232, 248, 255, 255) if (x in (7, 8) or (x + y) % 3 == 0) else (142, 212, 242, 255)
                base.put(48 + x, y, col)
                mask.put(48 + x, y, col)
            else:
                base.put(48 + x, y, (34, 58, 86, 255))

    # 5. lantern_lancet_glow (0, 16, 16, 48) — warm amber crown lancet
    for y in range(48):
        for x in range(16):
            if x in (0, 1, 14, 15) or y in (0, 1, 46, 47) or y % 12 == 0:
                base.put(x, 16 + y, (42, 44, 58, 255))
            else:
                col = (255, 216, 112, 255) if 5 <= x <= 10 else (232, 166, 64, 255)
                base.put(x, 16 + y, col)
                mask.put(x, 16 + y, col)

    # 6. pier_slot_glow (16, 16, 16, 48) — warm amber pier slot
    for y in range(48):
        for x in range(16):
            if x in (0, 1, 2, 13, 14, 15) or y in (0, 1, 46, 47) or y % 16 == 0:
                base.put(16 + x, 16 + y, (42, 44, 58, 255))
            else:
                col = (248, 206, 114, 255) if 6 <= x <= 9 else (216, 156, 68, 255)
                base.put(16 + x, 16 + y, col)
                mask.put(16 + x, 16 + y, col)

    # 7. ecliptic_glow (32, 16, 32, 8) — razor-thin golden star line
    for y in range(8):
        for x in range(32):
            if y in (3, 4):
                col = (255, 238, 162, 255) if x % 4 != 0 else (246, 198, 92, 255)
                base.put(32 + x, 16 + y, col)
                mask.put(32 + x, 16 + y, col)
            else:
                base.put(32 + x, 16 + y, BRZ_LOW)

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
    # Clean obsolete Revision 1 texture filenames
    for old_name in ("stone_limestone_clean.png", "stone_limestone_mature.png", "stone_limestone_worn.png",
                     "copper_fresh.png", "copper_verdigris.png", "guardians_atlas.png"):
        p = TEX_DIR / old_name
        if p.exists():
            p.unlink()

    for era in ("1926", "1941", "1976", "2000"):
        make_limestone_era(f"stone_limestone_{era}.png", era)
        make_copper_era(f"copper_{era}.png", era)
    make_stone_granite()
    make_stone_ashlar()
    make_steel_truss()
    make_bronze_gilded()
    make_water_pool()
    make_marble_plaza("marble_plaza.png", with_weeds=False)
    make_marble_plaza("marble_plaza_1976.png", with_weeds=True)
    glass_regions = make_glass_cathedral("glass_cathedral.png", taped_1941=False)
    make_glass_cathedral("glass_cathedral_1941.png", taped_1941=True)
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
    print("Generated all Revision 2 Great Meridian Monument 2x2 art-pixel textures + 512x512 relief atlas.")


if __name__ == "__main__":
    main()
