#!/usr/bin/env python3
"""
Texture and LimeZu swatch generator for the Small-Town WPA Post Office across two eras:
  - 1938: Original WPA-era brick & Indiana limestone Federal Post Office with painted interior mural
  - 1988: Modernized historic Post Office on the same lot & shell (accessibility ramp, glazed
          aluminum vestibule doors, royal-blue curb collection boxes, expanded loading canopy,
          rooftop HVAC, modest backlit sidewalk sign, and preserved interior 1938 WPA mural)

Follows docs/CHATGPT_MODEL_GUIDE.md strictly:
- Slices 6 shared LimeZu swatches directly from kotlin-street/tools/swatches/atlas/atlas.png
  (brick_red, roof_slate, ballast, roof_membrane_grey, asphalt_worn, pavement).
- Paints bespoke textures on exact 2x2 texel blocks (0.0388 m/texel -> 0.0776 m/art px).
- Dedicated panoramic 1930s WPA interior lobby mural ('wpa_mural.png', 256x72 texels = 128x36 art px)
  depicting small-town American agriculture, steam mail train, rural letter carrier, and airmail biplane.
- Enforces strict text fit assertions so NO lettering ever overflows its tablet or sign.
- Pure-black emissive masks so ONLY entrance bronze lanterns, lobby lights, rear dock light, and the
  modest 1988 sidewalk sign glow at night.
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
        xi, yi = int(x), int(y)
        if 0 <= xi < self.w and 0 <= yi < self.h:
            self.px[yi][xi] = col

    def rect(self, x, y, w, h, col):
        for yy in range(max(0, int(y)), min(self.h, int(y + h))):
            row = self.px[yy]
            for xx in range(max(0, int(x)), min(self.w, int(x + w))):
                row[xx] = col

    def border(self, x, y, w, h, col, t=1):
        self.rect(x, y, w, t, col)
        self.rect(x, y + h - t, w, t, col)
        self.rect(x, y, t, h, col)
        self.rect(x + w - t, y, t, h, col)

    def line(self, x0, y0, x1, y1, col):
        steps = max(1, int(max(abs(x1 - x0), abs(y1 - y0))))
        for i in range(steps + 1):
            t = i / steps
            self.put(round(x0 + (x1 - x0) * t), round(y0 + (y1 - y0) * t), col)

    def circle(self, cx, cy, r, col):
        for dy in range(-r, r + 1):
            for dx in range(-r, r + 1):
                if dx * dx + dy * dy <= r * r:
                    self.put(cx + dx, cy + dy, col)

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
# 1. EXTRACT 6 SHARED LIMEZU SWATCHES FROM ATLAS.PNG
# ==============================================================================
SHARED_SWATCHES = [
    ("brick_red", "1938 WPA red-brick main building walls, side wings, and tapered boiler chimney"),
    ("roof_slate", "Classical hipped slate roof and octagonal rooftop cupola roof"),
    ("ballast", "Recessed rear flat tar-and-gravel roof terrace behind parapet"),
    ("roof_membrane_grey", "Flashing, cupola trim, and 1988 extended loading-dock canopy deck"),
    ("asphalt_worn", "East postal truck drive and rear mail loading yard asphalt"),
    ("pavement", "Front civic plaza, sidewalks, accessibility ramp, loading dock, and clean lobby floor"),
]


def force_2x2_art_blocks(rows, w, h):
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
    _, _, arows = read_png_rgba(ATLAS_PNG)
    used = []
    for name, role in SHARED_SWATCHES:
        info = atlas_meta[name]
        x, y, w, h = info["x"], info["y"], info["w"], info["h"]
        sub = [arows[y + yy][x:x + w] for yy in range(h)]
        w2, h2, snapped = force_2x2_art_blocks(sub, w, h)
        write_png_rgba(TEX_DIR / f"{name}.png", w2, h2, snapped)
        used.append({"id": name, "file": f"{name}.png", "size": [w2, h2], "role": role})
    return used


# ==============================================================================
# 2. BESPOKE 2x2 PIXEL-ART TEXTURES
# ==============================================================================
def make_stone_trim():
    """Carved warm Indiana limestone for WPA pavilion, pilasters, steps, pediment, and plinth (64x64 texels = 32x32 art)."""
    c = ArtCanvas(32, 32, (214, 206, 188, 255))
    light = (232, 226, 210, 255)
    mid = (214, 206, 188, 255)
    mid2 = (200, 191, 173, 255)
    shade = (170, 160, 142, 255)
    dark = (96, 88, 76, 255)

    for y in range(32):
        for x in range(32):
            h = (x * 41 + y * 83) & 15
            c.put(x, y, mid if h < 9 else (mid2 if h < 14 else light))
    for row in range(0, 32, 8):
        c.rect(0, row, 32, 1, dark)
        c.rect(0, row + 1, 32, 1, light)
        c.rect(0, row + 7, 32, 1, shade)
        off = 0 if (row // 8) % 2 == 0 else 16
        for x in (off, (off + 16) % 32):
            c.rect(x, row, 1, 8, dark)
            c.put((x + 1) % 32, row + 2, light)
    for wx, wy in [(6, 4), (22, 12), (10, 20), (26, 28)]:
        c.put(wx, wy, shade)
    c.save_2x(TEX_DIR / "stone_trim.png")


def make_glass(year):
    """Alpha-blended glass for tall WPA federal windows so the warm lobby & mural peek through (64x64 texels = 32x32 art)."""
    c = ArtCanvas(32, 32, (58, 86, 112, 185))
    frame = (54, 48, 42, 255) if year == 1938 else (68, 74, 82, 255)
    mull = (218, 212, 198, 255) if year == 1938 else (146, 154, 162, 255)
    pane = (58, 86, 112, 172) if year == 1938 else (64, 94, 122, 178)
    hi = (156, 188, 214, 205)
    lo = (38, 56, 78, 195)

    c.rect(0, 0, 32, 32, pane)
    for i in range(32):
        if (i % 16) in (3, 4):
            c.put(i, i, hi)
            c.put((i + 1) % 32, i, hi)
        elif (i % 16) == 12:
            c.put(i, i, lo)
    c.border(0, 0, 32, 32, frame, 2)
    if year == 1938:
        # Classic WPA 12-over-12 white-painted colonial/federal muntin sash
        c.rect(0, 15, 32, 2, frame)
        c.rect(10, 2, 1, 28, mull)
        c.rect(21, 2, 1, 28, mull)
        c.rect(2, 8, 28, 1, mull)
        c.rect(2, 23, 28, 1, mull)
    else:
        # 1988 thermal bronze-anodized aluminum replacement sash
        c.rect(0, 15, 32, 2, frame)
        c.rect(15, 2, 2, 28, mull)
    c.save_2x(TEX_DIR / f"glass_{year}.png")


def make_wpa_mural():
    """
    Signature 1930s WPA Post Office Lobby Mural ('wpa_mural.png', 256x72 texels = 128x36 art px).
    Theme: 'THE MAIL UNITES FARM, RAIL, AND TOWN' (warm Thomas Hart Benton / Grant Wood earthy palette).
    Composition (left to right inside a carved walnut & gilt-bronze frame):
      - Sky: golden-ochre prairie horizon blending into dusty cerulean blue, cream clouds, airmail biplane (right)
      - Background: terraced green/gold hills, red barn & grain silo (left), small town steeple & water tower (center),
        brick mill & stone arch viaduct bridge (right)
      - Midground: 1930s black-and-red steam mail locomotive crossing the stone viaduct with trailing cream steam
      - Foreground: wheat harvesters (left), heroic WPA rural letter carrier handing mail to a farm family by a
        wooden fence & mailbox (center), postal clerk loading sacks beside a 1930s green mail truck (right)
    """
    c = ArtCanvas(128, 36, (108, 72, 42, 255))

    # Carved dark walnut & gilt frame (outer 2px)
    walnut_dark = (48, 30, 20, 255)
    walnut_mid = (84, 52, 32, 255)
    gilt = (206, 168, 76, 255)
    gilt_hi = (238, 208, 118, 255)
    c.border(0, 0, 128, 36, walnut_dark, 1)
    c.border(1, 1, 126, 34, walnut_mid, 1)
    c.border(2, 2, 124, 32, gilt, 1)
    c.rect(2, 2, 124, 1, gilt_hi)

    # Interior canvas bounds: x in [3..124] (122 px), y in [3..32] (30 px)
    # 1) Warm WPA sky gradient (y = 3..16)
    sky_bands = [
        (3, 5, (74, 108, 138, 255)),
        (5, 8, (96, 132, 156, 255)),
        (8, 11, (148, 166, 162, 255)),
        (11, 14, (216, 186, 130, 255)),
        (14, 17, (238, 204, 136, 255)),
    ]
    for y0, y1, col in sky_bands:
        c.rect(3, y0, 122, y1 - y0, col)
    # Subtle painterly dither between sky bands
    for x in range(3, 125, 2):
        c.put(x, 8, (118, 148, 158, 255))
        c.put(x + 1, 11, (186, 174, 142, 255))

    # Radiant setting sun low behind center town valley (x = 58, y = 14)
    c.circle(58, 14, 4, (246, 224, 156, 255))
    c.circle(58, 14, 2, (254, 242, 198, 255))

    # Sculptural WPA cumulus clouds (left & center-right)
    cloud_hi = (248, 238, 212, 255)
    cloud_sh = (212, 194, 168, 255)
    for cx, cy, r in [(16, 7, 3), (21, 6, 4), (27, 7, 3), (78, 7, 3), (84, 6, 3), (89, 7, 2)]:
        c.circle(cx, cy + 1, r, cloud_sh)
        c.circle(cx, cy, r, cloud_hi)

    # 1930s Airmail Biplane soaring upper right (x = 104..118, y = 5..10)
    plane_body = (176, 52, 42, 255)
    plane_wing = (232, 214, 172, 255)
    plane_dark = (46, 40, 38, 255)
    c.rect(105, 8, 11, 2, plane_body)       # fuselage
    c.rect(104, 7, 2, 2, plane_body)        # tail fin
    c.rect(108, 6, 7, 1, plane_wing)        # upper wing
    c.rect(109, 9, 6, 1, plane_wing)        # lower wing
    c.put(110, 7, plane_dark)               # strut
    c.put(114, 7, plane_dark)               # strut
    c.rect(116, 7, 1, 4, (226, 224, 216, 255))  # spinning prop

    # 2) Rolling Midwestern hills & golden wheat fields (y = 14..26)
    sage_far = (102, 124, 96, 255)
    hill_mid = (124, 142, 86, 255)
    hill_dark = (84, 104, 64, 255)
    wheat_hi = (224, 182, 82, 255)
    wheat_mid = (196, 148, 62, 255)
    soil = (146, 98, 62, 255)

    for x in range(3, 125):
        # Far rolling ridge
        h_far = int(15 + 2.4 * math.sin(x * 0.065) + 1.2 * math.cos(x * 0.14))
        for y in range(h_far, 25):
            c.put(x, y, sage_far)
        # Mid green & gold terraced hills
        h_mid = int(18 + 2.8 * math.sin(x * 0.052 + 1.2))
        for y in range(h_mid, 33):
            if x < 46:
                # Golden quilted wheat fields on the left
                stripe = ((x + y * 2) // 3) % 2 == 0
                c.put(x, y, wheat_hi if stripe else wheat_mid)
            elif x < 86:
                c.put(x, y, hill_mid if ((x + y) & 3) else hill_dark)
            else:
                c.put(x, y, soil if y > 22 else hill_mid)

    # 3) Left Background: Classic Red Barn & Cylindrical Grain Silo (x = 8..22, y = 13..20)
    barn_red = (164, 48, 38, 255)
    barn_roof = (74, 66, 62, 255)
    c.rect(10, 15, 10, 5, barn_red)
    c.rect(11, 14, 8, 1, barn_roof)
    c.rect(12, 13, 6, 1, barn_roof)
    c.rect(14, 17, 3, 3, (228, 220, 200, 255))  # white barn X door
    c.put(15, 18, barn_red)
    # Silo beside barn
    c.rect(20, 13, 3, 7, (208, 198, 178, 255))
    c.rect(20, 12, 3, 1, (148, 64, 52, 255))

    # 4) Center Valley: Small-Town Courthouse Spire & Water Tower (x = 46..68, y = 11..19)
    town_brick = (158, 76, 56, 255)
    town_cream = (224, 214, 192, 255)
    c.rect(48, 15, 7, 4, town_brick)
    c.rect(55, 14, 6, 5, town_cream)
    c.rect(57, 11, 2, 3, town_cream)   # church/civic spire
    c.put(57, 10, (196, 162, 74, 255))
    c.rect(62, 15, 5, 4, town_brick)
    # Water tower silhouette
    c.rect(69, 13, 4, 2, (112, 124, 136, 255))
    c.line(69, 15, 69, 18, (64, 68, 74, 255))
    c.line(72, 15, 72, 18, (64, 68, 74, 255))

    # 5) Right Midground: Stone Viaduct Bridge + 1930s Steam Mail Train (x = 76..124, y = 14..22)
    stone_v = (188, 176, 154, 255)
    stone_sh = (128, 116, 98, 255)
    c.rect(76, 19, 49, 2, stone_v)
    for ax in range(78, 122, 8):
        c.rect(ax, 21, 3, 4, stone_v)
        c.rect(ax + 3, 21, 5, 1, stone_sh)
    # Steam locomotive (heading left at x = 80..92) + Mail Cars (x = 93..122)
    loco_blk = (38, 36, 40, 255)
    loco_red = (168, 46, 38, 255)
    mail_grn = (58, 92, 68, 255)
    c.rect(81, 16, 10, 3, loco_blk)         # boiler & cab
    c.rect(82, 15, 2, 1, loco_blk)          # smokestack
    c.rect(88, 15, 3, 2, loco_red)          # cab roof
    c.rect(80, 18, 1, 1, (238, 214, 128, 255))  # brass headlamp
    # Billowing white steam plume drifting right
    for sx, sy in [(83, 14), (85, 13), (86, 13), (88, 12), (90, 12), (92, 12), (95, 11), (98, 11)]:
        c.rect(sx, sy, 3, 2, (242, 238, 228, 255))
    # Two Pullman/Railway Post Office (RPO) mail cars
    for cx in (93, 108):
        c.rect(cx, 16, 13, 3, mail_grn)
        c.rect(cx, 15, 13, 1, (64, 60, 58, 255))
        for wx in range(cx + 2, cx + 11, 3):
            c.put(wx, 17, (244, 224, 152, 255))

    # 6) FOREGROUND HEROIC WPA FIGURES & SCENES (y = 22..32)
    # Foreground warm earth & country road strip
    c.rect(3, 29, 122, 4, (132, 92, 60, 255))
    c.rect(3, 31, 122, 2, (104, 72, 46, 255))
    # Wooden split-rail fence across center-left
    fence = (112, 76, 46, 255)
    c.rect(34, 27, 46, 1, fence)
    c.rect(34, 29, 46, 1, fence)
    for fx in range(36, 80, 10):
        c.rect(fx, 26, 2, 6, fence)

    # Helper to paint stylized 1930s WPA muscular figures (height ~9 art px)
    skin = (224, 178, 142, 255)
    hat_straw = (228, 198, 116, 255)
    denim = (62, 86, 126, 255)
    shirt_cream = (232, 222, 198, 255)
    postman_blue = (48, 72, 116, 255)
    dress_red = (168, 64, 54, 255)
    boot = (46, 34, 28, 255)

    # A) Left Foreground: Two Wheat Harvesters + Stacked Golden Wheat Sheaves (x = 8..32)
    # Wheat sheaves
    for sx in (7, 27):
        c.rect(sx, 25, 4, 6, (218, 172, 68, 255))
        c.rect(sx + 1, 27, 2, 1, (148, 72, 42, 255))  # twine tie
    # Farmer 1 (standing with scythe/sheaf at x = 14)
    c.rect(13, 21, 4, 1, hat_straw)
    c.rect(14, 22, 2, 2, skin)
    c.rect(13, 24, 4, 3, shirt_cream)
    c.rect(13, 27, 4, 3, denim)
    c.rect(13, 30, 2, 2, boot)
    c.rect(15, 30, 2, 2, boot)
    # Farmer 2 (carrying wheat bundle at x = 21)
    c.rect(20, 22, 4, 1, hat_straw)
    c.rect(21, 23, 2, 2, skin)
    c.rect(20, 25, 4, 3, (176, 118, 68, 255))
    c.rect(20, 28, 4, 2, denim)
    c.rect(20, 30, 4, 2, boot)

    # B) Center Foreground Hero Scene: Rural Letter Carrier handing a white letter to Farm Family by Country Mailbox!
    # Country mailbox on post (x = 58)
    c.rect(58, 26, 2, 6, fence)
    c.rect(56, 24, 5, 2, (168, 174, 180, 255))
    c.put(59, 23, (186, 46, 38, 255))  # red flag up!
    # Letter Carrier (x = 49..55, facing right in classic 1930s postal blue uniform + leather satchel)
    c.rect(50, 21, 4, 1, postman_blue)          # peaked cap
    c.rect(51, 22, 2, 2, skin)                  # face
    c.rect(49, 24, 5, 4, postman_blue)          # jacket
    c.rect(50, 25, 2, 3, (144, 88, 46, 255))    # leather mail satchel
    c.rect(54, 25, 3, 1, (248, 244, 232, 255))  # extended arm holding white envelope!
    c.rect(50, 28, 4, 3, (40, 56, 88, 255))     # trousers
    c.rect(50, 31, 4, 1, boot)
    # Farm Mother & Child receiving the letter (x = 62..72)
    c.rect(64, 22, 2, 2, skin)
    c.rect(63, 24, 4, 6, dress_red)
    c.rect(62, 25, 2, 1, skin)                  # reaching arm
    c.rect(64, 30, 3, 2, boot)
    # Child beside her (x = 69)
    c.rect(69, 25, 2, 2, skin)
    c.rect(68, 27, 3, 3, (88, 128, 152, 255))
    c.rect(69, 30, 2, 2, boot)

    # C) Right Foreground: 1930s Olive-Green Mail Truck & Postal Clerk loading mailbags (x = 84..120)
    # Mail sacks piled at x = 86..93
    for mx in (86, 90):
        c.rect(mx, 28, 4, 4, (206, 188, 148, 255))
        c.rect(mx + 1, 29, 2, 1, (152, 62, 48, 255))
    # Postal Clerk (x = 95)
    c.rect(95, 22, 3, 1, postman_blue)
    c.rect(95, 23, 2, 2, skin)
    c.rect(94, 25, 4, 3, shirt_cream)
    c.rect(94, 28, 4, 3, postman_blue)
    c.rect(94, 31, 4, 1, boot)
    # Classic 1930s Box-Cab Olive-Green U.S. Mail Truck (x = 102..120, y = 24..31)
    truck_grn = (68, 96, 66, 255)
    truck_hi = (98, 128, 90, 255)
    c.rect(103, 24, 11, 6, truck_grn)           # cargo box
    c.rect(103, 24, 11, 1, truck_hi)
    c.rect(105, 26, 7, 1, (232, 206, 116, 255)) # gold stripe
    c.rect(114, 25, 4, 5, truck_grn)            # cab
    c.rect(115, 26, 2, 2, (162, 192, 212, 255)) # windshield
    c.rect(118, 27, 3, 3, truck_grn)            # engine hood
    c.put(120, 28, (242, 222, 142, 255))        # brass headlight
    c.circle(106, 30, 2, (36, 34, 36, 255))     # rear wheel
    c.put(106, 30, (192, 168, 102, 255))
    c.circle(118, 30, 2, (36, 34, 36, 255))     # front wheel
    c.put(118, 30, (192, 168, 102, 255))

    c.save_2x(TEX_DIR / "wpa_mural.png")


def make_details(year):
    """
    256x256 texels (128x128 art px) Post Office detail atlas with strict non-overlapping regions:
      - entry_doors:    [0, 0, 32, 44]     (exact 32x44 symmetrical double federal doors)
      - loading_door:   [48, 0, 48, 44]    (rear loading-dock freight bay door)
      - louver_vent:    [96, 0, 32, 44]    (rooftop cupola louvers & HVAC grilles)
      - mailbox_body:   [0, 44, 32, 24]    (1938 olive-green cast iron mailbox / 1988 royal-blue collection box)
      - dark_metal:     [32, 44, 32, 24]   (bronze/iron railings, flagpole, HVAC casing, rear door, bumpers)
      - brass_gold:     [64, 44, 32, 24]   (brass eagle finial, counter grilles, door hardware, bronze trim)
      - wood_trim:      [96, 44, 32, 24]   (walnut service counter, interior wainscot cap, loading dock timber)
      - hedge_green:    [0, 68, 32, 16]    (trimmed civic lawn/boxwood planter beds flanking steps)
      - yellow_stripe:  [32, 68, 64, 16]   (loading-zone yellow stripes & safety bollards)
      - po_boxes:       [96, 68, 32, 16]   (grid of gleaming brass/bronze P.O. Box doors inside lobby!)
      - interior_wall:  [0, 84, 64, 44]    (clean WPA plaster upper wall + marble/walnut lobby wainscot)
      - counter_front:  [64, 84, 64, 24]   (exact 64x24 postal teller counter base + brass/glass teller window!)
    """
    c = ArtCanvas(128, 128, (96, 92, 86, 255))
    outline = (36, 30, 26, 255)

    # 1) entry_doors [0, 0, 32, 44] — Symmetrical Double Federal Post Office front doors (exact 32x44!)
    if year == 1938:
        oak_hi = (158, 108, 64, 255)
        oak_mid = (122, 78, 44, 255)
        oak_lo = (84, 52, 28, 255)
        brass = (224, 188, 92, 255)
        c.rect(0, 0, 32, 44, oak_mid)
        c.border(0, 0, 32, 44, outline, 2)
        c.rect(15, 0, 2, 44, outline)
        # Upper fanlight transom (y = 2..10)
        c.rect(2, 2, 28, 8, outline)
        c.rect(3, 3, 26, 6, (58, 84, 110, 255))
        for mx in (9, 15, 21):
            c.rect(mx, 3, 2, 6, oak_hi)
        c.rect(2, 10, 28, 2, oak_hi)
        # Twin symmetrical oak door leaves (lx = 2 & lx = 17, width = 13 each)
        for lx in (2, 17):
            c.rect(lx, 12, 13, 30, oak_mid)
            c.rect(lx, 12, 13, 1, oak_hi)
            # Glazed upper vision window
            c.rect(lx + 2, 14, 9, 13, outline)
            c.rect(lx + 3, 15, 7, 11, (62, 92, 118, 255))
            c.rect(lx + 6, 15, 1, 11, oak_hi)
            c.rect(lx + 3, 20, 7, 1, oak_hi)
            # Lower recessed carved wood panel
            c.rect(lx + 2, 29, 9, 11, oak_lo)
            c.border(lx + 2, 29, 9, 11, outline, 1)
            c.rect(lx + 4, 31, 5, 7, oak_mid)
            # Brass vertical pull handles meeting symmetrically at center!
            hx = lx + 10 if lx == 2 else lx + 1
            c.rect(hx, 26, 2, 5, brass)
    else:
        # 1988 commercial dark-bronze aluminum & glass double doors (exact 32x44!)
        alum = (64, 62, 60, 255)
        alum_hi = (108, 106, 102, 255)
        glass = (62, 96, 124, 255)
        c.rect(0, 0, 32, 44, alum)
        c.border(0, 0, 32, 44, outline, 2)
        c.rect(2, 2, 28, 8, outline)
        c.rect(3, 3, 26, 6, glass)
        c.rect(15, 2, 2, 40, alum)
        c.rect(2, 10, 28, 2, alum_hi)
        for lx in (2, 17):
            c.rect(lx + 1, 13, 11, 24, outline)
            c.rect(lx + 2, 14, 9, 22, glass)
            c.rect(lx + 3, 15, 3, 2, (148, 182, 208, 255))
            c.rect(lx + 1, 25, 11, 2, (204, 206, 208, 255))  # stainless push bar
            c.rect(lx + 1, 38, 11, 4, alum_hi)               # kickplate

    # 2) loading_door [48, 0, 48, 44] — Rear freight / mail-dock door
    if year == 1938:
        c.rect(48, 0, 48, 44, (116, 110, 98, 255))
        c.border(48, 0, 48, 44, outline, 2)
        for sy in range(4, 42, 8):
            c.rect(50, sy, 44, 1, outline)
            c.rect(50, sy + 1, 44, 1, (148, 142, 128, 255))
        for wx in (54, 66, 78):
            c.rect(wx, 6, 8, 6, outline)
            c.rect(wx + 1, 7, 6, 4, (58, 84, 106, 255))
    else:
        c.rect(48, 0, 48, 44, (196, 196, 192, 255))
        c.border(48, 0, 48, 44, outline, 2)
        for sy in range(4, 42, 6):
            c.rect(50, sy, 44, 1, (148, 150, 152, 255))
            c.rect(50, sy + 1, 44, 1, (224, 224, 220, 255))
        c.rect(50, 32, 44, 10, (54, 82, 132, 255))  # modern postal navy bottom stripe

    # 3) louver_vent [96, 0, 32, 44]
    c.rect(96, 0, 32, 44, (84, 82, 78, 255))
    c.border(96, 0, 32, 44, outline, 2)
    slat_hi = (224, 218, 204, 255) if year == 1938 else (164, 170, 176, 255)
    for ly in range(3, 41, 3):
        c.rect(98, ly, 28, 1, slat_hi)
        c.rect(98, ly + 2, 28, 1, (44, 42, 40, 255))

    # 4) mailbox_body [0, 44, 32, 24] — 1938 Olive-Green Cast-Iron Mailbox vs 1988 Royal-Blue Collection Box!
    if year == 1938:
        mb_mid = (66, 94, 62, 255)
        mb_hi = (98, 128, 90, 255)
        mb_lo = (44, 64, 42, 255)
        c.rect(0, 44, 32, 24, mb_mid)
        c.border(0, 44, 32, 24, outline, 1)
        c.rect(1, 45, 30, 2, mb_hi)
        c.rect(1, 65, 30, 2, mb_lo)
        # Drop-chute hood & white schedule card
        c.rect(6, 48, 20, 4, outline)
        c.rect(7, 49, 18, 2, mb_hi)
        c.rect(9, 55, 14, 7, (232, 224, 200, 255))
        c.rect(11, 57, 10, 1, outline)
        c.rect(11, 59, 8, 1, outline)
    else:
        mb_mid = (42, 74, 134, 255)
        mb_hi = (74, 112, 176, 255)
        mb_lo = (26, 48, 92, 255)
        c.rect(0, 44, 32, 24, mb_mid)
        c.border(0, 44, 32, 24, outline, 1)
        c.rect(1, 45, 30, 2, mb_hi)
        c.rect(1, 65, 30, 2, mb_lo)
        c.rect(5, 48, 22, 4, outline)
        c.rect(6, 49, 20, 2, mb_hi)
        # Crisp white/red postal band across collection box
        c.rect(2, 55, 28, 3, (238, 236, 230, 255))
        c.rect(2, 58, 28, 2, (182, 46, 42, 255))

    # 5) dark_metal [32, 44, 32, 24]
    c.rect(32, 44, 32, 24, (74, 78, 84, 255))
    c.border(32, 44, 32, 24, outline, 1)
    c.rect(33, 45, 30, 2, (118, 124, 132, 255))
    c.rect(33, 65, 30, 2, (46, 48, 52, 255))

    # 6) brass_gold [64, 44, 32, 24]
    c.rect(64, 44, 32, 24, (198, 162, 74, 255))
    c.border(64, 44, 32, 24, outline, 1)
    c.rect(65, 45, 30, 2, (238, 212, 128, 255))
    c.rect(65, 65, 30, 2, (136, 106, 42, 255))

    # 7) wood_trim [96, 44, 32, 24]
    c.rect(96, 44, 32, 24, (124, 82, 48, 255))
    c.border(96, 44, 32, 24, outline, 1)
    for wy in range(46, 66, 4):
        c.rect(97, wy, 30, 1, (152, 106, 66, 255))
        c.rect(97, wy + 2, 30, 1, (88, 56, 32, 255))

    # 8) hedge_green [0, 68, 32, 16] — Trimmed civic boxwood planter shrubs (muted LimeZu olive/sage!)
    c.rect(0, 68, 32, 16, (72, 102, 64, 255))
    for y in range(68, 84):
        for x in range(0, 32):
            h = (x * 29 + y * 53) & 7
            if h == 0:
                c.put(x, y, (104, 136, 86, 255))
            elif h in (1, 2):
                c.put(x, y, (52, 76, 46, 255))
    c.border(0, 68, 32, 16, (40, 56, 36, 255), 1)

    # 9) yellow_stripe [32, 68, 64, 16]
    c.rect(32, 68, 64, 16, (216, 174, 48, 255))
    c.border(32, 68, 64, 16, outline, 1)
    c.rect(33, 69, 62, 2, (242, 208, 92, 255))
    c.rect(33, 81, 62, 2, (162, 124, 30, 255))

    # 10) po_boxes [96, 68, 32, 16] — Iconic grid of brass/bronze small-town P.O. Box doors!
    c.rect(96, 68, 32, 16, (56, 46, 36, 255))
    box_c = (196, 162, 82, 255) if year == 1938 else (178, 182, 186, 255)
    box_hi = (232, 204, 124, 255) if year == 1938 else (216, 220, 224, 255)
    for by in range(69, 83, 5):
        for bx in range(97, 127, 5):
            c.rect(bx, by, 4, 4, box_c)
            c.put(bx, by, box_hi)
            c.put(bx + 2, by + 2, (46, 38, 30, 255))  # dial lock

    # 11) interior_wall [0, 84, 64, 44] — Clean warm WPA plaster upper + cream marble & walnut wainscot
    c.rect(0, 84, 64, 24, (226, 218, 198, 255))
    c.rect(0, 108, 64, 2, (112, 74, 44, 255))   # walnut chair rail
    c.rect(0, 110, 64, 18, (192, 184, 166, 255))  # warm veined terrazzo/marble wainscot
    for tx in range(0, 64, 16):
        c.rect(tx, 110, 1, 18, (152, 144, 128, 255))
    for y in range(84, 108):
        for x in range(64):
            if ((x * 19 + y * 37) & 15) == 0:
                c.put(x, y, (212, 204, 184, 255))

    # 12) counter_front [64, 84, 64, 24] — Exact 64x24 Postal Teller Counter (both grille AND walnut/navy base!)
    if year == 1938:
        # Upper 11 px (y = 84..95): classic vertical brass teller grille & clerk window opening
        c.rect(64, 84, 64, 11, (50, 56, 62, 255))
        c.rect(64, 84, 64, 1, (118, 76, 44, 255))
        for gx in range(66, 127, 3):
            c.rect(gx, 85, 1, 10, (212, 176, 84, 255))
        c.rect(86, 88, 20, 7, (38, 42, 48, 255))
        c.border(86, 88, 20, 7, (232, 198, 102, 255), 1)
        # Middle 2 px (y = 95..97): marble counter top trim
        c.rect(64, 95, 64, 2, (226, 218, 200, 255))
        # Lower 11 px (y = 97..108): carved walnut paneled teller counter base!
        c.rect(64, 97, 64, 11, (118, 76, 44, 255))
        c.border(64, 97, 64, 11, outline, 1)
        for px in (68, 88, 108):
            c.rect(px, 99, 16, 7, (88, 54, 30, 255))
            c.border(px, 99, 16, 7, (158, 110, 66, 255), 1)
    else:
        # 1988 modern postal counter: glass/navy upper window bays + cream/navy lower front
        c.rect(64, 84, 64, 11, (68, 88, 108, 255))
        c.rect(64, 84, 64, 1, (44, 68, 116, 255))
        c.rect(86, 87, 20, 8, (44, 52, 62, 255))
        c.border(86, 87, 20, 8, (186, 192, 198, 255), 1)
        c.rect(64, 95, 64, 2, (234, 230, 222, 255))
        c.rect(64, 97, 64, 11, (202, 196, 184, 255))
        c.rect(64, 98, 64, 3, (44, 72, 128, 255))
        c.rect(64, 101, 64, 1, (182, 46, 42, 255))
        c.border(64, 97, 64, 11, outline, 1)

    c.save_2x(TEX_DIR / f"details_{year}.png")
    return {
        "entry_doors": [0, 0, 32, 44],
        "loading_door": [48, 0, 48, 44],
        "louver_vent": [96, 0, 32, 44],
        "mailbox_body": [0, 44, 32, 24],
        "dark_metal": [32, 44, 32, 24],
        "brass_gold": [64, 44, 32, 24],
        "wood_trim": [96, 44, 32, 24],
        "hedge_green": [0, 68, 32, 16],
        "yellow_stripe": [32, 68, 64, 16],
        "po_boxes": [96, 68, 32, 16],
        "interior_wall": [0, 84, 64, 44],
        "counter_front": [64, 84, 64, 24],
    }


def make_signs(year):
    """
    256x128 texels (128x64 art px) restrained civic signage atlas with strict fit assertions:
      - main_entablature: [0, 0, 120, 16]   (Carved limestone 'U.S. POST OFFICE' — preserved across both eras!)
      - date_stone:       [0, 16, 40, 14]   (Carved '1938' WPA dedication block)
      - mail_plaque:      [40, 16, 40, 14]  (Small bronze/navy 'MAIL DROP' or 'STAMPS' plaque)
      - hours_board:      [80, 16, 48, 16]  (Small lobby/entrance notice board)
      - civic_flag:       [0, 32, 32, 20]   (Muted civic stars-and-stripes style pennant)
      - loading_sign:     [64, 36, 64, 24]  (Rear dock notice 'MAIL TRUCKS')
    """
    c = ArtCanvas(128, 64, (206, 198, 180, 255))
    outline = (44, 38, 34, 255)
    stone = (220, 212, 194, 255)
    stone_hi = (236, 230, 214, 255)
    stone_lo = (168, 158, 140, 255)
    incised = (62, 52, 42, 255)
    incised_hi = (234, 228, 212, 255)

    # 1) main_entablature [0, 0, 120, 16] — Carved Indiana limestone 'U.S. POST OFFICE'
    c.rect(0, 0, 120, 16, stone)
    c.border(0, 0, 120, 16, outline, 1)
    c.rect(1, 1, 118, 1, stone_hi)
    c.rect(1, 14, 118, 1, stone_lo)
    c.border(2, 2, 116, 12, stone_lo, 1)
    c.draw_text_in_box(3, 3, 114, 10, "U.S. POST OFFICE", incised, font="5x7", spacing=1, shadow=incised_hi, pad_x=4, pad_y=1)

    # 2) date_stone [0, 16, 40, 14] — Carved '1938'
    c.rect(0, 16, 40, 14, stone)
    c.border(0, 16, 40, 14, outline, 1)
    c.rect(1, 17, 38, 1, stone_hi)
    c.border(2, 18, 36, 10, stone_lo, 1)
    c.draw_text_in_box(3, 19, 34, 8, "1938", incised, font="5x7", spacing=1, shadow=incised_hi, pad_x=2, pad_y=0)

    # 3) mail_plaque [40, 16, 40, 14] — Small bronze (1938) or postal-navy (1988) entrance plaque
    if year == 1938:
        c.rect(40, 16, 40, 14, (74, 54, 34, 255))
        c.border(40, 16, 40, 14, (214, 180, 92, 255), 1)
        c.draw_text_in_box(42, 18, 36, 10, "WPA 1938", (236, 206, 122, 255), font="3x5", spacing=1, pad_x=1, pad_y=1)
    else:
        c.rect(40, 16, 40, 14, (40, 68, 122, 255))
        c.border(40, 16, 40, 14, (236, 234, 228, 255), 1)
        c.draw_text_in_box(42, 18, 36, 10, "ZIP MAIL", (242, 240, 234, 255), font="3x5", spacing=1, pad_x=1, pad_y=1)

    # 4) civic_flag [0, 32, 32, 20] — Muted civic flag (32x20 exact fit)
    c.rect(0, 32, 32, 20, (46, 68, 104, 255))
    c.border(0, 32, 32, 20, outline, 1)
    for sy in range(34, 50, 4):
        c.rect(12, sy, 18, 2, (166, 54, 48, 255) if ((sy - 34) // 4) % 2 == 0 else (228, 220, 200, 255))
    c.rect(2, 34, 10, 10, (36, 54, 88, 255))
    for dy in (36, 40):
        for dx in (4, 8):
            c.put(dx, dy, (236, 228, 206, 255))

    # 5) loading_sign [64, 36, 64, 24] — Rear dock notice 'MAIL TRUCKS'
    c.rect(64, 36, 64, 24, (234, 230, 220, 255))
    c.border(64, 36, 64, 24, (42, 68, 118, 255), 2)
    c.draw_text_in_box(66, 38, 60, 10, "MAIL DOCK", (42, 68, 118, 255), font="5x7", spacing=1, pad_x=1, pad_y=1)
    c.draw_text_in_box(66, 49, 60, 9, "AUTHORIZED", (58, 52, 48, 255), font="3x5", spacing=1, pad_x=2, pad_y=1)

    c.save_2x(TEX_DIR / f"signs_{year}.png")
    return {
        "main_entablature": [0, 0, 120, 16],
        "date_stone": [0, 16, 40, 14],
        "mail_plaque": [40, 16, 40, 14],
        "civic_flag": [0, 32, 32, 20],
        "loading_sign": [64, 36, 64, 24],
    }


def make_glow(year):
    """
    256x64 texels (128x32 art px) glow atlas + pure-black emissive mask:
      - backlit_monument: [0, 0, 44, 18]   (1988 compact two-line 'POST / OFFICE' sidewalk monument; black in 1938)
      - warm_lantern:     [48, 0, 16, 12]  (classical bronze entrance lanterns, lobby lights, rear dock bulb)
      - cupola_glow:      [64, 0, 16, 12]  (subtle warm amber cupola louver glow at night)
    """
    base = ArtCanvas(128, 32, (38, 36, 34, 255))
    mask = ArtCanvas(128, 32, (0, 0, 0, 255))
    outline = (34, 30, 28, 255)

    # 1) Compact human-scaled Backlit Sidewalk Monument Sign [0, 0, 44, 18] — ONLY lit in 1988!
    if year == 1988:
        base.rect(0, 0, 44, 18, (238, 236, 228, 255))
        base.border(0, 0, 44, 18, (38, 62, 112, 255), 1)
        base.rect(1, 15, 42, 2, (182, 46, 42, 255))
        base.draw_text_in_box(2, 1, 40, 7, "POST", (34, 58, 108, 255), font="3x5", spacing=1, pad_x=2, pad_y=1)
        base.draw_text_in_box(2, 8, 40, 7, "OFFICE", (34, 58, 108, 255), font="3x5", spacing=1, pad_x=2, pad_y=1)

        mask.rect(1, 1, 42, 14, (226, 220, 198, 255))
        mask.rect(1, 15, 42, 2, (164, 46, 40, 255))
        mask.draw_text_in_box(2, 1, 40, 7, "POST", (22, 34, 62, 255), font="3x5", spacing=1, pad_x=2, pad_y=1)
        mask.draw_text_in_box(2, 8, 40, 7, "OFFICE", (22, 34, 62, 255), font="3x5", spacing=1, pad_x=2, pad_y=1)
    else:
        base.rect(0, 0, 44, 18, (52, 48, 44, 255))

    # 2) Warm bronze entrance lantern & lobby light [48, 0, 16, 12]
    base.rect(48, 0, 16, 12, (232, 198, 122, 255))
    base.border(48, 0, 16, 12, outline, 1)
    base.rect(51, 2, 10, 8, (254, 234, 174, 255))

    mask.rect(50, 1, 12, 10, (242, 204, 122, 255))
    mask.rect(52, 3, 8, 6, (255, 240, 188, 255))

    # 3) Subtle amber cupola lantern glow [64, 0, 16, 12]
    base.rect(64, 0, 16, 12, (198, 162, 92, 255))
    base.border(64, 0, 16, 12, outline, 1)
    base.rect(67, 2, 10, 8, (238, 204, 128, 255))

    mask.rect(66, 1, 12, 10, (186, 146, 78, 255))
    mask.rect(68, 3, 8, 6, (224, 186, 106, 255))

    base.save_2x(TEX_DIR / f"glow_base_{year}.png")
    mask.save_2x(TEX_DIR / f"glow_mask_{year}.png")
    return {
        "backlit_monument": [0, 0, 44, 18],
        "warm_lantern": [48, 0, 16, 12],
        "cupola_glow": [64, 0, 16, 12],
    }


def main():
    shared = extract_swatches()
    make_stone_trim()
    make_wpa_mural()
    regions = {}
    for year in (1938, 1988):
        make_glass(year)
        det = make_details(year)
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
    print("Generated all WPA Post Office (1938 & 1988) LimeZu swatches, WPA mural, and 2x2 art-pixel textures.")


if __name__ == "__main__":
    main()
