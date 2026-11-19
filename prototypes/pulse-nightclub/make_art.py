#!/usr/bin/env python3
"""
Texture and swatch generator for the PULSE 2020s Modern Nightclub & Sky Lounge landmark.
Follows docs/CHATGPT_MODEL_GUIDE.md and kotlin-street/docs/LANDMARKS_GLB.md:
- Cuts 7 shared LimeZu swatches directly from kotlin-street/tools/swatches/atlas/atlas.png
  (preserving exact 2x2 texel blocks and byte-for-byte swatch provenance).
- Paints 5 supplemental 2020s contemporary architectural nightclub textures at 1x art
  resolution and enlarges 2x via NEAREST so every single texture is made of exact 2x2
  texel blocks (1 texel = 0.0388 m, 1 art pixel = 0.0776 m).
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
    ("wall_slate_dark", "Dark architectural basalt/slate cladding for ground plinth and perimeter walls"),
    ("wall_slate_light", "Pale travertine/limestone accent panels and VIP entrance colonnade piers"),
    ("block_courses", "Acoustic concrete-block rear stage shell and rooftop stairwell core"),
    ("plank_horizontal", "Warm modern architectural teak decking for rooftop Sky Lounge & cantilevered wood soffit"),
    ("roof_membrane_grey", "Commercial grey flat roof membrane over rear hall and mechanical zone"),
    ("asphalt", "Fresh dark blacktop asphalt for VIP valet arrival drive and parking lot"),
    ("pavement", "Large-format architectural concrete promenade, VIP plaza, and main-room slab"),
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
        # Verify exact 2x2 texel block invariant
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
# 2. PAINT 5 BESPOKE 2020S MODERN ARCHITECTURAL NIGHTCLUB TEXTURES (2x2 GRID)
# ==============================================================================
def make_obsidian_cladding():
    """64x64 art px -> 128x128 texels: Matte charcoal vertical aluminum louvre rainscreen with bronze reveals."""
    c = ArtCanvas(64, 64, (24, 26, 33, 255))
    obs_dark = (18, 20, 26, 255)
    obs_mid = (28, 31, 40, 255)
    obs_hi = (38, 42, 54, 255)
    bronze_line = (128, 102, 72, 255)
    ice_accent = (62, 138, 172, 255)

    for y in range(64):
        for x in range(64):
            mod = x % 4
            if mod == 0:
                col = obs_dark
            elif mod == 1:
                col = obs_hi if (y % 16 != 0) else obs_mid
            elif mod == 2:
                col = obs_mid
            else:
                col = (22, 24, 31, 255)
            # Subtle modern horizontal panel seam every 16 art px
            if y % 16 == 0:
                col = obs_dark
            elif y % 16 == 1 and mod in (1, 2):
                col = (44, 48, 60, 255)
            c.put(x, y, col)

    # Recessed brushed-bronze horizontal architectural reveal band + slim ice-blue light pin-line
    c.rect(0, 30, 64, 2, bronze_line)
    c.rect(0, 32, 64, 1, obs_dark)
    c.rect(0, 33, 64, 1, ice_accent)
    c.save_2x(TEX_DIR / "obsidian.png")


def make_champagne_trim():
    """64x64 art px -> 128x128 texels: Brushed champagne-gold / anodized bronze architectural trim with dark steel reveal."""
    c = ArtCanvas(64, 64, (148, 120, 84, 255))
    steel = (30, 33, 42, 255)
    bronze_dk = (114, 90, 60, 255)
    bronze_md = (154, 126, 88, 255)
    bronze_hi = (196, 166, 120, 255)
    warm_glow = (232, 208, 162, 255)

    for y in range(64):
        for x in range(64):
            # Subtle horizontal brushed metallic grain
            g = ((x * 7 + y * 13) % 11)
            if y % 8 == 0:
                col = bronze_dk
            elif y % 8 == 1:
                col = bronze_hi
            elif g < 3:
                col = bronze_md
            elif g < 7:
                col = (164, 134, 94, 255)
            else:
                col = (142, 114, 78, 255)
            if x % 32 == 0:
                col = steel
            elif x % 32 == 1:
                col = warm_glow
            c.put(x, y, col)

    # Clean architectural shadow-gap groove across mid-height
    c.rect(0, 28, 64, 3, steel)
    c.rect(0, 31, 64, 1, warm_glow)
    c.save_2x(TEX_DIR / "champagne_trim.png")


def make_glass_curtain():
    """256x128 art px -> 512x256 texels (BLEND): Frameless dark-bronze curtain wall glazing, balcony glass & interior DJ shield."""
    c = ArtCanvas(256, 128, (0, 0, 0, 0))
    frame = (24, 26, 34, 255)
    bronze = (156, 128, 88, 255)
    glass_base = (38, 62, 86, 152)
    glass_hi = (98, 156, 198, 178)
    glass_spec = (168, 224, 252, 198)
    sign_bg = (16, 18, 26, 232)
    ice_txt = (124, 234, 255, 255)
    gold_txt = (248, 214, 146, 255)

    def draw_curtain_window(rx, ry, rw, rh, label=None, txt_col=ice_txt, panes_x=4, panes_y=2):
        c.rect(rx, ry, rw, rh, glass_base)
        # Diagonal modern architectural glass reflection bands
        for yy in range(ry + 1, ry + rh - 1):
            for xx in range(rx + 1, rx + rw - 1):
                diag = (xx - rx) + (yy - ry)
                if diag % 22 in (4, 5, 6):
                    c.put(xx, yy, glass_hi)
                elif diag % 22 == 7:
                    c.put(xx, yy, glass_spec)
        # Dark slim architectural mullions
        for px in range(1, panes_x):
            mx = rx + (rw * px) // panes_x
            c.rect(mx, ry, 1, rh, frame)
        for py in range(1, panes_y):
            my = ry + (rh * py) // panes_y
            c.rect(rx, my, rw, 1, frame)
        # Perimeter bronze & dark-steel curtain frame
        c.border(rx, ry, rw, rh, frame, 1)
        c.rect(rx + 1, ry + 1, rw - 2, 1, bronze)
        c.rect(rx + 1, ry + rh - 2, rw - 2, 1, bronze)
        if label:
            bw = min(rw - 8, len(label) * 6 + 6)
            bh = 11
            bx = rx + (rw - bw) // 2
            by = ry + (rh - bh) // 2
            c.rect(bx, by, bw, bh, sign_bg)
            c.border(bx, by, bw, bh, bronze, 1)
            c.draw_text(rx + rw // 2, ry + rh // 2, label, txt_col, shadow=(8, 12, 20, 255))

    regions = {}

    # 1. front_left (72x32): Ground-floor west lobby glass curtain wall ("LOUNGE")
    draw_curtain_window(0, 0, 72, 32, "LOUNGE", ice_txt, panes_x=4, panes_y=2)
    regions["front_left"] = {"sheet": "glass.png", "rect_art": [0, 0, 72, 32]}

    # 2. front_right (72x32): Ground-floor east lobby glass curtain wall ("VIP CLUB")
    draw_curtain_window(74, 0, 72, 32, "VIP CLUB", gold_txt, panes_x=4, panes_y=2)
    regions["front_right"] = {"sheet": "glass.png", "rect_art": [74, 0, 72, 32]}

    # 3. entry (32x36): Frameless double-leaf bronze-handled modern glass entrance doors
    draw_curtain_window(148, 0, 32, 36, None, ice_txt, panes_x=2, panes_y=1)
    # Tall vertical champagne-bronze architectural pull handles
    c.rect(148 + 13, 12, 1, 14, bronze)
    c.rect(148 + 18, 12, 1, 14, bronze)
    regions["entry"] = {"sheet": "glass.png", "rect_art": [148, 0, 32, 36]}

    # 4. transom (32x12): Clean clear atrium transom window above main doors
    draw_curtain_window(182, 0, 32, 12, None, ice_txt, panes_x=2, panes_y=1)
    regions["transom"] = {"sheet": "glass.png", "rect_art": [182, 0, 32, 12]}

    # 5. atrium_col (16x64): Double-height corner vertical atrium glass slot
    draw_curtain_window(216, 0, 16, 64, None, ice_txt, panes_x=1, panes_y=4)
    regions["atrium_col"] = {"sheet": "glass.png", "rect_art": [216, 0, 16, 64]}

    # 6. side_win (48x32): Side elevation modern curtain-wall window ("PULSE")
    draw_curtain_window(0, 36, 48, 32, "PULSE", ice_txt, panes_x=3, panes_y=2)
    regions["side_win"] = {"sheet": "glass.png", "rect_art": [0, 36, 48, 32]}

    # 7. vip_win (48x24): Side elevation VIP lounge window ("SKY BAR")
    draw_curtain_window(50, 36, 48, 24, "SKY BAR", gold_txt, panes_x=3, panes_y=1)
    regions["vip_win"] = {"sheet": "glass.png", "rect_art": [50, 36, 48, 24]}

    # 8. dj_glass (72x16): Interior DJ booth clear smoked-glass acoustic front shield ("MAIN ROOM")
    draw_curtain_window(100, 36, 72, 16, "MAIN ROOM", ice_txt, panes_x=3, panes_y=1)
    regions["dj_glass"] = {"sheet": "glass.png", "rect_art": [100, 36, 72, 16]}

    # 9. rail_glass (64x14): Rooftop Sky Lounge frameless glass safety balustrade with bronze top cap
    c.rect(0, 72, 64, 14, (58, 96, 128, 126))
    for yy in range(73, 85):
        for xx in range(1, 63):
            if (xx + yy) % 16 in (3, 4):
                c.put(xx, yy, (148, 208, 242, 164))
    for px in (0, 16, 32, 48, 63):
        c.rect(px, 72, 1, 14, frame)
    c.rect(0, 72, 64, 2, bronze)
    c.rect(0, 84, 64, 2, frame)
    regions["rail_glass"] = {"sheet": "glass.png", "rect_art": [0, 72, 64, 14]}

    c.save_2x(TEX_DIR / "glass.png")
    return regions


def make_led_media():
    """256x128 art px -> 512x256 texels (EMISSIVE): Modern LED signage, kinetic media facade, LED video wall & pulse floor."""
    c = ArtCanvas(256, 128, (14, 16, 24, 255))
    bg = (14, 16, 24, 255)
    obsidian = (20, 23, 34, 255)
    ice = (72, 236, 255, 255)
    ice_soft = (34, 142, 188, 255)
    violet = (164, 92, 255, 255)
    violet_dk = (74, 34, 138, 255)
    amber = (255, 198, 92, 255)
    amber_hi = (255, 236, 178, 255)
    white = (242, 248, 255, 255)

    regions = {}

    # 1. title (112x16): Sleek modern backlit architectural logo "PULSE" flanked by kinetic light bars
    c.rect(0, 0, 112, 16, bg)
    c.border(0, 0, 112, 16, ice_soft, 1)
    c.rect(1, 1, 110, 1, amber)
    c.rect(1, 14, 110, 1, ice)
    # Kinetic equalizer light-bars left and right
    bar_hs = [3, 6, 10, 7, 12, 5, 8, 4]
    for i, bh in enumerate(bar_hs):
        col = ice if i % 2 == 0 else violet
        c.rect(4 + i * 3, 8 - bh // 2, 2, bh, col)
        c.rect(106 - i * 3, 8 - bh // 2, 2, bh, col)
    c.draw_text(56, 8, "PULSE", white, shadow=violet_dk, spacing=2)
    regions["title"] = {"sheet": "led_media.png", "rect_art": [0, 0, 112, 16]}

    # 2. sky_sign (64x12): Rooftop "SKY LOUNGE" warm champagne-gold & obsidian backlit bar sign
    c.rect(114, 0, 64, 12, bg)
    c.border(114, 0, 64, 12, amber, 1)
    c.draw_text(114 + 32, 6, "SKY LOUNGE", amber_hi, shadow=(64, 42, 18, 255), spacing=1)
    regions["sky_sign"] = {"sheet": "led_media.png", "rect_art": [114, 0, 64, 12]}

    # 3. valet_sign (48x12): Front porte-cochere "VIP VALET" backlit architectural blade sign
    c.rect(180, 0, 48, 12, bg)
    c.border(180, 0, 48, 12, ice, 1)
    c.draw_text(180 + 24, 6, "VIP VALET", white, shadow=ice_soft, spacing=0)
    regions["valet_sign"] = {"sheet": "led_media.png", "rect_art": [180, 0, 48, 12]}

    # 4. lamp (16x4): Linear recessed architectural LED downlight bar (ny = -1 -> spawns Filament lights!)
    c.rect(232, 0, 16, 4, amber_hi)
    c.rect(233, 1, 14, 2, white)
    regions["lamp"] = {"sheet": "led_media.png", "rect_art": [232, 0, 16, 4]}

    # 5. led_media_wall (72x32): Exterior kinetic LED facade media screen on cantilevered upper box
    mx, my, mw, mh = 0, 18, 72, 32
    c.rect(mx, my, mw, mh, obsidian)
    c.border(mx, my, mw, mh, ice, 1)
    # Kinetic diagonal modern light-wave stripes
    for yy in range(my + 2, my + mh - 2):
        for xx in range(mx + 2, mx + mw - 2):
            v = ((xx - mx) * 2 + (yy - my) * 3) % 24
            if v in (0, 1):
                c.put(xx, yy, violet_dk)
            elif v == 12:
                c.put(xx, yy, (26, 48, 74, 255))
    # Bright center audio waveform & clean typography
    for i in range(16):
        bh = [4, 8, 12, 18, 14, 22, 16, 24, 20, 14, 22, 12, 16, 10, 6, 4][i]
        col = ice if i % 3 == 0 else (violet if i % 3 == 1 else amber)
        c.rect(mx + 4 + i * 4, my + 16 - bh // 2, 2, bh, col)
    c.rect(mx + 12, my + 9, 48, 14, (14, 16, 26, 255))
    c.border(mx + 12, my + 9, 48, 14, amber, 1)
    c.draw_text(mx + 36, my + 16, "PULSE", white, shadow=violet, spacing=2)
    regions["led_media_wall"] = {"sheet": "led_media.png", "rect_art": [0, 18, 72, 32]}

    # 6. monolith_top (48x32): Freestanding street totem architectural LED header
    tx, ty, tw, th = 74, 18, 48, 32
    c.rect(tx, ty, tw, th, bg)
    c.border(tx, ty, tw, th, amber, 1)
    c.rect(tx + 2, ty + 2, tw - 4, 2, ice)
    c.rect(tx + 2, ty + th - 4, tw - 4, 2, violet)
    c.draw_text(tx + 24, ty + 12, "PULSE", white, shadow=ice_soft, spacing=1)
    c.draw_text(tx + 24, ty + 22, "CLUB", amber_hi, shadow=(70, 48, 18, 255), spacing=1)
    regions["monolith_top"] = {"sheet": "led_media.png", "rect_art": [74, 18, 48, 32]}

    # 7. monolith_mid (48x24): Lower street totem info readout ("NIGHTLY / 22-04")
    lx, ly, lw, lh = 124, 18, 48, 24
    c.rect(lx, ly, lw, lh, bg)
    c.border(lx, ly, lw, lh, ice_soft, 1)
    c.draw_text(lx + 24, ly + 7, "VIP", amber_hi, shadow=(64, 42, 18, 255), spacing=1)
    c.rect(lx + 6, ly + 12, lw - 12, 1, violet)
    c.draw_text(lx + 24, ly + 18, "22-04", ice, shadow=(18, 48, 72, 255), spacing=1)
    regions["monolith_mid"] = {"sheet": "led_media.png", "rect_art": [124, 18, 48, 24]}

    # 8. emblem (24x24): Minimalist geometric kinetic pulse diamond emblem
    ex, ey = 176, 18
    c.rect(ex, ey, 24, 24, bg)
    c.border(ex, ey, 24, 24, amber, 1)
    for d in range(1, 10):
        col = ice if d in (3, 4) else (violet if d in (6, 7) else (amber_hi if d == 1 else obsidian))
        if col != obsidian:
            for y in range(24):
                for x in range(24):
                    if abs(x - 12) + abs(y - 12) == d:
                        c.put(ex + x, ey + y, col)
    c.rect(ex + 11, ey + 11, 2, 2, white)
    regions["emblem"] = {"sheet": "led_media.png", "rect_art": [176, 18, 24, 24]}

    # 9. dance_led (96x64): Contemporary dark-slate dance floor with concentric architectural LED light rings
    fx, fy, fw, fh = 0, 56, 96, 64
    c.rect(fx, fy, fw, fh, (18, 20, 28, 255))
    # Subtle large-format matte charcoal floor tiles (8x8 art px)
    for yy in range(fy, fy + fh):
        for xx in range(fx, fx + fw):
            if (xx - fx) % 8 == 0 or (yy - fy) % 8 == 0:
                c.put(xx, yy, (26, 29, 40, 255))
    # Recessed LED perimeter and concentric geometric pulse frames (modern Ibiza/Vegas lighting)
    c.border(fx + 1, fy + 1, fw - 2, fh - 2, amber, 1)
    c.border(fx + 6, fy + 5, fw - 12, fh - 10, ice, 1)
    c.border(fx + 14, fy + 11, fw - 28, fh - 22, violet, 1)
    c.border(fx + 24, fy + 18, fw - 48, fh - 36, amber_hi, 1)
    c.border(fx + 34, fy + 24, fw - 68, fh - 48, ice, 1)
    # Crosshair floor LED accent rays
    c.rect(fx + 6, fy + 31, 18, 2, ice_soft)
    c.rect(fx + fw - 24, fy + 31, 18, 2, ice_soft)
    c.rect(fx + 47, fy + 5, 2, 13, violet)
    c.rect(fx + 47, fy + fh - 18, 2, 13, violet)
    regions["dance_led"] = {"sheet": "led_media.png", "rect_art": [0, 56, 96, 64]}

    # 10. dj_led_wall (128x40): Main-room massive festival LED Video Wall behind DJ booth
    sx, sy, sw, sh = 100, 56, 128, 40
    c.rect(sx, sy, sw, sh, (12, 14, 24, 255))
    c.border(sx, sy, sw, sh, ice, 1)
    # Vertical kinetic LED light pillars & geometric chevron waves
    for i in range(24):
        px = sx + 4 + i * 5
        dist_center = abs(i - 11.5)
        bar_h = max(6, int(34 - dist_center * 2.1))
        col = ice if i % 3 == 0 else (violet if i % 3 == 1 else amber)
        y0 = sy + 20 - bar_h // 2
        c.rect(px, y0, 3, bar_h, col)
        c.rect(px + 1, y0 + 2, 1, max(2, bar_h - 4), white if i % 4 == 0 else amber_hi)
    # Dark architectural title plate centered on upper-middle of the video wall
    c.rect(sx + 32, sy + 7, 64, 18, (12, 14, 22, 255))
    c.border(sx + 32, sy + 7, 64, 18, amber_hi, 1)
    c.border(sx + 34, sy + 9, 60, 14, ice, 1)
    c.draw_text(sx + 64, sy + 16, "PULSE", white, shadow=violet, spacing=2)
    regions["dj_led_wall"] = {"sheet": "led_media.png", "rect_art": [100, 56, 128, 40]}

    # 11. bar_display (96x24): Illuminated warm-amber onyx & floating smoked-glass back-bar bottle wall
    bx, by, bw, bh = 100, 100, 96, 24
    c.rect(bx, by, bw, bh, (24, 20, 26, 255))
    c.border(bx, by, bw, bh, amber, 1)
    # Warm glowing onyx backlight panel
    for yy in range(by + 2, by + bh - 2):
        glow_col = (146, 98, 42, 255) if (yy - by) % 4 < 2 else (178, 122, 54, 255)
        c.rect(bx + 2, yy, bw - 4, 1, glow_col)
    # Two sleek floating illuminated shelves with luxury bottle silhouettes
    for sy_off in (10, 19):
        c.rect(bx + 2, by + sy_off, bw - 4, 2, white)
        for bi in range(18):
            bxx = bx + 5 + bi * 5
            bcol = (28, 36, 52, 255) if bi % 3 == 0 else ((42, 24, 58, 255) if bi % 3 == 1 else (36, 48, 42, 255))
            c.rect(bxx, by + sy_off - 6, 3, 6, bcol)
            c.rect(bxx + 1, by + sy_off - 8, 1, 2, amber_hi)
    regions["bar_display"] = {"sheet": "led_media.png", "rect_art": [100, 100, 96, 24]}

    c.save_2x(TEX_DIR / "led_media.png")
    return regions


def make_utility_atlas():
    """128x128 art px -> 256x256 texels: Four exact 64x64 art-pixel quadrants."""
    c = ArtCanvas(128, 128, (24, 26, 32, 255))

    # Quadrant 0,0 (steel): Brushed dark titanium / stainless steel for truss, bar counters, doors & HVAC
    for y in range(64):
        for x in range(64):
            base = 92 + ((x * 5 + y * 3) % 16)
            if y % 16 == 0 or x % 16 == 0:
                col = (56, 60, 70, 255)
            elif y % 16 == 1:
                col = (138, 144, 158, 255)
            else:
                col = (base, base + 4, base + 12, 255)
            c.put(x, y, col)

    # Quadrant 64,0 (acoustic): Matte obsidian speaker-grille & acoustic baffling
    for y in range(64):
        for x in range(64, 128):
            lx = x - 64
            if lx % 4 == 0 or y % 4 == 0:
                col = (22, 24, 30, 255)
            elif (lx + y) % 2 == 0:
                col = (34, 37, 46, 255)
            else:
                col = (28, 30, 38, 255)
            c.put(x, y, col)

    # Quadrant 0,64 (velvet): Deep modern royal-plum tufted leather upholstery for VIP banquette booths
    for y in range(64, 128):
        ly = y - 64
        for x in range(64):
            if ly % 8 == 0 or x % 8 == 0:
                col = (44, 20, 62, 255)
            elif ly % 8 == 1 or x % 8 == 1:
                col = (94, 52, 124, 255)
            else:
                col = (72, 34, 98, 255)
            if ly % 8 == 4 and x % 8 == 4:
                col = (32, 14, 48, 255)
            c.put(x, y, col)

    # Quadrant 64,64 (led_panel): Ribbed obsidian architectural ceiling baffles with subtle cool-slate & bronze reveals
    for y in range(64, 128):
        ly = y - 64
        for x in range(64, 128):
            lx = x - 64
            if ly % 6 == 0:
                col = (18, 22, 30, 255)
            elif ly % 6 == 1:
                col = (42, 52, 68, 255)
            elif lx % 32 == 0:
                col = (98, 82, 58, 255)
            else:
                col = (26, 32, 44, 255)
            c.put(x, y, col)

    c.save_2x(TEX_DIR / "utility.png")
    return {
        "steel": {"sheet": "utility.png", "rect_art": [0, 0, 64, 64]},
        "acoustic": {"sheet": "utility.png", "rect_art": [64, 0, 64, 64]},
        "velvet": {"sheet": "utility.png", "rect_art": [0, 64, 64, 64]},
        "led_panel": {"sheet": "utility.png", "rect_art": [64, 64, 64, 64]},
    }


def main():
    extract_swatches()
    make_obsidian_cladding()
    make_champagne_trim()
    regions = {}
    regions.update(make_glass_curtain())
    regions.update(make_led_media())
    regions.update(make_utility_atlas())
    (R / "art-regions.json").write_text(json.dumps(regions, indent=2) + "\n", encoding="utf-8")
    print("Generated 12 PULSE modern nightclub textures and art-regions.json successfully.")


if __name__ == "__main__":
    main()
