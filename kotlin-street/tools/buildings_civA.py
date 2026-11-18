"""The civic buildings that are several volumes each: the fire station, the hospital and the post office (its four colours),
modelled by hand from their sprites as make_buildings.py models the villas and the school (see build_model there).

The art draws each in its oblique way: fronts are true elevations, what faces up is seen from above, and a part that
stands further forward has its foot drawn lower by as much. Here each volume is a box standing at its own depth (its
foot row in the art gives it), wearing its front as drawn; what the art draws from above in a front (a canopy, an
awning, steps) is painted out of it and built as its own part; flat roofs are tops wearing the art's own roof, with
real parapets, and what stands on them (plant rooms, vents, hatches, a ladder, a mast) are boxes and cards. A volume is
split where a taller one stands beside it, so the street's painter (back to front) never draws a wall that is against
another (`hs`)."""
import numpy as np
from make_buildings import (Atlas, GLOW, cut, column_rows, dilate, fill_rows, glass, hip_side, along_mean, load, mbox,
                            mhip, panes_of, polygon, rgb_int, rise_for, shade, solid, yard)


def lit_panes(img):
    """The front's panes and their night light (make_buildings.panes_of), the light reaching on into the pale pixels
    inside each pane joined to its glass: the art's grey streaks of reflection, its pale stickers and blinds, which the
    glass's blue shades leave out and which would show as dark marks in a lit window. Not the frames and mullions
    (darker), nor the wall's own colours (those common outside the panes: a pale wall would light up round them)."""
    out, glow = panes_of(img)
    rgb = img[..., :3].astype(int); lum = rgb.sum(-1)
    code = (rgb[..., 0] << 16) | (rgb[..., 1] << 8) | rgb[..., 2]
    inside = np.zeros(lum.shape, bool)
    for (x, y, w, h, _) in out: inside[y:y + h, x:x + w] = True
    cs, ns = np.unique(code[~inside & (img[..., 3] > 200)], return_counts=True)
    wall = np.isin(code, cs[ns >= max(20, .005 * ns.sum())])
    pale = (lum >= 440) & (rgb[..., 2] >= rgb[..., 0] - 8) & (img[..., 3] > 200) & ~wall
    for (x, y, w, h, _) in out:
        box = np.zeros(lum.shape, bool); box[y:y + h, x:x + w] = True
        lit = box & (glow[..., 3] > 0)
        if not lit.any(): continue
        grow = box & pale
        while True:
            more = lit | (grow & (np.roll(lit, 1, 0) | np.roll(lit, -1, 0) | np.roll(lit, 1, 1) | np.roll(lit, -1, 1)))
            if (more == lit).all(): break
            lit = more
        lo, hi = lum[lit].min(), lum[lit].max()
        f = .8 + .2 * (lum[lit] - lo) / max(1, hi - lo)
        glow[lit] = np.stack([np.round(GLOW[0] * f), np.round(GLOW[1] * f), np.round(GLOW[2] * f), np.full(f.shape, GLOW[3])], -1).astype(np.uint8)
    return out, glow


def tile(pic, h, w):
    """`pic` repeated to `h` rows by `w` columns."""
    ry, rx = h // pic.shape[0] + 1, w // pic.shape[1] + 1
    return np.tile(pic, (ry, rx, 1))[:h, :w].copy()


def plain(c):
    """An opaque colour from an art pixel."""
    return tuple(int(v) for v in c[:3])


def walls(rows, depth, rng, corner, wins=()):
    """A side wall `depth` deep: each row the front's own colour for that row (`rows`, from its top), the front's own
    windows (each its picture, the fraction of the depth its middle stands at, its top row), darker corner columns and
    a fine grain. Returns it and where its windows glow."""
    h = len(rows)
    side = np.repeat(rows[:, None, :], depth, axis=1).copy(); side[..., 3] = 255
    glow = np.zeros_like(side)
    for (pic, f, wy) in wins:
        wh, ww = pic.shape[:2]; m = pic[..., 3] > 200; gm = dilate(glass(pic), 1) & m
        cx = max(2, min(depth - ww - 2, int(depth * f) - ww // 2))
        side[wy:wy + wh, cx:cx + ww][m] = pic[m]
        glow[wy:wy + wh, cx:cx + ww][gm] = GLOW
    for c in (0, 1, depth - 2, depth - 1): side[:, c, :3] = corner[:3]
    grain = np.kron(rng.integers(-3, 4, (h // 2 + 1, depth // 2 + 1)), np.ones((2, 2), np.int64))[:h, :depth]
    side[..., :3] = np.clip(side[..., :3].astype(int) + grain[..., None], 0, 255).astype(np.uint8)
    return side, glow


def decal(x, y, d, piece):
    """A picture lying flat at height `y` (x across, d back from the front line), wearing atlas `piece`, back row first."""
    return dict(t='decal', x=[float(x[0]), float(x[1])], y=[float(y), float(y)], d=[float(d[0]), float(d[1])], p=piece)


def slope(x, y, d, c, thick, ft=None, tt=None):
    """A sloped slab (a canopy, an awning): its front edge's top at y[0] on d[0], its back at y[1] on d[1]; its front edge
    `thick` deep wearing `ft`; its top wearing `tt` (back row first); its top, front and side colours."""
    p = dict(t='slope', x=[float(x[0]), float(x[1])], y=[float(y[0]), float(y[1])], d=[float(d[0]), float(d[1])],
             c=[rgb_int(c[0]), rgb_int(c[1]), rgb_int(c[2])], th=float(thick))
    if ft is not None: p['ft'] = ft
    if tt is not None: p['tt'] = tt
    return p


def prism(pts, y, c, ft=None, tt=None, panes=None, hide=()):
    """An upright prism over a plan polygon (`pts`: x, d corners anticlockwise seen from above, the front edge running
    left to right) from y[0] to y[1]: its top, front and side colours; its first face (corner 0 to 1) wearing `ft` (its
    windows `panes`), its top `tt` laid over the plan's bounds (back row first); `hide` the faces against other parts."""
    xs, ds = [q[0] for q in pts], [q[1] for q in pts]
    p = dict(t='prism', pts=[[float(a), float(b)] for a, b in pts], x=[float(min(xs)), float(max(xs))], y=[float(y[0]), float(y[1])],
             d=[float(min(ds)), float(max(ds))], c=[rgb_int(c[0]), rgb_int(c[1]), rgb_int(c[2])])
    if ft is not None: p['ft'] = ft
    if tt is not None: p['tt'] = tt
    if panes: p['panes'] = panes
    if hide: p['hide'] = list(hide)
    return p


def abut(p, sides):
    p['hs'] = sides
    return p


def rgb_int_img(pic):
    """Each pixel's colour as 0xRRGGBB."""
    c = pic[..., :3].astype(np.int64)
    return (c[..., 0] << 16) | (c[..., 1] << 8) | c[..., 2]


def card_of(pic, keep):
    """`pic` with only `keep` left (a beacon, a sign standing clear of what is behind it in the art)."""
    out = pic.copy(); out[~keep] = 0
    return out


class Front:
    """The whole front as the street sees it from straight ahead (what the wet road mirrors, and the corner turn's box
    wears): pieces laid by their foot's height, and their night glow."""
    def __init__(self, w, h):
        self.img = np.zeros((h, w, 4), np.uint8); self.emis = np.zeros_like(self.img); self.h = h
    def put(self, pic, x, y, glow=None):
        h, w = pic.shape[:2]; r = self.h - int(y) - h; m = pic[..., 3] > 0
        r0, c0 = max(0, r), max(0, int(x))
        sub = m[r0 - r:, c0 - int(x):][:self.h - r0, :self.img.shape[1] - c0]
        hh, ww = sub.shape
        self.img[r0:r0 + hh, c0:c0 + ww][sub] = pic[r0 - r:r0 - r + hh, c0 - int(x):c0 - int(x) + ww][sub]
        g = glow if glow is not None else np.zeros_like(pic)
        self.emis[r0:r0 + hh, c0:c0 + ww][sub] = g[r0 - r:r0 - r + hh, c0 - int(x):c0 - int(x) + ww][sub]


# ------------------------------------------------------------------------------------------------ the fire station
# landmark_fire_1 (512 x 480): on the left the hose tower, two storeys under a hipped roof with a flat top and a low
# lantern on it, standing 32 forward of the engine bay (its foot on row 412, the bay's on 380); a canopy over its door
# with two beacons on it, and a landing and a step before the door. On the right the engine bay, one storey with a
# parapet round its flat roof, and on that roof, set back, a long upper block with its own roof and two vents.
def fire_station(shop, rng):
    art = solid(np.array(load(shop['parts'][0]['key'])))
    W = 512; at = Atlas(512)
    GT, GB = 412, 380                    # the tower's and the bay's foot rows
    DT = GB - GT                         # -32: the tower's front, before the bay's
    brick = art[340, 330]
    # --- the engine bay: its wall (rows 240..380: the fascia's lower part, the brick, the doors) and its parapet (its
    # front rows 232..240, its top from above 224..232)
    bay = art[240:GB, 176:488].copy()
    bay_panes, bay_glow = lit_panes(bay)
    bay_r = at.add(bay, bay_glow)
    red_top, red_front = art[228, 300], art[236, 300]
    par_f = at.add(art[232:240, 176:488]); par_t = at.add(art[224:232, 176:488])
    # its roof: the art's diamond tiles (40 by 20 pixels a tile), laid over its whole top
    floor = tile(art[136:216, 200:440], 184, 312)
    floor_r = at.add(floor)
    win = art[262:298, 208:304]
    bay_side, bay_sg = walls(column_rows(art[240:GB], list(range(184, 206)) + list(range(306, 358))), 184, rng, art[300, 484],
                             [(win, .5, 22)])
    bay_sr = at.add(bay_side, bay_sg)
    # --- the upper block on the bay's roof (its front rows 64..128, its top 0..64 from above); its two vents (louvres in
    # the art's top) stand on it as low boxes, so its top is the art's with them painted out
    ub = art[64:128, 200:470].copy()
    ub_panes, ub_glow = lit_panes(ub)
    ub_r = at.add(ub, ub_glow)
    ut = art[0:64, 200:470].copy()
    vm = np.zeros(ut.shape[:2], bool); vm[22:42, 22:66] = True; vm[22:42, 198:250] = True
    ut_r = at.add(fill_rows(ut, vm))
    vent1 = at.add(art[24:40, 224:264]); vent2 = at.add(art[24:40, 400:448])
    ub_side, ub_sg = walls(column_rows(art[64:128], list(range(200, 208)) + list(range(462, 470))), 64, rng, art[120, 205],
                           [(art[82:112, 216:256], .5, 18)])
    ub_sr = at.add(ub_side, ub_sg)
    # --- the hose tower. Its lower storey (rows 304..412) with what the art draws from above in it painted out: the
    # canopy's top over the door (rows 334..366) and the beacons standing on it; the brick behind is the bay's own (the
    # same bricks, from between its doors), the windows over it as drawn. The canopy's face (rows 366..372) stands 32
    # before the tower, so its foot would be row 444: it is 72..78 up, and the art shows only the door's foot under
    # it (rows 372..404, 8..40 up). The door is rebuilt to its full height (8..58, two leaves of two glazed panels
    # each over the drawn kick panels, its jambs and head as drawn), the brick over it to the canopy.
    low = art[304:GT, 12:176].copy()
    patch = tile(art[316:348, 320:350], 40, 112)
    low[334 - 304:374 - 304, 40 - 12:152 - 12] = patch[:40, :112]
    low[318 - 304:334 - 304, 54 - 12:70 - 12] = fill_rows(low[318 - 304:334 - 304, 40 - 12:84 - 12], np.pad(np.ones((16, 16), bool), ((0, 0), (14, 14))))[:, 14:30]
    low[318 - 304:334 - 304, 122 - 12:138 - 12] = fill_rows(low[318 - 304:334 - 304, 108 - 12:152 - 12], np.pad(np.ones((16, 16), bool), ((0, 0), (14, 14))))[:, 14:30]
    dx = slice(46, 146)                                  # the door with its jambs
    glazed = art[376:392, dx]
    door = np.concatenate([art[372:376, dx], glazed[:, ::-1], art[374:376, dx], glazed, art[392:404, dx]])
    low[GT - 8 - door.shape[0] - 304:GT - 8 - 304, dx.start - 12:dx.stop - 12] = door
    low_panes, low_glow = lit_panes(low)
    low_r = at.add(low, low_glow)
    up = art[140:304, 8:184].copy()
    up_panes, up_glow = lit_panes(up)
    up_r = at.add(up, up_glow)
    post = art[250, 14]
    # its side walls: the front's courses (clear brick either side of the emblem and the door), its small windows
    sw_up = art[174:188, 32:56]; sw_low = art[314:328, 44:66]
    upside, upside_g = walls(column_rows(art[140:304], list(range(28, 44)) + list(range(152, 160))), 106, rng, post, [(sw_up, .35, 34), (sw_up, .75, 34)])
    lowside, lowside_g = walls(column_rows(art[304:GT], list(range(30, 40)) + list(range(152, 164))), 106, rng, post, [(sw_low, .6, 10)])
    # the tower's front 32 stand before the bay's front; behind that it stands beside the bay, against it up to the
    # bay's parapet: split there, so each piece's side is either open or against the bay
    def sides(pic, glow):
        return at.add(pic[:, :32], glow[:, :32]), at.add(pic[:, 32:], glow[:, 32:])
    ups_f, ups_b = sides(upside, upside_g); lows_f, lows_b = sides(lowside, lowside_g)
    # (the upper storey's back part splits again at the bay's parapet: below against it, above open)
    ups_bl = at.add(upside[124:164, 32:], upside_g[124:164, 32:]); ups_bh = at.add(upside[:124, 32:], upside_g[:124, 32:])
    # --- its roof: the rim (the art's roof from above, rows 34..140; the hipped roof covers all but its edge), the hipped
    # roof inside it (eaves x 16..176, the front face rows 96..136 between the hips, every other row: one screen row
    # each, the rise chosen so), its flat top under a low lantern
    E = 272
    rim = art[34:140, 8:184]
    rim_f = at.add(rim[-32:]); rim_b = at.add(rim[:-32])
    run = 22
    face = cut(art[96:137, 16:176], polygon((41, 160), [(0, 41), (36, 0), (124, 0), (160, 41)]))[::2]
    rise = rise_for(face.shape[0], run)
    R = E + rise
    face_r = at.add(face)
    hd0, hd1 = DT + 4, DT + 100                                         # the hipped roof's eaves, inside the rim
    lq = [(0, 96), (0, 0), (36, 96 - 74), (36, 96 - 22)]; rq = [(36, 96), (36, 0), (0, 96 - 74), (0, 96 - 22)]
    lpic = hip_side(face, 37, 97, lq, 1.0, (40, 112), True); rpic = hip_side(face, 37, 97, rq, 1.0, (40, 112), False)
    left, right = at.add(lpic), at.add(rpic)
    flush = [at.add(along_mean(lpic)), at.add(along_mean(rpic))]
    roof_c = art[120, 96]
    lant_t = at.add(art[46:88, 52:140]); lant_f = at.add(art[88:94, 52:140])
    # --- the canopy over the door: its top from above (rows 334..366, the beacons painted out), its front edge
    ct = art[334:366, 42:150].copy()
    bm = np.zeros(ct.shape[:2], bool); bm[:22, 12:28] = True; bm[:22, 80:96] = True
    ct_r = at.add(fill_rows(ct, bm)); cf_r = at.add(art[366:372, 42:150])
    # the beacons: glass tubes standing on it (each row from its first to its last bluish or light pixel, and its ink)
    def beacon(x0):
        pic = art[324:355, x0:x0 + 14].copy(); rgb = pic[..., :3].astype(int)
        lit = (rgb[..., 2] - rgb[..., 0] >= 25) | (rgb.sum(-1) > 560)
        keep = np.zeros(lit.shape, bool)
        for y in range(pic.shape[0]):
            xs = np.where(lit[y])[0]
            if len(xs): keep[y, max(0, xs.min() - 1):xs.max() + 2] = True
        return card_of(pic, keep)
    bp1, bp2 = beacon(54), beacon(122)
    b1, b2 = at.add(bp1), at.add(bp2)
    # --- the landing before the door and the step below it, their cheeks
    land_t = at.add(art[400:432, 52:140]); land_f = at.add(art[432:440, 52:140])
    step_t = at.add(art[440:452, 52:140]); step_f = at.add(art[452:456, 52:140])
    cheek = art[420, 49]; tread = art[444, 96]
    canopy_c = art[350, 96]
    parts = [
        # the bay (its left side is behind and against the tower) and its parapet round its roof
        abut(mbox((176, 488), (0, 140), (0, 184), (art[180, 300], brick, brick), ft=bay_r, st=bay_sr, tt=floor_r, panes=bay_panes), 'l'),
        mbox((176, 488), (140, 148), (0, 8), (red_top, red_front, shade(red_front, .85)), ft=par_f, tt=par_t),
        mbox((472, 488), (140, 148), (8, 184), (red_top, red_front, shade(red_front, .85))),
        mbox((184, 488), (140, 148), (176, 184), (red_top, red_front, shade(red_front, .85))),
        # the upper block on it, and its vents
        mbox((200, 470), (140, 204), (112, 176), (art[30, 330], art[100, 330], shade(art[120, 330], .9)), ft=ub_r, tt=ut_r, st=ub_sr, panes=ub_panes),
        mbox((224, 264), (204, 208), (136, 152), (art[30, 240], art[40, 240], shade(art[40, 240], .85)), tt=vent1, outline=True),
        mbox((400, 448), (204, 208), (136, 152), (art[30, 420], art[40, 420], shade(art[40, 420], .85)), tt=vent2, outline=True),
        # the tower behind its front: beside the bay (against it below the bay's parapet), then its front part
        abut(mbox((12, 176), (0, 108), (0, 74), (brick, brick, brick), st=lows_b, top=False), 'r'),
        abut(mbox((8, 184), (108, 148), (0, 74), (brick, brick, brick), st=ups_bl, top=False), 'r'),
        mbox((8, 184), (148, E), (0, 74), (roof_c, brick, brick), st=ups_bh, tt=rim_b),
        mbox((12, 176), (0, 108), (DT, 0), (brick, brick, brick), ft=low_r, st=lows_f, panes=low_panes, top=False),
        mbox((8, 184), (108, E), (DT, 0), (roof_c, brick, brick), ft=up_r, st=ups_f, tt=rim_f, panes=up_panes),
        mhip((16, 176), (E, R), (hd0, hd1), (52, 140, hd0 + run, hd1 - run), face_r, left, [v for p in lq for v in p], right,
             [v for p in rq for v in p], roof_c, hip=(art[116, 34], art[116, 36]), flush=flush),
        mbox((52, 140), (R, R + 6), (hd0 + run, hd1 - run), (art[70, 96], art[90, 96], shade(art[90, 96], .85)), ft=lant_f, tt=lant_t),
        # the canopy over the door and its beacons
        mbox((42, 150), (72, 78), (DT - 32, DT), (canopy_c, art[368, 96], shade(art[368, 96], .85)), ft=cf_r, tt=ct_r),
        mbox((54, 68), (78, 109), (DT - 20, DT - 20), (canopy_c, canopy_c, canopy_c), ft=b1, card=True),
        mbox((122, 136), (78, 109), (DT - 20, DT - 20), (canopy_c, canopy_c, canopy_c), ft=b2, card=True),
        # the landing, the step, the cheeks either side
        mbox((52, 140), (0, 8), (DT - 28, DT), (tread, art[436, 96], shade(art[436, 96], .85)), ft=land_f, tt=land_t),
        mbox((52, 140), (0, 4), (DT - 40, DT - 28), (tread, art[454, 96], shade(art[454, 96], .85)), ft=step_f, tt=step_t),
        mbox((46, 52), (0, 12), (DT - 40, DT), (cheek, cheek, shade(cheek, .8)), outline=True),
        mbox((140, 146), (0, 12), (DT - 40, DT), (cheek, cheek, shade(cheek, .8)), outline=True),
    ]
    setback = 48
    lawn, fence, fence_snow = yard(rng, W, setback + 184, False, (0, 0, 0), 'natural', [], ground='paving')
    fr = Front(W, int(R) + 8)
    fr.put(ub, 200, 140, ub_glow); fr.put(art[232:240, 176:488], 176, 140); fr.put(bay, 176, 0, bay_glow)
    fr.put(face, 16, E); fr.put(art[88:94, 52:140], 52, R)
    fr.put(up, 8, 108, up_glow); fr.put(low, 12, 0, low_glow)
    fr.put(art[366:372, 42:150], 42, 72)
    fr.put(bp1, 54, 78); fr.put(bp2, 122, 78)
    fr.put(art[432:440, 52:140], 52, 0)
    model = dict(kind='civic', w=W, frontX=(224 - W) / 2, setback=setback, lawn='paving', style='natural', parts=parts,
                 fence=None, roofTint=rgb_int(roof_c), lamp=None, lights='shop', smoke=[], fpx=0)
    return model, at, fr.img, bay_side, fr.emis, lawn, fence, fence_snow


# ------------------------------------------------------------------------------------------------ the hospital
# landmark_hospital_1 (864 x 672): three volumes of different heights side by side, each under a flat roof behind a
# wide coping: the main block on the left (its foot on row 540), the middle block standing 96 forward of it and taller
# (foot on 636; its left corner chamfered: the slanted strip at x 256..288), the emergency wing on the right, 32
# forward and the tallest (foot on 572). Before the main block an entrance canopy on two pillars with a blue cross
# standing on its edge; a road arrow on the ground under it. On the middle block's roof the helipad (a decal); on the
# main block's, a plant room with a fan unit on it. Every volume runs back to the same back line (d 256).
def hospital(shop, rng):
    art = solid(np.array(load(shop['parts'][0]['key'])))
    W = 864; at = Atlas(1024)
    GL, GM, GW = 540, 636, 572
    DM, DW = GL - GM, GL - GW                      # -96, -32: the middle block's and the wing's fronts
    # the back line: the main roof's back coping, its top (244 up) on rows 41..69 of the art, ends 256 back
    BACK = 256
    PT = 32                                        # the coping's width, all round
    wall = art[330, 20]; cope = art[280, 120]; cope_in = art[84, 120]; roof_c = art[200, 150]
    # --- the main block's front (rows 296..540: the coping's face 296..320, the wall), x 0..288: it ends at x 256, where
    # the chamfered corner (the art's slanted strip x 256..288) runs from it forward to the middle block's front; what
    # stands behind that corner (x 256..288, never seen) is the wall carried on
    mf = np.zeros((GL - 296, 288, 4), np.uint8)
    mf[:, :256] = art[296:GL, 0:256]
    mf[:, 256:288] = tile(art[296:GL, 212:248], GL - 296, 32)
    # what the canopy hides in the art (its top from above, rows 434..544, and its face) is the wall again: above where
    # it meets the wall (rows < 452) the wall's own rows; under it the entrance, a glass front between the two glass side
    # doors the art shows either side
    mf[434 - 296:480 - 296, 64:192] = fill_rows(mf[434 - 296:480 - 296], np.pad(np.ones((46, 128), bool), ((0, 0), (64, 96))))[:, 64:192]
    door = art[480:536, 192:212]
    frame, pane = art[500, 194], art[500, 198]
    ent = np.zeros((56, 128, 4), np.uint8); ent[..., 3] = 255
    ent[..., :3] = frame[:3]
    for x0 in range(2, 128, 21):
        ent[3:54, x0 + 1:min(128, x0 + 19), :3] = pane[:3]
    ent[:, :, :3] = np.where(np.arange(56)[:, None, None] < 3, frame[:3], ent[..., :3])
    mf[480 - 296:536 - 296, 64:192] = ent
    mf[536 - 296:GL - 296, 64:192] = art[536:GL, 30:40][:, np.arange(128) % 10]
    mf_panes, mf_glow = lit_panes(mf[24:])
    mf_r = at.add(mf[24:], mf_glow)
    mf_glow = np.concatenate([np.zeros_like(mf[:24]), mf_glow])
    # --- the middle block's front (rows 352..636) and the wing's (rows 192..572)
    df = art[352:GM, 288:544].copy()
    df_panes, df_glow = lit_panes(df[24:])
    wf = art[192:GW, 544:856].copy()
    # the slab over the ambulance bays stands out (its top from above rows 488..496 painted out of the wall)
    wf[488 - 192:496 - 192, 16:272] = fill_rows(wf[488 - 192:496 - 192], np.pad(np.ones((8, 256), bool), ((0, 0), (16, 40))))[:, 16:272]
    # the middle block's front stands before the wing: its piece covers 96..32 of its depth; above the main block's
    # roof its front and its parts behind are one box, below they are three (each open or against a neighbour)
    df_r = at.add(df[24:], df_glow)
    df_glow = np.concatenate([np.zeros_like(df[:24]), df_glow])
    lo_panes, lo_glow = lit_panes(wf[120:]); hi_panes, hi_glow = lit_panes(wf[24:120])
    wf_lo = at.add(wf[120:], lo_glow); wf_hi = at.add(wf[24:120], hi_glow)
    wf_glow = np.concatenate([np.zeros_like(wf[:24]), hi_glow, lo_glow])
    cope_m, cope_d, cope_w = at.add(mf[:24]), at.add(df[:24]), at.add(wf[:24])
    # --- the side walls: each volume's own courses and windows
    corner = art[400, 252]
    ms, ms_g = walls(column_rows(art[296:GL], list(range(8, 44)) + list(range(216, 244))), BACK, rng, corner,
                     [(art[344:378, 48:112], .3, 48), (art[344:378, 48:112], .72, 48)])
    ds, ds_g = walls(column_rows(art[352:GM], list(range(292, 300)) + list(range(366, 382))), BACK - DM, rng, corner,
                     [(art[408:454, 382:478], f, 56) for f in (.2, .5, .8)] + [(art[472:518, 382:478], f, 120) for f in (.2, .5, .8)]
                     + [(art[536:582, 382:478], f, 184) for f in (.2, .5, .8)])
    ws, ws_g = walls(column_rows(art[192:GW], list(range(552, 584)) + list(range(820, 848))), BACK - DW, rng, corner,
                     [(art[230:274, 588:660], .3, 38), (art[230:274, 588:660], .75, 38)])
    ms_r = at.add(ms[24:], ms_g[24:])
    # the chamfered corner: a face 101 long (plan (256, 0) to (288, -96)) with the middle block's courses and, as the
    # art's strip shows, two tall narrow windows (a light of the front's grid each, from 56 to 216 up)
    CH = int(round(np.hypot(32, 96)))
    chw = art[418:582, 322:346]
    ch, ch_g = walls(column_rows(art[352:GM], list(range(292, 300)) + list(range(366, 382))), CH, rng, corner,
                     [(chw, .3, 66), (chw, .7, 66)])
    ch_panes, ch_glow = lit_panes(ch[24:])
    ch_r = at.add(ch[24:], ch_glow)
    chc_r = at.add(tile(df[:24, 40:60], 24, CH))
    # the middle block's side above the main block's roof (before that roof it is behind the chamfered corner)
    ds_up = at.add(ds[24:64, 64:], ds_g[24:64, 64:])
    ws_lo = at.add(ws[120:], ws_g[120:]); ws_hi = at.add(ws[24:120], ws_g[24:120])
    # --- the roofs: the art's grey roof (its tiles laid over each), the copings round them (their face as the fronts
    # draw it, their tops the art's light coping)
    floor = art[110:250, 40:250]
    roofs = {}
    def roof_of(w, d):
        if (w, d) not in roofs: roofs[(w, d)] = at.add(tile(floor, d, w))
        return roofs[(w, d)]
    # --- the helipad on the middle block's roof: the art's circle (rows 155..305, x 320..490), drawn a little squeezed
    # along the depth: laid round (170 by 170)
    hp = art[155:306, 320:491].copy()
    yy, xx = np.mgrid[0:hp.shape[0], 0:hp.shape[1]]
    inside = ((xx - 85) / 86.0) ** 2 + ((yy - 75) / 76.0) ** 2 <= 1
    hp[~inside] = 0
    hp = hp[np.clip(np.round(np.arange(171) * 150 / 170).astype(int), 0, 150)]
    hp_r = at.add(hp)
    # the road arrow on the ground under the canopy (rows 598..640)
    ar = art[598:641, 104:156].copy()
    ar[~((ar[..., :3].astype(int).sum(-1) > 560) & (ar[..., 3] > 0))] = 0
    ar_r = at.add(ar)
    # --- the plant room on the main block's roof (its front rows 92..160, its top from above 8..92) and the fan unit
    pf = art[92:160, 64:256].copy()
    pf_panes, pf_glow = lit_panes(pf)
    pf_r = at.add(pf, pf_glow)
    ptop = art[8:92, 64:256].copy()
    fm = np.zeros(ptop.shape[:2], bool); fm[2:44, 24:82] = True
    pt_r = at.add(fill_rows(ptop, fm))
    fan_t = at.add(art[12:42, 90:144]); fan_f = at.add(art[42:50, 90:144])
    pside, pside_g = walls(column_rows(art[92:160], list(range(70, 80)) + list(range(232, 250))), 80, rng, corner)
    ps_r = at.add(pside, pside_g)
    # --- the entrance canopy: its top (rows 434..544, the cross's foot painted out), its face (rows 544..572); the cross
    # standing on its edge; the pillars
    ct = art[434:544, 64:192].copy(); cm = np.zeros(ct.shape[:2], bool); cm[100:, 44:84] = True
    ct_r = at.add(fill_rows(ct, cm))
    cf = art[544:572, 64:192].copy(); cfm = np.zeros(cf.shape[:2], bool); cfm[:, 36:92] = True
    cf_r = at.add(fill_rows(cf, cfm))
    cr = art[536:577, 104:152].copy(); rgb = cr[..., :3].astype(int)
    blue = (rgb[..., 2] > rgb[..., 0] + 40)
    cr = card_of(cr, dilate(blue, 1) & (blue | (rgb.sum(-1) < 260)) & (cr[..., 3] > 0))
    cr_r = at.add(cr)
    pil = at.add(art[572:632, 72:88]); pil2 = at.add(art[572:632, 168:184])
    pil_c = art[600, 80]
    canopy_c = art[480, 120]
    # --- the wing's slab over the ambulance bays (its face rows 496..504, its top 488..496)
    sl_t = at.add(art[488:496, 560:816]); sl_f = at.add(art[496:504, 560:816])
    HL, HM, HW = 220, 260, 356                     # the walls' tops (the roofs), each coping 24 above
    parts = [
        # the main block, its roof and coping (none on its right: the middle block stands there, taller)
        abut(mbox((0, 288), (0, HL), (0, BACK), (roof_c, wall, wall), ft=mf_r, st=ms_r, tt=roof_of(288, BACK), panes=mf_panes), 'r'),
        mbox((0, 288), (HL, HL + 24), (0, PT), (cope, cope_in, shade(cope_in, .9)), ft=cope_m),
        mbox((0, PT), (HL, HL + 24), (PT, BACK), (cope, cope_in, shade(cope_in, .9))),
        mbox((PT, 64), (HL, HL + 24), (BACK - PT, BACK), (cope, cope_in, shade(cope_in, .9))),
        mbox((256, 288), (HL, HL + 24), (BACK - PT, BACK), (cope, cope_in, shade(cope_in, .9))),
        mbox((64, 256), (HL, HL + 24), (244, BACK), (cope, cope_in, shade(cope_in, .9))),
        # its plant room (standing over the back coping, which runs on behind it) and the fan unit on it
        mbox((64, 256), (HL, HL + 68), (160, 244), (art[60, 200], wall, wall), ft=pf_r, tt=pt_r, st=ps_r, panes=pf_panes),
        mbox((90, 144), (HL + 68, HL + 76), (202, 232), (art[30, 120], art[46, 120], shade(art[46, 120], .85)), ft=fan_f, tt=fan_t),
        # the middle block below the main block's roof: behind it (against the main block and the wing), and the strip
        # between its front part and the main block (open on its left, against the wing)
        abut(mbox((288, 544), (0, HL), (0, BACK), (wall, wall, wall), top=False), 'lr'),
        abut(mbox((288, 544), (0, HL), (DW, 0), (wall, wall, wall), top=False), 'lr'),
        # the wing below the middle block's roof (against the middle block)
        abut(mbox((544, 856), (0, HM), (DW, BACK), (wall, wall, wall), ft=wf_lo, st=ws_lo, panes=lo_panes, top=False), 'l'),
        # the middle block above the main block's roof, behind its front part (against the wing)
        abut(mbox((288, 544), (HL, HM), (DW, BACK), (roof_c, wall, wall), st=ds_up, tt=roof_of(256, BACK - DW)), 'r'),
        # the wing above it, its roof and coping
        mbox((544, 856), (HM, HW), (DW, BACK), (roof_c, wall, wall), ft=wf_hi, st=ws_hi, tt=roof_of(312, BACK - DW), panes=hi_panes),
        mbox((544, 856), (HW, HW + 24), (DW, DW + PT), (cope, cope_in, shade(cope_in, .9)), ft=cope_w),
        mbox((544, 544 + PT), (HW, HW + 24), (DW + PT, BACK), (cope, cope_in, shade(cope_in, .9))),
        mbox((856 - PT, 856), (HW, HW + 24), (DW + PT, BACK), (cope, cope_in, shade(cope_in, .9))),
        mbox((544 + PT, 856 - PT), (HW, HW + 24), (BACK - PT, BACK), (cope, cope_in, shade(cope_in, .9))),
        # the middle block's front part, its roof and coping (along its front, its left and its back; on the right only
        # before the wing)
        abut(mbox((288, 544), (0, HM), (DM, DW), (roof_c, wall, wall), ft=df_r, tt=roof_of(256, DW - DM), panes=df_panes), 'l'),
        # (the helipad lies on its roof where the art draws it: rows 155..305 on a roof 260 up whose front's foot is row
        # 636 put it 71..221 back; laid round, 170 deep, inside the coping)
        decal((320, 490), HM + .5, (54, 224), hp_r),
        abut(mbox((288, 544), (HM, HM + 24), (DM, DM + PT), (cope, cope_in, shade(cope_in, .9)), ft=cope_d), 'l'),
        mbox((288, 288 + PT), (HM, HM + 24), (0, BACK), (cope, cope_in, shade(cope_in, .9))),
        abut(mbox((544 - PT, 544), (HM, HM + 24), (DM + PT, DW), (cope, cope_in, shade(cope_in, .9))), 'r'),
        mbox((288 + PT, 544), (HM, HM + 24), (BACK - PT, BACK), (cope, cope_in, shade(cope_in, .9))),
        # the chamfered corner from the main block's front to the middle block's, and its coping (its faces inside the
        # middle block hidden)
        prism([(256, 0), (288, DM), (288, 0)], (0, HM), (roof_c, wall, wall), ft=ch_r, panes=ch_panes, hide=(1,)),
        prism([(256, 0), (288, DM), (288 + PT, DM + PT), (288 + PT, 0)], (HM, HM + 24), (cope, cope_in, cope_in), ft=chc_r, hide=(1, 2)),
        # the wing's slab over the ambulance bays
        mbox((560, 816), (68, 76), (DW - 10, DW), (art[492, 700], art[500, 700], shade(art[500, 700], .85)), ft=sl_f, tt=sl_t),
        # before the main block: the arrow on the ground, the canopy on its pillars, the cross on its edge
        decal((104, 156), .5, (-100, -58), ar_r),
        mbox((72, 88), (0, 60), (-88, -78), (pil_c, pil_c, shade(pil_c, .8)), ft=pil),
        mbox((168, 184), (0, 60), (-88, -78), (pil_c, pil_c, shade(pil_c, .8)), ft=pil2),
        mbox((64, 192), (60, 88), (-88, 0), (canopy_c, art[556, 80], shade(art[556, 80], .85)), ft=cf_r, tt=ct_r),
        mbox((104, 152), (56, 97), (-90, -90), (canopy_c, canopy_c, canopy_c), ft=cr_r, card=True),
    ]
    setback = 120
    lawn, fence, fence_snow = yard(rng, W, setback + BACK, False, (0, 0, 0), 'natural', [], ground='paving')
    fr = Front(W, HW + 24)
    fr.put(mf, 0, 0, mf_glow); fr.put(wf, 544, 0, wf_glow); fr.put(df, 288, 0, df_glow)
    fr.put(art[496:504, 560:816], 560, 68)
    fr.put(art[572:632, 72:88], 72, 0); fr.put(art[572:632, 168:184], 168, 0)
    fr.put(art[544:572, 64:192], 64, 60); fr.put(cr, 104, 56)
    model = dict(kind='civic', w=W, frontX=(224 - W) / 2, setback=setback, lawn='paving', style='natural', parts=parts,
                 fence=None, roofTint=rgb_int(roof_c), lamp=None, lights='lamp', smoke=[], fpx=0)
    return model, at, fr.img, ds, fr.emis, lawn, fence, fence_snow


# ------------------------------------------------------------------------------------------------ the post office
# landmark_post_1..4 (256 x 416, four colours of one shape): a shop storey (its foot on row 410) under a flat roof with
# a parapet; on the roof, set back, a small upper block (its front rows 80..148) whose own roof carries three hatches and
# two vents, a mast, and a ladder up its front. Over the shop windows an awning slopes out from the wall (its top from
# above rows 320..336, its face with the sign 336..352), hung on two rods from brackets on the wall.
def post_office(shop, rng):
    art = solid(np.array(load(shop['parts'][0]['key'])))
    W = 256; at = Atlas(512)
    G = 410
    wall = art[300, 4]; dark = art[214, 128]; roof_c = art[170, 128]
    # --- the shop storey's front (rows 216..410; the parapet's face 208..216 is the parapet's own), the awning's top and
    # face and the rods painted out: the wall behind them is each row's own colour (the pilasters either side show it)
    sf = art[216:G, 0:256].copy()
    m = np.zeros(sf.shape[:2], bool); m[320 - 216:352 - 216, 16:240] = True
    sf = fill_rows(sf, m)
    # the rods stand before the end windows and the orange band: those carried on behind them (the windows' glass
    # from beside the rod, the band from its own end)
    for (a, b, glass_c, band_c) in ((25, 31, 23, 34), (223, 229, 231, 218)):
        sf[230 - 216:304 - 216, a:b] = sf[230 - 216:304 - 216, glass_c:glass_c + 1]
        sf[304 - 216:320 - 216, a:b] = sf[304 - 216:320 - 216, band_c:band_c + 1]
    sf_panes, sf_glow = lit_panes(sf)
    sf_r = at.add(sf, sf_glow)
    side, side_g = walls(column_rows(art[216:G], list(range(2, 8)) + list(range(248, 254))), 144, rng, art[300, 1],
                         [(art[236:300, 40:96], .3, 20), (art[236:300, 40:96], .72, 20)])
    side_r = at.add(side, side_g)
    # --- its roof (y 194, the parapet 8 above it): the art's grey floor, laid over it; the parapet's face and top
    floor = art[152:190, 40:216]
    floor_r = at.add(tile(floor, 144, 256))
    par_f = at.add(art[208:216, 0:256]); par_t = at.add(art[200:208, 0:256])
    rim_c = art[120, 8]
    # --- the upper block: its front (rows 80..148) with the ladder painted out (each row the colour just right of it),
    # its top (rows 4..80) with the hatches and vents painted out (they stand on it)
    uf = art[80:148, 20:236].copy()
    for y in range(uf.shape[0]): uf[y, 126:152] = uf[y, 154]
    uf_panes, uf_glow = lit_panes(uf)
    uf_r = at.add(uf, uf_glow)
    ut = art[4:80, 20:236].copy()
    um = np.zeros(ut.shape[:2], bool); um[6:38, 16:112] = True; um[44:68, 20:72] = True; um[24:78, 194:216] = True
    ut_r = at.add(fill_rows(ut, um))
    us, us_g = walls(column_rows(art[80:148], list(range(22, 30)) + list(range(226, 234))), 76, rng, art[120, 21],
                     [(art[96:140, 24:60], .5, 16)])
    us_r = at.add(us, us_g)
    # the hatches (three) and the vents (two): low boxes, their tops the art's, their faces its frame's lower rows
    units = []
    for (x0, x1, t0, t1, f1) in ((38, 66, 12, 32, 38), (66, 96, 12, 32, 38), (96, 128, 12, 32, 38), (42, 66, 50, 64, 70), (66, 90, 50, 64, 70)):
        units.append((x0, x1, t1, at.add(art[t0:t1, x0:x1]), at.add(art[t1:f1, x0:x1]), art[(t0 + t1) // 2, (x0 + x1) // 2], art[t1 + 2, (x0 + x1) // 2]))
    # --- the ladder: two rails and their rungs in the art's colours (its rails' light, its ink), standing before the
    # upper block's front from the roof to over its top
    rail, ink = art[112, 149], art[100, 147]
    lad = np.zeros((86, 24, 4), np.uint8)
    for x in (2, 20):
        lad[:, x - 1:x + 3] = ink; lad[:, x:x + 2] = rail
    for y in range(6, 86, 8):
        lad[y - 1:y + 2, 3:21] = ink; lad[y, 3:21] = rail
    lad[..., 3] = np.where(lad[..., :3].sum(-1) > 0, 255, 0)
    lad_r = at.add(lad)
    # the mast on the upper block's roof, at its front right corner (its foot on row 76, 262 up: 72 back): the art's own
    # pole (x 226..229), its cross-bar with the orange tip (rows 44..47) and the stays under it, cut from the roof
    # drawn behind them
    mast = art[32:77, 212:244].copy()
    keep = np.zeros(mast.shape[:2], bool)
    keep[:, 14:18] = True; keep[12:16, 2:32] = True
    stays = np.zeros_like(keep); stays[16:22, 8:26] = True
    # (not the roof's dark top round them)
    keep |= stays & (rgb_int_img(mast) != rgb_int(art[50, 212]))
    mast = card_of(mast, keep & (mast[..., 3] > 0))
    pole = art[56, 226]
    mast_r = at.add(mast)
    # --- the awning: its top (rows 320..336) sloping from the wall out and down to its face (rows 336..352, the sign);
    # the rods hang it from brackets on the wall
    aw_t = at.add(art[320:336, 16:240]); aw_f = at.add(art[336:352, 16:240])
    rod = art[280, 28]
    parts = [
        mbox((0, 256), (0, 194), (0, 144), (roof_c, wall, wall), ft=sf_r, st=side_r, tt=floor_r, panes=sf_panes),
        mbox((0, 256), (194, 202), (0, 8), (art[204, 128], dark, shade(dark, .85)), ft=par_f, tt=par_t),
        mbox((0, 20), (194, 202), (8, 144), (rim_c, dark, shade(rim_c, .8))),
        mbox((236, 256), (194, 202), (8, 144), (rim_c, dark, shade(rim_c, .8))),
        mbox((20, 236), (194, 202), (136, 144), (rim_c, dark, shade(rim_c, .8))),
        mbox((20, 236), (194, 262), (68, 144), (art[40, 180], art[120, 60], art[120, 60]), ft=uf_r, tt=ut_r, st=us_r, panes=uf_panes),
    ]
    for (x0, x1, t1, top, front, tc, fc) in units:
        dfront = 148 - 6 - t1
        parts.append(mbox((x0, x1), (262, 268), (dfront, dfront + (20 if t1 == 32 else 14)), (tc, fc, shade(fc, .85)), ft=front, tt=top, outline=True))
    parts += [
        mbox((212, 244), (262, 306), (72, 72), (pole, pole, pole), ft=mast_r, card=True),
        mbox((146, 170), (194, 280), (66, 66), (rail, rail, rail), ft=lad_r, card=True),
        # the awning, its rods and their brackets
        mbox((26, 30), (176, 180), (-18, 0), (rod, rod, shade(rod, .8)), outline=True),
        mbox((222, 226), (176, 180), (-18, 0), (rod, rod, shade(rod, .8)), outline=True),
        mbox((26, 30), (74, 176), (-18, -15), (rod, rod, shade(rod, .8))),
        mbox((222, 226), (74, 176), (-18, -15), (rod, rod, shade(rod, .8))),
        slope((16, 240), (74, 90), (-18, 0), (art[328, 128], art[344, 60], shade(art[344, 60], .85)), 16, ft=aw_f, tt=aw_t),
    ]
    setback = 32
    lawn, fence, fence_snow = yard(rng, W, setback + 144, False, (0, 0, 0), 'natural', [], ground='paving')
    fr = Front(W, 302)
    fr.put(uf, 20, 194, uf_glow); fr.put(lad, 146, 194); fr.put(mast, 212, 262)
    fr.put(art[208:216, 0:256], 0, 194); fr.put(sf, 0, 0, sf_glow)
    fr.put(art[336:352, 16:240], 16, 58)
    model = dict(kind='civic', w=W, frontX=(224 - W) / 2, setback=setback, lawn='paving', style='natural', parts=parts,
                 fence=None, roofTint=rgb_int(roof_c), lamp=None, lights='shop', smoke=[], fpx=0)
    return model, at, fr.img, side, fr.emis, lawn, fence, fence_snow


BUILDERS = {'landmark_fire_1': fire_station, 'landmark_hospital_1': hospital}


def builder(key):
    """This group's builder for sprite `key`, or None."""
    if key.startswith('landmark_post_'): return post_office
    return BUILDERS.get(key)
