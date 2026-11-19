#!/usr/bin/env python3
"""The modular facade kits of LimeZu's Modern Exteriors (theme 5 Floor Modular Building, theme 4 Condo_7, Condo_1/2, theme
16 Office), as data: kits.json (next to this file) lists, per kit, the pieces a building is assembled from (each a motif: a
run of whole tiles cut from named pack pictures), and this script writes the two things the pack does not carry,
generated per palette from its own art: the edge pilaster caps (the full 7-wide floors have a 3 px pilaster at each end, the
48/32 px slices have none) and a plain-wall filler tile (so any width, odd ones too, can be filled).

The pack itself is not in the repo (licence: no redistribution). Point PACK_ART at its 32x32 theme sorter folder
(`.../Modern_Exteriors_32x32/ME_Theme_Sorter_32x32`, from `kotlin-street/packs/join.sh`). Usage:
    PACK_ART=... python3 kits.py            # (re)writes kits/kits.json and kits/*.png
    kits.piece('fm', 'Roof_Modular_1')      # a picture of the pack, as a numpy RGBA array
The pack is 32 px per tile (its 48 px sorter is an exact 1.5x upscale; this pipeline is 32 px)."""
import glob, json, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
KITDIR = os.path.join(HERE, 'kits')
TILE = 32
PACK = os.environ.get('PACK_ART') or '/tmp/claude-0/-home-user-Timerpg/f2eefd9e-16c9-5542-9daa-dcab8571e817/scratchpad/packbuild/art/Modern_Exteriors_32x32/ME_Theme_Sorter_32x32'
THEMES = {
    'fm': ('5_Floor_Modular_Building_Singles_32x32', 'Floor_Modular_Building_32x32_'),
    'gb': ('4_Generic_Building_Singles_32x32', 'Generic_Building_32x32_'),
    'of': ('16_Office_Singles_32x32', 'Office_32x32_'),
    'cp': ('11_Camping_Singles_32x32', 'Camping_32x32_'),
}
_cache = {}


def piece(theme, name):
    """A picture of the pack: theme 'fm' (5 Floor Modular), 'gb' (4 Generic Building), 'of' (16 Office), 'cp' (11 Camping)."""
    key = (theme, name)
    if key not in _cache:
        d, pre = THEMES[theme]
        path = os.path.join(PACK, d, f'ME_Singles_{pre}{name}.png')
        if not os.path.exists(path): raise SystemExit(f'missing pack picture {path} (set PACK_ART)')
        _cache[key] = np.array(Image.open(path).convert('RGBA'))
    return _cache[key]


def cut(theme, name, x0, x1, fixl=False, fixr=False):
    """Columns x0..x1 of a picture, the pilaster at either end (3 px) replaced by the wall beside it when asked."""
    a = piece(theme, name)[:, x0:x1].copy()
    if fixl: a[:, 0:3] = a[:, 3:6]
    if fixr: a[:, -3:] = a[:, -6:-3]
    return a


PALETTES = ('brick', 'blue', 'grey')
# --- theme 5 ---------------------------------------------------------------------------------------------------------
# Middle floors: 5 two-tile motifs x 2 slices x 3 palettes (slice numbers 10p + 2m + 1, + 2); the full 7-wide floors are
# 6 per palette. Condo ground floors: 4 slice motifs x 2 x 3 (8p + 2m + 1), plus doors and windows cut from the full
# floors (4 per palette). Shop ground floors: only the full 7-wide ones exist (the Shop_Modular slices are single tiles
# of a display window and of a door, not motifs): a 5-tile display with its end pilaster and a 2-tile door, cut apart.
MID_MOTIFS = ('win', 'balc', 'sign', 'twin', 'slit')
CONDO_SLICES = ('smallwin', 'atm', 'pipe', 'tall')
# door and window motifs cut from the full condo ground floors (index in 1..4 within the palette, tile span, wall fix)
CONDO_CUTS = {
    'door_glass': (1, 4, 6, False, False), 'door_canopy': (2, 2, 5, False, False), 'door_arch': (3, 3, 6, False, False),
    'door_wood': (4, 2, 5, False, False), 'win2': (2, 5, 7, False, True), 'bigwin3': (3, 0, 3, True, False),
}
CONDO_DOORS = ('door_glass', 'door_canopy', 'door_arch', 'door_wood')
# open shop fronts per palette (the odd full pieces; 3 is the one with a CLOSED banner, the even ones have shutters down)
SHOP_OPEN = (1, 5, 7, 9, 11)
SHOP_CLOSED = (2, 6, 8, 10, 12)
ROOFS = {'teal': (1, 2), 'gold': (4, 5), 'stone': (7, 8)}      # Roof_Modular column pairs (192 tall)
# the 192-row flat roof column: top coping, the back parapet's inner face, the floor, the front coping, the front face
ROOF_ROWS = dict(back_top=(2, 12), back_face=(12, 26), floor=(44, 164), front_top=(166, 180), front_face=(178, 190))


def motif_cut(m):
    """A motif's picture from its `parts`: [[theme, name, x0, x1, fixl, fixr], ...] laid side by side."""
    return np.concatenate([cut(*p[:4], *p[4:]) for p in m['parts']], axis=1)


def kit5(p):
    pal = PALETTES[p]
    mid = {}
    for m, mid_id in enumerate(MID_MOTIFS):
        b = 10 * p + 2 * m + 1
        mid[mid_id] = dict(tiles=2, parts=[['fm', f'Middle_Floor_Modular_{b}', 0, 32], ['fm', f'Middle_Floor_Modular_{b + 1}', 0, 32]],
                           weight=dict(win=5, balc=0, sign=2, twin=3, slit=2)[mid_id], balcony=(mid_id == 'balc'))
    condo = {}
    for m, cid in enumerate(CONDO_SLICES):
        b = 8 * p + 2 * m + 1
        condo[cid] = dict(tiles=2, parts=[['fm', f'Ground_Floor_Condo_Modular_{b}', 0, 32], ['fm', f'Ground_Floor_Condo_Modular_{b + 1}', 0, 32]])
    for cid, (k, t0, t1, fl, fr) in CONDO_CUTS.items():
        condo[cid] = dict(tiles=t1 - t0, parts=[['fm', f'Ground_Floor_Condo_{4 * p + k}', t0 * TILE, t1 * TILE, fl, fr]], door=cid in CONDO_DOORS)
    shop = {}
    for i, k in enumerate(SHOP_OPEN):
        n = 12 * p + k
        shop[f'disp{i}'] = dict(tiles=5, parts=[['fm', f'Ground_Floor_Shop_{n}', 0, 160, True, False]])
        shop[f'door{i}'] = dict(tiles=2, parts=[['fm', f'Ground_Floor_Shop_{n}', 160, 224, False, True]], door=True)
    for i, k in enumerate(SHOP_CLOSED):
        n = 12 * p + k
        shop[f'cdisp{i}'] = dict(tiles=5, parts=[['fm', f'Ground_Floor_Shop_{n}', 0, 160, True, False]], closed=True)
        shop[f'cdoor{i}'] = dict(tiles=2, parts=[['fm', f'Ground_Floor_Shop_{n}', 160, 224, False, True]], door=True, closed=True)
    return dict(
        family='fm', palette=pal, tile=TILE,
        edge=dict(w_px=3, mid=[f'Middle_Floor_{6 * p + 1}'], condo=[f'Ground_Floor_Condo_{4 * p + 1}'], shop=[f'Ground_Floor_Shop_{12 * p + 1}']),
        filler=dict(tiles=1),
        middle=dict(h_tiles=4, motifs=mid, full=[f'Middle_Floor_{6 * p + i}' for i in range(1, 7)]),
        ground=dict(shop=dict(h_tiles=3, motifs=shop, full=[f'Ground_Floor_Shop_{12 * p + k}' for k in SHOP_OPEN]),
                    condo=dict(h_tiles=3, motifs=condo, full=[f'Ground_Floor_Condo_{4 * p + i}' for i in range(1, 5)])),
        roof=dict(flat={c: dict(columns=[f'Roof_Modular_{a}', f'Roof_Modular_{b}'], rows=ROOF_ROWS, depth_tiles=6) for c, (a, b) in ROOFS.items()},
                  # trial (final/item1_roof_corner_trial.png): 14/15 are the back-left/back-right end blocks of a parapet (64 tall),
                  # 17/16 the left/right rim stubs (32 tall, each ending in its own outline: they do NOT repeat into a closed rim),
                  # and there are no front corners, so the slices cannot frame a roof alone. The tool uses the columns' own rims
                  # (back cap and face, front cap) and builds the side rims as parapet boxes wearing a stub's coping (left/right)
                  corners=dict(teal=dict(tl=14, tr=15, left=17, right=16), gold=dict(tl=22, tr=23, left=24, right=25),
                               stone=dict(tl=26, tr=27, left=28, right=29))))


def kits():
    out = {'tile': TILE, 'kits': {PALETTES[p]: kit5(p) for p in range(3)}}
    # Condo_7: a 3-tile-wide tower of any height: Bottom (door, steps; 5 tiles), N x Middle (2 tiles), Top (roof from above + a floor; 7 tiles);
    # Window_1..5 are 96x64 sprites laid over a Middle (a window, a shuttered pair, ...)
    out['kits']['condo7'] = dict(family='gb', tile=TILE, widthTiles=3, bottom='Condo_7_Modular_Bottom', middle='Condo_7_Modular_Middle', top='Condo_7_Modular_Top',
                                 windows=[f'Condo_7_Modular_Window_{i}' for i in range(1, 6)], roof_prop='Condo_7_Roof_Air_Unit')
    # Condo_1 and Condo_2 (the same system in two colours) and Garage_Sales Building_1/2: columns 96/64/32 wide, 384 (4 floors + roof)
    # or 320 tall (3 floors + roof); a building is a row of columns, width = 3a + 2b + c tiles
    out['kits']['condo12'] = dict(family='gb', tile=TILE, colours=[1, 2],
                                  columns={'tall': dict(h=384, w3=['Condo_{c}_1', 'Condo_{c}_2'], w2=['Condo_{c}_3'], w1=['Condo_{c}_4', 'Condo_{c}_5']),
                                           'short': dict(h=320, w3=['Condo_{c}_6', 'Condo_{c}_7'], w2=['Condo_{c}_8'], w1=['Condo_{c}_9', 'Condo_{c}_10'])})
    return out


# --- generated tiles -------------------------------------------------------------------------------------------------
def filler_from(sources):
    """A one-tile plain wall from clean wall strips of the pack's pictures, [(theme, name, x0, x1), ...] (the brick
    courses are irregular, not periodic: the strips are laid end to end and every other one mirrored, which no eye
    finds). Every column has the picture's cornice and ledge bands, so the rows match its neighbours."""
    strips = [piece(s[0], s[1])[:, s[2]:s[3]] for s in sources]
    out = []; i = 0
    while sum(c.shape[1] for c in out) < TILE:
        st = strips[i % len(strips)]
        out.append(st if (i // len(strips)) % 2 == 0 else st[:, ::-1]); i += 1
    return np.ascontiguousarray(np.concatenate(out, axis=1)[:, :TILE])


def generate(out=KITDIR):
    os.makedirs(out, exist_ok=True)
    for old in glob.glob(out + '/*.png'): os.remove(old)
    data = kits()
    k = data['kits']
    for p, pal in enumerate(PALETTES):
        # edge caps: 3 px at each end of the full floors of the same kind (the pilaster with its cornice and ledge)
        for kind, name in (('mid', f'Middle_Floor_{6 * p + 1}'), ('condo', f'Ground_Floor_Condo_{4 * p + 1}'), ('shop', f'Ground_Floor_Shop_{12 * p + 1}')):
            a = piece('fm', name)
            Image.fromarray(np.ascontiguousarray(a[:, :3])).save(f'{out}/{pal}_{kind}_capl.png')
            Image.fromarray(np.ascontiguousarray(a[:, -3:])).save(f'{out}/{pal}_{kind}_capr.png')
        # plain-wall fillers: the clean left columns of a window motif's slice (middle), the tall-window motif (condo), the shop wall left of the display
        Image.fromarray(filler_from([('fm', f'Middle_Floor_Modular_{10 * p + 1}', 3, 13), ('fm', f'Middle_Floor_Modular_{10 * p + 2}', 19, 29)])).save(f'{out}/{pal}_mid_filler.png')
        Image.fromarray(filler_from([('fm', f'Ground_Floor_Condo_Modular_{8 * p + 7}', 3, 12), ('fm', f'Ground_Floor_Condo_Modular_{8 * p + 8}', 20, 29)])).save(f'{out}/{pal}_condo_filler.png')
        Image.fromarray(filler_from([('fm', f'Ground_Floor_Shop_{12 * p + 1}', 4, 13)])).save(f'{out}/{pal}_shop_filler.png')
    json.dump(data, open(out + '/kits.json', 'w'), indent=1)
    print('wrote', len(os.listdir(out)), 'files to', out)


def tile_image(name, kit):
    """A generated cap or filler picture by file name stem, e.g. ('brick_mid_capl')."""
    return np.array(Image.open(f'{KITDIR}/{name}.png').convert('RGBA'))


if __name__ == '__main__':
    generate()
