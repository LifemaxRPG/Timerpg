#!/usr/bin/env python3
"""
Texture and LimeZu swatch generator for the Small-Town Fire Station across two eras:
  - 1924 ("Engine Co. No. 2"): classic two-bay red-brick firehouse with hose-drying tower
  - 1988 ("Station No. 2"): modern municipal two-bay fire station on the same lot & curb

Follows docs/CHATGPT_MODEL_GUIDE.md strictly:
- Slices 7 shared LimeZu swatches directly from kotlin-street/tools/swatches/atlas/atlas.png
  (brick_red, block_courses, roof_slate, ballast, roof_membrane_grey, asphalt_worn, pavement).
- Paints bespoke textures on exact 2x2 texel blocks (0.0388 m/texel -> 0.0776 m/art px).
- Enforces strict text fit assertions so NO lettering ever overflows its sign or stone tablet.
- Uses pure-black emissive masks so ONLY lamps, red beacon globes, and the modest 1988
  municipal sign glow at night (never walls, roofs, or floors).
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
# 1. EXTRACT 7 SHARED LIMEZU SWATCHES FROM ATLAS.PNG
# ==============================================================================
SHARED_SWATCHES = [
    ("brick_red", "1924 brick firehouse walls, parapet, and hose-drying tower"),
    ("block_courses", "1988 modern split-face concrete block walls and upper crew wing"),
    ("roof_slate", "1924 pyramidal slate roof on the hose-drying tower"),
    ("ballast", "Flat tar-and-gravel main roof decks on both 1924 and 1988 stations"),
    ("roof_membrane_grey", "Parapet coping, metal flashing, and modern entrance canopy soffit"),
    ("asphalt_worn", "East employee parking lot and rear apparatus service lane"),
    ("pavement", "Front concrete apparatus apron, sidewalks, curb, and clean interior bay floor"),
]


def force_2x2_art_blocks(rows, w, h):
    """Ensure sliced swatches have strict even dims and clean 2x2 art-pixel blocks."""
    # Note: LimeZu atlas swatches are already 2x2 art-pixel aligned; verify/snap cleanly
    w2 = (w // 2) * 2
    h2 = (h // 2) * 2
    out = [[None] * w2 for _ in range(h2)]
    for y in range(0, h2, 2):
        for x in range(0, w2, 2):
            c = rows[y][x]
            out[y][x] = c
            out[y][x + 1] = c
            out[y + 1][x] = c
            out[y + 1][x + 1] = c
    return w2, h2, out


def extract_swatches():
    atlas_meta = json.loads(ATLAS_JSON.read_text(encoding="utf-8"))
    aw, ah, arows = read_png_rgba(ATLAS_PNG)
    used = []
    for name, role in SHARED_SWATCHES:
        info = atlas_meta[name]
        x, y, w, h = info["x"], info["y"], info["w"], info["h"]
        sub = [arows[y + yy][x:x + w] for yy in range(h)]
        # Make sure brick_red repeats cleanly at its seamless width (30 art px = 60 texels)
        w2, h2, snapped = force_2x2_art_blocks(sub, w, h)
        write_png_rgba(TEX_DIR / f"{name}.png", w2, h2, snapped)
        used.append({"id": name, "file": f"{name}.png", "size": [w2, h2], "role": role})
    return used


# ==============================================================================
# 2. BESPOKE 2x2 PIXEL-ART TEXTURES (LIMEZU WARM PALETTE)
# ==============================================================================
def make_stone_trim():
    """Carved pale Indiana limestone for 1924 belt courses, sills, quoins & 1988 trim (64x64 texels = 32x32 art)."""
    c = ArtCanvas(32, 32, (206, 198, 180, 255))
    light = (224, 217, 200, 255)
    mid = (204, 196, 178, 255)
    mid2 = (192, 183, 165, 255)
    shade = (164, 154, 138, 255)
    dark = (92, 84, 72, 255)

    for y in range(32):
        for x in range(32):
            # Subtle 3-step stone dither & ashlar courses every 8 art px
            h = (x * 37 + y * 91) & 15
            col = mid if h < 9 else (mid2 if h < 14 else light)
            c.put(x, y, col)
    for row in range(0, 32, 8):
        c.rect(0, row, 32, 1, dark)
        c.rect(0, row + 1, 32, 1, light)
        c.rect(0, row + 7, 32, 1, shade)
        off = 0 if (row // 8) % 2 == 0 else 16
        for x in (off, (off + 16) % 32):
            c.rect(x, row, 1, 8, dark)
            c.put((x + 1) % 32, row + 2, light)
    # Small authentic wear specks
    for wx, wy in [(5, 4), (21, 12), (11, 20), (26, 28)]:
        c.put(wx, wy, shade)
    c.save_2x(TEX_DIR / "stone_trim.png")


def make_glass(year):
    """Alpha-blended glass for windows, carriage door transoms, and modern bay panels (64x64 texels = 32x32 art)."""
    c = ArtCanvas(32, 32, (56, 82, 108, 205))
    frame = (46, 38, 34, 255) if year == 1924 else (64, 72, 82, 255)
    mull = (74, 62, 54, 255) if year == 1924 else (124, 134, 144, 255)
    pane = (52, 78, 104, 198) if year == 1924 else (62, 92, 120, 195)
    hi = (146, 178, 204, 215)
    lo = (36, 54, 76, 212)

    c.rect(0, 0, 32, 32, pane)
    # Diagonal pixel reflection streaks
    for i in range(32):
        for dx in (0, 1):
            if (i + dx) < 32:
                if (i % 16) in (3, 4):
                    c.put((i + dx) % 32, i, hi)
                elif (i % 16) in (12,):
                    c.put((i + dx) % 32, i, lo)
    # Outer dark frame + inner mullions (6-over-6 feel for 1924, crisp modern grid for 1988)
    c.border(0, 0, 32, 32, frame, 2)
    if year == 1924:
        c.rect(0, 15, 32, 2, frame)
        c.rect(10, 2, 1, 28, mull)
        c.rect(21, 2, 1, 28, mull)
        c.rect(2, 8, 28, 1, mull)
        c.rect(2, 23, 28, 1, mull)
    else:
        c.rect(0, 15, 32, 2, mull)
        c.rect(15, 2, 2, 28, mull)
    c.save_2x(TEX_DIR / f"glass_{year}.png")


def make_doors_and_details(year):
    """
    256x256 texels (128x128 art px) detail atlas with strict non-overlapping regions:
      - bay_door:       [0, 0, 64, 56]     (apparatus bay door leaf / roll-up door)
      - entry_door:     [64, 0, 24, 40]    (pedestrian main / back door)
      - louver_vent:    [88, 0, 40, 40]    (hose-tower wood louvers / modern mechanical louvers)
      - red_metal:      [64, 40, 32, 24]   (fire hydrant, call box, modern red fascia trim)
      - dark_metal:     [96, 40, 32, 24]   (steel service door, HVAC, radio mast, generator, bollards)
      - brass_gold:     [0, 56, 32, 24]    (brass fire pole, door hardware, plaque border)
      - wood_trim:      [32, 56, 32, 24]   (bench wood, hose rack, timber trim)
      - yellow_stripe:  [64, 56, 64, 24]   (apron safety stripes, yellow bollards, curb paint)
      - interior_wall:  [0, 80, 64, 48]    (clean tiled/painted firehouse interior shell wall)
      - dumpster_green: [64, 80, 64, 48]   (municipal rear dumpster, utility panels, generator grille)
    """
    c = ArtCanvas(128, 128, (96, 94, 90, 255))
    outline = (36, 30, 28, 255)

    # 1) bay_door [0, 0, 64, 56]
    if year == 1924:
        # Classic 1920s deep-crimson double panelled timber carriage doors with upper window band
        red_hi = (178, 62, 54, 255)
        red_mid = (148, 44, 40, 255)
        red_lo = (112, 30, 28, 255)
        cream = (224, 214, 192, 255)
        c.rect(0, 0, 64, 56, red_mid)
        c.border(0, 0, 64, 56, outline, 2)
        c.rect(31, 0, 2, 56, outline)  # center leaf seam
        for lx in (2, 33):
            lw = 29
            # Highlight top edge
            c.rect(lx, 2, lw, 1, red_hi)
            # Upper glazed sash area (painted dark glass + white muntins in door texture)
            c.rect(lx + 3, 4, lw - 6, 16, outline)
            c.rect(lx + 4, 5, lw - 8, 14, (54, 78, 102, 255))
            c.rect(lx + 4, 11, lw - 8, 2, cream)
            for mx in (lx + 10, lx + 18):
                c.rect(mx, 5, 2, 14, cream)
            c.rect(lx + 5, 6, 3, 2, (132, 164, 190, 255))
            # Lower recessed raised timber panels
            for py in (23, 39):
                c.rect(lx + 3, py, lw - 6, 13, red_lo)
                c.border(lx + 3, py, lw - 6, 13, outline, 1)
                c.rect(lx + 4, py + 1, lw - 8, 1, red_hi)
                c.rect(lx + 5, py + 2, lw - 10, 9, red_mid)
            # Iron strap hinges
            for hy in (10, 32, 48):
                c.rect(lx, hy, 5, 2, (48, 46, 48, 255))
            # Brass handle near center seam
            hx = lx + lw - 4 if lx == 2 else lx + 2
            c.rect(hx, 34, 2, 5, (216, 182, 92, 255))
    else:
        # 1988 modern sectional overhead fire-bay roll-up door (white/red aluminum + wide vision glass row)
        white_hi = (228, 226, 220, 255)
        white_mid = (202, 200, 194, 255)
        white_lo = (168, 168, 164, 255)
        red_band = (176, 48, 42, 255)
        red_hi = (204, 72, 62, 255)
        c.rect(0, 0, 64, 56, white_mid)
        c.border(0, 0, 64, 56, outline, 2)
        # 5 horizontal roll-up sections (11 art px each)
        for si in range(5):
            sy = 2 + si * 11
            sh = 10 if si < 4 else 9
            if si in (0, 3, 4):
                base_c = red_band if si >= 3 else white_mid
                hi_c = red_hi if si >= 3 else white_hi
                lo_c = (132, 34, 30, 255) if si >= 3 else white_lo
                c.rect(2, sy, 60, sh, base_c)
                c.rect(2, sy, 60, 1, hi_c)
                c.rect(2, sy + sh - 1, 60, 1, lo_c)
                # Ribbed steel panel grooves
                for gx in range(8, 60, 12):
                    c.rect(gx, sy + 1, 1, sh - 2, lo_c)
            else:
                # Middle 2 vision-glass sectional rows
                c.rect(2, sy, 60, sh, white_mid)
                c.rect(2, sy, 60, 1, white_hi)
                for px in (4, 19, 34, 49):
                    c.rect(px, sy + 2, 11, sh - 3, outline)
                    c.rect(px + 1, sy + 3, 9, sh - 5, (64, 96, 124, 255))
                    c.rect(px + 2, sy + 3, 3, 1, (148, 180, 206, 255))
            c.rect(2, sy + sh, 60, 1, outline)

    # 2) entry_door [64, 0, 24, 40]
    if year == 1924:
        oak_hi = (156, 108, 64, 255)
        oak_mid = (122, 80, 46, 255)
        oak_lo = (86, 54, 30, 255)
        c.rect(64, 0, 24, 40, oak_mid)
        c.border(64, 0, 24, 40, outline, 2)
        c.rect(66, 2, 20, 1, oak_hi)
        # Upper glass transom pane
        c.rect(68, 5, 16, 14, outline)
        c.rect(69, 6, 14, 12, (62, 88, 112, 255))
        c.rect(75, 6, 2, 12, oak_hi)
        c.rect(69, 11, 14, 2, oak_hi)
        # Lower carved wood panel + brass handle
        c.rect(68, 22, 16, 14, oak_lo)
        c.border(68, 22, 16, 14, outline, 1)
        c.rect(70, 24, 12, 10, oak_mid)
        c.rect(82, 23, 2, 4, (220, 186, 94, 255))
    else:
        # 1988 brushed bronze-aluminum commercial glass entry door
        alum = (82, 86, 92, 255)
        alum_hi = (124, 130, 138, 255)
        c.rect(64, 0, 24, 40, alum)
        c.border(64, 0, 24, 40, outline, 2)
        c.rect(66, 2, 20, 1, alum_hi)
        c.rect(67, 4, 18, 29, outline)
        c.rect(68, 5, 16, 27, (62, 92, 118, 255))
        c.rect(69, 6, 4, 2, (152, 184, 210, 255))
        c.rect(67, 19, 18, 2, alum_hi)  # push bar
        c.rect(66, 34, 20, 4, alum_hi)  # kickplate

    # 3) louver_vent [88, 0, 40, 40]
    c.rect(88, 0, 40, 40, (74, 72, 70, 255))
    c.border(88, 0, 40, 40, outline, 2)
    slat_hi = (138, 128, 116, 255) if year == 1924 else (154, 160, 166, 255)
    slat_lo = (48, 44, 42, 255)
    for ly in range(3, 37, 3):
        c.rect(90, ly, 36, 1, slat_hi)
        c.rect(90, ly + 1, 36, 1, (96, 92, 88, 255))
        c.rect(90, ly + 2, 36, 1, slat_lo)

    # 4) red_metal [64, 40, 32, 24] (fire hydrant, call box, 1988 red metal fascia)
    c.rect(64, 40, 32, 24, (172, 44, 38, 255))
    c.border(64, 40, 32, 24, outline, 1)
    c.rect(65, 41, 30, 2, (212, 78, 66, 255))
    c.rect(65, 61, 30, 2, (118, 28, 24, 255))
    for rx in range(68, 94, 6):
        c.rect(rx, 43, 1, 18, (144, 34, 30, 255))

    # 5) dark_metal [96, 40, 32, 24] (steel service door, HVAC, radio mast, bollards)
    c.rect(96, 40, 32, 24, (84, 90, 96, 255))
    c.border(96, 40, 32, 24, outline, 1)
    c.rect(97, 41, 30, 2, (124, 132, 140, 255))
    c.rect(97, 61, 30, 2, (54, 58, 64, 255))
    for gx in range(100, 126, 4):
        c.put(gx, 46, (58, 62, 68, 255))
        c.put(gx, 56, (112, 118, 126, 255))

    # 6) brass_gold [0, 56, 32, 24]
    c.rect(0, 56, 32, 24, (198, 162, 74, 255))
    c.border(0, 56, 32, 24, outline, 1)
    c.rect(1, 57, 30, 2, (238, 212, 126, 255))
    c.rect(1, 77, 30, 2, (138, 106, 44, 255))
    c.rect(10, 59, 4, 18, (226, 194, 102, 255))

    # 7) wood_trim [32, 56, 32, 24]
    c.rect(32, 56, 32, 24, (132, 92, 58, 255))
    c.border(32, 56, 32, 24, outline, 1)
    for wy in range(58, 78, 4):
        c.rect(33, wy, 30, 1, (158, 114, 74, 255))
        c.rect(33, wy + 2, 30, 1, (98, 66, 40, 255))

    # 8) yellow_stripe [64, 64, 64, 16] (note: y=64..80 cleanly below red/dark_metal!)
    c.rect(64, 64, 64, 16, (216, 174, 48, 255))
    c.border(64, 64, 64, 16, outline, 1)
    c.rect(65, 65, 62, 2, (242, 208, 92, 255))
    c.rect(65, 77, 62, 2, (162, 124, 30, 255))
    # Hazard wear speckles
    for sx in range(68, 124, 8):
        c.rect(sx, 68, 2, 7, (186, 146, 36, 255))

    # 9) interior_wall [0, 80, 64, 48] (clean painted plaster upper + glazed tile wainscot lower)
    c.rect(0, 80, 64, 28, (212, 206, 192, 255))
    c.rect(0, 108, 64, 2, (132, 48, 44, 255))  # red dado stripe
    c.rect(0, 110, 64, 18, (176, 174, 166, 255))
    for ty in range(110, 128, 6):
        c.rect(0, ty, 64, 1, (142, 140, 134, 255))
    for tx in range(0, 64, 8):
        c.rect(tx, 110, 1, 18, (142, 140, 134, 255))
    for y in range(80, 108):
        for x in range(64):
            if ((x * 19 + y * 31) & 15) == 0:
                c.put(x, y, (196, 190, 176, 255))

    # 10) dumpster_green [64, 80, 64, 48]
    c.rect(64, 80, 64, 48, (58, 92, 72, 255))
    c.border(64, 80, 64, 48, outline, 2)
    c.rect(66, 82, 60, 2, (88, 128, 102, 255))
    for rx in range(72, 124, 10):
        c.rect(rx, 84, 2, 40, (42, 68, 52, 255))
    c.rect(66, 122, 60, 4, (40, 62, 48, 255))

    c.save_2x(TEX_DIR / f"details_{year}.png")
    return {
        "bay_door": [0, 0, 64, 56],
        "entry_door": [64, 0, 24, 40],
        "louver_vent": [88, 0, 40, 40],
        "red_metal": [64, 40, 32, 24],
        "dark_metal": [96, 40, 32, 24],
        "brass_gold": [0, 56, 32, 24],
        "wood_trim": [32, 56, 32, 24],
        "yellow_stripe": [64, 64, 64, 16],
        "interior_wall": [0, 80, 64, 48],
        "dumpster_green": [64, 80, 64, 48],
    }


def make_signs(year):
    """
    256x128 texels (128x64 art px) signage atlas — restrained, era-accurate, strict fit assertions:
      - main_tablet: [0, 0, 128, 20]   (1924 carved stone 'ENGINE CO. NO. 2' / 1988 'FIRE STATION 2')
      - date_stone:  [0, 20, 44, 16]   (1924 carved '1924' / 1988 'EST. 1924')
      - bay1_plate:  [44, 20, 42, 16]  ('BAY 1' / '1')
      - bay2_plate:  [86, 20, 42, 16]  ('BAY 2' / '2')
      - fire_flag:   [0, 36, 64, 28]   (civic small-town pennant / emblem flag)
      - keep_clear:  [64, 36, 64, 28]  (modest painted apron/wall notice 'NO PARKING')
    """
    c = ArtCanvas(128, 64, (196, 188, 170, 255))
    outline = (42, 36, 32, 255)

    if year == 1924:
        # Carved Indiana limestone tablet — NO neon, NO bright plastic!
        stone = (214, 206, 188, 255)
        stone_hi = (232, 225, 210, 255)
        stone_lo = (164, 154, 136, 255)
        incised = (64, 54, 44, 255)
        incised_hi = (228, 222, 206, 255)

        # main_tablet [0, 0, 128, 20]
        c.rect(0, 0, 128, 20, stone)
        c.border(0, 0, 128, 20, outline, 1)
        c.rect(1, 1, 126, 1, stone_hi)
        c.rect(1, 18, 126, 1, stone_lo)
        c.border(3, 3, 122, 14, stone_lo, 1)
        c.draw_text_in_box(4, 4, 120, 12, "ENGINE CO. NO. 2", incised, font="5x7", spacing=1, shadow=incised_hi, pad_x=4, pad_y=2)

        # date_stone [0, 20, 44, 16]
        c.rect(0, 20, 44, 16, stone)
        c.border(0, 20, 44, 16, outline, 1)
        c.rect(1, 21, 42, 1, stone_hi)
        c.border(3, 22, 38, 12, stone_lo, 1)
        c.draw_text_in_box(4, 23, 36, 10, "1924", incised, font="5x7", spacing=1, shadow=incised_hi, pad_x=2, pad_y=1)

        # bay1_plate [44, 20, 36, 12] & bay2_plate [82, 20, 36, 12] — modest dark-red painted iron plates
        for bx, txt in [(44, "BAY 1"), (82, "BAY 2")]:
            c.rect(bx, 20, 36, 12, (124, 34, 30, 255))
            c.border(bx, 20, 36, 12, outline, 1)
            c.border(bx + 1, 21, 34, 10, (216, 182, 92, 255), 1)
            c.draw_text_in_box(bx + 2, 22, 32, 8, txt, (242, 232, 206, 255), font="5x7", spacing=1, shadow=(54, 18, 16, 255), pad_x=1, pad_y=0)
    else:
        # 1988 modern dark-charcoal municipal aluminum sign plates with crisp white/red lettering
        slate = (46, 52, 60, 255)
        slate_hi = (78, 86, 98, 255)
        white = (242, 240, 234, 255)
        red = (188, 48, 42, 255)

        # main_tablet [0, 0, 128, 20]
        c.rect(0, 0, 128, 20, slate)
        c.border(0, 0, 128, 20, outline, 1)
        c.rect(1, 1, 126, 1, slate_hi)
        c.rect(1, 17, 126, 2, red)
        c.draw_text_in_box(4, 3, 120, 13, "FIRE STATION 2", white, font="5x7", spacing=1, shadow=(22, 24, 28, 255), pad_x=4, pad_y=2)

        # date_stone [0, 20, 44, 16]
        c.rect(0, 20, 44, 16, (186, 182, 172, 255))
        c.border(0, 20, 44, 16, outline, 1)
        c.draw_text_in_box(2, 22, 40, 12, "EST.1924", (52, 48, 44, 255), font="3x5", spacing=1, pad_x=2, pad_y=2)

        # bay1_plate [44, 20, 36, 12] & bay2_plate [82, 20, 36, 12]
        for bx, txt in [(44, "BAY 1"), (82, "BAY 2")]:
            c.rect(bx, 20, 36, 12, red)
            c.border(bx, 20, 36, 12, outline, 1)
            c.rect(bx + 1, 21, 34, 1, (218, 86, 76, 255))
            c.draw_text_in_box(bx + 2, 22, 32, 8, txt, white, font="5x7", spacing=1, shadow=(76, 20, 18, 255), pad_x=1, pad_y=0)

    # fire_flag [0, 36, 32, 20] — tasteful muted civic flag (navy & warm cream stripes, exact 32x20 fit!)
    c.rect(0, 36, 32, 20, (46, 68, 104, 255))
    c.border(0, 36, 32, 20, outline, 1)
    for sy in range(38, 54, 4):
        c.rect(12, sy, 18, 2, (166, 54, 48, 255) if ((sy - 38) // 4) % 2 == 0 else (228, 220, 200, 255))
    c.rect(2, 38, 10, 10, (36, 54, 88, 255))
    for dy in (40, 44):
        for dx in (4, 8):
            c.put(dx, dy, (236, 228, 206, 255))

    # keep_clear [64, 36, 64, 28] — modest white/red municipal no-parking sign for driveway
    c.rect(64, 36, 64, 28, (232, 228, 218, 255))
    c.border(64, 36, 64, 28, (168, 44, 38, 255), 2)
    c.draw_text_in_box(66, 38, 60, 11, "FIRE LANE", (162, 38, 34, 255), font="5x7", spacing=1, pad_x=2, pad_y=1)
    c.draw_text_in_box(66, 50, 60, 11, "NO PARKING", (48, 44, 42, 255), font="3x5", spacing=1, pad_x=2, pad_y=1)

    c.save_2x(TEX_DIR / f"signs_{year}.png")
    return {
        "main_tablet": [0, 0, 128, 20],
        "date_stone": [0, 20, 44, 16],
        "bay1_plate": [44, 20, 36, 12],
        "bay2_plate": [82, 20, 36, 12],
        "fire_flag": [0, 36, 32, 20],
        "keep_clear": [64, 36, 64, 28],
    }


def make_glow(year):
    """
    256x64 texels (128x32 art px) glow atlas + pure-black emissive mask:
      - backlit_sign: [0, 0, 88, 16]    (1988 modest backlit 'STATION NO. 2' box; dark/unused in 1924)
      - red_globe:    [0, 20, 16, 12]   (classic glowing red firehouse entrance globe lamps)
      - warm_bulb:    [16, 20, 16, 12]  (small warm porch / rear service sconce bulb)
    """
    base = ArtCanvas(128, 32, (36, 38, 42, 255))
    mask = ArtCanvas(128, 32, (0, 0, 0, 255))
    outline = (34, 30, 28, 255)

    # Backlit sign region [0, 0, 88, 16]
    if year == 1988:
        base.rect(0, 0, 88, 16, (238, 232, 214, 255))
        base.border(0, 0, 88, 16, outline, 2)
        base.rect(2, 13, 84, 1, (182, 44, 38, 255))
        base.draw_text_in_box(2, 2, 84, 11, "STATION NO. 2", (152, 32, 28, 255), font="5x7", spacing=1, pad_x=2, pad_y=1)

        # Emissive mask: glowing warm cream face with crisp dark silhouette letters
        mask.rect(2, 2, 84, 12, (226, 212, 180, 255))
        mask.rect(2, 13, 84, 1, (160, 44, 36, 255))
        mask.draw_text_in_box(2, 2, 84, 11, "STATION NO. 2", (28, 12, 12, 255), font="5x7", spacing=1, pad_x=2, pad_y=1)
    else:
        # 1924 does NOT use backlit plastic signs (carved stone tablet only!) -> keep region pure black in mask
        base.rect(0, 0, 88, 16, (52, 48, 44, 255))

    # Red firehouse globe lantern [0, 20, 16, 12]
    base.rect(0, 20, 16, 12, (198, 48, 40, 255))
    base.border(0, 20, 16, 12, outline, 1)
    base.rect(3, 22, 10, 8, (238, 88, 72, 255))
    base.rect(5, 24, 6, 4, (255, 164, 128, 255))

    mask.rect(2, 21, 12, 10, (228, 56, 42, 255))
    mask.rect(4, 23, 8, 6, (255, 134, 96, 255))

    # Warm porch / service sconce [16, 20, 16, 12]
    base.rect(16, 20, 16, 12, (232, 204, 132, 255))
    base.border(16, 20, 16, 12, outline, 1)
    base.rect(19, 22, 10, 8, (252, 232, 174, 255))

    mask.rect(18, 21, 12, 10, (242, 206, 126, 255))
    mask.rect(20, 23, 8, 6, (255, 238, 186, 255))

    base.save_2x(TEX_DIR / f"glow_base_{year}.png")
    mask.save_2x(TEX_DIR / f"glow_mask_{year}.png")
    return {
        "backlit_sign": [0, 0, 88, 16],
        "red_globe": [0, 20, 16, 12],
        "warm_bulb": [16, 20, 16, 12],
    }


def main():
    shared = extract_swatches()
    make_stone_trim()
    regions = {}
    for year in (1924, 1988):
        make_glass(year)
        det = make_doors_and_details(year)
        sgn = make_signs(year)
        glw = make_glow(year)
        regions[str(year)] = {
            "details": det,
            "signs": sgn,
            "glow": glw,
        }
    manifest = {
        "shared_swatches": shared,
        "regions": regions,
    }
    (R / "art-regions.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print("Generated all Fire Station (1924 & 1988) LimeZu swatches and 2x2 art-pixel textures.")


if __name__ == "__main__":
    main()
