"""Buildings assembled from the modular kits of the Modern Exteriors pack (tools/kits.py, tools/kits/kits.json) as modelled
buildings: the parts the engine builds each from (models.json) and the pictures they wear, as buildings_apt.py makes the
apartment blocks. `compose_facade(spec)` is the generator's entry point; a spec is

    {id, kit: brick|blue|grey, widthTiles, ground: {kind: shop|condo, bays: [motif ids]}, floors: [[motif ids]] | N,
     roof: {style: flat, colour: teal|gold|stone}, corner: null, era, seed}

(docs/PACK_EXTERIORS.md). Every row of motifs is laid side by side (a motif is a run of whole tiles cut from the pack, so a
row is `widthTiles` tiles), its two ends covered by the generated 3 px pilaster caps, 1-tile plain-wall fillers where a
motif does not fit; the rows are stacked (the ground floor under the middle floors) into one front picture, which the engine
wears on a box like the apartment blocks'. The roof is a flat roof inside a parapet (apt.flat_roof): the parapet's front is
the top floor's cornice, its inside the roof slices' inner face, its copings the slices' top caps, the floor the slices'
gravel. The side wall carries each floor's wall colours and windows (make_buildings.side_segment). All pictures are cut from
the pack, which is not in the repo: set PACK_ART (see kits.py)."""
import json, os, zlib
import numpy as np
from PIL import Image
import kits
import make_buildings as mb
import buildings_apt as apt
from make_buildings import Atlas, mbox, shade, solid, rgb_int, column_rows

KJ = json.load(open(os.path.join(kits.KITDIR, 'kits.json')))['kits']
DEPTH = 120                                   # the box's depth (as the shops'), sprite pixels
PARAPET = 14                                  # the roof parapet's height: the cornice rows of the top floor, the slices' inner face rows
COPING = 10                                   # the copings' width (the slices' top cap rows)
PALS = ('brick', 'blue', 'grey')
DEFAULT_ROOF = {'brick': 'teal', 'blue': 'gold', 'grey': 'stone'}


def _tile(name):
    return kits.tile_image(name, None)


def motifs_of(kit, kind):
    return KJ[kit]['middle']['motifs'] if kind == 'mid' else KJ[kit]['ground'][kind]['motifs']


def compose_row(kit, kind, ids):
    """A row of motifs (and 'wall' fillers) as one picture, its ends capped. Returns the picture and the x span of its first door."""
    motifs = motifs_of(kit, kind)
    imgs, x, door = [], 0, None
    for i in ids:
        if i == 'wall': im = _tile(f'{kit}_{kind}_filler')
        else:
            m = motifs[i]; im = kits.motif_cut(m)
            if m.get('door') and door is None: door = (x, x + im.shape[1])
        imgs.append(im); x += im.shape[1]
    row = np.ascontiguousarray(np.concatenate(imgs, axis=1))
    row[:, :3] = _tile(f'{kit}_{kind}_capl'); row[:, -3:] = _tile(f'{kit}_{kind}_capr')
    return row, door


def row_tiles(kit, kind, ids):
    motifs = motifs_of(kit, kind)
    return sum(1 if i == 'wall' else motifs[i]['tiles'] for i in ids)


def auto_floor(kit, W, rng, pattern=None):
    """A middle floor's row for W tiles: seeded motifs by weight (pairs), a filler tile in a seeded place if W is odd."""
    motifs = KJ[kit]['middle']['motifs']
    names = list(motifs); w = np.array([motifs[n]['weight'] for n in names], float); w /= w.sum()
    n = W // 2
    row = [pattern[k % len(pattern)] if pattern else names[rng.choice(len(names), p=w)] for k in range(n)]
    if W % 2: row.insert(int(rng.integers(0, n + 1)), 'wall')
    return row


def auto_ground(kit, kind, W, rng):
    """A ground row: shops are a 5-tile display then a 2-tile door (then fillers or more displays); condos a seeded door among slice motifs."""
    motifs = KJ[kit]['ground'][kind]['motifs']
    if kind == 'shop':
        # every 7-tile slot is one of the signature pairs (a 5-tile display and a 2-tile door) or a whole front of another kind (bakery,
        # butchery, gym, music store: kits.py), never the same slot twice in a building while others remain; one slot in twelve is shut
        # (shutters down) for a dead shop
        kinds = sorted({m['kind7'] for m in motifs.values() if m.get('kind7')})
        pool = [('pair', i) for i in range(5)] + [('kind', k) for k in kinds] * 2
        def slot():
            c = pool.pop(int(rng.integers(0, len(pool)))) if pool else ('pair', int(rng.integers(0, 5)))
            if c[0] == 'pair': return [f'disp{c[1]}', f'door{c[1]}']
            opts = [m for m in motifs if motifs[m].get('kind7') == c[1] and not motifs[m].get('closed')]
            shut = [m for m in motifs if motifs[m].get('kind7') == c[1] and motifs[m].get('closed')]
            return [shut[int(rng.integers(0, len(shut)))] if (shut and rng.random() < .08) else opts[int(rng.integers(0, len(opts)))]]
        if W < 7:
            k = int(rng.integers(0, 5))
            return ([f'disp{k}'] + ['wall'] * (W - 5)) if W >= 5 else ([f'door{k}'] + ['wall'] * (W - 2))
        row = slot(); left = W - 7
        while left >= 7:
            row += slot(); left -= 7
        while left >= 5:
            row.insert(0, f'disp{int(rng.integers(0, 5))}'); left -= 5
        row += ['wall'] * left
        return row
    return auto_condo(kit, W, rng)


CONDO_GAP = 6          # the least plain wall between two features of a condo ground (and at its ends), pixels


def auto_condo(kit, W, rng):
    """A condo ground's features for W tiles: a door and, on either side in turn, the windows of the same style (kits.py
    CONDO_ELEMENTS: each full condo floor is a style), as many as fit with plain wall between them. A 7-tile ground is the
    pack's own full floor half the time ('full1', 'full3', 'full4')."""
    ms = KJ[kit]['ground']['condo']['motifs']
    # (not the second: its ATM belongs to a bank's front, not a home's or a hotel's)
    if W == 7 and rng.random() < 0.5: return [f'full{(1, 3, 4)[int(rng.integers(0, 3))]}']
    inner = W * 32 - 6
    doors = [d for d in ms if ms[d]['door'] and ms[d]['px'] + 2 * CONDO_GAP <= inner] or [min((d for d in ms if ms[d]['door']), key=lambda d: ms[d]['px'])]
    door = doors[int(rng.integers(0, len(doors)))]
    wins = [m for m in ms if not ms[m]['door'] and ms[m]['weight'] > 0 and ms[m]['family'] == ms[door]['family']]
    left, right, used = [], [], ms[door]['px']
    first_right = rng.random() < 0.5
    while wins:
        pick = wins[int(rng.integers(0, len(wins)))]
        n = len(left) + len(right) + 1
        if used + ms[pick]['px'] + CONDO_GAP * (n + 2) > inner: break
        to_right = (len(right) < len(left)) or (len(right) == len(left) and first_right)
        (right if to_right else left).append(pick); used += ms[pick]['px']
    return left[::-1] + [door] + right


def compose_condo(kit, ids, W):
    """A condo ground of W tiles: the plain condo wall with the features [ids] laid on it whole, spaced evenly, the ends capped
    (or a pack full floor, ['fullK']). Returns the picture and the door's x span."""
    p = PALS.index(kit)
    if len(ids) == 1 and ids[0].startswith('full'):
        assert W == 7, f'a full condo floor is 7 tiles, not {W}'
        k = int(ids[0][4:]); row = kits.piece('fm', f'Ground_Floor_Condo_{4 * p + k}').copy()
        door = next((kits.CONDO_ELEMENTS[d][1:3] for d in kits.CONDO_DOORS if kits.CONDO_ELEMENTS[d][0] == k), None)
        return np.ascontiguousarray(row), door
    ms = KJ[kit]['ground']['condo']['motifs']
    PX = W * 32
    fill = _tile(f'{kit}_condo_filler')
    row = np.ascontiguousarray(np.concatenate([fill if i % 2 == 0 else fill[:, ::-1] for i in range(W)], axis=1))
    pics = [kits.motif_cut(ms[i]) for i in ids]
    inner = PX - 6; used = sum(a.shape[1] for a in pics)
    assert used <= inner, f'condo ground {ids} ({used} px) does not fit {W} tiles'
    gaps = len(pics) + 1; spare = inner - used
    x = 3 + spare // gaps + (spare % gaps) // 2; door = None
    for i, a in zip(ids, pics):
        row[:, x:x + a.shape[1]] = a
        if ms[i].get('door') and door is None: door = (x, x + a.shape[1])
        x += a.shape[1] + spare // gaps
    row[:, :3] = _tile(f'{kit}_condo_capl'); row[:, -3:] = _tile(f'{kit}_condo_capr')
    return row, door


def roof_pieces(kit, colour, W):
    """The flat roof's pictures from the slices: (back_top, back_face, front_top, floor, left_top, right_top, colour)."""
    spec = KJ[kit]['roof']['flat'][colour]; rr = spec['rows']
    cols = np.concatenate([kits.piece('fm', spec['columns'][i % 2]) for i in range(W)], axis=1)
    cols = np.ascontiguousarray(cols[:, :W * 32])
    ids = KJ[kit]['roof']['corners'][colour]
    bar_l = kits.piece('fm', f'Roof_Modular_{ids["left"]}')[:, 2:2 + COPING]
    bar_r = kits.piece('fm', f'Roof_Modular_{ids["right"]}')[:, 32 - 2 - COPING:30]
    side_len = DEPTH - 2 * COPING
    tile_v = lambda a: np.ascontiguousarray(np.tile(a, (side_len // a.shape[0] + 1, 1, 1))[:side_len])
    floor = np.ascontiguousarray(cols[rr['floor'][0]:rr['floor'][0] + DEPTH, :])
    return dict(back_top=cols[rr['back_top'][0]:rr['back_top'][1]].copy(), back_face=cols[rr['back_face'][0]:rr['back_face'][0] + PARAPET].copy(),
                front_top=cols[rr['front_top'][0]:rr['front_top'][1]].copy(), floor=floor,
                left=tile_v(bar_l), right=tile_v(bar_r), colour=tuple(int(v) for v in np.median(floor[:, 8:-8].reshape(-1, 4), axis=0)[:3]))


def compose_facade(spec, shop=None, rng=None):
    """One modular building from its spec: the model (models.json entry), its atlas, front and side pictures, and the lot.
    `depth` (sprite pixels, default DEPTH) makes a deeper box: the downtown's towers and hotels."""
    global DEPTH
    old = DEPTH
    DEPTH = int(spec.get('depth') or DEPTH)
    try: return _compose(spec, shop, rng)
    finally: DEPTH = old


def _compose(spec, shop, rng):
    kit = spec['kit']
    if kit == 'parking': return compose_parking(spec, shop)
    if kit == 'condo12': return compose_columns(spec, shop)
    if kit == 'condo7': return compose_tower(spec, shop)
    if kit == 'office': return compose_office(spec, shop)
    if kit.startswith('c4_'): return compose_corner(spec, shop)
    if kit in ('b3_46', 'b8', 'b6'): return compose_block(spec, shop)
    if kit in CIVIC_KITS:
        import buildings_civic
        return buildings_civic.compose_civic(spec, shop)
    W = int(spec['widthTiles'])
    seed = zlib.crc32(spec['id'].encode()) + int(spec.get('seed', 0))
    r = np.random.default_rng(seed)
    gk = spec.get('ground', {}).get('kind', 'condo')
    pic_key = spec.get('ground', {}).get('picture')
    if pic_key: gk = 'shop'
    g_ids = ['wall'] * W if pic_key else (spec.get('ground', {}).get('bays') or auto_ground(kit, gk, W, r))
    floors = spec.get('floors', 2)
    if isinstance(floors, int): floors = [auto_floor(kit, W, r) for _ in range(floors)]
    floors = [f if isinstance(f, list) else auto_floor(kit, W, r, f.get('pattern', '').split(',') if f.get('pattern') else None) for f in floors]
    if gk != 'condo': assert row_tiles(kit, gk, g_ids) == W, f'{spec["id"]}: ground row is {row_tiles(kit, gk, g_ids)} tiles, not {W}'
    for f in floors: assert row_tiles(kit, 'mid', f) == W, f'{spec["id"]}: a floor row is {row_tiles(kit, "mid", f)} tiles, not {W}'
    PX = W * 32
    ground, door = compose_condo(kit, g_ids, W) if gk == 'condo' and not pic_key else compose_row(kit, gk, g_ids)
    sign = None
    if pic_key:
        # the business's own storefront (the game's sprite, as the shops wear it): its bottom 96 rows the ground floor, any
        # sign over them a card standing proud of the floor above (as the box shops hang it)
        g = np.array(mb.load(pic_key))
        assert g.shape[1] == PX, f'{spec["id"]}: storefront {pic_key} is {g.shape[1]} px wide, not {PX}'
        ground = np.ascontiguousarray(g[-96:].copy()); door = (PX // 2 - 20, PX // 2 + 20)
        if g.shape[0] > 96 and (g[:-96, :, 3] > 200).any(): sign = np.ascontiguousarray(g[:-96].copy())
    mids = [compose_row(kit, 'mid', f)[0] for f in floors]
    # top to bottom: the top floor first, the ground last
    rows = mids[::-1] + [ground]
    front = np.ascontiguousarray(np.concatenate(rows, axis=0))
    H = front.shape[0]; Y = H - PARAPET
    front_face = front[:PARAPET].copy()
    body = front[PARAPET:].copy()
    # --- the side wall: each floor's wall colours and windows, as the shops' sides are made
    old = mb.D; mb.D = DEPTH
    try:
        sides = []
        for i, rw in enumerate(rows):
            if i < len(mids): seg, win = rw, True
            else: seg, win = np.ascontiguousarray(_tile(f'{kit}_{gk}_filler')), False     # the ground floor's wall: its own clean wall, not the shop display's
            sides.append(np.array(mb.side_segment(Image.fromarray(np.ascontiguousarray(seg)), windows=win)))
    finally: mb.D = old
    side = np.concatenate(sides, axis=0)[PARAPET:]
    # the box's back wears this picture too, repeated along a wide building: no corner boards in it, or every repeat shows a seam
    side[:, :8] = side[:, 8:16]; side[:, -8:] = side[:, -16:-8]
    roof = roof_pieces(kit, (spec.get('roof') or {}).get('colour') or DEFAULT_ROOF[kit], W)
    wall_c = tuple(int(v) for v in np.median(_tile(f'{kit}_mid_filler')[20:100].reshape(-1, 4), axis=0)[:3])
    ledges = [(k * 128 + 118, k * 128 + 128) for k in range(len(mids))]   # the sill course at the foot of each middle floor
    extras = ()
    if sign is not None:
        def hang(at, Y, H, PX, sign=sign):
            ys = np.nonzero((sign[..., 3] > 200).any(1))[0]; xs = np.nonzero((sign[..., 3] > 200).any(0))[0]
            pic = np.ascontiguousarray(sign[ys.min():ys.max() + 1, xs.min():xs.max() + 1])
            c = tuple(int(v) for v in np.median(pic[pic[..., 3] > 200][:, :3], axis=0))
            y0 = 96; y1 = 96 + (sign.shape[0] - ys.min())
            return [mbox((int(xs.min()), int(xs.max()) + 1), (y0, y1), (-4, -4), (c, c, c), ft=at.add(pic), fx=int(xs.min()), card=True)]
        extras = (hang,)
    return finish(spec, shop, r, front, PARAPET, COPING, roof, door, side, wall_c, ledges, extras)


# Roof props from theme 5's Roof_Props (cut: its pixels' own top and front): each a small box standing on the roof floor, its top the sprite's
# top rows seen from above (one per pixel of depth), its front the rest. (name: (file, top rows, front rows, columns))
ROOF_PROPS = {
    'stair': ('Roof_Props_1', (17, 70), (73, 118), (33, 159)),     # a stair housing: teal roof with two vents and a fan, a brick front with its door
    'ac1': ('Roof_Props_6', (5, 28), (30, 62), (1, 60)),           # an air-conditioning unit
    'ac2': ('Roof_Props_7', (1, 38), (40, 62), (1, 62)),
    'boiler': ('Roof_Props_8', (26, 48), (50, 92), (7, 55)),       # a grey plant cabinet
}


def roof_prop_parts(at, Y, r, PX, depth_in=DEPTH, count=None):
    """Two or three props on a flat roof (floor at height Y, `PX` wide): picked and placed from `r`, left to right with gaps, back half of
    the roof so the parapet's front hides little of them. Returns the parts."""
    names = list(ROOF_PROPS); n = int(r.integers(1, 4)) if count is None else count
    picks = [names[int(i)] for i in r.permutation(len(names))[:n]]
    parts = []; x = COPING + 8 + int(r.integers(0, 16))
    for nm in picks:
        f, (t0, t1), (f0, f1), (c0, c1) = ROOF_PROPS[nm]
        art = kits.piece('fm', f); w = c1 - c0
        if x + w > PX - COPING - 6: continue
        top = np.ascontiguousarray(art[t0:t1, c0:c1]); front = np.ascontiguousarray(art[f0:f1, c0:c1])
        front = front.copy(); front[..., 3] = 255; top = top.copy(); top[..., 3] = 255
        d0 = 28 + int(r.integers(0, max(1, depth_in - 2 * COPING - (t1 - t0) - 40)))
        fc = tuple(int(v) for v in np.median(front.reshape(-1, 4), axis=0)[:3]); tc = tuple(int(v) for v in np.median(top.reshape(-1, 4), axis=0)[:3])
        side = np.ascontiguousarray(np.repeat(front[:, w // 2:w // 2 + 1], t1 - t0, axis=1))
        parts.append(mbox((x, x + w), (Y, Y + (f1 - f0)), (d0, d0 + (t1 - t0)), (tc, fc, shade(fc, .8)), ft=at.add(front), fx=x, tt=at.add(top), st=at.add(side)))
        x += w + 14 + int(r.integers(0, 40))
    return parts


def finish(spec, shop, r, front, P, T, roof, door, side, wall_c, ledges=(), extras=(), lit=True):
    """The model of a box whose front picture is `front` (its first P rows the parapet's front face: the top floor's
    cornice), under a flat roof made of `roof`'s pictures, wearing `side` (the body's side, already without the parapet rows).
    `ledges` are rows of the front that stand 3 px out of the wall; `extras(at, Y, H, PX)` returns more parts (roof props...)."""
    PX = front.shape[1]; H = front.shape[0]; Y = H - P
    front_face = front[:P].copy(); body = front[P:].copy()
    at = Atlas(512)
    parts, front_par = apt.flat_roof(at, None, 0, PX, Y, H, DEPTH, T, front_face, roof['back_face'],
                                     (roof['back_top'], roof['front_top'], (roof['left'], roof['right'])))
    b_panes, b_glow = strict_panes(body) if lit else ([], np.zeros_like(body))
    body_r = at.add(body, b_glow)
    side_r = at.add(np.ascontiguousarray(side))
    parts.insert(0, mbox((2, PX - 2), (0, Y), (0, DEPTH), (roof['colour'], wall_c, shade(wall_c, .8)), ft=body_r, fx=0, tt=at.add(np.ascontiguousarray(roof['floor'][:, 2:PX - 2])), st=side_r, panes=b_panes))
    # what stands out of the wall: the sill courses (3 px), as the shops' ledges
    el = apt.Elev(front, [(P, H)])
    for (r0, r1) in ledges:
        parts.append(apt.relief(at, el, 0, PX, r0, r1, -3))
    for ex in extras: parts += ex(at, Y, H, PX)
    if spec.get('props', True): parts += roof_prop_parts(at, Y, np.random.default_rng(zlib.crc32(spec['id'].encode()) + 99), PX)
    parts.append(front_par)
    d0, d1 = (door[0] + 6, door[1] - 6) if door else (PX // 2 - 8, PX // 2 + 8)
    shop_d = shop or dict(yard=dict(front=1, style='compact'))
    setback, style, lawn, fence, fence_snow = apt.lot(shop_d, r, PX, DEPTH, 8, (d0, d1, 8), 'paving')
    model = apt.model_of('apartment', PX, setback, style, parts, roof['colour'], 'home', 'paving', fence, lamp=[(d0 + d1) / 2, 78, -3])
    emis = np.zeros((H, PX, 4), np.uint8); emis[P:] = b_glow
    return model, at, front, side, emis, lawn, fence, fence_snow



def _rng(spec):
    return np.random.default_rng(zlib.crc32(spec['id'].encode()) + int(spec.get('seed', 0)))


def _side_of(body):
    """A box's side from its front body (windows kept), without corner boards (the back repeats it along a wide building)."""
    old = mb.D; mb.D = DEPTH
    try: side = np.array(mb.side_segment(Image.fromarray(np.ascontiguousarray(body)), windows=True))
    finally: mb.D = old
    side[:, :8] = side[:, 8:16]; side[:, -8:] = side[:, -16:-8]
    return side


def _tile_v(a, n):
    return np.ascontiguousarray(np.tile(a, (n // a.shape[0] + 1, 1, 1))[:n])


def compose_columns(spec, shop=None):
    """A row of Condo_1/2 columns (terrace / row-house system: tall 384 = 4 floors + roof, short 320 = 3 floors + roof; each
    column 96, 64 or 32 px wide; a pilaster on its left, the last one's right closed by the mirrored pilaster)."""
    c = spec['colour']; tall = spec.get('height', 'tall') == 'tall'; R = 0 if tall else 8
    ids = spec['columns']
    img = np.ascontiguousarray(np.concatenate([kits.piece('gb', f'Condo_{c}_{i}') for i in ids], axis=1))
    PX = img.shape[1]; P, T = 12, 6
    front = np.ascontiguousarray(img[R + 124:].copy())
    front[:, PX - 8:] = np.flip(front[:, :8], axis=1)
    floor = np.ascontiguousarray(img[R + 14:R + 14 + DEPTH])
    side_len = DEPTH - 2 * T
    bar = img[R + 30:R + 110, 2:8]
    roof = dict(back_top=img[R + 2:R + 8].copy(), back_face=img[R + 8:R + 20].copy(), front_top=img[R + 114:R + 124].copy(), floor=floor,
                left=_tile_v(bar, side_len), right=_tile_v(np.flip(bar, axis=1), side_len),
                colour=tuple(int(v) for v in np.median(floor[30:90, 12:-12].reshape(-1, 4), axis=0)[:3]))
    x, door = 0, None
    for i in ids:
        w = kits.piece('gb', f'Condo_{c}_{i}').shape[1]
        if w == 96 and door is None: door = (x + 30, x + 66)
        x += w
    wall_c = tuple(int(v) for v in front[120, 20][:3])
    # (the side from a plain window column: the door's dark recess and steps would colour its foot)
    side = _side_of(np.ascontiguousarray(kits.piece('gb', f'Condo_{c}_{4 if tall else 9}')[R + 124 + P:]))
    return finish(spec, shop, _rng(spec), front, P, T, roof, door, side, wall_c)


def compose_tower(spec, shop=None):
    """Condo_7: a 3-tile-wide tower, N floors of Middle with a Window sprite laid over each, the Bottom's door and steps (the
    steps as boxes), the Top's flat roof with its air unit as a card."""
    n = int(spec.get('floors', 3)); wins = spec.get('windows') or [1 + (k * 2 + int(spec.get('seed', 0))) % 5 for k in range(n)]
    top = kits.piece('gb', 'Condo_7_Modular_Top'); mid = kits.piece('gb', 'Condo_7_Modular_Middle'); bot = kits.piece('gb', 'Condo_7_Modular_Bottom')
    P, T = 8, 6
    rows = [top[164:224]]
    for k in range(n):
        m = Image.fromarray(np.ascontiguousarray(mid)); m.alpha_composite(Image.fromarray(kits.piece('gb', f'Condo_7_Modular_Window_{wins[k % len(wins)]}')))
        rows.append(np.array(m))
    rows.append(bot[:128])
    front = np.ascontiguousarray(np.concatenate([top[150:158]] + rows, axis=0)); PX = 96
    floor = np.ascontiguousarray(top[38:38 + DEPTH, :])
    side_len = DEPTH - 2 * T
    roof = dict(back_top=top[36:42].copy(), back_face=top[42:50].copy(), front_top=top[136:150].copy(), floor=floor,
                left=_tile_v(top[60:130, 4:10], side_len), right=_tile_v(top[60:130, 86:92], side_len),
                colour=tuple(int(v) for v in np.median(top[60:130, 26:76].reshape(-1, 4), axis=0)[:3]))
    wall_c = tuple(int(v) for v in mid[30, 20][:3])
    wallrows = np.concatenate([front[P:P + 60 + 64 * n]] + [np.concatenate([mid, mid], axis=0)], axis=0)
    side = _side_of(wallrows)[:front.shape[0] - P]
    steps = bot[128:158]; sx = np.nonzero((steps[..., 3] > 200).any(0))[0]
    def extras(at, Y, H, PX):
        out = []
        top_c, riser_c = (0xb6, 0xb8, 0xc0), (0x7e, 0x7d, 0x86)
        x0, x1 = (int(sx.min()), int(sx.max()) + 1) if len(sx) else (24, 72)
        for i in range(3):
            out.append(mbox((x0, x1), (0, 9 - 3 * i), (-(i + 1) * 9, -i * 9), (top_c, riser_c, shade(riser_c, .85))))
        air = kits.piece('gb', 'Condo_7_Roof_Air_Unit')
        c = tuple(int(v) for v in np.median(air[air[..., 3] > 200][:, :3], axis=0))
        out.append(mbox((8, 72), (Y, Y + 64), (70, 70), (c, c, c), ft=at.add(np.ascontiguousarray(air)), fx=8, card=True))
        return out
    return finish(spec, shop, _rng(spec), front, P, T, roof, (30, 66), side, wall_c, (), (extras,))


def office_middles(st, g):
    """The office style's upper-floor middle (192 tall, 96 wide) and its lobby middle (96 tall). Styles 1 and 2 have both; style 3 has no
    lobby middle (its lobby is the barred shutter of its Left_2/Right_2: a run of that shutter, its door-less shopfront, built from the
    Left_2's own columns) and style 4 no 192 middle (two floors, each the cornice band of its Left over its 96 wide Window_4_Middle_Modular)."""
    nm = lambda n: f'Building_{st}_{n}'
    try: up = g(nm('Middle_Modular'))
    except SystemExit: up = None
    try: lob = g(nm('Middle_Modular_2'))
    except SystemExit: lob = None
    if lob is None:
        l2 = g(nm('Left_2')); lob = np.ascontiguousarray(np.tile(l2[:, 8:56], (1, 2, 1)))
    if up is None:
        left = g(nm('Left')); band = np.ascontiguousarray(np.tile(left[:32, 4:60], (1, 2, 1))[:, :96])
        floor = np.concatenate([band, g(f'Window_{st}_Middle_Modular')], axis=0)
        up = np.ascontiguousarray(np.concatenate([floor, floor], axis=0))
    return up, lob


def compose_office(spec, shop=None):
    """An office tower (theme 16 styles 1 and 2): a ground row (Left_2 + k x Middle_2 + Right_2, 96 tall: the lobby with its
    curtains) under n blocks of two floors (Left + k x Middle + Right, 192 tall), width 4 + 3k tiles; the Roof_Left/Middle/Right
    flat roof on top."""
    st = int(spec.get('style', 1)); k = int(spec.get('mid', 1)); n = int(spec.get('blocks', 2))
    g = lambda nm: kits.piece('of', nm)
    def row(parts): return np.ascontiguousarray(np.concatenate(parts, axis=1))
    mid_up, mid_lobby = office_middles(st, g)
    upper = row([g(f'Building_{st}_Left')] + [mid_up] * k + [g(f'Building_{st}_Right')])
    lobby = row([g(f'Building_{st}_Left_2')] + [mid_lobby] * k + [g(f'Building_{st}_Right_2')])
    front = np.ascontiguousarray(np.concatenate([upper] * n + [lobby], axis=0)); PX = front.shape[1]
    P, T = 14, 10
    rl, rm, rr = g('Roof_Left'), g('Roof_Middle_Modular'), g('Roof_Right')
    roofpic = row([rl] + [rm] * k + [rr])
    floor = _tile_v(roofpic[82:98], DEPTH)
    side_len = DEPTH - 2 * T
    roof = dict(back_top=roofpic[16:26].copy(), back_face=roofpic[50:64].copy(), front_top=roofpic[140:150].copy(), floor=floor,
                left=_tile_v(rl[100:116, 2:12], side_len), right=_tile_v(rr[100:116, 52:62], side_len),
                colour=tuple(int(v) for v in np.median(roofpic[70:130, 20:-20].reshape(-1, 4), axis=0)[:3]))
    wall_c = tuple(int(v) for v in front[20, 20][:3])
    # the side: the wall's rows (plain columns at the piece's edge), a ribbon window of the style on each floor
    blocks = np.concatenate([upper] * n + [lobby], axis=0)
    rows = column_rows(blocks, [4, 5, 6, 7, 8, 9])
    rows[-96:] = rows[96:192]                                         # (the lobby's foot in the floors' wall, not its curtains' brown)
    win = g(f'Window_{st}_Middle_Modular') if st in (1, 2, 3, 4) else g(f'Building_{st}_Window_Modular')
    wins = [(win, 192 * i + 40 + off, (.5,), strict_panes(win)[0]) for i in range(n) for off in (0, 96)]
    side, _ = apt.side_windows(rows, DEPTH, wins, _rng(spec), tuple(int(v) for v in wall_c) + (255,))
    side[:, :8] = side[:, 8:16]; side[:, -8:] = side[:, -16:-8]
    side = side[P:]
    ledges = [(192 * i + 184, 192 * i + 192) for i in range(n)]
    return finish(spec, shop, _rng(spec), front, P, T, roof, (PX // 2 - 30, PX // 2 + 30), side, wall_c, ledges)


# ------------------------------------------------------------------------------------------------ item 4: the red-brick corner blocks
# Condo_4_38/39 (a four-storey block whose corner is a prism: a chamfer forward from the wing, the entrance bay across, a chamfer
# back to the end), Condo_4_40 (the octagonal tower, only its front three faces drawn), and Condo_6, Condo_3_46, Condo_8 as one
# or two boxes. The art draws the roof from above in its top rows (one row per pixel of depth, so those rows give the depth), the
# front's parapet and cornice below them, then the elevation down to its foot. A chamfered face is drawn sheared (its foot falls
# or rises one row per two columns, 30 rows over the corner: the plan's depth is that many pixels), so each column is taken
# level by its own step (as the police station's tower) and the face stands as a `wall` between two plan points.
CORNERS = {
    # key: (art, wing x span, prism x span, faces (x0, x1, d0, d1), roof rows, elevation top row, wing foot, door, mirrored)
    'c4_38': dict(art='Condo_4_38', wing=(0, 320), prism=(320, 510), faces=[(320, 384, 0, -30), (384, 446, -30, -30), (446, 510, -30, 0)],
                  plan=[(320, 0), (384, -30), (446, -30), (510, 0)], roof=208, foot=543, door=(394, 436)),
    'c4_39': dict(art='Condo_4_39', wing=(192, 512), prism=(2, 192), faces=[(2, 66, 0, -30), (66, 128, -30, -30), (128, 192, -30, 0)],
                  plan=[(2, 0), (66, -30), (128, -30), (192, 0)], roof=208, foot=543, door=(76, 118)),
}
CLEAN = {'c4_38': [40, 56], 'c4_39': [200, 216]}                 # plain brick columns for the walls' rows


def _foot(a, x):
    return int(np.nonzero(a[:, x, 3] == 255)[0].max())


def _level(a, x0, x1, top, E, base, clip=30, sign=1):
    """The columns x0..x1 of a sheared face let down to a level foot: column x starts `step` rows lower (step = its foot's offset)."""
    cols = []
    for x in range(x0, x1):
        s = int(min(max((_foot(a, x) - base) * sign, 0), clip))
        cols.append(a[top + s * (1 if sign > 0 else -1):top + s * (1 if sign > 0 else -1) + E, x])
    return np.ascontiguousarray(np.stack(cols, axis=1))


def _wall_rows(img, cols):
    return column_rows(img, cols)


def _brick_side(img, cols, depth, wins, rng, wall_c):
    side, glow = apt.side_windows(_wall_rows(img, cols), depth, wins, rng, tuple(int(v) for v in wall_c) + (255,))
    side[:, :4] = side[:, 4:8]; side[:, -4:] = side[:, -8:-4]
    return side, glow


def compose_corner(spec, shop=None):
    from buildings_civA import prism as mprism
    from buildings_civB import mwall
    cfg = CORNERS[spec['kit']] if spec['kit'] in CORNERS else None
    if spec['kit'] == 'c4_40': return compose_octagon(spec, shop)
    a = kits.piece('gb', cfg['art']); rng = _rng(spec)
    R = cfg['roof']; foot = cfg['foot']; E = foot - R; W = 512; D = R
    w0, w1 = cfg['wing']; p0, p1 = cfg['prism']
    at = Atlas(512)
    elev = np.ascontiguousarray(a[R:foot, w0:w1].copy())
    wall_c = tuple(int(v) for v in elev[200, (CLEAN[spec['kit']][0] - w0)][:3])
    roof_c = tuple(int(v) for v in np.median(a[40:160, p0 + 20:p0 + 90].reshape(-1, 4), axis=0)[:3])
    # a window of the front for the sides: its picture, frame and awning (x 62..160 of the wing's elevation), one per storey
    wx = 62 - w0 if spec['kit'] == 'c4_38' else 252 - w0
    pic = elev[48:122, wx:wx + 98]
    wins = [(pic, wy, (.3, .7), strict_panes(pic)[0]) for wy in (48, 138, 228)]
    side, side_g = _brick_side(elev, [c - w0 for c in CLEAN[spec['kit']]], D, wins, rng, wall_c)
    side_r = at.add(np.ascontiguousarray(side), side_g)
    panes, glow = strict_panes(elev)
    wing = mbox((w0 + (2 if w0 == 0 else 0), w1 - (0 if w0 == 0 else 2)) if False else (w0, w1), (0, E), (0, D), (roof_c, wall_c, shade(wall_c, .8)), ft=at.add(elev, glow), fx=w0,
                tt=at.add(np.ascontiguousarray(a[0:R, w0:w1])), st=side_r, panes=panes)
    pr_front = at.add(np.ascontiguousarray(elev[:, :64]))
    prow = mbox((p0, p1), (0, E), (0, D), (roof_c, wall_c, shade(wall_c, .8)), ft=pr_front, fx=p0, tt=at.add(np.ascontiguousarray(a[0:R, p0:p1])), st=side_r)
    wedge = mprism(cfg['plan'], (0, E), (roof_c, wall_c, wall_c), tt=at.add(np.ascontiguousarray(a[R:R + 30, p0:p1])), hide=(0, 1, 2, 3))
    parts = [wing, prow, wedge]
    for (x0, x1, d0, d1) in cfg['faces']:
        f = _level(a, x0, x1, R, E, foot)
        fp, fg = strict_panes(f)
        w = mwall((x0, x1), (d0, d1), (0, E), wall_c, ft=at.add(f, fg))
        if fg[..., 3].any(): w['sg'] = True
        parts.append(w)
    d0_, d1_ = cfg['door']
    setback, style, lawn, fence, fence_snow = apt.lot(shop or dict(yard=dict(front=1, style='compact')), rng, W, D, 8, (d0_, d1_, 8), 'paving')
    model = apt.model_of('apartment', W, setback, style, parts, roof_c, 'home', 'paving', fence, lamp=[(d0_ + d1_) / 2, 90, -33])
    emis = np.zeros_like(elev); emis[:] = glow
    return model, at, elev, side, emis, lawn, fence, fence_snow


def compose_octagon(spec, shop=None):
    """Condo_4_40: the octagonal tower: the front three faces from the art (the chamfers let down to a level foot), the other
    five a brick wall with the front's windows, an octagon roof from above on top."""
    from buildings_civA import prism as mprism
    from buildings_civB import mwall
    a = kits.piece('gb', 'Condo_4_40'); rng = _rng(spec)
    R, base = 172, 507; E = base - R; D = 172
    at = Atlas(512)
    roof_c = tuple(int(v) for v in np.median(a[60:140, 70:120].reshape(-1, 4), axis=0)[:3])
    wall_c = tuple(int(v) for v in a[300, 100][:3])
    plan = [(64, 0), (128, 0), (190, 30), (190, 142), (128, 172), (64, 172), (2, 142), (2, 30)]
    faces = []
    mid = np.ascontiguousarray(a[R:base, 64:128])
    left = _level(a, 2, 64, R, E, base, sign=-1)
    right = _level(a, 128, 190, R, E, base, sign=-1)
    # (the chamfers' source rows run up, not down: the far end of a chamfer is higher)
    left = np.ascontiguousarray(np.stack([a[R - int(min(max(base - _foot(a, x), 0), 30)):R - int(min(max(base - _foot(a, x), 0), 30)) + E, x] for x in range(2, 64)], axis=1))
    right = np.ascontiguousarray(np.stack([a[R - int(min(max(base - _foot(a, x), 0), 30)):R - int(min(max(base - _foot(a, x), 0), 30)) + E, x] for x in range(128, 190)], axis=1))
    pic = right[40:110, 8:54]
    wins = [(pic, wy, (.5,), strict_panes(pic)[0]) for wy in (60, 150, 240)]
    sideA, sideG = _brick_side(mid, [2, 3, 4, 5], 112, wins, rng, wall_c)
    sideB, sideBG = _brick_side(mid, [2, 3, 4, 5], 64, [], rng, wall_c)
    parts = [mprism(plan, (0, E), (roof_c, wall_c, wall_c), tt=at.add(np.ascontiguousarray(a[0:R, 2:190])), hide=tuple(range(8)))]
    pics = [mid, right, sideA, sideB, sideB, sideB, sideA, left]
    for i in range(8):
        (x0, d0), (x1, d1) = plan[i], plan[(i + 1) % 8]
        fp, fg = strict_panes(pics[i])
        w = mwall((x0, x1), (d0, d1), (0, E), wall_c, ft=at.add(np.ascontiguousarray(pics[i]), fg if i in (0, 1, 7) else (sideG if pics[i] is sideA else None)))
        if (i in (0, 1, 7) and fg[..., 3].any()) or pics[i] is sideA: w['sg'] = True
        parts.append(w)
    setback, style, lawn, fence, fence_snow = apt.lot(shop or dict(yard=dict(front=1, style='compact')), rng, 192, D, 8, (72, 120, 8), 'paving')
    model = apt.model_of('apartment', 192, setback, style, parts, roof_c, 'home', 'paving', fence, lamp=[96, 90, -3])
    return model, at, mid, sideA, np.zeros_like(mid), lawn, fence, fence_snow


def strict_panes(pic, least=330):
    """The panes of a picture and where they glow, from clear blue glass only (the stone's lilac shades pass for glass by
    the shops' rule and would light the whole facade): blue clearly over red and over green."""
    a = pic.astype(int); r, g, b, al = (a[..., i] for i in range(4))
    m = (al > 200) & (b > r + 30) & (b > g + 6) & ((r + g + b) / 3 > 120)
    boxes = [(int(x0), int(y0), int(x1 - x0 + 1), int(y1 - y0 + 1), 1) for (x0, y0, x1, y1) in mb.components(mb.dilate(m, 1), 30)]
    return mb.hand_panes(pic, boxes, least)


def plain_roof(art, cop, face, floor_rows, PX, T, P, rng):
    """A roof of plain pieces: the coping and the parapet's inner face in the art's colours (medians of the given
    (rows, cols) boxes), a gravel floor in the median of its box, side copings."""
    med = lambda box: tuple(int(v) for v in np.median(art[box[0]:box[1], box[2]:box[3]].reshape(-1, 4), axis=0)[:3])
    c, f, g = med(cop), med(face), med(floor_rows)
    full = lambda col, h, w: np.dstack([np.full((h, w, 3), col, np.uint8), np.full((h, w, 1), 255, np.uint8)])
    floor = full(g, DEPTH, PX); floor[..., :3] = np.clip(floor[..., :3].astype(int) + rng.integers(-9, 10, (DEPTH, PX, 1)), 0, 255)
    return dict(back_top=full(c, T, PX), back_face=full(f, P, PX), front_top=full(c, T, PX), floor=floor,
                left=full(c, DEPTH - 2 * T, T), right=full(c, DEPTH - 2 * T, T), colour=g)


# Condo_8's three balconies (art rows: platform seen from above, then its railing; x span): painted out of the front with the wall's own
# row colours and built as geometry, a slab standing out 16 px with a railing round its three open sides.
C8_BALCONIES = [(44, 136, 242, 258, 260, 284), (20, 124, 318, 334, 337, 362), (40, 138, 400, 416, 418, 442)]
C8_TOP = 164                                       # the first row of Condo_8's elevation in the art (the roof's rows are above)
C8_FOOT = 502


def condo8_balconies(a, front, rng):
    front = front.copy(); mask = np.zeros(front.shape[:2], bool)
    for (x0, x1, s0, s1, r0, r1) in C8_BALCONIES:
        mask[s0 - 3 - C8_TOP:r1 + 4 - C8_TOP, x0 - 4:x1 + 4] = True
    front = mb.fill_rows(front, mask)
    def parts(at, Y, H, PX):
        out = []
        for (x0, x1, s0, s1, r0, r1) in C8_BALCONIES:
            top = np.ascontiguousarray(a[s0:s1, x0:x1]).copy(); top[..., 3] = 255
            tc = tuple(int(v) for v in np.median(top.reshape(-1, 4), axis=0)[:3])
            edge = tuple(int(v) for v in a[r1 - 2, (x0 + x1) // 2][:3]); bar = tuple(int(v) for v in np.median(a[r0 + 4:r0 + 12, x0 + 4:x1 - 4].reshape(-1, 4), axis=0)[:3])
            yf = C8_FOOT - r1 + 6; dp = s1 - s0
            out.append(mbox((x0, x1), (yf - 6, yf), (-dp, 0), (tc, edge, shade(edge, .8)), tt=at.add(top)))
            h = r1 - r0 - 6
            out.append(mb.mrail((x0, yf, -dp), (x1, yf, -dp), h, bar, posts=5, w=2))
            out.append(mb.mrail((x0, yf, -dp), (x0, yf, 0), h, bar, posts=8, w=2))
            out.append(mb.mrail((x1, yf, -dp), (x1, yf, 0), h, bar, posts=8, w=2))
        return out
    return front, parts


def compose_block(spec, shop=None):
    """Condo_3_46 (five storeys) and Condo_8 (the fire-escape tower): the elevation under a plain flat roof (their roofs are drawn
    with props and a stepped parapet: the props are not built), Condo_6 as two boxes (the block and its one-storey annex with its flat roof)."""
    k = spec['kit']; rng = _rng(spec)
    if k == 'b6': return compose_b6(spec, shop)
    if k == 'b3_46':
        a = kits.piece('gb', 'Condo_3_46'); PX = 224; P, T = 12, 10
        front = np.ascontiguousarray(a[178:570].copy())
        roof = plain_roof(a, (22, 28, 40, 180), (32, 38, 40, 180), (44, 160, 4, 220), PX, T, P, rng)
        wall_c = tuple(int(v) for v in a[330, 10][:3]); door = (92, 132); side = _side_of(front[P:])
    else:
        a = kits.piece('gb', 'Condo_8'); PX = 160; P, T = 12, 8
        front = np.ascontiguousarray(a[164:502].copy())
        roof = plain_roof(a, (31, 35, 20, 140), (37, 39, 20, 140), (70, 150, 40, 120), PX, T, P, rng)
        wall_c = tuple(int(v) for v in a[300, 10][:3]); door = (50, 110)
        front, balc_parts = condo8_balconies(a, front, rng)
        side = apt.side_windows(column_rows(front[P:], [140, 141, 142, 143, 144]), DEPTH, [], rng, wall_c + (255,))[0]
        side[:, :4] = side[:, 4:8]; side[:, -4:] = side[:, -8:-4]
    return finish(spec, shop, rng, front, P, T, roof, door, side, wall_c, lit=True, extras=[balc_parts] if k == 'b8' else ())


def compose_b6(spec, shop=None):
    """Condo_6: the four-storey block (arched windows) and, in front of its right part, the one-storey annex whose flat roof (the
    art's, from above, with its air-conditioning vents) stands level with the ground floor's top."""
    a = kits.piece('gb', 'Condo_6'); rng = _rng(spec); at = Atlas(512)
    R, G, GF = 236, 510, 394
    E = G - R; Ha = G - GF
    wall_c = tuple(int(v) for v in a[300, 12][:3]); roof_c = tuple(int(v) for v in np.median(a[100:200, 40:160].reshape(-1, 4), axis=0)[:3])
    main = np.ascontiguousarray(a[R:G, 6:208].copy()); annex = np.ascontiguousarray(a[GF:G, 208:400].copy())
    pm, gm = strict_panes(main); pa, ga = strict_panes(annex)
    rows = column_rows(main, [188, 189, 190, 191])
    side, side_g = apt.side_windows(rows, 206, [], rng, wall_c + (255,)); side[:, :4] = side[:, 4:8]; side[:, -4:] = side[:, -8:-4]
    side_r = at.add(np.ascontiguousarray(side))
    roof_a = at.add(np.ascontiguousarray(a[254:394, 208:400]))
    parts = [
        mbox((6, 208), (0, E), (0, 206), (roof_c, wall_c, shade(wall_c, .8)), ft=at.add(main, gm), fx=6, tt=at.add(np.ascontiguousarray(a[34:R, 6:208])), st=side_r, panes=pm),
        mbox((208, 400), (0, Ha), (0, 140), (roof_c, wall_c, shade(wall_c, .8)), ft=at.add(annex, ga), fx=208, tt=roof_a, st=side_r, panes=pa),
    ]
    setback, style, lawn, fence, fence_snow = apt.lot(shop or dict(yard=dict(front=1, style='compact')), rng, 416, 206, 8, (90, 160, 8), 'paving')
    model = apt.model_of('apartment', 416, setback, style, parts, roof_c, 'home', 'paving', fence, lamp=[125, 70, -3])
    emis = np.zeros_like(main); emis[:] = gm
    return model, at, main, side, emis, lawn, fence, fence_snow

def compose_parking(spec, shop=None):
    """Surface parking (a cleared downtown back lot): asphalt from the game's own tile with painted stalls across its width,
    a low kerb round it, a gap in the kerb at the front for the way in. `widthTiles` wide, DEPTH deep, 2 px high."""
    W = int(spec['widthTiles']) * 32; D = DEPTH; rng = _rng(spec); at = Atlas(512)
    tile = np.array(mb.load('asphalt_25'))[:32, :32]
    top = np.ascontiguousarray(np.tile(tile, (D // 32 + 1, W // 32 + 1, 1))[:D, :W])
    # a little wear: patched and oil-stained cells
    for _ in range(W * D // 2500):
        y, x = int(rng.integers(0, D - 12)), int(rng.integers(0, W - 12)); top[y:y + 10, x:x + 12, :3] = (top[y:y + 10, x:x + 12, :3] * .86).astype(np.uint8)
    paint = np.array([226, 222, 200, 255], np.uint8)
    # two rows of stalls facing an aisle down the middle: lines every 28 px (a stall), 2 px wide, 30% of the depth long
    L = int(D * .3)
    for x in range(10, W - 10, 28):
        top[6:6 + L, x:x + 2] = paint; top[D - 6 - L:D - 6, x:x + 2] = paint
    top[6 + L:6 + L + 2, 10:W - 10] = paint; top[D - 8 - L:D - 6 - L, 10:W - 10] = paint
    a_c = tuple(int(v) for v in np.median(top[..., :3].reshape(-1, 3), axis=0))
    kerb_c = (150, 148, 140)
    parts = [mbox((0, W), (0, 1), (0, D), (a_c, shade(a_c, .9), shade(a_c, .8)), tt=at.add(top))]
    gap0, gap1 = W // 2 - 40, W // 2 + 40
    for (x0, x1) in ((0, gap0), (gap1, W)):
        parts.append(mbox((x0, x1), (0, 4), (0, 4), (kerb_c, shade(kerb_c, .9), shade(kerb_c, .8))))
    parts.append(mbox((0, W), (0, 4), (D - 4, D), (kerb_c, shade(kerb_c, .9), shade(kerb_c, .8))))
    for x0 in (0, W - 4): parts.append(mbox((x0, x0 + 4), (0, 4), (4, D - 4), (kerb_c, shade(kerb_c, .9), shade(kerb_c, .8))))
    front = np.zeros((4, W, 4), np.uint8); front[..., :3] = kerb_c; front[..., 3] = 255
    side = np.zeros((4, D, 4), np.uint8); side[..., :3] = kerb_c; side[..., 3] = 255
    model = apt.model_of('apartment', W, 0, 'compact', parts, a_c, 'home', 'paving', None)
    lawn = np.zeros((1, W, 4), np.uint8)
    return model, at, front, side, np.zeros_like(front), lawn, None, None


CIVIC_KITS = ('v4', 'v5', 'v6', 'v1', 'v2', 'v3', 'v7', 'haunted', 'windmill', 'chapel', 'chapel_gate')
KEYS = ('pk_',)


def builder(key):
    """The builder for a pack sprite key (pk_...), or None."""
    if not key.startswith('pk_'): return None
    def make(shop, rng):
        return compose_facade(shop['spec'], shop, rng)
    return make


# ------------------------------------------------------------------------------------------------ the showcase
def showcase_specs():
    """About twelve varied buildings (2-6 storeys, 3-12 tiles wide, shop and condo grounds)."""
    S = lambda i, kit, W, gk, F, roof, seed=0, **k: dict(id=i, kit=kit, widthTiles=W, ground=dict(kind=gk, **k.get('g', {})), floors=F, roof=dict(style='flat', colour=roof), corner=None, era=[1950, 2029], seed=seed)
    return [
        S('pk_m01', 'brick', 7, 'shop', 2, 'teal'), S('pk_m02', 'brick', 10, 'condo', 3, 'teal', 3), S('pk_m03', 'blue', 8, 'shop', 4, 'gold', 5),
        S('pk_m04', 'grey', 6, 'condo', 5, 'stone', 7), S('pk_m05', 'blue', 12, 'shop', 2, 'gold', 11), S('pk_m06', 'grey', 9, 'condo', 3, 'stone', 2),
        S('pk_m07', 'brick', 5, 'shop', 6, 'teal', 4), S('pk_m08', 'blue', 4, 'condo', 3, 'gold', 9), S('pk_m09', 'grey', 14, 'shop', 2, 'stone', 6),
        dict(id='pk_c7a', kit='condo7', widthTiles=3, floors=3, seed=1), dict(id='pk_c7b', kit='condo7', widthTiles=3, floors=5, seed=4),
        dict(id='pk_o1a', kit='office', style=1, mid=1, blocks=2), dict(id='pk_o2a', kit='office', style=2, mid=2, blocks=3),
        dict(id='pk_o3a', kit='office', style=3, mid=2, blocks=2), dict(id='pk_o4a', kit='office', style=4, mid=1, blocks=3),
        dict(id='pk_b38', kit='c4_38', widthTiles=16), dict(id='pk_b39', kit='c4_39', widthTiles=16), dict(id='pk_b40', kit='c4_40', widthTiles=6),
        dict(id='pk_b346', kit='b3_46', widthTiles=7), dict(id='pk_b8', kit='b8', widthTiles=5), dict(id='pk_b6', kit='b6', widthTiles=13),
        dict(id='pk_c1a', kit='condo12', widthTiles=6, colour=1, height='tall', columns=[1, 3, 4, 5, 2]),
        dict(id='pk_c1b', kit='condo12', widthTiles=8, colour=2, height='short', columns=[6, 9, 8, 10, 7]),
    ]


def civic_specs():
    """The Victorian civic buildings, the other Additional Houses and the chapel (streets 5 and 6 of the pack showcase)."""
    return [dict(id='pk_' + k, kit=k, widthTiles=24) for k in CIVIC_KITS]


def showcase(out):
    """street.json + buildings/ for the pack's buildings: street 0 the facade kit's shops and condos, 1 the other kits."""
    shops, names = [], []
    cspecs = civic_specs(); nbase = len(showcase_specs())
    for sp in showcase_specs() + cspecs:
        shops.append(dict(id=f'show:{sp["id"]}', sprite=sp['id'], home=False, name=sp['id'], kind='Shop', owner='', colour='base',
                          parts=[dict(key=sp['id'], w=sp.get('widthTiles', 3) * 32, h=200, y=0)], yard=dict(front=1, style='compact'), spec=sp))
        names.append(sp['id'])
    # a street of scale: the game's own apartment blocks and a villa beside two of ours (street 3)
    scale = []
    for key in ('condo_3_45', 'pk_m01', 'villa_1', 'pk_m03', 'condo_5_1'):
        if key.startswith('pk_'): scale.append(key); continue
        im = mb.load(key); sp = 's_' + key
        shops.append(dict(id=f'show:{sp}', sprite=sp, home=True, name=key, kind='Home', owner='', colour='base', parts=[dict(key=key, w=im.width, h=im.height, y=0)], yard=dict(front=2, style='compact')))
        scale.append(sp)
    os.makedirs(out + '/buildings', exist_ok=True)
    json.dump(dict(town='pack', year=2025, type='pack showcase', shops=shops,
                   streets=[dict(name='Facade kit', buildings=names[:5]), dict(name='Facade kit 2', buildings=names[5:9]), dict(name='Towers and terraces', buildings=[n for n in names[9:nbase] if not n.startswith(('pk_b', 'pk_k'))]), dict(name='Red-brick corners and blocks', buildings=[n for n in names[:nbase] if n.startswith(('pk_b', 'pk_k'))]), dict(name='Scale against the game\'s own', buildings=scale),
                            dict(name='Victorian civic', buildings=names[nbase:nbase + 3]), dict(name='Victorian wings and landmarks', buildings=names[nbase + 3:nbase + 7]), dict(name='Other Additional Houses and chapel', buildings=names[nbase + 7:])]),
              open(out + '/street.json', 'w'), indent=1)
    return out + '/street.json', out + '/buildings'
