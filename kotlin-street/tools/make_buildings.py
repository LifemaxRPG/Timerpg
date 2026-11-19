#!/usr/bin/env python3
"""Composes box-building textures for the Kotlin street from OUR sprites (the parts our game uses, exported to
street.json by export-street.ts): for each shop `bN_front.png` (storefront + middle floors), `bN_side.png` (a matching
side wall made from the same trim and windows), `bN_emis.png` (window glass, for night glow), `bN_roof.png`,
and `bN_sign.png` / `bN_prop.png` (rooftop signs). A home that wears one of our mobile homes (house_small_*,
house_medium_*) is cut into the pictures of a pitched-roof house instead (see build_house) and its shape goes to
houses.json. Usage: python3 make_buildings.py [--jobs=N] street.json out_dir; python3 make_buildings.py [--jobs=N] --showcase
out_dir builds every house sprite into out_dir for the preview's houses mode. The shops are built in N processes (default:
every core; --jobs=1 builds them in this one); the output is the same either way."""
import glob, json, os, sys, zlib
import importlib
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

def components(mask, least=60):
    """Bounding boxes of connected glass regions (4-neighbour flood fill) of more than `least` pixels."""
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
                if n > least: out.append((x0, y0, x1, y1))
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
    """A side wall for one front segment: each row the colour of that row's WALL on the front (its glass and the frames
    round it left out, so the trim bands and the wall colour carry over and the windows do not smear into grey bands),
    plus every row of the front's own windows, each its real window with its frame, repeated along the depth."""
    w, h = seg.size
    a = np.array(seg); gm = glass(a)
    near = dilate(gm, 4)                                            # the glass and its frame
    rows = np.zeros((h, 4), np.uint8); ok = np.zeros(h, bool)
    for y in range(h):
        px = a[y][(a[y, :, 3] > 200) & ~near[y]]
        if len(px) >= 8: rows[y] = np.median(px, axis=0); ok[y] = True
    if ok.any():
        idx = np.where(ok)[0]
        for y in np.where(~ok)[0]: rows[y] = rows[idx[np.abs(idx - y).argmin()]]   # a row all windows: the nearest wall's
    else: rows[:] = (60, 50, 50, 255)
    if not windows:
        # the ground floor's side wall: the wall's own colour with brick courses, not the shop display's bands
        solid = a[(a[..., 3] > 200) & ~gm]
        base = np.median(solid, axis=0)
        rows = (rows * .25 + base * .75).astype(np.uint8)
        rows[::6, :3] = (rows[::6, :3].astype(np.int32) * 86 // 100).astype(np.uint8)
    side = Image.fromarray(np.repeat(rows[:, None, :], D, axis=1))
    if windows:
        # whole windows (panes split by a mullion together), grouped into rows by their top
        boxes = sorted((b for b in components(dilate(gm, 1), 20) if (b[2] - b[0]) < 70), key=lambda b: (b[1], b[0]))
        done = []
        for (x0, y0, x1, y1) in boxes:
            if any(abs(y0 - d) <= 4 for d in done): continue
            done.append(y0)
            m = 3; crop = seg.crop((max(0, x0 - m), max(0, y0 - m), min(w, x1 + m + 1), min(h, y1 + m + 1)))
            cw = crop.width
            spots = (0.12, 0.42, 0.72) if cw <= 34 else (0.18, 0.58) if cw <= 52 else (0.3,)
            for fx in spots:
                side.alpha_composite(crop, (min(D - cw, int(D * fx)), max(0, y0 - m)))
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
# windows), and how far it stands out from the wall in sprite pixels. The classic street draws it as a box from `_relief`.
# The three balcony floors (middle_2 brick, middle_8 blue, middle_14 grey) are one drawing in three colours: a U of
# stone (its cap seen from above, the slab's dark edge under it) round a tiled floor, two small brackets under it, the
# double window behind it reaching down to the floor. The Filament town builds it as real geometry (`_balc`, BALCONY).
RELIEF = {k: dict(box=(14, 50, 212, 118), arms_below=68, arms=((14, 34), (190, 212)), depth=48, wall=100)
          for k in ('middle_2', 'middle_8', 'middle_14')}

# The balcony as built (rows in the part's own pixels, x in the front's): the slab's span, the parapet's top, the floor
# (the slab's top), the slab's foot, how far it stands out and the parapet's thickness; the brackets under it (their x
# spans, how far down and out they reach); the rows of the art's floor (from above), of its cap and of the slab's edge;
# the double window behind it, made a glass door down to the floor (its x span, the glass rows repeated, its sill rows).
BALCONY = dict(x=(14, 212), top=76, floor=100, foot=108, depth=32, thick=8, brackets=((32, 42), (182, 192)), bdown=8,
               bout=16, floor_rows=(74, 96), floor_x=(30, 194), cap_row=99, outline_row=96, slab_rows=(105, 113),
               door_x=(84, 144), door_glass=(46, 66), door_sill=(66, 70))

_TEMPLATE = {}
# How far the stone ledges (the sill courses between storeys, the cornice under the roof) stand out of the wall
LEDGE_OUT = 3
# The steps before the classic offices' doors (their storefront's rows: the upper step 76..84, the lower to the ground)
DOOR_STEPS = {k: [(92, 132, 84, 96, 10), (92, 132, 76, 84, 5)] for k in ('classic_law_office_', 'classic_doctor_s_office_')}
def relief_mask(im, spec):
    """The part's pixels that belong to the balcony: the same drawing in each colour, so its outline is found once on the
    blue one (everything in the box that is not the plain blue wall or its glass), less the band along the storey's foot
    that runs on under it, plus the two brackets under the slab."""
    if 'm' not in _TEMPLATE:
        a = np.array(load('middle_8')).astype(int); h, w = a.shape[:2]
        x0, y0, x1, y1 = spec['box']
        wall = a[..., 2] >= a[..., 0] + 30
        m = np.zeros((h, w), bool)
        m[y0:y1, x0:x1] = ~wall[y0:y1, x0:x1] & (a[y0:y1, x0:x1, 3] > 40)
        top = np.zeros((h, w), bool)
        for (c0, c1) in spec['arms']: top[y0:spec['arms_below'], c0:c1] = True
        m[y0:spec['arms_below'], :] &= top[y0:spec['arms_below'], :]
        m[114:, :] = False
        b = np.array(load('middle_2')).astype(int)
        for (c0, c1) in BALCONY['brackets']:
            br = b[112:118, c0:c1]
            m[112:118, c0:c1] |= (br[..., 2] > br[..., 0] + 12) & (br[..., 3] > 200)
        _TEMPLATE['m'] = m
    return _TEMPLATE['m'].copy()

def wall_fill(fa, hole, ok, period):
    """Fills `hole` in the picture `fa` (in place) with the wall's own pixels: each from the nearest clean wall (`ok`)
    a whole number of the brickwork's periods (rows, columns) away, so the courses and their joints carry on through;
    where no clean wall is that far, a whole number of rows away in the nearest column, then the nearest at all."""
    P, Q = period
    h, w = hole.shape
    tries = []
    for (p, q, n) in ((P, Q, 7), (P, 1, 60), (1, 1, 60)):
        tries += sorted(((dy * p, dx * q) for dy in range(-n, n + 1) for dx in range(-n, n + 1) if (dy, dx) != (0, 0)),
                        key=lambda t: (abs(t[0]) + abs(t[1]) * .7, t))
    src = fa.copy()
    for y, x in zip(*np.where(hole)):
        for dy, dx in tries:
            sy, sx = y - dy, x - dx
            if 0 <= sy < h and 0 <= sx < w and ok[sy, sx]:
                fa[y, x] = src[sy, sx]; break

def balcony_wall(n, out, fa, fa0, relief, overlay, balcs, boxes):
    """For each balcony floor of a box (at front row `py`): fills the wall behind the art's flat balcony (in `fa`, in
    place) with the storey's own wall, opens the double window behind it into a glass door down to the floor, and writes
    what the engine builds the balcony from: `_balc` (its rows in the front's pixels, its colours) and its pictures, the
    parapet's face (`_balp`), the slab's edge (`_bals`) and the floor (`_balf`), all in the art's own colours and pixels.
    Returns the windows' boxes, the door's panes made as tall as the door."""
    B = BALCONY; H = fa.shape[0]
    rgb = lambda c: int(c[0]) << 16 | int(c[1]) << 8 | int(c[2])
    specs = []
    for (py, key, pa) in balcs:
        # the clean wall of this storey: opaque, not glass or near it, not the balcony, not under the storefront's sign
        # (the wall's own colours: those of a patch of plain wall under the left window, which every colour of the
        # drawing has, so no frame, sill or outline is carried into the hole)
        patch = fa0[py + 58:py + 68, 32:80, :3].reshape(-1, 3).astype(np.int64)
        pal = np.unique((patch[:, 0] << 16) | (patch[:, 1] << 8) | patch[:, 2])
        cols = (fa0[..., 0].astype(np.int64) << 16) | (fa0[..., 1].astype(np.int64) << 8) | fa0[..., 2]
        ok = np.isin(cols, pal) & (fa[..., 3] > 200) & ~relief & ~overlay
        lim = np.zeros_like(ok); lim[py + 10:py + 113, 16:208] = True; ok &= lim
        hole = relief.copy(); hole[:py, :] = False; hole[py + MID:, :] = False
        # brick courses repeat every 12 rows (two courses, the joints staggered) and 30 columns; the plain walls' grain
        # is matched the same way
        wall_fill(fa, hole, ok, (12, 30) if key == 'middle_2' else (15, 30))
        # the glass door: the window's frame and glass carried down to the floor, its sill at the floor
        dx0, dx1 = B['door_x']; g0, g1 = B['door_glass']; s0, s1 = B['door_sill']
        sill = s1 - s0; foot = py + B['floor']
        for r in range(py + g1, foot - sill):
            src = py + g0 + (r - py - g1) % (g1 - g0)
            fa[r, dx0:dx1] = fa0[src, dx0:dx1]
            gl = glass(fa0[src:src + 1, dx0:dx1])[0]
            fa[r, dx0:dx1][gl] = 0
        fa[foot - sill:foot, dx0:dx1] = fa0[py + s0:py + s1, dx0:dx1]
        boxes = [(bx, by, bw, (foot - sill) - by) if (dx0 <= bx and bx + bw <= dx1 and py <= by < py + g1) else (bx, by, bw, bh)
                 for (bx, by, bw, bh) in boxes]
        # what the engine builds: the slab, the parapet round it, the brackets under it
        x0, x1 = B['x']; w = x1 - x0
        cap = pa[B['cap_row'], 60, :3]; line = pa[B['outline_row'], 60, :3]
        if not specs:
            par = np.zeros((B['floor'] - B['top'], w, 4), np.uint8); par[..., 3] = 255
            par[..., :3] = cap
            ph = par.shape[0]
            for c in (11, 19):                                          # the stone's courses
                par[c, :, :3] = (cap * .88).astype(np.uint8)
            for k, c in enumerate((11, 19, ph)):                        # its joints, staggered course by course
                r0 = (0 if k == 0 else (11, 19)[k - 1]) + 1
                for jx in range((12 * k) % 24 + 6, w - 3, 24): par[r0:c, jx, :3] = (cap * .88).astype(np.uint8)
            par[1:3, :, :3] = np.clip(cap * 1.08, 0, 255).astype(np.uint8)   # the cap's lit edge
            par[0, :, :3] = line; par[-1, :, :3] = line; par[:, 0, :3] = line; par[:, -1, :3] = line
            Image.fromarray(par).save(f'{out}/{n}_balp.png')
            r0, r1 = B['slab_rows']
            col = pa[r0:r1, 100].astype(np.uint8)
            slab = np.repeat(col[:, None, :], w, axis=1); slab[..., 3] = 255
            slab[:, 0, :3] = line; slab[:, -1, :3] = line
            Image.fromarray(slab).save(f'{out}/{n}_bals.png')
            f0, f1 = B['floor_rows']; c0, c1 = B['floor_x']
            Image.fromarray(pa[f0:f1, c0:c1].astype(np.uint8)).save(f'{out}/{n}_balf.png')
        under = (pa[B['slab_rows'][0] + 2, 100, :3] * .8)
        bracket = pa[117, (B['brackets'][0][0] + B['brackets'][0][1]) // 2, :3]
        (a0, a1), (b0, b1) = B['brackets']
        specs.append([x0, x1, py + B['top'], py + B['floor'], py + B['foot'], B['depth'], B['thick'], rgb(cap), rgb(under),
                      rgb(bracket), a0, a1, b0, b1, B['bdown'], B['bout']])
    WINDOWS[n + '_balc'] = specs
    return boxes

def build(shop, out):
    # the cinema has no LimeZu sprite: the engine builds it (its era's front, posters and marquee from street.json's
    # graph.cinema), so nothing is cut for it here
    if shop['parts'][0]['key'].startswith('landmark_cinema_'): return
    # nor do the stores (src/town/stores.ts): the engine builds each era's store, its car park and yards (graph.stores)
    if shop['parts'][0]['key'].startswith('landmark_store_'): return
    if shop['parts'][0]['key'].startswith(('house_small_', 'house_medium_')): return build_house(shop, out)
    if shop['parts'][0]['key'].startswith(('villa_', 'landmark_clocktower_', 'landmark_school_', 'house_terraced_')) or \
            shop['parts'][0]['key'].startswith(('estate_', 'landmark_police_')) or apt_maker(shop['parts'][0]['key']): return build_model(shop, out)
    if shop['parts'][0]['key'].startswith(('landmark_fire_', 'landmark_hospital_', 'landmark_post_')): return build_model(shop, out)
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
    relief = np.zeros((H, 224), bool); depth = 0; balcs = []
    for m in mids:
        im = load(m['key']); y -= MID
        if m['key'] in RELIEF:
            balcs.append((y, m['key'], np.array(im).astype(int)))
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
    fa0 = np.array(front)
    fa = fa0.copy(); fa[cut] = (0, 0, 0, 0)
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
        # the wall behind it: the wall's own brickwork carried on from the clean wall of the same storey, the double
        # window made a glass door down to the floor; the balcony itself is built as geometry (`_balc`)
        boxes = balcony_wall(shop['sprite'], out, fa, fa0, relief, overlay, balcs, boxes)
    front = Image.fromarray(fa)
    WINDOWS[shop['sprite']] = boxes
    # the stone ledges the art draws flat across the front: each middle floor's sill course at its foot (rows 118..128:
    # its outline, the light stone, its shade) and the top floor's cornice under the roof (rows 0..8). The Filament
    # engines build them as real ledges standing out of the wall (BoxShell.ledge), wearing these rows of the front; not
    # where the storefront's sign hangs over them (it stays on the wall)
    ledges = []
    for i in range(len(mids)):
        y0 = H - body_h - MID * (i + 1)
        bands = [(y0 + 118, y0 + 128, LEDGE_OUT)] + ([(y0, y0 + 8, LEDGE_OUT)] if i == len(mids) - 1 else [])
        for (r0, r1, o) in bands:
            free = ~overlay[r0:r1].any(axis=0) & (fa[r0:r1, :, 3] > 200).all(axis=0)
            x = 0
            while x < 224:
                if not free[x]: x += 1; continue
                e = x
                while e < 224 and free[e]: e += 1
                if e - x >= 8: ledges.append([x, e, r0, r1, o])
                x = e
    # the steps up to the door the classic offices' art paints flat at its foot: two stone steps standing out of the
    # door's recess, each wearing its own rows of the art (the tread's light row along its top)
    for pre, steps in DOOR_STEPS.items():
        if ground['key'].startswith(pre):
            for (x0, x1, r0, r1, o) in steps: ledges.append([x0, x1, H - body_h + r0, H - body_h + r1, o])
    if ledges: WINDOWS[shop['sprite'] + '_ledge'] = ledges
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
    # a pitched roof over the box (our roof_2/4/6: a ridge along the street; roof_8/9/11: the same between two stone
    # end walls). The art draws it from above: the far slope, the ridge cap, the near slope down to the eave. The street's
    # nearly level view sees the near slope as a band rising from the front's top edge to the ridge, so its rows are
    # taken every other row (the art is 2x pixel art) from the ridge cap down, one screen row each in the engine; the end
    # walls are each a column of the art's own stone, taken the same way, with three rows of their cap over the ridge
    pitch = PITCHED.get(roof['key']) if roof else None
    if pitch is not None:
        ra = np.array(load(roof['key']))
        r0, posts = pitch
        slope = ra[r0:r0 + 132:2].copy()
        if posts:
            slope[:, :posts] = 0; slope[:, 224 - posts:] = 0
            rows = [8, 10, 12] + list(range(r0, r0 + 132, 2))
            Image.fromarray(np.ascontiguousarray(ra[rows, :posts])).save(f'{out}/{n}_postl.png')
            Image.fromarray(np.ascontiguousarray(ra[rows, 224 - posts:])).save(f'{out}/{n}_postr.png')
            # the Filament engines build them as real end walls following the roof's slope (BoxShell.endWalls): their
            # coping wears the art's light capstone with its border (rows 8..72), their end piers the art's pier, its
            # lighter band (a capstone at the pier's head) and the panel under it (rows 152..224)
            Image.fromarray(np.ascontiguousarray(ra[8:72, :posts])).save(f'{out}/{n}_wcap.png')
            Image.fromarray(np.ascontiguousarray(ra[152:224, :posts])).save(f'{out}/{n}_pier.png')
        Image.fromarray(slope).save(f'{out}/{n}_pitch.png')
        # the gable continues the side wall under it: its upper storey's wall colour (not its glass or frames)
        sa = np.array(side); top = sa[4:min(sa.shape[0], 100)]
        wallpx = top[(top[..., 3] > 200) & ~dilate(glass(top), 3)]
        rake = ra[r0 + 130, 112]
        gable = np.median(wallpx, axis=0) if len(wallpx) > 20 else np.median(fa[6:20, 8:216].reshape(-1, 4), axis=0)
        WINDOWS[n + '_pitch'] = [[rgb_int(rake), rgb_int(gable), posts]]
        # the gable wall: the side wall's own top storey carried up into the triangle (its boards, bands and windows, as
        # an attic's), as tall as the slope's band, so the end of the house reads as one wall with the side under it
        gp = np.array(side)[8:8 + len(range(r0, r0 + 132, 2))]
        Image.fromarray(np.ascontiguousarray(gp)).save(f'{out}/{n}_gablep.png')

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
    """Green or semi-transparent pixels: the ground strip's grass in the art and its shadow (see ground())."""
    r, g, b, al = (a[..., i].astype(int) for i in range(4))
    return ((g > r + 25) & (g > b + 10)) | (al < 250)

def ground(a, base):
    """The art's ground (its grass and its shadow), not the house: green or semi-transparent in the grey base design and
    the same pixel in this colour variant. Colour alone is not enough: the green variants (c2, c7) paint the hitch and
    the wheels green, and the ground is the one thing every variant draws alike."""
    return grassy(base) & ((a == base).all(-1) | (a[..., 3] < 250))

def siding_rows(wall, rows):
    """Each of the first `rows` rows of the wall's siding colour: the median over the columns that are plain siding all
    the way down, so the door, the window frames and the blinds do not colour the end walls, which have none of them.
    Plain siding is the column most repeated near the wall's two ends (no design has a window or a door within 12
    pixels of a corner; a blind repeats its column too, so the whole wall cannot be asked), and every column that
    matches it in nearly every row."""
    w = wall[:rows, :, :3]
    cols = [w[:, c].tobytes() for c in range(w.shape[1])]
    ends = cols[:12] + cols[-12:]
    mode = np.frombuffer(max(set(ends), key=lambda c: (ends.count(c), c)), np.uint8).reshape(-1, 3)
    plain = [c for c in range(w.shape[1]) if (w[:, c] == mode).all(-1).mean() >= .85]
    return np.median(w[:, plain], axis=1).astype(np.uint8) if plain else mode

def rgb_int(c):
    return int(c[0]) << 16 | int(c[1]) << 8 | int(c[2])

def shade(c, f):
    return tuple(int(max(0, min(255, round(v * f)))) for v in c[:3])

def yard(rng, width, deep, grass, path, style, gaps, ground='earth'):
    """A lot's front yard, `width` pixels along the street and `deep` back from it: its ground (one row per screen row,
    the front first at the bottom), a path (x0, x1, how far back it reaches) to the gate, and its edge along the street
    as the game fences it (src/town/layout.ts): a picket fence, a hedge for a formal yard, nothing for a natural one,
    open across `gaps`. Also where snow lies on that fence or hedge. Draws from `rng` in a fixed order, so a yard is
    the same every run."""
    lrows = max(4, int(round(RISE_PER_120 * deep / 120)))
    half = width // 2
    if grass:
        greens = np.array([(45, 114, 86), (55, 133, 78), (71, 151, 87), (50, 124, 82)])
        pick = rng.integers(0, len(greens), (lrows, half)); pick = np.repeat(pick, 2, axis=1)
        lawn = greens[pick]
    elif ground == 'paving':
        stone = np.array([(150, 140, 128), (140, 131, 120), (158, 148, 134), (146, 136, 124)])
        pick = rng.integers(0, len(stone), (lrows, half)); pick = np.repeat(pick, 2, axis=1)
        lawn = stone[pick]
    else:
        earth = np.array([(122, 104, 84), (110, 94, 76), (131, 113, 90), (116, 99, 80)])
        pick = rng.integers(0, len(earth), (lrows, half)); pick = np.repeat(pick, 2, axis=1)
        lawn = earth[pick]
    lawn = np.concatenate([lawn, np.full((lrows, width, 1), 255)], axis=2).astype(np.uint8)
    path_c = np.array([(168, 142, 108), (156, 130, 98), (176, 152, 118)])
    px0, px1, reach = path
    for t in range(lrows):
        d = (lrows - t - .5) * deep / lrows                         # this row's depth from the street
        if d <= reach:
            for x in range(px0, px1, 2):
                lawn[t, x:x + 2, :3] = path_c[rng.integers(0, 3)]
    lawn[-1, :, :3] = shade(lawn[-1, 0], .8)                       # the kerb of the lot along the pavement

    fence = None
    if style != 'natural':
        fence = np.zeros((32, width, 4), np.uint8)
        def open_at(x0, x1): return any(x0 < g1 and x1 > g0 for (g0, g1) in gaps)
        if style == 'formal':
            bushes = [np.array(load(f'hedge_{i}')) for i in range(1, 6)]
            x = -4
            while x < width:
                b = bushes[int(rng.integers(0, 5))]
                if not open_at(x + 6, x + 26):
                    tile = Image.fromarray(fence); tile.alpha_composite(Image.fromarray(b), (int(x), 0)) if x >= 0 else tile.alpha_composite(Image.fromarray(b[:, -x:]), (0, 0))
                    fence = np.array(tile)
                x += 18 + int(rng.integers(0, 4))
        else:
            # the art's two fence pieces (each drawn with an open end) laid end to end, alternating as the game does
            pieces = [np.array(load('fence_9'))[:, 0:28], np.array(load('fence_10'))[:, 6:32]]
            tx, i = 0, 0
            while tx < width:
                tile = pieces[i % 2]
                for c in range(min(tile.shape[1], width - tx)):
                    if not open_at(tx + c, tx + c + 1): fence[:, tx + c] = tile[:, c]
                tx += tile.shape[1]; i += 1
        if not (fence[..., 3] > 0).any(): fence = None
    # the snow on it: the first pixels down from each of its top surfaces (a picket's point, a rail, a bush's crown),
    # two of the art's pixels deep on a hedge and one on a fence, ragged pair by pair so it lies, not paints
    fence_snow = None
    if fence is not None:
        solid = fence[..., 3] > 128
        above = np.zeros_like(solid); above[1:] = solid[:-1]
        deep_by = 2 + 2 * rng.integers(0, 2, fence.shape[1] // 2 + 1) + (2 if style == 'formal' else 0)
        fence_snow = np.zeros_like(fence)
        for (y, x) in zip(*np.nonzero(solid & ~above)):
            for t in range(int(deep_by[x // 2])):
                if y + t >= fence.shape[0] or not solid[y + t, x]: break
                fence_snow[y + t, x] = (255, 255, 255, 255)
    return lawn, fence, fence_snow

def build_house(shop, out):
    """One of our mobile homes as the pictures and the shape (houses.json) the engine's pitched-roof house is built from."""
    key = shop['parts'][0]['key']; n = shop['sprite']
    tier, design, colour = house_key(key)
    spec = HOUSE[tier]
    porch = design >= 5
    art = np.array(load(key))
    # a porch design is its steps twin with a porch in front: the wall behind it comes from the twin, pixel for pixel
    twin = np.array(load(key.replace(f'_{design}', f'_{design - 4}', 1))) if porch else art
    # the grey base design of each (the colour variants repaint only the house): what tells the ground from the house
    base = np.array(load(f'house_{tier}_{design}'))
    twin_base = np.array(load(f'house_{tier}_{design - 4}')) if porch else base
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
        w = sub[:, a0:a1]; keep = ~ground(w, twin_base[174:186, rx0:rx1][:, a0:a1])
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
    rows = np.zeros((FRONT_ROWS, 4), np.uint8); rows[:, 3] = 255
    # the siding down to the floor's edge (inset from the corner boards); under the floor, the dark space as it is
    rows[:74, :3] = siding_rows(wall[:, 2:-2], 74)
    for r in range(74, FRONT_ROWS):
        px = wall[r][wall[r, :, 3] > 200]
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
    hitch[ground(hitch, base[hy0:hy1, hx0:hx1])] = 0
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
        rail = art[224:236, 50:108].copy(); fascia_p = art[236:242, 52:108]
        rail_c = np.median(art[160:220, 52:54].reshape(-1, 4), axis=0)            # the side rail's light face
        # between the balusters the art shows the deck's boards (seen from above): open there, so the deck and the
        # door behind show through the railing as they would
        boards_px = {tuple(p) for p in boards.reshape(-1, 4)[:, :3]}
        rail_px = {tuple(p) for p in rail[:4].reshape(-1, 4)[:, :3]}
        for (y, x) in np.ndindex(6, rail.shape[1]):
            c = tuple(rail[6 + y, x, :3])
            if c in boards_px and c not in rail_px: rail[6 + y, x] = 0
        atlas[0:12, 0:58] = rail; atlas[12:18, 0:56] = fascia_p
        atlas[18:24, 0:48] = deck_row[None, :, :]
        steps = art[186:236, 108:128]
        tread_c = np.median(steps[steps[..., 3] > 200].reshape(-1, 4)[:, :3], axis=0)
        lum_s = steps[..., :3].astype(int).sum(-1)
        tread_c = np.median(steps[(steps[..., 3] > 200) & (lum_s >= np.percentile(lum_s[steps[..., 3] > 200], 60))][:, :3], axis=0)
        riser_c = shade(tread_c, .72)
        # the dark space under the deck (so it reads as a platform, not a bench on legs), the legs under the deck's
        # front, the deck (its top the boards, its front the art's six rows of fascia, one pixel each), the side rails,
        # the steps down to the right, the front railing standing on the deck's edge
        under = shade(void, .8)
        box((54, 106), (0, 8), (0, 62), under, under, under)
        box((52, 56), (0, 8), (58, 64), dark, dark, dark)
        box((100, 106), (0, 8), (58, 64), dark, dark, dark)
        box((52, 108), (8, 14), (0, 64), deck_c, deck_c, shade(deck_c, .7), ft=[0, 12, 56, 6], tt=[0, 18, 48, 6])
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

    # --- the lawn (grass in the art's own greens, or the bare earth the shadow designs stand on) and the dirt path from
    # the steps to the street, and the yard's edge along the street, open at the gate and across the porch
    front_x = (224 - (rx1 - rx0)) // 2
    setback = int(shop.get('yard', {}).get('front', 2)) * 32
    setback = int(np.ceil(setback * PORCH_GAIN))
    grass = design in (1, 2, 5, 6)
    if porch: px0, px1, reach = front_x + 126, front_x + 146, 36 * PORCH_GAIN
    else: px0, px1, reach = front_x + 64, front_x + 96, setback - 16 * PORCH_GAIN
    style = shop.get('yard', {}).get('style', 'compact')
    gaps = [(px0 - 6, px1 + 6)] + ([(front_x + 50, front_x + 128)] if porch else [])
    lawn, fence, fence_snow = yard(rng, 224, setback + D, grass, (px0, px1, reach), style, gaps)

    for name, img in (('front', f), ('emis', em), ('side', side), ('slope', slope), ('ridge', ridge), ('back', back),
                      ('hitch', hitch), ('lawn', lawn)):
        Image.fromarray(img).save(f'{out}/{n}_{name}.png')
    if porch: Image.fromarray(atlas).save(f'{out}/{n}_porch.png')
    if fence is not None:
        Image.fromarray(fence).save(f'{out}/{n}_fence.png'); Image.fromarray(fence_snow).save(f'{out}/{n}_fencesnow.png')
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

# ------------------------------------------------------------------------------------------------ modelled buildings
# Our villas (villa_1..5) and two landmarks (landmark_clocktower_1/2, landmark_school_1) are drawn like the mobile homes,
# in a 3/4 view from above, but each is more than one volume, and their roofs are not the houses' ridge along the
# street: a villa has a FRONT-FACING gable (the triangle end faces the street, its slopes run back from it), a terrace
# roof over its ground floor with a balcony and a small gable over the door, and a raised porch deck with railings and
# steps; the clock tower and the school have HIPPED roofs (four sloped faces, a flat top), the school a cross gable over
# its middle bay. The engine builds each from a list of parts, back to front (models.json): boxes (each face plain or
# wearing a piece of the art, the front's windows listed), front gables (two slopes laid on their quads, a face) and
# hipped roofs (the front face one screen row per row of the art's own pixels, the side faces laid on their quads). All
# the art one building wears is packed in one picture, bN_atlas.png, and where it glows at night in bN_glow.png. The
# places below are the art's own pixels (every variant of a sprite shares them: the villas differ only in their roofs'
# colour, the clock towers only in the time on the clock).
MODELS = {}
# The pitched roofs a shop or condo stack can wear: where the ridge cap starts in the art (the near slope runs 132 rows
# from there to the eave) and how wide its stone end walls are (0: none)
PITCHED = {'roof_2': (60, 0), 'roof_4': (60, 0), 'roof_6': (60, 0), 'roof_8': (92, 32), 'roof_9': (92, 32), 'roof_11': (92, 32)}
GLOW = (255, 205, 120, 235)

class Atlas:
    """The pieces of art one modelled building wears, packed in shelves into one picture in the order they are added,
    and where each glows at night in a second picture laid out the same."""
    def __init__(self, width):
        self.w, self.x, self.y, self.row, self.items = width, 0, 0, 0, []
    def add(self, img, glow=None):
        img = np.ascontiguousarray(img); h, w = img.shape[:2]
        if self.x + w > self.w: self.x, self.y, self.row = 0, self.y + self.row + 1, 0
        r = [self.x, self.y, w, h]
        self.items.append((r, img, glow))
        self.x += w + 1; self.row = max(self.row, h)
        return r
    def glows(self, r):
        """Whether the piece at `r` glows anywhere at night."""
        return any(glow is not None and (glow[..., 3] > 0).any() for (q, _, glow) in self.items if q == r)
    def save(self, out, n):
        a = np.zeros((self.y + self.row, self.w, 4), np.uint8); g = np.zeros_like(a)
        for (x, y, w, h), img, glow in self.items:
            a[y:y + h, x:x + w] = img
            if glow is not None: g[y:y + h, x:x + w] = glow
        Image.fromarray(a).save(f'{out}/{n}_atlas.png'); Image.fromarray(g).save(f'{out}/{n}_glow.png')

def solid(a):
    """The art without the half-transparent shadow it casts to the right and below (the engine casts its own)."""
    a = a.copy(); a[a[..., 3] < 255] = 0
    return a

def panes_of(img, least=30, dial=False):
    """A front's window panes (x, y, w, h in its pixels, and 1 for a square pane, 0 for a round one, which mirrors no
    square of sky): its clean blue glass, as the shops' windows are found; and where it glows at night: every shade of
    its glass inside the pane (its darker reflections too, which left dark wedges in a lit pane; not its frame's ink, its
    mullions or the wood round it, which made a ragged edge), a little brighter where the art's glass is lighter, so a
    lit pane keeps the art's own shading rather than a flat fill. With `dial`, a round pane is a clock's dial and glows
    over its whole face (below)."""
    gm = glass(img); out = []; glow = np.zeros_like(img); near = dilate(gm, 1)
    rgb = img[..., :3].astype(int)
    lum = rgb.sum(-1)
    # the glass's shades: blue, and greener than red (the frames' and mullions' blue-greys are not: their green is their red)
    shade = (rgb[..., 2] > rgb[..., 0] + 15) & (rgb[..., 1] >= rgb[..., 0] + 10) & (rgb[..., 2] >= rgb[..., 1]) & (lum > 240) & (img[..., 3] > 200)
    for (x0, y0, x1, y1) in components(near, least):
        sub = gm[y0:y1 + 1, x0:x1 + 1]; ys, xs = np.where(sub)
        if len(xs) < least: continue
        bx0, by0 = x0 + int(xs.min()), y0 + int(ys.min()); w, h = int(xs.max() - xs.min() + 1), int(ys.max() - ys.min() + 1)
        if w < 4 or h < 4: continue
        # a piece of a pane already found (a dial's glass falls apart round its hands)
        if any(px <= bx0 and py <= by0 and bx0 + w <= px + pw and by0 + h <= py + ph for (px, py, pw, ph, _) in out): continue
        square = near[by0:by0 + h, bx0:bx0 + w].mean() >= .85
        out.append([bx0, by0, w, h, 1 if square else 0])
        if not square and dial:
            # a round pane (a clock's dial) glows over its whole face: everything inside its ellipse that is the face's
            # cool colour (its glass, its paint, the bezel's shading), not its ink, hands and marks, nor the warm wall
            # the art shades below it. Row spans from the first glass to the last leave hard-edged notches where a
            # row's face runs on past its glass.
            ys, xs = np.mgrid[0:img.shape[0], 0:img.shape[1]]
            cx, cy, rx, ry = bx0 + (w - 1) / 2, by0 + (h - 1) / 2, w / 2 + 1.5, h / 2 + 1.5
            inside = ((xs - cx) / rx) ** 2 + ((ys - cy) / ry) ** 2 <= 1
            face = (rgb[..., 2] >= rgb[..., 0]) & (lum > 300)
            glow[inside & face] = GLOW
            continue
        # the pane: its clean glass and every shade of glass joined to it (4 neighbours), so the light fills it up to its
        # frame and its mullions (a round window's darker shades reach well past its clean glass); the pane's box grows
        # to it (the light is drawn in it). Should the glass's colours run on out of the window (to the box searched's
        # edge), only the glass's own box and a pixel round it.
        def flood(r):
            m = np.zeros(img.shape[:2], bool)
            ya, yb, xa, xb = max(0, by0 - r), min(img.shape[0], by0 + h + r), max(0, bx0 - r), min(img.shape[1], bx0 + w + r)
            st = [(y + by0, x + bx0) for y, x in zip(*np.nonzero(gm[by0:by0 + h, bx0:bx0 + w] & shade[by0:by0 + h, bx0:bx0 + w]))]
            for y, x in st: m[y, x] = True
            while st:
                y, x = st.pop()
                for ny, nx in ((y + 1, x), (y - 1, x), (y, x + 1), (y, x - 1)):
                    if ya <= ny < yb and xa <= nx < xb and shade[ny, nx] and not m[ny, nx]:
                        m[ny, nx] = True; st.append((ny, nx))
            edge = m[ya, xa:xb].any() or m[yb - 1, xa:xb].any() or m[ya:yb, xa].any() or m[ya:yb, xb - 1].any()
            return m, edge and (ya > 0 or yb < img.shape[0] or xa > 0 or xb < img.shape[1])
        m, leaked = flood(24)
        if leaked:
            m = np.zeros(img.shape[:2], bool); box = (slice(max(0, by0 - 1), by0 + h + 1), slice(max(0, bx0 - 1), bx0 + w + 1))
            m[box] = shade[box]
        if not m.any(): continue
        ys, xs = np.nonzero(m)
        out[-1][:4] = [int(xs.min()), int(ys.min()), int(xs.max() - xs.min() + 1), int(ys.max() - ys.min() + 1)]
        lo, hi = lum[m].min(), lum[m].max()
        f = .8 + .2 * (lum[m] - lo) / max(1, hi - lo)
        glow[m] = np.stack([np.round(GLOW[0] * f), np.round(GLOW[1] * f), np.round(GLOW[2] * f), np.full(f.shape, GLOW[3])], -1).astype(np.uint8)
    glow[img[..., 3] == 0] = 0
    return out, glow

def clock_of(img, pane, reach=27):
    """A clock's dial (the round `pane` of `img`) without its painted hands, which would show the art's one time of day
    all day: the engine draws them at the hour. The hands are what is not the face's cool colour and joins the hub (the
    gold cell nearest the dial's middle; the marks round the rim stand apart), each pixel painted with the face's pixel
    opposite it across the hub (the face's rays are symmetric; where that is not the face, a hand or a mark, the
    face's median colour).
    Returns the dial without them, the hub's cell [x, y, size] (the art's pixels are doubled: a cell is its pixel), and
    the hands' colours: the ink down their middle, the brown either side, the gold at their tips and the hub."""
    bx0, by0, w, h, _ = pane
    cx, cy = bx0 + (w - 1) / 2, by0 + (h - 1) / 2
    rgb = img[..., :3].astype(int)
    ys, xs = np.mgrid[0:img.shape[0], 0:img.shape[1]]
    gold = (rgb[..., 0] > 150) & (rgb[..., 1] > 140) & (rgb[..., 2] < 130) & (np.hypot(xs - cx, ys - cy) < 8)
    gy, gx = np.where(gold)
    if not len(gx): return img, None, None
    hx, hy = int(gx.min()), int(gy.min()); cell = int(gx.max() - gx.min() + 1)
    ox, oy = hx + cell / 2, hy + cell / 2
    near = np.hypot(xs + .5 - ox, ys + .5 - oy) < reach
    face = (rgb[..., 2] >= rgb[..., 0]) & (rgb.sum(-1) > 300)
    ink, brown, tip = img[hy - cell, hx - cell].copy(), img[hy, hx - cell].copy(), img[hy, hx].copy()
    # the hands: flood from the hub through what is not the face
    hands = np.zeros(face.shape, bool); st = [(hy, hx)]; hands[hy, hx] = True
    while st:
        y, x = st.pop()
        for ny, nx in ((y + 1, x), (y - 1, x), (y, x + 1), (y, x - 1)):
            if near[ny, nx] and not face[ny, nx] and not hands[ny, nx]:
                hands[ny, nx] = True; st.append((ny, nx))
    med = np.median(img[face & near], axis=0).astype(np.uint8)
    out = img.copy()
    for y, x in zip(*np.where(hands)):
        x2, y2 = int(2 * ox - x - 1), int(2 * oy - y - 1)
        out[y, x] = img[y2, x2] if face[y2, x2] else med
    return out, [hx, hy, cell], (ink, brown, tip)

def fill_rows(img, mask):
    """What `mask` covers, painted with its own row's colour (the median of the rest of that row): a plank or a course
    of tiles carried across where something else stood on it in the art."""
    img = img.copy()
    for y in range(img.shape[0]):
        m = mask[y]
        if not m.any(): continue
        rest = img[y][~m & (img[y, :, 3] > 200)]
        if len(rest): img[y][m] = np.median(rest, axis=0).astype(np.uint8)
    return img

def fill_period(img, mask, period):
    """What `mask` covers, painted with the same row `period` pixels to its left (again and again until clear of the
    mask): a roof's tiles, whose pattern repeats along a row, carried across where something stood on them."""
    img = img.copy()
    for (y, x) in zip(*np.nonzero(mask)):
        sx = x - period
        while sx >= 0 and mask[y, sx]: sx -= period
        if sx >= 0: img[y, x] = img[y, sx]
    return img

def hip_side(face, w, h, quad, per_row, window, eave_left):
    """A hipped roof's side face, `w` columns up the slope (eave to top) by `h` rows along its depth (the back eave in
    row 0, the front one in row h - 1), each row `per_row` of depth, cut to `quad`: tiled with the front face's own
    tiles. The art draws its side faces in the front's way, courses across the picture, which on a face whose courses
    run along the street's depth sampled into hatching (squeezed a few times) or, averaged, into streaks. Here every
    course runs along the depth at the height of the front face's course (the two meet at the hip), each column the
    front face's row at its height, the tiles along it the front's along its row (from `window`, columns of the face
    whose rows are whole, as wide as a whole number of tiles), one tile of the front per as much depth."""
    rows = face.shape[0]
    x0, x1 = window
    u = np.arange(w) if eave_left else (w - 1 - np.arange(w))           # columns from the eave
    rr = np.clip(rows - 1 - (u * rows) // w, 0, rows - 1)
    depth = ((h - 1 - np.arange(h)) * per_row).astype(int)
    xs = x0 + depth % (x1 - x0)
    out = face[rr[None, :], xs[:, None]].copy()
    out[..., 3] = 255
    return cut(out, polygon((h, w), quad))

def along_mean(img):
    """`img` with each column one colour, the mean of its opaque pixels (its alpha kept): a hipped roof's side face
    seen so nearly edge on that its tiles' joints along the depth fall closer than a pixel apart, where sampling them
    would scatter them into hatching. Each course is then the stripe the eye sees there."""
    m = img[..., 3] > 0
    out = img.copy()
    for x in range(img.shape[1]):
        if m[:, x].any(): out[m[:, x], x, :3] = np.round(img[m[:, x], x, :3].astype(float).mean(0)).astype(np.uint8)
    return out

def polygon(shape, pts):
    """A mask of the polygon `pts` (x, y in pixels, the pixels' corners), edges included."""
    m = Image.new('L', (shape[1], shape[0]), 0)
    from PIL import ImageDraw
    ImageDraw.Draw(m).polygon([tuple(p) for p in pts], fill=255, outline=255)
    return np.array(m) > 0

def cut(img, keep):
    img = img.copy(); img[~keep] = 0
    return img

def column_rows(img, cols):
    """Each row's colour over `cols` (the plain wall), the median: a side wall's courses taken from the front's."""
    return np.median(img[:, cols].astype(int), axis=1).astype(np.uint8)

def side_wall(rows, depth, window, wy, at, rng, corner):
    """A side wall `depth` pixels deep: each row the front's own colour for that row, a window (the front's own, frame
    and all) at each fraction of the depth in `at` with its top at row `wy`, darker corner boards and a fine grain.
    Returns it and where its windows glow."""
    h = len(rows)
    side = np.repeat(rows[:, None, :], depth, axis=1).copy(); side[..., 3] = 255
    glow = np.zeros_like(side)
    if window is not None:
        wh, ww = window.shape[:2]; m = window[..., 3] > 200; gm = dilate(glass(window), 1) & m
        for f in at:
            cx = int(depth * f) - ww // 2
            side[wy:wy + wh, cx:cx + ww][m] = window[m]
            glow[wy:wy + wh, cx:cx + ww][gm] = GLOW
    for c in (0, 1, depth - 2, depth - 1): side[:, c, :3] = corner[:3]
    grain = rng.integers(-4, 5, (h // 2 + 1, depth // 2 + 1))
    grain = np.kron(grain, np.ones((2, 2), np.int32))[:h, :depth]
    side[..., :3] = np.clip(side[..., :3].astype(int) + grain[..., None], 0, 255).astype(np.uint8)
    return side, glow

def open_balusters(pic, r0, r1, dark=(0x3a, 0x3a, 0x50)):
    """A railing's picture open between its balusters (rows r0..r1): the art shows what is behind them as its dark
    ink; a dark pixel with dark on both sides is a gap, one beside a baluster its outline."""
    pic = pic.copy(); d = (pic[..., :3] == dark).all(-1)
    for y in range(r0, r1):
        for x in range(1, pic.shape[1] - 1):
            if d[y, x] and d[y, x - 1] and d[y, x + 1]: pic[y, x] = 0
    return pic

def fl(v): return [round(float(a), 3) for a in v]

def mbox(x, y, d, c, ft=None, fx=None, tt=None, st=None, card=False, panes=None, top=True, outline=False, clock=None):
    """A box of the model: x across the front picture, y up from the ground, d back from the front line (sprite pixels);
    its top, front and side colours; the pieces of the atlas its front, top and side wear; its front's windows; the hands
    of the clock its front and sides wear (`clock`: the hub's cell in the front piece, the hands' lengths in cells, their
    colours)."""
    p = dict(t='box', x=fl(x), y=fl(y), d=fl(d), c=[rgb_int(c[0]) if top else -1, rgb_int(c[1]), rgb_int(c[2])])
    if clock is not None: p['clock'] = clock
    for k, v in (('ft', ft), ('tt', tt), ('st', st)):
        if v is not None: p[k] = v
    if fx is not None: p['fx'] = float(fx)
    if card: p['card'] = True
    if panes: p['panes'] = panes
    if outline: p['out'] = True
    return p

def mrail(a, b, h, bar, glass=None, alpha=0, posts=0, w=2):
    """A railing (as buildings_estB.mrail): its handrail `h` above the line from a to b (each x, y, d), `w` thick, in
    `bar`'s colour, a glass panel under it (`glass` at `alpha`) or none, posts every `posts` pixels (0: the ends only)."""
    return dict(t='rail', x=fl((a[0], b[0])), y=fl((a[1], b[1])), d=fl((a[2], b[2])), h=float(h), w=float(w),
                c=[rgb_int(bar), rgb_int(glass) if glass is not None else -1], ga=int(alpha), posts=float(posts))


def mgable(x, y, d, left, lp, right, rp, rake, roofc, de=None, face=None, fx=None, fy=None, panes=None, lip=None, ax=None):
    """A front-facing gable: eaves x[0] and x[1], eave and ridge heights y, from its face d[0] back to d[1] (the eaves
    back to de: where they meet another roof, if they do). Each slope wears an atlas piece laid on its quad by four of
    its points: the front eave, the apex, the ridge's back end, the back eave (lp, rp). `lip` is the colour of the
    roof's edge in the art, just above the face's rake: the engine fills the face's stepped rake up to the slope's
    straight edge with it. A single-pitch (lean-to) roof has its apex `ax` at one side and only the left slope."""
    p = dict(t='gable', x=fl(x), y=fl(y), d=fl(d), l=left, lp=fl(lp), rake=[rgb_int(rake[0]), rgb_int(rake[1])], roof=rgb_int(roofc))
    if right is not None: p.update(r=right, rp=fl(rp))
    if ax is not None: p['ax'] = float(ax)                          # a single-pitch roof: its apex (high edge) at one side
    if lip is not None: p['lip'] = rgb_int(lip)
    if de is not None: p['de'] = float(de)
    if face is not None: p.update(face=face, fx=float(fx), fy=float(fy))
    if panes: p['panes'] = panes
    return p

def mhip(x, y, d, top, front, left, lp, right, rp, roofc, flat=None, hip=None, flush=None):
    """A hipped roof: eaves x and d, eave and ridge heights y, its flat top (or ridge) x top[0..1] by d top[2..3]; the
    front face's piece (one screen row per row, ridge to eave), each side face's piece and its four points (the front
    eave, the back eave, the top's back and front ends), the flat top's piece; the hips' colours as the art draws them
    (their crease, and the cap tiles along them if the art draws a lighter row there); `flush` the side faces' pieces
    with each course one colour along its depth, for a view that sees them nearly edge on."""
    p = dict(t='hip', x=fl(x), y=fl(y), d=fl(d), top=fl(top), front=front, l=left, lp=fl(lp), r=right, rp=fl(rp), roof=rgb_int(roofc))
    if flat is not None: p['flat'] = flat
    if hip is not None: p['hip'] = [rgb_int(c) for c in hip]
    if flush is not None: p['la'], p['ra'] = flush
    return p

def rise_for(rows, run):
    """The rise for which a face `run` deep shows exactly `rows` screen rows (one per row of its picture)."""
    return rows - RISE_PER_120 * run / 120.0

def villa(shop, rng):
    key = shop['parts'][0]['key']
    art = solid(np.array(load(key)))
    # the roofs are the pixels the five villas colour differently
    vs = [np.array(load(f'villa_{i}')) for i in range(1, 6)]
    roofm = np.zeros(art.shape[:2], bool)
    for o in vs[1:]: roofm |= (o != vs[0]).any(-1)
    W = 256; at = Atlas(512)
    wall_c = art[320, 40]; rake_c = art[198, 6]; edge_c = art[202, 6]; roof_c = art[150, 40]
    lip_c = art[192, 6]                                                 # the roof's dark edge row above the rake's ink
    # --- the main roof: where its slopes end and the gable's face begins, column by column (the ridge board, cols
    # 122..133, is not roof-coloured: the face starts under its end)
    top_cut = np.zeros(W, int)
    for x in range(W):
        c = np.where(roofm[:205, x])[0]
        top_cut[x] = c.max() + 1 if len(c) > 4 else (132 if 100 < x < 156 else 196)
    keep = np.zeros(art.shape[:2], bool)
    for x in range(W): keep[:top_cut[x], x] = art[:top_cut[x], x, 3] > 0
    left = at.add(cut(art[0:198, 0:128], keep[0:198, 0:128]))
    right = at.add(cut(art[0:198, 128:256], keep[0:198, 128:256]))
    # the upper floor's front and the gable over it: a true front elevation, used as drawn (the round window, the rake
    # boards, the balcony's glass door)
    up = art[130:236, 0:W].copy()
    for x in range(W): up[:max(0, top_cut[x] - 130), x] = 0
    # what stands in front of its foot in the art (the small gable's ridge, the balcony's side rails) is built new: the
    # siding of a clear column beside each carried across behind it
    up[86:106, 72:88] = up[86:106, 68:69]; up[86:106, 172:188] = up[86:106, 166:167]; up[86:106, 228:243] = up[86:106, 244:245]
    up_panes, up_glow = panes_of(up)
    up_r = at.add(up, up_glow)
    # --- the ground floor's wall, its porch posts taken off (they are boxes standing in front of it) and the siding
    # carried across behind them
    gf = art[302:342, 4:252].copy()                                     # its wall is cols 4..251 (the storey above, 2..253)
    gf[:, 16:28] = gf[:, 36:37]; gf[:, 220:232] = gf[:, 206:207]
    gf_panes, gf_glow = panes_of(gf)
    gf_r = at.add(gf, gf_glow)
    # --- the side walls (the eave walls): each row the front's own siding, a window of the front's
    win = art[196:230, 32:64]
    corner = art[214, 4]
    gfs, gfs_glow = side_wall(column_rows(art[302:342], list(range(36, 58)) + list(range(192, 222))), 167, win, 2, (.3, .7), rng, corner)
    ups, ups_glow = side_wall(column_rows(art[196:236], list(range(64, 96)) + list(range(160, 186))), 131, win, 0, (.5,), rng, corner)
    gfs_r = at.add(gfs, gfs_glow); ups_r = at.add(ups, ups_glow)
    # --- the terrace roof over the ground floor: its fascia, and its boards (the small gable and the balcony that
    # stand on it in the art painted out: the engine builds them)
    fascia = art[296:302, 2:254]
    deck_roof = art[238:296, 2:254].copy()
    m = np.zeros(deck_roof.shape[:2], bool); m[:, 38:120] = True; m[:46, 172:240] = True
    deck_roof = fill_rows(deck_roof, m)
    fascia_r = at.add(fascia); roofdeck_r = at.add(deck_roof)
    # --- the porch posts, the deck (its boards with the posts, rails and railing painted out; its front), the railings
    post_l = at.add(art[302:342, 20:32]); post_r = at.add(art[302:342, 224:236])
    deck = art[342:394, 2:254].copy()
    m = np.zeros(deck.shape[:2], bool)
    m[0:20, 18:30] = m[0:20, 222:234] = True; m[0:26, 0:8] = m[0:26, 244:252] = True; m[24:, 0:32] = m[24:, 126:252] = True
    deck_r = at.add(fill_rows(deck, m)); deckf_r = at.add(art[396:408, 2:254])
    rail = open_balusters(art[372:396], 4, 13)
    rail_l = at.add(rail[:, 2:34]); rail_rr = at.add(rail[:, 128:254])
    rail_side = at.add(rail[:, 140:193])
    balc = open_balusters(art[258:282, 176:240], 4, 21)
    balc_r = at.add(balc)
    # --- the small gable over the door: its face as drawn, its slopes (28 rows deep in the art)
    sg = art[252:292, 40:120].copy()
    for x in range(80):
        c = np.where(roofm[240:292, 40 + x])[0]
        t = 240 + c.max() + 1 if len(c) else (258 if 36 <= x < 44 else 292)
        sg[:max(0, t - 252), x] = 0
    sg_panes, sg_glow = panes_of(sg, 12)
    sg_r = at.add(sg, sg_glow)
    keep2 = np.zeros(art.shape[:2], bool)
    for x in range(40, 120):
        c = np.where(roofm[240:292, x])[0]
        if len(c): keep2[240 + max(0, c.min() - 2):240 + c.max() + 1, x] = True
    keep2[224:258, 76:84] = True
    keep2 &= art[..., 3] > 0
    sl = at.add(cut(art[222:292, 40:80], keep2[222:292, 40:80])); sr = at.add(cut(art[222:292, 80:120], keep2[222:292, 80:120]))
    rail_top = art[368, 20]; rail_c = art[388, 20]; post_c = art[320, 24]
    deck_c = art[380, 80]; deck_front = art[400, 200]; tread = art[404, 80]; riser = art[400, 80]
    parts = [
        mbox((4, 252), (12, 52), (0, 167), (wall_c, wall_c, wall_c), ft=gf_r, st=gfs_r, panes=gf_panes, top=False),
        # the floor between the storeys, behind the terrace: its edge along the shown side
        mbox((4, 252), (52, 58), (36, 167), (art[298, 140], art[298, 140], art[298, 140]), top=False),
        mgable((0, 256), (98, 161), (36, 171), left, (0, 197, 128, 133, 128, -2, 0, 62), right, (128, 197, 0, 133, 0, -2, 128, 62), (rake_c, edge_c), roof_c, lip=lip_c),
        mbox((2, 254), (58, 98), (36, 167), (wall_c, wall_c, wall_c), ft=up_r, fx=0, st=ups_r, panes=up_panes, top=False),
        mbox((2, 254), (0, 12), (-53, 0), (deck_c, deck_front, shade(deck_front, .8)), ft=deckf_r, tt=deck_r),
        mbox((20, 32), (12, 52), (-18, -10), (rail_top, post_c, shade(post_c, .8)), ft=post_l),
        mbox((224, 236), (12, 52), (-18, -10), (rail_top, post_c, shade(post_c, .8)), ft=post_r),
        mbox((2, 254), (52, 58), (-22, 36), (art[260, 140], art[298, 140], shade(art[298, 140], .8)), ft=fascia_r, tt=roofdeck_r),
        # its side rails: a handrail on posts back to the wall (not solid ends: seen from the side, open)
        mrail((180, 58, -8), (180, 58, 36), 24, rail_c, posts=11, w=4),
        mrail((236, 58, -8), (236, 58, 36), 24, rail_c, posts=11, w=4),
        mbox((176, 240), (58, 82), (-8, -8), (rail_top, rail_c, rail_c), ft=balc_r, card=True),
        # (its ridge where the face's checkered rake boards meet, at 45 degrees from its eaves: the slopes' straight front
        # edges then run along the boards' outer edge, and the verge the engine draws above them shows)
        mgable((41, 119), (61, 100), (-18, 36), sl, (1, 67, 40, 33, 40, 5, 1, 39), sr, (39, 67, 0, 33, 0, 5, 39, 39), (rake_c, edge_c), roof_c,
               face=sg_r, fx=40, fy=58, panes=sg_panes, lip=lip_c),
        mbox((2, 8), (12, 36), (-53, 0), (rail_top, rail_c, rail_c), st=rail_side),
        mbox((248, 254), (12, 36), (-53, 0), (rail_top, rail_c, rail_c), st=rail_side),
        mbox((2, 34), (12, 36), (-53, -53), (rail_top, rail_c, rail_c), ft=rail_l, card=True),
        mbox((128, 254), (12, 36), (-53, -53), (rail_top, rail_c, rail_c), ft=rail_rr, card=True),
        mbox((28, 34), (12, 40), (-53, -47), (rail_top, rail_c, shade(rail_c, .8)), outline=True),
        mbox((128, 134), (12, 40), (-53, -47), (rail_top, rail_c, shade(rail_c, .8)), outline=True),
        mbox((34, 128), (6, 12), (-59, -53), (tread, riser, shade(riser, .85)), outline=True),
        mbox((34, 128), (0, 6), (-65, -53), (tread, riser, shade(riser, .85)), outline=True),
    ]
    # --- the yard: grass, the path from the steps to the gate, its fence or hedge. The house stands back by the game's
    # front yard and a tile more, for its deck and steps
    setback = int(shop.get('yard', {}).get('front', 2)) * 32 + 32
    style = shop.get('yard', {}).get('style', 'compact')
    px0, px1 = 66, 98
    lawn, fence, fence_snow = yard(rng, W, setback + 167, True, (px0, px1, setback - 65), style, [(px0 - 6, px1 + 6)])
    # the whole front for what does not build it (the corner turn: a box of the two storeys), its side and its glow
    front = np.zeros((98, W, 4), np.uint8)
    front[0:40] = up[66:106]; front[40:46] = art[296:302, 0:W]; front[46:86] = art[302:342, 0:W]; front[86:98] = art[396:408, 0:W]
    front = solid(front); front[:, :2] = 0; front[:, 254:] = 0
    # and what stands in front of the storeys, as the street sees it from straight ahead (a row of this picture is 98
    # less its height): the balcony's railing and the small gable over the door before the upper floor, the deck's
    # railings before the ground floor (the porch posts are in its rows as drawn). The corner turn's box wears them
    # flat, so the villa keeps them as the view comes round; the wet road mirrors them
    def over(pic, x, y_top):
        r0 = 98 - y_top; h, w = pic.shape[:2]; m = pic[..., 3] > 0
        front[r0:r0 + h, x:x + w][m] = pic[m]
    over(sg, 40, 98); over(balc, 176, 82); over(rail[:, 2:34], 2, 36); over(rail[:, 128:254], 128, 36)
    emis = np.zeros_like(front); emis[0:40] = panes_of(front[0:40])[1]; emis[46:86] = panes_of(front[46:86])[1]
    side = np.zeros((98, 167, 4), np.uint8)
    side[0:40] = np.concatenate([ups[:, :38], ups[:, 2:]], axis=1); side[40:46, :, :3] = art[298, 140][:3]; side[40:46, :, 3] = 255; side[46:86] = gfs
    side[86:98, :, :3] = deck_front[:3]; side[86:98, :, 3] = 255
    model = dict(kind='villa', w=W, frontX=(224 - W) / 2, setback=setback, lawn='grass', style=style, parts=parts,
                 fence=dict(d=3, foot=28 if style != 'formal' else 32) if fence is not None else None,
                 roofTint=rgb_int(roof_c), lamp=[104, 40, 0], lights='home', smoke=[], fpx=0)
    return model, at, front, side, emis, lawn, fence, fence_snow

def tower(shop, rng):
    key = shop['parts'][0]['key']
    art = solid(np.array(load(key)))
    W = 256; at = Atlas(512)
    roof_c = art[100, 128]
    # the tower: a square box wearing its front on every side (a clock on each face), the roof's eave fascia its top.
    # Its upper storeys' brick piers stand two pixels in from the stone bands and the lower storey; on a box both the
    # front's and the side's two outer columns would be empty there, a slit of sky down each corner. Those rows are
    # widened to the box's edge: the pier's outline moved out to it and its brick carried after it.
    body = art[140:460, 10:244].copy()
    for y in range(body.shape[0]):
        xs = np.where(body[y, :, 3] > 0)[0]
        if not len(xs): continue
        f, l = int(xs.min()), int(xs.max())
        if 0 < f <= 2: body[y, 0:f + 2] = body[y, f:2 * f + 2].copy()
        n = body.shape[1] - 1 - l
        if 0 < n <= 2: body[y, l - 1:] = body[y, l - 1 - n:l + 1].copy()
    # the clock's painted hands taken off its dial (the engine draws them at the hour: the minute hand as long as the
    # art's, 12 of its doubled pixels, the hour hand 9), then where the face glows at night: all of it, hands' place too
    clock = None
    for q in panes_of(body, dial=True)[0]:
        if q[4] == 0 and clock is None:
            body, hub, ink = clock_of(body, q)
            if hub is not None: clock = hub + [12, 9] + [rgb_int(c) for c in ink]
    b_panes, b_glow = panes_of(body, dial=True)
    body_r = at.add(body, b_glow)
    # the hipped roof: its front face, the art's rows 61..139 between the hips, every other one (the artist's own
    # pixels, one screen row each; the rise is chosen so). Its last row is the tiles' ends along the eave, the roof's
    # (on the wall's top they stayed red under the snow)
    E, run = 320, 58
    face = cut(art[61:140, 34:220], polygon((79, 186), [(0, 79), (58, 0), (128, 0), (186, 79)]))
    face = face[::2]
    rise = rise_for(face.shape[0], run)
    front_r = at.add(face)
    lq = [(0, 134), (0, 0), (58, 26), (58, 57)]; rq = [(58, 134), (58, 0), (0, 26), (0, 57)]
    lpic = hip_side(face, 59, 135, lq, 234 / 134, (64, 124), True); rpic = hip_side(face, 59, 135, rq, 234 / 134, (64, 124), False)
    left, right = at.add(lpic), at.add(rpic)
    flush = [at.add(along_mean(lpic)), at.add(along_mean(rpic))]
    lantern = at.add(art[50:61, 92:164])
    stone = art[112, 16]
    # the box's top round the roof: the stone coping the art shows along the tower's edges from above (light stone
    # outlined in ink over the grey course under it), its rows the top's depth from the back (one per 10 pixels)
    cope, grey, ink = art[60, 28], art[140, 14], art[60, 22]
    top = np.zeros((24, 234, 4), np.uint8); top[:] = grey
    top[1:23, 2:12] = cope; top[1:23, 222:232] = cope; top[1:3, 2:232] = cope; top[21:23, 2:232] = cope
    top[0, :] = ink; top[23, :] = ink; top[:, 0:2] = ink; top[:, 232:] = ink
    grain = np.kron(rng.integers(-5, 6, (12, 117)), np.ones((2, 2), np.int64))
    top[..., :3] = np.clip(top[..., :3].astype(int) + grain[..., None], 0, 255).astype(np.uint8)
    top_r = at.add(top)
    parts = [
        mbox((10, 244), (0, E), (0, 234), (stone, art[300, 128], art[300, 128]), ft=body_r, tt=top_r, st=body_r, panes=b_panes, clock=clock),
        mhip((34, 220), (E, E + rise), (0, 234), (92, 162, run, 234 - run), front_r, left, [v for p in lq for v in p], right, [v for p in rq for v in p], roof_c,
             hip=(art[80, 73], art[80, 73]), flush=flush),
        mbox((92, 164), (E + rise, E + rise + 11), (run + 12, 234 - run - 12), (art[40, 128], art[55, 128], shade(art[55, 128], .8)), ft=lantern),
    ]
    setback = 32
    lawn, fence, fence_snow = yard(rng, W, setback + 234, False, (0, 0, 0), 'natural', [], ground='paving')
    model = dict(kind='tower', w=W, frontX=(224 - W) / 2, setback=setback, lawn='paving', style='natural', parts=parts,
                 fence=None, roofTint=rgb_int(roof_c), lamp=None, lights='lamp', smoke=[], fpx=10)
    front = np.zeros((E, 234, 4), np.uint8); front[:] = body
    return model, at, front, body, b_glow, lawn, fence, fence_snow

def school(shop, rng):
    key = shop['parts'][0]['key']
    art = solid(np.array(load(key)))
    W = 768; at = Atlas(768)
    roof_c = art[300, 100]
    bottom = int(np.where(art[:, 30, 3] > 0)[0].max()) + 1
    E = bottom - 398
    # --- the walls: the long front as drawn, and the middle bay (standing 12 forward) with the cross gable's face over
    # it, cut above its rake boards. The bay is as wide as the two doors' arches (cols 224..545), which reach out past
    # the gable's eaves (258, 510): an edge through an arch would break it in two
    body = art[398:bottom, 20:748]
    b_panes, b_glow = panes_of(body)
    body_r = at.add(body, b_glow)
    BX0, BX1 = 222, 546
    bay = art[318:bottom, BX0:BX1].copy()
    for x in range(BX1 - BX0):
        g = x + BX0 - 258                                               # across the cross gable, from its left eave
        r = 398 - (126 - abs(g - 126 + .5)) * 74 / 126 - 2 if 0 <= g < 252 else 398   # its rake's top edge; else the wall's
        bay[:max(0, int(r) - 318), x] = 0
    bay_panes, bay_glow = panes_of(bay)
    bay_r = at.add(bay, bay_glow)
    ret = column_rows(art[398:bottom], list(range(204, 222)))
    bay_side = at.add(np.repeat(ret[:, None, :], 12, axis=1))
    # the side walls: each row the commonest colour of the front's wall clear of its windows and doors, an upper and an
    # arched window of the front's own, frame and arch and all
    rows = np.zeros((E, 4), np.uint8); gm = glass(body)
    for y in range(E):
        near = gm[max(0, y - 40):y + 40].any(0)
        near = near | np.roll(near, 8) | np.roll(near, -8)               # and the frames beside the glass
        px = [tuple(c) for c in body[y][(body[y, :, 3] > 200) & ~near]]
        rows[y] = max(set(px), key=lambda c: (px.count(c), c)) if px else (190, 170, 140, 255)
    side, side_glow = side_wall(rows, 272, None, 0, (), rng, art[600, 24])
    for (y0, y1, x0, x1) in ((8, 86, 24, 94), (110, 228, 72, 172)):
        crop = body[y0:y1, x0:x1]; m = crop[..., 3] > 200; g = dilate(glass(crop), 1) & m
        for f in (.27, .73):
            cx = int(272 * f) - crop.shape[1] // 2
            side[y0:y1, cx:cx + crop.shape[1]][m] = crop[m]
            side_glow[y0:y1, cx:cx + crop.shape[1]][g] = GLOW
    side_r = at.add(side, side_glow)
    # --- the hipped roof: the front face (rows 145..397 between the hips, every other one), the cross gable's slopes
    # and the second chimney painted out with the tiles' own pattern (they repeat every 12 pixels along a row)
    run = 122
    region = art[145:398].copy()
    m = np.zeros(region.shape[:2], bool); m[45:, 256:512] = True; m[:18, 552:610] = True
    region = fill_period(region, m, 12)
    face = cut(region[:, 14:754], polygon((253, 740), [(0, 253), (122, 0), (617, 0), (740, 253)]))[::2]
    rise = rise_for(face.shape[0], run)
    front_r = at.add(face)
    flat = art[105:145, 136:631].copy()
    fm = np.zeros(flat.shape[:2], bool); fm[:, 418:472] = True
    flat_r = at.add(fill_period(flat, fm, 12)[::2])
    lq = [(0, 350), (0, 0), (122, 57), (122, 97)]; rq = [(123, 350), (123, 0), (0, 57), (0, 97)]
    lpic = hip_side(face, 123, 351, lq, 284 / 350, (126, 234), True); rpic = hip_side(face, 124, 351, rq, 284 / 350, (126, 234), False)
    left, right = at.add(lpic), at.add(rpic)
    flush = [at.add(along_mean(lpic)), at.add(along_mean(rpic))]
    # the cross gable's slopes: from its rake back to the valley where they meet the main roof
    ridge2 = E + 72
    clq = [(0, 201), (126, 129), (126, 2), (4, 113)]; crq = [(126, 201), (0, 129), (0, 2), (122, 113)]
    cl = at.add(cut(art[195:398, 258:385], polygon((203, 127), clq)))
    cr = at.add(cut(art[195:398, 384:511], polygon((203, 127), crq)))
    R = E + rise
    # the chimneys: each a brick stack (its front the art's, the near one's carried up a few courses: more of it shows
    # over the ridge than the art draws) under a wider stone cap, whose front is the art's band of courses and whose
    # top the art's cap seen from above, its flue a dark hole (every other row: the art draws tops twice as deep)
    stack1 = at.add(np.concatenate([art[88:98, 156:198], art[88:106, 156:198]])); cap1 = at.add(art[72:86, 152:202])
    top1 = at.add(art[39:72, 152:202][::2])
    stack2 = at.add(art[144:162, 560:602]); cap2 = at.add(art[130:142, 556:606]); top2 = at.add(art[96:130, 556:606][::2])
    brick, cap_c = art[95, 175], art[78, 160]
    stone = art[700, 210]; tread = art[700, 300]; riser = art[405, 300]
    # the steps' cheeks: the art's stone balusters along the stair, a coping on them
    coping, ink = art[690, 230], art[650, 214]
    # (from the cheek's outline in: the two columns left of it are the art's half-clear shadow, which solid() clears)
    cheek = np.repeat(art[690:691, 200:234], 24, axis=0); cheek[0:3] = coping; cheek[3] = ink
    cheek[-1] = ink; cheek[:, :1] = ink; cheek[:, -1:] = ink
    cheek[..., 3] = 255
    cheek_r = at.add(cheek)
    parts = [
        mbox((20, 748), (0, E), (0, 272), (stone, stone, stone), ft=body_r, st=side_r, panes=b_panes, top=False),
        mbox((156, 198), (R - 18, R + 10), (174, 194), (brick, brick, shade(brick, .8)), ft=stack1),
        mbox((152, 202), (R + 10, R + 24), (170, 198), (cap_c, cap_c, shade(cap_c, .8)), ft=cap1, tt=top1),
        mhip((14, 754), (E, R), (-6, 278), (136, 631, -6 + run, 278 - run), front_r, left, [v for p in lq for v in p], right,
             [v for p in rq for v in p], roof_c, flat=flat_r, hip=(art[200, 108], art[200, 106]), flush=flush),
        mbox((560, 602), (R - 10, R + 8), (104, 118), (brick, brick, shade(brick, .8)), ft=stack2),
        mbox((556, 606), (R + 8, R + 20), (101, 121), (cap_c, cap_c, shade(cap_c, .8)), ft=cap2, tt=top2),
        mgable((258, 510), (E, ridge2), (-12, 71), cl, [v for p in clq for v in p], cr, [v for p in crq for v in p], (roof_c, roof_c), roof_c, de=-6,
               lip=art[370, 300]),
        # the ledge over the bay beside the gable (the bay itself has no top: the gable's face stands on it)
        mbox((BX0, BX1), (E - 4, E), (-12, -6), (art[408, 230], art[406, 230], shade(art[406, 230], .8)), outline=True),
        mbox((BX0, BX1), (0, E), (-12, 0), (stone, stone, stone), ft=bay_r, fx=BX0, st=bay_side, panes=bay_panes, top=False),
        mbox((198, 232), (0, 24), (-70, -12), (coping, art[720, 210], shade(art[720, 210], .85)), ft=cheek_r),
        mbox((534, 568), (0, 24), (-70, -12), (coping, art[720, 210], shade(art[720, 210], .85)), ft=cheek_r),
        mbox((232, 534), (0, 18), (-40, -12), (tread, riser, shade(riser, .85)), outline=True),
        mbox((232, 534), (0, 12), (-46, -40), (tread, riser, shade(riser, .85)), outline=True),
        mbox((232, 534), (0, 6), (-52, -40), (tread, riser, shade(riser, .85)), outline=True),
    ]
    setback = 80
    lawn, fence, fence_snow = yard(rng, W, setback + 272, False, (0, 0, 0), 'natural', [], ground='paving')
    model = dict(kind='school', w=W, frontX=(224 - W) / 2, setback=setback, lawn='paving', style='natural', parts=parts,
                 fence=None, roofTint=rgb_int(roof_c), lamp=None, lights='shop',
                 smoke=[[177, 184, R + 24], [581, 111, R + 20]], fpx=20)
    return model, at, body, side, b_glow, lawn, fence, fence_snow

# ------------------------------------------------------------------------------------------------ terraced houses
# The terraced family houses (house_terraced_1/2/4/5/6, each in six colours) are drawn like the rest, front elevation
# below and what faces up seen from above: their front wall is the END of a single-pitch (lean-to) roof that rises across
# the facade, low on the left and high on the right, with its high edge running back from the street; the big slab is
# that roof seen from above; on the right a lower side wing runs the house's depth (its top from above, its front below).
# What stands back in the art (a recessed door porch, the garage under the upper storey) has its foot drawn higher, by
# how far back it stands; in the street's nearly level view a few pixels of depth do not show, so the front is made a
# true elevation instead: in a recess's columns the rows its top hides are put back under the storey above, so every
# wall meets the ground on one line (row 461). Steps, a planter or a pot standing in front are built as boxes and cards.
# Every colour of a design shares its shape (the recolours keep the alpha); designs 4, 5, 6 sit two pixels further left.
TERRACED = {
    1: dict(recess=[(42, 173, 449)], steps=(84, 141), cards=[(216, 294, 435, 476, 14)], door=(84, 141), lamp=(150, 84)),
    2: dict(recess=[(42, 173, 449)], steps=(84, 141), cards=[], door=(84, 141), lamp=(150, 84)),
    4: dict(recess=[(236, 279, 457)], steps=None, cards=[], door=(236, 279), lamp=None),
    5: dict(recess=[(40, 225, 449)], steps=None, cards=[(304, 336, 410, 464, 2)], door=(228, 300), lamp=None),
    6: dict(recess=[(40, 225, 449)], steps=None, cards=[], door=(228, 300), lamp=None),
}
T_X0, T_X1, T_FOOT, T_RIDGE = 20, 352, 461, 318                     # the art's span, its ground row, the roof's high edge
T_R = 389                                                           # the storey above a recess ends here
T_DEPTH = 240                                                       # the roof's run back, as the art draws it

def t_roof_edge(x):
    """The first row of the roof slab's front edge (its dark band; the trim under it 8 rows on) in column x: it falls
    two rows every eight columns (the art is 2x pixel art) from row 306 at column 40."""
    return 306 - 2 * ((x - 40) // 8)

def terraced(shop, rng):
    key = shop['parts'][0]['key']
    design = int(key.split('_')[2])
    cfg = TERRACED[design]
    raw = np.array(load(key)); art = solid(raw)
    op = art[..., 3] > 0
    x0 = int(np.where(op[:, :T_RIDGE].any(0))[0].min())               # where the roof starts (20, or 22 for 1 and 2)
    W = T_X1 - T_X0; at = Atlas(512)
    X = lambda ax: ax - T_X0                                        # the art's columns to the model's
    roof_c = art[150, 150]; lip_c = art[t_roof_edge(100) + 2, 100]
    rake_c = art[t_roof_edge(100) + 9, 100]; edge_c = art[t_roof_edge(100) + 16, 100]
    # --- the facade as a true elevation, columns x0..305, from the roof slab's front edge down to the ground
    fw = 306 - T_X0; top_row = t_roof_edge(305); fh = T_FOOT + 1 - top_row
    face = np.zeros((fh, fw, 4), np.uint8)
    rec = {}
    for (a, b, foot) in cfg['recess']:
        for x in range(a, b + 1): rec[x] = foot
    for x in range(max(x0, T_X0), 306):
        col = art[:, x].copy()
        foot = rec.get(x)
        if foot is not None:
            k = T_FOOT - foot                                       # the rows this recess's top hides
            col = np.concatenate([col[:T_R], np.repeat(col[T_R:T_R + 1], k, 0), col[T_R:foot + 1]])
        col = col[:T_FOOT + 1]
        col[:t_roof_edge(x)] = 0                                    # above the slab's front edge: the roof (its slope)
        face[:, x - T_X0] = col[top_row:T_FOOT + 1]
    face_panes, face_glow = panes_of(face)
    face_r = at.add(face, face_glow)
    # --- the roof slope from above: rows down to the slab's front edge, columns x0..318
    rw = T_RIDGE + 1 - x0; rh = t_roof_edge(x0)
    slope = np.zeros((rh, rw, 4), np.uint8)
    for x in range(x0, T_RIDGE + 1):
        e = t_roof_edge(x); slope[:e, x - x0] = art[:e, x]
    top_l = int(np.where(op[:, x0])[0].min()); top_r = int(np.where(op[:, T_RIDGE])[0].min())
    slope_r = at.add(slope)
    # --- the side wing on the right: its front (its top's edge 270..280 dropped; the rows its standing back hides put
    # back) and its top from above
    wing = art[280:456, 306:352]
    wing = np.concatenate([np.repeat(wing[:1], T_FOOT - 455, 0), wing])
    wing_top = art[30:280, 306:352]
    wing_r = at.add(wing); wing_top_r = at.add(wing_top)
    # --- the side walls: each row the front's own siding (the party walls have no windows)
    corner = shade(art[400, 60], .8) + (255,)
    plain = list(range(32, 48))                                     # the upper storey's wall left of its window
    main_h = T_FOOT - (t_roof_edge(42) + 16)                         # the low (left) side's wall, up to the trim
    high_h = T_FOOT - (t_roof_edge(305) + 16)
    # (the upper storey's siding at plain columns, repeated down the wall: the ground storey's rows are doors and
    # recesses, which a side wall does not carry)
    up_rows = column_rows(face[t_roof_edge(42) + 16 - top_row:T_R - 8 - top_row], plain)
    def siding(h): return np.resize(up_rows, (h,) + up_rows.shape[1:])
    ms, _ = side_wall(siding(main_h), T_DEPTH, None, 0, (), rng, corner)
    hs, _ = side_wall(siding(high_h), T_DEPTH, None, 0, (), rng, corner)
    ws = importlib.import_module('buildings_wf-sprites').ribbed_side(wing, 250, corner)   # the wing's ribs carried round
    ms_r = at.add(ms); hs_r = at.add(hs); ws_r = at.add(ws)
    # the walls' plain colour: the siding's own body colour (its commonest), not whatever stripe one pixel lands on
    sid = up_rows[up_rows[..., 3] > 0][:, :3]
    vals, cnt = np.unique(sid, axis=0, return_counts=True)
    wall_c = np.concatenate([vals[cnt.argmax()], [255]]).astype(np.uint8) if len(vals) else art[440, 60]
    wing_c = art[400, 330]
    yr0, yr1 = T_FOOT - t_roof_edge(x0), T_FOOT - t_roof_edge(T_RIDGE)
    parts = [
        # the side wing, standing back by the rows its foot is drawn above the front's
        mbox((X(306), X(352)), (0, wing.shape[0]), (6, 256), (wing_top[-1, 20], wing_c, shade(wing_c, .85)), ft=wing_r, tt=wing_top_r, st=ws_r),
        # the house's high side under the roof's high edge (its front is the facade's)
        mbox((X(302), X(306)), (0, high_h), (0, T_DEPTH), (wall_c, wall_c, wall_c), st=hs_r, top=False),
        # the house, its low side wall, wearing the facade (which rises over it to the roof's edge)
        mbox((X(42), X(306)), (0, main_h), (0, T_DEPTH), (wall_c, wall_c, wall_c), ft=face_r, fx=X(T_X0), st=ms_r, panes=face_panes, top=False),
        # the single-pitch roof: its slope from above laid on its quad, the high edge at the right
        mgable((X(x0), X(T_RIDGE)), (yr0, yr1), (0, T_DEPTH), slope_r, (0, rh, rw - 1, t_roof_edge(T_RIDGE), rw - 1, top_r, 0, top_l),
               None, None, (rake_c, edge_c), roof_c, lip=lip_c, ax=X(T_RIDGE)),
    ]
    st = cfg['steps']
    if st is not None:
        a, b = X(st[0]), X(st[1] + 1)
        tread = art[456, (st[0] + st[1]) // 2]; riser = art[460, (st[0] + st[1]) // 2]
        parts += [mbox((a, b), (6, 12), (0, 12), (tread, riser, shade(riser, .85)), outline=True),
                  mbox((a, b), (0, 6), (-8, 12), (tread, riser, shade(riser, .85)), outline=True)]
    for (a, b, r0, r1, fwd) in cfg['cards']:
        pic = art[r0:r1, a:b].copy()
        parts.append(mbox((X(a), X(b)), (0, r1 - r0 - fwd), (-fwd, -fwd + 2), (pic[-1, (b - a) // 2], pic[-1, (b - a) // 2], pic[-1, (b - a) // 2]),
                          ft=at.add(pic), card=True))
    # --- the yard: the game's front yard (its depth, its fence or hedge), a path to the door, open at the gate
    setback = int(shop.get('yard', {}).get('front', 2)) * 32 + 16
    style = shop.get('yard', {}).get('style', 'compact')
    px0, px1 = X(cfg['door'][0]) + 4, X(cfg['door'][1]) - 4
    lawn, fence, fence_snow = yard(rng, W, setback + T_DEPTH, True, (px0, px1, setback - 8), style, [(px0 - 6, px1 + 6)])
    # the whole front for what does not build it (the corner turn, the wet road's mirror): the facade and the wing
    H = max(fh, wing.shape[0])
    front = np.zeros((H, W, 4), np.uint8)
    front[H - fh:, :fw] = face
    m = wing[..., 3] > 0; sub = front[H - wing.shape[0]:, X(306):X(352)]; sub[m] = wing[m]
    emis = np.zeros_like(front); emis[H - fh:, :fw] = face_glow
    side = ms
    lamp = [X(cfg['lamp'][0]), cfg['lamp'][1], 12] if cfg['lamp'] else None
    model = dict(kind='terraced', w=W, frontX=(224 - W) / 2, setback=setback, lawn='grass', style=style, parts=parts,
                 fence=dict(d=3, foot=28 if style != 'formal' else 32) if fence is not None else None,
                 roofTint=rgb_int(roof_c), lamp=lamp, lights='home', smoke=[], fpx=0)
    return model, at, front, side, emis, lawn, fence, fence_snow

# ------------------------------------------------------------------------------------------------ estates
# The estates (houseEstate) are the biggest houses, each several volumes; each is modelled by hand from its parts, as the
# villas are. The one-story house: a big FRONT-FACING gable over the house, a garage standing a little forward under its
# own small front gable, and a porch roof on two posts beside it. The art draws the garage's and the porch's roofs from
# above inside what would be the front, so in the front picture those are painted over with the wall's own vertical
# boards (each column the colour of its board just above), and the engine builds the roofs.

def column_fill(img, mask, src_rows):
    """What `mask` covers, painted in each column with that column's colour at the first unmasked row of `src_rows`
    (the wall's boards run up and down, so a column keeps its colour)."""
    img = img.copy()
    for x in range(img.shape[1]):
        m = mask[:, x]
        if not m.any(): continue
        src = next((y for y in src_rows(x) if 0 <= y < img.shape[0] and not mask[y, x] and img[y, x, 3] > 200), None)
        if src is not None: img[m, x] = img[src, x]
    return img

def hand_panes(img, boxes, least=300):
    """Window panes placed by hand (x, y, w, h in `img`'s pixels, then 1 square or 0 round), for art whose walls share
    the glass's blue-greys (a search for the glass finds the wall too); each glows at night over its lighter pixels
    (the glass, a blind's slats), not its frame's ink, a little brighter where the art's glass is lighter."""
    lum = img[..., :3].astype(int).sum(-1)
    glow = np.zeros_like(img)
    for (x, y, w, h, sq) in boxes:
        sub = lum[y:y + h, x:x + w]; m = (sub >= least) & (img[y:y + h, x:x + w, 3] > 200)
        if not m.any(): continue
        lo, hi = sub[m].min(), sub[m].max(); f = .8 + .2 * (sub - lo) / max(1, hi - lo)
        g = np.stack([np.round(GLOW[0] * f), np.round(GLOW[1] * f), np.round(GLOW[2] * f), np.full(f.shape, GLOW[3])], -1).astype(np.uint8)
        glow[y:y + h, x:x + w][m] = g[m]
    return [list(b) for b in boxes], glow

def board_side(lit, depth, h, rng, corner, windows=(), base=None):
    """A side wall of upright boards, `depth` deep and `h` tall, the front's own: `lit` one period of the front's lit
    boards (a colour per column) repeated along the depth, so they run up the side as up the front; the front's windows
    (each its picture, frame and all, the fraction of the depth its middle stands at, its top row and its glass box);
    `base` the colours of the wall's foot, a row each; darker corner boards and a fine grain. Returns it and where its
    windows glow."""
    side = np.zeros((h, depth, 4), np.uint8)
    side[:] = lit[np.arange(depth) % len(lit)][None]
    side[..., 3] = 255
    if base is not None: side[h - len(base):, :, :3] = np.asarray(base)[:, None, :3]
    glow = np.zeros_like(side)
    for (pic, f, wy, pane) in windows:
        wh, ww = pic.shape[:2]; m = pic[..., 3] > 200
        cx = int(depth * f) - ww // 2
        side[wy:wy + wh, cx:cx + ww][m] = pic[m]
        g = hand_panes(pic, [pane])[1]; gm = g[..., 3] > 0
        glow[wy:wy + wh, cx:cx + ww][gm] = g[gm]
    for c in (0, 1, depth - 2, depth - 1): side[:, c, :3] = corner[:3]
    grain = rng.integers(-3, 4, (h // 2 + 1, depth // 2 + 1))
    grain = np.kron(grain, np.ones((2, 2), np.int32))[:h, :depth]
    side[..., :3] = np.clip(side[..., :3].astype(int) + grain[..., None], 0, 255).astype(np.uint8)
    return side, glow

def tile_x(pic, w):
    """`pic` repeated across to `w` columns."""
    return np.concatenate([pic] * (w // pic.shape[1] + 1), axis=1)[:, :w].copy()

def pent_end(color, run, rise):
    """A pent roof's end as a hipped roof's side piece (columns up the slope, rows along its depth): its edge colour."""
    pic = np.zeros((run, max(2, int(rise)), 4), np.uint8); pic[..., :3] = color[:3]; pic[..., 3] = 255
    return pic

def apt_maker(key):
    """The apartment blocks' and the hotel's builder (tools/buildings_apt.py) for this sprite, or None."""
    import buildings_apt
    return buildings_apt.maker(key)

def build_model(shop, out):
    """One of our villas or landmarks as the parts the engine builds it from (models.json) and the pictures they wear."""
    key = shop['parts'][0]['key']; n = shop['sprite']
    rng = np.random.default_rng(zlib.crc32(n.encode()))
    import buildings_estA, buildings_estB, buildings_civA
    from buildings_civB import police
    make = apt_maker(key) or (buildings_estB.ESTATES[key] if key in buildings_estB.ESTATES else police if key.startswith('landmark_police_') else buildings_civA.builder(key) or (villa if key.startswith('villa_') else tower if key.startswith('landmark_clocktower_') else terraced if key.startswith('house_terraced_')
            else buildings_estA.estate_one_story if key == 'estate_one_story_house' else buildings_estA.estate_country if key == 'estate_country_house' else school))
    model, at, front, side, emis, lawn, fence, fence_snow = make(shop, rng)
    # which boxes' sides have windows that light at night (the engine lights no other: a railing's side has none)
    for p in model['parts']:
        if 'st' in p and at.glows(p['st']): p['sg'] = True
    at.save(out, n)
    for name, img in (('front', front), ('side', side), ('emis', emis), ('lawn', lawn)):
        Image.fromarray(np.ascontiguousarray(img)).save(f'{out}/{n}_{name}.png')
    if fence is not None:
        Image.fromarray(fence).save(f'{out}/{n}_fence.png'); Image.fromarray(fence_snow).save(f'{out}/{n}_fencesnow.png')
    WINDOWS[n] = []
    MODELS[n] = model

# What this script writes for a sprite. Anything else in the folder is left alone: the lighting maps make_lighting.py
# made from each front need a depth model that is not always on disk, so they must survive a re-run that does not
# change their front.
OUTPUTS = ('front', 'side', 'emis', 'roof', 'pitch', 'postl', 'postr', 'gablep', 'balp', 'bals', 'balf', 'wcap', 'pier', 'sign', 'prop', 'glass', 'over', 'slope', 'ridge', 'back', 'hitch', 'lawn', 'porch', 'fence',
           'fencesnow', 'atlas', 'glow')
MAPS = ('lit', 'shade')

def pixels(path):
    return np.array(Image.open(path).convert('RGBA')) if os.path.exists(path) else None

def showcase(out):
    """Every house sprite (8 designs x 2 sizes x 10 colours) on a street.json of its own, in out/ (street.json and
    buildings/), for checking them by eye: street 0 has every design once in a colour each, street 1 every colour of
    one design, street 2 the five villas, street 3 the clock towers and the school, street 4 the terraced houses, street 5 the
    estates, streets 6 to 10 each group's own (estates A, the Japanese and modern estates, civic, police, apartments and the hotel). The preview reads it with
    -Dassets=out -Dmode=houses (or -Dmode=models for streets 2 and 3)."""
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
    # the villas (each built with a front-facing gable), and the landmarks with hipped roofs, which no street has yet
    def add(key, yard_style):
        im = load(key); sprite = f'b{len(shops)}'
        shops.append(dict(id=f'show:{key}', sprite=sprite, home=key.startswith(('villa_', 'house_', 'estate_', 'condo_')), name=key, kind='Home' if key.startswith(('villa_', 'house_', 'estate_', 'condo_')) else 'Landmark',
                          owner='', colour='base', parts=[dict(key=key, w=im.width, h=im.height, y=0)], yard=dict(front=2, style=yard_style)))
        return sprite
    villas = [add(f'villa_{i}', ('compact', 'formal', 'natural', 'flower', 'formal')[i - 1]) for i in range(1, 6)]
    landmarks = [add(k, 'natural') for k in ('landmark_clocktower_1', 'landmark_school_1', 'landmark_clocktower_2')]
    # the terraced houses (single-pitch roofs): every design, in a colour each
    terr = [add(f'house_terraced_{d}{c}', ('compact', 'formal', 'natural', 'flower', 'compact')[i])
            for i, (d, c) in enumerate(((1, ''), (2, '_c3'), (4, '_c1'), (5, ''), (6, '_c3')))]
    estates = [add('estate_one_story_house', 'formal'), add('estate_country_house', 'compact'), add('estate_japanese_house', 'natural'), add('estate_modern_house', 'flower')]
    # the one-story and country estates on a street of their own (street 6)
    estates_a = [add('estate_one_story_house', 'formal'), add('estate_country_house', 'compact'), add('estate_one_story_house', 'flower')]
    estates_b = [add('estate_japanese_house', 'natural'), add('estate_modern_house', 'formal')]
    civic = [add(k, 'natural') for k in ('landmark_fire_1', 'landmark_hospital_1', 'landmark_post_1', 'landmark_post_2', 'landmark_post_3', 'landmark_post_4')]
    police = [add(f'landmark_police_{i}', 'natural') for i in (1, 2, 3)]
    apts = [add(k, 'natural') for k in ('condo_3_44', 'condo_3_45', 'condo_5_1', 'condo_5_2', 'condo_5_3', 'condo_9', 'landmark_hotel_1')]
    os.makedirs(out + '/buildings', exist_ok=True)
    json.dump(dict(town='showcase', year=2025, type='every house sprite', shops=shops,
                   streets=[dict(name='Every design', buildings=row), dict(name='Every colour', buildings=colours),
                            dict(name='Villas', buildings=villas), dict(name='Landmarks', buildings=landmarks),
                            dict(name='Terraced', buildings=terr), dict(name='Estates', buildings=estates),
                            dict(name='Estates A', buildings=estates_a),
                            dict(name='Japanese and modern estates', buildings=estates_b),
                            dict(name='Civic', buildings=civic), dict(name='Police', buildings=police),
                            dict(name='Apartments and the hotel', buildings=apts)]),
              open(out + '/street.json', 'w'), indent=1)
    return out + '/street.json', out + '/buildings'

def build_one(job):
    """One shop built in a worker process: what it added to WINDOWS, HOUSES and MODELS, in the order it added them (the
    pictures go straight to disk, one set of file names per sprite, so workers never write the same file)."""
    shop, out = job
    WINDOWS.clear(); HOUSES.clear(); MODELS.clear()
    build(shop, out)
    return list(WINDOWS.items()), list(HOUSES.items()), list(MODELS.items())

def build_all(shops, out, jobs):
    """Every shop, in `jobs` processes (1: in this one). The workers' additions are merged in the shops' order, so the
    json files come out byte-identical to a serial run (every builder seeds its own rng from the sprite's name)."""
    if jobs <= 1:
        for shop in shops: build(shop, out)
        return
    import multiprocessing
    # the modelled buildings (landmarks, estates, apartments: 0.2-0.8 s each) go first so none is left running alone at
    # the end; the results are put back in the shops' order before merging
    heavy = lambda s: 0 if s['parts'][0]['key'].startswith(('house_small_', 'house_medium_')) or len(s['parts']) > 1 else -1
    order = sorted(range(len(shops)), key=lambda i: (heavy(shops[i]), i))
    done = [None] * len(shops)
    # fork: the workers start with this module (and the group modules) already imported
    with multiprocessing.get_context('fork').Pool(jobs) as pool:
        for i, r in pool.imap_unordered(build_nth, [(i, shops[i], out) for i in order], chunksize=1):
            done[i] = r
    for w, h, m in done:
        WINDOWS.update(w); HOUSES.update(h); MODELS.update(m)

def build_nth(job):
    """build_one for the shop at index i, tagged with i."""
    i, shop, out = job
    return i, build_one((shop, out))

def main(argv):
    jobs = os.cpu_count() or 1
    if argv and argv[0].startswith('--jobs='): jobs = int(argv[0].split('=', 1)[1]); argv = argv[1:]
    if argv[0] == '--showcase': src, out = showcase(argv[1])
    else: src, out = argv[0], argv[1]
    os.makedirs(out, exist_ok=True)
    shops = json.load(open(src))['shops']
    names = {s['sprite'] for s in shops}
    # each front as it was, so a lighting map is kept only while it still fits its front
    before = {n: pixels(f'{out}/{n}_front.png') for n in names}
    for f in glob.glob(out + '/b*_*.png'):
        sprite, suffix = os.path.basename(f)[:-4].split('_', 1)
        # a sprite no longer on the streets loses everything; one still there only what is made again below
        if suffix in OUTPUTS or sprite not in names: os.remove(f)
    build_all(shops, out, jobs)
    for n in names:
        now, was = pixels(f'{out}/{n}_front.png'), before[n]
        if was is None or now is None or was.shape != now.shape or (was != now).any():
            for m in MAPS:
                if os.path.exists(f'{out}/{n}_{m}.png'): os.remove(f'{out}/{n}_{m}.png')
    json.dump(WINDOWS, open(out + '/windows.json', 'w'))
    # the houses' shapes in a file of their own: the app reads windows.json as lists only
    if HOUSES: json.dump(HOUSES, open(out + '/houses.json', 'w'), sort_keys=True)
    elif os.path.exists(out + '/houses.json'): os.remove(out + '/houses.json')
    # and the villas' and landmarks' parts
    if MODELS: json.dump(MODELS, open(out + '/models.json', 'w'), sort_keys=True)
    elif os.path.exists(out + '/models.json'): os.remove(out + '/models.json')
    print('wrote', len(os.listdir(out)), 'files to', out, '; windows:', {k: len(v) for k, v in WINDOWS.items()})

if __name__ == '__main__':
    main(sys.argv[1:])
