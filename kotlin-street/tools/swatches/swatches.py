#!/usr/bin/env python3
"""
Swatch library: seamless tiles cut FROM the LimeZu sprites (32 px art, nearest, never resampled) plus a few painted in
the pack's palette where no sprite has the material. General, not store-only: any building, interior or farm code can draw
from it (Kotlin: scene3d/Swatches.kt `Swatches.get(id)`).

Usage:
  swatches.py build  --root exteriors=DIR [--root farm=DIR ...] [--spec swatches.json ...] [--out DIR] [--kotlin FILE]
  swatches.py preview --root ... [--spec ...] --png sheet.png     (each swatch tiled 3x3, labelled, 2x)

A spec file is a JSON list (or {entries:[...]}) of entries in the shape of tools/materials.json (the pack-wide
catalogue, branch wf-materials; read it with --catalog and name its entries with {"use": "<catalogue id>", "id": "<ours>"}):
  {id, family, pack, file, rect:[x,y,w,h], kind?, tile?, repeat?, palette?, tags?, seam?, cell?, size?, wrap?, pick?}
 - `pack` names a --root (exteriors, interiors, farm); `file` is the path inside that pack (a leading
   Modern_Exteriors_32x32/ME_Theme_Sorter_32x32/ is optional); `rect` is the crop in the file's pixels, snapped to the
   art pixel grid (the 32 px art is the 16 px art at 2 x 2, so x, y, w, h are made even).
 - `kind` (as the catalogue): absent = a plain tile, wrapped both ways (the pixels past its right and bottom edges are
   dithered over the first columns/rows in whole art pixels unless the catalogue says `tile: seamless`); `strip` = wraps
   along its longer axis only (fences, awnings, cornices: cut as drawn); `decal` = placed once (cut as drawn);
   `autotile` = a sheet to cut cells from: `cell: [cx, cy]` picks the 32 x 32 cell at rect + cell * 32.
 - `size` [w, h] is the output size when smaller than `rect`; `wrap` ('xy', 'x', 'y', 'none') overrides what `kind` says;
   `seam` as a string ('blend', 'exact', 'none') overrides the blending; (the catalogue's `tile`, `repeat` and `seam`
   scores are read as hints: `tile: seamless` means 'exact').
 - `pick` (instead of rect) finds the tile: {"glob": "...", "opaque": true, "lum": [min,max]} takes the first matching file.
 - pack 'paint' with `paint:<name>` is generated here (ballast, worn asphalt) in the pack's palette and shading.
Output: atlas.png + atlas.json (id -> rect in the atlas + the entry) and the generated Kotlin data file.
"""
import argparse, glob, json, os, sys, base64, random
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))

def load_specs(paths, catalog=None):
    out = []
    cat = {}
    if catalog:
        d = json.load(open(catalog))
        for e in (d if isinstance(d, list) else d.get('entries', d.get('materials', []))): cat[e['id']] = e
    for p in paths:
        with open(p) as f:
            d = json.load(f)
        for e in (d if isinstance(d, list) else d.get('materials', d.get('entries', []))):
            if 'use' in e:
                base = dict(cat[e['use']]); base.update({k: v for k, v in e.items() if k != 'use'}); e = base
            out.append(e)
    return out

def resolve(root, f):
    for cand in (f, f.split('ME_Theme_Sorter_32x32/')[-1], f.split('/', 1)[-1]):
        p = os.path.join(root, cand)
        if os.path.exists(p): return p
    raise SystemExit('no file ' + f)

def snap(rect):
    x, y, w, h = rect
    x2, y2 = x + w, y + h
    x, y = x + x % 2, y + y % 2
    return [x, y, (x2 - x) // 2 * 2, (y2 - y) // 2 * 2]

def wrap_of(e, w, h):
    if 'wrap' in e: return e['wrap']
    if isinstance(e.get('repeat'), str): return e['repeat']
    k = e.get('kind')
    if k == 'strip': return 'x' if w >= h else 'y'
    if k == 'decal': return 'none'
    return 'xy'

def seam_of(e, wrap):
    if isinstance(e.get('seam'), str): return e['seam']
    if wrap == 'none' or e.get('kind') in ('strip', 'decal', 'autotile'): return 'exact'
    if wrap != 'xy': return 'exact'
    return 'exact' if e.get('tile') == 'seamless' else 'blend'

PX = 2   # the 32 px art is the 16 px art at 2 x 2: its pixel is 2 texels, so everything is cut, blended and painted in 2 x 2 blocks

def blend_seam(src, x0, y0, w, h, b=4, rnd=None):
    """w x h tile from src at (x0,y0); the b pixels past the right and bottom edges dither over the first b columns/rows
    (in whole art pixels, so the 2 x 2 blocks stay whole)."""
    rnd = rnd or random.Random(7)
    t = [[src.getpixel((x0 + x, y0 + y)) for x in range(w)] for y in range(h)]
    def over(x, y): return src.getpixel((min(x0 + x, src.width - 1), min(y0 + y, src.height - 1)))
    for by in range(0, h, PX):
        for bx in range(0, b, PX):
            if x0 + w + bx < src.width and over(w + bx, by)[3] > 127 and rnd.random() < (b - bx) / (b + 1.0):
                for a in range(PX):
                    for c in range(PX): t[by + c][bx + a] = over(w + bx + a, by + c)
    for by in range(0, b, PX):
        for bx in range(0, w, PX):
            if y0 + h + by < src.height and over(bx, h + by)[3] > 127 and rnd.random() < (b - by) / (b + 1.0):
                for a in range(PX):
                    for c in range(PX): t[by + c][bx + a] = over(bx + a, h + by + c)
    im = Image.new('RGBA', (w, h))
    for y in range(h):
        for x in range(w): im.putpixel((x, y), t[y][x])
    return im

def paint(name, W, H):
    return paint_art(name, W // PX, H // PX).resize((W, H), Image.NEAREST)

def paint_art(name, w, h):
    """Painted swatches in the pack's palette: dark outline, 1-px highlights, dithered wear."""
    r = random.Random(hash(name) & 0xffff)
    im = Image.new('RGBA', (w, h))
    def put(x, y, c): im.putpixel((x % w, y % h), c + (255,))
    if name == 'ballast':      # roof gravel: stones of 1-2 px, three greys, a light chip, a dark shadow pixel under it
        base = [(0x8B, 0x8B, 0x9E), (0x7D, 0x7F, 0x99), (0xA6, 0xA6, 0xB4)]
        for y in range(h):
            for x in range(w): put(x, y, base[0])
        for _ in range(w * h // 3):
            x, y = r.randrange(w), r.randrange(h); c = r.choice(base[1:] + [(0x6C, 0x6E, 0x85)]); put(x, y, c)
            if r.random() < .4: put(x + 1, y, c)
        for _ in range(w * h // 14):
            x, y = r.randrange(w), r.randrange(h); put(x, y, (0xD2, 0xD2, 0xDC)); put(x + 1, y + 1, (0x56, 0x59, 0x72))
    elif name == 'asphalt_worn':  # the pack's asphalt with oil dither and hairline cracks
        base = (0x4F, 0x4C, 0x52)
        for y in range(h):
            for x in range(w): put(x, y, base if r.random() > .06 else (0x58, 0x55, 0x5B))
        for _ in range(w * h // 40):
            x, y = r.randrange(w), r.randrange(h)
            for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1), (2, 1)):
                if (x + y + dx + dy) % 2 == 0: put(x + dx, y + dy, (0x3A, 0x38, 0x42))
        x, y = r.randrange(w), 0
        for yy in range(h // 2, h):
            put(x, yy, (0x2E, 0x2C, 0x36)); x += r.choice((-1, 0, 0, 1))
    elif name.startswith('brick'):   # coursed brick at the art's own brick size (the markets' base: 14 x 7 and a joint), grey tone roles
        bw, bh = (7, 3) if name == 'brick' else (11, 5)   # art pixels: 'brick' 14 x 6 texels and a joint, or 'brickbig' (split-face block)
        MORTAR, D, B, L, Hh = (0x46, 0x46, 0x4C), (0x5A,) * 3, (0x82,) * 3, (0xAA,) * 3, (0xDC,) * 3
        pw, ph = bw + 1, bh + 1
        for y in range(h):
            for x in range(w): put(x, y, MORTAR)
        for row in range(h // ph):
            off = (pw // 2) if row % 2 else 0
            for k in range(w // pw):
                x0 = k * pw + off; t = r.random()
                base = D if t < .2 else L if t < .34 else B
                for yy in range(bh):
                    for xx in range(bw):
                        c = base
                        if yy == bh - 1 and base is not D: c = D        # shadowed lower course edge
                        put(x0 + xx, row * ph + yy, c)
                put(x0, row * ph, Hh if base is not D else B)            # 1-px highlight on the top-left corner
                if r.random() < .45: put(x0 + 1 + r.randrange(bw - 2), row * ph + r.randrange(bh - 1), D if base is not D else L)  # dithered wear
    else:
        raise SystemExit('unknown paint ' + name)
    return im

def cut(e, roots):
    pack = e.get('pack', 'exteriors')
    if pack == 'paint':
        w, h = e.get('size') or e['rect'][2:]
        return paint(e['paint'], w, h)
    root = roots[pack]
    pick = e.get('pick')
    if pick:
        f = None
        for cand in sorted(glob.glob(os.path.join(root, pick['glob'])), key=lambda s: (len(s), s)):
            im = Image.open(cand).convert('RGBA')
            if pick.get('opaque') and min(p[3] for p in list(im.getdata())) < 255: continue
            lum = sum(sum(p[:3]) / 3 for p in list(im.getdata())) / (im.width * im.height)
            lo, hi = pick.get('lum', [0, 255])
            if lo <= lum <= hi: f = cand; break
        if not f: raise SystemExit('no match for ' + e['id'])
        rect = e.get('rect') or [0, 0, im.width, im.height]
    else:
        f = resolve(root, e['file']); rect = e['rect']
    src = Image.open(f).convert('RGBA')
    if e.get('kind') == 'autotile' and 'cell' in e:
        rect = [rect[0] + e['cell'][0] * 32, rect[1] + e['cell'][1] * 32, 32, 32]
    x, y, w, h = snap(rect)
    sz = e.get('size') or (w, h)
    tw, th = min(sz[0], w) // 2 * 2, min(sz[1], h) // 2 * 2
    wrap = wrap_of(e, tw, th)
    if seam_of(e, wrap) == 'blend' and wrap != 'none':
        return blend_seam(src, x, y, tw, th)
    return src.crop((x, y, x + tw, y + th))

def build(entries, roots):
    return [(e, cut(e, roots)) for e in entries]

def shelf(items, width=256):
    x = y = rowh = 0; pos = []
    for e, im in items:
        if x + im.width > width: x, y, rowh = 0, y + rowh + 1, 0
        pos.append((x, y)); x += im.width + 1; rowh = max(rowh, im.height)
    return pos, y + rowh

def write_atlas(items, out):
    pos, H = shelf(items)
    at = Image.new('RGBA', (256, H), (0, 0, 0, 0))
    meta = {}
    for (e, im), (x, y) in zip(items, pos):
        at.paste(im, (x, y)); meta[e['id']] = {'x': x, 'y': y, 'w': im.width, 'h': im.height, 'entry': e}
    os.makedirs(out, exist_ok=True)
    at.save(os.path.join(out, 'atlas.png'))
    json.dump(meta, open(os.path.join(out, 'atlas.json'), 'w'), indent=1)

ALPHA = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
def write_kotlin(items, path):
    pal = {}
    body = []
    for e, im in items:
        s = []
        for p in list(im.getdata()):
            c = (0 if p[3] < 128 else 0xFF000000) | (p[0] << 16) | (p[1] << 8) | p[2]
            if p[3] < 128: c = 0
            if c not in pal: pal[c] = len(pal)
            s.append(pal[c])
        body.append((e['id'], im.width, im.height, s))
    if len(pal) > 4096: raise SystemExit('palette too large')
    chars = lambda i: ALPHA[i // 64] + ALPHA[i % 64]
    lines = ['package com.lifemax.rpg.feature.habits.presentation.scene3d', '',
             '// GENERATED by tools/swatches/swatches.py: do not edit. Seamless tiles cut from the LimeZu sprites (see Swatches.kt).',
             'internal object SwatchData {',
             '    val PALETTE = intArrayOf(' + ', '.join('0x%08X.toInt()' % c if c else '0' for c in pal) + ')',
             '    /** id, width, height, pixels as two characters of palette index each (base 64 digits, A-Za-z0-9+/) */',
             '    val TILES: List<Triple<String, Pair<Int, Int>, String>> = listOf(']
    for id_, w, h, s in body:
        lines.append('        Triple("%s", %d to %d, "%s"),' % (id_, w, h, ''.join(chars(i) for i in s)))
    lines += ['    )', '}', '']
    open(path, 'w').write('\n'.join(lines))

def preview(items, png, rep=3, sc=2):
    cw = max(i.width for _, i in items) * rep * sc + 8; ch = max(i.height for _, i in items) * rep * sc + 22
    cols = 6; rows = (len(items) + cols - 1) // cols
    from PIL import ImageDraw
    S = Image.new('RGBA', (cols * cw, rows * ch), (40, 0, 40, 255)); d = ImageDraw.Draw(S)
    for k, (e, im) in enumerate(items):
        big = Image.new('RGBA', (im.width * rep, im.height * rep))
        for a in range(rep):
            for b in range(rep): big.paste(im, (a * im.width, b * im.height))
        big = big.resize((big.width * sc, big.height * sc), Image.NEAREST)
        ox, oy = (k % cols) * cw + 4, (k // cols) * ch + 18
        S.paste(big, (ox, oy)); d.text((ox, oy - 14), '%s %dx%d' % (e['id'], im.width, im.height), fill=(255, 255, 0, 255))
    S.save(png)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cmd', choices=['build', 'preview'])
    ap.add_argument('--root', action='append', default=[])
    ap.add_argument('--spec', action='append', default=[os.path.join(HERE, 'swatches.json')])
    ap.add_argument('--out', default=os.path.join(HERE, 'atlas'))
    ap.add_argument('--kotlin'); ap.add_argument('--png'); ap.add_argument('--catalog', help='tools/materials.json (git show wf-materials:kotlin-street/tools/materials.json)')
    a = ap.parse_args()
    roots = dict(r.split('=', 1) for r in a.root)
    items = build(load_specs(a.spec, a.catalog), roots)
    if a.cmd == 'preview': preview(items, a.png)
    else:
        write_atlas(items, a.out)
        if a.kotlin: write_kotlin(items, a.kotlin)
        print(len(items), 'swatches ->', a.out)

if __name__ == '__main__': main()
