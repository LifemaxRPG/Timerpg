#!/usr/bin/env python3
"""The colour variants of the pack's mobile homes and terraced houses (public/assets/<group>/<key>_c1.._cN.png), made from
the grey originals next to them. Run by scripts/extract-assets.mjs after it copies the originals; run it alone to remake
the variants: `python3 scripts/house-colours.py`.

The pack has one grey colourway. Real houses mix colours: painted siding, trim in another colour, a darker roof, a door
in its own colour, darker cladding on a wing or round the garage. So each variant is a PALETTE PER PART (wall, trim, roof,
accent, door), from era-appropriate combinations (below), and each part of the picture is found by where it is (the roof
seen from above, the wall band, the window frames round the glass, the door) and recoloured by a ramp: every grey of the
part keeps its place on the part's own light-dark ramp, moved onto the palette colour's (hue and saturation of the target,
lightness the target's plus the pixel's offset from the part's median grey). So the pixel art keeps its shading and its
small palette; outlines (the darkest greys), glass, plants, brick and the wooden decks keep their own colours.
(The earlier recolour tinted every mid grey with the roof colour, so a terraced house, whose walls are mid greys, came
out one colour from roof to door: "everything is green".)"""
import colorsys, glob, os, sys
import numpy as np
from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'public', 'assets')

H = lambda s: tuple(int(s[i:i + 2], 16) for i in (1, 3, 5))
# Mobile homes (1960s-2020s): light painted or vinyl siding, a darker metal or shingle roof, contrasting trim, a door of its own.
# (wall, trim, roof, door)
MOBILE = [
    ('#e3d5a6', '#3f5a3c', '#4a4d52', '#8c3b32'),   # cream, forest-green trim, charcoal shingles, barn-red door
    ('#b9cfe0', '#2f4763', '#3a3d44', '#b8402f'),   # pale blue, navy trim, black-grey roof, red door
    ('#bfd0b0', '#f2efe6', '#5a4636', '#2f5a46'),   # sage, white trim, brown shingles, green door
    ('#ecd38f', '#6b4a32', '#6e3b2c', '#3f6a5a'),   # butter yellow (70s), brown trim, oxide-red metal roof, teal door
    ('#f0eeea', '#40474f', '#2f5240', '#9b2f2f'),   # white, slate trim, green metal roof, red door
    ('#e8c3ae', '#f4efe8', '#5b5552', '#3d4f6d'),   # blush/salmon (60s-70s), white trim, warm grey roof, navy door
    ('#cfcac0', '#2f3a46', '#2c2e33', '#c08a2e'),   # greige, dark slate trim, black roof, mustard door
    ('#b7d3c0', '#5a3f2e', '#6a5544', '#7a2f3c'),   # mint, brown trim, brown roof, burgundy door
    ('#d9c7a4', '#7a2f2a', '#45484d', '#2f4a63'),   # tan, brick-red trim, charcoal roof, navy door
]
# Terraced family houses (modern): (wall: the light siding, trim: frames and fascia, roof, accent: the darker cladding round
# the garage, the wing and the porch, door)
TERRACED = [
    ('#e2d9c4', '#f6f4ee', '#3a3c40', '#5b4433', '#2d3b4f'),   # warm white, white trim, charcoal roof, dark-wood cladding, navy door
    ('#aebfa6', '#f1eee4', '#4d5257', '#45484e', '#9a3b2f'),   # sage, cream trim, slate roof, charcoal cladding, red door
    ('#a9bccd', '#ffffff', '#2f3237', '#33415a', '#c39233'),   # pale blue-grey, white, black roof, navy cladding, ochre door
    ('#d8c39a', '#f5efe2', '#5c4637', '#7b4a35', '#3c5a49'),   # sand, cream, brown roof, cedar cladding, green door
    ('#c4c2bd', '#f4f3f0', '#4a3d38', '#8a3c2e', '#2f2f33'),   # light grey, white, dark brown roof, brick-red cladding, black door
]
GROUPS = {'houseSmall': MOBILE, 'houseMedium': MOBILE, 'houseBig': MOBILE, 'houseTerraced': TERRACED}

# terraced geometry (as kotlin-street/tools/make_buildings.py's terraced(): the roof slab's front edge, the side wing, the doors)
T_RIDGE, T_WING = 318, 306
T_DOOR = {1: (84, 141), 2: (84, 141), 4: (236, 279), 5: (228, 300), 6: (228, 300)}
t_edge = lambda x: 306 - 2 * ((x - 40) // 8)


def hls(a):
    """Per-pixel (h, l, s) of an RGB uint8 array."""
    f = a[..., :3].astype(float) / 255
    mx, mn = f.max(-1), f.min(-1); l = (mx + mn) / 2; d = mx - mn
    s = np.where(d == 0, 0, d / np.maximum(1e-6, 1 - np.abs(2 * l - 1)))
    r, g, b = f[..., 0], f[..., 1], f[..., 2]
    h = np.where(d == 0, 0, np.where(mx == r, ((g - b) / np.maximum(d, 1e-6)) % 6, np.where(mx == g, (b - r) / np.maximum(d, 1e-6) + 2, (r - g) / np.maximum(d, 1e-6) + 4)) / 6)
    return h, l, s


def ramp(img, mask, colour, gain=1.0):
    """Recolour the pixels of [mask] onto [colour]'s ramp: hue and saturation the colour's, lightness the colour's plus each
    pixel's offset from the masked pixels' median lightness (so shading and the art's few greys stay distinct)."""
    if not mask.any(): return
    _, l, _ = hls(img)
    l0 = float(np.median(l[mask]))
    th, tl, ts = colorsys.rgb_to_hls(*(c / 255 for c in colour))
    out = img.copy()
    ys, xs = np.nonzero(mask)
    cache = {}
    for y, x in zip(ys, xs):
        k = tuple(img[y, x, :3])
        if k not in cache:
            nl = min(.97, max(.05, tl + (l[y, x] - l0) * gain))
            # a little less saturated at the ends of the ramp, as painters' shading does
            ns = ts * (1 - .35 * abs(nl - tl) / max(tl, 1 - tl))
            cache[k] = tuple(int(round(v * 255)) for v in colorsys.hls_to_rgb(th, nl, ns))
        out[y, x, :3] = cache[k]
    img[...] = out


def glass(a):
    r, g, b, al = (a[..., i].astype(int) for i in range(4))
    return (al > 200) & (b > r + 18) & (b > g - 8) & ((r + g + b) / 3 > 134)


def greys(a):
    """The art's greys: unsaturated pixels, and its lilac-blue greys (the pack shades its grey with a blue tint), not glass."""
    from scipy import ndimage
    h, l, s = hls(a)
    g = (a[..., 3] > 0) & ((s < .25) | ((h > .5) & (h < .8) & (s < .42)))
    return g & ~ndimage.binary_dilation(glass(a) & (s >= .3), iterations=1)


def near_glass(a, pad=4):
    """Pixels within [pad] of a pane's bounding box (its frame), by connected panes of glass."""
    from scipy import ndimage
    lab, n = ndimage.label(glass(a) & (hls(a)[2] >= .3))
    m = np.zeros(a.shape[:2], bool)
    for sl in ndimage.find_objects(lab):
        if sl is None: continue
        y0, y1, x0, x1 = sl[0].start, sl[0].stop, sl[1].start, sl[1].stop
        if (y1 - y0) * (x1 - x0) < 12: continue
        m[max(0, y0 - pad):y1 + pad, max(0, x0 - pad):x1 + pad] = True
    return m


def mobile(a, pal):
    wall, trim, roof, door = (H(c) for c in pal)
    h, l, s = hls(a)
    op = a[..., 3] > 0; grey = greys(a)
    light = grey & (l > .7)
    rows = light.sum(1) / np.maximum(1, op.sum(1))
    # the wall band: from the first row of siding down while siding rows keep coming (gaps of a few rows: a sill, a
    # gutter); the deck's rails further down are not wall
    eave = int(np.nonzero(rows > .3)[0].min()); foot = eave
    while any(rows[foot + 1:foot + 13] > .2): foot += 1 + int(np.nonzero(rows[foot + 1:foot + 13] > .2)[0][0])
    cols = np.nonzero(light[eave:foot + 1].any(0))[0]; c0, c1 = int(cols.min()), int(cols.max())
    yy, xx = np.mgrid[:a.shape[0], :a.shape[1]]
    inwall = (yy >= eave) & (yy <= foot) & (xx >= c0) & (xx <= c1)
    out = a.copy()
    ramp(out, grey & (yy < eave) & (l > .22), roof)                                     # the roof from above, its edge
    ramp(out, inwall & light, wall)                                                      # the siding
    ramp(out, inwall & grey & ~light & (l > .3) & near_glass(a), trim)                   # window and door frames
    ramp(out, inwall & ~grey & (h > .04) & (h < .13) & (s < .6) & (l > .4) & door_box(a, inwall & (yy < foot - 2)), door)
    return out


def door_box(a, inwall):
    """The door: a run of wood-coloured pixels in the wall band (the deck lies below the band)."""
    from scipy import ndimage
    h, l, s = hls(a)
    wood = inwall & (s >= .25) & (h > .04) & (h < .13) & (l > .4)
    lab, n = ndimage.label(wood)
    # the door is the tallest wood (window shutters are shorter; the deck stands below the band)
    objs = [(sl[0].stop - sl[0].start, i) for i, sl in enumerate(ndimage.find_objects(lab)) if sl is not None and (sl[1].stop - sl[1].start) >= 10]
    if not objs: return np.zeros(a.shape[:2], bool)
    sl = ndimage.find_objects(lab)[max(objs)[1]]
    m = np.zeros(a.shape[:2], bool); m[sl] = True
    return m


def terraced(a, design, pal):
    wall, trim, roof, accent, door = (H(c) for c in pal)
    h, l, s = hls(a)
    op = a[..., 3] > 0; grey = greys(a)
    yy, xx = np.mgrid[:a.shape[0], :a.shape[1]]
    edge = t_edge(xx)
    slab = (xx <= T_RIDGE) & (yy < edge + 6)
    fascia = (xx <= T_RIDGE) & (yy >= edge + 6) & (yy < edge + 16) & ~slab
    wing_top = (xx >= T_WING) & (yy < 280) & ~slab
    wing = (xx >= T_WING) & (yy >= 280) & ~slab & ~fascia
    face = op & ~slab & ~fascia & ~wing_top & ~wing
    frames = near_glass(a)
    out = a.copy()
    ramp(out, grey & (slab | wing_top) & (l > .22), roof)
    ramp(out, grey & fascia & (l > .55), trim)
    ramp(out, grey & fascia & (l <= .55) & (l > .22), roof)
    ramp(out, grey & face & frames & (l > .6), trim)
    ramp(out, grey & face & ~(frames & (l > .6)) & (l >= .64), wall)
    ramp(out, grey & (face | wing) & (l >= .3) & (l < .64) & ~(frames & (l > .6)), accent)
    d0, d1 = T_DOOR[design]
    dm = (xx >= d0) & (xx <= d1) & (yy > 380) & (yy < 462) & op & (s >= .2) & (h > .0) & (h < .12) & (l > .2) & (l < .6)
    ramp(out, dm, door)
    return out


def main():
    made = 0
    for group, pals in GROUPS.items():
        for f in sorted(glob.glob(os.path.join(ROOT, group, '*.png'))):
            key = os.path.basename(f)[:-4]
            if '_c' in key: continue
            a = np.array(Image.open(f).convert('RGBA'))
            for i, pal in enumerate(pals):
                out = terraced(a, int(key.split('_')[2]), pal) if group == 'houseTerraced' else mobile(a, pal)
                Image.fromarray(out).save(os.path.join(ROOT, group, f'{key}_c{i + 1}.png'), optimize=True)
                made += 1
    print('house colours: wrote', made, 'variants')


if __name__ == '__main__':
    if len(sys.argv) > 1: ROOT = sys.argv[1]
    main()
