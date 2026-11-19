#!/usr/bin/env python3
"""
Texture and swatch generator for THE FOUNDRY (1938 Historic Working Iron & Steel Factory) —
the original pre-conversion working foundry that shares the exact footprint, red-brick sawtooth
warehouse shell, corner tower, and front yard layout later adapted into THE FOUNDRY nightclub.

Follows docs/CHATGPT_MODEL_GUIDE.md and kotlin-street/docs/LANDMARKS_GLB.md:
- Extracts 7 shared LimeZu swatches directly from kotlin-street/tools/swatches/atlas/atlas.png
- Paints 5 bespoke 1930s-1980s industrial ironworks textures at 1x art resolution and enlarges
  2x via NEAREST so every texture is exact 2x2 texel blocks (1 texel = 0.0388 m, 1 art px = 0.0776 m).
- Writes textures/*.png, source-materials/*.png + provenance.json, and art-regions.json.
"""
import json
import os
import struct
import zlib
from pathlib import Path

from pixel_font import FONT

R = Path(__file__).resolve().parent
REPO_ROOT = R.parent.parent
ATLAS_PNG = R.parent.parent / "swatches" / "atlas" / "atlas.png"
ATLAS_JSON = R.parent.parent / "swatches" / "atlas" / "atlas.json"

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
    ("brick_red", "Original 1938 industrial red-brick foundry walls, smokestack tower, and yard perimeter walls"),
    ("wall_slate_dark", "Soot-darkened cut-stone factory plinths, smokestack collars, and heavy gantry piers"),
    ("block_courses", "Industrial cinderblock rear furnace-bay elevation and loading bay wall"),
    ("roof_tin_brown", "Weathered rusted corrugated-iron pitched sawtooth factory roof slopes"),
    ("plank_horizontal", "Heavy oiled timber foreman's catwalk mezzanine, pallets, and machinist workbenches"),
    ("asphalt_brown", "Oil-stained industrial side truck lane and rear coal/scrap freight yard"),
    ("pavement", "Worn poured-concrete receiving yard, loading dock deck, and factory floor"),
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
# 2. PAINT 5 BESPOKE 1938 HISTORIC WORKING FOUNDRY TEXTURES (2x2 GRID)
# ==============================================================================
def make_riveted_iron():
    """64x64 art px -> 128x128 texels: Heavy weathered dark riveted structural iron with subtle rust & steel sheen."""
    c = ArtCanvas(64, 64, (44, 46, 48, 255))
    dk = (28, 30, 32, 255)
    md = (50, 54, 58, 255)
    hi = (74, 78, 84, 255)
    rust = (112, 62, 40, 255)
    rivet = (96, 100, 106, 255)

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
                col = (40, 43, 46, 255)
            if y % 16 == 0:
                col = dk
            elif y % 16 == 1 and mod in (2, 3, 4):
                col = hi
            # Subtle rust weathering near panel seams
            if y % 16 in (14, 15) and ((x * 7 + y * 5) % 9 < 3):
                col = rust
            # Heavy iron rivets
            if y % 16 in (3, 13) and x % 8 == 3:
                col = rivet
            c.put(x, y, col)

    # Classic industrial horizontal iron gusset seam with faint rust patina
    c.rect(0, 30, 64, 1, dk)
    c.rect(0, 31, 64, 2, rust)
    c.rect(0, 33, 64, 1, dk)
    c.save_2x(TEX_DIR / "riveted_iron.png")


def make_concrete_aged():
    """64x64 art px -> 128x128 texels: Soot-weathered cast-stone coping, lintels & industrial safety-yellow stripe trim."""
    c = ArtCanvas(64, 64, (124, 120, 112, 255))
    dk = (78, 74, 68, 255)
    md = (128, 124, 114, 255)
    hi = (158, 152, 140, 255)
    soot = (64, 62, 58, 255)
    warn_y = (212, 158, 36, 255)
    warn_k = (34, 34, 36, 255)

    for y in range(64):
        for x in range(64):
            if y % 8 == 0:
                col = dk
            elif y % 8 == 1:
                col = hi
            else:
                v = (x * 13 + y * 9) % 15
                col = md if v < 7 else (114, 110, 102, 255)
            if x % 16 == 0:
                col = dk
            if y < 6 and (x * 5 + y) % 7 < 2:
                col = soot
            c.put(x, y, col)

    # Subtle weathered industrial safety-stripe reveal along center band
    for x in range(64):
        for y in range(29, 34):
            c.put(x, y, warn_y if ((x + y) // 4) % 2 == 0 else warn_k)
    c.rect(0, 28, 64, 1, dk)
    c.rect(0, 34, 64, 1, dk)
    c.save_2x(TEX_DIR / "concrete_aged.png")


def make_factory_glass():
    """256x128 art px -> 512x256 texels (BLEND): Soot-dusted steel factory sash windows, clerestories & catwalk rails."""
    c = ArtCanvas(256, 128, (0, 0, 0, 0))
    iron = (34, 36, 38, 255)
    iron_hi = (78, 80, 82, 255)
    rust = (112, 60, 38, 255)
    glass_bg = (68, 76, 72, 152)
    glass_warm = (138, 112, 74, 166)
    glass_hi = (176, 186, 174, 182)
    sign_bg = (30, 34, 30, 236)
    cream = (238, 224, 186, 255)
    amber = (242, 176, 56, 255)
    warn_y = (224, 168, 36, 255)

    def draw_sash(rx, ry, rw, rh, label=None, txt_col=cream, cols=6, rows=3, warm_furnace=False):
        base = glass_warm if warm_furnace else glass_bg
        c.rect(rx, ry, rw, rh, base)
        for yy in range(ry + 1, ry + rh - 1):
            for xx in range(rx + 1, rx + rw - 1):
                d = (xx - rx) + (yy - ry)
                if d % 22 in (5, 6):
                    c.put(xx, yy, glass_hi)
                elif warm_furnace and yy > ry + rh // 2 and (xx + yy) % 5 == 0:
                    c.put(xx, yy, (198, 134, 58, 178))
        # Dense vintage steel factory mullions
        for ci in range(1, cols):
            mx = rx + (rw * ci) // cols
            c.rect(mx, ry, 1, rh, iron)
        for ri in range(1, rows):
            my = ry + (rh * ri) // rows
            c.rect(rx, my, rw, 1, iron)
        c.border(rx, ry, rw, rh, iron, 2)
        c.rect(rx + 2, ry + 2, rw - 4, 1, iron_hi)
        if label:
            bw = min(rw - 8, len(label) * 6 + 6)
            bh = 11
            bx = rx + (rw - bw) // 2
            by = ry + (rh - bh) // 2
            c.rect(bx, by, bw, bh, sign_bg)
            c.border(bx, by, bw, bh, amber if txt_col == cream else cream, 1)
            c.draw_text(rx + rw // 2, ry + rh // 2, label, txt_col, shadow=(16, 14, 12, 255))

    regions = {}

    # 1. front_left (72x32): Factory steel sash window (no lettering: the name is painted on the brick)
    draw_sash(0, 0, 72, 32, None, amber, cols=8, rows=4, warm_furnace=True)
    regions["front_left"] = {"sheet": "glass_factory.png", "rect_art": [0, 0, 72, 32]}

    # 2. front_right (72x32): Factory steel sash window
    draw_sash(74, 0, 72, 32, None, cream, cols=8, rows=4, warm_furnace=True)
    regions["front_right"] = {"sheet": "glass_factory.png", "rect_art": [74, 0, 72, 32]}

    # 3. entry (32x36): Heavy steel-framed double industrial freight doors
    draw_sash(148, 0, 32, 36, None, cream, cols=4, rows=4)
    # Lower riveted steel kickplates on both factory doors
    c.rect(148 + 2, 22, 28, 12, (46, 50, 52, 255))
    c.rect(148 + 15, 2, 2, 32, iron)
    c.rect(148 + 13, 18, 1, 6, warn_y)
    c.rect(148 + 18, 18, 1, 6, warn_y)
    regions["entry"] = {"sheet": "glass_factory.png", "rect_art": [148, 0, 32, 36]}

    # 4. transom (32x12): Steel-mullion transom window over factory entry doors
    draw_sash(182, 0, 32, 12, None, cream, cols=4, rows=2, warm_furnace=True)
    regions["transom"] = {"sheet": "glass_factory.png", "rect_art": [182, 0, 32, 12]}

    # 5. clerestory (128x16): Dusty warm-glowing north-light sawtooth skylight factory glazing
    draw_sash(0, 36, 128, 16, None, amber, cols=16, rows=2, warm_furnace=True)
    regions["clerestory"] = {"sheet": "glass_factory.png", "rect_art": [0, 36, 128, 16]}

    # 6. side_win (48x32): Side brick elevation multi-pane factory sash
    draw_sash(130, 36, 48, 32, None, cream, cols=6, rows=4, warm_furnace=True)
    regions["side_win"] = {"sheet": "glass_factory.png", "rect_art": [130, 36, 48, 32]}

    # 7. slag_shield (48x16): Heavy steel mesh & safety-yellow slag guard around center crucible pit ("HOT IRON")
    c.rect(180, 36, 48, 16, (38, 34, 30, 152))
    for yy in range(37, 51):
        for xx in range(181, 227):
            if (xx + yy) % 4 == 0:
                c.put(xx, yy, (72, 68, 62, 210))
    c.border(180, 36, 48, 16, iron, 2)
    c.rect(180, 36, 48, 2, warn_y)
    c.rect(182, 40, 44, 9, sign_bg)
    c.draw_text(204, 44, "HOT IRON", amber, shadow=(16, 12, 10, 255), spacing=0)
    regions["slag_shield"] = {"sheet": "glass_factory.png", "rect_art": [180, 36, 48, 16]}

    # 8. mezz_rail (64x14): Industrial iron pipe & safety-yellow foreman's catwalk guardrail (open steel bars!)
    c.rect(0, 72, 64, 14, (0, 0, 0, 0))
    c.rect(0, 72, 64, 2, warn_y)       # safety-yellow top pipe handrail
    c.rect(0, 78, 64, 1, iron_hi)      # mid iron rail
    c.rect(0, 83, 64, 3, iron)         # riveted steel kickboard toe-plate
    for px in range(0, 64, 8):
        c.rect(px, 72, 2, 14, iron)
    c.rect(62, 72, 2, 14, iron)
    regions["mezz_rail"] = {"sheet": "glass_factory.png", "rect_art": [0, 72, 64, 14]}

    # 9. shop_win (48x24): Side elevation machine shop window ("SHOP")
    draw_sash(66, 72, 48, 24, "SHOP", amber, cols=6, rows=3)
    regions["shop_win"] = {"sheet": "glass_factory.png", "rect_art": [66, 72, 48, 24]}

    c.save_2x(TEX_DIR / "glass_factory.png")
    return regions


# The works' name, painted on the brick (a faded ghost sign): one variant per run (FACTORY_VARIANT, 0..3); the town picks one
# from its seed (src/town/glbSites.ts FACTORY_NAMES keeps the same order). Made-up surnames, no real company.
VARIANTS = [
    ("hargrove", "HARGROVE", "IRON WORKS"),
    ("whitcomb", "WHITCOMB", "FOUNDRY CO."),
    ("ostrander", "OSTRANDER", "IRON WORKS"),
    ("pelham", "PELHAM", "FOUNDRY CO."),
]
VARIANT = VARIANTS[int(os.environ.get("FACTORY_VARIANT", "0")) % len(VARIANTS)]


def brick_tile(c, x, y, w, h):
    """Fills [x, y, w, h] (art px) with the brick swatch (its art pixels: every second texel)."""
    bw, bh, rows = read_png_rgba(TEX_DIR / "brick_red.png")
    for yy in range(h):
        for xx in range(w):
            c.put(x + xx, y + yy, tuple(rows[((y + yy) * 2) % bh][((x + xx) * 2) % bw]))


def ghost_text(c, cx, cy, text, x0, y0, w, h, fade=0.8, field=(58, 38, 30)):
    """Paints [text] on what is there in the box, as old paint on brick: a dark painted field worn through to the brick in
    places, cream letters blended into it, a few of their pixels gone."""
    for yy in range(y0, y0 + h):
        for xx in range(x0, x0 + w):
            if (xx * 5 + yy * 3) % 13 == 0: continue  # worn through
            r, g, b, a = c.px[yy][xx]
            c.px[yy][xx] = (int(r + (field[0] - r) * .72), int(g + (field[1] - g) * .72), int(b + (field[2] - b) * .72), 255)
    tmp = ArtCanvas(c.w, c.h, (0, 0, 0, 0))
    tmp.draw_text(cx, cy, text, (255, 255, 255, 255), spacing=1)
    k = 0
    for yy in range(y0, y0 + h):
        for xx in range(x0, x0 + w):
            if tmp.px[yy][xx][3] == 0: continue
            k += 1
            if (xx * 7 + yy * 13) % 17 == 0: continue  # flaked off
            r, g, b, a = c.px[yy][xx]
            cr, cg, cb = 226, 214, 184
            c.px[yy][xx] = (int(r + (cr - r) * fade), int(g + (cg - g) * fade), int(b + (cb - b) * fade), 255)


def make_factory_glow():
    """256x128 art px -> 512x256 texels (EMISSIVE): Warm industrial factory signage, incandescent lamps & molten iron glow."""
    c = ArtCanvas(256, 128, (30, 28, 26, 255))
    enamel = (26, 44, 36, 255)      # Classic 1930s industrial porcelain-enamel dark forest green
    iron_bg = (32, 30, 28, 255)
    rust = (146, 66, 36, 255)
    cream = (248, 234, 198, 255)
    cream_dk = (118, 92, 58, 255)
    warn_y = (238, 178, 42, 255)
    molten_hi = (255, 224, 118, 255)
    molten_md = (255, 134, 34, 255)
    molten_dk = (182, 54, 16, 255)
    bulb = (255, 214, 132, 255)

    regions = {}

    # 1. ghost_left / ghost_right (72x10 each): the works' name painted on the front wall's brick over the windows, faded
    #    (not lit: the hall's picture, detail_hall, carries them)
    brick_tile(c, 0, 0, 72, 10)
    ghost_text(c, 36, 5, VARIANT[1], 0, 0, 72, 10)
    regions["ghost_left"] = {"sheet": "factory_glow.png", "rect_art": [0, 0, 72, 10]}
    brick_tile(c, 0, 20, 72, 10)
    ghost_text(c, 36, 25, VARIANT[2], 0, 20, 72, 10)
    regions["ghost_right"] = {"sheet": "factory_glow.png", "rect_art": [0, 20, 72, 10]}

    # 2. gate_board (64x12): one plain painted board over the yard gate, unlit
    c.rect(114, 0, 64, 12, (58, 50, 40, 255))
    c.border(114, 0, 64, 12, (92, 78, 60, 255), 1)
    c.draw_text(114 + 32, 6, "WORKS GATE", (206, 196, 170, 255), spacing=1)
    regions["gate_board"] = {"sheet": "factory_glow.png", "rect_art": [114, 0, 64, 12]}

    # 3. stencil (48x10): "SHIPPING" stencilled in white over the dock door, on the brick
    brick_tile(c, 180, 0, 48, 10)
    ghost_text(c, 180 + 24, 5, "SHIPPING", 180, 0, 48, 10, fade=0.85, field=(110, 104, 96))
    regions["stencil"] = {"sheet": "factory_glow.png", "rect_art": [180, 0, 48, 10]}

    # 4. lamp (16x4): Warm incandescent factory bay pendant light bar (ny = -1 -> spawns Filament lights!)
    c.rect(232, 0, 16, 4, warn_y)
    c.rect(233, 1, 14, 2, bulb)
    regions["lamp"] = {"sheet": "factory_glow.png", "rect_art": [232, 0, 16, 4]}

    # 7. casting_pit (96x64): Foundry iron-plate casting floor with safety-yellow hazard border & molten iron pouring troughs
    fx, fy, fw, fh = 0, 56, 96, 64
    c.rect(fx, fy, fw, fh, (36, 36, 38, 255))
    for yy in range(fy, fy + fh):
        for xx in range(fx, fx + fw):
            if (xx - fx) % 12 == 0 or (yy - fy) % 8 == 0:
                c.put(xx, yy, (52, 52, 56, 255))
    # Safety yellow & black diagonal hazard perimeter around casting floor
    for xx in range(fx, fx + fw):
        for t in (0, 1, fh - 2, fh - 1):
            c.put(xx, fy + t, warn_y if ((xx + t) // 3) % 2 == 0 else (24, 24, 26, 255))
    for yy in range(fy, fy + fh):
        for t in (0, 1, fw - 2, fw - 1):
            c.put(fx + t, yy, warn_y if ((yy + t) // 3) % 2 == 0 else (24, 24, 26, 255))
    # Glowing molten-iron slag troughs & center pouring hearth under the crucible
    c.border(fx + 10, fy + 8, fw - 20, fh - 16, rust, 2)
    c.border(fx + 18, fy + 14, fw - 36, fh - 28, molten_dk, 2)
    c.border(fx + 20, fy + 16, fw - 40, fh - 32, molten_md, 1)
    # Molten iron channels feeding the casting molds
    c.rect(fx + 6, fy + 30, 18, 4, molten_dk)
    c.rect(fx + 7, fy + 31, 16, 2, molten_hi)
    c.rect(fx + fw - 24, fy + 30, 18, 4, molten_dk)
    c.rect(fx + fw - 23, fy + 31, 16, 2, molten_hi)
    c.rect(fx + 46, fy + 6, 4, 12, molten_dk)
    c.rect(fx + 47, fy + 7, 2, 10, molten_hi)
    c.rect(fx + 46, fy + fh - 18, 4, 12, molten_dk)
    c.rect(fx + 47, fy + fh - 17, 2, 10, molten_hi)
    regions["casting_pit"] = {"sheet": "factory_glow.png", "rect_art": [0, 56, 96, 64]}

    # 8. furnace_wall (128x40): Massive Rear Brick & Riveted-Iron Blast Furnace Hearth & Boiler Bank
    sx, sy, sw, sh = 100, 56, 128, 40
    c.rect(sx, sy, sw, sh, (42, 38, 36, 255))
    c.border(sx, sy, sw, sh, rust, 2)
    # 4 glowing molten blast-furnace fireboxes with iron grates
    for fi in range(4):
        bx0 = sx + 8 + fi * 30
        c.rect(bx0, sy + 20, 22, 16, (28, 26, 24, 255))
        c.rect(bx0 + 2, sy + 22, 18, 12, molten_dk)
        c.rect(bx0 + 4, sy + 24, 14, 8, molten_md)
        c.rect(bx0 + 6, sy + 26, 10, 4, molten_hi)
        # Iron firebox bars
        for bar in (6, 11, 16):
            c.rect(bx0 + bar, sy + 21, 1, 14, (34, 32, 30, 255))
    # Riveted cast-iron boiler nameplate "IRON FOUNDRY" across upper hearth
    c.rect(sx + 18, sy + 4, 92, 13, enamel)
    c.border(sx + 18, sy + 4, 92, 13, warn_y, 1)
    c.draw_text(sx + 64, sy + 10, "IRON FOUNDRY", cream, shadow=(14, 20, 16, 255), spacing=1)
    regions["furnace_wall"] = {"sheet": "factory_glow.png", "rect_art": [100, 56, 128, 40]}

    # 9. tool_wall (96x24): Warm-lit Machinist Pegboard Tool Rack, Blueprints & Gauge Panel (east wall)
    cx, cy, cw, ch = 100, 100, 96, 24
    c.rect(cx, cy, cw, ch, (62, 52, 40, 255))
    c.border(cx, cy, cw, ch, rust, 1)
    # Pegboard background lit by overhead warm workbench lamps
    c.rect(cx + 2, cy + 2, cw - 4, 2, bulb)
    for yy in range(cy + 4, cy + ch - 2):
        bg_c = (84, 70, 52, 255) if (yy % 2 == 0) else (74, 60, 44, 255)
        c.rect(cx + 2, yy, cw - 4, 1, bg_c)
    # Hanging industrial wrenches, calipers & saws
    for ti in range(10):
        tx = cx + 5 + ti * 6
        c.rect(tx, cy + 6, 2, 12, (164, 170, 178, 255))
        c.rect(tx - 1, cy + 6, 4, 2, (196, 202, 210, 255))
    # Pinned industrial blueprint sheet on the right half
    c.rect(cx + 66, cy + 6, 26, 14, (42, 82, 136, 255))
    c.border(cx + 66, cy + 6, 26, 14, cream, 1)
    c.rect(cx + 70, cy + 10, 18, 1, cream)
    c.rect(cx + 70, cy + 13, 12, 1, cream)
    c.rect(cx + 74, cy + 16, 10, 1, cream)
    regions["tool_wall"] = {"sheet": "factory_glow.png", "rect_art": [100, 100, 96, 24]}

    c.save_2x(TEX_DIR / "factory_glow.png")
    return regions


def make_utility_factory():
    """128x128 art px -> 256x256 texels: Four exact 64x64 art-pixel industrial quadrants."""
    c = ArtCanvas(128, 128, (40, 40, 40, 255))

    # Quadrant 0,0 (iron_plate): Galvanized corrugated iron, roll-up freight doors, dust cyclones & blowers
    for y in range(64):
        for x in range(64):
            base = 88 + ((x * 5 + y * 3) % 14)
            if y % 8 == 0 or x % 16 == 0:
                col = (52, 54, 56, 255)
            elif y % 8 == 1:
                col = (128, 132, 136, 255)
            else:
                col = (base, base + 2, base + 2, 255)
            c.put(x, y, col)

    # Quadrant 64,0 (coal_slag): Dark coal/coke pile, soot-blackened furnace iron & oil drums
    for y in range(64):
        for x in range(64, 128):
            lx = x - 64
            v = (lx * 11 + y * 13) % 11
            if v < 3:
                col = (24, 24, 26, 255)
            elif v < 7:
                col = (38, 36, 36, 255)
            else:
                col = (54, 50, 46, 255)
            c.put(x, y, col)

    # Quadrant 0,64 (crate_wood): Heavy nailed wooden foundry shipping crates & sand-casting flasks
    for y in range(64, 128):
        ly = y - 64
        for x in range(64):
            if ly % 16 in (0, 15) or x % 16 in (0, 15):
                col = (98, 68, 40, 255)
            elif ly % 4 == 0:
                col = (124, 88, 52, 255)
            elif (x % 16) == (ly % 16):
                col = (114, 80, 46, 255)
            else:
                col = (154, 114, 70, 255)
            c.put(x, y, col)

    # Quadrant 64,64 (hazard_yellow): Caterpillar industrial safety-yellow painted bridge crane & hoist steel
    for y in range(64, 128):
        ly = y - 64
        for x in range(64, 128):
            lx = x - 64
            if ly % 16 == 0:
                col = (138, 96, 18, 255)
            elif ly % 16 == 1:
                col = (248, 202, 74, 255)
            elif lx % 16 == 8 and ly % 16 in (4, 12):
                col = (64, 52, 28, 255)
            else:
                col = (218, 162, 34, 255)
            c.put(x, y, col)

    c.save_2x(TEX_DIR / "utility_factory.png")
    return {
        "iron_plate": {"sheet": "utility_factory.png", "rect_art": [0, 0, 64, 64]},
        "coal_slag": {"sheet": "utility_factory.png", "rect_art": [64, 0, 64, 64]},
        "crate_wood": {"sheet": "utility_factory.png", "rect_art": [0, 64, 64, 64]},
        "hazard_yellow": {"sheet": "utility_factory.png", "rect_art": [64, 64, 64, 64]},
    }


def main():
    extract_swatches()
    make_riveted_iron()
    make_concrete_aged()
    regions = {}
    regions.update(make_factory_glass())
    regions.update(make_factory_glow())
    regions.update(make_utility_factory())
    (R / "art-regions.json").write_text(json.dumps(regions, indent=2) + "\n", encoding="utf-8")
    print("Generated 12 THE FOUNDRY (1938 Historic Working Factory) textures and art-regions.json successfully.")


if __name__ == "__main__":
    main()
