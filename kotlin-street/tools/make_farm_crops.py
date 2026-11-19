#!/usr/bin/env python3
"""Cuts LimeZu's Modern Farm crops and soil into one atlas + a data file, so a field can later be laid as rows of upright cards
over tilled ground: app/src/main/assets/farm/crops_atlas.png and crops.json. Needs the pack's 32x32 folder (FARM_ART, see
buildings_farm.py). The growth sheets hold seven frames per plant: [0] sprout, [1] young, [2] and [3] growing (3 sways), [4]
budding, [5] and [6] ripe (6 sways); `stages` names them. Seasons are ours (the pack has none): when the crop is sown and harvested.
    python3 make_farm_crops.py [OUT_DIR]"""
import json, os, sys
import numpy as np
from PIL import Image
HERE = os.path.dirname(os.path.abspath(__file__))
FARM = os.environ.get('FARM_ART') or '/tmp/claude-0/-home-user-Timerpg/f2eefd9e-16c9-5542-9daa-dcab8571e817/scratchpad/farm/pack/32x32'
# sheet name: (rows of plants, each row's height, variants' names, single-file name, sow, harvest)
CROPS = {
    'cabbage': ('Cabbage', 32, ['green'], 'spring', 'summer'), 'carrot': ('Carrot', 32, ['orange'], 'spring', 'summer'),
    'cauliflower': ('Cauliflower', 32, ['white'], 'spring', 'summer'), 'coffee': ('Coffee', 64, ['red'], 'spring', 'autumn'),
    'corn': ('Corn', 64, ['yellow'], 'spring', 'summer'), 'cotton': ('Cotton', 32, ['white'], 'spring', 'autumn'),
    'grape': ('Grape', 64, ['purple', 'red'], 'spring', 'autumn'), 'onion': ('Onion', 64, ['white'], 'spring', 'summer'),
    'pepper': ('Pepper', 32, ['red'], 'spring', 'summer'), 'pineapple': ('Pineapple', 64, ['gold'], 'spring', 'summer'),
    'prickly_pear': ('Prickly_Pear', 96, ['pink'], 'spring', 'summer'), 'pumpkin': ('Pumpkin', 64, ['orange'], 'summer', 'autumn'),
    'radish': ('Radish', 32, ['red'], 'spring', 'spring'), 'strawberry': ('Strawberry', 16, ['red'], 'spring', 'summer'),
    'tomato': ('Tomato', 64, ['red'], 'spring', 'summer'), 'turnip': ('Turnip', 32, ['leaf', 'bulb'], 'summer', 'autumn'),
    'watermelon': ('Watermelon', 64, ['green'], 'spring', 'summer'), 'wheat': ('Wheat', 32, ['golden'], 'autumn', 'summer'),
    'zucchini': ('Zuchini', 64, ['green'], 'spring', 'summer'),
}
STAGES = [('sprout', [0]), ('young', [1]), ('growing', [2, 3]), ('budding', [4]), ('ripe', [5, 6])]
# 32x32 soil tiles (1_Terrains_32x32.png, x, y), checked by eye on 3x3 tilings (tools/materials.json has the same picks)
SOILS = {'tilled': (12, 300, 'furrowed tilled soil, brown'), 'tilled_dark': (44, 396, 'wet or dark tilled soil'),
         'furrows': (4, 548, 'ploughed field: furrow lines'), 'dirt': (44, 44, 'plain tan dirt'), 'sand': (44, 172, 'dune sand')}


def main(out):
    os.makedirs(out, exist_ok=True)
    rows = []   # (label, image (h, 7*w, 4))
    meta = {}
    for key, (sheet, rh, variants, sow, harvest) in CROPS.items():
        im = np.array(Image.open(f'{FARM}/Crops_Growth_32x32/{sheet}_Growth_Stages_32x32.png').convert('RGBA'))
        fw = 16 if key == 'strawberry' else 32
        info = dict(frames=7, frameW=fw, sow=sow, harvest=harvest, variants=[], stages=[dict(name=n, frames=f) for n, f in STAGES])
        for vi, vname in enumerate(variants):
            band = im[vi * rh:(vi + 1) * rh]
            top = min(int(np.nonzero(band[:, f * fw:(f + 1) * fw, 3] > 0)[0].min()) if (band[:, f * fw:(f + 1) * fw, 3] > 0).any() else rh for f in range(7))
            band = band[top:]
            info['variants'].append(dict(name=vname, row=len(rows), h=band.shape[0]))
            rows.append(band)
        meta[key] = info
    # soil tiles, one row
    terr = np.array(Image.open(f'{FARM}/1_Terrains_32x32.png').convert('RGBA'))
    soil = np.concatenate([terr[y:y + 32, x:x + 32] for (x, y, _) in SOILS.values()], 1)
    W = max(r.shape[1] for r in rows + [soil])
    H = sum(r.shape[0] for r in rows) + soil.shape[0]
    atlas = np.zeros((H, W, 4), np.uint8); y = 0
    for i, r in enumerate(rows):
        atlas[y:y + r.shape[0], :r.shape[1]] = r
        for k in meta:
            for v in meta[k]['variants']:
                if v['row'] == i: v['y'] = y
        y += r.shape[0]
    atlas[y:y + 32, :soil.shape[1]] = soil
    soils = {k: dict(x=i * 32, y=y, w=32, h=32, note=n) for i, (k, (_, _, n)) in enumerate(SOILS.items())}
    for k in meta:
        for v in meta[k]['variants']: v.pop('row')
    Image.fromarray(atlas).save(f'{out}/crops_atlas.png', optimize=True)
    json.dump(dict(format=1, note='Modern Farm (LimeZu) crops: frames run left to right from x 0 at each variant\'s y, frameW wide, h tall, feet on the bottom row; stages name the frames; soils are 32x32 tiles below',
                   atlas='crops_atlas.png', atlasSize=[W, H], crops=meta, soils=soils), open(f'{out}/crops.json', 'w'), indent=1)
    print('wrote', out, W, H, len(meta), 'crops')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'farm'))
