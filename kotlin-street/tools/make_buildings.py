#!/usr/bin/env python3
"""Composes box-building textures for the Kotlin street from OUR sprites (the parts our game uses, exported to
street.json by export-street.ts): for each shop `bN_front.png` (storefront + middle floors), `bN_side.png` (a matching
side wall made from the same trim and windows), `bN_emis.png` (window glass, for night glow), `bN_roof.png`,
and `bN_sign.png` / `bN_prop.png` (rooftop signs).  Usage: python3 make_buildings.py street.json out_dir"""
import glob, json, os, sys, zlib
import numpy as np
from PIL import Image

ASSETS = os.path.join(os.path.dirname(__file__), '..', '..', 'public', 'assets')
BODY, MID, D = 96, 128, 120
WINDOWS = {}

def load(key):
    hits = glob.glob(os.path.join(ASSETS, '*', key + '.png'))
    if not hits: raise SystemExit('missing sprite ' + key)
    return Image.open(hits[0]).convert('RGBA')

def glass(a):
    r, g, b, al = (a[..., i].astype(int) for i in range(4))
    return (al > 200) & (b > r + 18) & (b > g - 8) & ((r + g + b) / 3 > 134)

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

def dilate(m, r):
    out = m.copy()
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            out |= np.roll(np.roll(m, dy, 0), dx, 1)
    return out

def find_windows(front, upper_rows):
    """Clean, window-shaped glass regions on the upper floors: returns their boxes (x, y, w, h) in sprite pixels and the
    mask of the glass to cut out. Panes split by a mullion are grouped into one window; arched, tiny or ragged glass is left alone."""
    a = np.array(front); gm = glass(a); gm[upper_rows:, :] = False
    boxes = []; cut = np.zeros_like(gm)
    for (x0, y0, x1, y1) in components(dilate(gm, 1)):
        sub = gm[y0:y1 + 1, x0:x1 + 1]
        ys, xs = np.where(sub)
        if len(xs) == 0: continue
        bx0, bx1, by0, by1 = x0 + xs.min(), x0 + xs.max(), y0 + ys.min(), y0 + ys.max()
        w, h = bx1 - bx0 + 1, by1 - by0 + 1
        fill = len(xs) / float(w * h)
        if 10 <= w <= 64 and 14 <= h <= 72 and fill > .62:
            boxes.append((int(bx0), int(by0), int(w), int(h)))
            cut[by0:by1 + 1, bx0:bx1 + 1] |= gm[by0:by1 + 1, bx0:bx1 + 1]
    return boxes, cut

def side_segment(seg, windows):
    """A side wall for one front segment: each row takes the typical colour of that row of the front (so the trim bands
    and the wall colour carry over, and decoration averages out), plus the segment's own windows."""
    w, h = seg.size
    a = np.array(seg); gm = glass(a)
    rows = np.zeros((h, 4), np.uint8)
    for y in range(h):
        px = a[y][(a[y, :, 3] > 200) & ~gm[y]]
        rows[y] = np.median(px, axis=0) if len(px) else (60, 50, 50, 255)
    if not windows:
        # the ground floor's side wall: the wall's own colour with brick courses, not the shop display's bands
        solid = a[(a[..., 3] > 200) & ~gm]
        base = np.median(solid, axis=0)
        rows = (rows * .25 + base * .75).astype(np.uint8)
        rows[::6, :3] = (rows[::6, :3].astype(np.int32) * 86 // 100).astype(np.uint8)
    side = Image.fromarray(np.repeat(rows[:, None, :], D, axis=1))
    if windows:
        boxes = [b for b in components(gm) if (b[2]-b[0]) < 70]
        if boxes:
            x0, y0, x1, y1 = boxes[0]
            m = 3; crop = seg.crop((max(0, x0-m), max(0, y0-m), min(w, x1+m+1), min(h, y1+m+1)))
            for fx in (0.12, 0.42, 0.72):
                side.alpha_composite(crop, (int(D * fx), max(0, y0-m)))
    # corner pilasters, and a fine grain so the wall reads as masonry and not as flat bands
    arr = np.array(side).astype(np.int32)
    arr[:, :5, :3] = arr[:, :5, :3] * 72 // 100; arr[:, -5:, :3] = arr[:, -5:, :3] * 72 // 100
    arr[:, 5:7, :3] = arr[:, 5:7, :3] * 118 // 100; arr[:, -7:-5, :3] = arr[:, -7:-5, :3] * 118 // 100
    g = np.random.default_rng(h * 7 + int(arr[0, 0, 0])).integers(-5, 6, (h // 3 + 1, D // 3 + 1))
    g = np.kron(g, np.ones((3, 3), np.int32))[:h, :D]
    arr[..., :3] = np.clip(arr[..., :3] + g[..., None], 0, 255)
    return Image.fromarray(arr.astype(np.uint8))

# What sticks out of a middle floor (drawn by the artist seen from above, as a flat picture on the wall): its region in the
# part's own pixels, the rows above which only the given columns belong to it (a balcony's side rails rise past the
# windows), and how far it stands out from the wall in sprite pixels. The engine draws it as a box: it slides off the
# wall with the view, shows its side and its thickness, and throws a shadow on the wall.
RELIEF = {'middle_8': dict(box=(14, 50, 212, 118), arms_below=68, arms=((14, 34), (190, 212)), depth=44)}

def relief_mask(im, spec):
    """The part's pixels that belong to what sticks out: everything in the box that is not the plain wall colour."""
    a = np.array(im).astype(int); h, w = a.shape[:2]
    x0, y0, x1, y1 = spec['box']
    wall = a[..., 2] >= a[..., 0] + 30                       # the blue-grey wall (and the window glass)
    m = np.zeros((h, w), bool)
    m[y0:y1, x0:x1] = ~wall[y0:y1, x0:x1] & (a[y0:y1, x0:x1, 3] > 40)
    top = np.zeros((h, w), bool)
    for (c0, c1) in spec['arms']: top[y0:spec['arms_below'], c0:c1] = True
    m[y0:spec['arms_below'], :] &= top[y0:spec['arms_below'], :]
    return m

def build(shop, out):
    parts = shop['parts']; ground = next(p for p in parts if not p['key'].startswith(('roof_', 'roofprop_', 'middle_')))
    mids = [p for p in parts if p['key'].startswith('middle_')]
    roof = next((p for p in parts if p['key'].startswith('roof_')), None)
    prop = next((p for p in parts if p['key'].startswith('roofprop_')), None)
    g = load(ground['key']); body_h = min(BODY, g.height)
    H = body_h + MID * len(mids)
    over = g.height - body_h                      # the sign that hangs over the top of the storefront
    extra = max(0, over - (H - body_h))           # the part of it that stands above the roofline
    front = Image.new('RGBA', (224, H + extra)); side = Image.new('RGBA', (D, H))
    body = g.crop((0, g.height - body_h, 224, g.height))
    side.alpha_composite(side_segment(body, False), (0, H - body_h))
    y = H - body_h                                # the middle floors stack on top of the storefront
    relief = np.zeros((H, 224), bool); depth = 0
    for m in mids:
        im = load(m['key']); y -= MID
        if m['key'] in RELIEF:
            relief[y:y + MID, :] |= relief_mask(im, RELIEF[m['key']]); depth = max(depth, RELIEF[m['key']]['depth'])
        front.alpha_composite(im, (0, extra + y)); side.alpha_composite(side_segment(im, True), (0, y))
    # the upper floors alone (before the storefront's sign is laid over them), to find their real windows in
    mids_only = front.crop((0, extra, 224, extra + H))
    # the storefront goes on LAST, so its sign overlaps the floor above it, as in our game
    front.alpha_composite(g, (0, extra + H - g.height))
    overlay = np.zeros((H, 224), bool)                # where the storefront (and its overhanging sign) covers the front
    ga = np.array(g)[..., 3] > 40
    top = H - g.height
    overlay[max(0, top):, :] = ga[max(0, -top):, :]
    sign = front.crop((0, 0, 224, extra)) if extra > 0 else None
    front = front.crop((0, extra, 224, extra + H))
    prop = next((p for p in parts if p['key'].startswith('roofprop_')), None)
    # the upper floors' windows become real openings: their glass is cut out of the front (the engine draws the room
    # behind, the reflection and the glint over), and only the remaining glass (shop windows) glows from the mask
    boxes, cut = find_windows(mids_only, H - body_h)
    # a window the storefront's sign hangs over is not a window any more (the gym's dumbbell, a bait shop's fish)
    kept = []
    for (bx, by, bw, bh) in boxes:
        if overlay[by:by + bh, bx:bx + bw].mean() > .08: cut[by:by + bh, bx:bx + bw] = False
        else: kept.append((bx, by, bw, bh))
    boxes = kept
    cut &= ~overlay
    fa = np.array(front); fa[cut] = (0, 0, 0, 0)
    # what sticks out comes off the wall into its own picture (where the storefront's sign hangs over it, the sign stays
    # on the front), and the wall behind it is filled in with the wall's own colour, row by row
    relief &= ~overlay
    if relief.any():
        mo = np.array(mids_only)
        rimg = np.zeros((H, 224, 4), np.uint8); rimg[relief] = mo[relief]
        Image.fromarray(rimg).save(f'{out}/{shop["sprite"]}_relief.png')
        ys, xs = np.where(relief)
        WINDOWS[shop['sprite'] + '_relief'] = [(int(xs.min()), int(ys.min()), int(xs.max() - xs.min() + 1), int(ys.max() - ys.min() + 1), depth)]
        wallc = (fa[..., 2].astype(int) >= fa[..., 0].astype(int) + 30) & (fa[..., 3] > 200) & ~glass(fa)
        fallback = np.median(fa[wallc][:, :4], axis=0).astype(np.uint8) if wallc.any() else np.array((110, 130, 160, 255), np.uint8)
        for yy in np.unique(ys):
            row = wallc[yy] & ~relief[yy]
            c = np.median(fa[yy][row], axis=0).astype(np.uint8) if row.sum() > 6 else fallback
            fa[yy][relief[yy]] = c
    front = Image.fromarray(fa)
    WINDOWS[shop['sprite']] = boxes
    # the shopfront glass (the display shows through it): nothing is cut; the engine lays a faint reflection over this mask
    # (big panes only, so signs, lettering and walls are left alone, and the display items stay clean)
    sg = glass(fa); sg[:H - body_h, :] = False
    keep = np.zeros_like(sg); soft = np.zeros_like(sg)
    for (x0, y0, x1, y1) in components(dilate(sg, 1)):
        sub = sg[y0:y1 + 1, x0:x1 + 1]; n = int(sub.sum())
        ys, xs = np.where(sub)
        if n < 40: continue
        bw, bh = int(xs.max() - xs.min() + 1), int(ys.max() - ys.min() + 1)
        # real windows and doors reach down into the shopfront's window zone; signs, lettering and ledges end well above it
        if y0 + ys.max() < H - 40 or bh < 14: continue
        if n >= 150 and n / float(bw * bh) >= .6: keep[y0:y1 + 1, x0:x1 + 1] |= sub                    # a clean pane: its glass
        elif 30 <= bw <= 170 and bh >= 24: soft[y0 + ys.min():y0 + ys.max() + 1, x0 + xs.min():x0 + xs.max() + 1] = True   # a display window: the whole pane, fainter, items showing through
    gimg = np.zeros((H, 224, 4), np.uint8); gimg[soft & ~keep] = (255, 255, 255, 105); gimg[keep] = (255, 255, 255, 255)
    Image.fromarray(gimg).save(f'{out}/{shop["sprite"]}_glass.png')
    WINDOWS[shop['sprite'] + '_shop'] = [(0, int(H - body_h), 224, int(body_h))]
    # what glows at night: the shopfront's window panes only (not the sign's letters, the trim or the ledges)
    em = np.zeros((H, 224, 4), np.uint8); em[keep | soft] = (255, 205, 120, 235)
    n = shop['sprite']
    front.save(f'{out}/{n}_front.png'); side.save(f'{out}/{n}_side.png'); Image.fromarray(em).save(f'{out}/{n}_emis.png')
    if sign is not None: sign.save(f'{out}/{n}_sign.png')
    if prop: load(prop['key']).save(f'{out}/{n}_prop.png')
    # a flat roof (gravel with a parapet in the building's own trim colour); our roof sprites are drawn from above with
    # transparent margins, which read as a faint strip on a side-on box
    rim = tuple(int(v) for v in np.median(fa[0:4, 8:216].reshape(-1, 4), axis=0)[:3])
    rng = np.random.default_rng(zlib.crc32(shop["sprite"].encode()))
    roof_img = np.zeros((64, 224, 4), np.uint8); roof_img[:, :] = (74, 78, 87, 255)
    noise = rng.integers(-14, 14, (64, 224, 1)); roof_img[..., :3] = np.clip(roof_img[..., :3].astype(int) + noise, 0, 255)
    roof_img[:5, :, :3] = rim; roof_img[:, :5, :3] = rim; roof_img[:, -5:, :3] = rim; roof_img[-5:, :, :3] = rim
    Image.fromarray(roof_img).save(f'{out}/{n}_roof.png')

if __name__ == '__main__':
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    for f in glob.glob(out + '/*.png'): os.remove(f)
    for shop in json.load(open(src))['shops']:
        build(shop, out)
    json.dump(WINDOWS, open(out + '/windows.json', 'w'))
    print('wrote', len(os.listdir(out)), 'files to', out, '; windows:', {k: len(v) for k, v in WINDOWS.items()})
