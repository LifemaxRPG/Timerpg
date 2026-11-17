#!/usr/bin/env python3
"""Composes box-building textures for the Kotlin street from OUR sprites (the parts our game uses, exported to
street.json by export-street.ts): for each shop `bN_front.png` (storefront + middle floors), `bN_side.png` (a matching
side wall made from the same trim and windows), `bN_emis.png` (window glass, for night glow), `bN_roof.png`,
and `bN_sign.png` / `bN_prop.png` (rooftop signs). A home that wears one of our mobile homes (house_small_*,
house_medium_*) is cut into the pictures of a pitched-roof house instead (see build_house) and its shape goes to
houses.json. Usage: python3 make_buildings.py street.json out_dir; python3 make_buildings.py --showcase out_dir builds
every house sprite into out_dir for the preview's houses mode."""
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
RELIEF = {'middle_8': dict(box=(14, 50, 212, 118), arms_below=68, arms=((14, 34), (190, 212)), depth=48, wall=100)}

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
    if shop['parts'][0]['key'].startswith(('house_small_', 'house_medium_')): return build_house(shop, out)
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
            rm = relief_mask(im, RELIEF[m['key']]); relief[y:y + MID, :] |= rm; depth = max(depth, RELIEF[m['key']]['depth'])
            wall_line = y + RELIEF[m['key']]['wall']        # where the new balcony's slab meets the wall (above the shop's sign)
            # its colours, from the part itself: the stone's light face, and the floor tiles in the middle of the box
            pa = np.array(im)[..., :3].astype(int); px = pa[rm]; lum = px.sum(1)
            bx0, by0, bx1, by1 = RELIEF[m['key']]['box']
            colours = (np.median(px[lum >= np.percentile(lum, 85)], axis=0),
                       np.median(pa[by0 + 24:by1 - 24, bx0 + 40:bx1 - 40].reshape(-1, 3), axis=0))
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
        ys, xs = np.where(relief)
        # the engine builds the balcony new, as a box, from this: its span, the line its floor meets the wall, how far it
        # stands out, and the colours of its stone (the parapet's light face) and of its floor tiles
        stone, floor = colours
        # the storefront's sign where it hangs in front of the balcony: drawn again over the engine's box
        near = np.zeros_like(relief); near[max(0, ys.min() - 8):ys.max() + 30, :] = True
        ov = overlay & near
        if ov.any():
            oimg = np.zeros((H, 224, 4), np.uint8); oimg[ov] = fa[ov]
            Image.fromarray(oimg).save(f'{out}/{shop["sprite"]}_over.png')
        x0, x1, yb = int(xs.min()), int(xs.max() + 1), int(wall_line)
        rgb = lambda c: int(c[0]) << 16 | int(c[1]) << 8 | int(c[2])
        WINDOWS[shop['sprite'] + '_relief'] = [(x0, int(ys.min()), x1 - x0, yb - int(ys.min()), depth, rgb(stone), rgb(floor))]
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

# ------------------------------------------------------------------------------------------------ houses
# Our mobile homes (houseSmall / houseMedium) are drawn in a 3/4 view from above: a roof slab on top, the front wall
# below it, a strip of ground, some with a porch. The engine builds them as real geometry instead (a wall, a pitched
# roof whose ridge runs along the street, gable ends, a flue, the porch or steps as boxes), so each is cut here into the
# pictures that geometry wears. Every design shares one structure (three switches: glass or blinds windows, grass or
# a shadow under it, steps or a porch), so the places below are known boxes, in the art's own pixels (it is 2x pixel art).
# The engine's view (shared HabitsScene BuildingSpec): a building's 224 sprite pixels are W = 184 units wide, and a
# depth of 120 sprite pixels rises DY = 10 units on screen.
B_W, SRC_W, B_DY = 184.0, 224.0, 10.0
RISE_PER_120 = B_DY / (B_W / SRC_W)          # 12.174: how many sprite pixels on screen 120 pixels of depth rise
EAVE_OVER, VERGE, SLAB_R = 4, 2, 4           # the roof's overhang at the eaves and at the gables, the slab's thickness
FRONT_ROWS, FASCIA = 86, 6                   # the front picture: art rows 100..185 (the eave's edge, the wall, under the floor)
PORCH_GAIN = 1.0                             # how far the porch and steps stand out, against the art's own reading
HOUSE = {
    # roof x span (with its 2-px verges), wall x span, the near slope's, ridge cap's and far slope's art rows, the
    # house's depth (front to back, in pixels), the flue (x0, x1, its top row and foot row on the far slope), the hitch's
    # box, the wheels' x spans, the window panes (x, y, w, h in art pixels)
    'small': dict(roof=(0, 160), wall=(2, 158), slope=(58, 100), ridge=(50, 58), back=(20, 50), depth=80,
                  flue=(74, 88, 21, 43), hitch=(158, 192, 114, 164), wheels=((6, 32), (122, 150)),
                  panes=((16, 120, 24, 24), (120, 120, 24, 24))),
    'medium': dict(roof=(0, 224), wall=(2, 222), slope=(54, 100), ridge=(44, 54), back=(12, 44), depth=88,
                   flue=(74, 88, 17, 39), hitch=(222, 256, 114, 164), wheels=((6, 32), (130, 158), (192, 218)),
                   panes=((16, 122, 14, 20), (128, 120, 24, 24), (164, 120, 24, 24))),
}
HOUSES = {}

def house_key(key):
    """house_small_7_c9 -> ('small', 7, 'c9'); the grey base has no colour suffix."""
    p = key.split('_')
    return p[1], int(p[2]), (p[3] if len(p) > 3 else '')

def house_rise(rows, depth):
    """The roof's rise (sprite pixels) for which its near slope, from the eave (overhanging the wall) up to the ridge at
    mid-depth, shows exactly `rows` pixel rows on screen: every row of the art's slope taken 2:1 is one screen row, so
    the artist's pixels stay crisp, none doubled or dropped. About 21 degrees, steeper than the art's 12: the view is
    nearly level, and a roof as low as drawn would show as a sliver."""
    return rows - RISE_PER_120 * (depth / 2 + EAVE_OVER) / 120.0

def grassy(a):
    """The ground strip's grass in the art (and its semi-transparent shadow), not the house."""
    r, g, b, al = (a[..., i].astype(int) for i in range(4))
    return ((g > r + 25) & (g > b + 10)) | (al < 250)

def rgb_int(c):
    return int(c[0]) << 16 | int(c[1]) << 8 | int(c[2])

def shade(c, f):
    return tuple(int(max(0, min(255, round(v * f)))) for v in c[:3])

def build_house(shop, out):
    """One of our mobile homes as the pictures and the shape (houses.json) the engine's pitched-roof house is built from."""
    key = shop['parts'][0]['key']; n = shop['sprite']
    tier, design, colour = house_key(key)
    spec = HOUSE[tier]
    porch = design >= 5
    art = np.array(load(key))
    # a porch design is its steps twin with a porch in front: the wall behind it comes from the twin, pixel for pixel
    twin = np.array(load(key.replace(f'_{design}', f'_{design - 4}', 1))) if porch else art
    rx0, rx1 = spec['roof']; wx0, wx1 = spec['wall']; D = spec['depth']
    rng = np.random.default_rng(zlib.crc32(n.encode()))

    # --- the front: the eave's edge (fascia), the wall, and under the floor
    f = twin[100:100 + FRONT_ROWS, rx0:rx1].copy()
    f[FASCIA:, :wx0] = 0; f[FASCIA:, wx1:] = 0                      # beside the wall: nothing (the hitch is its own card)
    floor_line = np.median(f[72, wx0 + 4:wx1 - 4].reshape(-1, 4), axis=0)
    # under the floor (art rows 174-185) the art shows the ground strip between the wheels; the engine has its own lawn,
    # so it becomes the dark space under the house (a shadow, not the trim's colour): darkest under the floor, a little
    # light coming through from behind at the foot
    void = np.array((34, 34, 44)) * .7 + floor_line[:3] * .3
    for r in range(74, FRONT_ROWS):
        t = (r - 74) / (FRONT_ROWS - 75)
        c = shade(void, .7 + .45 * t) if r < FRONT_ROWS - 2 else shade(void, 1.6)
        f[r, wx0:wx1, :3] = c; f[r, wx0:wx1, 3] = 255
    f[72:74, wx0:wx1] = np.round(floor_line).astype(np.uint8)       # the floor's edge, across where the steps were too
    sub = twin[174:186, rx0:rx1]
    for (a0, a1) in spec['wheels']:
        w = sub[:, a0:a1]; keep = ~grassy(w)
        f[74:86, a0:a1][keep] = w[keep]
    # the window panes: kept as drawn (curtains or blinds behind the glass); the engine lays the sky's reflection, the
    # rain and the night's glow over them
    panes = [(x, y - 100, w, h) for (x, y, w, h) in spec['panes']]
    gm = glass(art[100:186, rx0:rx1])
    fill = min(gm[y:y + h, x:x + w].mean() for (x, y, w, h) in panes)
    glassy = design % 2 == 1
    assert (fill > .34) == glassy, f'{key}: the panes do not look like {"glass" if glassy else "blinds"} ({fill:.2f})'
    em = np.zeros_like(f)
    for (x, y, w, h) in panes: em[y:y + h, x:x + w] = (255, 205, 120, 235)

    # --- the roof: the near slope, the ridge cap and the far slope, each every other row of the art (its own 1x pixels)
    s0, s1 = spec['slope']; slope = art[s0:s1:2, rx0:rx1].copy()
    g0, g1 = spec['ridge']; ridge = art[g0:g1:2, rx0:rx1].copy()
    b0, b1 = spec['back']; back = art[b0:b1:2, rx0:rx1].copy()
    # the far slope's flue is built as a box; on the far slope's picture (seen only in the corner turn) it stays drawn
    rise = house_rise(slope.shape[0], D)

    # --- the gable ends: the wall's rows carried round the corner, the siding up into the triangle, a vent under the apex
    top = FRONT_ROWS + int(np.ceil(rise))
    side = np.zeros((top, D, 4), np.uint8)
    wall = f[:, wx0:wx1]
    rows = np.zeros((FRONT_ROWS, 4), np.uint8)
    for r in range(FRONT_ROWS):
        px = wall[r][(wall[r, :, 3] > 200) & ~glass(wall[r:r + 1])[0]]
        rows[r] = np.median(px, axis=0) if len(px) else (60, 60, 70, 255)
    trim = rows[7]                                                  # the band under the eave, in the variant's trim colour
    # the siding's repeat (its lap boards), found from the wall's own rows, carried up the triangle
    body = rows[12:62, :3].astype(int)
    period = min(range(4, 17, 2), key=lambda p: np.abs(body[p:] - body[:-p]).sum() / (len(body) - p))
    for t in range(top):
        y = top - t                                                 # height of the row's top (sprite pixels)
        r = FRONT_ROWS - y
        side[t, :] = rows[r] if r >= FASCIA else rows[12 + (r - FASCIA) % period]
    def gable(d):                                                   # the gable's top edge (height) at depth d
        dd = min(d, D - d)
        return FRONT_ROWS + rise * (dd + EAVE_OVER) / (D / 2 + EAVE_OVER) - SLAB_R
    for c in range(D):
        g = gable(c + .5)
        for t in range(top):
            if top - t - .5 > g: side[t, c] = 0
        # a band of the trim colour under the rake, as the eaves' band runs under the front's fascia
        for t in range(top):
            if 0 < g - (top - t - .5) <= 4 and top - t > FRONT_ROWS - FASCIA: side[t, c, :3] = trim[:3]
    # a window: the front's first, frame and all, in the middle of the end wall
    fx, fy, fw, fh = panes[0]
    crop = f[fy - 2:fy + fh + 6, fx - 2:fx + fw + 2]
    cx = D // 2 - crop.shape[1] // 2; ty = top - FRONT_ROWS + fy - 2
    m = crop[..., 3] > 200; side[ty:ty + crop.shape[0], cx:cx + crop.shape[1]][m] = crop[m]
    # a louvred vent under the apex, in the trim colour
    vy = int(top - (FRONT_ROWS + rise - SLAB_R - 4)); vw = 12
    for t in range(vy, vy + 7):
        side[t, D // 2 - vw // 2:D // 2 + vw // 2, :3] = shade(trim, .8 if (t - vy) % 2 else 1.15)
    side[vy - 1, D // 2 - vw // 2 - 1:D // 2 + vw // 2 + 1, :3] = shade(trim, .55)
    side[vy + 7, D // 2 - vw // 2 - 1:D // 2 + vw // 2 + 1, :3] = shade(trim, .55)
    # corner boards, and a fine grain so the siding reads as painted boards, not flat bands
    for c in (0, 1, D - 2, D - 1):
        live = side[:, c, 3] > 0
        side[live, c, :3] = shade(trim, .85)
    grain = rng.integers(-4, 5, (top // 2 + 1, D // 2 + 1))
    grain = np.kron(grain, np.ones((2, 2), np.int32))[:top, :D]
    side[..., :3] = np.clip(side[..., :3].astype(int) + grain[..., None] * (side[..., 3:4] > 0), 0, 255).astype(np.uint8)

    # --- the hitch: a card from the art, its foot on the ground beside the house at mid-depth
    hx0, hx1, hy0, hy1 = spec['hitch']
    hitch = art[hy0:hy1, hx0:hx1].copy()
    hitch[grassy(hitch)] = 0
    hys = np.where(hitch[..., 3] > 0)[0]
    hitch = hitch[:hys.max() + 1] if len(hys) else hitch

    # --- the flue, the rake boards' colours
    fl0, fl1, ft, ff = spec['flue']
    fpx = art[ft:ff, fl0:fl1].reshape(-1, 4)
    roofc = art[s0 + 6, rx0 + 40]
    fpx = fpx[(fpx[:, 3] > 200) & (np.abs(fpx[:, :3].astype(int) - roofc[:3].astype(int)).sum(1) > 30)]
    lum = fpx[:, :3].astype(int).sum(1)
    flue_body = np.median(fpx[lum <= np.percentile(lum, 60)], axis=0) if len(fpx) else roofc
    flue_cap = np.median(fpx[lum >= np.percentile(lum, 80)], axis=0) if len(fpx) else roofc
    fascia_c = np.median(art[101:103, rx0 + 8:rx1 - 8].reshape(-1, 4), axis=0)
    edge_c = np.median(art[104:106, rx0 + 8:rx1 - 8].reshape(-1, 4), axis=0)

    # --- the porch, or the steps, as boxes (x in the front picture's pixels, y up from the ground, z out of the wall)
    boxes = []; atlas = np.zeros((32, 64, 4), np.uint8)
    def box(x, y, z, top_c, front_c, side_c, ft=None, tt=None, card=False):
        boxes.append(dict(x=list(map(float, x)), y=list(map(float, y)), z=[float(v) * PORCH_GAIN for v in z],
                          c=[rgb_int(top_c), rgb_int(front_c), rgb_int(side_c)], ft=ft, tt=tt, card=card))
    if porch:
        dark = np.median(art[242:250, 52:56].reshape(-1, 4), axis=0)              # the legs and the outlines
        boards = art[172:222, 56:104]
        deck_row = np.median(boards, axis=0).astype(np.uint8)                       # each board's colour, front to back
        deck_c = np.median(boards.reshape(-1, 4), axis=0)
        rail = art[224:236, 50:108]; fascia_p = art[236:242, 52:108]
        rail_c = np.median(art[160:220, 52:54].reshape(-1, 4), axis=0)            # the side rail's light face
        atlas[0:12, 0:58] = rail; atlas[12:18, 0:56] = fascia_p
        atlas[18:24, 0:48] = deck_row[None, :, :]
        steps = art[186:236, 108:128]
        tread_c = np.median(steps[steps[..., 3] > 200].reshape(-1, 4)[:, :3], axis=0)
        lum_s = steps[..., :3].astype(int).sum(-1)
        tread_c = np.median(steps[(steps[..., 3] > 200) & (lum_s >= np.percentile(lum_s[steps[..., 3] > 200], 60))][:, :3], axis=0)
        riser_c = shade(tread_c, .72)
        # the legs under the deck's front, the deck (its top the boards, its front the art's fascia), the side rails,
        # the steps down to the right, the front railing standing on the deck's edge
        box((52, 56), (0, 10), (58, 64), dark, dark, dark)
        box((100, 106), (0, 10), (58, 64), dark, dark, dark)
        box((52, 108), (10, 14), (0, 64), deck_c, deck_c, shade(deck_c, .7), ft=[0, 12, 56, 6], tt=[0, 18, 48, 6])
        box((50, 56), (14, 26), (0, 64), rail_c, rail_c, shade(rail_c, .8))
        box((102, 108), (14, 26), (0, 28), rail_c, rail_c, shade(rail_c, .8))
        for i, h in enumerate((10.5, 7, 3.5)):
            box((108 + 6 * i, 114 + 6 * i), (0, h), (28, 64), tread_c, riser_c, shade(riser_c, .85))
        box((50, 108), (14, 26), (62, 64), rail_c, rail_c, shade(rail_c, .8), ft=[0, 0, 58, 12], card=True)
    else:
        st = twin[172:192, 58:102]
        ok = st[..., 3] > 200
        lum_s = st[..., :3].astype(int).sum(-1)
        tread_c = np.median(st[ok & (lum_s >= np.percentile(lum_s[ok], 55))][:, :3], axis=0)
        riser_c = np.median(st[ok & (lum_s < np.percentile(lum_s[ok], 45))][:, :3], axis=0)
        box((60, 100), (7, 14), (0, 8), tread_c, riser_c, shade(riser_c, .85))
        box((60, 100), (0, 7), (0, 16), tread_c, riser_c, shade(riser_c, .85))

    # --- the lawn: grass in the art's own greens (or the bare earth the shadow designs stand on), and the dirt path from
    # the steps to the street. In the lot's 224-pixel frame, one row per screen row (front first at the bottom)
    front_x = (224 - (rx1 - rx0)) // 2
    setback = int(shop.get('yard', {}).get('front', 2)) * 32
    setback = int(np.ceil(setback * PORCH_GAIN))
    deep = setback + D
    lrows = max(4, int(round(RISE_PER_120 * deep / 120)))
    grass = design in (1, 2, 5, 6)
    if grass:
        greens = np.array([(45, 114, 86), (55, 133, 78), (71, 151, 87), (50, 124, 82)])
        pick = rng.integers(0, len(greens), (lrows, 112)); pick = np.repeat(pick, 2, axis=1)
        lawn = greens[pick]
    else:
        earth = np.array([(122, 104, 84), (110, 94, 76), (131, 113, 90), (116, 99, 80)])
        pick = rng.integers(0, len(earth), (lrows, 112)); pick = np.repeat(pick, 2, axis=1)
        lawn = earth[pick]
    lawn = np.concatenate([lawn, np.full((lrows, 224, 1), 255)], axis=2).astype(np.uint8)
    path_c = np.array([(168, 142, 108), (156, 130, 98), (176, 152, 118)])
    if porch: px0, px1, reach = front_x + 126, front_x + 146, 36 * PORCH_GAIN
    else: px0, px1, reach = front_x + 64, front_x + 96, setback - 16 * PORCH_GAIN
    for t in range(lrows):
        d = (lrows - t - .5) * deep / lrows                         # this row's depth from the street
        if d <= reach:
            for x in range(px0, px1, 2):
                lawn[t, x:x + 2, :3] = path_c[rng.integers(0, 3)]
    lawn[-1, :, :3] = shade(lawn[-1, 0], .8)                       # the kerb of the lot along the pavement

    # --- the yard's edge along the street, as the game fences it (src/town/layout.ts): a picket fence, a hedge for a
    # formal yard, nothing for a natural one; open at the gate where the path meets the street, and across the porch
    style = shop.get('yard', {}).get('style', 'compact')
    fence = None
    if style != 'natural':
        fence = np.zeros((32, 224, 4), np.uint8)
        gaps = [(px0 - 6, px1 + 6)] + ([(front_x + 50, front_x + 128)] if porch else [])
        def open_at(x0, x1): return any(x0 < g1 and x1 > g0 for (g0, g1) in gaps)
        if style == 'formal':
            bushes = [np.array(load(f'hedge_{i}')) for i in range(1, 6)]
            x = -4
            while x < 224:
                b = bushes[int(rng.integers(0, 5))]
                if not open_at(x + 6, x + 26):
                    tile = Image.fromarray(fence); tile.alpha_composite(Image.fromarray(b), (int(x), 0)) if x >= 0 else tile.alpha_composite(Image.fromarray(b[:, -x:]), (0, 0))
                    fence = np.array(tile)
                x += 18 + int(rng.integers(0, 4))
        else:
            # the art's two fence pieces (each drawn with an open end) laid end to end, alternating as the game does
            pieces = [np.array(load('fence_9'))[:, 0:28], np.array(load('fence_10'))[:, 6:32]]
            tx, i = 0, 0
            while tx < 224:
                tile = pieces[i % 2]
                for c in range(min(tile.shape[1], 224 - tx)):
                    if not open_at(tx + c, tx + c + 1): fence[:, tx + c] = tile[:, c]
                tx += tile.shape[1]; i += 1
        if not (fence[..., 3] > 0).any(): fence = None

    for name, img in (('front', f), ('emis', em), ('side', side), ('slope', slope), ('ridge', ridge), ('back', back),
                      ('hitch', hitch), ('lawn', lawn)):
        Image.fromarray(img).save(f'{out}/{n}_{name}.png')
    if porch: Image.fromarray(atlas).save(f'{out}/{n}_porch.png')
    if fence is not None: Image.fromarray(fence).save(f'{out}/{n}_fence.png')
    # its windows are not cut out (their curtains and blinds are the art's own), so it has no rooms behind them
    WINDOWS[n] = []
    r3 = lambda v: round(float(v), 3)
    HOUSES[n] = dict(
        tier=tier, design=design, colour=colour, frontX=front_x, wall=[wx0, wx1], h=FRONT_ROWS, fascia=FASCIA,
        rise=r3(rise), depth=D, over=[EAVE_OVER, VERGE], slab=SLAB_R, setback=setback,
        flue=dict(x=[fl0 + 1, fl1 - 1], d=[r3(.63 * D - 5), r3(.63 * D + 5)], top=r3(FRONT_ROWS + rise + 10),
                  body=rgb_int(flue_body), cap=rgb_int(flue_cap)),
        rake=[rgb_int(fascia_c), rgb_int(edge_c)], hitch=[wx1, 0], panes=[list(p) for p in panes], glass=glassy,
        boxes=boxes, lawn='grass' if grass else 'earth', style=style,
        # the fence strip's depth from the street and the row its foot stands on (below it: its shadow on the ground)
        fence=dict(d=3, foot=28 if style != 'formal' else 32) if fence is not None else None,
        roofTint=rgb_int(art[s0 + 10, rx0 + 20]),
    )

# What this script writes for a sprite. Anything else in the folder is left alone: the lighting maps make_lighting.py
# made from each front need a depth model that is not always on disk, so they must survive a re-run that does not
# change their front.
OUTPUTS = ('front', 'side', 'emis', 'roof', 'sign', 'prop', 'glass', 'over', 'slope', 'ridge', 'back', 'hitch', 'lawn', 'porch', 'fence')
MAPS = ('lit', 'shade')

def pixels(path):
    return np.array(Image.open(path).convert('RGBA')) if os.path.exists(path) else None

def showcase(out):
    """Every house sprite (8 designs x 2 sizes x 10 colours) on a street.json of its own, in out/ (street.json and
    buildings/), for checking them by eye: street 0 has every design once in a colour each, street 1 every colour of
    one design. The preview reads it with -Dassets=out -Dmode=houses."""
    shops, row, colours = [], [], []
    names = {}
    for tier in ('small', 'medium'):
        for design in range(1, 9):
            for c in [''] + [f'_c{k}' for k in range(1, 10)]:
                key = f'house_{tier}_{design}{c}'
                im = load(key); sprite = f'b{len(shops)}'; names[key] = sprite
                shops.append(dict(id=f'show:{key}', sprite=sprite, home=True, name=key, kind='Home', owner='', colour=c[1:] or 'base',
                                  parts=[dict(key=key, w=im.width, h=im.height, y=0)], yard=dict(front=2, style=('compact', 'formal', 'natural', 'flower')[len(shops) % 4])))
    cycle = [''] + [f'_c{k}' for k in range(1, 10)]
    for i, (tier, design) in enumerate([(t, d) for t in ('small', 'medium') for d in range(1, 9)]):
        row.append(names[f'house_{tier}_{design}{cycle[(i * 3 + 1) % 10]}'])
    colours = [names[f'house_small_7{c}'] for c in cycle]
    os.makedirs(out + '/buildings', exist_ok=True)
    json.dump(dict(town='showcase', year=2025, type='every house sprite', shops=shops,
                   streets=[dict(name='Every design', buildings=row), dict(name='Every colour', buildings=colours)]),
              open(out + '/street.json', 'w'), indent=1)
    return out + '/street.json', out + '/buildings'

if __name__ == '__main__':
    if sys.argv[1] == '--showcase': src, out = showcase(sys.argv[2])
    else: src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    shops = json.load(open(src))['shops']
    names = {s['sprite'] for s in shops}
    # each front as it was, so a lighting map is kept only while it still fits its front
    before = {n: pixels(f'{out}/{n}_front.png') for n in names}
    for f in glob.glob(out + '/b*_*.png'):
        sprite, suffix = os.path.basename(f)[:-4].split('_', 1)
        # a sprite no longer on the streets loses everything; one still there only what is made again below
        if suffix in OUTPUTS or sprite not in names: os.remove(f)
    for shop in shops:
        build(shop, out)
    for n in names:
        now, was = pixels(f'{out}/{n}_front.png'), before[n]
        if was is None or now is None or was.shape != now.shape or (was != now).any():
            for m in MAPS:
                if os.path.exists(f'{out}/{n}_{m}.png'): os.remove(f'{out}/{n}_{m}.png')
    json.dump(WINDOWS, open(out + '/windows.json', 'w'))
    # the houses' shapes in a file of their own: the app reads windows.json as lists only
    if HOUSES: json.dump(HOUSES, open(out + '/houses.json', 'w'), sort_keys=True)
    elif os.path.exists(out + '/houses.json'): os.remove(out + '/houses.json')
    print('wrote', len(os.listdir(out)), 'files to', out, '; windows:', {k: len(v) for k, v in WINDOWS.items()})
