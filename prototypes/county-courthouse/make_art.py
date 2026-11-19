#!/usr/bin/env python3
"""
Texture and swatch generator for Harlan County Courthouse & Square across 4 states:
  1894 (1894-1950), 1962 (1951-1975), 1988 (1976-1999), 2008 (2000 onward), plus courthouse-far.

Rules enforced:
- Pulls shared LimeZu swatches directly from kotlin-street/tools/swatches/atlas/atlas.png:
  brick_red, roof_slate, roof_membrane_green, pavement, plank_horizontal, block_courses.
- Exports every texture on an exact 2x2 texel art-pixel grid (0.0388 m/texel -> 0.0776 m/art px).
- No flat single-colour walls: all painted materials carry outlines (#3a3a50), top highlights,
  and stepped LimeZu-palette shading.
- Lettering measured strictly against its plate before drawing (zero overflow):
  "HARLAN COUNTY COURTHOUSE", "1894", "SAVE OUR COURTHOUSE" (1988), "RESTORED 2004" (2008).
- Separate pure-black emissive masks for clock dials and lamp heads (never whole walls!).
"""
import json
import math
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
            w, h, bd, ct, _, _, _ = struct.unpack(">IIBBBBB", chunk)
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

    def circle(self, cx, cy, r, col, filled=True):
        r2 = r * r
        for yy in range(int(cy - r - 1), int(cy + r + 2)):
            for xx in range(int(cx - r - 1), int(cx + r + 2)):
                dx = xx - cx
                dy = yy - cy
                d2 = dx * dx + dy * dy
                if filled:
                    if d2 <= r2:
                        self.put(xx, yy, col)
                else:
                    if abs(math.sqrt(d2) - r) <= 0.7:
                        self.put(xx, yy, col)

    def draw_text_in_box(self, bx, by, bw, bh, text, col, font="5x7", scale=1, spacing=1, pad_x=2, pad_y=1):
        tw, th = measure_text(text, font=font, scale=scale, spacing=spacing)
        assert tw + 2 * pad_x <= bw, f"Text '{text}' width {tw}+2*{pad_x} overflows plate width {bw}!"
        assert th + 2 * pad_y <= bh, f"Text '{text}' height {th}+2*{pad_y} overflows plate height {bh}!"
        ox = bx + (bw - tw) // 2
        oy = by + (bh - th) // 2
        glyphs = FONT_5X7 if font == "5x7" else FONT_3X5
        gw = 5 if font == "5x7" else 3
        cx = ox
        for ch in text.upper():
            g = glyphs.get(ch, glyphs[" "])
            for gy, row in enumerate(g):
                for gx, bit in enumerate(row):
                    if bit == "1":
                        for sy in range(scale):
                            for sx in range(scale):
                                self.put(cx + gx * scale + sx, oy + gy * scale + sy, col)
            cx += (gw + spacing) * scale

    def save_2x(self, path):
        w2, h2 = self.w * 2, self.h * 2
        out = [[None] * w2 for _ in range(h2)]
        for y in range(self.h):
            r0 = out[y * 2]
            r1 = out[y * 2 + 1]
            src = self.px[y]
            for x in range(self.w):
                c = src[x]
                r0[x * 2] = c
                r0[x * 2 + 1] = c
                r1[x * 2] = c
                r1[x * 2 + 1] = c
        write_png_rgba(path, w2, h2, out)


def extract_limezu_swatches():
    """Extract shared LimeZu swatches directly from atlas.png onto exact 2x2 art-pixel grids."""
    atlas_meta = json.loads(ATLAS_JSON.read_text(encoding="utf-8"))
    _, _, atlas_rows = read_png_rgba(ATLAS_PNG)

    picks = [
        ("brick_red", "swatch_brick_red.png"),
        ("roof_slate", "swatch_roof_slate.png"),
        ("pavement", "swatch_pavement.png"),
        ("plank_horizontal", "swatch_plank_horizontal.png"),
        ("roof_membrane_green", "swatch_copper_base.png"),
        ("block_courses", "swatch_block_courses.png"),
    ]
    extracted = {}
    for sid, fname in picks:
        m = atlas_meta[sid]
        x0, y0, w, h = m["x"], m["y"], m["w"], m["h"]
        # Copy exact 1x swatch and upscale 2x nearest so every art pixel is a crisp 2x2 texel block
        c = ArtCanvas(w, h)
        for yy in range(h):
            for xx in range(w):
                c.put(xx, yy, atlas_rows[y0 + yy][x0 + xx])
        c.save_2x(TEX_DIR / fname)
        extracted[sid] = {"file": fname, "art_size": [w, h], "tex_size": [w * 2, h * 2]}
    return extracted, atlas_meta, atlas_rows


def make_rusticated_stone():
    """64x64 art px -> 128x128 texels: Romanesque Revival rough-dressed rusticated granite ashlar."""
    c = ArtCanvas(64, 64, (148, 144, 136, 255))
    outline = (58, 58, 80, 255)
    # 4 heavy stone courses of 16 art px height with alternating staggered ashlar joints
    course_h = 16
    for row_idx in range(4):
        y0 = row_idx * course_h
        offset = 0 if row_idx % 2 == 0 else 16
        blocks = [0, 32] if row_idx % 2 == 0 else [0, 16, 48]
        for y in range(y0, y0 + course_h):
            ly = y - y0
            for x in range(64):
                bx = (x + offset) % 32
                block_id = ((x + offset) // 32) + row_idx * 7
                base_tone = [
                    (158, 152, 142),
                    (146, 142, 134),
                    (164, 156, 144),
                    (138, 136, 130),
                ][block_id % 4]
                if ly == 0 or bx == 0:
                    col = outline
                elif ly == 1 or bx == 1:
                    # Rock-faced top/left bevel highlight
                    col = (min(255, base_tone[0] + 24), min(255, base_tone[1] + 22), min(255, base_tone[2] + 18), 255)
                elif ly >= course_h - 2 or bx == 31:
                    # Deep chiseled rusticated bottom/right shadow
                    col = (max(0, base_tone[0] - 28), max(0, base_tone[1] - 26), max(0, base_tone[2] - 20), 255)
                else:
                    # Stepped rough-hewn granite pillow surface texture
                    h = (x * 19 + y * 37 + block_id * 13) % 23
                    if h in (0, 1, 2):
                        col = (min(255, base_tone[0] + 14), min(255, base_tone[1] + 12), min(255, base_tone[2] + 10), 255)
                    elif h in (3, 4, 5):
                        col = (max(0, base_tone[0] - 16), max(0, base_tone[1] - 15), max(0, base_tone[2] - 12), 255)
                    else:
                        col = (base_tone[0], base_tone[1], base_tone[2], 255)
                c.put(x, y, col)
    c.save_2x(TEX_DIR / "stone_rusticated.png")


def make_copper_spire(atlas_meta, atlas_rows):
    """64x64 art px -> 128x128 texels: Patinated verdigris standing-seam copper (normal & darker 1988)."""
    m = atlas_meta["roof_membrane_green"]
    mx, my, mw, mh = m["x"], m["y"], m["w"], m["h"]
    outline = (44, 62, 62, 255)

    for variant, fname, darken in [
        ("normal", "copper_spire.png", 0),
        ("dark_1988", "copper_spire_dark.png", 36),
    ]:
        c = ArtCanvas(64, 64)
        for y in range(64):
            for x in range(64):
                sr, sg, sb, _ = atlas_rows[my + (y % mh)][mx + (x % mw)]
                # Blend toward historic verdigris copper teal-green with vertical standing seams every 8 art px
                r = max(24, min(220, int(sr * 0.55 + 78 * 0.45) - darken))
                g = max(36, min(230, int(sg * 0.55 + 152 * 0.45) - darken))
                b = max(36, min(225, int(sb * 0.55 + 132 * 0.45) - darken))
                if x % 8 == 0:
                    col = (max(18, outline[0] - darken // 2), max(24, outline[1] - darken // 2), max(28, outline[2] - darken // 2), 255)
                elif x % 8 == 1:
                    col = (min(240, r + 24), min(245, g + 26), min(240, b + 22), 255)
                elif y % 16 == 0:
                    col = (max(20, r - 20), max(24, g - 18), max(24, b - 16), 255)
                elif (x * 13 + y * 29) % 19 == 0:
                    # Subtle patina mottling
                    col = (min(230, r + 14), min(240, g + 18), min(235, b + 14), 255)
                elif (x * 7 + y * 17) % 23 == 0:
                    col = (max(20, r - 18), max(24, g - 16), max(24, b - 14), 255)
                else:
                    col = (r, g, b, 255)
                c.put(x, y, col)
        c.save_2x(TEX_DIR / fname)


def make_lawn_turf():
    """64x64 art px -> 128x128 texels: Textured LimeZu-palette town-square lawn with mown stripes & grass flecks."""
    c = ArtCanvas(64, 64, (71, 151, 87, 255))  # LimeZu #479757 base
    for y in range(64):
        stripe = ((y // 16) % 2 == 0)
        for x in range(64):
            base = (76, 156, 88) if stripe else (66, 144, 82)
            h = (x * 23 + y * 41) % 31
            if h in (0, 1):
                col = (100, 182, 59, 255)   # LimeZu #64b63b bright grass blade cluster
            elif h in (2, 3):
                col = (38, 139, 73, 255)    # LimeZu #268b49 shadow tuft
            elif h == 4 and (x % 4 == 0):
                col = (118, 186, 82, 255)   # clover/sun fleck
            else:
                col = (base[0], base[1], base[2], 255)
            c.put(x, y, col)
    c.save_2x(TEX_DIR / "ground_lawn.png")


def make_interior_wall():
    """64x64 art px -> 128x128 texels: Historic courthouse plaster upper wall + chair rail + oak wainscot."""
    c = ArtCanvas(64, 64, (226, 218, 198, 255))
    outline = (58, 58, 80, 255)
    for y in range(64):
        for x in range(64):
            if y < 40:
                # Warm aged cream plaster with subtle stepped shading & cornice shadow at top
                h = (x * 17 + y * 29) % 23
                if y == 0:
                    col = outline
                elif y <= 3:
                    col = (196, 188, 168, 255)  # crown molding shadow
                elif h in (0, 1):
                    col = (234, 228, 210, 255)
                elif h in (2, 3):
                    col = (212, 204, 184, 255)
                else:
                    col = (224, 216, 196, 255)
            elif y in (40, 42):
                col = outline
            elif y == 41:
                col = (168, 128, 82, 255)  # oak chair rail highlight
            else:
                # Paneled oak wainscot (y = 43..63)
                ly = y - 43
                px = x % 16
                if ly == 20:
                    col = outline
                elif px == 0 or (ly in (2, 17) and 2 <= px <= 13):
                    col = (78, 54, 36, 255)
                elif px == 2 and 2 <= ly <= 17:
                    col = (152, 110, 68, 255)
                elif px == 13 and 2 <= ly <= 17:
                    col = (94, 66, 42, 255)
                else:
                    col = (126, 90, 54, 255) if (x + y) % 5 else (116, 82, 48, 255)
            c.put(x, y, col)
    c.save_2x(TEX_DIR / "interior_wall.png")


def make_detail_atlas(stained_1988=False):
    """
    128x128 art px -> 256x256 texels: 4 strict 64x64 quadrants (NO geometry may cross quadrant bounds!).
      Q0 (0,0):   dark_iron — cast-iron/bronze (lamp posts, railings, bell, memorial soldier, clock hands)
      Q1 (64,0):  concrete_annex — mid-century poured concrete panels (clean 1962 / water-stained 1988)
      Q2 (0,64):  rotunda_marble — historic cream & charcoal checkerboard stone tile for central hall
      Q3 (64,64): bandstand_cream — painted cream architectural trim & woodwork (bandstand, skylight sash)
    """
    c = ArtCanvas(128, 128, (64, 66, 74, 255))
    outline = (42, 44, 56, 255)

    # Q0 (0,0,64,64): dark_iron / aged bronze
    for y in range(64):
        for x in range(64):
            h = (x * 13 + y * 29) % 19
            if x % 16 == 0 or y % 16 == 0:
                col = outline
            elif x % 16 == 1 or y % 16 == 1:
                col = (98, 102, 114, 255)
            elif h in (0, 1):
                col = (84, 92, 96, 255)  # faint bronze/iron sheen
            else:
                col = (62, 66, 76, 255)
            c.put(x, y, col)

    # Q1 (64,0,64,64): concrete_annex (clean mid-century 1962 vs stained/streaked 1988)
    for y in range(64):
        for x in range(64, 128):
            lx = x - 64
            h = (lx * 19 + y * 31) % 23
            if lx % 32 == 0 or y % 16 == 0:
                col = (94, 96, 102, 255)
            elif y % 16 == 1:
                col = (196, 194, 186, 255)
            else:
                col = (176, 174, 166, 255) if h > 4 else (162, 160, 152, 255)
            if stained_1988:
                # Vertical rain/grime stains dripping down concrete panels
                if lx in (6, 7, 22, 23, 38, 39, 54, 55) and y < 52:
                    col = (118, 114, 104, 255) if (y + lx) % 3 else (104, 100, 92, 255)
                elif y >= 50:
                    col = (128, 124, 114, 255)
            c.put(x, y, col)

    # Q2 (0,64,64,64): rotunda_marble — classic 16x16 checkerboard marble tiles
    for y in range(64, 128):
        ly = y - 64
        for x in range(64):
            tx, ty = x // 16, ly // 16
            dark = ((tx + ty) % 2 == 1)
            if x % 16 == 0 or ly % 16 == 0:
                col = (98, 96, 102, 255)
            elif dark:
                col = (84, 86, 94, 255) if (x + ly) % 7 else (98, 100, 108, 255)
            else:
                col = (224, 218, 204, 255) if (x + ly) % 9 else (208, 202, 188, 255)
            c.put(x, y, col)

    # Q3 (64,64,64,64): bandstand_cream — painted heritage cream clapboard/trim
    for y in range(64, 128):
        ly = y - 64
        for x in range(64, 128):
            lx = x - 64
            if ly % 8 == 0:
                col = (146, 140, 130, 255)
            elif ly % 8 == 1:
                col = (242, 238, 226, 255)
            elif lx % 16 == 0:
                col = (192, 186, 174, 255)
            else:
                col = (228, 222, 208, 255)
            c.put(x, y, col)

    fname = "detail_atlas_1988.png" if stained_1988 else "detail_atlas.png"
    c.save_2x(TEX_DIR / fname)
    return {
        "dark_iron": {"sheet": fname, "rect_art": [0, 0, 64, 64]},
        "concrete_annex": {"sheet": fname, "rect_art": [64, 0, 64, 64]},
        "rotunda_marble": {"sheet": fname, "rect_art": [0, 64, 64, 64]},
        "bandstand_cream": {"sheet": fname, "rect_art": [64, 64, 64, 64]},
    }


def make_glass_atlas():
    """
    128x128 art px -> 256x256 texels (BLEND): 4 clean 64x64 regions:
      - win_arched (0,0,32,48): historic multi-pane sash glazing (semi-transparent so interior glows!)
      - win_bricked (32,0,32,48): 1951-1999 partly bricked-in arch with smaller 1950s aluminium window!
      - win_boarded (64,0,32,48): 1976-1999 plywood-boarded neglected window
      - win_annex (96,0,32,48): mid-century aluminium ribbon window for concrete annex
      - skylight_pane (0,64,64,64): leaded skylight glazing overhead in the central hall
    """
    c = ArtCanvas(128, 128, (0, 0, 0, 0))
    outline = (48, 48, 66, 255)
    wood = (88, 60, 42, 255)
    alum = (174, 180, 188, 255)
    alum_dk = (102, 108, 118, 255)
    glass = (116, 142, 168, 146)
    glass_hi = (176, 202, 222, 178)

    def fill_glass_panes(rx, ry, rw, rh, frame_col=wood, cols=2, rows=3):
        c.rect(rx, ry, rw, rh, glass)
        for yy in range(ry + 2, ry + rh - 2):
            for xx in range(rx + 2, rx + rw - 2):
                if ((xx - rx) + (yy - ry)) % 14 in (4, 5):
                    c.put(xx, yy, glass_hi)
        for i in range(1, cols):
            mx = rx + (rw * i) // cols
            c.rect(mx, ry, 2, rh, frame_col)
        for j in range(1, rows):
            my = ry + (rh * j) // rows
            c.rect(rx, my, rw, 2, frame_col)
        c.border(rx, ry, rw, rh, outline, 1)
        c.border(rx + 1, ry + 1, rw - 2, rh - 2, frame_col, 1)

    # 1. win_arched (0,0,32,48)
    fill_glass_panes(0, 0, 32, 48, wood, cols=2, rows=3)

    # 2. win_bricked (32,0,32,48): upper half bricked in with mismatched red-tan brick, lower aluminium window
    for y in range(0, 22):
        for x in range(32, 64):
            lx = x - 32
            if y % 4 == 0 or (lx + (4 if (y // 4) % 2 else 0)) % 8 == 0:
                col = (146, 134, 120, 255)  # tan mortar
            else:
                col = (158, 86, 72, 255) if (lx + y) % 5 else (142, 74, 62, 255)
            c.put(x, y, col)
    c.rect(32, 20, 32, 2, alum_dk)
    fill_glass_panes(34, 22, 28, 24, alum, cols=2, rows=2)
    c.border(32, 0, 32, 48, outline, 1)

    # 3. win_boarded (64,0,32,48): weathered plywood sheets nailed across window opening
    for y in range(0, 48):
        for x in range(64, 96):
            lx = x - 64
            if y in (0, 24, 47) or lx in (0, 31):
                col = outline
            elif (lx + y * 3) % 11 == 0:
                col = (142, 114, 78, 255)
            else:
                col = (168, 138, 96, 255)
            c.put(x, y, col)
    # Cross brace batten
    for t in range(4, 28):
        c.put(64 + t, 8 + t, (112, 86, 56, 255))
        c.put(64 + t, 9 + t, (112, 86, 56, 255))
        c.put(95 - t, 8 + t, (112, 86, 56, 255))
        c.put(95 - t, 9 + t, (112, 86, 56, 255))

    # 4. win_annex (96,0,32,48): 1950s aluminium ribbon window
    fill_glass_panes(96, 0, 32, 48, alum, cols=2, rows=2)

    # 5. skylight_pane (0,64,64,64): luminous pale skylight glazing
    c.rect(0, 64, 64, 64, (194, 218, 238, 128))
    for i in range(0, 64, 16):
        c.rect(i, 64, 2, 64, (220, 214, 198, 255))
        c.rect(0, 64 + i, 64, 2, (220, 214, 198, 255))
    c.border(0, 64, 64, 64, outline, 1)

    c.save_2x(TEX_DIR / "glass_atlas.png")
    return {
        "win_arched": {"sheet": "glass_atlas.png", "rect_art": [0, 0, 32, 48]},
        "win_bricked": {"sheet": "glass_atlas.png", "rect_art": [32, 0, 32, 48]},
        "win_boarded": {"sheet": "glass_atlas.png", "rect_art": [64, 0, 32, 48]},
        "win_annex": {"sheet": "glass_atlas.png", "rect_art": [96, 0, 32, 48]},
        "skylight_pane": {"sheet": "glass_atlas.png", "rect_art": [0, 64, 64, 64]},
    }


def make_sign_plates():
    """
    256x128 art px -> 512x256 texels: ALL carved stone plates, protest banner, bronze plaque,
    patched brick scar, and courthouse flag. EVERY text string is strictly fit-checked!
    """
    c = ArtCanvas(256, 128, (148, 144, 136, 255))
    outline = (58, 58, 80, 255)
    stone_lt = (196, 190, 176, 255)
    stone_md = (176, 170, 156, 255)
    stone_dk = (92, 88, 82, 255)
    carved_ink = (54, 52, 62, 255)

    regions = {}

    # 1. main_frieze (96x20 at 0,0): Carved limestone entablature "HARLAN COUNTY / COURTHOUSE"
    #    Line 1: "HARLAN COUNTY" = 13*6-1 = 77 px; Line 2: "COURTHOUSE" = 10*6-1 = 59 px -> fits 96x20 cleanly!
    c.rect(0, 0, 96, 20, stone_lt)
    for yy in range(1, 19):
        for xx in range(1, 95):
            if (xx * 13 + yy * 29) % 17 == 0:
                c.put(xx, yy, stone_md)
    c.border(0, 0, 96, 20, outline, 1)
    c.border(1, 1, 94, 18, stone_dk, 1)
    c.draw_text_in_box(0, 2, 96, 8, "HARLAN COUNTY", carved_ink, font="5x7", spacing=1, pad_x=4, pad_y=0)
    c.draw_text_in_box(0, 11, 96, 8, "COURTHOUSE", carved_ink, font="5x7", spacing=1, pad_x=4, pad_y=0)
    regions["main_frieze"] = {"sheet": "sign_plates.png", "rect_art": [0, 0, 96, 20]}

    # 2. datestone_1894 (32x12 at 100,0): Carved granite datestone "1894"
    #    4 chars in 5x7 = 23 px wide x 7 px tall -> fits 32x12 cleanly!
    c.rect(100, 0, 32, 12, stone_lt)
    c.border(100, 0, 32, 12, outline, 1)
    c.border(101, 1, 30, 10, stone_dk, 1)
    c.draw_text_in_box(100, 0, 32, 12, "1894", carved_ink, font="5x7", spacing=1, pad_x=3, pad_y=2)
    regions["datestone_1894"] = {"sheet": "sign_plates.png", "rect_art": [100, 0, 32, 12]}

    # 3. plaque_restored_2004 (36x16 at 136,0): Bronze restoration plaque "RESTORED / 2004"
    #    Line 1: "RESTORED" (8*4-1 = 31 px), Line 2: "2004" (4*4-1 = 15 px) -> fits 36x16 cleanly at 1:1 scale!
    px, py, pw, ph = 136, 0, 36, 16
    c.rect(px, py, pw, ph, (98, 72, 46, 255))
    c.border(px, py, pw, ph, (46, 34, 24, 255), 1)
    c.border(px + 1, py + 1, pw - 2, ph - 2, (186, 146, 86, 255), 1)
    c.draw_text_in_box(px, py + 2, pw, 6, "RESTORED", (228, 194, 124, 255), font="3x5", spacing=1, pad_x=2, pad_y=0)
    c.draw_text_in_box(px, py + 9, pw, 6, "2004", (228, 194, 124, 255), font="3x5", spacing=1, pad_x=2, pad_y=0)
    regions["plaque_restored_2004"] = {"sheet": "sign_plates.png", "rect_art": [136, 0, 36, 16]}

    # 4. protest_banner_1988 (92x26 at 0,20): Hand-painted cloth banner "SAVE OUR / COURTHOUSE" on the tower!
    #    Line 1: "SAVE OUR" = 8 chars in 5x7 = 47 px wide; Line 2: "COURTHOUSE" = 10 chars in 5x7 = 59 px wide!
    bx, by, bw, bh = 0, 20, 92, 26
    canvas_bg = (234, 226, 204, 255)
    red_paint = (168, 42, 42, 255)
    c.rect(bx, by, bw, bh, canvas_bg)
    for yy in range(by + 1, by + bh - 1):
        for xx in range(bx + 1, bx + bw - 1):
            if (xx * 11 + yy * 19) % 29 == 0:
                c.put(xx, yy, (206, 198, 178, 255))  # weathered canvas crease
    c.border(bx, by, bw, bh, outline, 1)
    c.border(bx + 1, by + 1, bw - 2, bh - 2, red_paint, 1)
    c.draw_text_in_box(bx, by + 3, bw, 9, "SAVE OUR", red_paint, font="5x7", spacing=1, pad_x=4, pad_y=1)
    c.draw_text_in_box(bx, by + 14, bw, 9, "COURTHOUSE", red_paint, font="5x7", spacing=1, pad_x=4, pad_y=1)
    regions["protest_banner_1988"] = {"sheet": "sign_plates.png", "rect_art": [0, 20, 92, 26]}

    # 5. patched_brick_2008 (96x48 at 0,52): Slightly newer mismatched patched brick scar on -Z wall after annex removal!
    for y in range(52, 100):
        ly = y - 52
        for x in range(0, 96):
            if ly % 4 == 0 or (x + (4 if (ly // 4) % 2 else 0)) % 8 == 0:
                col = (182, 168, 150, 255)  # lighter fresh mortar
            elif ly in (1, 2) or x in (1, 94):
                col = (122, 62, 54, 255)    # ghost roof flashing seam line
            else:
                col = (182, 92, 74, 255) if (x * 7 + ly * 13) % 5 else (164, 78, 64, 255)
            c.put(x, y, col)
    regions["patched_brick_2008"] = {"sheet": "sign_plates.png", "rect_art": [0, 52, 96, 48]}

    # 6. flag_banner (36x22 at 100,20): Simple stylized civic striped flag (non-brand generic stars-and-stripes motif)
    fx, fy, fw, fh = 100, 20, 36, 22
    for yy in range(fh):
        stripe_red = ((yy // 2) % 2 == 0)
        for xx in range(fw):
            if xx < 14 and yy < 12:
                col = (46, 68, 118, 255)
                if xx % 3 == 1 and yy % 3 == 1:
                    col = (232, 228, 218, 255)
            else:
                col = (172, 54, 52, 255) if stripe_red else (234, 228, 214, 255)
            c.put(fx + xx, fy + yy, col)
    c.border(fx, fy, fw, fh, outline, 1)
    regions["flag_banner"] = {"sheet": "sign_plates.png", "rect_art": [100, 20, 36, 22]}

    c.save_2x(TEX_DIR / "sign_plates.png")
    return regions


def make_clock_dials_and_lamps():
    """
    Produces:
    1) clock_dial_lit.png + clock_dial_mask.png (44x44 art px -> 88x88 texels):
       Crisp pixel clock face with Roman/tick hour markers; mask is PURE BLACK outside the glowing dial glass!
    2) clock_dial_dark.png (44x44 art px): Weathered, grimy, UNLIT 1988 clock dial (zero emissive!).
    3) glow_lamps_base.png + glow_lamps_mask.png (32x32 art px -> 64x64 texels):
       Gas lantern amber glass (0,0,16,16), cobra-head cool sodium lens (16,0,16,16),
       restored warm LED acorn globe (0,16,16,16), tower floodlight lens (16,16,16,16).
       Mask is PURE BLACK everywhere except the glowing bulb cores!
    """
    outline = (44, 46, 58, 255)
    stone_rim = (138, 134, 126, 255)
    black = (0, 0, 0, 255)

    for is_lit, base_name, mask_name in [
        (True, "clock_dial_lit.png", "clock_dial_mask.png"),
        (False, "clock_dial_dark.png", None),
    ]:
        cb = ArtCanvas(44, 44, stone_rim)
        cm = ArtCanvas(44, 44, black)
        cx, cy = 21.5, 21.5
        # Outer stone bezel & dark cast-iron ring
        cb.border(0, 0, 44, 44, outline, 1)
        cb.circle(cx, cy, 19.5, outline, filled=True)
        cb.circle(cx, cy, 18.0, (246, 238, 208, 255) if is_lit else (142, 138, 128, 255), filled=True)
        if is_lit:
            cm.circle(cx, cy, 18.0, (244, 224, 168, 255), filled=True)
        # Inner subtle dial track ring
        cb.circle(cx, cy, 12.5, (186, 178, 152, 255) if is_lit else (110, 108, 102, 255), filled=False)
        # 12 crisp hour tick marks (bolder at 12, 3, 6, 9)
        for h in range(12):
            ang = math.radians(h * 30.0 - 90.0)
            r_in = 12.5 if h % 3 == 0 else 14.0
            r_out = 17.0
            for step in range(8):
                r = r_in + (r_out - r_in) * (step / 7.0)
                tx = int(round(cx + math.cos(ang) * r))
                ty = int(round(cy + math.sin(ang) * r))
                cb.put(tx, ty, outline)
                if is_lit:
                    cm.put(tx, ty, (38, 36, 44, 255))
                if h % 3 == 0:
                    cb.put(tx + 1, ty, outline)
                    if is_lit:
                        cm.put(tx + 1, ty, (38, 36, 44, 255))
        # Center bronze boss pivot
        cb.circle(cx, cy, 2.0, outline, filled=True)
        if is_lit:
            cm.circle(cx, cy, 2.0, black, filled=True)
        else:
            # Subtle water grime streaks on dark 1988 dial
            for gx in (14, 24, 30):
                for gy in range(8, 34):
                    if (gx + gy) % 3 == 0:
                        cb.put(gx, gy, (112, 110, 104, 255))
        cb.save_2x(TEX_DIR / base_name)
        if mask_name:
            cm.save_2x(TEX_DIR / mask_name)

    # Lamps atlas (32x32 art px -> 4 strict 16x16 quadrants)
    lb = ArtCanvas(32, 32, outline)
    lm = ArtCanvas(32, 32, black)

    # Q0 (0,0): Gas lantern (warm amber flame core inside dark iron frame)
    lb.rect(2, 2, 12, 12, (252, 212, 124, 255))
    lb.border(2, 2, 12, 12, outline, 1)
    lm.rect(3, 3, 10, 10, (255, 198, 96, 255))

    # Q1 (16,0): 1962/1988 Cobra-head electric lamp lens (cool pale sodium/mercury)
    lb.rect(18, 2, 12, 12, (226, 236, 248, 255))
    lb.border(18, 2, 12, 12, outline, 1)
    lm.rect(19, 3, 10, 10, (214, 228, 252, 255))

    # Q2 (0,16): 2008 Restored warm heritage acorn globe
    lb.rect(2, 18, 12, 12, (255, 236, 178, 255))
    lb.border(2, 18, 12, 12, outline, 1)
    lm.rect(3, 19, 10, 10, (255, 224, 148, 255))

    # Q3 (16,16): 2008 Tower warm floodlight lamp head
    lb.rect(18, 18, 12, 12, (255, 244, 206, 255))
    lb.border(18, 18, 12, 12, outline, 1)
    lm.rect(19, 19, 10, 10, (255, 236, 176, 255))

    lb.save_2x(TEX_DIR / "glow_lamps_base.png")
    lm.save_2x(TEX_DIR / "glow_lamps_mask.png")

    return {
        "lamp_gas": {"sheet": "glow_lamps_base.png", "rect_art": [0, 0, 16, 16]},
        "lamp_cobra": {"sheet": "glow_lamps_base.png", "rect_art": [16, 0, 16, 16]},
        "lamp_acorn": {"sheet": "glow_lamps_base.png", "rect_art": [0, 16, 16, 16]},
        "lamp_flood": {"sheet": "glow_lamps_base.png", "rect_art": [16, 16, 16, 16]},
    }


def main():
    swatches, atlas_meta, atlas_rows = extract_limezu_swatches()
    make_rusticated_stone()
    make_copper_spire(atlas_meta, atlas_rows)
    make_lawn_turf()
    make_interior_wall()
    detail_clean = make_detail_atlas(stained_1988=False)
    detail_1988 = make_detail_atlas(stained_1988=True)
    glass_regions = make_glass_atlas()
    sign_regions = make_sign_plates()
    lamp_regions = make_clock_dials_and_lamps()

    manifest = {
        "shared_swatches": swatches,
        "regions": {
            "detail_clean": detail_clean,
            "detail_1988": detail_1988,
            "glass": glass_regions,
            "signs": sign_regions,
            "lamps": lamp_regions,
        },
    }
    (R / "art-regions.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print("Generated Harlan County Courthouse swatches, stone/copper/lawn/interior art, clock dials, plates, and pure-black emissive masks.")


if __name__ == "__main__":
    main()
