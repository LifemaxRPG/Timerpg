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
from make_buildings import Atlas, mbox, mhip, shade, solid, rgb_int, column_rows, cut, polygon, hip_side, along_mean, rise_for, fill_period, yard

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
        e0 = foot_of(art, 40)
        pf = at.add(np.ascontiguousarray(art[606:716, 36:100])); pt = at.add(np.ascontiguousarray(art[446:540, 22:96]))
        pc = med(art, (700, 712, 40, 90))
        parts.insert(0, mbox((22, 96), (0, 104), (30, 150), (med(art, (470, 500, 40, 90)), pc, shade(pc, .8)), ft=pf, tt=pt))
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


def slate_piece(h, w):
    """A slate roof texture of h rows by w columns: the Victorian roofs' tiles (period 12), tiled."""
    v5 = art_of('Victorian_House_5')
    return np.ascontiguousarray(civA.tile(v5[130:190, 200:260], h, w))


def chapel(spec, shop=None):
    """Grace_Chapel (with or without its gate): the art's facade (stone portal under its small gable, the plank gable of the nave over it
    inside the stepped rake, pinnacles) as the front of a nave under a steep gable roof of slate, 300 deep, a belfry and spire on the ridge
    (stone box under a pyramid of slate, the art's cross on its top)."""
    gate = spec['kit'] == 'chapel_gate'
    art = grave_art('Grace_Chapel_With_Gate' if gate else 'Grace_Chapel')
    rng = np.random.default_rng(sum(map(ord, spec['id']))); at = Atlas(1024)
    foot = foot_of(art, 80); W = 160; D = 300
    ap, ev = 38, 100
    E = foot - ev; R = foot - ap
    face = art[ap:foot, 0:W].copy()
    pan, glow = civA.lit_panes(face)
    face_r = at.add(face, glow)
    half = W // 2; Hp = D + (R - E) + 2
    rise = R - E
    sl = slate_piece(Hp, half); sr = sl.copy()
    q_fe = Hp - 1; q_ap = q_fe - rise; q_re = q_ap - D; q_be = q_fe - D
    lq = [(0, q_fe), (half - 1, q_ap), (half - 1, q_re), (0, q_be)]; rq = [(half - 1, q_fe), (0, q_ap), (0, q_re), (half - 1, q_be)]
    left = at.add(cut(sl, polygon(sl.shape[:2], lq))); right = at.add(cut(sr, polygon(sr.shape[:2], rq)))
    stone = med(art, (200, 240, 100, 118)); roof_c = med(art, (130, 150, 40, 60))
    rows = column_rows(art[ev:foot], [8, 9, 10, 11, 12, 13])
    ok = (art[ev:foot, 8:14, 3] == 255).all(1)
    for y in range(len(rows)):
        if not ok[y]:
            ys = np.nonzero(ok[:y])[0]
            if len(ys): rows[y] = rows[ys[-1]]
    side = civA.tile(art[196:250, 3:27], E, D); side[..., 3] = 255
    side[:, :2, :3] = side[:, 2:3, :3]; side[:, -2:, :3] = side[:, -3:-2, :3]
    # long windows between the buttresses: the facade's own portal glass, a narrow gothic light every 60
    sg = np.zeros_like(side)
    side_r = at.add(np.ascontiguousarray(side), sg)
    wall = mbox((0, W), (0, E), (0, D), (roof_c, stone, shade(stone, .8)), ft=face_r, fx=0, st=side_r, panes=pan, top=False)
    gab = mb.mgable((0, W), (E, R), (0, D), left, [v for p in lq for v in p], right, [v for p in rq for v in p], (stone, stone), roof_c, lip=stone)
    # the belfry and its spire on the ridge, 90 back
    bx0, bx1, bd0, bd1 = 56, 104, 70, 118
    tower_h = 70
    bw = at.add(np.ascontiguousarray(civA.tile(art[196:250, 2:26], tower_h, bx1 - bx0)))
    belfry = mbox((bx0, bx1), (R - 14, R + tower_h), (bd0, bd1), (stone, stone, shade(stone, .85)), ft=bw, st=bw)
    v5 = art_of('Victorian_House_5')
    spire, srise = hip_roof(at, v5, (bx0 - 3, bx1 + 3), R + tower_h, (118, 246), (200, 254), 27, 54, (130, 140, 200, 210), (0, 36), med(v5, (150, 200, 220, 240)), med(v5, (150, 152, 220, 222)), d0=bd0 - 3, y0=0)
    cross = at.add(np.ascontiguousarray(art[6:38, 62:98]))
    fin = mbox((62, 98), (R + tower_h + srise, R + tower_h + srise + 32), (bd0 + 24, bd0 + 24), (stone, stone, stone), ft=cross, card=True)
    parts = [wall, gab, belfry, spire, fin]
    door = (56, 104)
    setback, style, lawn, fence, fence_snow = lot_for(shop, rng, W, D, door)
    model = apt.model_of('school', W, setback, style, parts, roof_c, 'shop', 'paving', fence, lamp=[80, 70, -3])
    return model, at, face, side, glow, lawn, fence, fence_snow


def haunted(spec, shop=None):
    """The haunted mansion (rare landmark): the ground storey with its two bay-window gables and the great Gothic portal, the central
    block over it under a steep plank gable roof (the art's slopes from above on quads, as V1's gabled wing), the octagonal right turret set
    back 90 under its conical shingle roof (a hipped roof to a point), a flat terrace either side of the block. The art's left turret
    (a lantern tower over the terrace) is not built."""
    art = art_of('Haunted_House'); rng = np.random.default_rng(sum(map(ord, spec['id']))); at = Atlas(1024)
    foot = foot_of(art, 300)
    G0 = 450; EG = foot - G0
    X0, X1 = 70, 500; D = 260
    elev = art[G0:foot, X0:X1].copy(); pan, glow = civA.lit_panes(elev)
    wall_c = med(art, (G0 + 60, G0 + 70, 100, 110)); roof_c = med(art, (100, 200, 200, 260))
    side = civA.tile(art[336:416, 362:386], EG, D); side[..., 3] = 255; sglow = np.zeros_like(side)
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
    block = mbox((bx0, bx1), (EG, EG + EC), (0, Dc), (roof_c, wall_c, shade(wall_c, .8)), ft=at.add(blk, bglow), fx=bx0, st=side_r, panes=bpan, top=False)
    gx0, gx1 = 178, 400; mid = 289; rise = ev - ap
    lp_img = art[20:ev, gx0:mid]; rp_img = art[20:ev, mid:gx1]
    lq = [(0, ev - 20), (mid - gx0 - 1, ap - 20), (mid - gx0 - 1, 0), (0, 70)]; rq = [(gx1 - mid - 1, ev - 20), (0, ap - 20), (0, 0), (gx1 - mid - 1, 70)]
    left = at.add(cut(lp_img, polygon(lp_img.shape[:2], lq))); right = at.add(cut(rp_img, polygon(rp_img.shape[:2], rq)))
    plank = med(art, (100, 200, 200, 230))
    gab = mb.mgable((gx0, gx1), (EG + EC, EG + EC + rise), (0, Dc), left, [v for p in lq for v in p], right, [v for p in rq for v in p], (plank, plank), plank, lip=med(art, (ap + 4, ap + 6, 270, 280)))
    # the turret: set back 90, a box wearing the art's wall, a conical roof
    tx0, tx1, t0 = 435, 580, 330; tfoot = 610; ET = tfoot - t0
    tw = art[t0:tfoot, tx0:tx1].copy(); tpan, tglow = civA.lit_panes(tw)
    tside = civA.tile(art[470:560, 445:470], ET, 145); tside[..., 3] = 255; tsg = np.zeros_like(tside)
    turret = mbox((tx0, tx1), (0, ET), (90, 235), (roof_c, med(art, (t0 + 100, t0 + 110, 500, 520)), shade(med(art, (t0 + 100, t0 + 110, 500, 520)), .8)), ft=at.add(tw, tglow), fx=tx0, st=at.add(tside, tsg), panes=tpan, top=False)
    cone, crise = hip_roof(at, art, (tx0 - 6, tx1 + 6), ET, (196, 322), (tx0 - 6, tx1 + 6), 78, 145 + 12, (180, 190, 500, 510), (0, 60), med(art, (230, 290, 500, 520)), med(art, (230, 232, 500, 502)), d0=90 - 6)
    parts = [ground, block, gab, turret, cone]
    setback, style, lawn, fence, fence_snow = lot_for(shop, rng, 590, D, (260, 330))
    model = apt.model_of('school', 590, setback, style, parts, roof_c, 'shop', 'paving', fence, lamp=None)
    return model, at, elev, side, glow, lawn, fence, fence_snow


def windmill(spec, shop=None):
    """The wind-mill (a landmark for the edge of town): a timber lattice tower on four legs (the art's lattice as a card each side and the
    legs as boxes) under its wheel, a card standing on the tower's top turned to the street (and its back)."""
    art = art_of('Post_Apocalyptic_House_Wind_Mill'); rng = np.random.default_rng(sum(map(ord, spec['id']))); at = Atlas(512)
    h, w = art.shape[:2]
    foot = foot_of(art, 64)
    lattice = np.ascontiguousarray(art[128:foot, :]); wheel = np.ascontiguousarray(art[0:132, :])
    lat_r = at.add(lattice); wheel_r = at.add(wheel)
    side = civA.tile(lattice[:, 40:88], lattice.shape[0], 40)
    wood = med(art, (180, 240, 50, 60))
    Hh = lattice.shape[0]
    # a box wearing the lattice on each face (front and back cards, two side boxes): an open frame, no top
    parts = [mbox((20, 108), (0, Hh), (0, 0), (wood, wood, wood), ft=lat_r, card=True),
             mbox((20, 108), (0, Hh), (40, 40), (wood, wood, wood), ft=lat_r, card=True),
             mbox((20, 24), (0, Hh), (0, 40), (wood, wood, wood), st=at.add(side)),
             mbox((104, 108), (0, Hh), (0, 40), (wood, wood, wood), st=at.add(side)),
             mbox((0, 128), (Hh - 6, Hh + 126), (20, 20), (wood, wood, wood), ft=wheel_r, card=True)]
    setback, style, lawn, fence, fence_snow = lot_for(shop, rng, 128, 40, (50, 78))
    model = apt.model_of('tower', 128, setback, style, parts, wood, 'lamp', 'paving', fence, lamp=None)
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
