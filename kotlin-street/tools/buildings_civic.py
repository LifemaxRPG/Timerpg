"""Civic and landmark buildings cut from LimeZu's Modern Exteriors (theme 24 Additional Houses and 19 Graveyard), modelled
as make_buildings.py models the school and the clock tower: parts (models.json) wearing one atlas, built by the Filament
and classic painters alike. `compose_civic(spec)` is the entry point (spec['kit'] picks the building), reached from
`buildings_pack.compose_facade`.

The Victorian blocks (V4 the town hall, V5 the old school, V6 the mansard block) are an elevation of brick with arched
windows under a slate roof the art draws from above: the elevation is cut from the art's front (the roof's rows taken
off), the slate near slope is the front face of a hipped roof (every other row, one screen row each: `mb.mhip`), its side
faces tiled anew, its flat top the art's flat roof inside its coping (V4, V6) or the ridge (V5); the clock tower is a
square box wearing its front on every side under a hipped roof whose flat top is the art's skylight frame. All pictures
come from the pack (not in the repo: PACK_ART, see kits.py)."""
import os
import numpy as np
from PIL import Image
import kits
import make_buildings as mb
import buildings_apt as apt
import buildings_civA as civA
from make_buildings import Atlas, mbox, mhip, mrail, shade, solid, rgb_int, column_rows, cut, polygon, hip_side, along_mean, rise_for, fill_period, yard

AH = '24_Additional_Houses_Singles_32x32/24_Additional_Houses_%s_32x32.png'


def art_of(name):
    """A picture of theme 24 (Victorian_House_4 ...) without its half-transparent ground shadow."""
    p = os.path.join(kits.PACK, AH % name)
    if not os.path.exists(p): raise SystemExit(f'missing pack picture {p} (set PACK_ART)')
    return solid(np.array(Image.open(p).convert('RGBA')))


def foot_of(a, x):
    return int(np.nonzero(a[:, x, 3] == 255)[0].max()) + 1


def opaque_rows(img, cols):
    """Each row's colour over `cols` (the median of its opaque pixels; a row with none takes the one above)."""
    rows = np.zeros((img.shape[0], 4), np.uint8); last = np.array([120, 100, 100, 255], np.uint8)
    for y in range(img.shape[0]):
        px = img[y, cols]; px = px[px[:, 3] == 255]
        if len(px): last = np.median(px, axis=0).astype(np.uint8)
        rows[y] = last
    rows[:, 3] = 255
    return rows


def med(a, box):
    r0, r1, c0, c1 = box
    return tuple(int(v) for v in np.median(a[r0:r1, c0:c1].reshape(-1, 4), axis=0)[:3])


def hip_roof(at, art, eaves, E, face_rows, face_x, run, D, flat_rect, win, roof_c, hipc, skip=1, repair=None, d0=0, y0=0):
    """A hipped roof on a wall of height E: the art's slate face rows `face_rows` (x `face_x`, the eave's width) cut to the
    trapezoid the hips make and taken every other row, side faces tiled with its own tiles, the flat top the art's
    `flat_rect` (r0, r1, c0, c1); `win` the columns of the face (whole tiles) the sides are tiled from. Returns the part."""
    r0, r1 = face_rows; x0, x1 = face_x
    region = art[r0:r1, x0:x1].copy()
    if repair is not None: region = fill_period(region, repair(region), 12)
    h, w = region.shape[:2]
    face = cut(region, polygon((h, w), [(0, h), (run, 0), (w - run, 0), (w, h)]))[::2]
    rise = rise_for(face.shape[0], run)
    front_r = at.add(face)
    lq = [(0, 134), (0, 0), (run - 1, 26), (run - 1, 57)]; rq = [(run - 1, 134), (run - 1, 0), (0, 26), (0, 57)]
    lpic = hip_side(face, run, 135, lq, D / 134, win, True); rpic = hip_side(face, run, 135, rq, D / 134, win, False)
    left, right = at.add(lpic), at.add(rpic)
    flush = [at.add(along_mean(lpic)), at.add(along_mean(rpic))]
    c0, c1 = flat_rect[2], flat_rect[3]
    flat = np.ascontiguousarray(art[flat_rect[0]:flat_rect[1], c0:c1][::skip])
    flat_r = at.add(flat)
    x_a, x_b = eaves
    part = mhip((x_a, x_b), (y0 + E, y0 + E + rise), (d0, d0 + D), (x_a + run, x_b - run, d0 + run, d0 + D - run), front_r, left, [v for p in lq for v in p],
                right, [v for p in rq for v in p], roof_c, flat=flat_r, hip=(hipc, hipc), flush=flush)
    return part, rise


def tower_parts(at, art, T, rng, d0=0, dx=0):
    """The clock tower of V4/V5/V7: a square box wearing its front on every side under a hipped roof (the skylight frame its top).
    Returns the parts and the height of its walls and roof."""
    x0, x1 = T['x']; Wd = x1 - x0
    wall0, foot = T['wall']; E = foot - wall0
    body = art[wall0:foot, T['wall_x'][0]:T['wall_x'][1]].copy()
    # the round window is a clock's dial in the clock towers: keep it plain glass (the pack's round window has no hands)
    pan, glow = civA.lit_panes(body)
    body_r = at.add(body, glow)
    run = T['run']; D = Wd
    stone = med(art, T['stone'])
    roof_c = med(art, T['roof'])
    flat = T['flat']
    roof, rise = hip_roof(at, art, (x0 + dx, x1 + dx), E, T['face'], (x0, x1), run, D, flat, T['win'], roof_c, med(art, T['hip']), skip=1, d0=d0)
    box = mbox((T['wall_x'][0] + dx, T['wall_x'][1] + dx), (0, E), (d0, d0 + D), (stone, med(art, T['wallc']), shade(med(art, T['wallc']), .8)),
               ft=body_r, fx=T['wall_x'][0] + dx, tt=at.add(np.ascontiguousarray(art[flat[0]:flat[0] + 12, flat[2]:flat[3]])), st=body_r, panes=pan)
    return [box, roof], E, rise, body, glow


def lot_for(shop, rng, W, D, door):
    setback, style, lawn, fence, fence_snow = apt.lot(shop or dict(yard=dict(front=1, style='formal')), rng, W, D, 16, (door[0], door[1], 16), 'paving')
    return setback, style, lawn, fence, fence_snow


# ------------------------------------------------------------------------------------------------ the Victorian blocks
# Measurements are the art's own pixels (V4 is V5 with the flat roof added over the slate: 192 rows lower).
V5_TOWER = dict(x=(0, 176), wall_x=(4, 172), wall=(182, 0), face=(62, 180), flat=(12, 62, 64, 112), run=64, win=(64, 124),
                stone=(300, 330, 14, 20), roof=(100, 150, 40, 120), hip=(88, 90, 40, 42), wallc=(300, 340, 80, 100))


def _wing_side(art, elev, cols, depth, wins, rng, wall_c):
    rows = column_rows(elev, cols)
    ok = (elev[:, cols, 3] == 255).all(1)
    for y in range(len(rows)):
        if not ok[y]:
            ys = np.nonzero(ok[:y])[0]
            if len(ys): rows[y] = rows[ys[-1]]
    rows[..., 3] = 255
    side, glow = civA.walls(rows, depth, rng, wall_c, wins)
    side[:, :4] = side[:, 4:8]; side[:, -4:] = side[:, -8:-4]
    return side, glow


def compose_vic(spec, shop=None):
    k = spec['kit']; rng = np.random.default_rng(abs(hash(spec['id'])) % (2 ** 31) if False else sum(map(ord, spec['id'])))
    at = Atlas(1024)
    if k == 'v6': return compose_v6(spec, shop, rng, at)
    art = art_of('Victorian_House_5' if k == 'v5' else 'Victorian_House_4')
    off = 192 if k == 'v4' else 0
    foot = foot_of(art, 300)
    T = dict(V5_TOWER)
    T['wall'] = (182 + off, foot); T['face'] = (62 + off, 180 + off); T['flat'] = (12 + off, 62 + off, 64, 112)
    T['stone'] = (300 + off, 330 + off, 14, 20); T['roof'] = (100 + off, 150 + off, 40, 120); T['hip'] = (88 + off, 90 + off, 40, 42); T['wallc'] = (300 + off, 340 + off, 80, 100)
    tparts, ET, rise_t, tbody, tglow = tower_parts(at, art, T, rng)
    # --- the wing: its elevation (the roof's rows taken off), its roof the slate near slope and the art's flat top or ridge
    wall0 = 247 + off; X0, X1 = 176, 556
    elev = art[wall0:foot, X0:X1].copy(); EW = foot - wall0
    pan, glow = civA.lit_panes(elev)
    wall_c = med(art, (wall0 + 40, wall0 + 60, 330 - 0, 345))
    wins = []
    for (r0, r1, c0, c1) in ((285 + off - wall0, 363 + off - wall0, 198 - X0, 252 - X0), (376 + off - wall0, 456 + off - wall0, 198 - X0, 252 - X0)):
        pic = elev[r0:r1, c0:c1].copy(); wins.append((pic, .3, r0)); wins.append((pic, .7, r0))
    side, sglow = _wing_side(art, elev, list(range(100, 120)), 240 if k == 'v5' else 300, wins, rng, wall_c)
    side_r = at.add(np.ascontiguousarray(side), sglow)
    D = 240 if k == 'v5' else 300
    roof_c = med(art, (130 + off, 200 + off, 200, 300))
    def repair(region):    # the chimney on the slope (painted out; built as a box below)
        m = np.zeros(region.shape[:2], bool); m[16:104, 420 - X0:480 - X0] = True
        return m
    if k == 'v5':
        face_rows, flat_rect, run, skip = (118, 246), (96, 118, 200, 500), 100, 1
    else:
        face_rows, flat_rect, run, skip = (310 + 0, 438), (72, 286, 184, 516), 70, 1
    roof, rise = hip_roof(at, art, (X0, X1 + 8), EW, face_rows, (X0, X1 + 8), run, D, flat_rect, (230, 290), roof_c, med(art, (90 + off, 92 + off, 300, 302)), repair=repair)
    body_r = at.add(elev, glow)
    stone = med(art, (wall0 + 5, wall0 + 8, 300, 320))
    wing = mbox((X0, X1), (0, EW), (0, D), (roof_c, wall_c, shade(wall_c, .8)), ft=body_r, fx=X0, st=side_r, panes=pan, top=False)
    parts = [wing, roof]
    # the chimney on the slope's top: brick stack under a stone cap
    # the chimney the art draws on the slope (painted out of the slate above): a brick stack under a stone cap, standing on it
    brick = med(art, (200 + off, 210 + off, 440, 460)); cap_c = med(art, (146 + off, 150 + off, 440, 460))
    st = at.add(np.ascontiguousarray(art[192 + off:214 + off, 428:472])); cp = at.add(np.ascontiguousarray(art[146 + off:160 + off, 424:476]))
    tp = at.add(np.ascontiguousarray(art[134 + off:146 + off, 424:476]))
    yb = EW + rise * 26 / run - 4
    parts.insert(2, mbox((432, 468), (yb, yb + 34), (22, 42), (brick, brick, shade(brick, .8)), ft=st))
    parts.insert(3, mbox((427, 473), (yb + 34, yb + 46), (19, 45), (cap_c, cap_c, shade(cap_c, .8)), ft=cp, tt=tp))
    parts += tparts
    door = (60, 116)
    setback, style, lawn, fence, fence_snow = lot_for(shop, rng, X1, D, door)
    model = apt.model_of('school', X1, setback, style, parts, roof_c, 'shop', 'paving', fence, lamp=None)
    H = max(EW, tbody.shape[0]); front = np.zeros((H, X1, 4), np.uint8)
    front[H - EW:, X0:X1] = elev; front[H - tbody.shape[0]:, T['wall_x'][0]:T['wall_x'][1]] = tbody
    emis = np.zeros_like(front); emis[H - EW:, X0:X1] = glow; emis[H - tbody.shape[0]:, T['wall_x'][0]:T['wall_x'][1]] = tglow
    return model, at, front, side, emis, lawn, fence, fence_snow


def compose_v6(spec, shop, rng, at):
    art = art_of('Victorian_House_6')
    foot = foot_of(art, 30)
    wall0 = 440; X0, X1 = 2, 374
    elev = art[wall0:foot, X0:X1].copy(); EW = foot - wall0
    pan, glow = civA.lit_panes(elev)
    wall_c = med(art, (wall0 + 40, wall0 + 60, 120, 130))
    wins = []
    for (r0, r1) in ((478 - wall0, 545 - wall0), (580 - wall0, 645 - wall0)):
        pic = elev[r0:r1, 64 - X0:128 - X0].copy(); wins.append((pic, .3, r0)); wins.append((pic, .7, r0))
    D = 330
    side, sglow = _wing_side(art, elev, list(range(130, 138)), D, wins, rng, wall_c)
    side_r = at.add(np.ascontiguousarray(side), sglow)
    roof_c = med(art, (390, 430, 100, 300))
    roof, rise = hip_roof(at, art, (0, 376), EW, (372, 440), (0, 376), 60, D, (36, 366, 62, 322), (100, 160), roof_c, med(art, (390, 392, 20, 22)))
    body_r = at.add(elev, glow)
    wing = mbox((X0, X1), (0, EW), (0, D), (roof_c, wall_c, shade(wall_c, .8)), ft=body_r, fx=X0, st=side_r, panes=pan, top=False)
    door = (150, 226)
    setback, style, lawn, fence, fence_snow = lot_for(shop, rng, 376, D, door)
    model = apt.model_of('school', 376, setback, style, [wing, roof], roof_c, 'shop', 'paving', fence, lamp=None)
    return model, at, elev, side, glow, lawn, fence, fence_snow


def tower_cfg(art, off=0):
    T = dict(V5_TOWER); foot = foot_of(art, 300 if art.shape[1] > 200 else 100)
    T['wall'] = (182 + off, foot); T['face'] = (62 + off, 180 + off); T['flat'] = (12 + off, 62 + off, 64, 112)
    T['stone'] = (300 + off, 330 + off, 14, 20); T['roof'] = (100 + off, 150 + off, 40, 120); T['hip'] = (88 + off, 90 + off, 40, 42); T['wallc'] = (300 + off, 340 + off, 80 + 0, 100)
    return T


GAB = dict(  # the gabled wing of V1, V2, V3 (art x of its eaves, rows: slope top, the ridge's apex, the eave line, the foot)
    x=(0, 176), roof_top=190, apex=497, eave=550, ridge_end=195, D=300)


def gabled_wing(at, art, ax, shop, rng, d0=0):
    """V1/V2/V3's front-gabled wing: its wall and the gable's triangle (the art's front, cut above the rake), two slopes (the art's
    from above, laid on quads), 300 deep; art x `ax` (its eaves) .. +176. Returns the parts, its eave and ridge heights."""
    foot = foot_of(art, ax + 20)
    top, ap, ev = GAB['roof_top'], GAB['apex'], GAB['eave']
    W = 176; D = GAB['D']
    E = foot - ev; R = foot - ap
    face = art[ap:foot, ax:ax + W].copy(); h = face.shape[0]; t = ev - ap
    face = cut(face, polygon((h, W), [(0, h), (0, t), (W // 2, 0), (W, t), (W, h)]))
    pan, glow = civA.lit_panes(face)
    face_r = at.add(face, glow)
    half = W // 2
    lp_img = art[top:ev + 6, ax:ax + half]; rp_img = art[top:ev + 6, ax + half:ax + W]
    hl, hr_ = lp_img.shape[0], rp_img.shape[0]
    q_fe, q_ap, q_re, q_be = ev - top, ap - top, GAB['ridge_end'] - top, 256 - top
    lq = [(0, q_fe), (half - 1, q_ap), (half - 1, q_re), (0, q_be)]; rq = [(half - 1, q_fe), (0, q_ap), (0, q_re), (half - 1, q_be)]
    left = at.add(cut(lp_img, polygon(lp_img.shape[:2], lq))); right = at.add(cut(rp_img, polygon(rp_img.shape[:2], rq)))
    roof_c = med(art, (330, 400, ax + 10, ax + 60)); rake_c = med(art, (ap + 6, ap + 8, ax + 20, ax + 30))
    rows = column_rows(art[ev:foot], list(range(ax + 36, ax + 44)))
    ok = (art[ev:foot, ax + 36:ax + 44, 3] == 255).all(1)
    wins = []
    for (r0, r1) in ((72, 150), (166, 244)):
        pic = face[r0:r1, 66:110].copy(); wins += [(pic, .3, r0 - (ev - ap)), (pic, .7, r0 - (ev - ap))]
    side, sglow = civA.walls(rows, D, rng, med(art, (ev + 60, ev + 70, ax + 8, ax + 12)), wins)
    side_r = at.add(side, sglow)
    wall_c = med(art, (ev + 60, ev + 70, ax + 60, ax + 64))
    wall = mbox((ax, ax + W), (0, E), (d0, d0 + D), (roof_c, wall_c, shade(wall_c, .8)), ft=face_r, fx=ax, st=side_r, panes=pan, top=False)
    gab = mgable_wrap((ax, ax + W), (E, R), (d0, d0 + D), left, [v for p in lq for v in p], right, [v for p in rq for v in p],
                      (rake_c, rake_c), roof_c, lip=rake_c)
    return [wall, gab], E, R, face, glow


def mgable_wrap(x, y, d, left, lp, right, rp, rake, roofc, lip=None):
    return mb.mgable(x, y, d, left, lp, right, rp, rake, roofc, lip=lip)


def main_wing_v1(at, art, rng):
    """V1's long wing to the right of the gabled one: brick elevation under a mansard (slate face, the art's flat roof inside its coping),
    set back 26 (its foot is drawn 26 rows higher), and the tall pier at the far end."""
    foot = foot_of(art, 300); wall0 = 513; X0, X1 = 184, 448; D = 300; d0 = 26
    elev = art[wall0:foot, X0:X1].copy(); EW = foot - wall0
    pan, glow = civA.lit_panes(elev)
    wall_c = med(art, (wall0 + 60, wall0 + 70, 256, 262))
    wins = []
    for (r0, r1) in ((550 - wall0, 615 - wall0), (637 - wall0, 700 - wall0)):
        pic = elev[r0:r1, 196 - X0:250 - X0].copy(); wins += [(pic, .3, r0), (pic, .7, r0)]
    side, sglow = _wing_side(art, elev, list(range(254 - X0, 262 - X0)), D, wins, rng, wall_c)
    side_r = at.add(side, sglow)
    roof_c = med(art, (450, 500, 250, 340))
    roof, rise = hip_roof(at, art, (X0, X1), EW, (432, 512), (X0, X1), 60, D, (100, 424, 160, 440), (100, 160), roof_c, med(art, (440, 442, 250, 252)), d0=d0)
    wing = mbox((X0, X1), (0, EW), (d0, d0 + D), (roof_c, wall_c, shade(wall_c, .8)), ft=at.add(elev, glow), fx=X0, st=side_r, panes=pan, top=False)
    # the pier at the far end
    pe = foot_of(art, 470) - wall0; pier = np.ascontiguousarray(art[wall0:wall0 + pe, 450:504])
    pc = med(art, (580, 620, 460, 490))
    pside = np.repeat(pier[:, 24:28][:, :1].copy(), 100, axis=1)
    pier_box = mbox((450, 504), (0, pe), (d0, d0 + 100), (med(art, (440, 460, 460, 490)), pc, shade(pc, .8)), ft=at.add(pier), tt=at.add(np.ascontiguousarray(np.tile(art[440:446, 460:490], (8, 2, 1)))), st=at.add(np.ascontiguousarray(pside)))
    return [wing, roof, pier_box]


def v3_porch(at, art):
    """V3's side entrance (the art's x 18..100: a stair rising back from the street to a landing, the doorway on the
    landing, two posts carrying a balcony whose floor and balustrade the art draws from above) as its parts: eight steps
    5 up and 5 back each, the landing's base, the doorway as a wall at its back, the posts, the balcony slab (its edge the
    art's, its floor the art's floor between the balusters) and a balustrade along its front and its open side."""
    stone = med(art, (640, 644, 40, 90)); dark = shade(stone, .7)
    tread = np.ascontiguousarray(art[641:646, 34:100])
    parts = []
    for i in range(8):
        tr = at.add(tread)
        parts.append(mbox((34, 100), (0, 5 * (i + 1)), (30 + 5 * i, 70), (stone, stone, shade(stone, .8)), ft=tr, fx=34))
    base_st = at.add(np.ascontiguousarray(civA.tile(art[700:712, 40:60], 40, 80)))
    parts.append(mbox((22, 100), (0, 40), (70, 150), (stone, stone, shade(stone, .8)), st=base_st))
    door = np.ascontiguousarray(art[555:612, 30:100])
    dpan, dglow = civA.lit_panes(door)
    brick_st = at.add(np.ascontiguousarray(civA.tile(art[655:675, 140:170], door.shape[0], 40)))
    bc = med(art, (655, 675, 140, 170))
    parts.append(mbox((22, 100), (40, 40 + door.shape[0]), (110, 150), (stone, bc, shade(bc, .8)), ft=at.add(door, dglow), fx=30, st=brick_st, panes=dpan, top=False))
    post = np.ascontiguousarray(art[545:620, 18:30])
    for (a, b) in ((22, 32), (90, 100)):
        pr = at.add(post)
        parts.append(mbox((a, b), (40, 104), (70, 80), (stone, stone, shade(stone, .8)), ft=pr, fx=a, st=pr))
    edge = np.ascontiguousarray(art[530:545, 22:100]); floor = np.ascontiguousarray(civA.tile(art[465:510, 40:90], 86, 84))
    parts.append(mbox((18, 102), (104, 112), (66, 152), (stone, stone, shade(stone, .8)), ft=at.add(edge), fx=18, tt=at.add(floor)))
    for a, b in (((19, 112, 67), (101, 112, 67)), ((19, 112, 67), (19, 112, 150))):
        parts.append(mrail(a, b, 14, stone, posts=8, w=3))
    return parts


def compose_gabled(spec, shop=None):
    k = spec['kit']; rng = np.random.default_rng(sum(map(ord, spec['id'])))
    at = Atlas(1024)
    art = art_of({'v1': 'Victorian_House_1', 'v2': 'Victorian_House_2', 'v3': 'Victorian_House_3'}[k])
    ax = {'v1': 8, 'v2': 8, 'v3': 104}[k]
    tower_art = art_of('Victorian_House_7')
    T = tower_cfg(tower_art)
    # the tower stands at the gable's back end, its face 300 behind the front, the art's tower elevation on every side
    tp, ET, rise_t, tbody, tglow = tower_parts(at, tower_art, T, rng, d0=GAB['D'], dx=ax)
    gp, E, R, face, glow = gabled_wing(at, art, ax, shop, rng)
    parts = gp + tp
    if k == 'v1':
        parts = main_wing_v1(at, art, rng) + parts
    if k == 'v3':
        parts = v3_porch(at, art) + parts
    PXW = (504 if k == 'v1' else ax + 176 + 8)
    door = (ax + 70, ax + 106)
    setback, style, lawn, fence, fence_snow = lot_for(shop, rng, PXW, GAB['D'] + 176, door)
    roof_c = med(art, (330, 400, ax + 10, ax + 60))
    model = apt.model_of('school', PXW, setback, style, parts, roof_c, 'shop', 'paving', fence, lamp=None)
    side = np.zeros((E, GAB['D'], 4), np.uint8) if False else np.array(Image.new('RGBA', (GAB['D'], max(E, 1)), (150, 110, 100, 255)))
    return model, at, face, side, glow, lawn, fence, fence_snow


def compose_tower7(spec, shop=None):
    rng = np.random.default_rng(sum(map(ord, spec['id']))); at = Atlas(1024)
    art = art_of('Victorian_House_7'); T = tower_cfg(art)
    tp, ET, rise, tbody, tglow = tower_parts(at, art, T, rng)
    setback, style, lawn, fence, fence_snow = lot_for(shop, rng, 176, 176, (60, 116))
    model = apt.model_of('tower', 176, setback, style, tp, med(art, T['roof']), 'lamp', 'paving', fence, lamp=None)
    return model, at, tbody, tbody, tglow, lawn, fence, fence_snow


GRAVE = '19_Graveyard_Singles_32x32/ME_Singles_Graveyard_32x32_%s.png'


def grave_art(name):
    return solid(np.array(Image.open(os.path.join(kits.PACK, GRAVE % name)).convert('RGBA')))


# ------------------------------------------------------------------------------------------------ the Grace Chapel
# The art (160 x 288) is drawn oblique from above (a thing d back stands d rows higher): a nave whose ridge runs back from
# the street, its front gable wall (the portal, the rose window, the stepped coping, the cross on the apex) a true
# elevation with its foot on row 272, its brown shingle roof drawn from above (78 rows deep, the back gable's coping and
# cross over it), and a buttress at each corner (foot on row 288: 16 before the wall) carrying a pinnacle. Each of those
# is its own part here; the nave is made deeper than the art's (8 tiles), with buttresses and lancet windows along its
# sides, a lower chancel with its own gable at the back and a belfry and spire on the ridge (the art has no steeple).
CH_FOOT = 272                  # the front wall's foot row
CH_E, CH_R = 108, 160          # the walls' height and the ridge's (apex row 112); the eaves (row 172, 100 up) at 45 degrees
CH_X0, CH_X1 = 28, 132         # the nave's side walls; its eaves overhang to 20 and 140
CH_D, CH_RD = 256, 16          # the nave's depth; the portal's depth into the front wall
# the art's stone palette (its own colours)
DK, DK2 = (58, 58, 80), (70, 70, 94)
WALL, WALL_L, WALL_D = (86, 89, 114), (90, 94, 120), (80, 82, 108)
MID, MID2, LT, LT2, LT3 = (108, 110, 133), (125, 127, 153), (139, 139, 171), (157, 163, 183), (172, 175, 191)
GL1, GL2 = (143, 161, 200), (115, 140, 168)
STAIN, MOSS = (94, 80, 81), (52, 96, 88)


def _px(img, y, x, c):
    if 0 <= y < img.shape[0] and 0 <= x < img.shape[1]: img[y, x, :3] = c; img[y, x, 3] = 255


def ashlar(h, w, rng, bw=14, bh=10, stains=True):
    """Coursed stone in the chapel's own colours: blocks bw x bh, every other course half a block over, a dark joint under
    and right of each block, its top edge a shade lighter, a few stains and tufts of moss. Courses run from the bottom
    row up, so walls of one height share them."""
    img = np.zeros((h, w, 4), np.uint8); img[..., 3] = 255; img[..., :3] = WALL
    for k, r1 in enumerate(range(h, 0, -bh)):
        r0 = max(0, r1 - bh); off = (k % 2) * (bw // 2)
        for c in range(-off, w, bw):
            x0, x1 = max(0, c), min(w, c + bw)
            if x1 <= x0: continue
            img[r0:r1, x0:x1, :3] = (WALL, WALL, WALL_L, WALL_D)[int(rng.integers(0, 4))]
            img[r0, x0:x1 - 1, :3] = WALL_L if r0 > 0 else img[r0, x0, :3]
            img[r1 - 1, x0:x1, :3] = DK2
            if 0 <= c + bw - 1 < w: img[r0:r1, c + bw - 1, :3] = DK2
    if stains:
        for _ in range(max(1, h * w // 600)):
            y, x = int(rng.integers(0, h)), int(rng.integers(0, w))
            col = STAIN if rng.random() < .6 else MOSS
            for dy in range(int(rng.integers(1, 4))): _px(img, y + dy, x, col)
    return img


def lancet(w, h, sill=True):
    """A tall pointed (lancet) window w x h: a light stone frame, leaded glass in the rose window's blues (a mullion down
    its middle, a bar every 10 rows), a sill under it. Returns it and its night glow (the glass, warm)."""
    img = np.zeros((h, w, 4), np.uint8); glow = np.zeros_like(img)
    hw = w / 2; pt = int(round(w * .75))                      # the pointed head's height
    def half(y, inset):                                          # the half-width at row y (an equilateral pointed arch)
        if y >= pt: return hw - inset
        t = (pt - y) / pt
        if t >= 1: return -1.
        return max(0., (hw - inset) * (1 - t * t) ** .5 - t * inset * .3)
    body = h - (3 if sill else 0)
    for y in range(body):
        for x in range(w):
            dx = abs(x + .5 - hw)
            if dx <= half(y, 0):
                inner = dx <= half(y - 2, 2) and y >= 2
                if not inner:
                    c = LT2 if (x < hw and y < body - 1) else MID
                    if y == body - 1: c = MID
                    img[y, x, :3] = c
                else:
                    g = GL2 if ((x + y // 3) % 4) else GL1
                    if abs(x + .5 - hw) < .6 or (y > pt and (y - pt) % 10 == 0): g = DK2
                    img[y, x, :3] = g
                    if g != DK2: glow[y, x] = (255, 196, 110, 235)
                    else: glow[y, x] = (200, 140, 70, 160)
                img[y, x, 3] = 255
    if sill:
        img[body:, :, :3] = LT; img[body:, :, 3] = 255; img[h - 1, :, :3] = DK2
    return img, glow


def louvre(w, h):
    """A belfry's opening: a pointed arch with its louvre boards (slanting slats, light above, dark between)."""
    img, _ = lancet(w, h, sill=True)
    m = (img[..., :3] == GL1).all(-1) | (img[..., :3] == GL2).all(-1) | (img[..., :3] == DK2).all(-1)
    ys, xs = np.nonzero(m)
    for y, x in zip(ys, xs):
        img[y, x, :3] = (MID2 if (y % 4) == 0 else DK2 if (y % 4) == 1 else DK)
    return img


def shingle_field(art, rows):
    """The art's brown shingles, unrolled: each column x of its left slope (20 the eave, 77 at the ridge) from the slope's
    front edge back (the art draws a thing d back d rows higher), so a column runs up the slope and a row along the
    depth; a clean stretch of 40 rows (free of the copings and crosses) repeated to `rows`, the back row first."""
    cols = []
    for x in range(20, 77):
        f = 172 - (x - 20)
        cols.append(art[f - 74:f - 34, x])
    block = np.ascontiguousarray(np.stack(cols, 1)); block[..., 3] = 255
    return civA.tile(block, rows, block.shape[1])


def pyramid(at, x, y, d, front_pic, side_pic, roof_c, top=2.):
    """A four-sided pyramid (a pinnacle, a spire) as a hipped roof to a small square top: its front (and back) wearing
    `front_pic` cut to the triangle, its sides `side_pic` (columns up the face, rows along the depth) on their quads."""
    x0, x1 = x; d0, d1 = d
    cx, cd = (x0 + x1) / 2, (d0 + d1) / 2
    fh, fw = front_pic.shape[:2]; t = top / (x1 - x0) * fw / 2
    tri = cut(front_pic, polygon((fh, fw), [(0, fh), (fw / 2 - t, 0), (fw / 2 + t, 0), (fw, fh)]))
    sh, sw = side_pic.shape[:2]; ts = top / (d1 - d0) * sh / 2
    lq = [(0, sh), (0, 0), (sw, sh / 2 - ts), (sw, sh / 2 + ts)]
    rq = [(sw, sh), (sw, 0), (0, sh / 2 - ts), (0, sh / 2 + ts)]
    return mhip(x, y, d, (cx - top / 2, cx + top / 2, cd - top / 2, cd + top / 2), at.add(tri), at.add(side_pic), [v for p in lq for v in p],
                at.add(np.ascontiguousarray(side_pic[:, ::-1])), [v for p in rq for v in p], roof_c, hip=(roof_c, shade(roof_c, .7)))


def coping_pic(w, h):
    """A coping stone's face: its lit top edge, the stone, a dark joint under it."""
    img = np.zeros((h, w, 4), np.uint8); img[..., 3] = 255; img[..., :3] = MID
    img[:2, :, :3] = LT2; img[2, :, :3] = LT; img[-1, :, :3] = DK2; img[:, -1, :3] = DK2
    return img


def stepped_coping(at, x_eave, x_apex, y_eave, y_apex, d, n, rng):
    """The gable's stepped coping, as the art draws it: n stone steps up each rake from the eave to the apex, each a box
    standing proud of the wall and over the roof's edge, its top 3 over the slope at its upper end, its foot 6 under it
    at its lower end (over the art's steps, which the front picture leaves out); a cap under the apex. `d` the depths it stands between."""
    parts = []
    run = x_apex - x_eave
    for side in (0, 1):
        for i in range(n):
            a, b = x_eave + run * i / n, x_eave + run * (i + 1) / n
            ylo = y_eave + (y_apex - y_eave) * i / n - 6; yhi = y_eave + (y_apex - y_eave) * (i + 1) / n + 3
            xs = (a, b) if side == 0 else (2 * x_apex - b, 2 * x_apex - a)
            pic = coping_pic(int(round(b - a)), int(round(yhi - ylo)))
            r = at.add(pic)
            parts.append(mbox(xs, (ylo, yhi), d, (LT, MID, shade(MID, .85)), ft=r, st=r))
    cap = at.add(coping_pic(12, 10))
    parts.append(mbox((x_apex - 6, x_apex + 6), (y_apex - 4, y_apex + 6), (d[0] - 1, d[1] + 1), (LT, MID, shade(MID, .85)), ft=cap, st=cap))
    return parts


def buttress(at, art, rng, mirror=False, back=False, pin=True):
    """A corner buttress: a square pier (the art's panelled pier as its face), a weathering, a narrower upper stage and a
    pinnacle (a stone pyramid to a light tip). x (outer, inner) as the left one has them; `mirror` the right one."""
    def X(a, b): return (160 - b, 160 - a) if mirror else (a, b)
    def D(a, b): return (CH_D - b, CH_D - a) if back else (a, b)
    low = np.ascontiguousarray(art[204:288, 133:160] if mirror else art[204:288, 0:27])
    up = np.ascontiguousarray(art[168:200, 136:154] if mirror else art[168:200, 6:24])
    lr, ur = at.add(low), at.add(up)
    parts = [mbox(X(1, 28), (0, 84), D(-16, 10), (LT, WALL, shade(WALL, .8)), ft=lr, fx=X(1, 28)[0], st=lr),
             mbox(X(3, 27), (84, 89), D(-14, 9), (LT2, MID, shade(MID, .8)), ft=at.add(coping_pic(24, 5)), fx=X(3, 27)[0]),
             mbox(X(6, 24), (89, 124), D(-11, 6), (LT, WALL, shade(WALL, .8)), ft=ur, fx=X(6, 24)[0], st=ur),
             mbox(X(4, 26), (124, 128), D(-13, 8), (LT2, MID, shade(MID, .8)), ft=at.add(coping_pic(22, 4)), fx=X(4, 26)[0])]
    if pin:
        st = ashlar(44, 16, rng, bw=8, bh=6, stains=False)
        parts.append(pyramid(at, X(7, 23), (128, 172), D(-10.5, 5.5), st, np.ascontiguousarray(st[:, :17]), MID2, top=1.5))
        parts.append(mbox(X(14, 16), (171, 178), D(-3.5, -1.5), (LT3, LT2, LT), fx=X(14, 16)[0]))
    return parts


def side_buttress(at, rng, c, mirror=False):
    """A buttress along a long wall at depth c: a pier stepping in twice under lit weatherings."""
    def X(a, b): return (160 - b, 160 - a) if mirror else (a, b)
    s1 = at.add(ashlar(56, 14, rng, bw=7, bh=8, stains=False)); s2 = at.add(ashlar(24, 9, rng, bw=9, bh=8, stains=False))
    return [mbox(X(14, 28), (0, 56), (c - 7, c + 7), (LT, WALL, shade(WALL, .8)), ft=s1, fx=X(14, 28)[0], st=s1),
            mbox(X(16, 28), (56, 60), (c - 6, c + 6), (LT2, MID, shade(MID, .8)), ft=at.add(coping_pic(12, 4)), fx=X(16, 28)[0]),
            mbox(X(19, 28), (60, 84), (c - 5, c + 5), (LT, WALL, shade(WALL, .8)), ft=s2, fx=X(19, 28)[0], st=s2),
            mbox(X(21, 28), (84, 88), (c - 4, c + 4), (LT2, MID, shade(MID, .8)), ft=at.add(coping_pic(7, 4)), fx=X(21, 28)[0])]


def bay(rng, w, h, lw, lh, foot):
    """A stretch of side wall w wide: coursed stone with a lancet window lw x lh in its middle, its sill `foot` up."""
    img = ashlar(h, w, rng); glow = np.zeros_like(img)
    win, wg = lancet(lw, lh)
    x0 = (w - lw) // 2; y0 = h - foot - lh
    m = win[..., 3] > 0
    img[y0:y0 + lh, x0:x0 + lw][m] = win[m]; glow[y0:y0 + lh, x0:x0 + lw] = wg
    return img, glow


def portal_doors(art, w, h, floor):
    """The wall at the back of the portal: stone round a pair of oak doors under the pointed arch (the art shows the
    portal dark), their planks in the roof's browns, iron straps; `floor` rows of the step under them."""
    img = ashlar(h, w, np.random.default_rng(7), stains=False)
    wood = [tuple(int(v) for v in c) for c in (art[120, 30, :3], art[124, 40, :3], art[130, 34, :3])]
    pt = 18; hw = w / 2 - 6
    for y in range(h - floor):
        for x in range(w):
            dx = abs(x + .5 - w / 2)
            t = (pt - (y - 6)) / pt if y - 6 < pt else 0
            lim = hw * (1 - t * t) ** .5 if 0 <= t <= 1 else (hw if t <= 0 else -1)
            if y < 6 or dx > lim + 2: continue
            if dx > lim: img[y, x, :3] = LT2 if x < w / 2 else MID; continue
            c = wood[(x // 4) % 3]
            if x % 4 == 3: c = shade(c, .7)
            if abs(x + .5 - w / 2) < 1: c = DK
            if (y - 6) in (pt + 6, h - floor - 12): c = DK2
            img[y, x, :3] = c
    img[h - floor:, :, :3] = LT
    return img


def back_wall(rng, glow_c=(255, 196, 110, 235)):
    """The nave's back elevation (as seen from behind): coursed stone up into the gable, a lancet either side of the
    chancel and a round window high in the gable."""
    W = CH_X1 - CH_X0; H = CH_R - 1
    img = ashlar(H, W, rng); glow = np.zeros_like(img)
    keep = polygon((H, W), [(0, H), (0, H - CH_E), (W / 2, 0), (W, H - CH_E), (W, H)])
    img[~keep] = 0
    for cx in (14, W - 14):
        win, wg = lancet(12, 40); x0 = cx - 6; y0 = H - 22 - 40
        m = win[..., 3] > 0; img[y0:y0 + 40, x0:x0 + 12][m] = win[m]; glow[y0:y0 + 40, x0:x0 + 12] = wg
    # the oculus: a ring of light stone round leaded glass
    cy, cx, r = H - CH_E - 22, W / 2, 9
    for y in range(int(cy - r - 1), int(cy + r + 2)):
        for x in range(int(cx - r - 1), int(cx + r + 2)):
            q = ((x + .5 - cx) ** 2 + (y + .5 - cy) ** 2) ** .5
            if q <= r - 2.5:
                c = DK2 if (abs(x + .5 - cx) < .7 or abs(y + .5 - cy) < .7) else (GL1 if (x + y) % 3 else GL2)
                img[y, x, :3] = c; glow[y, x] = glow_c if c != DK2 else (200, 140, 70, 160)
            elif q <= r: img[y, x, :3] = LT2 if y < cy else MID
    return img, glow


def chapel(spec, shop=None):
    """Grace_Chapel (with or without its gate), cut into its parts (see CH_FOOT above)."""
    gate = spec['kit'] == 'chapel_gate'
    art = grave_art('Grace_Chapel_With_Gate' if gate else 'Grace_Chapel')
    rng = np.random.default_rng(sum(map(ord, spec['id']))); at = Atlas(1024)
    E, R, D, RD, X0, X1 = CH_E, CH_R, CH_D, CH_RD, CH_X0, CH_X1
    W = X1 - X0
    # --- the front gable wall: the art's elevation from the apex down, cut under the rake (the coping steps stand over
    # it) and open where the portal goes into the wall
    top = CH_FOOT - (R - 1)
    face = art[top:CH_FOOT, X0:X1].copy(); fh = face.shape[0]
    # (under the roof's slope, the art's own rake: from the eave at x 20, E - 8 up, to the apex at 45 degrees)
    xs = np.arange(X0, X1) + .5
    roof_h = (E - 8) + (R - (E - 8)) * (60 - np.abs(xs - 80)) / 60
    keep = np.arange(fh)[:, None] >= fh - roof_h[None, :] - 4
    # the art's brown roof seen over the coping's steps is not the wall
    rgb = face[..., :3].astype(int)
    keep &= ~((rgb[..., 0] > rgb[..., 2] + 8) & (np.arange(fh)[:, None] < fh - E + 10))
    spring, apex_a = 60, 77                                          # the portal's springing and its arch's apex (y)
    keep &= ~polygon((fh, W), [(50 - X0, fh), (50 - X0, fh - spring), (80 - X0, fh - apex_a), (110 - X0, fh - spring), (110 - X0, fh)])
    face = cut(face, keep)
    face_r = at.add(face)
    # --- the roof: the art's shingles from above on both slopes (the lit left one, mirrored for the right), the full depth
    field = shingle_field(art, D + 6)
    hw = field.shape[1]
    lq = [(0, D + 6), (hw, D + 6), (hw, 0), (0, 0)]; rq = [(hw, D + 6), (0, D + 6), (0, 0), (hw, 0)]
    left = at.add(field); right = at.add(np.ascontiguousarray(field[:, ::-1]))
    roof_c = tuple(int(v) for v in np.median(field[..., :3].reshape(-1, 3), axis=0))
    gab = mb.mgable((20, 140), (E - 8, R), (-3, D + 3), left, [v for p in lq for v in p], right, [v for p in rq for v in p], (MID, MID), roof_c, lip=MID)
    ridge = at.add(np.ascontiguousarray(civA.tile(art[50:100, 75:85], D + 6, 10)))
    ridge_cap = mbox((75, 85), (R - 2, R + 4), (-3, D + 3), (LT, MID, shade(MID, .8)), tt=ridge)
    # --- the walls: the nave (its front the back of the portal: doors), the portal's jambs and the wall over its arch
    doors = portal_doors(art, 60, apex_a + 4, 14)
    bay_img, bay_glow = bay(rng, 60, E, 14, 46, 30)
    bay_r = at.add(bay_img, bay_glow)
    nave = mbox((X0, X1), (0, E), (RD, D), (roof_c, WALL, shade(WALL, .8)), ft=at.add(doors), fx=50, st=bay_r, top=False)
    jamb_st = at.add(ashlar(E, RD, rng, stains=False))
    soffit = at.add(ashlar(20, RD, rng, bw=8, bh=5, stains=False))
    parts = [nave,
             mbox((X0, 50), (0, E), (0, RD), (roof_c, WALL, shade(WALL, .8)), st=jamb_st, top=False),
             mbox((110, X1), (0, E), (0, RD), (roof_c, WALL, shade(WALL, .8)), st=jamb_st, top=False)]
    # the arch's soffit in steps (darker: in the portal's shade), then the wall over it carrying the whole front picture
    for (a, b, y0) in ((50, 57, 61), (57, 64, 65), (64, 71, 69), (71, 76, 73), (84, 89, 73), (89, 96, 69), (96, 103, 65), (103, 110, 61)):
        parts.append(mbox((a, b), (y0, apex_a), (0, RD), (MID, WALL_D, shade(WALL_D, .8)), st=soffit, top=False))
    parts.append(mbox((50, 110), (apex_a, E), (0, RD), (roof_c, WALL, shade(WALL, .8)), top=False))
    # the front picture itself (the wall and its gable, open in the portal) on the wall's face: a card, so the portal
    # shows through it (a box's front is backed in its own colour)
    parts.append(mbox((X0, X1), (0, fh), (0, 0), (WALL, WALL, WALL), ft=face_r, card=True))
    # the steps up to the portal (its floor the top one, running in under the arch)
    for i, (a, b, d0) in enumerate(((42, 118, -16), (46, 114, -11), (50, 110, -6))):
        sp = coping_pic(b - a, 5)
        parts.append(mbox((a, b), (i * 5 - (1 if i == 2 else 0), i * 5 + 5 - (1 if i == 2 else 0)), (d0, RD), (LT, MID, shade(MID, .8)), ft=at.add(sp), st=at.add(sp[:, :1].repeat(RD - d0, 1))))
    if gate:
        g = art[CH_FOOT - apex_a - 2:CH_FOOT - 14, 50:110].copy()
        dark = np.isin((g[..., 0].astype(int) << 16) | (g[..., 1].astype(int) << 8) | g[..., 2], [rgb_int(DK), rgb_int(DK2)])
        g[dark] = 0
        g = cut(g, polygon(g.shape[:2], [(0, g.shape[0]), (0, apex_a - spring + 2), (30, 0), (60, apex_a - spring + 2), (60, g.shape[0])]))
        parts.append(mbox((50, 110), (14, 14 + g.shape[0]), (4, 4), (MID, MID, MID), ft=at.add(g), card=True))
    # --- the rose window, standing proud of the wall in its own ring, glowing at night
    rose = art[CH_FOOT - 110:CH_FOOT - 88, 68:92].copy()
    rg = np.zeros_like(rose)
    rr = rose[..., :3].astype(int)
    gl = (rr[..., 2] - rr[..., 0] > 30) & (rose[..., 3] > 0)
    rg[gl] = (255, 196, 110, 235)
    rose_r = at.add(rose, rg)
    parts.append(mbox((68, 92), (88, 110), (-2, 0), (LT, MID, shade(MID, .8)), ft=rose_r, st=at.add(coping_pic(2, 22))))
    # --- the stepped copings on the front and the back gables, the crosses on their apexes
    parts += stepped_coping(at, 20, 80, E - 8, R, (-5, 5), 5, rng)
    parts += stepped_coping(at, 20, 80, E - 8, R, (D - 5, D + 5), 5, rng)
    cross_f = art[84:114, 62:98].copy(); cf = cross_f[..., :3].astype(int); cross_f[(cf[..., 0] > cf[..., 2] + 5)] = 0
    parts.append(mbox((62, 98), (R + 4, R + 4 + cross_f.shape[0]), (0, 0), (LT, LT, LT), ft=at.add(cross_f), card=True))
    # --- the back: its own elevation over the nave's back wall, the chancel (a lower, narrower box under its own gable)
    bw_img, bw_glow = back_wall(rng)
    parts.append(mbox((X0, X1), (0, bw_img.shape[0]), (D + .4, D + .4), (WALL, WALL, WALL), ft=at.add(np.ascontiguousarray(bw_img[:, ::-1]), np.ascontiguousarray(bw_glow[:, ::-1])), card=True))
    CW0, CW1, CD, CE = 54, 106, 48, 62
    cb, cbg = bay(rng, 48, CE, 12, 34, 16)
    cb_r = at.add(cb, cbg)
    parts.append(mbox((CW0, CW1), (0, CE), (D, D + CD), (roof_c, WALL, shade(WALL, .8)), st=cb_r, top=False))
    cfield = shingle_field(art, CD + 4)[:, 20:]
    chw = cfield.shape[1]
    clq = [(0, CD + 4), (chw, CD + 4), (chw, 0), (0, 0)]; crq = [(chw, CD + 4), (0, CD + 4), (0, 0), (chw, 0)]
    parts.append(mb.mgable((CW0 - 4, CW1 + 4), (CE - 4, CE + 26), (D, D + CD), at.add(cfield), [v for p in clq for v in p],
                           at.add(np.ascontiguousarray(cfield[:, ::-1])), [v for p in crq for v in p], (MID, MID), roof_c, lip=MID))
    # its back: a smaller gable of its own, coursed up to the apex, its lancet under it
    CH = CE + 26; cw = CW1 - CW0
    cbk = ashlar(CH, cw, rng); cbkg = np.zeros_like(cbk)
    cbk[~polygon((CH, cw), [(0, CH), (0, CH - CE), (cw / 2, 0), (cw, CH - CE), (cw, CH)])] = 0
    win, wg = lancet(12, 34); m = win[..., 3] > 0; y0 = CH - 16 - 34
    cbk[y0:y0 + 34, cw // 2 - 6:cw // 2 + 6][m] = win[m]; cbkg[y0:y0 + 34, cw // 2 - 6:cw // 2 + 6] = wg
    parts.append(mbox((CW0, CW1), (0, CH), (D + CD + .4, D + CD + .4), (WALL, WALL, WALL), ft=at.add(cbk, cbkg), card=True))
    parts.append(mbox((76, 84), (CE + 24, CE + 29), (D, D + CD + 1), (LT, MID, shade(MID, .8)), tt=at.add(np.ascontiguousarray(civA.tile(art[50:100, 75:85], CD + 3, 8)))))
    # --- the buttresses: one at each corner (pinnacles), and between the bays along both sides
    for mirror in (False, True):
        parts += buttress(at, art, rng, mirror=mirror)
        parts += buttress(at, art, rng, mirror=mirror, back=True)
        for j in (1, 2, 3):
            parts += side_buttress(at, rng, RD + (D - RD) * j / 4, mirror=mirror)
    # --- the belfry on the ridge near the front: stone, a louvred opening each side, a cornice, a shingle spire, the cross
    bx0, bx1, bd0, bd1 = 62, 98, 26, 62
    bel = ashlar(56, 36, rng, bw=9, bh=7, stains=False)
    lv = louvre(12, 30); m = lv[..., 3] > 0; bel[12:42, 12:24][m] = lv[m]
    bel_r = at.add(bel)
    parts.append(mbox((bx0, bx1), (R - 24, R + 32), (bd0, bd1), (LT, WALL, shade(WALL, .8)), ft=bel_r, st=bel_r))
    corn = at.add(coping_pic(42, 5))
    parts.append(mbox((bx0 - 3, bx1 + 3), (R + 32, R + 37), (bd0 - 3, bd1 + 3), (LT2, MID, shade(MID, .8)), ft=corn, st=corn))
    sfield = shingle_field(art, 46)
    spire_front = np.ascontiguousarray(np.transpose(sfield[:40, :46], (1, 0, 2))[::-1])
    parts.append(pyramid(at, (bx0 - 2, bx1 + 2), (R + 37, R + 100), (bd0 - 2, bd1 + 2), spire_front, np.ascontiguousarray(sfield[:40, :46]), roof_c, top=3.))
    cross = art[4:30, 63:97].copy(); cc = cross[..., :3].astype(int); cross[(cc[..., 0] > cc[..., 2] + 5)] = 0
    parts.append(mbox((63, 97), (R + 98, R + 98 + cross.shape[0]), ((bd0 + bd1) / 2, (bd0 + bd1) / 2), (LT, LT, LT), ft=at.add(cross), card=True))
    # the order the painters draw them in: the nave's roof after its walls, the ridge's cap on it
    parts = parts[:1] + [gab, ridge_cap] + parts[1:]
    door = (56, 104)
    setback, style, lawn, fence, fence_snow = lot_for(shop, rng, 160, D + CD, door)
    model = apt.model_of('school', 160, setback, style, parts, roof_c, 'shop', 'paving', fence, lamp=[80, 64, -3])
    return model, at, face, bay_img, np.zeros_like(face), lawn, fence, fence_snow


HB_LIGHT, HB_MID, HB_DARK, HB_SHADE = (123, 110, 113), (113, 100, 103), (101, 87, 87), (107, 80, 82)


def brick(h, w, rng, bw=12, bh=6):
    """The haunted mansion's brick in its own colours (the turret's lit face): stretchers bw x bh, every other course
    half a brick over, dark mortar under and right of each, courses from the bottom up."""
    img = np.zeros((h, w, 4), np.uint8); img[..., 3] = 255; img[..., :3] = HB_MID
    for k, r1 in enumerate(range(h, 0, -bh)):
        r0 = max(0, r1 - bh); off = (k % 2) * (bw // 2)
        for c in range(-off, w, bw):
            x0, x1 = max(0, c), min(w, c + bw)
            if x1 <= x0: continue
            img[r0:r1, x0:x1, :3] = (HB_LIGHT, HB_MID, HB_MID, HB_DARK, HB_SHADE)[int(rng.integers(0, 5))]
            img[r1 - 1, x0:x1, :3] = DK2
            if 0 <= c + bw - 1 < w: img[r0:r1, c + bw - 1, :3] = DK2
    return img


def brick_side(rng, h, w, wins):
    """A side wall of the mansion: its brick with the front's own windows (each its picture, the fraction of the width
    its middle stands at, its height of its foot), their glass glowing at night. Returns it and its glow."""
    img = brick(h, w, rng); glow = np.zeros_like(img)
    for (pic, f, y) in wins:
        ph, pw = pic.shape[:2]; m = pic[..., 3] > 200
        x0 = int(w * f) - pw // 2; y0 = h - y - ph
        if y0 < 0 or x0 < 0 or x0 + pw > w: continue
        img[y0:y0 + ph, x0:x0 + pw][m] = pic[m]
        rgb = pic[..., :3].astype(int)
        g = m & (rgb[..., 2] > rgb[..., 0] + 25)
        glow[y0:y0 + ph, x0:x0 + pw][g] = (255, 196, 110, 235)
    return img, glow


def haunted(spec, shop=None):
    """The haunted mansion (rare landmark): the ground storey with its two bay-window gables and the great Gothic portal, the central
    block over it under a steep plank gable roof (the art's slopes from above on quads, as V1's gabled wing), the octagonal right turret set
    back 90 under its conical shingle roof (a hipped roof to a point), a flat terrace either side of the block behind iron railings, and the
    left lantern tower standing on the terrace (128 back: its foot row 322) under its own steep front gable (the round window in its face,
    its slopes the art's from above). Side walls are the mansion's brick with the front's arched windows."""
    art = art_of('Haunted_House'); rng = np.random.default_rng(sum(map(ord, spec['id']))); at = Atlas(1024)
    foot = foot_of(art, 300)
    G0 = 450; EG = foot - G0
    X0, X1 = 70, 500; D = 260
    elev = art[G0:foot, X0:X1].copy(); pan, glow = civA.lit_panes(elev)
    wall_c = HB_MID; roof_c = med(art, (100, 200, 200, 260))
    # the front's windows, for the sides: the bays' arched light, the block's tall pointed one, the turret's
    win_low = np.ascontiguousarray(art[593:670, 127:163]); win_up = np.ascontiguousarray(art[320:407, 227:263])
    win_t = np.ascontiguousarray(art[338:412, 478:536])
    side, sglow = brick_side(rng, EG, D, [(win_low, f, 24) for f in (.2, .5, .8)] + [(win_low, f, 140) for f in (.2, .5, .8)])
    side_r = at.add(side, sglow)
    terrace = at.add(np.ascontiguousarray(art[345:412, 110:190]))
    ground = mbox((X0, X1), (0, EG), (0, D), (med(art, (360, 400, 100, 180)), wall_c, shade(wall_c, .8)), ft=at.add(elev, glow), fx=X0, tt=terrace, st=side_r, panes=pan)
    # the central block and its plank gable
    bx0, bx1 = 190, 392; Dc = 210
    ap, ev = 228, 300
    blk = art[ap:G0, bx0:bx1].copy(); h = blk.shape[0]; t = ev - ap; w = bx1 - bx0
    blk = cut(blk, polygon((h, w), [(0, h), (0, t), (w // 2, 0), (w, t), (w, h)]))
    bpan, bglow = civA.lit_panes(blk)
    EC = G0 - ev
    bside, bsg = brick_side(rng, EC, Dc, [(win_up, f, 40) for f in (.25, .75)])
    block = mbox((bx0, bx1), (EG, EG + EC), (0, Dc), (roof_c, wall_c, shade(wall_c, .8)), ft=at.add(blk, bglow), fx=bx0, st=at.add(bside, bsg), panes=bpan, top=False)
    gx0, gx1 = 178, 400; mid = 289; rise = ev - ap
    lp_img = art[20:ev, gx0:mid]; rp_img = art[20:ev, mid:gx1]
    lq = [(0, ev - 20), (mid - gx0 - 1, ap - 20), (mid - gx0 - 1, 0), (0, 70)]; rq = [(gx1 - mid - 1, ev - 20), (0, ap - 20), (0, 0), (gx1 - mid - 1, 70)]
    left = at.add(cut(lp_img, polygon(lp_img.shape[:2], lq))); right = at.add(cut(rp_img, polygon(rp_img.shape[:2], rq)))
    plank = med(art, (100, 200, 200, 230))
    gab = mb.mgable((gx0, gx1), (EG + EC, EG + EC + rise), (0, Dc), left, [v for p in lq for v in p], right, [v for p in rq for v in p], (plank, plank), plank, lip=med(art, (ap + 4, ap + 6, 270, 280)))
    # the turret: set back 90, a box wearing the art's wall, a conical roof
    tx0, tx1, t0 = 435, 580, 330; tfoot = 610; ET = tfoot - t0
    tw = art[t0:tfoot, tx0:tx1].copy(); tpan, tglow = civA.lit_panes(tw)
    tside, tsg = brick_side(rng, ET, 145, [(win_t, .5, ET - 74 - 8)])
    turret = mbox((tx0, tx1), (0, ET), (90, 235), (roof_c, wall_c, shade(wall_c, .8)), ft=at.add(tw, tglow), fx=tx0, st=at.add(tside, tsg), panes=tpan, top=False)
    # the conical roof to a ring 29 across, its sides tiled from the face's columns that are whole in every row (the left
    # ones, outside the face's trapezoid, came out black), the art's metal cap standing on it as a pyramid to its spike
    cone, crise = hip_roof(at, art, (tx0 - 6, tx1 + 6), ET, (196, 322), (tx0 - 6, tx1 + 6), 64, 145 + 12, (180, 190, 500, 510), (66, 90), med(art, (230, 290, 500, 520)), med(art, (230, 232, 500, 502)), d0=90 - 6)
    capf = np.ascontiguousarray(art[136:198, 488:532]); caps = np.ascontiguousarray(art[160:198, 490:530]).copy(); caps[..., 3] = 255
    capc = med(art, (180, 196, 500, 520))
    cap = pyramid(at, (tx0 - 6 + 64, tx1 + 6 - 64), (ET + crise, ET + crise + 58), (90 - 6 + 64, 90 - 6 + 157 - 64), capf, caps, capc, top=2.)
    spike = mbox((506, 510), (ET + crise + 57, ET + crise + 72), (160.5, 162.5), (LT2, LT, MID))
    parts = [ground, block, gab, turret, cone, cap, spike]
    # --- the left lantern tower on the terrace: its ledge (rows 300..322), its storey (228..300), its front gable
    L0 = 128; LD = 94
    ledge = np.ascontiguousarray(art[300:322, 74:176]); lst = np.ascontiguousarray(art[228:300, 78:172])
    lpan, lglow = civA.lit_panes(lst)
    lside, lsg = brick_side(rng, 72, LD, [])
    parts.append(mbox((74, 176), (EG, EG + 22), (L0 - 4, L0 + LD + 4), (med(art, (302, 306, 100, 150)), HB_DARK, shade(HB_DARK, .8)), ft=at.add(ledge), fx=74,
                      st=at.add(np.ascontiguousarray(civA.tile(ledge[:, 40:60], 22, LD + 8)))))
    parts.append(mbox((78, 172), (EG + 22, EG + 94), (L0, L0 + LD), (roof_c, wall_c, shade(wall_c, .8)), ft=at.add(lst, lglow), fx=78, st=at.add(lside, lsg), panes=lpan, top=False))
    # its gable: the face is the art's triangle (the round window), the slopes the art's from above (front eave row 222, apex 112,
    # the ridge's back end 28, the back eave 140)
    la, le = 112, 222
    lface = art[la:le + 6, 62:176].copy(); fh_, fw_ = lface.shape[:2]
    lface = cut(lface, polygon((fh_, fw_), [(0, fh_), (56, 0), (fw_, fh_)]))
    lfp, lfg = civA.lit_panes(lface)
    # the round window lights too (it is no square pane)
    rgbf = lface[..., :3].astype(int); rw = (rgbf[..., 2] > rgbf[..., 0] + 25) & (lface[..., 3] > 0)
    yy, xx = np.mgrid[:fh_, :fw_]; rw &= (xx - 57) ** 2 + (yy - 67) ** 2 <= 22 ** 2      # the window only, not the trims
    lfg[rw] = (255, 196, 110, 235)
    lpl = art[20:le + 6, 62:118]; lpr = art[20:le + 6, 118:176]
    llq = [(0, le - 20), (55, la - 20), (55, 8), (0, 120)]; lrq = [(57, le - 20), (0, la - 20), (0, 8), (57, 120)]
    lrise = le - la
    parts.append(mb.mgable((62, 176), (EG + 94, EG + 94 + lrise), (L0 - 6, L0 + LD + 2), at.add(cut(lpl, polygon(lpl.shape[:2], llq))), [v for p in llq for v in p],
                           at.add(cut(lpr, polygon(lpr.shape[:2], lrq))), [v for p in lrq for v in p], (plank, plank), plank, face=at.add(lface, lfg), fx=62, fy=EG + 88,
                           lip=med(art, (130, 134, 80, 90))))
    # --- the terrace's iron railings along its front and down its sides (the art draws them at rows 340 and 410..430)
    iron = (58, 58, 80)
    for (a0, a1) in ((X0, bx0), (bx1, X1)):
        parts.append(mrail((a0, EG, 3), (a1, EG, 3), 14, iron, posts=10))
    parts.append(mrail((X0 + 2, EG, 3), (X0 + 2, EG, D - 3), 14, iron, posts=10))
    parts.append(mrail((X1 - 2, EG, 3), (X1 - 2, EG, 88), 14, iron, posts=10))
    setback, style, lawn, fence, fence_snow = lot_for(shop, rng, 590, D, (260, 330))
    model = apt.model_of('school', 590, setback, style, parts, roof_c, 'shop', 'paving', fence, lamp=None)
    return model, at, elev, side, glow, lawn, fence, fence_snow


def windmill(spec, shop=None):
    """The wind-mill (a landmark for the edge of town): a tapered timber tower (the art's lattice, rows 160 to its foot, as a card
    front and back; its four legs leaning in from 78 square at the foot to 62 at the top, and the sides' girts and cross braces, as
    timbers along their lines), a plank platform on top, the wheel (the art's, rows 28..160) a card standing before a gearbox on it,
    the tail vane running back from the gearbox. The old build stood two full-height posts outside the lattice at its sides."""
    art = art_of('Post_Apocalyptic_House_Wind_Mill'); rng = np.random.default_rng(sum(map(ord, spec['id']))); at = Atlas(512)
    foot = foot_of(art, 64)
    H = foot - 160; B0, B1, T0, T1 = 26, 104, 34, 96; DB = 78
    lattice = np.ascontiguousarray(art[160:foot, 24:106]); wheel = np.ascontiguousarray(art[28:160, :])
    wood = med(art, (180, 240, 50, 60)); dark = shade(wood, .6)
    lat_r = at.add(lattice)
    def xl(y): return B0 + (T0 - B0) * y / H
    def xr(y): return B1 + (T1 - B1) * y / H
    def df(y): return (T0 - B0) * y / H
    def db(y): return DB - (T0 - B0) * y / H
    parts = [mbox((24, 106), (0, H), (2, 2), (wood, wood, wood), ft=lat_r, card=True),
             mbox((24, 106), (0, H), (DB - 2, DB - 2), (wood, wood, wood), ft=lat_r, card=True)]
    # the legs, leaning in
    for (x0, x1) in ((B0 + 2, T0 + 2), (B1 - 2, T1 - 2)):
        for (d0, d1) in ((2, df(H) + 2), (DB - 2, db(H) - 2)):
            parts.append(mrail((x0, 0, d0), (x1, H, d1), 4, dark, w=4))
    # the sides: girts at three heights and a cross brace between each pair, timbers along their lines
    levels = (2, H * .36, H * .7, H - 4)
    for side in (0, 1):
        X = xl if side == 0 else xr
        o = 1 if side == 0 else -1
        for y in levels:
            parts.append(mrail((X(y) + o, y, df(y) + 2), (X(y) + o, y, db(y) - 2), 3, wood, w=3))
        for y0, y1 in zip(levels[:-1], levels[1:]):
            parts.append(mrail((X(y0) + o, y0, df(y0) + 3), (X(y1) + o, y1, db(y1) - 3), 3, wood, w=3))
            parts.append(mrail((X(y0) + o, y0, db(y0) - 3), (X(y1) + o, y1, df(y1) + 3), 3, wood, w=3))
    # the platform on top, its planks the art's lattice timbers laid flat
    planks = np.ascontiguousarray(civA.tile(art[236:244, 40:90], DB - 2 * (T0 - B0) + 6, T1 - T0 + 6)); planks[..., 3] = 255
    parts.append(mbox((T0 - 3, T1 + 3), (H, H + 5), (df(H) - 3, db(H) + 3), (wood, wood, dark), tt=at.add(planks)))
    # the gearbox behind the hub, the tail vane running back from it, the wheel before it
    hub = H - 4 + (160 - 114)
    steel = med(art, (100, 120, 60, 68)); steel_d = shade(steel, .7)
    parts.append(mbox((58, 70), (hub - 6, hub + 6), (-1, 22), (steel, steel_d, steel_d)))
    parts.append(mbox((63, 65), (hub - 2, hub + 2), (22, 60), (steel, steel_d, steel_d)))
    vane = np.zeros((44, 40, 4), np.uint8); vane[..., :3] = steel; vane[..., 3] = 255
    vane[::6, :, :3] = steel_d; vane[:, :2, :3] = steel_d
    parts.append(mbox((63.5, 64.5), (hub - 18, hub + 26), (56, 96), (steel, steel_d, steel_d), st=at.add(vane)))
    parts.append(mbox((0, 128), (H - 4, H - 4 + wheel.shape[0]), (-2, -2), (wood, wood, wood), ft=at.add(wheel), card=True))
    setback, style, lawn, fence, fence_snow = lot_for(shop, rng, 128, 96, (50, 78))
    model = apt.model_of('tower', 128, setback, style, parts, wood, 'lamp', 'paving', fence, lamp=None)
    side = np.zeros((H, DB, 4), np.uint8)
    return model, at, lattice, side, np.zeros_like(lattice), lawn, fence, fence_snow


KITS = ('v4', 'v5', 'v6')


def compose_civic(spec, shop=None):
    k = spec['kit']
    if k in ('v1', 'v2', 'v3'): return compose_gabled(spec, shop)
    if k == 'v7': return compose_tower7(spec, shop)
    if k in ('chapel', 'chapel_gate'): return chapel(spec, shop)
    if k == 'haunted': return haunted(spec, shop)
    if k == 'windmill': return windmill(spec, shop)
    return compose_vic(spec, shop)
