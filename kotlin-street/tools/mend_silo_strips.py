"""Mends the silos' wrapped strips (fm_silo1/2: the body and the dome's rings) in their atlases.

The strips were unrolled from the pack's front view by sin(angle) (tools/buildings_farm.py, unroll): round each side
(angle near 90 and 270 degrees) they read the sprite's own edge, its dark outline and side shading, so a dark stripe ran
up each side of the silo, body and dome; and the body's steel hoops kept the front view's perspective curve, bands that
rise and dip into V shapes once wrapped round. Here: the body's columns are shifted up or down so each hoop runs level
(its first hoop's middle found in each column), and the columns of the two edge bands are mirrored in from beside them.
"""
import json, sys
import numpy as np
from PIL import Image

BANDS = ((62.0, 118.0), (230.0, 310.0))      # the edge bands about 90 and 270 degrees (the left side's shading is wider)


def ang(j, n): return (j + .5) * 360.0 / n


def col_of(theta, n): return int(theta % 360.0 / 360.0 * n) % n


def mirror_edges(s):
    n = s.shape[1]
    out = s.copy()
    for j in range(n):
        th = ang(j, n)
        for a0, a1 in BANDS:
            if a0 <= th < a1:
                mid = (a0 + a1) / 2
                src = a0 - (th - a0) if th < mid else a1 + (a1 - th)
                out[:, j] = s[:, col_of(src, n)]
    return out


def level_hoops(s, target=None):
    rgb = s[..., :3].astype(int)
    hoop = (rgb.sum(2) > 420) & (rgb[..., 2] >= rgb[..., 0] - 10)
    n = s.shape[1]
    centre = np.full(n, np.nan)
    for j in range(n):
        th = ang(j, n)
        if any(a0 <= th < a1 for a0, a1 in BANDS): continue
        rows = np.nonzero(hoop[38:74, j])[0]
        if len(rows): centre[j] = 38 + (rows.min() + rows.max()) / 2.0
    good = ~np.isnan(centre)
    if good.sum() < n // 4: return s, None
    if target is None: target = float(np.median(centre[good]))
    idx = np.arange(n)
    # (columns without a hoop found: the nearest found column's shift)
    filled = np.interp(idx, idx[good], centre[good], period=n)
    out = s.copy()
    h = s.shape[0]
    for j in range(n):
        d = int(round(target - filled[j]))
        src = np.clip(np.arange(h) - d, 0, h - 1)
        out[:, j] = s[src, j]
    return out, target


root = sys.argv[1]
models = json.load(open(root + '/models.json'))
for key in ('fm_silo1', 'fm_silo2'):
    path = f'{root}/{key}_atlas.png'
    im = np.array(Image.open(path).convert('RGBA'))
    done = []
    for p in models[key]['parts']:
        if p.get('t') != 'cyl' or 'ft' not in p or p.get('axis') == 'x': continue
        x, y, w, h = p['ft']
        if w != 128: continue          # (the pipe, 24 round: left as it is)
        strip = im[y:y + h, x:x + w].copy()
        body = h > 100
        if body:
            strip, t = level_hoops(strip)
            done.append(f'body {w}x{h} hoops levelled at row {t}')
        strip = mirror_edges(strip)
        if not body: done.append(f'ring {w}x{h}')
        im[y:y + h, x:x + w] = strip
    Image.fromarray(im).save(path, optimize=True)
    print(key, '; '.join(done))
