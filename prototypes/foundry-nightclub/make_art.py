#!/usr/bin/env python3
"""
Texture and swatch generator for THE FOUNDRY — Adaptive-Reuse Industrial-Brutalist
Sawtooth-Roof Modern Nightclub & Open-Air Courtyard Venue.
Follows docs/CHATGPT_MODEL_GUIDE.md and kotlin-street/docs/LANDMARKS_GLB.md:
- Extracts 7 shared LimeZu swatches directly from kotlin-street/tools/swatches/atlas/atlas.png
- Paints 5 bespoke modern industrial-brutalist pixel-art textures at 1x art resolution
  and enlarges 2x via NEAREST so every single texture is made of exact 2x2 texel blocks
  (1 texel = 0.0388 m, 1 art pixel = 0.0776 m).
- Writes textures/*.png, source-materials/*.png + provenance.json, and art-regions.json.
"""
import json
import struct
import zlib
from pathlib import Path

from pixel_font import FONT

R = Path(__file__).resolve().parent
REPO_ROOT = R.parent.parent
ATLAS_PNG = REPO_ROOT / "kotlin-street" / "tools" / "swatches" / "atlas" / "atlas.png"
ATLAS_JSON = REPO_ROOT / "kotlin-street" / "tools" / "swatches" / "atlas" / "atlas.json"

TEX_DIR = R / "textures"
SRC_DIR = R / "source-materials"
TEX_DIR.mkdir(parents=True, exist_ok=True)
SRC_DIR.mkdir(parents=True, exist_ok=True)


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
    """1x art-pixel canvas that exports at 2x NEAREST so every art pixel is an exact 2x2 texel block."""

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

    def draw_text(self, cx, cy, text, fg, shadow=None, spacing=1, scale=1):
        text = text.upper()
        total_w = len(text) * (5 * scale) + max(0, len(text) - 1) * (spacing * scale)
        x0 = cx - total_w // 2
        y0 = cy - (7 * scale) // 2
        for i, ch in enumerate(text):
            glyph = FONT.get(ch, FONT[" "])
            gx0 = x0 + i * (5 + spacing) * scale
            for gy, row in enumerate(glyph):
                for gx, bit in enumerate(row):
                    if bit == "1":
                        if shadow is not None:
                            self.rect(gx0 + gx * scale + 1, y0 + gy * scale + 1, scale, scale, shadow)
                        self.rect(gx0 + gx * scale, y0 + gy * scale, scale, scale, fg)

    def draw_vertical_text(self, cx, y_start, text, fg, shadow=None, row_step=8):
        text = text.upper()
        for i, ch in enumerate(text):
            glyph = FONT.get(ch, FONT[" "])
            gx0 = cx - 2
            gy0 = y_start + i * row_step
            for gy, row in enumerate(glyph):
                for gx, bit in enumerate(row):
                    if bit == "1":
                        if shadow is not None:
                            self.put(gx0 + gx + 1, gy0 + gy + 1, shadow)
                        self.put(gx0 + gx, gy0 + gy, fg)

    def save_2x(self, path):
        out = []
        for y in range(self.h):
            row = self.px[y]
            scaled_row = []
            for x in range(self.w):
                c = row[x]
                scaled_row.append(c)
                scaled_row.append(c)
            out.append(scaled_row)
            out.append(list(scaled_row))
        write_png_rgba(path, self.w * 2, self.h * 2, out)


# ==============================================================================
# 1. EXTRACT 7 SHARED LIMEZU SWATCHES DIRECTLY FROM ATLAS.PNG
# ==============================================================================
SHARED_SWATCHES = [
    ("brick_red", "Heritage industrial warehouse brick facade, courtyard walls, and side elevations"),
    ("wall_slate_light", "Raw architectural concrete for brutalist entrance gantry, vertical light tower, and planters"),
    ("block_courses", "Acoustic concrete-block rear warehouse shell and backstage stairwell core"),
    ("roof_slate", "Dark architectural slate / standing-seam pitched sawtooth roof slopes"),
    ("plank_horizontal", "Warm reclaimed timber decking for the open-air front courtyard lounge and rear VIP mezzanine"),
    ("asphalt_worn", "Industrial rear load-in lane and side service drive asphalt"),
    ("pavement", "Polished concrete front courtyard plaza, loading dock deck, and main warehouse hall floor"),
]


def extract_swatches():
    atlas_meta = json.loads(ATLAS_JSON.read_text(encoding="utf-8"))
    aw, ah, arows = read_png_rgba(ATLAS_PNG)
    provenance = {
        "source_atlas": "kotlin-street/tools/swatches/atlas/atlas.png",
        "atlas_size": [aw, ah],
        "swatches": [],
    }
    for name, role in SHARED_SWATCHES:
        info = atlas_meta[name]
        x, y, w, h = info["x"], info["y"], info["w"], info["h"]
        sub = [arows[y + yy][x:x + w] for yy in range(h)]
        for yy in range(0, h, 2):
            for xx in range(0, w, 2):
                c = sub[yy][xx]
                assert sub[yy][xx + 1] == c and sub[yy + 1][xx] == c and sub[yy + 1][xx + 1] == c, (
                    f"Swatch {name} failed 2x2 check at ({xx},{yy})"
                )
        write_png_rgba(SRC_DIR / f"{name}.png", w, h, sub)
        write_png_rgba(TEX_DIR / f"{name}.png", w, h, sub)
        provenance["swatches"].append({
            "id": name,
            "size": [w, h],
            "atlas_rect": [x, y, w, h],
            "role": role,
            "entry": info.get("entry", {}),
        })
    (SRC_DIR / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")


# ==============================================================================
# 2. PAINT 5 BESPOKE MODERN INDUSTRIAL-BRUTALIST TEXTURES (2x2 GRID)
# ==============================================================================
def make_corten_steel():
    """64x64 art px -> 128x128 texels: Blackened structural architectural steel & ribbed factory cladding with coral/mint reveal."""
    c = ArtCanvas(64, 64, (30, 33, 38, 255))
    dk = (22, 24, 28, 255)
    md = (36, 40, 46, 255)
    hi = (52, 57, 66, 255)
    rivet = (74, 80, 90, 255)
    coral = (218, 62, 68, 255)
    mint = (46, 178, 146, 255)

    for y in range(64):
        for x in range(64):
            mod = x % 8
            if mod == 0:
                col = dk
            elif mod == 1:
                col = hi
            elif mod in (2, 3, 4):
                col = md
            else:
                col = (28, 31, 36, 255)
            if y % 16 == 0:
                col = dk
            elif y % 16 == 1 and mod in (2, 3, 4):
                col = hi
            # Subtle industrial steel plate rivets
            if y % 16 == 3 and x % 8 == 3:
                col = rivet
            c.put(x, y, col)

    # Crisp contemporary laser-coral & mint-teal architectural shadow-reveal band
    c.rect(0, 30, 64, 2, coral)
    c.rect(0, 32, 64, 1, dk)
    c.rect(0, 33, 64, 1, mint)
    c.save_2x(TEX_DIR / "corten_steel.png")


def make_brutalist_trim():
    """64x64 art px -> 128x128 texels: Fluted raw architectural concrete & blackened steel gantry coping."""
    c = ArtCanvas(64, 64, (132, 134, 136, 255))
    steel = (28, 30, 34, 255)
    c_dk = (98, 100, 104, 255)
    c_md = (136, 138, 142, 255)
    c_hi = (168, 170, 174, 255)
    coral_pin = (232, 66, 72, 255)

    for y in range(64):
        for x in range(64):
            # Board-formed architectural concrete horizontal tie-rod & flute rhythm
            if y % 8 == 0:
                col = c_dk
            elif y % 8 == 1:
                col = c_hi
            else:
                v = (x * 11 + y * 7) % 13
                col = c_md if v < 6 else (126, 128, 132, 255)
            if x % 32 == 0:
                col = steel
            elif x % 32 == 1:
                col = c_hi
            # Board-form tie-hole detail
            if x % 16 == 8 and y % 8 == 4:
                col = (72, 74, 78, 255)
            c.put(x, y, col)

    # Recessed blackened-steel architectural reveals + razor-thin coral light trim
    c.rect(0, 27, 64, 3, steel)
    c.rect(0, 30, 64, 2, coral_pin)
    c.rect(0, 32, 64, 2, steel)
    c.save_2x(TEX_DIR / "concrete_rib.png")


def make_industrial_glass():
    """256x128 art px -> 512x256 texels (BLEND): Black-steel Crittall factory windows, sawtooth clerestories & 360 DJ shield."""
    c = ArtCanvas(256, 128, (0, 0, 0, 0))
    steel = (22, 24, 28, 255)
    steel_hi = (58, 64, 74, 255)
    glass_bg = (34, 54, 62, 148)
    glass_hi = (94, 152, 164, 176)
    glass_spec = (176, 232, 238, 198)
    sky_glow = (128, 224, 206, 168)
    sign_bg = (18, 20, 24, 236)
    coral_txt = (255, 86, 94, 255)
    mint_txt = (74, 246, 196, 255)
    white_txt = (242, 248, 252, 255)

    def draw_crittall(rx, ry, rw, rh, label=None, txt_col=coral_txt, cols=6, rows=3, warm_sky=False):
        base = (44, 78, 86, 156) if warm_sky else glass_bg
        c.rect(rx, ry, rw, rh, base)
        for yy in range(ry + 1, ry + rh - 1):
            for xx in range(rx + 1, rx + rw - 1):
                d = (xx - rx) + (yy - ry)
                if d % 20 in (4, 5):
                    c.put(xx, yy, sky_glow if warm_sky else glass_hi)
                elif d % 20 == 6:
                    c.put(xx, yy, glass_spec)
        # Authentic industrial Crittall black-steel grid mullions
        for ci in range(1, cols):
            mx = rx + (rw * ci) // cols
            c.rect(mx, ry, 1, rh, steel)
        for ri in range(1, rows):
            my = ry + (rh * ri) // rows
            c.rect(rx, my, rw, 1, steel)
        c.border(rx, ry, rw, rh, steel, 2)
        c.rect(rx + 2, ry + 2, rw - 4, 1, steel_hi)
        if label:
            bw = min(rw - 8, len(label) * 6 + 6)
            bh = 11
            bx = rx + (rw - bw) // 2
            by = ry + (rh - bh) // 2
            c.rect(bx, by, bw, bh, sign_bg)
            c.border(bx, by, bw, bh, coral_txt if txt_col == mint_txt else mint_txt, 1)
            c.draw_text(rx + rw // 2, ry + rh // 2, label, txt_col, shadow=(10, 12, 16, 255))

    regions = {}

    # 1. front_left (72x32): Courtyard bar service & industrial Crittall window ("COURTYARD")
    draw_crittall(0, 0, 72, 32, "COURTYARD", mint_txt, cols=6, rows=3)
    regions["front_left"] = {"sheet": "glass.png", "rect_art": [0, 0, 72, 32]}

    # 2. front_right (72x32): Main warehouse factory Crittall window ("LIVE SETS")
    draw_crittall(74, 0, 72, 32, "LIVE SETS", coral_txt, cols=6, rows=3)
    regions["front_right"] = {"sheet": "glass.png", "rect_art": [74, 0, 72, 32]}

    # 3. entry (32x36): Heavy steel-framed double industrial glass warehouse doors
    draw_crittall(148, 0, 32, 36, None, white_txt, cols=2, rows=3)
    c.rect(148 + 13, 14, 1, 10, coral_txt)
    c.rect(148 + 18, 14, 1, 10, coral_txt)
    regions["entry"] = {"sheet": "glass.png", "rect_art": [148, 0, 32, 36]}

    # 4. transom (32x12): Steel-mullion transom window over main doors
    draw_crittall(182, 0, 32, 12, None, white_txt, cols=4, rows=1)
    regions["transom"] = {"sheet": "glass.png", "rect_art": [182, 0, 32, 12]}

    # 5. clerestory (128x16): Long north-light sawtooth skylight glazing band
    draw_crittall(0, 36, 128, 16, None, mint_txt, cols=16, rows=1, warm_sky=True)
    regions["clerestory"] = {"sheet": "glass.png", "rect_art": [0, 36, 128, 16]}

    # 6. side_win (48x32): Side brick elevation Crittall warehouse window ("FOUNDRY")
    draw_crittall(130, 36, 48, 32, "FOUNDRY", white_txt, cols=4, rows=3)
    regions["side_win"] = {"sheet": "glass.png", "rect_art": [130, 36, 48, 32]}

    # 7. dj_shield (48x16): 360-degree Center Boiler-Room DJ Island smoked-glass booth surround ("360 DJ")
    draw_crittall(180, 36, 48, 16, "360 DJ", coral_txt, cols=4, rows=1)
    regions["dj_shield"] = {"sheet": "glass.png", "rect_art": [180, 36, 48, 16]}

    # 8. mezz_rail (64x14): Interior mezzanine & courtyard industrial glass/steel railing
    c.rect(0, 72, 64, 14, (44, 72, 80, 124))
    for yy in range(73, 85):
        for xx in range(1, 63):
            if (xx + yy) % 14 in (3, 4):
                c.put(xx, yy, (132, 206, 212, 160))
    for px in (0, 16, 32, 48, 63):
        c.rect(px, 72, 1, 14, steel)
    c.rect(0, 72, 64, 2, coral_txt)
    c.rect(0, 84, 64, 2, steel)
    regions["mezz_rail"] = {"sheet": "glass.png", "rect_art": [0, 72, 64, 14]}

    # 9. vip_win (48x24): Side elevation acoustic lounge window ("BAR & YARD")
    draw_crittall(66, 72, 48, 24, "BAR", mint_txt, cols=4, rows=2)
    regions["vip_win"] = {"sheet": "glass.png", "rect_art": [66, 72, 48, 24]}

    c.save_2x(TEX_DIR / "glass.png")
    return regions


def make_led_industrial():
    """256x128 art px -> 512x256 texels (EMISSIVE): Laser-crimson, studio-white & mint-emerald brutalist club signage & lighting."""
    c = ArtCanvas(256, 128, (16, 18, 22, 255))
    bg = (16, 18, 22, 255)
    charcoal = (24, 27, 32, 255)
    coral = (255, 62, 74, 255)
    coral_dk = (146, 26, 36, 255)
    mint = (46, 244, 186, 255)
    mint_dk = (18, 112, 86, 255)
    white = (246, 252, 255, 255)
    amber = (255, 194, 86, 255)

    regions = {}

    # 1. gantry_sign (112x16): Monumental "THE FOUNDRY" spanning steel gantry arch header
    c.rect(0, 0, 112, 16, bg)
    c.border(0, 0, 112, 16, coral, 1)
    c.rect(2, 2, 108, 1, mint)
    c.rect(2, 13, 108, 1, mint)
    # Industrial diagonal hazard-accent laser slashes left and right
    for k in range(3):
        c.rect(5 + k * 3, 4, 2, 8, coral if k % 2 == 0 else white)
        c.rect(105 - k * 3, 4, 2, 8, coral if k % 2 == 0 else white)
    c.draw_text(56, 8, "THE FOUNDRY", white, shadow=coral_dk, spacing=1)
    regions["gantry_sign"] = {"sheet": "led_industrial.png", "rect_art": [0, 0, 112, 16]}

    # 2. yard_sign (64x12): Courtyard outdoor bar fascia "YARD BAR"
    c.rect(114, 0, 64, 12, bg)
    c.border(114, 0, 64, 12, mint, 1)
    c.draw_text(114 + 32, 6, "YARD BAR", mint, shadow=mint_dk, spacing=1)
    regions["yard_sign"] = {"sheet": "led_industrial.png", "rect_art": [114, 0, 64, 12]}

    # 3. entry_badge (48x12): Crisp backlit "MAIN HALL" lintel over warehouse doors
    c.rect(180, 0, 48, 12, bg)
    c.border(180, 0, 48, 12, coral, 1)
    c.draw_text(180 + 24, 6, "MAIN HALL", white, shadow=coral_dk, spacing=0)
    regions["entry_badge"] = {"sheet": "led_industrial.png", "rect_art": [180, 0, 48, 12]}

    # 4. lamp (16x4): Linear studio-white & warm-coral industrial LED fixture bar (ny = -1 -> spawns Filament lights!)
    c.rect(232, 0, 16, 4, coral)
    c.rect(233, 1, 14, 2, white)
    regions["lamp"] = {"sheet": "led_industrial.png", "rect_art": [232, 0, 16, 4]}

    # 5. tower_blade (24x64): Vertical Tokyo/Berlin-style architectural blade sign reading "FOUNDRY" vertically!
    bx, by, bw, bh = 232, 18, 24, 64
    c.rect(bx, by, bw, bh, bg)
    c.border(bx, by, bw, bh, coral, 1)
    c.rect(bx + 2, by + 2, 1, bh - 4, mint)
    c.rect(bx + bw - 3, by + 2, 1, bh - 4, mint)
    c.draw_vertical_text(bx + 12, by + 5, "FOUNDRY", white, shadow=coral_dk, row_step=8)
    regions["tower_blade"] = {"sheet": "led_industrial.png", "rect_art": [232, 18, 24, 64]}

    # 6. beacon_panel (32x32): Brutalist illuminated architectural light-cube crown emblem
    qx, qy = 196, 18
    c.rect(qx, qy, 32, 32, bg)
    c.border(qx, qy, 32, 32, coral, 2)
    c.border(qx + 5, qy + 5, 22, 22, mint, 1)
    c.border(qx + 10, qy + 10, 12, 12, white, 1)
    c.rect(qx + 14, qy + 14, 4, 4, coral)
    # Crosshair laser bars
    c.rect(qx + 15, qy + 2, 2, 8, mint)
    c.rect(qx + 15, qy + 22, 2, 8, mint)
    c.rect(qx + 2, qy + 15, 8, 2, mint)
    c.rect(qx + 22, qy + 15, 8, 2, mint)
    regions["beacon_panel"] = {"sheet": "led_industrial.png", "rect_art": [196, 18, 32, 32]}

    # 7. laser_floor (96x64): Dark warehouse concrete dance floor with 360-degree radial laser-grid & center DJ target rings
    fx, fy, fw, fh = 0, 56, 96, 64
    c.rect(fx, fy, fw, fh, (20, 22, 26, 255))
    # Expansion-joint warehouse concrete grid (12x8)
    for yy in range(fy, fy + fh):
        for xx in range(fx, fx + fw):
            if (xx - fx) % 12 == 0 or (yy - fy) % 8 == 0:
                c.put(xx, yy, (30, 34, 40, 255))
    # Outer coral perimeter & inner mint laser track around the 360 DJ island
    c.border(fx + 1, fy + 1, fw - 2, fh - 2, coral, 1)
    c.border(fx + 8, fy + 6, fw - 16, fh - 12, mint, 1)
    c.border(fx + 18, fy + 13, fw - 36, fh - 26, white, 1)
    c.border(fx + 26, fy + 18, fw - 52, fh - 36, coral, 2)
    # Cardinal laser floor conduits leading to the center 360 DJ booth
    c.rect(fx + 2, fy + 31, 24, 2, mint)
    c.rect(fx + fw - 26, fy + 31, 24, 2, mint)
    c.rect(fx + 47, fy + 2, 2, 16, coral)
    c.rect(fx + 47, fy + fh - 18, 2, 16, coral)
    regions["laser_floor"] = {"sheet": "led_industrial.png", "rect_art": [0, 56, 96, 64]}

    # 8. matrix_wall (128x40): Massive Printworks/Berghain-style industrial kinetic light-matrix & strobe tube backdrop
    sx, sy, sw, sh = 100, 56, 128, 40
    c.rect(sx, sy, sw, sh, (14, 16, 20, 255))
    c.border(sx, sy, sw, sh, coral, 1)
    # Vertical array of industrial LED strobe tubes (crimson, studio-white, mint-teal)
    for i in range(20):
        tx = sx + 4 + i * 6
        c.rect(tx, sy + 3, 2, 34, (32, 36, 44, 255))
        if i % 4 == 0:
            c.rect(tx, sy + 4, 2, 32, coral)
            c.rect(tx, sy + 16, 2, 8, white)
        elif i % 4 == 2:
            c.rect(tx, sy + 8, 2, 24, mint)
        else:
            c.rect(tx, sy + 12, 2, 16, white)
    # Horizontal industrial laser beam across full width
    c.rect(sx + 2, sy + 20, sw - 4, 2, coral)
    # Crisp brutalist centered stencil plate "THE FOUNDRY"
    c.rect(sx + 22, sy + 10, 84, 18, (14, 16, 20, 255))
    c.border(sx + 22, sy + 10, 84, 18, white, 1)
    c.border(sx + 24, sy + 12, 80, 14, mint, 1)
    c.draw_text(sx + 64, sy + 19, "THE FOUNDRY", white, shadow=coral_dk, spacing=1)
    regions["matrix_wall"] = {"sheet": "led_industrial.png", "rect_art": [100, 56, 128, 40]}

    # 9. craft_bar (96x24): Industrial backlit emerald-mint & copper-pipe spirits & draft tap wall
    cx, cy, cw, ch = 100, 100, 96, 24
    c.rect(cx, cy, cw, ch, charcoal)
    c.border(cx, cy, cw, ch, mint, 1)
    for yy in range(cy + 2, cy + ch - 2):
        bg_c = (24, 96, 82, 255) if (yy - cy) % 4 < 2 else (34, 128, 108, 255)
        c.rect(cx + 2, yy, cw - 4, 1, bg_c)
    for sy_off in (10, 19):
        c.rect(cx + 2, cy + sy_off, cw - 4, 2, amber)
        for bi in range(18):
            bxx = cx + 5 + bi * 5
            bcol = (24, 26, 30, 255) if bi % 2 == 0 else (76, 32, 34, 255)
            c.rect(bxx, cy + sy_off - 6, 3, 6, bcol)
            c.rect(bxx + 1, cy + sy_off - 8, 1, 2, white)
    regions["craft_bar"] = {"sheet": "led_industrial.png", "rect_art": [100, 100, 96, 24]}

    # 10. courtyard_light (64x16): Warm architectural exterior LED light-ribbon banner ("OPEN AIR")
    ox, oy = 0, 20
    c.rect(ox, oy, 64, 16, bg)
    c.border(ox, oy, 64, 16, mint, 1)
    c.rect(ox + 2, oy + 2, 60, 2, coral)
    c.draw_text(ox + 32, oy + 10, "OPEN AIR", white, shadow=mint_dk, spacing=1)
    regions["courtyard_light"] = {"sheet": "led_industrial.png", "rect_art": [0, 20, 64, 16]}

    c.save_2x(TEX_DIR / "led_industrial.png")
    return regions


def make_utility_atlas():
    """128x128 art px -> 256x256 texels: Four exact 64x64 art-pixel quadrants."""
    c = ArtCanvas(128, 128, (26, 28, 32, 255))

    # Quadrant 0,0 (steel): Gunmetal structural I-beams, steel truss catwalks, industrial HVAC & roll-up doors
    for y in range(64):
        for x in range(64):
            base = 76 + ((x * 5 + y * 3) % 14)
            if y % 16 == 0 or x % 16 == 0:
                col = (44, 48, 54, 255)
            elif y % 16 == 1:
                col = (118, 124, 134, 255)
            else:
                col = (base, base + 4, base + 8, 255)
            c.put(x, y, col)

    # Quadrant 64,0 (speaker): Matte-black industrial sub-bass & line-array enclosures
    for y in range(64):
        for x in range(64, 128):
            lx = x - 64
            if lx % 4 == 0 or y % 4 == 0:
                col = (20, 22, 26, 255)
            elif (lx + y) % 2 == 0:
                col = (34, 36, 42, 255)
            else:
                col = (26, 28, 34, 255)
            c.put(x, y, col)

    # Quadrant 0,64 (coral): Oxblood-coral architectural leather lounge seating & DJ accents
    for y in range(64, 128):
        ly = y - 64
        for x in range(64):
            if ly % 8 == 0 or x % 8 == 0:
                col = (88, 28, 32, 255)
            elif ly % 8 == 1 or x % 8 == 1:
                col = (168, 56, 62, 255)
            else:
                col = (132, 42, 46, 255)
            c.put(x, y, col)

    # Quadrant 64,64 (concrete_dark): Deep poured charcoal architectural concrete acoustic ceiling & slabs
    for y in range(64, 128):
        ly = y - 64
        for x in range(64, 128):
            lx = x - 64
            if ly % 8 == 0:
                col = (22, 24, 28, 255)
            elif ly % 8 == 1:
                col = (48, 52, 58, 255)
            elif lx % 24 == 0:
                col = (30, 32, 38, 255)
            else:
                col = (34, 38, 44, 255)
            c.put(x, y, col)

    c.save_2x(TEX_DIR / "utility.png")
    return {
        "steel": {"sheet": "utility.png", "rect_art": [0, 0, 64, 64]},
        "speaker": {"sheet": "utility.png", "rect_art": [64, 0, 64, 64]},
        "coral": {"sheet": "utility.png", "rect_art": [0, 64, 64, 64]},
        "concrete_dark": {"sheet": "utility.png", "rect_art": [64, 64, 64, 64]},
    }


def main():
    extract_swatches()
    make_corten_steel()
    make_brutalist_trim()
    regions = {}
    regions.update(make_industrial_glass())
    regions.update(make_led_industrial())
    regions.update(make_utility_atlas())
    (R / "art-regions.json").write_text(json.dumps(regions, indent=2) + "\n", encoding="utf-8")
    print("Generated 12 THE FOUNDRY industrial modern nightclub textures and art-regions.json successfully.")


if __name__ == "__main__":
    main()
