#!/usr/bin/env python3
"""Composes box-building textures for the Kotlin street from OUR sprites (the parts our game uses, exported to
street.json by export-street.ts): for each shop `bN_front.png` (storefront + middle floors), `bN_side.png` (a matching
side wall made from the same trim and windows), `bN_emis.png` (window glass, for night glow), `bN_roof.png`,
and `bN_sign.png` / `bN_prop.png` (rooftop signs).  Usage: python3 make_buildings.py street.json out_dir"""
import glob, json, os, sys
import numpy as np
from PIL import Image

ASSETS = os.path.join(os.path.dirname(__file__), '..', '..', 'public', 'assets')
BODY, MID, D = 96, 128, 120

def load(key):
    hits = glob.glob(os.path.join(ASSETS, '*', key + '.png'))
    if not hits: raise SystemExit('missing sprite ' + key)
    return Image.open(hits[0]).convert('RGBA')

def glass(a):
    r, g, b, al = (a[..., i].astype(int) for i in range(4))
    return (al > 200) & (b > r + 18) & (b > g - 8) & ((r + g + b) / 3 > 100)

def components(mask):
    """Bounding boxes of connected glass regions (4-neighbour flood fill)."""
    h, w = mask.shape; seen = np.zeros_like(mask); out = []
    for y in range(h):
        for x in range(w):
            if mask[y, x] and not seen[y, x]:
                st = [(y, x)]; seen[y, x] = True; x0 = x1 = x; y0 = y1 = y; n = 0
                while st:
                    cy, cx = st.pop(); n += 1
                    x0 = min(x0, cx); x1 = max(x1, cx); y0 = min(y0, cy); y1 = max(y1, cy)
                    for ny, nx in ((cy+1, cx), (cy-1, cx), (cy, cx+1), (cy, cx-1)):
                        if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                            seen[ny, nx] = True; st.append((ny, nx))
                if n > 60: out.append((x0, y0, x1, y1))
    return out

def side_segment(seg, windows):
    """A side wall for one front segment: each row takes the typical colour of that row of the front (so the trim bands
    and the wall colour carry over, and decoration averages out), plus the segment's own windows."""
    w, h = seg.size
    a = np.array(seg); gm = glass(a)
    rows = np.zeros((h, 4), np.uint8)
    for y in range(h):
        px = a[y][(a[y, :, 3] > 200) & ~gm[y]]
        rows[y] = np.median(px, axis=0) if len(px) else (60, 50, 50, 255)
    side = Image.fromarray(np.repeat(rows[:, None, :], D, axis=1))
    if windows:
        boxes = [b for b in components(gm) if (b[2]-b[0]) < 70]
        if boxes:
            x0, y0, x1, y1 = boxes[0]
            m = 3; crop = seg.crop((max(0, x0-m), max(0, y0-m), min(w, x1+m+1), min(h, y1+m+1)))
            for fx in (0.22, 0.62):
                side.alpha_composite(crop, (int(D * fx), max(0, y0-m)))
    return side

def build(shop, out):
    parts = shop['parts']; ground = next(p for p in parts if not p['key'].startswith(('roof_', 'roofprop_', 'middle_')))
    mids = [p for p in parts if p['key'].startswith('middle_')]
    roof = next((p for p in parts if p['key'].startswith('roof_')), None)
    prop = next((p for p in parts if p['key'].startswith('roofprop_')), None)
    g = load(ground['key']); body_h = min(BODY, g.height)
    H = body_h + MID * len(mids)
    front = Image.new('RGBA', (224, H)); side = Image.new('RGBA', (D, H))
    body = g.crop((0, g.height - body_h, 224, g.height))
    front.alpha_composite(body, (0, H - body_h)); side.alpha_composite(side_segment(body, False), (0, H - body_h))
    y = H - body_h
    for m in mids:
        im = load(m['key']); y -= MID
        front.alpha_composite(im, (0, y)); side.alpha_composite(side_segment(im, True), (0, y))
    # darken the side a touch so it reads as a turned face
    em = np.zeros((H, 224, 4), np.uint8); fa = np.array(front); gm = glass(fa)
    em[gm] = (255, 205, 120, 235)
    n = shop['sprite']
    front.save(f'{out}/{n}_front.png'); side.save(f'{out}/{n}_side.png'); Image.fromarray(em).save(f'{out}/{n}_emis.png')
    # a flat roof (gravel with a parapet in the building's own trim colour); our roof sprites are drawn from above with
    # transparent margins, which read as a faint strip on a side-on box
    rim = tuple(int(v) for v in np.median(fa[0:4, 8:216].reshape(-1, 4), axis=0)[:3])
    rng = np.random.default_rng(abs(hash(shop['sprite'])) % 2**32)
    roof_img = np.zeros((64, 224, 4), np.uint8); roof_img[:, :] = (74, 78, 87, 255)
    noise = rng.integers(-14, 14, (64, 224, 1)); roof_img[..., :3] = np.clip(roof_img[..., :3].astype(int) + noise, 0, 255)
    roof_img[:5, :, :3] = rim; roof_img[:, :5, :3] = rim; roof_img[:, -5:, :3] = rim; roof_img[-5:, :, :3] = rim
    Image.fromarray(roof_img).save(f'{out}/{n}_roof.png')
    if g.height > BODY: g.crop((0, 0, 224, g.height - BODY)).save(f'{out}/{n}_sign.png')
    if prop: load(prop['key']).save(f'{out}/{n}_prop.png')

if __name__ == '__main__':
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    for f in glob.glob(out + '/*.png'): os.remove(f)
    for shop in json.load(open(src))['shops']:
        build(shop, out)
    print('wrote', len(os.listdir(out)), 'files to', out)
