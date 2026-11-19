#!/usr/bin/env python3
"""Re-skin the Junction Diner prototype (its first .glb, made before docs/CHATGPT_MODEL_GUIDE.md) to the landmark contract.

Reads the original export (git show origin/codex/junction-diner-prototype:prototypes/junction-diner/export/junction-diner.glb)
and writes a .glb the game's landmark loader takes like any guide-made model (docs/LANDMARKS_GLB.md):

- the origin moved to the middle of the building's front edge (the original stood 4 m behind it), the presentation road
  strip and the kerb/lane lines beyond the sidewalk dropped;
- the interior emptied to its shell (floor, walls, ceiling): booths, tables, counter, stools, cups and pendant lamps go
  (our RoomKit furnishes rooms);
- every material renamed by role (wall_/roof_/glass_/glow_/sign_/ground_/floor_/trim_/detail_) and its flat-colour
  picture replaced: our swatches (brick, roof, pavement, planks: tools/swatches/atlas) and pictures painted here in the
  LimeZu palette (ribbed teal enamel panels, cream porcelain, brushed aluminium, checker linoleum, glass, lamps, neon),
  all on the 2 x 2 texel art-pixel grid at 0.0388 m a texel, with outlines, highlights, dither and wear; UVs laid anew
  by each face's direction at that density; the signs repainted at the grid in the original diner's 5x7 lettering
  (pixel_font.py): JUNCTION, the DINER blade, the canopy strap (deepened to 0.70 m so a letter fits), OPEN LATE in 3x5
  letters on a widened panel, and a pie poster in place of the coffee one;
- door nodes (door_main at the front entrance step, door_back at the kitchen door) found from the steps;
- a `collision` mesh (boxes, extras collision_only/render:false) read off the geometry as the game's stores do: the
  walls cut between knee and chest height on a 10 cm grid, merged into rectangles; the lot's benches, planters, bins
  and crates likewise (tagged `lot` in model-info.json, which lists every box);
- `structure` and `lot` groups (lot: what stands outside the walls and below 2.4 m: sidewalk, planters, bench, bike,
  bins, crates).

Usage: python3 reskin_diner.py ORIGINAL.glb ATLAS_DIR OUT_DIR   (ATLAS_DIR: tools/swatches/atlas)
"""
import io, json, math, struct, sys
from pathlib import Path
import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from pixel_font import FONT  # noqa: E402

TEXEL = 0.0388  # metres a texel; one art pixel is 2 x 2 texels
FRONT_Z = 4.0   # the original's front wall line (glTF z); moved to 0


# ---------------------------------------------------------------- reading
def load(path):
    d = Path(path).read_bytes()
    cl = struct.unpack('<I', d[12:16])[0]
    j = json.loads(d[20:20 + cl])
    o = 20 + cl
    bl = struct.unpack('<I', d[o:o + 4])[0]
    return j, d[o + 8:o + 8 + bl]


def accessor(j, b, i):
    a = j['accessors'][i]; bv = j['bufferViews'][a['bufferView']]
    dt = {5126: np.float32, 5123: np.uint16, 5125: np.uint32, 5121: np.uint8}[a['componentType']]
    n = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4}[a['type']]
    o = bv.get('byteOffset', 0) + a.get('byteOffset', 0)
    arr = np.frombuffer(b, dt, count=a['count'] * n, offset=o)
    return arr.reshape(-1, n) if n > 1 else arr


def qmat(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def local(n):
    if 'matrix' in n:
        return np.array(n['matrix']).reshape(4, 4).T
    m = np.eye(4)
    m[:3, :3] = qmat(n.get('rotation', [0, 0, 0, 1])) @ np.diag(n.get('scale', [1, 1, 1]))
    m[:3, 3] = n.get('translation', [0, 0, 0])
    return m


def triangles(j, b):
    """Every triangle in the model: (material name, positions [n,3,3], normals [n,3,3], uvs [n,3,2] or None)."""
    out = []

    def rec(i, parent):
        n = j['nodes'][i]; m = parent @ local(n)
        if 'mesh' in n:
            for p in j['meshes'][n['mesh']]['primitives']:
                at = p['attributes']
                P = accessor(j, b, at['POSITION']).astype(float); N = accessor(j, b, at['NORMAL']).astype(float)
                UV = accessor(j, b, at['TEXCOORD_0']).astype(float) if 'TEXCOORD_0' in at else None
                I = accessor(j, b, p['indices']).astype(int).reshape(-1, 3)
                W = (m[:3, :3] @ P.T).T + m[:3, 3]
                NW = (m[:3, :3] @ N.T).T
                NW /= np.maximum(np.linalg.norm(NW, axis=1, keepdims=True), 1e-9)
                out.append((j['materials'][p['material']]['name'], W[I], NW[I], UV[I] if UV is not None else None))
        for c in n.get('children', []):
            rec(c, m)
    for r in j['scenes'][j.get('scene', 0)]['nodes']:
        rec(r, np.eye(4))
    return out


# ---------------------------------------------------------------- pictures (art pixels, then 2 x 2)
def hexc(h, a=255):
    h = h.lstrip('#'); return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


OUT = hexc('3a3a50')  # the packs' common outline


class Art:
    """A picture painted in art pixels (each becomes 2 x 2 texels)."""
    def __init__(self, w, h, fill):
        self.w, self.h = w, h
        self.px = [[fill] * w for _ in range(h)]

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h: self.px[y][x] = c

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1): self.set(x, y, c)

    def image(self):
        im = Image.new('RGBA', (self.w * 2, self.h * 2))
        for y in range(self.h):
            for x in range(self.w):
                for k in range(4): im.putpixel((x * 2 + k % 2, y * 2 + k // 2), self.px[y][x])
        return im


def rnd(seed):
    s = [seed * 2654435761 % 2**32]
    def f():
        s[0] = (s[0] * 1664525 + 1013904223) % 2**32; return s[0] / 2**32
    return f


TEAL = [hexc(c) for c in ('1f3a3e', '2f5d5f', '3f7f7a', '5a9e93', '8cc5b2')]  # outline, dark, base, light, highlight
CREAM = [hexc(c) for c in ('6b5e4a', 'b8a98a', 'dcd2b4', 'ece5cc', 'f8f4e4')]
ALU = [hexc(c) for c in ('3a3a50', '7d8292', 'a9aebb', 'cdd1da', 'eef0f4')]
RED = [hexc(c) for c in ('4a1f24', '7a2e30', 'a14f44', 'c2665a', 'e08c7a')]
OCHRE = [hexc(c) for c in ('4f3a1c', '8a6428', 'b08a3a', 'cfa851', 'ecd28a')]


def teal_panels():
    """Ribbed porcelain-enamel panels: a rib every 4 art pixels (lit edge, shade), a joint every 16 rows, a little wear."""
    a = Art(16, 16, TEAL[2]); r = rnd(3)
    for x in range(16):
        k = x % 4
        for y in range(16):
            c = TEAL[3] if k == 0 else TEAL[1] if k == 3 else TEAL[2]
            if k == 2 and (x + y) % 2 == 0 and y > 9: c = TEAL[1]  # stepped shade low down, dithered
            a.set(x, y, c)
    a.rect(0, 15, 15, 15, TEAL[0]); a.rect(0, 0, 15, 0, TEAL[4])  # joint and its lit top edge
    for _ in range(4):
        a.set(int(r() * 16), 1 + int(r() * 13), CREAM[1])  # chips in the enamel
    return a


def cream_enamel():
    a = Art(16, 16, CREAM[2]); r = rnd(5)
    a.rect(0, 0, 15, 0, CREAM[4]); a.rect(0, 1, 15, 1, CREAM[3])
    a.rect(15, 0, 15, 15, CREAM[1]); a.rect(0, 0, 0, 15, CREAM[3])
    a.rect(0, 14, 15, 14, CREAM[1]); a.rect(0, 15, 15, 15, CREAM[0])
    for y in range(9, 14):
        for x in range(1, 15):
            if (x + y) % 2 == 0 and y > 11: a.set(x, y, CREAM[1])
    for _ in range(3):
        a.set(1 + int(r() * 13), 3 + int(r() * 8), CREAM[1])
    return a


def aluminium():
    a = Art(8, 8, ALU[2])
    for y in range(8):
        for x in range(8):
            a.set(x, y, ALU[3] if y % 3 == 0 else ALU[2] if (x + y) % 5 else ALU[1])
    a.rect(0, 0, 7, 0, ALU[4]); a.rect(0, 7, 7, 7, ALU[0])
    return a


def outline():
    a = Art(8, 8, OUT); r = rnd(9)
    for _ in range(5): a.set(int(r() * 8), int(r() * 8), hexc('2b2b3c'))
    a.rect(0, 0, 7, 0, hexc('4a4a63'))
    return a


def checker():
    """Black and cream linoleum, 4 art-pixel tiles (31 cm), worn and scuffed."""
    a = Art(8, 8, CREAM[3]); r = rnd(11)
    dark = hexc('2e3440'); darkl = hexc('3c4352')
    for y in range(8):
        for x in range(8):
            d = ((x // 4) + (y // 4)) % 2 == 0
            c = (dark if (x % 4 or y % 4) else darkl) if d else (CREAM[3] if (x % 4 or y % 4) else CREAM[4])
            a.set(x, y, c)
    for _ in range(3): a.set(int(r() * 8), int(r() * 8), CREAM[1])
    return a


def glass():
    """Thin teal glazing: see-through (alpha) with one lit streak and a darker sill row, so it reads as glass. One storey
    tall (40 art pixels) so the room the game paints behind see-through glass lines up with the floor."""
    clear = (60, 120, 128, 60)
    a = Art(16, 40, clear)
    for k in range(6):
        a.set(9 - k, 8 + k, (200, 235, 230, 130))
    a.rect(0, 39, 15, 39, (40, 70, 76, 150))
    return a


SMALL = {'O': '010101101101010', 'P': '110101110100100', 'E': '111100110100111', 'N': '101111111111101', 'L': '100100100100111',
         'A': '010101111101101', 'T': '111010010010010'}


def small_text(w, h, text, ink):
    """[text] in the 3x5 letters, centred, on clear (glass lettering)."""
    a = Art(w, h, (0, 0, 0, 0))
    tw = len(text) * 4 - 1; x = (w - tw) // 2; y = (h - 5) // 2
    for ch in text:
        g = SMALL.get(ch)
        if g:
            for r in range(5):
                for c in range(3):
                    if g[r * 3 + c] == '1': a.set(x + c, y + r, ink)
        x += 4
    return a


def pie_poster(w, h):
    """A small window poster: a slice of pie on a plate (no lettering fits 8 art pixels)."""
    a = Art(w, h, CREAM[3])
    a.rect(0, 0, w - 1, 0, TEAL[0]); a.rect(0, h - 1, w - 1, h - 1, TEAL[0]); a.rect(0, 0, 0, h - 1, TEAL[0]); a.rect(w - 1, 0, w - 1, h - 1, TEAL[0])
    cx, cy = w // 2, h // 2 + 1
    a.rect(cx - 3, cy + 2, cx + 3, cy + 2, ALU[2])  # plate
    for k in range(4):
        a.rect(cx - 2 + k // 2, cy - k + 1, cx + 2, cy - k + 1, OCHRE[3] if k < 3 else RED[2])  # wedge, cherry top
    a.rect(cx - 2, cy + 1, cx + 2, cy + 1, OCHRE[1])
    return a


def flat(ramp, seed, n=8):
    a = Art(n, n, ramp[2]); r = rnd(seed)
    for y in range(n):
        for x in range(n):
            t = r(); a.set(x, y, ramp[1] if t < .15 else ramp[3] if t > .9 else ramp[2])
    a.rect(0, 0, n - 1, 0, ramp[3])
    return a


def lamp(c0, c1):
    a = Art(4, 4, c0); a.rect(1, 1, 2, 2, c1); return a


def foliage():
    g = [hexc(c) for c in ('268b49', '479757', '64b63b', '8fd16a')]
    a = Art(8, 8, g[1]); r = rnd(13)
    for y in range(8):
        for x in range(8):
            t = r(); a.set(x, y, g[0] if t < .25 else g[2] if t > .75 else g[3] if t > .95 else g[1])
    return a


def soil():
    s = [hexc(c) for c in ('3a2a1c', '4f3a26', '6a5038')]
    a = Art(8, 8, s[1]); r = rnd(17)
    for y in range(8):
        for x in range(8):
            t = r(); a.set(x, y, s[0] if t < .3 else s[2] if t > .85 else s[1])
    return a


def swatch(atlas, entries, sid):
    e = entries[sid]
    return atlas.crop((e['x'], e['y'], e['x'] + e['w'], e['y'] + e['h'])).convert('RGBA')


def text_art(w, h, lines, bg, ink, frame, hi, scale=1, vertical=False, transparent=False):
    """A sign w x h art pixels: frame and lit edge, [lines] centred in the 5x7 lettering at [scale]."""
    a = Art(w, h, (0, 0, 0, 0) if transparent else bg)
    if not transparent:
        a.rect(0, 0, w - 1, 0, frame); a.rect(0, h - 1, w - 1, h - 1, frame); a.rect(0, 0, 0, h - 1, frame); a.rect(w - 1, 0, w - 1, h - 1, frame)
        a.rect(1, 1, w - 2, 1, hi)
    if vertical:
        word = lines[0]; gh = 7 * scale + scale * 2
        y = (h - (len(word) * gh - scale * 2)) // 2
        for ch in word:
            g = FONT.get(ch); gw = len(g[0]) * scale
            x = (w - gw) // 2
            for j, row in enumerate(g):
                for i, p in enumerate(row):
                    if p == '1': a.rect(x + i * scale, y + j * scale, x + i * scale + scale - 1, y + j * scale + scale - 1, ink)
            y += gh
        return a
    lh = 7 * scale; gap = 2 * scale
    total = len(lines) * lh + (len(lines) - 1) * gap
    y = (h - total) // 2
    for line in lines:
        widths = [len(FONT.get(c, FONT['-'])[0]) * scale if c != ' ' else 3 * scale for c in line]
        tw = sum(widths) + scale * (len(line) - 1)
        x = (w - tw) // 2
        for c, cw in zip(line, widths):
            if c != ' ':
                for j, row in enumerate(FONT[c]):
                    for i, p in enumerate(row):
                        if p == '1':
                            a.rect(x + i * scale, y + j * scale, x + i * scale + scale - 1, y + j * scale + scale - 1, ink)
            x += cw + scale
        y += lh + gap
    return a


# ---------------------------------------------------------------- geometry helpers
def flat_normals(P, N0):
    n = np.cross(P[:, 1] - P[:, 0], P[:, 2] - P[:, 0])
    l = np.linalg.norm(n, axis=1, keepdims=True)
    n = n / np.maximum(l, 1e-12)
    # face the way the model's own normals did (the file is double-sided, its winding is no guide)
    s = np.sign(np.einsum('ij,ij->i', n, N0.mean(1)))
    s[s == 0] = 1
    return n * s[:, None], l[:, 0]


def planar_uv(P, n, size):
    """UVs at TEXEL a texel by each face's main direction: walls take x or z across and y down, tops x and z."""
    w, h = size
    ax = np.abs(n).argmax(1)
    u = np.where(ax == 1, P[:, :, 0].T, np.where(ax == 0, P[:, :, 2].T * -np.sign(n[:, 0]), P[:, :, 0].T * np.sign(n[:, 2]))).T
    v = np.where(ax == 1, -P[:, :, 2].T, -P[:, :, 1].T).T
    return np.stack([u / (TEXEL * w), v / (TEXEL * h)], axis=2)


def solids(P, lo, hi, cell=0.1, fill=True):
    """Boxes a walker meets: [P]'s triangles cut by level planes between lo and hi, drawn on a grid of [cell], the
    inside filled, merged into rectangles (x0, z0, x1, z1)."""
    if len(P) == 0: return []
    x0, z0 = P[:, :, 0].min() - .5, P[:, :, 2].min() - .5
    x1, z1 = P[:, :, 0].max() + .5, P[:, :, 2].max() + .5
    cols, rows = int((x1 - x0) / cell) + 2, int((z1 - z0) / cell) + 2
    g = np.zeros((rows, cols), bool)

    def mark(x, z):
        g[int((z - z0) / cell), int((x - x0) / cell)] = True

    for h in np.linspace(lo, hi, 4):
        for t in P:
            ys = t[:, 1]
            if ys.min() > h or ys.max() < h: continue
            pts = []
            for i, k in ((0, 1), (1, 2), (2, 0)):
                a, b = t[i], t[k]
                if (a[1] - h) * (b[1] - h) <= 0 and a[1] != b[1]:
                    s = (h - a[1]) / (b[1] - a[1]); pts.append(a + (b - a) * s)
            if len(pts) >= 2:
                a, b = pts[0], pts[1]
                n = max(1, int(np.hypot(*(b - a)[[0, 2]]) / (cell * .5)) + 1)
                for s in np.linspace(0, 1, n + 1):
                    p = a + (b - a) * s; mark(p[0], p[2])
    if fill:
        from collections import deque
        seen = np.zeros_like(g); q = deque()
        for c in range(cols):
            for r in (0, rows - 1): q.append((r, c))
        for r in range(rows):
            for c in (0, cols - 1): q.append((r, c))
        while q:
            r, c = q.popleft()
            if r < 0 or c < 0 or r >= rows or c >= cols or seen[r, c] or g[r, c]: continue
            seen[r, c] = True
            q.extend(((r + 1, c), (r - 1, c), (r, c + 1), (r, c - 1)))
        g = ~seen
    # rows of runs, then runs merged down while identical
    rects = []; open_ = {}
    for r in range(rows + 1):
        runs = set()
        if r < rows:
            c = 0
            while c < cols:
                if g[r, c]:
                    c0 = c
                    while c < cols and g[r, c]: c += 1
                    runs.add((c0, c))
                else: c += 1
        for k in list(open_):
            if k not in runs: rects.append((k[0], open_.pop(k), k[1], r))
        for k in runs:
            open_.setdefault(k, r)
    return [(x0 + a * cell, z0 + b * cell, x0 + c * cell, z0 + d * cell) for a, b, c, d in rects]


def box_tris(x0, y0, z0, x1, y1, z1):
    v = np.array([[x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0], [x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]])
    f = [(0, 2, 1), (0, 3, 2), (4, 5, 6), (4, 6, 7), (0, 1, 5), (0, 5, 4), (3, 7, 6), (3, 6, 2), (0, 4, 7), (0, 7, 3), (1, 2, 6), (1, 6, 5)]
    return v[np.array(f)]


# ---------------------------------------------------------------- the re-skin
# original material -> (new name, picture key, keep the original UVs)
ROLES = {
    '01 • oxidised teal enamel': ('wall_enamel_teal', 'teal', False),
    '02 • warm porcelain enamel': ('wall_porcelain_cream', 'cream', False),
    '03 • oxblood vinyl': ('trim_vinyl_red', 'red', False),
    '04 • kitchen brick': ('wall_brick_kitchen', 'brick', False),
    '05 • rolled roofing': ('roof_rolled', 'roof', False),
    '06 • brushed aluminium': ('trim_aluminium', 'alu', False),
    '07 • deep outline': ('trim_outline', 'outline', False),
    '08 • weathered timber': ('detail_timber', 'planks', False),
    '09 • checker linoleum': ('floor_checker', 'checker', False),
    '10 • sidewalk concrete': ('ground_sidewalk', 'pavement', False),
    '12 • thin teal glazing': ('glass_dining', 'glass', False),
    '13 • aged ochre paint': ('detail_ochre', 'ochre', False),
    '14 • copper rust': ('detail_copper', 'copper', False),
    '15 • ceramic': ('detail_ceramic', 'ceramic', False),
    '17 • soil': ('detail_soil', 'soil', False),
    '18 • leaves': ('detail_leaves', 'leaves', False),
    '19 • warm lamps': ('glow_warm_lamps', 'lamp', False),
    '20 • coral neon': ('glow_coral_neon', 'neon', False),
    '21 • original JUNCTION pixel lettering': ('sign_junction', 'junction', True),
    '22 • original DINER pixel lettering': ('sign_diner_blade', 'blade', True),
    '23 • original menu strap': ('sign_menu_strap', 'strap', True),
    '25 • original OPEN LATE pixel glass lettering': ('sign_open_late', 'open', True),
    '26 • original coffee poster': ('sign_coffee_poster', 'poster', True),
}
DROP = {'11 • patched road', '16 • coffee', '24 • original blackboard'}
# what stands inside the walls and is not shell (furniture) goes
SHELL = {'09 • checker linoleum', '04 • kitchen brick'}


def footprint_inside(x, z, shrink):
    """Inside the dining room (x +-6, z -4.4..4) or the kitchen (x -2.6..6.1, z -8.3..-4.4), [shrink] in from the walls."""
    din = (np.abs(x) < 6 - shrink) & (z > -4.4 + shrink) & (z < FRONT_Z - shrink)
    # the dining room's rounded front corners (radius about 2 m)
    for cx in (-4.0, 4.0):
        cz = FRONT_Z - 2.0
        corner = (np.sign(x - cx) == np.sign(cx)) & (z > cz)
        din &= ~corner | (np.hypot(x - cx, z - cz) < 2.0 - shrink)
    kit = (x > -2.6 + shrink) & (x < 6.1 - shrink) & (z > -8.3 + shrink) & (z < -4.4 + shrink)
    return din | kit


def main(src, atlas_dir, out_dir):
    out_dir = Path(out_dir); out_dir.mkdir(parents=True, exist_ok=True)
    j, b = load(src)
    atlas = Image.open(Path(atlas_dir) / 'atlas.png').convert('RGBA')
    entries = json.loads((Path(atlas_dir) / 'atlas.json').read_text())
    pics = {
        'teal': teal_panels().image(), 'cream': cream_enamel().image(), 'alu': aluminium().image(), 'outline': outline().image(),
        'checker': checker().image(), 'glass': glass().image(), 'red': flat(RED, 21).image(), 'ochre': flat(OCHRE, 23).image(),
        'copper': flat([hexc(c) for c in ('2e1a14', '4f2c20', '6e3f2a', '8a5638', 'b07a52')], 25).image(),
        'ceramic': flat(CREAM, 27).image(), 'soil': soil().image(), 'leaves': foliage().image(),
        'lamp': lamp(hexc('f2b860'), hexc('fde8b0')).image(), 'neon': lamp(hexc('f25a3c'), hexc('ffb49a')).image(),
        'brick': swatch(atlas, entries, 'brick_red'), 'roof': swatch(atlas, entries, 'ballast'),
        'pavement': swatch(atlas, entries, 'pavement'), 'planks': swatch(atlas, entries, 'plank_horizontal'),
    }
    groups = {}  # (group, new name) -> lists
    sign_quads = {}
    steps = []
    for mname, P, N, UV in triangles(j, b):
        if mname in DROP: continue
        P = P.copy(); P[:, :, 2] -= FRONT_Z
        c = P.mean(1)
        # the presentation road and the kerb/lane lines beyond the sidewalk
        keep = (np.abs(c[:, 0]) < 8.6) & (c[:, 2] < 4.5) & (c[:, 1] > -.2)
        # the furniture inside the walls (the shell's floor, partition and ceiling stay)
        if mname not in SHELL:
            inside = footprint_inside(c[:, 0], c[:, 2] + FRONT_Z, .3) & (c[:, 1] > .27) & (c[:, 1] < 3.2)
            keep &= ~inside
        P, N = P[keep], N[keep]
        UV = UV[keep] if UV is not None else None
        if len(P) == 0: continue
        name, key, keepuv = ROLES[mname]
        if key == 'strap':
            # the canopy's menu strap was 0.34 m: too low for the 5x7 letters at the art's size; it hangs to 0.70 m
            top = P[:, :, 1].max(); P[:, :, 1] = np.where(P[:, :, 1] < top - .01, top - .70, P[:, :, 1])
        if key == 'open':
            # the window lettering widened to fit OPEN LATE in the 3x5 letters at the art's size (2.9 m)
            cx = (P[:, :, 0].max() + P[:, :, 0].min()) / 2; k = 2.9 / (P[:, :, 0].max() - P[:, :, 0].min())
            P[:, :, 0] = cx + (P[:, :, 0] - cx) * k
        if mname == '10 • sidewalk concrete':
            hi = P[P[:, :, 1].max(1) > .1]
            if len(hi): steps.append(hi)
        n, area = flat_normals(P, N)
        c = P.mean(1)
        lot = ~footprint_inside(c[:, 0], c[:, 2] + FRONT_Z, -.8) & (P[:, :, 1].max(1) < 2.4)
        if name.startswith('ground_'): lot[:] = True
        for grp, sel in (('structure', ~lot), ('lot', lot)):
            if not sel.any(): continue
            g = groups.setdefault((grp, name), {'P': [], 'N': [], 'UV': [], 'key': key, 'keepuv': keepuv})
            g['P'].append(P[sel]); g['N'].append(np.repeat(n[sel][:, None, :], 3, axis=1)); g['UV'].append(UV[sel] if keepuv else None)
        if keepuv: sign_quads[key] = P
    # the signs repainted on the art grid at their own size
    def size_of(P):
        pts = P.reshape(-1, 3); ext = pts.max(0) - pts.min(0)
        h = ext[1]; w = max(np.hypot(ext[0], ext[2]), .1)
        return max(4, int(round(w / TEXEL / 2))), max(4, int(round(h / TEXEL / 2)))
    for key, P in sign_quads.items():
        w, h = size_of(P)
        if key == 'junction':
            a = text_art(w, h, ['JUNCTION'], TEAL[1], CREAM[3], TEAL[0], TEAL[3], scale=1)
        elif key == 'strap':
            a = text_art(w, h, ['COFFEE PIE MEALS'], CREAM[2], TEAL[1], CREAM[0], CREAM[4], scale=1)
        elif key == 'blade':
            a = text_art(w, h, ['DINER'], RED[1], CREAM[3], RED[0], RED[3], scale=max(1, min((w - 4) // 5, (h - 4) // 45)), vertical=True)
        elif key == 'open':
            a = small_text(w, h, 'OPEN LATE', hexc('f2b860'))
        else:
            a = pie_poster(w, h)
        pics[key] = a.image()
    # doors: the entrance steps (raised sidewalk pieces at the front and behind the kitchen)
    S = np.concatenate(steps).reshape(-1, 3) if steps else np.zeros((0, 3))
    def door(sel_z, out):
        s = S[sel_z(S[:, 2])]
        return [float(s[:, 0].mean()) if len(s) else 0.0, out]
    front = door(lambda z: (z > -.2) & (z < 1.5), 1)
    back = door(lambda z: z < -8.0, -1)
    # collision: the building's walls (the inside filled), and the lot's things a walker meets
    allP = {grp: np.concatenate([np.concatenate(g['P']) for (gg, _), g in groups.items() if gg == grp and not _.startswith(('ground_', 'floor_', 'roof_'))]) for grp in ('structure', 'lot')}
    bld = solids(allP['structure'][(allP['structure'][:, :, 1].min(1) < 1.6)], .45, 1.5)
    lotb = solids(allP['lot'], .35, 1.2, fill=True)
    boxes = [(r, 'structure') for r in bld if (r[2] - r[0]) * (r[3] - r[1]) > .02] + [(r, 'lot') for r in lotb if (r[2] - r[0]) * (r[3] - r[1]) > .01]
    # ---- write the .glb
    bin_ = bytearray(); views = []; accs = []; meshes = []; nodes = []; mats = []; images = []; textures = []
    def view(data, target=None):
        while len(bin_) % 4: bin_.append(0)
        o = len(bin_); bin_.extend(data); v = {'buffer': 0, 'byteOffset': o, 'byteLength': len(data)}
        if target: v['target'] = target
        views.append(v); return len(views) - 1
    def acc(arr, typ, comp, target):
        arr = np.ascontiguousarray(arr)
        a = {'bufferView': view(arr.tobytes(), target), 'componentType': comp, 'count': int(arr.shape[0]), 'type': typ}
        if typ == 'VEC3': a['min'] = arr.min(0).tolist(); a['max'] = arr.max(0).tolist()
        accs.append(a); return len(accs) - 1
    matidx = {}
    def material(name, key):
        if name in matidx: return matidx[name]
        im = pics[key]; buf = io.BytesIO(); im.save(buf, 'PNG')
        images.append({'name': key, 'mimeType': 'image/png', 'bufferView': view(buf.getvalue())})
        textures.append({'sampler': 0, 'source': len(images) - 1})
        m = {'name': name, 'doubleSided': True, 'pbrMetallicRoughness': {'baseColorTexture': {'index': len(textures) - 1}, 'metallicFactor': 0, 'roughnessFactor': .8}}
        if name.startswith(('glow_', 'sign_')): m['emissiveTexture'] = {'index': len(textures) - 1}; m['emissiveFactor'] = [.2, .2, .2]
        if name.startswith('glass_') or key == 'open': m['alphaMode'] = 'BLEND'
        mats.append(m); matidx[name] = len(mats) - 1; return matidx[name]
    group_nodes = {'structure': [], 'lot': []}
    tri_count = 0
    for (grp, name), g in sorted(groups.items()):
        P = np.concatenate(g['P']); N = np.concatenate(g['N'])
        im = pics[g['key']]
        if g['keepuv']: UV = np.concatenate(g['UV'])
        else: UV = planar_uv(P, N[:, 0], im.size)
        pos = P.reshape(-1, 3).astype(np.float32); nrm = N.reshape(-1, 3).astype(np.float32); uv = UV.reshape(-1, 2).astype(np.float32)
        idx = np.arange(len(pos), dtype=np.uint32)
        prim = {'attributes': {'POSITION': acc(pos, 'VEC3', 5126, 34962), 'NORMAL': acc(nrm, 'VEC3', 5126, 34962), 'TEXCOORD_0': acc(uv, 'VEC2', 5126, 34962)},
                'indices': acc(idx, 'SCALAR', 5125, 34963), 'material': material(name, g['key'])}
        meshes.append({'name': f'{grp}__{name}', 'primitives': [prim]})
        nodes.append({'name': f'{grp}__{name}', 'mesh': len(meshes) - 1})
        group_nodes[grp].append(len(nodes) - 1)
        tri_count += len(P)
    # collision: boxes, never drawn
    ct = np.concatenate([box_tris(r[0], 0, r[1], r[2], 2.4 if grp == 'structure' else 1.2, r[3]) for r, grp in boxes]) if boxes else np.zeros((0, 3, 3))
    cpos = ct.reshape(-1, 3).astype(np.float32)
    cn = np.repeat(flat_normals(ct, np.repeat(np.ones((len(ct), 1, 3)), 3, 1))[0][:, None, :], 3, 1).reshape(-1, 3).astype(np.float32)
    meshes.append({'name': 'collision', 'primitives': [{'attributes': {'POSITION': acc(cpos, 'VEC3', 5126, 34962), 'NORMAL': acc(cn, 'VEC3', 5126, 34962)},
                                                        'indices': acc(np.arange(len(cpos), dtype=np.uint32), 'SCALAR', 5125, 34963)}]})
    nodes.append({'name': 'collision', 'mesh': len(meshes) - 1, 'extras': {'collision_only': True, 'render': False}})
    col = len(nodes) - 1
    nodes.append({'name': 'door_main', 'translation': [front[0], 0, .3], 'extras': {'interaction': 'Go inside'}}); dm = len(nodes) - 1
    nodes.append({'name': 'door_back', 'translation': [back[0], 0, -12.45], 'rotation': [0, 1, 0, 0], 'extras': {'interaction': 'Go inside'}}); db = len(nodes) - 1
    nodes.append({'name': 'lot', 'children': group_nodes['lot']}); lt = len(nodes) - 1
    nodes.append({'name': 'structure', 'children': group_nodes['structure']}); st = len(nodes) - 1
    nodes.append({'name': 'junction_diner', 'children': [col, dm, db, lt, st]}); root = len(nodes) - 1
    gltf = {'asset': {'version': '2.0', 'generator': 'Timerpg reskin_diner.py'}, 'scene': 0, 'scenes': [{'name': 'Scene', 'nodes': [root]}],
            'nodes': nodes, 'meshes': meshes, 'materials': mats, 'textures': textures, 'images': images,
            'samplers': [{'magFilter': 9728, 'minFilter': 9984, 'wrapS': 10497, 'wrapT': 10497}],
            'accessors': accs, 'bufferViews': views, 'buffers': [{'byteLength': len(bin_)}]}
    js = json.dumps(gltf, separators=(',', ':')).encode()
    while len(js) % 4: js += b' '
    while len(bin_) % 4: bin_.append(0)
    total = 12 + 8 + len(js) + 8 + len(bin_)
    data = struct.pack('<III', 0x46546C67, 2, total) + struct.pack('<II', len(js), 0x4E4F534A) + js + struct.pack('<II', len(bin_), 0x004E4942) + bytes(bin_)
    (out_dir / 'junction-diner.glb').write_bytes(data)
    # model-info.json, in the guide's format (collision boxes in Blender's z-up as the other models give them)
    allpts = np.concatenate([np.concatenate(g['P']) for g in groups.values()]).reshape(-1, 3)
    info = {
        'name': 'Junction Diner', 'era': {'built': 1948, 'design_years': [1940, 1965]},
        'units': 'metres', 'axes': 'glTF Y up, front +Z', 'origin': 'Ground level at the middle of the building front edge',
        'footprint_m': {'building_width': 12.0, 'building_depth': 12.3, 'lot_width': round(float(allpts[:, 0].max() - allpts[:, 0].min()), 3),
                        'lot_depth': round(float(allpts[:, 2].max() - allpts[:, 2].min()), 3)},
        'heights_m': {'roof': 3.4, 'blade_sign': round(float(allpts[:, 1].max()), 3)},
        'triangles_visible': int(tri_count), 'triangles_collision': int(len(ct)),
        'materials': sorted(m['name'] for m in mats),
        'doors': [{'node': 'door_main', 'position': [front[0], 0, .3], 'outward': [0, 0, 1]}, {'node': 'door_back', 'position': [back[0], 0, -12.45], 'outward': [0, 0, -1]}],
        'pixel_grid': {'metres_per_texel': TEXEL, 'texels_per_art_pixel': 2}, 'lot_node': 'lot',
        'interior': 'Empty shell: checker floor, walls, ceiling. The game furnishes the room.',
        'collision': {'node': 'collision', 'render': False, 'boxes_blender_z_up': [
            {'center': [(r[0] + r[2]) / 2, -(r[1] + r[3]) / 2, (1.2 if grp == 'structure' else .6)], 'size': [r[2] - r[0], r[3] - r[1], 2.4 if grp == 'structure' else 1.2], 'group': grp, 'attachment': grp}
            for r, grp in boxes]},
        'swatches_used': ['brick_red', 'ballast', 'pavement', 'plank_horizontal'],
        'source': 'Re-skinned by kotlin-street/tools/landmarks/reskin_diner.py from the original prototype export',
    }
    (out_dir / 'model-info.json').write_text(json.dumps(info, indent=1))
    print(f'{tri_count} triangles, {len(mats)} materials, {len(boxes)} collision boxes, doors at x {front[0]:.2f} (front) and {back[0]:.2f} (back), {len(data) // 1024} KiB')


if __name__ == '__main__':
    main(*sys.argv[1:4])
