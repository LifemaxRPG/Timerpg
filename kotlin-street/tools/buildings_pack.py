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
        k = int(rng.integers(0, 5))
        if W < 7: return ([f'disp{k}'] + ['wall'] * (W - 5)) if W >= 5 else ([f'door{k}'] + ['wall'] * (W - 2))
        row = [f'disp{k}', f'door{k}']; left = W - 7
        while left >= 7:
            k = int(rng.integers(0, 5)); row += [f'disp{k}', f'door{k}']; left -= 7
        while left >= 5:
            row.insert(0, f'disp{int(rng.integers(0, 5))}'); left -= 5
        row += ['wall'] * left
        return row
    doors = [m for m in motifs if motifs[m].get('door')]
    plain = [m for m in motifs if not motifs[m].get('door') and motifs[m]['tiles'] == 2]
    row = [doors[int(rng.integers(0, len(doors)))]]; left = W - motifs[row[0]]['tiles']
    while left >= 2:
        row.insert(int(rng.integers(0, len(row) + 1)), plain[int(rng.integers(0, len(plain)))]); left -= 2
    row += ['wall'] * left
    return row


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
    """One modular building from its spec: the model (models.json entry), its atlas, front and side pictures, and the lot."""
    kit = spec['kit']
    if kit == 'condo12': return compose_columns(spec, shop)
    if kit == 'condo7': return compose_tower(spec, shop)
    if kit == 'office': return compose_office(spec, shop)
    W = int(spec['widthTiles'])
    seed = zlib.crc32(spec['id'].encode()) + int(spec.get('seed', 0))
    r = np.random.default_rng(seed)
    gk = spec.get('ground', {}).get('kind', 'condo')
    g_ids = spec.get('ground', {}).get('bays') or auto_ground(kit, gk, W, r)
    floors = spec.get('floors', 2)
    if isinstance(floors, int): floors = [auto_floor(kit, W, r) for _ in range(floors)]
    floors = [f if isinstance(f, list) else auto_floor(kit, W, r, f.get('pattern', '').split(',') if f.get('pattern') else None) for f in floors]
    assert row_tiles(kit, gk, g_ids) == W, f'{spec["id"]}: ground row is {row_tiles(kit, gk, g_ids)} tiles, not {W}'
    for f in floors: assert row_tiles(kit, 'mid', f) == W, f'{spec["id"]}: a floor row is {row_tiles(kit, "mid", f)} tiles, not {W}'
    PX = W * 32
    ground, door = compose_row(kit, gk, g_ids)
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
    return finish(spec, shop, r, front, PARAPET, COPING, roof, door, side, wall_c, ledges)


def finish(spec, shop, r, front, P, T, roof, door, side, wall_c, ledges=(), extras=()):
    """The model of a box whose front picture is `front` (its first P rows the parapet's front face: the top floor's
    cornice), under a flat roof made of `roof`'s pictures, wearing `side` (the body's side, already without the parapet rows).
    `ledges` are rows of the front that stand 3 px out of the wall; `extras(at, Y, H, PX)` returns more parts (roof props...)."""
    PX = front.shape[1]; H = front.shape[0]; Y = H - P
    front_face = front[:P].copy(); body = front[P:].copy()
    at = Atlas(512)
    parts, front_par = apt.flat_roof(at, None, 0, PX, Y, H, DEPTH, T, front_face, roof['back_face'],
                                     (roof['back_top'], roof['front_top'], (roof['left'], roof['right'])))
    b_panes, b_glow = apt.glass_panes(body)
    body_r = at.add(body, b_glow)
    side_r = at.add(np.ascontiguousarray(side))
    parts.insert(0, mbox((2, PX - 2), (0, Y), (0, DEPTH), (roof['colour'], wall_c, shade(wall_c, .8)), ft=body_r, fx=0, tt=at.add(np.ascontiguousarray(roof['floor'][:, 2:PX - 2])), st=side_r, panes=b_panes))
    # what stands out of the wall: the sill courses (3 px), as the shops' ledges
    el = apt.Elev(front, [(P, H)])
    for (r0, r1) in ledges:
        parts.append(apt.relief(at, el, 0, PX, r0, r1, -3))
    for ex in extras: parts += ex(at, Y, H, PX)
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


def compose_office(spec, shop=None):
    """An office tower (theme 16 styles 1 and 2): a ground row (Left_2 + k x Middle_2 + Right_2, 96 tall: the lobby with its
    curtains) under n blocks of two floors (Left + k x Middle + Right, 192 tall), width 4 + 3k tiles; the Roof_Left/Middle/Right
    flat roof on top."""
    st = int(spec.get('style', 1)); k = int(spec.get('mid', 1)); n = int(spec.get('blocks', 2))
    g = lambda nm: kits.piece('of', nm)
    def row(parts): return np.ascontiguousarray(np.concatenate(parts, axis=1))
    upper = row([g(f'Building_{st}_Left')] + [g(f'Building_{st}_Middle_Modular')] * k + [g(f'Building_{st}_Right')])
    lobby = row([g(f'Building_{st}_Left_2')] + [g(f'Building_{st}_Middle_Modular_2')] * k + [g(f'Building_{st}_Right_2')])
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
    wins = [(win, 192 * i + 40 + off, (.5,), apt.glass_panes(win)[0]) for i in range(n) for off in (0, 96)]
    side, _ = apt.side_windows(rows, DEPTH, wins, _rng(spec), tuple(int(v) for v in wall_c) + (255,))
    side[:, :8] = side[:, 8:16]; side[:, -8:] = side[:, -16:-8]
    side = side[P:]
    ledges = [(192 * i + 184, 192 * i + 192) for i in range(n)]
    return finish(spec, shop, _rng(spec), front, P, T, roof, (PX // 2 - 30, PX // 2 + 30), side, wall_c, ledges)


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
        dict(id='pk_c1a', kit='condo12', widthTiles=6, colour=1, height='tall', columns=[1, 3, 4, 5, 2]),
        dict(id='pk_c1b', kit='condo12', widthTiles=8, colour=2, height='short', columns=[6, 9, 8, 10, 7]),
    ]


def showcase(out):
    """street.json + buildings/ for the pack's buildings: street 0 the facade kit's shops and condos, 1 the other kits."""
    shops, names = [], []
    for sp in showcase_specs():
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
                   streets=[dict(name='Facade kit', buildings=names[:5]), dict(name='Facade kit 2', buildings=names[5:9]), dict(name='Towers and terraces', buildings=names[9:]), dict(name='Scale against the game\'s own', buildings=scale)]),
              open(out + '/street.json', 'w'), indent=1)
    return out + '/street.json', out + '/buildings'
