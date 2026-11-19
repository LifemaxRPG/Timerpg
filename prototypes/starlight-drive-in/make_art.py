#!/usr/bin/env python3
"""Cut shared LimeZu swatches from atlas.png and paint original pixel artwork at 1x art resolution, enlarged 2x."""
import json
import struct
import zlib
from pathlib import Path
from pixel_font import FONT

R = Path(__file__).resolve().parent
TEX_DIR = R / 'textures'
SRC_DIR = R / 'source-materials'
TEX_DIR.mkdir(parents=True, exist_ok=True)
SRC_DIR.mkdir(parents=True, exist_ok=True)

ATLAS_DIR = R.parent.parent / 'kotlin-street' / 'tools' / 'swatches' / 'atlas'
REG = {}


def hex_rgba(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


INK = hex_rgba('#34384c')
OUTLINE = hex_rgba('#3a3a50')
CREAM = hex_rgba('#eee9df')
CREAM_HI = hex_rgba('#fff6e7')
CREAM_SH = hex_rgba('#d8cfc0')
CREAM_DK = hex_rgba('#bfb39f')
TEAL = hex_rgba('#46756a')
TEAL_HI = hex_rgba('#76998a')
TEAL_SH = hex_rgba('#396157')
TEAL_DK = hex_rgba('#2b4c44')
RED = hex_rgba('#a14f44')
RED_HI = hex_rgba('#c46d5e')
RED_SH = hex_rgba('#853c35')
GOLD = hex_rgba('#ffe6ab')
GOLD_SH = hex_rgba('#e0b862')
STEEL = hex_rgba('#7d7f99')
STEEL_HI = hex_rgba('#a2a5bf')
STEEL_SH = hex_rgba('#5d6078')
CHARCOAL = hex_rgba('#34384c')
CHARCOAL_HI = hex_rgba('#505670')
CHARCOAL_SH = hex_rgba('#252838')
SKY_DUSK = hex_rgba('#496a80')
SKY_HORIZON = hex_rgba('#d99b66')
SCREEN_WHITE = hex_rgba('#e8e4da')
SCREEN_SH = hex_rgba('#cfc8b8')


def read_png_rgba(path):
    data = Path(path).read_bytes()
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    pos = 8
    w = h = depth = ctype = 0
    plte = []
    trns = b''
    idat = bytearray()
    while pos < len(data):
        length, ctype_bytes = struct.unpack('>I4s', data[pos:pos + 8])
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        t = ctype_bytes.decode('latin1')
        if t == 'IHDR':
            w, h, depth, ctype, _, _, _ = struct.unpack('>IIBBBBB', chunk)
        elif t == 'PLTE':
            plte = [(chunk[i], chunk[i + 1], chunk[i + 2]) for i in range(0, len(chunk), 3)]
        elif t == 'tRNS':
            trns = chunk
        elif t == 'IDAT':
            idat.extend(chunk)
        elif t == 'IEND':
            break
    raw = zlib.decompress(bytes(idat))
    ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    bpp = max(1, (ch * depth) // 8)
    stride = (w * ch * depth + 7) // 8
    prev = bytearray(stride)
    cur = bytearray(stride)
    out = [[(0, 0, 0, 255)] * w for _ in range(h)]
    idx = 0
    for y in range(h):
        f = raw[idx]
        idx += 1
        for i in range(stride):
            x = raw[idx]
            idx += 1
            a = cur[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if f == 0:
                v = x
            elif f == 1:
                v = (x + a) & 0xFF
            elif f == 2:
                v = (x + b) & 0xFF
            elif f == 3:
                v = (x + ((a + b) >> 1)) & 0xFF
            else:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                v = (x + pr) & 0xFF
            cur[i] = v
        for x in range(w):
            if ctype == 6:
                out[y][x] = (cur[x * 4], cur[x * 4 + 1], cur[x * 4 + 2], cur[x * 4 + 3])
            elif ctype == 2:
                out[y][x] = (cur[x * 3], cur[x * 3 + 1], cur[x * 3 + 2], 255)
            elif ctype == 3:
                per = 8 // depth
                bval = cur[x // per]
                shift = 8 - depth * (x % per + 1)
                pi = (bval >> shift) & ((1 << depth) - 1)
                r, g, b_ = plte[pi]
                al = trns[pi] if pi < len(trns) else 255
                out[y][x] = (r, g, b_, al)
        prev[:] = cur
    return w, h, out


def write_png_rgba(path, w, h, rows):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        row = rows[y]
        for x in range(w):
            r, g, b, a = row[x]
            raw.extend((r, g, b, a))

    def chunk(tag, body):
        crc = zlib.crc32(tag + body) & 0xFFFFFFFF
        return struct.pack('>I', len(body)) + tag + body + struct.pack('>I', crc)

    ihdr = struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)
    comp = zlib.compress(bytes(raw), 9)
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr) + chunk(b'IDAT', comp) + chunk(b'IEND', b'')
    Path(path).write_bytes(png)


class Canvas:
    """Art-pixel canvas (1x art pixels); saved at 2x texel resolution via NEAREST."""
    def __init__(self, w, h, fill=INK):
        self.w = w
        self.h = h
        self.px = [[fill for _ in range(w)] for _ in range(h)]

    def point(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = c

    def line_h(self, x0, x1, y, c):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            self.point(x, y, c)

    def line_v(self, x, y0, y1, c):
        for y in range(min(y0, y1), max(y0, y1) + 1):
            self.point(x, y, c)

    def rect(self, x0, y0, x1, y1, fill=None, outline=None):
        if fill is not None:
            for y in range(y0, y1 + 1):
                for x in range(x0, x1 + 1):
                    self.point(x, y, fill)
        if outline is not None:
            self.line_h(x0, x1, y0, outline)
            self.line_h(x0, x1, y1, outline)
            self.line_v(x0, y0, y1, outline)
            self.line_v(x1, y0, y1, outline)

    def stepped_panel(self, x0, y0, w, h, base, hi, sh, dk=INK, ribs=0, wear_seed=7):
        x1, y1 = x0 + w - 1, y0 + h - 1
        for y in range(y0, y1 + 1):
            ry = y - y0
            for x in range(x0, x1 + 1):
                rx = x - x0
                if rx == 0 or ry == 0 or rx == w - 1 or ry == h - 1:
                    c = dk
                elif ry == 1 or rx == 1:
                    c = hi
                elif ry == h - 2 or rx == w - 2:
                    c = sh
                else:
                    bayer = ((rx & 1) << 1) | (ry & 1)
                    chip = ((rx * 37 + ry * 73 + wear_seed * 19) & 63) == 0
                    if ribs > 0 and (rx % ribs) == 0:
                        c = sh
                    elif ribs > 0 and (rx % ribs) == 1:
                        c = hi
                    elif chip:
                        c = sh
                    elif ry < h // 4 and bayer == 0:
                        c = hi
                    elif ry > (h * 2) // 3 and bayer >= 2:
                        c = sh
                    else:
                        c = base
                self.point(x, y, c)

    def text(self, x, y, s, c, shadow=None):
        cx = x
        for ch in s.upper():
            rows = FONT.get(ch, ['00000'] * 7)
            for yy, row in enumerate(rows):
                for xx, v in enumerate(row):
                    if v == '1':
                        if shadow is not None:
                            self.point(cx + xx + 1, y + yy + 1, shadow)
                        self.point(cx + xx, y + yy, c)
            cx += 6

    def save_2x(self, filename):
        tw, th = self.w * 2, self.h * 2
        rows = [[self.px[y // 2][x // 2] for x in range(tw)] for y in range(th)]
        write_png_rgba(TEX_DIR / filename, tw, th, rows)


# 1. Cut shared LimeZu swatches directly from kotlin-street/tools/swatches/atlas/atlas.png
atlas_w, atlas_h, atlas_px = read_png_rgba(ATLAS_DIR / 'atlas.png')
atlas_meta = json.loads((ATLAS_DIR / 'atlas.json').read_text())

SWATCH_IDS = [
    'brick_red',
    'block_courses',
    'ballast',
    'roof_tin_blue',
    'plank_vertical',
    'asphalt_worn',
    'pavement',
]

provenance_entries = []
for sid in SWATCH_IDS:
    entry = atlas_meta[sid]
    ax, ay, aw, ah = entry['x'], entry['y'], entry['w'], entry['h']
    crop_rows = [atlas_px[ay + y][ax:ax + aw] for y in range(ah)]
    write_png_rgba(TEX_DIR / f'{sid}.png', aw, ah, crop_rows)
    write_png_rgba(SRC_DIR / f'{sid}.png', aw, ah, crop_rows)
    provenance_entries.append({
        'id': sid,
        'atlas_rect': [ax, ay, aw, ah],
        'source': entry.get('entry', {}),
    })

(SRC_DIR / 'provenance.json').write_text(json.dumps({
    'atlas': 'kotlin-street/tools/swatches/atlas/atlas.png',
    'licence': 'LimeZu: Timerpg game use only, credit required',
    'swatches': provenance_entries,
}, indent=2) + '\n')

# 2. Supplemental original pixel-art textures (painted at art resolution, enlarged 2x)
# 2a. cream.png (64x64 art -> 128x128 texels): weathered porcelain cream enamel panels
c_cream = Canvas(64, 64, CREAM)
c_cream.stepped_panel(0, 0, 64, 32, CREAM, CREAM_HI, CREAM_SH, OUTLINE, ribs=16, wear_seed=11)
c_cream.stepped_panel(0, 32, 64, 32, CREAM, CREAM_HI, CREAM_SH, OUTLINE, ribs=16, wear_seed=23)
c_cream.save_2x('cream.png')

# 2b. teal.png (64x64 art -> 128x128 texels): Googie ribbed teal porcelain enamel
c_teal = Canvas(64, 64, TEAL)
c_teal.stepped_panel(0, 0, 64, 64, TEAL, TEAL_HI, TEAL_SH, OUTLINE, ribs=8, wear_seed=37)
c_teal.save_2x('teal.png')

# 2c. utility.png (128x128 art -> 256x256 texels): four 64x64 quadrants (no bleed)
c_util = Canvas(128, 128, INK)
# Quadrant 0: metal (steel I-beam / speaker post stanchion / A-frame truss)
c_util.stepped_panel(0, 0, 64, 64, STEEL, STEEL_HI, STEEL_SH, OUTLINE, ribs=8, wear_seed=41)
for ry in range(4, 60, 8):
    for rx in range(4, 60, 8):
        c_util.point(rx, ry, STEEL_HI)
        c_util.point(rx + 1, ry + 1, OUTLINE)
REG['metal'] = {'rect_art': [0, 0, 64, 64]}

# Quadrant 1: rubber (dark acoustic screen baffle hood / trim / dumpster lid)
c_util.stepped_panel(64, 0, 64, 64, CHARCOAL, CHARCOAL_HI, CHARCOAL_SH, OUTLINE, ribs=16, wear_seed=53)
REG['rubber'] = {'rect_art': [64, 0, 64, 64]}

# Quadrant 2: coral (terracotta Googie accent enamel / striped awning ribs / marquee arrow)
c_util.stepped_panel(0, 64, 64, 64, RED, RED_HI, RED_SH, OUTLINE, ribs=8, wear_seed=67)
REG['coral'] = {'rect_art': [0, 64, 64, 64]}

# Quadrant 3: paint (cast-aluminum speaker housing & ceramic mosaic accents)
c_util.stepped_panel(64, 64, 64, 64, CREAM_SH, CREAM_HI, CREAM_DK, OUTLINE, ribs=4, wear_seed=79)
for gy in range(70, 122, 4):
    c_util.line_h(70, 122, gy, OUTLINE)
REG['paint'] = {'rect_art': [64, 64, 64, 64]}
c_util.save_2x('utility.png')

# 2d. glass.png (256x128 art -> 512x256 texels): concession, box office, ticket kiosk & projection ports
c_glass = Canvas(256, 128, (0, 0, 0, 0))


def draw_glass_region(key, rect, label=None, mullion_step=18):
    x, y, w, h = rect
    REG[key] = {'rect_art': rect}
    glass_fill = (83, 127, 139, 108)
    glass_hi = (135, 178, 190, 140)
    glass_sh = (58, 94, 106, 125)
    c_glass.rect(x, y, x + w - 1, y + h - 1, fill=glass_fill, outline=INK)
    c_glass.line_h(x + 1, x + w - 2, y + 1, CREAM)
    c_glass.line_v(x + 1, y + 1, y + h - 2, glass_hi)
    c_glass.line_h(x + 1, x + w - 2, y + h - 2, glass_sh)
    # Diagonal pixel reflection streaks
    for d in range(4, w + h - 4, 14):
        for t in range(3, h - 3):
            px = x + d - t
            py = y + t
            if x + 2 <= px < x + w - 2 and y + 2 <= py < y + h - 2:
                c_glass.point(px, py, glass_hi)
    # Mullions
    for mx in range(x + mullion_step, x + w - 2, mullion_step):
        c_glass.line_v(mx, y, y + h - 1, INK)
        c_glass.line_v(mx + 1, y + 1, y + h - 2, CREAM_SH)
    if label:
        lw = len(label) * 6 + 4
        lx = x + max(2, (w - lw) // 2)
        ly = y + h - 13
        c_glass.rect(lx, ly, lx + lw - 1, ly + 10, fill=CREAM, outline=INK)
        c_glass.text(lx + 3, ly + 2, label, RED)


draw_glass_region('front_left', [0, 0, 72, 32], 'SNACK BAR', 18)
draw_glass_region('front_right', [74, 0, 72, 32], 'BOX OFFICE', 18)
draw_glass_region('entry', [148, 0, 32, 36], None, 16)
draw_glass_region('transom', [182, 0, 32, 12], None, 16)
draw_glass_region('ticket_front', [216, 0, 24, 24], '50C', 12)
draw_glass_region('side_win', [0, 36, 48, 32], 'DRIVE-IN', 16)
draw_glass_region('walkup', [50, 36, 48, 24], 'POPCORN', 16)
draw_glass_region('proj_ports', [100, 36, 72, 16], None, 24)
# Draw optical projector lens ports inside proj_ports
for lx in (112, 136, 160):
    c_glass.rect(lx - 5, 40, lx + 5, 48, fill=CHARCOAL, outline=CREAM)
    c_glass.rect(lx - 2, 42, lx + 2, 46, fill=GOLD, outline=INK)
c_glass.save_2x('glass.png')

# 2e. signs.png (256x128 art -> 512x256 texels): neon signs, attraction board, lamps, and widescreen feature art
c_signs = Canvas(256, 128, TEAL_DK)

# Title sign: STARLIGHT DRIVE-IN (112x14 art)
REG['title'] = {'rect_art': [0, 0, 112, 14]}
c_signs.stepped_panel(0, 0, 112, 14, TEAL, TEAL_HI, TEAL_SH, INK, ribs=0, wear_seed=1)
c_signs.rect(1, 1, 110, 12, outline=GOLD)
c_signs.text(5, 4, 'STARLIGHT DRIVE-IN', CREAM, shadow=INK)

# Pylon header: STARLIGHT (72x14 art)
REG['pylon_top'] = {'rect_art': [114, 0, 72, 14]}
c_signs.stepped_panel(114, 0, 72, 14, RED, RED_HI, RED_SH, INK, ribs=0, wear_seed=2)
c_signs.rect(115, 1, 184, 12, outline=GOLD)
c_signs.text(123, 4, 'STARLIGHT', GOLD, shadow=INK)

# Snack bar sign: SNACK BAR (64x12 art)
REG['snack_sign'] = {'rect_art': [188, 0, 64, 12]}
c_signs.stepped_panel(188, 0, 64, 12, RED, RED_HI, RED_SH, INK, ribs=0, wear_seed=3)
c_signs.text(194, 3, 'SNACK BAR', CREAM, shadow=INK)

# Attraction reader board (72x24 art)
REG['reader'] = {'rect_art': [0, 16, 72, 24]}
c_signs.stepped_panel(0, 16, 72, 24, CREAM, CREAM_HI, CREAM_SH, INK, ribs=0, wear_seed=4)
for ry in (24, 32):
    c_signs.line_h(2, 69, ry, CREAM_DK)
c_signs.text(4, 18, 'NOW SHOWING', RED)
c_signs.text(4, 29, 'NIGHT REEL', INK)

# Ticket canopy plaque: TICKETS (48x12 art)
REG['tickets'] = {'rect_art': [74, 16, 48, 12]}
c_signs.stepped_panel(74, 16, 48, 12, TEAL, TEAL_HI, TEAL_SH, INK, ribs=0, wear_seed=5)
c_signs.text(78, 19, 'TICKETS', GOLD, shadow=INK)

# Lamp soffit & speaker post amber dome (16x4 art)
REG['lamp'] = {'rect_art': [124, 16, 16, 4]}
c_signs.rect(124, 16, 139, 19, fill=GOLD, outline=INK)
c_signs.line_h(125, 138, 17, CREAM_HI)

# Starburst neon core (24x24 art)
REG['starburst'] = {'rect_art': [142, 16, 24, 24]}
c_signs.stepped_panel(142, 16, 24, 24, RED, RED_HI, RED_SH, INK, wear_seed=6)
c_signs.rect(145, 19, 162, 36, outline=GOLD)
c_signs.rect(149, 23, 158, 32, fill=GOLD, outline=INK)

# Widescreen Drive-In Silver Screen (192x72 art -> 14.90m x 5.59m active picture area)
REG['screen_canvas'] = {'rect_art': [0, 44, 192, 72]}
c_signs.stepped_panel(0, 44, 192, 72, SCREEN_WHITE, CREAM_HI, SCREEN_SH, INK, wear_seed=9)
# Cinema dusk sky & desert horizon frame inside the silver screen
c_signs.rect(4, 48, 187, 111, fill=SKY_DUSK, outline=INK)
# Stepped sunset bands with Bayer dither
for sy in range(49, 111):
    ry = sy - 49
    for sx in range(5, 187):
        bayer = ((sx & 1) << 1) | (sy & 1)
        if ry < 20:
            col = TEAL_DK if (ry < 10 and bayer >= 2) else SKY_DUSK
        elif ry < 38:
            col = SKY_HORIZON if (ry > 28 or bayer >= 2) else SKY_DUSK
        elif ry < 46:
            col = GOLD if bayer < 2 else SKY_HORIZON
        else:
            # Silhouette mesa range along the bottom of the movie frame
            mesa = 46 - int(6 * ((sx * 13) % 17 == 0) + 4 * (((sx // 8) % 3) == 0))
            col = CHARCOAL_SH if ry >= mesa else GOLD_SH
        c_signs.point(sx, sy, col)
# Classic intermission title card on the drive-in screen
c_signs.rect(24, 56, 167, 86, fill=TEAL_DK, outline=GOLD)
c_signs.text(42, 61, 'STARLIGHT DRIVE-IN', GOLD, shadow=INK)
c_signs.text(39, 73, 'FEATURE PRESENTATION', CREAM, shadow=INK)

c_signs.save_2x('signs.png')

(R / 'art-regions.json').write_text(json.dumps(REG, indent=2) + '\n')
print('Generated 12 textures and art-regions.json successfully.')
