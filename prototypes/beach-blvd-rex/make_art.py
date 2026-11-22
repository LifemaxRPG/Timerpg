#!/usr/bin/env python3
"""
Generates 2x2 art-pixel textures (0.0388 m/texel -> 0.0776 m/art-px) for the
1-to-1 copy of "Rex" — the 1964 Jacksonville Beach Blvd roadside dinosaur statue
(10150 Beach Blvd, Jacksonville, FL — post-2007 restoration look):
  - skin_rex_rough.png  : heavy pebbled/scaly vermilion-orange-red stucco (crown, back, legs, tail)
  - skin_rex_smooth.png : smooth vermilion-orange-red painted plaster (face, throat, arms) + nostril panel
  - skin_rex_belly.png  : smooth teal/turquoise belly patch with subtle weathered ochre patina
  - bone_toes_teeth.png : white concrete femur bone cane, pale yellow-cream toenails, dark mouth & white teeth
  - glow_base.png / glow_mask.png : recessed dark eye socket, glowing ruby-red lightbulb eye, ground uplight lens
  - lot_fence.png       : alpha-cutout black wrought-iron picket fence + solid iron post/rail
  - lot_ground.png      : dark pine-bark mulch, Florida turf grass, modest angled bronze plaque
  - Shared LimeZu swatches sliced from kotlin-street/tools/swatches/atlas/atlas.png:
      pavement.png, asphalt_worn.png
"""
import json
import math
import struct
import zlib
from pathlib import Path

from pixel_font import FONT_5X7, measure_text

ROOT = Path(__file__).resolve().parent
TEX_DIR = ROOT / "textures"
TEX_DIR.mkdir(parents=True, exist_ok=True)

ATLAS_PATH = ROOT.parent.parent / "kotlin-street" / "tools" / "swatches" / "atlas" / "atlas.png"
ATLAS_JSON = ROOT.parent.parent / "kotlin-street" / "tools" / "swatches" / "atlas" / "atlas.json"

TEXEL_M = 0.0388
ART_PX_M = TEXEL_M * 2.0


def _crc32(data: bytes) -> int:
    return zlib.crc32(data) & 0xFFFFFFFF


def _png_chunk(ctype: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + ctype + data + struct.pack(">I", _crc32(ctype + data))


def write_png(path: Path, width: int, height: int, rgba: bytes):
    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)
        raw.extend(rgba[y * stride:(y + 1) * stride])
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    idat = zlib.compress(bytes(raw), 6)
    png = b"\x89PNG\r\n\x1a\n" + _png_chunk(b"IHDR", ihdr) + _png_chunk(b"IDAT", idat) + _png_chunk(b"IEND", b"")
    path.write_bytes(png)


def decode_png_rgba(path: Path):
    buf = path.read_bytes()
    assert buf[:8] == b"\x89PNG\r\n\x1a\n"
    pos = 8
    w = h = 0
    idat = bytearray()
    while pos < len(buf):
        length = struct.unpack(">I", buf[pos:pos + 4])[0]
        ctype = buf[pos + 4:pos + 8]
        chunk = buf[pos + 8:pos + 8 + length]
        pos += 12 + length
        if ctype == b"IHDR":
            w, h = struct.unpack(">II", chunk[:8])
        elif ctype == b"IDAT":
            idat.extend(chunk)
    raw = zlib.decompress(bytes(idat))
    stride = w * 4
    out = bytearray(w * h * 4)
    idx = 0
    for y in range(h):
        flt = raw[idx]
        idx += 1
        row_off = y * stride
        prev_off = (y - 1) * stride
        for i in range(stride):
            x = raw[idx]
            idx += 1
            a = out[row_off + i - 4] if i >= 4 else 0
            b = out[prev_off + i] if y > 0 else 0
            c = out[prev_off + i - 4] if (y > 0 and i >= 4) else 0
            if flt == 0:
                val = x
            elif flt == 1:
                val = (x + a) & 0xFF
            elif flt == 2:
                val = (x + b) & 0xFF
            elif flt == 3:
                val = (x + ((a + b) >> 1)) & 0xFF
            elif flt == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                val = (x + pr) & 0xFF
            else:
                raise ValueError(f"Unsupported PNG filter {flt}")
            out[row_off + i] = val
    return w, h, bytes(out)


class ArtCanvas:
    """Draws on an art-pixel grid and upscales 2x2 cleanly to texels."""
    def __init__(self, aw: int, ah: int, fill=(0, 0, 0, 255)):
        self.aw = aw
        self.ah = ah
        self.px = [fill] * (aw * ah)

    def set(self, x: int, y: int, col):
        if 0 <= x < self.aw and 0 <= y < self.ah:
            if len(col) == 3:
                col = (col[0], col[1], col[2], 255)
            self.px[y * self.aw + x] = col

    def get(self, x: int, y: int):
        if 0 <= x < self.aw and 0 <= y < self.ah:
            return self.px[y * self.aw + x]
        return (0, 0, 0, 0)

    def rect(self, x0: int, y0: int, w: int, h: int, col):
        for y in range(y0, y0 + h):
            for x in range(x0, x0 + w):
                self.set(x, y, col)

    def save(self, path: Path):
        w, h = self.aw * 2, self.ah * 2
        buf = bytearray(w * h * 4)
        for ay in range(self.ah):
            for ax in range(self.aw):
                r, g, b, a = self.px[ay * self.aw + ax]
                for dy in (0, 1):
                    row = (ay * 2 + dy) * w
                    for dx in (0, 1):
                        off = (row + ax * 2 + dx) * 4
                        buf[off] = r
                        buf[off + 1] = g
                        buf[off + 2] = b
                        buf[off + 3] = a
        write_png(path, w, h, bytes(buf))


def h2d(x: int, y: int, seed: int = 0) -> float:
    n = (x * 374761393 + y * 668265263 + seed * 1442695041) & 0xFFFFFFFF
    n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0


def clamp_rgb(r, g, b, a=255):
    return (max(0, min(255, int(r))), max(0, min(255, int(g))), max(0, min(255, int(b))), int(a))


def slice_swatches():
    meta = json.loads(ATLAS_JSON.read_text())
    aw, ah, pixels = decode_png_rgba(ATLAS_PATH)
    entries = meta.get("swatches", meta.get("tiles", meta))
    if isinstance(entries, list):
        lookup = {e["name"]: e for e in entries if "name" in e}
    else:
        lookup = entries

    used = []
    for name in ("pavement", "asphalt_worn"):
        info = lookup[name]
        sx, sy, sw, sh = info["x"], info["y"], info["w"], info["h"]
        out = bytearray(sw * sh * 4)
        for y in range(sh):
            src_off = ((sy + y) * aw + sx) * 4
            dst_off = y * sw * 4
            out[dst_off:dst_off + sw * 4] = pixels[src_off:src_off + sw * 4]
        write_png(TEX_DIR / f"{name}.png", sw, sh, bytes(out))
        used.append(name)
    return used


def make_skin_rex_rough():
    """
    Heavy organic pebbled/scaly vermilion-orange-red stucco seen on Rex's crown hood,
    back of neck, entire back, flanks, chunky legs, and ground tail!
    Uses finer seamless Voronoi pebbled cells (22x22) with rich burnt-terracotta crevices
    (NOT near-black blotches!) so from medium distance Rex glows vibrant vermilion-orange
    exactly matching real Beach Blvd photos (rex-ref-front & rex-ref-angle3).
    """
    c = ArtCanvas(64, 64)
    gx, gy = 22, 22
    cell_w, cell_h = 64.0 / gx, 64.0 / gy
    centers = []
    for cy in range(gy):
        for cx in range(gx):
            jx = (h2d(cx, cy, 11) - 0.5) * 0.70
            jy = (h2d(cx, cy, 23) - 0.5) * 0.70
            px = (cx + 0.5 + jx) * cell_w
            py = (cy + 0.5 + jy) * cell_h
            tone = h2d(cx, cy, 37)
            centers.append((px, py, tone))

    for y in range(64):
        for x in range(64):
            d1 = 1e9
            d2 = 1e9
            best_dx = 0.0
            best_dy = 0.0
            best_tone = 0.5
            bx0 = int(x / cell_w)
            by0 = int(y / cell_h)
            for oy in (-1, 0, 1):
                for ox in (-1, 0, 1):
                    ncx = (bx0 + ox) % gx
                    ncy = (by0 + oy) % gy
                    px, py, tone = centers[ncy * gx + ncx]
                    dx = x + 0.5 - px
                    dy = y + 0.5 - py
                    if dx > 32.0:
                        dx -= 64.0
                    elif dx < -32.0:
                        dx += 64.0
                    if dy > 32.0:
                        dy -= 64.0
                    elif dy < -32.0:
                        dy += 64.0
                    dist = math.hypot(dx * 0.85, dy * 1.18)
                    if dist < d1:
                        d2 = d1
                        d1 = dist
                        best_dx = dx
                        best_dy = dy
                        best_tone = tone
                    elif dist < d2:
                        d2 = dist

            edge = d2 - d1
            jit = (h2d(x, y, 47) - 0.5) * 10
            # Warm burnt-sienna/brick-red crevices (preserves bright vermilion body hue!)
            if edge < 0.34:
                r, g, b = 158, 42, 18
            elif edge < 0.64:
                r, g, b = 192, 56, 24
            else:
                light = (-best_dy * 0.45 - best_dx * 0.25)
                if light > 0.30:
                    r, g, b = 246, 98, 44
                elif light > -0.25:
                    r, g, b = 228, 78, 32
                else:
                    r, g, b = 208, 64, 26
            dv = int((best_tone - 0.5) * 16 + jit)
            c.set(x, y, clamp_rgb(r + dv, g + dv * 0.52, b + dv * 0.32))
    c.save(TEX_DIR / "skin_rex_rough.png")


def make_skin_rex_smooth():
    """
    Smooth painted vermilion-orange-red plaster for Rex's face, snout, chin, front throat,
    and both smooth cylindrical arms — PLUS a dedicated top-right panel with Rex's iconic
    bold dark-outlined 'OO' nostrils on the blunt snout front!
    Atlas layout (64x32 art px):
      - plain   : (0,  0, 32, 32)  smooth painted vermilion plaster
      - snout   : (32, 0, 32, 32)  smooth vermilion snout front with bold dark-outlined 'OO' oval nostrils!
    """
    c = ArtCanvas(64, 32)
    for y in range(32):
        for x in range(64):
            n1 = h2d(x, y, 53)
            n2 = h2d(x // 2, y // 2, 71)
            r = 228 + (n1 - 0.5) * 10 + (n2 - 0.5) * 8
            g = 78 + (n1 - 0.5) * 8 + (n2 - 0.5) * 6
            b = 32 + (n1 - 0.5) * 6 + (n2 - 0.5) * 4
            c.set(x, y, clamp_rgb(r, g, b))

    # Paint the two iconic bold dark-outlined 'OO' nostrils in the 'snout' region (32..64, 0..32)
    # Directly matches rex-ref-angle2.png: large, bold, slightly squarish dark ovals side-by-side!
    for nx_c in (40, 56):
        ny_c = 16
        for dy in range(-9, 10):
            for dx in range(-7, 8):
                # Superellipse (n=2.6) gives the exact slightly boxy-oval 'OO' shape from rex-ref-angle2!
                dist = (abs(dx / 5.6) ** 2.6 + abs(dy / 7.2) ** 2.6) ** (1.0 / 2.6)
                if 0.54 <= dist <= 1.08:
                    c.set(nx_c + dx, ny_c + dy, (36, 18, 12))
                elif dist < 0.54:
                    c.set(nx_c + dx, ny_c + dy, (194, 64, 26))

    c.save(TEX_DIR / "skin_rex_smooth.png")
    return {
        "plain": [0, 0, 32, 32],
        "snout": [32, 0, 32, 32],
    }


def make_skin_rex_belly():
    """
    Smooth turquoise-teal teardrop belly patch (post-2007 restoration look),
    with the signature weathered golden-ochre worn center patina visible in rex-ref-front & rex-ref-angle3!
    """
    c = ArtCanvas(32, 32)
    for y in range(32):
        for x in range(32):
            n1 = h2d(x, y, 97)
            n2 = h2d(x // 2, y // 2, 103)
            n3 = h2d(x // 3, y // 3, 113)
            # Vivid coastal turquoise-teal border (#329498)
            r = 50 + (n1 - 0.5) * 12 + (n2 - 0.5) * 10
            g = 148 + (n1 - 0.5) * 14 + (n2 - 0.5) * 12
            b = 152 + (n1 - 0.5) * 14 + (n2 - 0.5) * 10
            # Weathered warm ochre/olive-yellow center patch matching real photos
            d_center = math.hypot((x - 15.5) / 8.5, (y - 18.0) / 9.5)
            if d_center < 1.05 and n3 > 0.36:
                t = max(0.0, min(0.68, (1.05 - d_center) * 0.72 + (n3 - 0.5) * 0.25))
                r = r * (1 - t) + 178 * t
                g = g * (1 - t) + 152 * t
                b = b * (1 - t) + 74 * t
            c.set(x, y, clamp_rgb(r, g, b))
    c.save(TEX_DIR / "skin_rex_belly.png")


def make_bone_toes_teeth():
    """
    64x32 art-px atlas:
      - bone    : (0,  0, 32, 32)  clean weathered white painted concrete femur bone cane
      - toes    : (32, 0, 16, 16)  pale yellow-cream stubby toenails
      - teeth   : (48, 0, 16, 16)  bright white neat teeth
      - mouth   : (32, 16, 32, 16) pitch-dark charcoal-black mouth interior
    """
    c = ArtCanvas(64, 32)
    # White concrete bone
    for y in range(32):
        for x in range(32):
            n = h2d(x, y, 131)
            n2 = h2d(x // 3, y // 3, 137)
            v = 234 + (n - 0.5) * 12 + (n2 - 0.5) * 10
            c.set(x, y, clamp_rgb(v, v + 1, v + 2))
    # Pale yellow-cream toenails (distinctive warm butter-cream in real photos!)
    for y in range(16):
        for x in range(32, 48):
            n = h2d(x, y, 149)
            c.set(x, y, clamp_rgb(232 + (n - 0.5) * 12, 222 + (n - 0.5) * 12, 146 + (n - 0.5) * 14))
    # Crisp bright white teeth
    for y in range(16):
        for x in range(48, 64):
            n = h2d(x, y, 157)
            v = 244 + (n - 0.5) * 8
            c.set(x, y, clamp_rgb(v, v, v - 2))
    # Dark mouth interior
    for y in range(16, 32):
        for x in range(32, 64):
            n = h2d(x, y, 163)
            c.set(x, y, clamp_rgb(26 + (n - 0.5) * 8, 18 + (n - 0.5) * 6, 20 + (n - 0.5) * 6))

    c.save(TEX_DIR / "bone_toes_teeth.png")
    return {
        "bone": [0, 0, 32, 32],
        "toes": [32, 0, 16, 16],
        "teeth": [48, 0, 16, 16],
        "mouth": [32, 16, 32, 16],
    }


def make_glow_eyes_and_uplights():
    """
    32x32 art-px paired baseColor + strictly black-masked emissive texture:
      - eye     : (0,  0, 16, 16) glowing ruby-red lightbulb eye inside dark rim
      - uplight : (16, 0, 16, 16) warm ground floodlight lens
      - socket  : (0, 16, 16, 16) recessed dark-brown/charcoal eye socket walls (emissive=#000000!)
      - dark    : (16,16, 16, 16) non-emissive black metal housing (emissive=#000000!)
    """
    base = ArtCanvas(32, 32, (20, 14, 14, 255))
    mask = ArtCanvas(32, 32, (0, 0, 0, 255))

    # 1. Glowing ruby-red lightbulb eye (0..16, 0..16)
    cx, cy = 7.5, 7.5
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - cx, y - cy)
            if d > 6.6:
                base.set(x, y, (42, 16, 14))
                mask.set(x, y, (0, 0, 0))
            elif d > 4.8:
                base.set(x, y, (176, 18, 18))
                mask.set(x, y, (175, 16, 14))
            elif d > 2.4:
                base.set(x, y, (242, 36, 26))
                mask.set(x, y, (245, 34, 20))
            else:
                # Hot bright red-orange incandescent bulb center
                base.set(x, y, (255, 152, 112))
                mask.set(x, y, (255, 128, 72))

    # 2. Warm floodlight uplight lens (16..32, 0..16)
    for y in range(16):
        for x in range(16, 32):
            lx, ly = x - 16, y
            if lx in (0, 15) or ly in (0, 15):
                base.set(x, y, (34, 36, 40))
                mask.set(x, y, (0, 0, 0))
            else:
                d = math.hypot(lx - 7.5, ly - 7.5)
                if d < 5.5:
                    base.set(x, y, (255, 242, 204))
                    mask.set(x, y, (255, 224, 156))
                else:
                    base.set(x, y, (226, 204, 156))
                    mask.set(x, y, (186, 158, 96))

    # 3. Recessed dark eye socket walls (0..16, 16..32) — STRICTLY NON-EMISSIVE
    for y in range(16, 32):
        for x in range(16):
            n = h2d(x, y, 179)
            base.set(x, y, clamp_rgb(56 + (n - 0.5) * 10, 22 + (n - 0.5) * 6, 16 + (n - 0.5) * 6))
            mask.set(x, y, (0, 0, 0))

    # 4. Dark metal lamp housing (16..32, 16..32) — STRICTLY NON-EMISSIVE
    for y in range(16, 32):
        for x in range(16, 32):
            n = h2d(x, y, 191)
            v = 36 + (n - 0.5) * 10
            base.set(x, y, clamp_rgb(v, v + 2, v + 4))
            mask.set(x, y, (0, 0, 0))

    base.save(TEX_DIR / "glow_base.png")
    mask.save(TEX_DIR / "glow_mask.png")
    return {
        "eye": [0, 0, 16, 16],
        "uplight": [16, 0, 16, 16],
        "socket": [0, 16, 16, 16],
        "dark": [16, 16, 16, 16],
    }


def make_lot_fence():
    """
    Tall black wrought-iron picket fence matching the exact fence encircling Beach Blvd Rex!
    32x32 art-px alpha-masked texture:
      - panel : (0, 0, 32, 24)  transparent between crisp vertical black iron pickets + top/bottom horizontal rails + pointed finial tips!
      - iron  : (0, 24, 32, 8)  solid matte black wrought iron for corner posts, rails, and plaque stand
    """
    c = ArtCanvas(32, 32, (0, 0, 0, 0))

    # Fence panel (0..32 wide = ~2.48 m, 0..24 tall = ~1.86 m)
    # Vertical iron pickets every 3 art px (~0.23 m spacing — crisp & never blurs!)
    for x in range(32):
        is_picket = (x % 3 == 1)
        for y in range(24):
            # Top horizontal rail at y=3, second upper rail at y=5, bottom rail at y=22
            if y in (3, 22):
                v = 26 + int((h2d(x, y, 211) - 0.5) * 8)
                c.set(x, y, (v, v + 2, v + 5, 255))
            elif is_picket and y >= 1:
                # Pointed spearhead top above top rail (y=1..2) + vertical picket bar (y=3..23)
                v = 24 + int((h2d(x, y, 223) - 0.5) * 8)
                if y == 1:
                    c.set(x, y, (46, 50, 56, 255))  # tiny metallic highlight on spear tip
                else:
                    c.set(x, y, (v, v + 2, v + 4, 255))

    # Solid black iron band (0..32, 24..32)
    for y in range(24, 32):
        for x in range(32):
            v = 28 + int((h2d(x, y, 229) - 0.5) * 10)
            c.set(x, y, (v, v + 2, v + 5, 255))

    c.save(TEX_DIR / "lot_fence.png")
    return {
        "panel": [0, 0, 32, 24],
        "iron": [0, 24, 32, 8],
    }


def make_lot_ground():
    """
    64x64 art-px atlas for the Beach Blvd roadside lot:
      - mulch  : (0,  0, 32, 32) dark Florida pine-bark mulch inside the wrought-iron fence
      - grass  : (32, 0, 32, 32) Florida St. Augustine grass apron outside the fence
      - curb   : (0, 32, 32, 32) weathered precast roadside concrete street curb
      - plaque : (32,32, 32, 20) modest dark-bronze historical marker plaque ("REX" / "1964")
    """
    c = ArtCanvas(64, 64, (40, 36, 32, 255))

    # 1. Dark pine-bark mulch (0..32, 0..32) — matching dark mulch around Rex's feet & bone in real photos
    for y in range(32):
        for x in range(32):
            n = h2d(x, y, 241)
            n2 = h2d(x // 2, y, 251)
            if n > 0.84:
                col = (94, 66, 48)
            elif n > 0.55:
                col = (68, 48, 36)
            elif n2 > 0.5:
                col = (52, 38, 30)
            else:
                col = (40, 30, 26)
            c.set(x, y, col)

    # 2. Florida roadside grass apron (32..64, 0..32)
    for y in range(32):
        for x in range(32, 64):
            n = h2d(x, y, 263)
            n2 = h2d(x // 2, y // 2, 269)
            r = 88 + (n - 0.5) * 24 + (n2 - 0.5) * 16
            g = 122 + (n - 0.5) * 26 + (n2 - 0.5) * 18
            b = 58 + (n - 0.5) * 16
            # Occasional dry sandy Florida thatch blade
            if n > 0.90:
                r, g, b = 138, 128, 86
            c.set(x, y, clamp_rgb(r, g, b))

    # 3. Weathered roadside concrete street curb (0..32, 32..64)
    for y in range(32, 64):
        for x in range(32):
            n = h2d(x, y, 277)
            v = 196 + (n - 0.5) * 16
            if x % 16 == 0:
                v -= 28  # crisp expansion joint
            c.set(x, y, clamp_rgb(v, v - 2, v - 8))

    # 4. Modest bronze historical plaque (32..64, 32..52) — matches the small angled marker inside the fence!
    def draw_text(canvas, x0, y0, text, col, scale=1):
        cx = x0
        for ch in text.upper():
            g = FONT_5X7.get(ch)
            if g:
                for gy, row in enumerate(g):
                    for gx, bit in enumerate(row):
                        if bit == "1":
                            canvas.rect(cx + gx * scale, y0 + gy * scale, scale, scale, col)
            cx += 6 * scale

    px0, py0, pw, ph = 32, 32, 32, 20
    c.rect(px0, py0, pw, ph, (176, 144, 84))       # warm brass border
    c.rect(px0 + 1, py0 + 1, pw - 2, ph - 2, (46, 36, 26))  # dark oxidized bronze face
    w1, _ = measure_text("REX", scale=1)
    draw_text(c, px0 + (pw - w1) // 2, py0 + 2, "REX", (226, 194, 118), scale=1)
    c.rect(px0 + 4, py0 + 10, pw - 8, 1, (148, 118, 68))
    w2, _ = measure_text("1964", scale=1)
    draw_text(c, px0 + (pw - w2) // 2, py0 + 12, "1964", (206, 178, 112), scale=1)

    c.save(TEX_DIR / "lot_ground.png")
    return {
        "mulch": [0, 0, 32, 32],
        "grass": [32, 0, 32, 32],
        "curb": [0, 32, 32, 32],
        "plaque": [32, 32, 32, 20],
    }


def main():
    swatches = slice_swatches()
    make_skin_rex_rough()
    reg_smooth = make_skin_rex_smooth()
    make_skin_rex_belly()
    reg_btt = make_bone_toes_teeth()
    reg_glow = make_glow_eyes_and_uplights()
    reg_fence = make_lot_fence()
    reg_ground = make_lot_ground()

    regions = {
        "texel_m": TEXEL_M,
        "art_px_m": ART_PX_M,
        "swatches": swatches,
        "regions": {
            "skin_rex_smooth": reg_smooth,
            "bone_toes_teeth": reg_btt,
            "glow_rex_eyes": reg_glow,
            "lot_fence": reg_fence,
            "lot_ground": reg_ground,
        },
    }
    (ROOT / "art-regions.json").write_text(json.dumps(regions, indent=2) + "\n")
    print(f"Generated 1-to-1 Beach Blvd Rex textures -> {TEX_DIR}")


if __name__ == "__main__":
    main()
