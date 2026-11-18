"""The police station (landmark_police_1..3, three colours of one shape) as model parts, for make_buildings.py's
build_model. The art draws it the way the other sprites are drawn: what stands up as a front elevation, what faces up
seen from above, and depth shown by drawing a thing higher the further back it stands (about a row per pixel of depth
here). Its shape:

- a tall block on the left whose flat roof is a slab on pillars: round pillars up its front, a chamfered corner (a wall
  turned towards the wing), an open loggia over an entrance porch on the left (the porch's steps, its landing, its
  door wall; a round column under the loggia's floor and one at its back), and on the roof a parapet with a rail, a
  stair house under a glass pyramid and the POLICE sign on legs with the badge standing on it;
- a lower wing on the right whose far end is rounded: a quarter of an elliptic cylinder at the front corner and a
  longer one at the back (the art's roof from above is a D), with pilasters along its front, a flat roof with a
  parapet and a rail that follow the curve, four spotlights and a second stair house.

The rounded walls are a new part, 'cyl' (a segment of an elliptic cylinder wearing a picture strip whose columns run
evenly along its angle); the chamfer is a 'wall' (an upright face between two plan points). The chamfer is the art's
columns let down by the art's own steps, so its bands come out level; the rounded wall wears the front's own courses
and the art's louvred windows, unrolled from where the art draws them."""
import math
import numpy as np

G = 559                                                            # the art's ground row under the tall block's front


def _mb():
    import make_buildings as mb
    return mb


def mcyl(c, r, a, y, colors, ft=None, ri=None, card=False):
    """A segment of an upright elliptic cylinder: centre c (x across the front picture, d back), semi-axes r (across, in
    depth), from angle a[0] to a[1] in degrees (0 faces the street, 90 the right, 180 the back, -90 the left), from
    height y[0] to y[1]. `ft` the atlas strip it wears: its columns evenly along the angle from a[0], its rows from the
    top down. colors: its top and its wall (0xRRGGBB; a top of -1 is not drawn). `ri` the inner semi-axes of a ring (a
    parapet: its top is the band between); `card` a thin open surface (a rail), drawn from both sides, no top."""
    mb = _mb()
    p = dict(t='cyl', c=mb.fl(c), r=mb.fl(r), a=mb.fl(a), y=mb.fl(y), col=[mb.rgb_int(colors[0]) if colors[0] is not None else -1, mb.rgb_int(colors[1])])
    if ft is not None: p['ft'] = ft
    if ri is not None: p['ri'] = mb.fl(ri)
    if card: p['card'] = True
    return p


def mwall(x, d, y, color, ft=None):
    """An upright face from plan point (x[0], d[0]) to (x[1], d[1]) (its outside is on the street's side of that line,
    left to right), from height y[0] to y[1], wearing atlas piece `ft` (its columns along the face, rows from the top)."""
    mb = _mb()
    p = dict(t='wall', x=mb.fl(x), d=mb.fl(d), y=mb.fl(y), c=[mb.rgb_int(color)])
    if ft is not None: p['ft'] = ft
    return p


def arc_len(a, b, t0, t1, n=400):
    ts = np.radians(np.linspace(t0, t1, n + 1))
    xs, ds = a * np.sin(ts), -b * np.cos(ts)
    return float(np.hypot(np.diff(xs), np.diff(ds)).sum())


def unroll(art, cx, cd, a, b, t0, t1, y0, y1, foot=G, xlim=(0, 1e9), top_row=0, fill=None, width=None, rows=None):
    """The strip a cylinder segment wears, sampled from the art: column j at angle t (evenly from t0 to t1), row i at
    height y1 - i - .5; the art's pixel where that point is drawn (column cx + a sin t, row foot - d - y, d = cd - b cos
    t; or rows(x, y) where the art's own rise along the curve is measured). Rows above `top_row` (what the art hides
    under a cornice) are the first row below it; where the art has nothing (outside xlim, or clear) the column of
    `fill` (rows) stands in. Returns it and a mask of the sampled pixels."""
    h = int(round(y1 - y0)); w = width or max(2, int(round(arc_len(a, b, t0, t1))))
    out = np.zeros((h, w, 4), np.uint8); got = np.zeros((h, w), bool)
    for j in range(w):
        t = math.radians(t0 + (j + .5) / w * (t1 - t0))
        x = cx + a * math.sin(t); d = cd - b * math.cos(t)
        xi = int(math.floor(x))
        for i in range(h):
            y = y1 - i - .5
            row = int(math.floor(rows(xi, y) if rows is not None else foot - d - y))
            row = max(row, top_row)
            if xlim[0] <= xi < xlim[1] and 0 <= row < art.shape[0] and 0 <= xi < art.shape[1] and art[row, xi, 3] == 255:
                out[i, j] = art[row, xi]; got[i, j] = True
            elif fill is not None:
                out[i, j] = fill[i]
    return out, got


def pillar_strip(art, x0, x1, row_top, row_foot, h):
    """A round pillar drawn x0..x1 in the art (its shading the light on its curve), unrolled onto its front half: each
    strip column the art's column at that angle; its rows the pillar's from its foot up (`h` of them), the rows above
    `row_top` (hidden under the cornice) its first row below it."""
    cx = (x0 + x1) / 2.0; r = (x1 - x0) / 2.0
    w = max(2, int(round(math.pi * r)))
    out = np.zeros((h, w, 4), np.uint8)
    for j in range(w):
        t = -math.pi / 2 + (j + .5) / w * math.pi
        xi = min(x1 - 1, max(x0, int(math.floor(cx + r * math.sin(t)))))
        for i in range(h):
            row = max(row_top, row_foot - (h - i))
            out[i, j] = art[row, xi]
    out[..., 3] = 255
    return out


def rail_strip(art, rows, post_cols, length, period, bar_rows):
    """A rail's elevation `length` long: its bar (rows `bar_rows` of the art's clean column) and its posts (the art's
    post at `post_cols`, its rows below the bar) every `period`; clear between them."""
    r0, r1 = rows
    h = r1 - r0
    out = np.zeros((h, length, 4), np.uint8)
    pa, pb = post_cols
    post = art[r0:r1, pa:pb]
    b0, b1 = bar_rows
    bar = art[b0:b1, pa - 4:pa - 3]                                  # a clean column of the bar, beside a post
    out[b0 - r0:b1 - r0, :] = bar
    pw = pb - pa
    for x in range(period // 2 - pw // 2, length - pw + 1, period):
        m = post[..., 3] > 0
        m[:b1 - r0] = False
        sub = out[:, x:x + pw]; sub[m] = post[m]
    return out


def level_rows(img, spans, like=None, share=.4, most=3):
    """Rows the art draws as a band across `spans` (c0, c1) but left dashed once let down by the wall's steps (some of
    its edges step a row every two columns, not two every four): each such row (few colours, one of them a fair
    share) its commonest colour. Which rows are bands is judged on `like` (the same piece in the red colour: in the
    greys the wall and the window frames share colours, and their rows passed for bands)."""
    out = img.copy(); ref = img if like is None else like
    for (c0, c1) in spans:
        for i in range(img.shape[0]):
            _, n = np.unique(ref[i, c0:c1].reshape(-1, 4), axis=0, return_counts=True)
            if len(n) <= most and n.max() >= share * (c1 - c0):
                cols, n = np.unique(img[i, c0:c1].reshape(-1, 4), axis=0, return_counts=True)
                out[i, c0:c1] = cols[n.argmax()]
    return out


def floor_of(img):
    """A flat roof's floor from above with what stands on it in the art (parapets, a sign, lamps, a stair house: the
    engine builds them) painted out: each such pixel the nearest floor pixel up or down its column (the floor's
    shading runs across the roof, so a column keeps it). The floor is the art's warm brown (red a little over blue,
    green between; not the badge's gold)."""
    a = img[..., :3].astype(int)
    fl = (a[..., 0] > a[..., 2] + 12) & (a[..., 0] < a[..., 2] + 70) & (a[..., 1] > a[..., 2]) & (a[..., 1] < a[..., 0]) & (img[..., 3] > 200)
    out = img.copy()
    h = img.shape[0]
    for x in range(img.shape[1]):
        ys = np.nonzero(fl[:, x])[0]
        if not len(ys): continue
        for y in np.nonzero(~fl[:, x])[0]:
            out[y, x] = img[ys[np.abs(ys - y).argmin()], x]
    out[..., 3] = 255
    return out


def curve_rows(variant1, x0, x1, wall_h):
    """Where the art draws the rounded wing's wall, column by column (x0..x1): the first and last rows of its red brick
    in the red variant (every colour shares the shape), the wall's top two rows above the first and its foot (the blue
    base under the brick) 22 below the last, as on the straight front. The art lifts the curve as it goes round by its
    own eye, not by one rule of depth; sampling between these lines takes each column as drawn. Returns rows(x, y) for
    a height y on the wall (above its top: the parapet's band over it, a row per row)."""
    a = variant1.astype(int)
    red = (a[..., 0] > a[..., 1] + 40) & (a[..., 0] > a[..., 2] + 30) & (a[..., 3] == 255)
    top, foot = {}, {}
    for x in range(x0, x1):
        r = np.nonzero(red[330:, x])[0]
        if len(r): top[x], foot[x] = 330 + r.min() - 2, 330 + r.max() + 22
    def rows(x, y):
        if x not in top: return -1
        if y >= wall_h: return top[x] - (y - wall_h)
        return foot[x] - (foot[x] - top[x]) * y / wall_h
    return rows


def police(shop, rng):
    mb = _mb()
    key = shop['parts'][0]['key']
    art = mb.solid(np.array(mb.load(key)))
    # the red colour: where glass is found (the blue-grey colour's walls pass for glass) and the shape measured
    art1 = mb.solid(np.array(mb.load('landmark_police_1')))
    at = mb.Atlas(1024)
    W = 672
    px = lambda y, x: art[y, x]
    shade = mb.shade
    brick = px(330, 200); wall_w = px(470, 450)                     # the tall block's and the wing's wall colours
    band = px(286, 100); cope = px(266, 100); ink = px(298, 100)
    floor_c = px(200, 450)
    # ---------------------------------------------------------------- the tall block
    # its walls stand to 261 (the art's row 298 over its front), the slab and the parapet over them
    E = 261
    SB = E + 36                                                     # the sign board's foot
    # the tower's front between its pillars (x 160..247): its own art, the pillars (round, built new) painted out with the
    # wall beside them, row by row
    t1 = art[G - E:G, 160:247].copy()
    t1[:, 0:23] = t1[:, 23:24]; t1[:, 68:87] = t1[:, 67:68]
    t1_panes, t1_glow = mb.panes_of(t1)
    t1_r = at.add(t1, t1_glow)
    # the side walls: the tower's courses (a clean column of its brick), a window of its front's
    rows = mb.column_rows(art[G - E:G], [183, 184])
    win = art[310:362, 184:228]
    corner = shade(brick, .8) + (255,)
    tside, tside_glow = mb.side_wall(rows, 240, win, 310 - (G - E), (.12, .5, .85), rng, corner)
    for wy in (372, 434):                                           # its three windows' storeys
        for f in (.12, .5, .85):
            cxw = int(240 * f) - win.shape[1] // 2
            m = win[..., 3] > 200
            tside[wy - (G - E):wy - (G - E) + win.shape[0], cxw:cxw + win.shape[1]][m] = win[m]
            tside_glow[wy - (G - E):wy - (G - E) + win.shape[0], cxw:cxw + win.shape[1]][mb.dilate(mb.glass(win), 1) & m] = mb.GLOW
    tside_r = at.add(tside, tside_glow)
    # the chamfer: from the tower's front (247, d 0) back to the right pillar (286, d 18). The art draws it rising to
    # the right in steps of its doubled pixels (two rows every four columns, measured by its foot on the red colour:
    # every colour shares the shape); each column is let down by its own step, so its sills, its base and its windows'
    # frames come out level. A straight line of depth through the steps left them a sawtooth. The rows under the
    # cornice are its top row's
    CH = 18
    foot = lambda x: int(np.nonzero(art1[:, x, 3] == 255)[0].max())
    step = {x: foot(x) - foot(248) for x in range(248, 286)}; step[247] = 0
    def chamfer(a):
        ch = np.zeros((E, 39, 4), np.uint8)
        for c in range(39):
            for i in range(E):
                ch[i, c] = a[max(302, 557 + step[247 + c] - (E - 1 - i)), 247 + c]
        return ch
    # its sills, its base and its windows' bars level across it, then the bars inside the windows' frames
    ch1 = chamfer(art1); ch1_l = level_rows(ch1, [(1, 38)])
    ch = level_rows(level_rows(chamfer(art), [(1, 38)], ch1), [(9, 30)], ch1_l)
    ch_panes, ch_glow = mb.panes_of(level_rows(ch1_l, [(9, 30)]))
    ch_r = at.add(ch, ch_glow)
    # the round pillars: the art's own shading unrolled onto their front halves
    pl = at.add(pillar_strip(art, 160, 182, 300, 565, E))
    pm = at.add(pillar_strip(art, 228, 247, 300, 567, E))
    pr = at.add(pillar_strip(art, 286, 307, 300, 541, E))
    # --- the loggia (the upper storey open on the left, x 8..160): its back wall stands 50 back (the art shows rows
    # 300..345 of it under the slab, its floor from above in front); above what the art shows it carries on in its own
    # wall, the panel's colour
    LB = 50
    lw = np.zeros((E - 3 - 124, 152, 4), np.uint8)                  # heights 124..258 (the slab's underside)
    red = art[322:330, 70:96]
    for i in range(lw.shape[0]):
        y = 258 - i - .5
        row = int(math.floor(G - LB - y))
        if 300 <= row < 345: lw[i] = art[row, 8:160]
        else: lw[i] = np.resize(red[i % red.shape[0]], (152, 4))
    lw[..., 3] = 255
    lw_panes, lw_glow = mb.panes_of(lw)
    lw_r = at.add(lw, lw_glow)
    # its left end wall (the art's x 8..33 under the slab) and the slab between the storeys (its front, its floor from
    # above with the end wall and the column off it)
    endw = at.add(art[301:395, 8:33])
    slab_f = at.add(art[395:435, 8:160])
    lfloor = art[345:395, 8:160].copy(); m = np.zeros(lfloor.shape[:2], bool); m[:, :30] = True; m[:4, :65] = True
    lfloor_r = at.add(mb.fill_rows(lfloor, m))
    uc = at.add(pillar_strip(art, 36, 70, 307, 343, E - 3 - 164))
    # --- the porch under it: its door wall 60 back (the art shows rows 437..478 of it, the doors and the lamp), the wall
    # above in the red panel's courses; its landing 20 high from 34 back, four steps up to it; the glass partition along
    # its left (seen edge on in the art, x 60..66) and the round column left of it
    PB, PH = 60, 20
    dw = np.zeros((124, 124, 4), np.uint8)                          # x 36..160, heights 0..124
    for i in range(124):
        y = 124 - i - .5
        row = int(math.floor(G - PB - y))
        if 437 <= row < 479: dw[i] = art[row, 36:160]
        elif row < 437: dw[i] = np.resize(red[i % red.shape[0]], (124, 4))   # the wall above the doors: the loggia's panels
        else: dw[i] = art[478, 36:160]
    dw[:, :30] = dw[:, 30:31]                                      # (behind the column and the partition)
    dw[..., 3] = 255
    dw_panes, dw_glow = mb.panes_of(dw)
    dw_r = at.add(dw, dw_glow)
    part_f = art[431:555, 60:66].copy(); part_f[:7] = part_f[7]
    part_r = at.add(part_f)
    lc = at.add(pillar_strip(art, 36, 62, 446, 531, 124))
    landing = at.add(art[478:505, 66:160])
    tread, riser = px(512, 120), px(515, 120)
    # the side walls of the loggia's and the porch's storeys (the block's left side): its brick, the tower's window
    lside, lside_glow = mb.side_wall(rows[:134], 190, win, 30, (.3, .72), rng, corner)
    lside_r = at.add(lside, lside_glow)
    gside, gside_glow = mb.side_wall(rows[-124:], 180, win, 40, (.3, .72), rng, corner)
    gside_r = at.add(gside, gside_glow)
    # --- the roof: the slab's edge, the roof's floor from above (what stands on it in the art painted out with its own
    # row: the engine builds it), the parapet round it (a broad coping, its rail as a card), the stair house under its
    # glass pyramid, the sign on its legs and the badge on it
    slab_e = at.add(art[298:301, 2:318])
    roof_r = at.add(floor_of(art[58:298, 2:318]))
    pf = at.add(art[283:298, 2:318])
    pt = art[258:283, 2:318].copy(); pt_m = (pt[..., :3].astype(int).sum(-1) < 330); pt_m[:4] = True
    pt_r = at.add(mb.fill_rows(pt, pt_m))
    ps = at.add(mb.tile_x(art[283:298, 40:56], 180))
    pl_top = at.add(np.ascontiguousarray(art[78:258, 2:28])); pr_top = at.add(np.ascontiguousarray(art[78:258, 292:318]))
    pb_f = art[78:93, 2:318].copy()
    pb_r = at.add(pb_f); pb_t = at.add(art[43:78, 2:318])
    rail_t = rail_strip(art, (255, 274), (26, 32), 298, 32, (255, 262))
    rail_tr = at.add(rail_t)
    rail_ts = at.add(mb.tile_x(rail_t, 200))
    # the stair house: its front as drawn (the notices, the door), its sides its red panel's courses
    sh_f = art[87:155, 35:128]
    sh_r = at.add(sh_f, mb.panes_of(sh_f)[1])
    sh_side, _ = mb.side_wall(mb.column_rows(sh_f, [8, 9, 10, 11]), 71, None, 0, (), rng, shade(px(120, 40), .8) + (255,))
    sh_s = at.add(sh_side)
    def pyramid(r0, r1, x0, x1, run, depth):
        """A glass pyramid's parts: the art's front face from above (its triangle from the eave up to the apex, every
        other row: one screen row each), its side faces tiled from the front's, its rise."""
        face = mb.cut(art[r0:r1, x0:x1], mb.polygon((r1 - r0, x1 - x0), [(0, r1 - r0), ((x1 - x0) / 2, 0), (x1 - x0, r1 - r0)]))[::2]
        rise = mb.rise_for(face.shape[0], run)
        h = depth + 1; mid = depth / 2
        lq = [(0, h - 1), (0, 0), (run, mid - .5), (run, mid + .5)]; rq = [(run, h - 1), (run, 0), (0, mid - .5), (0, mid + .5)]
        win = ((x1 - x0) // 2 - 6, (x1 - x0) // 2 + 6)
        lp = mb.hip_side(face, int(run) + 1, h, lq, depth / max(1, h - 1), win, True)
        rp = mb.hip_side(face, int(run) + 1, h, rq, depth / max(1, h - 1), win, False)
        return at.add(face), at.add(lp), lq, at.add(rp), rq, rise, [at.add(mb.along_mean(lp)), at.add(mb.along_mean(rp))]
    sky1 = pyramid(27, 88, 35, 128, 35, 71)
    # the sign: its board (the letters glow at night: they are light tubes), its cap's front and top (the badge off it),
    # its legs; the badge a card standing on the cap
    board = art[210:260, 55:265]
    letters = (board[..., 2].astype(int) > 200) & (board[..., 1].astype(int) > 190) & (board[..., 0].astype(int) < 200)
    bglow = np.zeros_like(board); bglow[letters] = (190, 255, 250, 235)
    ys, xs = np.nonzero(letters)
    board_r = at.add(board, bglow)
    board_pane = [[int(xs.min()), int(ys.min()), int(xs.max() - xs.min() + 1), int(ys.max() - ys.min() + 1), 0]]
    cap_f = at.add(art[203:210, 48:272])
    cap_t = art[187:203, 48:272].copy(); cm = np.zeros(cap_t.shape[:2], bool); cm[:, 80:146] = True
    cap_t_r = at.add(mb.fill_rows(cap_t, cm))
    bd = art[145:219, 128:194].copy()
    bdr = bd[..., :3].astype(int)
    gold = (bdr[..., 0] > 150) & (bdr[..., 2] < 140) & (bdr[..., 0] > bdr[..., 2] + 60)
    keep = mb.dilate(gold, 1)
    for i in range(keep.shape[0]):                                  # (and what lies inside its outline on each row)
        c = np.nonzero(keep[i])[0]
        if len(c): keep[i, c.min():c.max() + 1] = True
    bd[~keep] = 0
    badge = at.add(bd)
    # ---------------------------------------------------------------- the wing
    # its front stands 30 back (its foot the art's row 529), its walls 137 high; the pilasters (boxes) painted off its
    # front with the wall either side of them, row by row
    WD, WE = 30, 137
    WF = G - WD                                                     # its front's foot row
    def wing_front(a):
        wf = a[WF - WE:WF, 307:604].copy()
        for (b0, b1) in bases:
            l, r = b0 - 307, min(b1, 604) - 307
            for i in range(WE):
                src = [x for x in (l - 2, l - 3, r + 2, r + 3) if 0 <= x < wf.shape[1]]
                wf[i, l:r] = np.median(wf[i, src], axis=0).astype(np.uint8) if src else wf[i, l - 1]
        return wf
    shafts = [(314, 338), (382, 406), (512, 536), (580, 604)]
    bases = [(312, 340), (380, 408), (510, 538), (578, 606)]
    wf = wing_front(art)
    # its lit windows: the glazing over the shutter and the cash machine's screen. (The shutter, the vents and the
    # beams' ends pass for glass by colour, and lit up at night)
    wf_panes, wf_glow = mb.panes_of(wing_front(art1))
    wf_panes = [p for p in wf_panes if (p[0], p[1]) in ((109, 12), (43, 96))]
    keep = np.zeros(wf.shape[:2], bool)
    for (x, y, w, h, _) in wf_panes: keep[y:y + h, x:x + w] = True
    wf_glow[~keep] = 0
    wf_r = at.add(wf, wf_glow)
    shaft_r = [at.add(art[WF - WE + 3:WF - 8, a:b]) for (a, b) in shafts]
    base_r = [at.add(art[WF + 1 - 12:WF + 12, a:b]) for (a, b) in bases]
    # its roof's floor from above (the parapets, the lamps and the stair house painted out with their row's floor)
    wfl_r = at.add(floor_of(art[150:WF - WE, 307:604]))
    # the rounded end: a quarter of an elliptic cylinder at the front corner (across 52, back 85), the right side straight
    # from 115 to 157 back, a longer quarter round the back (back 115): the art's D seen from above
    CX = 604; FC = WD + 85; BC = 157; A = 52
    # the rounded wall's courses: the front's (its shadow under the coping, its base) with its panel's colour
    # between, plain: the art's curve has no beam and no vents, and its shading is the light on the curve, which the
    # engine gives it
    wall_rows = mb.column_rows(art[WF - WE:WF], [560, 561, 562, 563, 564, 565])
    wall_rows[10:114] = wall_rows[20]
    # its louvred windows, two storeys of them: the art's, unrolled between the lines it draws the curve's top and foot
    # on (column by column: it lifts the curve by its own eye), their clean left part (its frame and a louvre and its
    # gap) closed by the same louvres and the frame turned round; the straight sampling ran stepped past it
    crows = curve_rows(art1, 604, 652, WE)
    def louvres(a):
        q, _ = unroll(a, CX, FC, A, 85, 0, 90, 0, WE, xlim=(604, 651), rows=crows)
        frame, bay = q[29:54, 27:33], q[29:54, 33:46]
        return np.concatenate([frame, bay, bay, bay[:, :6], frame[:, ::-1]], axis=1)
    lv = louvres(art)
    lv_glow = np.zeros_like(lv); lv_glow[mb.glass(louvres(art1))] = mb.GLOW
    def curved(width, cols):
        w = np.repeat(wall_rows[:, None, :], width, axis=1).copy(); g = np.zeros_like(w)
        for c in cols:
            for r in (29, 67):
                w[r:r + lv.shape[0], c:c + lv.shape[1]] = lv; g[r:r + lv.shape[0], c:c + lv.shape[1]] = lv_glow
        w[..., 3] = 255
        return w, g
    front_q, q_glow = curved(int(round(arc_len(A, 85, 0, 90))), [27])
    fq_r = at.add(front_q, q_glow)
    bw = int(round(arc_len(A, 115, 90, 180)))
    back_q, bq_glow = curved(bw, [bw // 2 - lv.shape[1] // 2])
    bq_r = at.add(back_q, bq_glow)
    rside, _ = curved(42, [])
    rside_r = at.add(rside)
    # its parapet: the front's band and coping (rail posts and lamps off it), round the curve the band's own rows (the
    # art's, sampled round it, came out stepped), the back's inner face and coping
    wp_f = at.add(art[WF - WE - 15:WF - WE, 307:604])
    wpt = art[347:377, 307:604].copy(); wm = wpt[..., :3].astype(int).sum(-1) < 330; wm[:5] = True
    wpt_r = at.add(mb.fill_rows(wpt, wm))
    band_rows = mb.column_rows(art[WF - WE - 15:WF - WE], [450, 451, 452])
    ring_fr = at.add(np.repeat(band_rows[:, None, :], int(round(arc_len(A, 85, 0, 90))), axis=1))
    ring_br = at.add(np.repeat(band_rows[:, None, :], bw, axis=1))
    wps = at.add(mb.tile_x(art[WF - WE - 15:WF - WE, 450:466], 120))
    wpb_f = art[175:190, 307:604].copy(); wpb_m = np.zeros(wpb_f.shape[:2], bool); wpb_m[:, 30:95] = True; wpb_m[:, 205:265] = True
    wpb_r = at.add(mb.fill_rows(wpb_f, wpb_m))
    wpb_t = art[135:175, 307:604].copy(); wpb_tm = wpb_t[..., :3].astype(int).sum(-1) < 330; wpb_tm[:, 30:95] = True; wpb_tm[:, 205:265] = True
    wpb_tr = at.add(mb.fill_rows(wpb_t, wpb_tm))
    rail_w = rail_strip(art, (344, 366), (334, 340), 297, 32, (344, 351))
    rail_wr = at.add(rail_w)
    rail_wq = at.add(mb.tile_x(rail_w, int(round(arc_len(A - 13, 85 - 13, 0, 90)))))
    rail_wb = at.add(mb.tile_x(rail_w, int(round(arc_len(A - 13, 115 - 13, 90, 180)))))
    rail_ws = at.add(mb.tile_x(rail_w, 42))
    # the spotlights: the front two as drawn (their lenses glow at night), the back two the back-right one's picture
    def lamp(x0, x1, r0, r1, keep=None, lit=True):
        pic = art[r0:r1, x0:x1].copy()
        if keep is not None: pic[~keep] = 0
        lum = pic[..., :3].astype(int).sum(-1)
        # (the floor and the coping round it are not the lamp: what is not its greys and whites)
        rr2, bb2 = pic[..., 0].astype(int), pic[..., 2].astype(int)
        pic[rr2 > bb2 + 12] = 0
        lens = (lum > 690) & (pic[..., 3] > 0) & lit
        g = np.zeros_like(pic); g[lens] = (255, 250, 230, 240)
        ys2, xs2 = np.nonzero(lens)
        pane = [[int(xs2.min()), int(ys2.min()), int(xs2.max() - xs2.min() + 1), int(ys2.max() - ys2.min() + 1), 0]] if len(xs2) else []
        return at.add(pic, g), pane, pic.shape
    # (the front ones: the rail's bar behind the head and the coping's edge under the foot are not the lamp)
    lf_keep = np.ones((52, 52), bool); lf_keep[:3] = False; lf_keep[3:5] = False; lf_keep[3:5, 18:34] = True; lf_keep[51, :10] = lf_keep[51, 42:] = False
    lamp_f = lamp(342, 394, 293, 345, lf_keep)
    # (the back ones stand in front of the back parapet's coping and rail in the art: only the lamp's own outline is
    # kept, its head and its knobs, its stalk, its foot; the coping beside it stood in the air as a bar)
    lb_keep = np.zeros((88, 46), bool)
    lb_keep[:56, 7:40] = True; lb_keep[19:33, 0:46] = True; lb_keep[56:69, 17:29] = True; lb_keep[69:, 8:37] = True
    lamp_b = lamp(517, 563, 120, 208, lb_keep, lit=False)            # (turned away: the lens is on its far side)
    # the wing's stair house and its pyramid
    sh2_f = art[222:295, 319:397]
    sh2_r = at.add(sh2_f, mb.panes_of(sh2_f)[1])
    sh2_side, _ = mb.side_wall(mb.column_rows(sh2_f, [6, 7, 8, 9]), 70, None, 0, (), rng, shade(px(260, 330), .8) + (255,))
    sh2_s = at.add(sh2_side)
    sky2 = pyramid(152, 223, 319, 397, 30, 70)
    glass_c = px(60, 60); glass_e = px(80, 100)
    # ---------------------------------------------------------------- the parts, back to front
    def pyr(x0, x1, y, d0, d1, s):
        face, lp, lq, rp, rq, rise, flush = s
        cxp, cdp = (x0 + x1) / 2, (d0 + d1) / 2
        return mb.mhip((x0, x1), (y, y + rise), (d0, d1), (cxp - .5, cxp + .5, cdp - .5, cdp + .5), face, lp, [v for p in lq for v in p], rp,
                       [v for p in rq for v in p], glass_c, hip=(glass_e, glass_e), flush=flush)
    sh_c = px(120, 40); sh2_c = px(260, 330)
    parts = [
        # the tall block behind its chamfer, above the wing's roof (first: from the right, all of the wing is before
        # its side; under the wing's roof only the strip between the pillar and the wing's front shows, drawn below)
        mb.mbox((247, 307), (WE, E), (CH, 240), (brick, brick, shade(brick, .85)), st=tside_r, top=False),
        # the wing: its main block, the straight right side, the back and front quarters of its rounded end
        # (its back and its short left end behind the tall block wear the rounded end's wall: brick on its blue base band)
        mb.mbox((307, 604), (0, WE), (WD, 272), (floor_c, wall_w, shade(wall_w, .85)), ft=wf_r, tt=wfl_r, st=rside_r, panes=wf_panes),
        mb.mbox((604, 656), (0, WE), (115, 157), (floor_c, wall_w, shade(wall_w, .85)), st=rside_r),
        mcyl((CX, BC), (A, 115), (90, 180), (0, WE), (floor_c, wall_w), ft=bq_r),
        mcyl((CX, FC), (A, 85), (0, 90), (0, WE), (floor_c, wall_w), ft=fq_r),
        # its back parapet and its rail, the lamps behind the stair house, the stair house
        mb.mbox((307, 604), (WE, WE + 15), (232, 272), (cope, band, band), ft=wpb_r, tt=wpb_tr),
        # the rail along it, from the tall block round to where the curve's rail meets it (without it that rail stopped
        # short at the curve's end, a bar in the air over the roof)
        mb.mbox((307, 604), (WE + 15, WE + 15 + rail_w.shape[0]), (252, 252), (ink, ink, ink), ft=rail_wr, card=True),
        mb.mbox((345, 391), (WE, WE + lamp_b[2][0]), (226, 226), (cope, cope, cope), ft=lamp_b[0], card=True, panes=lamp_b[1]),
        mb.mbox((517, 563), (WE, WE + lamp_b[2][0]), (226, 226), (cope, cope, cope), ft=lamp_b[0], card=True, panes=lamp_b[1]),
        mb.mbox((319, 397), (WE, WE + 73), (127, 197), (sh2_c, sh2_c, shade(sh2_c, .85)), ft=sh2_r, st=sh2_s, top=False),
        pyr(319, 397, WE + 73, 127, 197, sky2),
        # the parapet round the rounded end, its right side, its front; its rail along the front and round the curve
        mcyl((CX, BC), (A, 115), (90, 180), (WE, WE + 15), (cope, band), ft=ring_br, ri=(A - 26, 115 - 40)),
        mb.mbox((630, 656), (WE, WE + 15), (115, 157), (cope, band, band), st=wps),
        mb.mbox((307, 604), (WE, WE + 15), (WD, WD + 30), (cope, band, band), ft=wp_f, tt=wpt_r),
        mcyl((CX, FC), (A, 85), (0, 90), (WE, WE + 15), (cope, band), ft=ring_fr, ri=(A - 26, 85 - 30)),
        mcyl((CX, BC), (A - 13, 115 - 20), (90, 180), (WE + 15, WE + 15 + rail_w.shape[0]), (None, ink), ft=rail_wb, card=True),
        mcyl((CX, FC), (A - 13, 85 - 13), (0, 90), (WE + 15, WE + 15 + rail_w.shape[0]), (None, ink), ft=rail_wq, card=True),
        mb.mbox((307, 604), (WE + 15, WE + 15 + rail_w.shape[0]), (WD + 13, WD + 13), (ink, ink, ink), ft=rail_wr, card=True),
        mb.mbox((342, 394), (WE + 15, WE + 15 + lamp_f[2][0]), (WD + 27, WD + 27), (cope, cope, cope), ft=lamp_f[0], card=True, panes=lamp_f[1]),
        mb.mbox((514, 566), (WE + 15, WE + 15 + lamp_f[2][0]), (WD + 27, WD + 27), (cope, cope, cope), ft=lamp_f[0], card=True, panes=lamp_f[1]),
        # the wing's pilasters, standing out of its front on their bases
        *[mb.mbox((a, b), (12, WE - 3), (WD - 8, WD), (cope, px(470, 326), shade(px(470, 326), .8)), ft=shaft_r[i]) for i, (a, b) in enumerate(shafts)],
        *[mb.mbox((a, b), (0, 12), (WD - 10, WD), (px(WF - 4, a + 6), px(WF - 4, a + 6), shade(px(WF - 4, a + 6), .8)), ft=base_r[i]) for i, (a, b) in enumerate(bases)],
        # the tall block: behind the chamfer, the tower, the loggia's storey and the porch's (each its own volume: the
        # loggia and the porch are open in front of them)
        mb.mbox((247, 307), (0, WE + 15), (CH, WD), (brick, brick, shade(brick, .85)), st=tside_r, top=False),
        mb.mbox((8, 160), (124, 258), (LB, 240), (brick, brick, shade(brick, .85)), ft=lw_r, st=lside_r, panes=lw_panes, top=False),
        mb.mbox((36, 160), (0, 124), (PB, 240), (brick, brick, shade(brick, .85)), ft=dw_r, st=gside_r, panes=dw_panes, top=False),
        mb.mbox((160, 247), (0, E), (0, 240), (brick, brick, shade(brick, .85)), ft=t1_r, st=tside_r, panes=t1_panes, top=False),
        mwall((247, 286), (0, CH), (0, E), brick, ft=ch_r),
        # the loggia: its column at the back, its end wall, the slab it stands on
        mcyl((53, LB), (17, 17), (-90, 90), (164, 258), (None, px(320, 50)), ft=uc),
        mb.mbox((8, 33), (164, 258), (0, LB), (cope, px(350, 20), shade(px(350, 20), .85)), ft=endw, top=False),
        mb.mbox((8, 160), (124, 164), (0, LB), (px(370, 100), px(420, 100), shade(px(420, 100), .85)), ft=slab_f, tt=lfloor_r),
        # the porch: the landing, the steps, the partition, the column
        mb.mbox((66, 160), (0, PH), (34, PB), (tread, riser, shade(riser, .85)), tt=landing),
        *[mb.mbox((66, 160), (5 * k, 5 * k + 5), (4 + 7.5 * k, 34), (tread, riser, shade(riser, .85)), outline=True) for k in range(4)],
        mb.mbox((60, 66), (0, 124), (4, PB), (px(450, 63), px(450, 63), shade(px(450, 63), .85)), ft=part_r),
        mcyl((49, 43), (13, 13), (-90, 90), (0, 124), (None, px(480, 45)), ft=lc),
        # the round pillars up the front
        mcyl((171, 5), (11, 11), (-90, 90), (0, E), (None, px(400, 170)), ft=pl),
        mcyl((237.5, 3.5), (9.5, 9.5), (-90, 90), (0, E), (None, px(400, 237)), ft=pm),
        mcyl((296.5, CH + 1), (10.5, 10.5), (-90, 90), (0, E), (None, px(400, 296)), ft=pr),
        # the roof: the slab, the parapet's back and sides, the stair house and its pyramid, the rail behind it
        mb.mbox((2, 318), (E - 3, E), (0, 240), (floor_c, ink, ink), ft=slab_e, tt=roof_r),
        mb.mbox((2, 318), (E, E + 15), (205, 240), (cope, px(85, 100), px(85, 100)), ft=pb_r, tt=pb_t),
        mb.mbox((10, 308), (E + 15, E + 34), (220, 220), (ink, ink, ink), ft=rail_tr, card=True),
        mb.mbox((35, 128), (E, E + 68), (143, 214), (sh_c, sh_c, shade(sh_c, .85)), ft=sh_r, st=sh_s, top=False),
        pyr(35, 128, E + 68, 143, 214, sky1),
        mb.mbox((2, 28), (E, E + 15), (25, 205), (cope, band, band), tt=pl_top, st=ps),
        mb.mbox((292, 318), (E, E + 15), (25, 205), (cope, band, band), tt=pr_top, st=ps),
        mb.mbox((10, 12), (E + 15, E + 34), (10, 220), (ink, ink, ink), st=rail_ts),
        mb.mbox((306, 308), (E + 15, E + 34), (10, 220), (ink, ink, ink), st=rail_ts),
        # the sign on its legs, its cap, the badge. (The art draws the board's foot just over the coping; as an
        # elevation the legs lift it clear of the parapet's rail, or the rail would run across its letters)
        *[mb.mbox((a, a + 6), (E, SB), (33, 39), (ink, px(268, a + 2), shade(px(268, a + 2), .8)), outline=True) for a in (68, 160, 250)],
        mb.mbox((55, 265), (SB, SB + 50), (30, 42), (cope, px(230, 60), shade(px(230, 60), .85)), ft=board_r, panes=board_pane, top=False),
        mb.mbox((48, 272), (SB + 50, SB + 57), (27, 45), (px(195, 60), px(206, 60), shade(px(206, 60), .85)), ft=cap_f, tt=cap_t_r),
        mb.mbox((128, 194), (SB + 40, SB + 40 + bd.shape[0]), (26, 26), (cope, cope, cope), ft=badge, card=True),
        # the front parapet and its rail
        mb.mbox((2, 318), (E, E + 15), (0, 25), (cope, band, band), ft=pf, tt=pt_r, st=ps),
        mb.mbox((10, 308), (E + 15, E + 34), (10, 10), (ink, ink, ink), ft=rail_tr, card=True),
    ]
    # which rounded and turned walls have windows that light at night
    for p in parts:
        if p['t'] in ('cyl', 'wall') and 'ft' in p and at.glows(p['ft']): p['sg'] = True
    setback = 48
    lawn, fence, fence_snow = mb.yard(rng, W, setback + 272, False, (0, 0, 0), 'natural', [], ground='paving')
    # the whole front for what does not build it (the wet road's mirror): the art's elevations where each stands
    front = np.zeros((SB + 40 + bd.shape[0], W, 4), np.uint8); emis = np.zeros_like(front)
    H = front.shape[0]
    def over(pic, x, y_top, glow=None):
        r0 = H - y_top; h, w = pic.shape[:2]; m = pic[..., 3] > 0
        front[r0:r0 + h, x:x + w][m] = pic[m]
        emis[r0:r0 + h, x:x + w][m] = glow[m] if glow is not None else 0
    over(wf, 307, WE, wf_glow); over(lw, 8, 258, lw_glow); over(dw, 36, 124, dw_glow); over(t1, 160, E, t1_glow); over(ch, 247, E, ch_glow)
    over(art[301:395, 8:33], 8, 258); over(art[395:435, 8:160], 8, 164); over(art[283:301, 2:318], 2, E + 15)
    over(board, 55, SB + 50, bglow); over(art[203:210, 48:272], 48, SB + 57); over(bd, 128, SB + 40 + bd.shape[0])
    model = dict(kind='police', w=W, frontX=(224 - W) / 2, setback=setback, lawn='paving', style='natural', parts=parts,
                 fence=None, roofTint=mb.rgb_int(floor_c), lamp=[113, 48, PB], lights='lamp', smoke=[], fpx=0)
    return model, at, front, tside, emis, lawn, fence, fence_snow
