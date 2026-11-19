#!/usr/bin/env python3
"""
Texture and swatch generator for Reel Time Video — a 4-bay small-town strip centre across
four states: 1984, 1996, 2011, and 2020.

Follows docs/CHATGPT_MODEL_GUIDE.md strictly:
- Extracts 6 shared LimeZu swatches directly from kotlin-street/tools/swatches/atlas/atlas.png
  (block_courses, ballast, roof_membrane_grey, asphalt_worn, pavement, plank_horizontal).
- Paints bespoke textures in the LimeZu palette on exact 2x2 texel blocks (0.0388 m/texel).
- Enforces strict text fit assertions (assert_fits) so NO lettering ever runs past its plate.
- Produces separate daytime sign textures and pure-black-background emissive glow masks so
  ONLY lit sign faces and tiny walkway soffit bulbs glow at night (never floors/walls/unlit signs).
"""
import json
import struct
import zlib
from pathlib import Path

from pixel_font import FONT_5X7, FONT_3X5, measure_text

R = Path(__file__).resolve().parent
REPO_ROOT = R.parent.parent
ATLAS_PNG = REPO_ROOT / "kotlin-street" / "tools" / "swatches" / "atlas" / "atlas.png"
ATLAS_JSON = REPO_ROOT / "kotlin-street" / "tools" / "swatches" / "atlas" / "atlas.json"

TEX_DIR = R / "textures"
TEX_DIR.mkdir(parents=True, exist_ok=True)


def read_png_rgba(path):
    data = Path(path).read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    pos = 8
    w = h = 0
    idat = bytearray()
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if ctype == b"IHDR":
            w, h, bd, ct, comp, filt, inter = struct.unpack(">IIBBBBB", chunk)
            assert bd == 8 and ct == 6
        elif ctype == b"IDAT":
            idat.extend(chunk)
    raw = zlib.decompress(bytes(idat))
    stride = w * 4
    rows = []
    idx = 0
    prev = bytearray(stride)
    for _ in range(h):
        f = raw[idx]
        idx += 1
        cur = bytearray(raw[idx:idx + stride])
        idx += stride
        if f == 1:
            for i in range(4, stride):
                cur[i] = (cur[i] + cur[i - 4]) & 0xFF
        elif f == 2:
            for i in range(stride):
                cur[i] = (cur[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                a = cur[i - 4] if i >= 4 else 0
                cur[i] = (cur[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = cur[i - 4] if i >= 4 else 0
                b = prev[i]
                c = prev[i - 4] if i >= 4 else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                cur[i] = (cur[i] + pr) & 0xFF
        prev = cur
        rows.append([tuple(cur[x * 4:(x + 1) * 4]) for x in range(w)])
    return w, h, rows


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


class ArtCanvas:
    """1x art-pixel canvas exporting at exact 2x NEAREST so every pixel is a 2x2 texel block."""

    def __init__(self, w_art, h_art, bg=(0, 0, 0, 255)):
        self.w = w_art
        self.h = h_art
        self.px = [[bg for _ in range(w_art)] for _ in range(h_art)]

    def put(self, x, y, col):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = col

    def rect(self, x, y, w, h, col):
        for yy in range(max(0, y), min(self.h, y + h)):
            row = self.px[yy]
            for xx in range(max(0, x), min(self.w, x + w)):
                row[xx] = col

    def border(self, x, y, w, h, col, t=1):
        self.rect(x, y, w, t, col)
        self.rect(x, y + h - t, w, t, col)
        self.rect(x, y, t, h, col)
        self.rect(x + w - t, y, t, h, col)

    def draw_text_in_box(self, bx, by, bw, bh, text, fg, font="5x7", spacing=1, shadow=None, pad_x=2, pad_y=1):
        """Draws text strictly centered inside (bx, by, bw, bh) and asserts it fits inside padding."""
        tw, th = measure_text(text, font=font, spacing=spacing, scale=1)
        assert tw <= bw - 2 * pad_x, (
            f"Text '{text}' ({tw}px) overflows box width {bw}px (max {bw - 2 * pad_x}px)"
        )
        assert th <= bh - 2 * pad_y, (
            f"Text '{text}' ({th}px) overflows box height {bh}px (max {bh - 2 * pad_y}px)"
        )
        glyphs = FONT_5X7 if font == "5x7" else FONT_3X5
        cw = 5 if font == "5x7" else 3
        x0 = bx + (bw - tw) // 2
        y0 = by + (bh - th) // 2
        for i, ch in enumerate(text.upper()):
            glyph = glyphs.get(ch, glyphs[" "])
            gx0 = x0 + i * (cw + spacing)
            for gy, row in enumerate(glyph):
                for gx, bit in enumerate(row):
                    if bit == "1":
                        if shadow is not None:
                            self.put(gx0 + gx + 1, y0 + gy + 1, shadow)
                        self.put(gx0 + gx, y0 + gy, fg)

    def save_2x(self, path):
        out = []
        for y in range(self.h):
            row = self.px[y]
            srow = []
            for x in range(self.w):
                c = row[x]
                srow.append(c)
                srow.append(c)
            out.append(srow)
            out.append(list(srow))
        write_png_rgba(path, self.w * 2, self.h * 2, out)


# ==============================================================================
# 1. EXTRACT 6 SHARED LIMEZU SWATCHES FROM ATLAS.PNG
# ==============================================================================
SHARED_SWATCHES = [
    ("block_courses", "Split-face concrete block side, rear, and lower storefront bulkhead walls"),
    ("ballast", "Tar-and-gravel flat roof deck"),
    ("roof_membrane_grey", "Roof parapet flashing, metal coping, and covered walkway soffit"),
    ("asphalt_worn", "Front 12-space parking lot and rear service lane asphalt"),
    ("pavement", "Covered front sidewalk, rear dumpster pad, and clean interior floor shell"),
    ("plank_horizontal", "Store rental shelving wood and rear timber trash-enclosure gate"),
]


def extract_swatches():
    atlas_meta = json.loads(ATLAS_JSON.read_text(encoding="utf-8"))
    aw, ah, arows = read_png_rgba(ATLAS_PNG)
    used = []
    for name, role in SHARED_SWATCHES:
        info = atlas_meta[name]
        x, y, w, h = info["x"], info["y"], info["w"], info["h"]
        sub = [arows[y + yy][x:x + w] for yy in range(h)]
        for yy in range(0, h, 2):
            for xx in range(0, w, 2):
                c = sub[yy][xx]
                assert sub[yy][xx + 1] == c and sub[yy + 1][xx] == c and sub[yy + 1][xx + 1] == c
        write_png_rgba(TEX_DIR / f"{name}.png", w, h, sub)
        used.append({"id": name, "size": [w, h], "role": role})
    return used


# ==============================================================================
# 2. SHARED BESPOKE LIMEZU-PALETTE TEXTURES (STUCCO FASCIA + UTILITY ATLAS)
# ==============================================================================
def make_stucco_fascia():
    """64x64 art px -> 128x128 texels: Warm pebble-dash tan-cream strip-centre stucco with 1px LimeZu outlines."""
    c = ArtCanvas(64, 64, (214, 204, 186, 255))
    outline = (58, 58, 80, 255)   # Classic LimeZu #3a3a50 outline
    hi = (232, 224, 208, 255)
    mid1 = (214, 204, 186, 255)
    mid2 = (198, 186, 166, 255)
    sh = (176, 164, 144, 255)
    stain = (158, 148, 134, 255)

    for y in range(64):
        for x in range(64):
            h = (x * 17 + y * 31 + (x ^ y) * 7) % 23
            if h < 4:
                col = hi
            elif h < 14:
                col = mid1
            elif h < 20:
                col = mid2
            else:
                col = sh
            # Subtle vertical expansion joint every 32 art px
            if x % 32 == 0:
                col = sh
            elif x % 32 == 1:
                col = hi
            # Top coping shadow & water drip wear near top courses
            if y == 0:
                col = outline
            elif y == 1:
                col = hi
            elif y in (2, 3) and (x * 5 + y) % 11 < 3:
                col = stain
            elif y == 63:
                col = outline
            c.put(x, y, col)
    c.save_2x(TEX_DIR / "stucco_fascia.png")


def make_utility_atlas():
    """128x128 art px -> 256x256 texels: 4 strict 64x64 quadrants in LimeZu palette."""
    c = ArtCanvas(128, 128, (88, 92, 102, 255))
    outline = (58, 58, 80, 255)

    # Q0 (0,0,64,64): galvanized_metal — rooftop HVAC units, back steel doors, grease trap lid, vent stacks
    for y in range(64):
        for x in range(64):
            v = (x * 7 + y * 5) % 13
            if y % 16 == 0 or x % 16 == 0:
                col = outline
            elif y % 16 == 1 or x % 16 == 1:
                col = (182, 188, 198, 255)
            elif v < 5:
                col = (148, 154, 164, 255)
            else:
                col = (132, 138, 148, 255)
            # Subtle louvre ribs on bottom half of tile
            if 36 <= y <= 56 and y % 4 == 0 and 4 <= x <= 60:
                col = (84, 88, 100, 255)
            c.put(x, y, col)

    # Q1 (64,0,64,64): dumpster_green — commercial muted olive-green steel dumpsters & VHS curbside drop box metal
    for y in range(64):
        for x in range(64, 128):
            lx = x - 64
            if lx % 16 == 0 or y % 16 == 0:
                col = (44, 58, 56, 255)
            elif y % 16 == 1:
                col = (102, 132, 114, 255)
            elif (lx * 11 + y * 7) % 17 == 0:
                col = (118, 82, 58, 255)  # small rust fleck
            elif lx % 8 == 4:
                col = (68, 94, 80, 255)
            else:
                col = (82, 110, 94, 255)
            c.put(x, y, col)

    # Q2 (0,64,64,64): vhs_shelves — rows of colorful muted-retro VHS clamshell tape spines on wood shelves
    tape_cols = [
        (164, 72, 68, 255),   # muted red
        (76, 108, 148, 255),  # denim blue
        (206, 174, 94, 255),  # warm yellow
        (92, 138, 104, 255),  # retro green
        (216, 208, 192, 255), # cream
        (134, 86, 128, 255),  # muted plum
        (54, 56, 68, 255),    # black plastic case
    ]
    for y in range(64, 128):
        ly = y - 64
        for x in range(64):
            row_mod = ly % 8
            if row_mod == 0:
                col = outline
            elif row_mod == 1:
                col = (162, 126, 86, 255)  # wood shelf top highlight
            elif row_mod == 2:
                col = (122, 90, 58, 255)   # wood shelf edge
            elif x % 2 == 0:
                col = (48, 48, 60, 255)    # spine outline
            else:
                idx = ((x // 2) * 5 + (ly // 8) * 11) % len(tape_cols)
                col = tape_cols[idx]
            c.put(x, y, col)

    # Q3 (64,64,64,64): lot_paint — weathered off-white parking lot stripe & concrete wheel-stop paint
    for y in range(64, 128):
        ly = y - 64
        for x in range(64, 128):
            lx = x - 64
            if (lx * 13 + ly * 7) % 19 == 0:
                col = (146, 144, 140, 255)  # small asphalt wear speck
            elif (lx + ly) % 7 == 0:
                col = (212, 206, 194, 255)
            else:
                col = (232, 226, 214, 255)
            c.put(x, y, col)

    c.save_2x(TEX_DIR / "utility.png")
    return {
        "galvanized_metal": {"sheet": "utility.png", "rect_art": [0, 0, 64, 64]},
        "dumpster_green": {"sheet": "utility.png", "rect_art": [64, 0, 64, 64]},
        "vhs_shelves": {"sheet": "utility.png", "rect_art": [0, 64, 64, 64]},
        "lot_paint": {"sheet": "utility.png", "rect_art": [64, 64, 64, 64]},
    }


# ==============================================================================
# 3. STATE-SPECIFIC STOREFRONT GLASS + SIGNS + PURE-BLACK EMISSIVE GLOW MASKS
# ==============================================================================
def make_state_glass(year):
    """
    256x128 art px -> 512x256 texels (BLEND): Aluminum-frame strip-centre storefront glazing.
    Contains bay-specific window art, hand-lettered fictional film posters, banners, and FOR LEASE sheets.
    """
    c = ArtCanvas(256, 128, (0, 0, 0, 0))
    outline = (58, 58, 80, 255)
    alum = (158, 164, 174, 255)
    alum_dk = (92, 98, 110, 255)
    glass_bg = (118, 142, 164, 92)
    glass_hi = (174, 198, 216, 132)
    glass_vacant = (84, 96, 110, 148)
    paper = (236, 230, 214, 255)
    yellow_paper = (242, 208, 76, 255)
    red_ink = (182, 52, 50, 255)
    navy_ink = (42, 62, 102, 255)
    dark_ink = (44, 44, 56, 255)

    def draw_window_frame(rx, ry, rw, rh, panes=3, vacant=False, door_on_right=False):
        bg = glass_vacant if vacant else glass_bg
        c.rect(rx, ry, rw, rh, bg)
        # Subtle diagonal reflection streak
        for yy in range(ry + 2, ry + rh - 2):
            for xx in range(rx + 2, rx + rw - 2):
                d = (xx - rx) + (yy - ry)
                if d % 28 in (6, 7):
                    c.put(xx, yy, glass_hi)
        # Horizontal transom bar near top
        c.rect(rx, ry + 7, rw, 1, alum)
        c.rect(rx, ry + 8, rw, 1, alum_dk)
        # Vertical aluminum mullions
        for i in range(1, panes):
            mx = rx + (rw * i) // panes
            c.rect(mx, ry, 1, rh, alum)
            c.rect(mx + 1, ry, 1, rh, alum_dk)
        if door_on_right:
            dx = rx + rw - 20
            c.rect(dx, ry, 2, rh, alum_dk)
            c.rect(dx + 3, ry + rh // 2, 1, 6, alum)  # push bar handle
            c.rect(dx + 2, ry + rh - 5, 16, 4, alum_dk)  # door kickplate
        c.border(rx, ry, rw, rh, outline, 1)
        c.border(rx + 1, ry + 1, rw - 2, rh - 2, alum, 1)

    def draw_mini_poster(px, py, pw, ph, bg_col, title_top, title_bot, accent_col):
        c.rect(px, py, pw, ph, bg_col)
        c.border(px, py, pw, ph, outline, 1)
        # Simple pixel graphic block in middle of poster
        c.rect(px + 3, py + 9, pw - 6, 6, accent_col)
        c.draw_text_in_box(px + 1, py + 2, pw - 2, 7, title_top, paper, font="3x5", spacing=1, pad_x=0, pad_y=0)
        c.draw_text_in_box(px + 1, py + ph - 8, pw - 2, 7, title_bot, paper, font="3x5", spacing=1, pad_x=0, pad_y=0)

    regions = {}

    # 1. bay1_win (88x30 at 0,0): Quiet neighbour Suds Laundry storefront
    draw_window_frame(0, 0, 88, 30, panes=3, door_on_right=True)
    # Modest small "OPEN" / "WASH" card in left pane
    c.rect(6, 11, 24, 9, paper)
    c.border(6, 11, 24, 9, navy_ink, 1)
    c.draw_text_in_box(6, 11, 24, 9, "OPEN", navy_ink, font="3x5", spacing=1, pad_x=2, pad_y=1)
    regions["bay1_win"] = {"sheet": f"glass_{year}.png", "rect_art": [0, 0, 88, 30]}

    # 2. bay4_win (88x30 at 90,0): Quiet neighbour Ruby Nails storefront
    draw_window_frame(90, 0, 88, 30, panes=3, door_on_right=True)
    c.rect(96, 11, 26, 9, (242, 224, 224, 255))
    c.border(96, 11, 26, 9, (152, 78, 92, 255), 1)
    c.draw_text_in_box(96, 11, 26, 9, "NAILS", (152, 78, 92, 255), font="3x5", spacing=1, pad_x=2, pad_y=1)
    regions["bay4_win"] = {"sheet": f"glass_{year}.png", "rect_art": [90, 0, 88, 30]}

    # 3. hero_door (22x30 at 184,0): Storefront glass entry door (Bay 2 main entrance)
    draw_window_frame(184, 0, 22, 30, panes=1)
    c.rect(186, 25, 18, 4, alum_dk)
    c.rect(188, 14, 1, 6, alum)
    if year == 2011:
        # Small taped closing-hours paper notice on door
        c.rect(189, 10, 12, 8, yellow_paper)
        c.border(189, 10, 12, 8, red_ink, 1)
    elif year in (1984, 1996):
        c.rect(189, 10, 12, 7, paper)
        c.border(189, 10, 12, 7, navy_ink, 1)
    regions["hero_door"] = {"sheet": f"glass_{year}.png", "rect_art": [184, 0, 22, 30]}

    # 4. bay2_glass (68x30 at 0,36): Hero Bay 2 main display window (left of door_main)
    draw_window_frame(0, 36, 68, 30, panes=2)
    if year == 1984:
        # "NEW RELEASES" hand-painted top banner across window + 2 hand-lettered fictional film posters!
        c.rect(3, 38, 62, 8, (238, 226, 174, 255))
        c.border(3, 38, 62, 8, red_ink, 1)
        c.draw_text_in_box(3, 38, 62, 8, "NEW RELEASES", red_ink, font="3x5", spacing=1, pad_x=2, pad_y=1)
        draw_mini_poster(6, 47, 26, 17, (56, 72, 112, 255), "STAR", "DRIFT", (214, 168, 72, 255))
        draw_mini_poster(36, 47, 26, 17, (132, 64, 56, 255), "NIGHT", "CAB", (226, 198, 124, 255))
    elif year == 1996:
        # "2 NIGHT RENTALS" bright crisp banner + 2 fictional 90s film posters
        c.rect(3, 38, 62, 8, navy_ink)
        c.border(3, 38, 62, 8, yellow_paper, 1)
        c.draw_text_in_box(3, 38, 62, 8, "2 NIGHT RENTALS", yellow_paper, font="3x5", spacing=1, pad_x=1, pad_y=1)
        draw_mini_poster(6, 47, 26, 17, (48, 86, 98, 255), "CYBER", "RUN", (142, 202, 194, 255))
        draw_mini_poster(36, 47, 26, 17, (76, 62, 92, 255), "COLD", "BAY", (206, 146, 86, 255))
    elif year == 2011:
        # "STORE CLOSING" bright yellow/red liquidation paper posters taped to glass, leaving gap to see half-empty shelves!
        c.rect(4, 38, 60, 9, yellow_paper)
        c.border(4, 38, 60, 9, red_ink, 1)
        c.draw_text_in_box(4, 38, 60, 9, "STORE CLOSING", red_ink, font="3x5", spacing=1, pad_x=2, pad_y=1)
        c.rect(36, 49, 28, 13, red_ink)
        c.border(36, 49, 28, 13, yellow_paper, 1)
        c.draw_text_in_box(36, 49, 28, 7, "ALL", yellow_paper, font="3x5", spacing=1, pad_x=1, pad_y=0)
        c.draw_text_in_box(36, 55, 28, 7, "70 OFF", yellow_paper, font="3x5", spacing=1, pad_x=1, pad_y=0)
    elif year == 2020:
        # Reused phone repair shop window decals: "PHONE REPAIR" & "SCREENS"
        c.rect(4, 38, 60, 9, paper)
        c.border(4, 38, 60, 9, (46, 96, 162, 255), 1)
        c.draw_text_in_box(4, 38, 60, 9, "PHONE REPAIR", (46, 96, 162, 255), font="3x5", spacing=1, pad_x=2, pad_y=1)
        c.rect(8, 50, 36, 9, (46, 96, 162, 255))
        c.draw_text_in_box(8, 50, 36, 9, "SCREENS", paper, font="3x5", spacing=1, pad_x=2, pad_y=1)
    regions["bay2_glass"] = {"sheet": f"glass_{year}.png", "rect_art": [0, 36, 68, 30]}

    # 5. bay3_glass (88x30 at 74,36): Expansion Bay 3 storefront window
    is_vacant = year in (1984, 2020)
    draw_window_frame(74, 36, 88, 30, panes=3, vacant=is_vacant, door_on_right=is_vacant)
    if is_vacant:
        # Plain white "FOR LEASE" paper notice taped inside window
        c.rect(92, 46, 46, 11, paper)
        c.border(92, 46, 46, 11, red_ink, 1)
        c.draw_text_in_box(92, 46, 46, 11, "FOR LEASE", red_ink, font="3x5", spacing=1, pad_x=2, pad_y=1)
    elif year == 1996:
        # Expanded Reel Time Video Bay 3: "NEW RELEASES" banner + "RED CANYON" poster + clear sightline to shelves!
        c.rect(78, 38, 58, 8, (238, 226, 174, 255))
        c.border(78, 38, 58, 8, navy_ink, 1)
        c.draw_text_in_box(78, 38, 58, 8, "NEW RELEASES", navy_ink, font="3x5", spacing=1, pad_x=2, pad_y=1)
        draw_mini_poster(132, 47, 26, 17, (148, 82, 52, 255), "RED", "MESA", (228, 194, 112, 255))
    elif year == 2011:
        # Expanded Bay 3 closing liquidation poster: "EVERYTHING MUST GO" + clear window gap showing half-empty shelves
        c.rect(78, 38, 52, 16, yellow_paper)
        c.border(78, 38, 52, 16, red_ink, 1)
        c.draw_text_in_box(78, 39, 52, 7, "EVERYTHING", red_ink, font="3x5", spacing=1, pad_x=2, pad_y=0)
        c.draw_text_in_box(78, 46, 52, 7, "MUST GO", red_ink, font="3x5", spacing=1, pad_x=2, pad_y=0)
    regions["bay3_glass"] = {"sheet": f"glass_{year}.png", "rect_art": [74, 36, 88, 30]}

    c.save_2x(TEX_DIR / f"glass_{year}.png")
    return regions


def make_state_signs_and_glow(year):
    """
    Produces:
    1) signs_{year}.png (256x128 art px -> 512x256 texels): Opaque daytime/unlit plates
       (unlit 2011 faded sign, 2020 cheap vinyl banner + old fascia ghost outline & bolt holes,
       VHS wall return slot, curbside drop box label, rear bay numbers).
    2) glow_base_{year}.png + glow_mask_{year}.png (256x64 art px -> 512x128 texels):
       STRICT EMISSIVE RULE: glow_mask_{year}.png holds ONLY the glowing pixels (lit fascia sign
       faces and tiny walkway soffit bulbs); EVERY other pixel is pure (0,0,0,255) black!
    """
    outline = (58, 58, 80, 255)
    cream = (242, 234, 212, 255)
    warm_lit = (252, 240, 198, 255)
    navy = (42, 62, 102, 255)
    blue_bright = (52, 92, 152, 255)
    red_retro = (176, 58, 52, 255)
    gold = (232, 184, 72, 255)
    rose = (168, 92, 106, 255)
    teal = (54, 114, 122, 255)
    stucco = (214, 204, 186, 255)
    stucco_ghost = (182, 172, 154, 255)
    bolt = (78, 66, 62, 255)
    rust_drip = (144, 116, 94, 255)
    black = (0, 0, 0, 255)

    # --- A. GLOWING SIGNS + PURE-BLACK EMISSIVE MASK (256x64 art px) ---
    gb = ArtCanvas(256, 64, (44, 46, 54, 255))
    gm = ArtCanvas(256, 64, black)  # PURE BLACK everywhere except glowing pixels!

    regions = {}

    # 1. bay1_sign (76x11 at 0,0): Quiet neighbour "SUDS LAUNDRY"
    gb.rect(0, 0, 76, 11, cream if year <= 1996 else (222, 214, 196, 255))
    gb.border(0, 0, 76, 11, outline, 1)
    gb.draw_text_in_box(0, 0, 76, 11, "SUDS LAUNDRY", teal, font="5x7", spacing=1, pad_x=2, pad_y=2)
    # Emissive mask: ONLY inner lit sign face glows (outer 1px frame stays pure black!)
    gm.rect(1, 1, 74, 9, (186, 196, 178, 255) if year <= 1996 else (152, 158, 146, 255))
    gm.draw_text_in_box(0, 0, 76, 11, "SUDS LAUNDRY", (42, 86, 94, 255), font="5x7", spacing=1, pad_x=2, pad_y=2)
    regions["bay1_sign"] = {"sheet": f"glow_base_{year}.png", "rect_art": [0, 0, 76, 11]}

    # 2. bay4_sign (64x11 at 80,0): Quiet neighbour "RUBY NAILS"
    gb.rect(80, 0, 64, 11, cream if year <= 1996 else (226, 216, 204, 255))
    gb.border(80, 0, 64, 11, outline, 1)
    gb.draw_text_in_box(80, 0, 64, 11, "RUBY NAILS", rose, font="5x7", spacing=1, pad_x=2, pad_y=2)
    gm.rect(81, 1, 62, 9, (194, 178, 174, 255))
    gm.draw_text_in_box(80, 0, 64, 11, "RUBY NAILS", (128, 56, 70, 255), font="5x7", spacing=1, pad_x=2, pad_y=2)
    regions["bay4_sign"] = {"sheet": f"glow_base_{year}.png", "rect_art": [80, 0, 64, 11]}

    # 3. soffit_lamp (6x4 at 148,0): Small recessed walkway soffit bulb (ny = -1 -> spawns Filament lights!)
    gb.rect(148, 0, 6, 4, (74, 76, 84, 255))
    gb.rect(149, 1, 4, 2, warm_lit)
    gm.rect(149, 1, 4, 2, (255, 232, 176, 255))
    regions["soffit_lamp"] = {"sheet": f"glow_base_{year}.png", "rect_art": [148, 0, 6, 4]}

    # 4. hero_sign_1984 (96x12 at 0,18): Plain warm-lit 1-bay box sign "REEL TIME VIDEO"
    gb.rect(0, 18, 96, 12, cream)
    gb.border(0, 18, 96, 12, outline, 1)
    gb.border(1, 19, 94, 10, red_retro, 1)
    gb.draw_text_in_box(0, 18, 96, 12, "REEL TIME VIDEO", navy, font="5x7", spacing=1, pad_x=3, pad_y=2)
    if year == 1984:
        gm.rect(2, 20, 92, 8, (226, 210, 168, 255))
        gm.draw_text_in_box(0, 18, 96, 12, "REEL TIME VIDEO", (34, 48, 84, 255), font="5x7", spacing=1, pad_x=3, pad_y=2)
    regions["hero_sign_1984"] = {"sheet": f"glow_base_{year}.png", "rect_art": [0, 18, 96, 12]}

    # 5. hero_sign_1996 (110x12 at 0,38): Expanded brighter 2-bay backlit box sign "REEL TIME VIDEO"
    gb.rect(0, 38, 110, 12, blue_bright)
    gb.border(0, 38, 110, 12, outline, 1)
    gb.border(1, 39, 108, 10, gold, 1)
    gb.draw_text_in_box(0, 38, 110, 12, "REEL TIME VIDEO", warm_lit, font="5x7", spacing=2, pad_x=3, pad_y=2)
    if year == 1996:
        gm.rect(2, 40, 106, 8, (64, 108, 178, 255))
        gm.border(1, 39, 108, 10, (212, 164, 52, 255), 1)
        gm.draw_text_in_box(0, 38, 110, 12, "REEL TIME VIDEO", (255, 242, 188, 255), font="5x7", spacing=2, pad_x=3, pad_y=2)
    regions["hero_sign_1996"] = {"sheet": f"glow_base_{year}.png", "rect_art": [0, 38, 110, 12]}

    gb.save_2x(TEX_DIR / f"glow_base_{year}.png")
    gm.save_2x(TEX_DIR / f"glow_mask_{year}.png")

    # --- B. OPAQUE / UNLIT SIGN PLATES (256x128 art px) ---
    sp = ArtCanvas(256, 128, (64, 66, 74, 255))

    # 1. hero_sign_2011_faded (110x12 at 0,0): Sun-faded, peeling, UNLIT 2-bay "REEL TIME VIDEO" sign
    sp.rect(0, 0, 110, 12, (108, 118, 132, 255))  # sun-bleached blue plastic
    sp.border(0, 0, 110, 12, outline, 1)
    sp.border(1, 1, 108, 10, (154, 138, 96, 255), 1)  # faded mustard trim
    # Weather streaks & sun-fading patches
    for xx in range(3, 107):
        if xx % 9 in (2, 3):
            sp.rect(xx, 2, 1, 8, (124, 130, 138, 255))
        if xx in (22, 58, 90):
            sp.rect(xx, 2, 2, 8, (82, 86, 94, 255))  # plastic crack
    sp.draw_text_in_box(0, 0, 110, 12, "REEL TIME VIDEO", (196, 192, 178, 255), font="5x7", spacing=2, pad_x=3, pad_y=2)
    regions["hero_sign_2011_faded"] = {"sheet": f"signs_{year}.png", "rect_art": [0, 0, 110, 12]}

    # 2. fascia_ghost_2020 (110x12 at 0,20): Stucco fascia scar showing old sign's ghost outline + dark bolt holes + rust drips!
    for y in range(20, 32):
        for x in range(110):
            h = (x * 17 + y * 31) % 19
            col = stucco if h < 11 else (198, 188, 170, 255)
            # Ghost dirt halo rectangle where the old 1996 110x12 sign box used to sit
            if 1 <= x <= 108 and 21 <= y <= 30:
                col = (166, 156, 138, 255) if h < 12 else (154, 144, 128, 255)
            if (x in (1, 108) and 21 <= y <= 30) or (y in (21, 30) and 1 <= x <= 108):
                col = (112, 102, 88, 255)  # weathered grime border outline!
            sp.put(x, y, col)
    # Faint ghost letter stains from "REEL TIME VIDEO" on the right exposed portion + dark bolt holes & rust drips
    sp.draw_text_in_box(0, 20, 110, 12, "REEL TIME VIDEO", (134, 124, 108, 255), font="5x7", spacing=2, pad_x=3, pad_y=2)
    for bx in (8, 32, 55, 78, 101):
        for by in (22, 29):
            sp.rect(bx, by, 2, 2, (48, 42, 40, 255))
            if by == 22:
                sp.rect(bx, by + 2, 1, 3, (124, 92, 72, 255))
            else:
                sp.rect(bx, by + 1, 1, 1, (124, 92, 72, 255))
    regions["fascia_ghost_2020"] = {"sheet": f"signs_{year}.png", "rect_art": [0, 20, 110, 12]}

    # 3. banner_2020 (68x12 at 116,20): Cheap white & blue vinyl banner "CELL FIX" tied over the old sign frame
    bx, by, bw, bh = 116, 20, 68, 12
    sp.rect(bx, by, bw, bh, (238, 240, 244, 255))
    sp.border(bx, by, bw, bh, (44, 96, 164, 255), 1)
    # Subtle vinyl sag shadow line & corner grommet tie-straps
    sp.rect(bx + 1, by + bh - 2, bw - 2, 1, (206, 212, 222, 255))
    for gx in (bx + 2, bx + bw // 2, bx + bw - 3):
        sp.put(gx, by + 1, outline)
        sp.put(gx, by + bh - 2, outline)
    sp.draw_text_in_box(bx, by, bw, bh, "CELL FIX", (44, 96, 164, 255), font="5x7", spacing=1, pad_x=6, pad_y=2)
    regions["banner_2020"] = {"sheet": f"signs_{year}.png", "rect_art": [116, 20, 68, 12]}

    # 4. vhs_return_slot (14x16 at 0,42): Recessed metal VHS wall return slot beside door_main (1984-2011)
    sp.rect(0, 42, 14, 16, (148, 154, 164, 255))
    sp.border(0, 42, 14, 16, outline, 1)
    sp.rect(2, 44, 10, 4, navy if year != 2011 else (112, 114, 120, 255))
    sp.rect(2, 50, 10, 4, (36, 38, 46, 255))  # metal flap slot
    sp.rect(3, 51, 8, 1, (186, 192, 202, 255))
    regions["vhs_return_slot"] = {"sheet": f"signs_{year}.png", "rect_art": [0, 42, 14, 16]}

    # 5. dropbox_front (16x22 at 18,42): Curbside freestanding VHS return drop box (1996 active / 2011 taped closed)
    dx, dy, dw, dh = 18, 42, 16, 22
    box_col = (52, 86, 134, 255) if year == 1996 else (92, 104, 118, 255)
    sp.rect(dx, dy, dw, dh, box_col)
    sp.border(dx, dy, dw, dh, outline, 1)
    sp.rect(dx + 2, dy + 3, 12, 4, (36, 38, 46, 255))  # chute flap
    sp.rect(dx + 2, dy + 9, 12, 7, cream)
    sp.border(dx + 2, dy + 9, 12, 7, outline, 1)
    if year == 1996:
        sp.draw_text_in_box(dx + 2, dy + 9, 12, 7, "VHS", red_retro, font="3x5", spacing=0, pad_x=1, pad_y=1)
    else:
        # Taped shut in 2011 with yellow strip across chute!
        sp.rect(dx + 1, dy + 4, 14, 2, (232, 198, 72, 255))
        sp.draw_text_in_box(dx + 2, dy + 9, 12, 7, "OUT", red_retro, font="3x5", spacing=0, pad_x=1, pad_y=1)
    regions["dropbox_front"] = {"sheet": f"signs_{year}.png", "rect_art": [18, 42, 16, 22]}

    sp.save_2x(TEX_DIR / f"signs_{year}.png")
    return regions


def main():
    swatches = extract_swatches()
    make_stucco_fascia()
    util_regions = make_utility_atlas()

    all_regions = {"utility": util_regions, "states": {}}
    for year in (1984, 1996, 2011, 2020):
        st_reg = {}
        st_reg.update(make_state_glass(year))
        st_reg.update(make_state_signs_and_glow(year))
        all_regions["states"][str(year)] = st_reg

    manifest = {
        "shared_swatches": swatches,
        "regions": all_regions,
    }
    (R / "art-regions.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print("Generated Reel Time Video swatches, state glazing, daytime signs, and pure-black emissive masks (1984/1996/2011/2020).")


if __name__ == "__main__":
    main()
