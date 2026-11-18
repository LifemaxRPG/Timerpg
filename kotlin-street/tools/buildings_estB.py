"""Two of the estates (houseEstate), each modelled by hand from its sprite as the villas are (see make_buildings.py
build_model): the Japanese house and the modern house. Imported by make_buildings.build_model; each builder returns
what the others do (the model, its atlas, the whole front, a side, the front's glow, the yard's lawn and fence)."""
import numpy as np
from make_buildings import (load, solid, Atlas, cut, polygon, column_fill, hand_panes, board_side, rgb_int, shade,
                            rise_for, mbox, mgable, mhip, yard, column_rows, side_wall, tile_x, fl, glass)


def box(*a, hide=None, **k):
    """A box (make_buildings.mbox); `hide` 'l', 'r' or 'lr': those sides stand against other volumes (no view sees them)."""
    p = mbox(*a, **k)
    if hide: p['hs'] = hide
    return p


def mrail(a, b, h, bar, glass=None, alpha=0, posts=0, w=2):
    """A railing: its handrail `h` above the line from a to b (each x, y, d: a stair's nosings, a terrace's edge), `w`
    thick, in `bar`'s colour; under it a glass panel (`glass` at `alpha` of 255) or nothing, and posts every `posts`
    pixels along it (0: one at each end only)."""
    p = dict(t='rail', x=fl((a[0], b[0])), y=fl((a[1], b[1])), d=fl((a[2], b[2])), h=float(h), w=float(w), c=[rgb_int(bar), rgb_int(glass) if glass is not None else -1],
             ga=int(alpha), posts=float(posts))
    return p


def slope_only(x, y, d, right, rp, rake, roofc, lip):
    """A single-pitch roof whose high edge is its left end (x[0]): only its right slope (make_buildings.mgable has the
    high edge at the right, its left slope)."""
    p = mgable(x, y, d, None, (0,) * 8, right, rp, rake, roofc, lip=lip, ax=x[0])
    del p['l'], p['lp']
    return p


def keep_boxes(pic, x0, y0, boxes):
    """`pic` (its top left at the art's x0, y0) with only what lies in `boxes` (x0, y0, x1, y1 in the art) kept: a thing
    cut from the floor it stands on in the art, by hand where its colours are the floor's."""
    m = np.zeros(pic.shape[:2], bool)
    for (a, b, c, d) in boxes: m[b - y0:d - y0, a - x0:c - x0] = True
    return cut(pic, m)


def lean_to_end(lit, edge, run, rise, courses=None):
    """A lean-to roof's end as a hipped roof's side piece (columns up from the eave to the roof's edge, rows along the
    depth, the back first): the wall under it in its own siding (`lit`, a colour per column of the front's, here running
    along the depth as the side wall's do; or `courses`, level boards a colour per row from the eave up, as a side wall
    in lap siding has them), the roof's edge in its last two columns."""
    w = max(3, int(round(rise)))
    pic = np.zeros((run, w, 4), np.uint8)
    if courses is not None: pic[..., :3] = np.asarray(courses)[np.arange(w) % len(courses)][None, :, :3]
    else: pic[..., :3] = np.asarray(lit)[np.arange(run) % len(lit)][:, None, :3]
    pic[:, -2:, :3] = edge[:3]
    pic[..., 3] = 255
    return pic


def lean_to(at, x0, x1, eave, run, rows, lit, edge, roofc, d0=0, courses=None):
    """A lean-to roof over x0..x1 running back `run` from `d0`, its eave at `eave`, rising to the back: its face the
    art's roof from above (`rows`, already taken one per screen row), its two ends the wall's siding under its edge.
    Returns the part and its rise."""
    rise = rise_for(rows.shape[0], run)
    face_r = at.add(rows)
    end = lean_to_end(lit, edge, run, rise, courses); end_r = at.add(end)
    q = [0, run - 1, 0, 0, end.shape[1] - 1, 0, end.shape[1] - 1, 1]
    return mhip((x0, x1), (eave, eave + rise), (d0, d0 + run), (x0, x1, d0 + run - 1, d0 + run), face_r, end_r, q, end_r, q, roofc), rise


def to_ground(pic, ink=(0x3a, 0x3a, 0x50), reach=12):
    """`pic` (a front, its last row the ground's) with each column's foot carried down to the ground in the art's ink:
    solid() takes away the half-clear ground shadow the art draws under a wall, which left the wall's last rows open
    (a stepped strip of pavement under it). Only columns whose wall ends within `reach` rows of the ground."""
    pic = pic.copy(); h = pic.shape[0]
    for c in range(pic.shape[1]):
        op = np.nonzero(pic[:, c, 3] > 0)[0]
        if len(op) and h - 1 - reach <= op.max() < h - 1:
            pic[op.max() + 1:, c, :3] = ink; pic[op.max() + 1:, c, 3] = 255
    return pic


# ------------------------------------------------------------------------------------------------ the Japanese house
# A front-gabled main house of two storeys in corrugated siding; along the ground floor's front a pent roof of copper
# sheets (a lean-to strip on the wall between the storeys); the two upper windows are bays standing out of the wall on
# lattice sills; on each side a lower annex under its own lean-to roof running back (corrugated iron on the left, a
# board roof on the right). The art draws the pent roof and both annexes' roofs from above inside the front: in the
# fronts they are the walls' own siding carried down (each column keeps its colour), and the engine builds the roofs.
def estate_japanese(shop, rng):
    art = solid(np.array(load('estate_japanese_house')))
    at = Atlas(512)
    G = 447                                                          # the ground row
    AX, X0, X1 = 242, 66, 418                                        # the main gable's apex and eaves
    def rake(x): return int(170 + .5 * abs(x - AX))                   # its barge board's top in column x
    P = 10                                                           # the corrugation repeats every 10 columns
    W = 470
    roof_c = art[150, 150]; rake_c = art[rake(150) + 8, 150]; edge_c = art[rake(150) + 18, 150]; lip_c = art[rake(150) - 2, 150]
    wall_c = art[300, 232]
    PENT0, PENT1 = 340, 383                                          # the pent roof's rows (from above) in the art
    lit = art[300, 230:230 + 2 * P]                                  # the plain corrugation between the bays (x 140 is a bay's sill)
    corner = shade(wall_c, .75) + (255,)
    foot = [tuple(art[440, 330, :3])] * 3
    # --- the main front, the gable down to the ground (columns 54..429); the pent roof painted out with the
    # corrugation above it
    face = art[170:G + 1, 54:430].copy()
    for c in range(face.shape[1]):
        x = c + 54
        face[:max(0, rake(x) - 170), c] = 0
        if x < 56 or x > 426: face[max(0, rake(x) - 170 + 26):, c] = 0
    pm = np.zeros(face.shape[:2], bool); pm[PENT0 - 170:PENT1 - 170, :292 - 54] = True
    face = column_fill(face, pm, lambda x: range(PENT0 - 1 - 170, 300 - 170, -1))
    # the hoods over the upper windows (rows 248..271, the art's from above) painted out with the wall above them where
    # the barge board leaves some (under it the hood, built, covers the board's own pixels)
    HOOD = (248, 272); bays = [(107, 223), (267, 383)]
    hm = np.zeros(face.shape[:2], bool)
    for (a, b) in bays:
        for x in range(a, b): hm[max(HOOD[0], rake(x) + 22) - 170:HOOD[1] - 170, x - 54] = True
    face = column_fill(face, hm, lambda c: range(HOOD[0] - 1 - 170, rake(c + 54) + 21 - 170, -1))
    face = to_ground(face)
    # its windows: the long one under the right eave (clear glass), the lattice windows of the ground floor (their
    # dark glass lights at night between the slats; behind the slats it mirrors no square of sky: 0)
    f_panes, f_glow = hand_panes(face, [(258, 186, 92, 16, 1)])
    # (the left one runs on into the left annex's front: its part there is the annex's pane)
    g_panes, g_glow = hand_panes(face, [(2, 218, 27, 46, 0), (39, 215, 57, 38, 0), (146, 215, 70, 37, 0)], least=200)
    f_panes += g_panes; f_glow = np.maximum(f_glow, g_glow)
    face_r = at.add(face, f_glow)
    # --- the upper windows' bays, standing out of the wall: the window, its frame and the lattice sill under it as drawn
    BAY = (272, 334)
    bay_pics = []
    for (a, b) in bays:
        pic = art[BAY[0]:BAY[1], a:b].copy()
        bp, bg = hand_panes(pic, [(17, 17, 34, 11, 1), (63, 17, 33, 11, 1)])
        bay_pics.append((at.add(pic, bg), bp, pic, bg))
    bay_side = board_side(art[300, 112:114], 8, BAY[1] - BAY[0], rng, shade(art[300, 112], .7) + (255,))[0]
    bay_side_r = at.add(bay_side)
    # --- the main gable's slopes from above, up to the barge boards
    keep = np.zeros(art.shape[:2], bool)
    for x in range(X0, X1 + 1): keep[:rake(x), x] = art[:rake(x), x, 3] > 0
    left = at.add(cut(art[0:rake(X0) + 1, X0:AX], keep[0:rake(X0) + 1, X0:AX]))
    right = at.add(cut(art[0:rake(X1) + 1, AX:X1 + 1], keep[0:rake(X1) + 1, AX:X1 + 1]))
    lw, rw = AX - X0, X1 + 1 - AX
    # --- the left annex: its front from its roof's edge (row 276) down, the pent's rows its siding carried down; its
    # lean-to roof of corrugated iron (the art's from above, every fourth row: a gentle pitch that meets the main eave)
    LA0, LA1, LTOP = 16, 56, 276
    la = art[LTOP:G + 1, LA0:LA1].copy()
    m = np.zeros(la.shape[:2], bool); m[PENT0 - LTOP:PENT1 - LTOP] = True
    la = column_fill(la, m, lambda x: range(PENT0 - 1 - LTOP, 0, -1))
    la = to_ground(la)
    la_panes, la_glow = hand_panes(la, [(24, 112, 16, 46, 0)], least=200)
    la_r = at.add(la, la_glow)
    RUN = 200
    la_roof = art[122:LTOP:4, LA0:LA1].copy(); la_roof[..., 3] = 255
    la_edge = art[124, 30]
    la_side, _ = board_side(lit, RUN, G + 1 - LTOP, rng, corner, (), foot)
    la_side_r = at.add(la_side)
    la_hip, la_rise = lean_to(at, LA0, LA1, G + 1 - LTOP, RUN, la_roof, lit, la_edge, art[200, 30])
    # --- the right annex: the same, its board roof, its downpipe down the front as drawn. The art draws its wall in lap
    # siding at a slant (its side seen at an angle, x 442..463) over a brown base: in the front the boards are set
    # level (each column taken as many rows lower as the boards fall there) and the front
    # ends where the base does (x 456; past it the art draws only that slanted side); the side wall wears the same
    # boards and base, a row each, with a lattice window of the front's.
    RA0, RA1, RTOP = 426, 456, 280
    ra = art[RTOP:G + 1, RA0:RA1].copy()
    for x in range(442, RA1):
        o = 2 * ((x - 446) // 4)                                     # (the boards fall two rows every four columns)
        ra[304 - RTOP:366 - RTOP, x - RA0] = art[304 + o:366 + o, x]
    ra = to_ground(ra)
    ra_r = at.add(ra)
    ra_roof = art[112:RTOP:4, RA0:RA1].copy(); ra_roof[..., 3] = 255
    courses = column_rows(ra, list(range(446 - RA0, RA1 - RA0)))
    lat = art[384:441, 94:152]
    ra_side, ra_glow = side_wall(courses, RUN, None, 0, (), rng, shade(art[330, 450], .7) + (255,))
    wy, wx = 96, RUN // 2 - lat.shape[1] // 2; lm = lat[..., 3] > 0
    ra_side[wy:wy + lat.shape[0], wx:wx + lat.shape[1]][lm] = lat[lm]
    lg = hand_panes(lat, [(0, 1, 56, 38, 0)], least=200)[1]; gm = lg[..., 3] > 0
    ra_glow[wy:wy + lat.shape[0], wx:wx + lat.shape[1]][gm] = lg[gm]
    ra_side_r = at.add(ra_side, ra_glow)
    # (the lean-to's ends: the side's boards carried up under its edge, from the eave's course up)
    ra_hip, ra_rise = lean_to(at, RA0, RA1, G + 1 - RTOP, RUN, ra_roof, None, art[276, 440], art[200, 440],
                              courses=courses[24:42][::-1])
    # --- the pent roof: its copper sheets from above every other row (one screen row each), its ends in its edge colour
    pent = art[PENT0:PENT1:2, 18:292].copy()
    prun = 24; prise = rise_for(pent.shape[0], prun)
    pent_r = at.add(pent)
    pend = np.zeros((prun, max(2, int(prise)), 4), np.uint8); pend[..., :3] = art[378, 100][:3]; pend[..., 3] = 255
    pend_r = at.add(pend)
    pq = [0, prun - 1, 0, 0, pend.shape[1] - 1, 0, pend.shape[1] - 1, 1]
    # --- the main house's side walls in the front's corrugation, an upper window of the front's on each
    win = art[282:322, 268:332]
    ms, ms_glow = board_side(lit, 220, G - rake(X0), rng, corner, [(win, .5, 30, (8, 6, 48, 28, 1))], foot)
    ms_r = at.add(ms, ms_glow)
    E = G - rake(X0)
    parts = [
        # the main house and its gable
        mgable((X0, X1), (E, G - 170), (0, 220), left, (0, rake(X0), lw, 170, lw, 0, 0, 95), right, (rw - 1, rake(X1), 0, 170, 0, 0, rw - 1, 95),
               (rake_c, edge_c), roof_c, lip=lip_c),
        mbox((56, 426), (0, E), (0, 220), (wall_c, wall_c, wall_c), ft=face_r, fx=54, st=ms_r, panes=f_panes, top=False),
        # the annexes either side, against its side walls (their sides there are inside it), under their lean-tos
        box((LA0, LA1), (0, G + 1 - LTOP), (0, RUN), (wall_c, wall_c, wall_c), ft=la_r, st=la_side_r, panes=la_panes, top=False, hide='r'),
        la_hip,
        box((RA0, RA1), (0, G + 1 - RTOP), (0, RUN), (wall_c, wall_c, wall_c), ft=ra_r, st=ra_side_r, top=False, hide='l'),
        ra_hip,
    ]
    # the upper windows' bays, each under its hood: a little lean-to of corrugated iron from the wall out past the bay
    # (the art's from above, every other row; its open ends in the shade under it)
    for (a, b), (r, bp, _, _) in zip(bays, bay_pics):
        parts.append(mbox((a, b), (G - BAY[1], G - BAY[0]), (-8, 0), (art[BAY[0] + 1, a + 20], art[300, a + 2], art[300, a + 2]), ft=r, st=bay_side_r, panes=bp))
        hood = art[HOOD[0]:HOOD[1], a - 1:b + 1].copy()
        for x in range(a - 1, b + 1): hood[:max(0, rake(x) + 22 - HOOD[0]), x - a + 1] = 0
        hood = hood[::2]
        under_c = shade(art[260, a + 20], .55) + (255,)
        hp, _ = lean_to(at, a - 1, b + 1, G - BAY[0], 10, hood, [under_c], under_c, art[260, a + 20], d0=-10)
        parts.append(hp)
    # the pent roof along the ground floor's front
    parts.append(mhip((18, 292), (G - 382, G - 382 + prise), (-prun, 0), (18, 292, -1, 0), pent_r, pend_r, pq, pend_r, pq, art[360, 100]))
    setback = int(shop.get('yard', {}).get('front', 2)) * 32 + 24
    style = shop.get('yard', {}).get('style', 'compact')
    lawn, fence, fence_snow = yard(rng, W, setback + 220, False, (156, 196, setback), style, [(150, 202)], ground='paving')
    # the whole front for what does not build it (the corner turn, the wet road's mirror), rows from the apex down:
    # the face, the annexes, the bays and the pent roof as the street sees them from straight ahead
    H = G - 170 + 1
    front = np.zeros((H, W, 4), np.uint8); emis = np.zeros_like(front)
    def over(pic, x, y_top, glow=None):
        r0 = H - y_top; h, w = pic.shape[:2]; m = pic[..., 3] > 0
        front[r0:r0 + h, x:x + w][m] = pic[m]
        emis[r0:r0 + h, x:x + w][m] = glow[m] if glow is not None else 0
    over(face, 54, H, f_glow); over(la, LA0, G + 1 - LTOP, la_glow); over(ra, RA0, G + 1 - RTOP)
    for (a, b), (_, _, pic, bg) in zip(bays, bay_pics): over(pic, a, G - BAY[0], bg)
    for (a, b) in bays: over(art[HOOD[0]:HOOD[1], a:b], a, G - HOOD[0] + 1)
    over(art[PENT0:PENT1 + 1, 18:292], 18, G - PENT0 + 1)
    model = dict(kind='estate', w=W, frontX=(224 - W) / 2, setback=setback, lawn='paving', style=style, parts=parts,
                 fence=dict(d=3, foot=28 if style != 'formal' else 32) if fence is not None else None,
                 roofTint=rgb_int(roof_c), lamp=[176, 70, -2], lights='home', smoke=[], fpx=0)
    return model, at, front, ms, emis, lawn, fence, fence_snow


# ------------------------------------------------------------------------------------------------ the modern house
# A two-storey block under a single-pitch roof whose high edge is its left end (the roof falls across the front to the
# right, its slab drawn from above as the art draws it), its upper storey standing back on the flat roof of a long
# glazed ground storey: a terrace with a glass-railed edge, a cantilever parasol over an armchair, a chair and a table,
# a round column under the roof's corner; behind the terrace a higher deck behind a tiled wall and a glass railing; up the
# left side a stair to the terrace; on the right, under the roof's low end, the entrance set back beneath the
# overhang, a slanted strut holding the roof's corner. The art is an oblique view: a thing `d` back from the front line
# and `y` up stands at row 444 - y - d. The fronts are true elevations; what the art draws from above (the slab, the
# terrace's floor, the parasol's canopy) is laid on geometry, and what stands on them is built.
def estate_modern(shop, rng):
    raw = np.array(load('estate_modern_house')); art = solid(raw)
    at = Atlas(512)
    G = 444                                                          # the row under the ground line at the front
    T = 104                                                          # the terrace (the ground storey's flat roof)
    DM = 34                                                          # the upper storey's front, back from the front line
    DB = 78                                                          # the entrance's front, under the overhang
    DEEP = 285                                                       # the back of the house
    R0, R1, RX = 284, 720, 545                                       # the roof's columns; the upper storey's right end
    W = 736
    def edge(x):
        """The first row of the roof's fascia in column x: under the slab's tiles, 8 rows of fascia over 2 of shadow
        (it falls two rows every fourteen columns from row 246 at column 290; found in the art where it can be)."""
        e = 246 + 2 * ((x - 290) // 14)
        for r in range(e - 4, e + 5):
            col = art[r:r + 10, x, :3].astype(int)
            if (col[:8] == (0x46, 0x46, 0x5e)).all(-1).sum() >= 7 and (art[r - 1, x, :3] != (0x46, 0x46, 0x5e)).any():
                return r
        return e
    f = {x: edge(x) for x in range(R0 - 2, R1 + 2)}
    def yof(row, d): return G - d - row                               # the height of the bottom of art row `row` at depth d
    wall_c = art[262, 290]; frame_c = art[350, 530]; floor_c = art[210, 250]; tile_c = art[170, 200]
    dark = np.array([0x3a, 0x3a, 0x50, 255], np.uint8)
    # --- the roof's slab from above, column by column down to the fascia
    tops = {x: int(np.where(art[:, x, 3] > 0)[0].min()) for x in range(R0, R1)}
    rw = R1 - R0; rh = f[R1 - 1]
    slab = np.zeros((rh, rw, 4), np.uint8)
    for x in range(R0, R1): slab[tops[x]:f[x], x - R0] = art[tops[x]:f[x], x]
    slab_r = at.add(slab)
    y_hi, y_lo = yof(f[R0] - 1, DM), yof(f[R1 - 1] - 1, DM)          # the roof's top at its high and low ends
    def under(x): return yof(f[x] + 9, DM)                           # the fascia's foot (the roof's underside) in column x
    # --- the upper storey's front: from the fascia down to the terrace, the column standing before its left end painted
    # out (the wall at its left carried across: it is built)
    top_row = min(f[x] for x in range(R0, RX))
    face = np.zeros((306 - top_row, RX - R0, 4), np.uint8)
    for x in range(R0, RX): face[f[x] - top_row:, x - R0] = art[f[x]:306, x]
    col_m = np.zeros(face.shape[:2], bool); col_m[:, 297 - R0:319 - R0] = True; col_m[:f[300] + 10 - top_row] = False
    for (yy, xx) in zip(*np.nonzero(col_m)): face[yy, xx] = face[yy, 290 - R0]
    face_panes, face_glow = hand_panes(face, [(352 - R0, 263 - top_row, 46, 36, 1), (422 - R0, 268 - top_row, 44, 31, 1), (492 - R0, 273 - top_row, 46, 27, 1)])
    face_r = at.add(face, face_glow)
    # its sides: the white render of its left end, the dark band of the fascia along the top
    def render_side(h, depth, band=10):
        sd = np.zeros((h, depth, 4), np.uint8); sd[...] = wall_c; sd[:min(band, h)] = art[f[300] + 2, 300]
        sd[:, :2, :3] = shade(wall_c, .8); sd[..., 3] = 255
        return sd
    hs = render_side(y_hi - T, DEEP - DM); hs_r = at.add(hs)
    a1s = render_side(under(RX - 1) - T, DEEP - DM, 0); a1s_r = at.add(a1s)
    # --- the entrance under the overhang: its front as drawn from under the fascia down to its foot (row 365); the wall
    # above it, which the fascia hides in the art, carries on the upper storey's own band under the roof's edge (its
    # strip windows between grey panels and their sills, every 70 columns as there, each column from under the fascia
    # down to its sill), plain wall under that down to the door's head
    EX1 = 706
    bh = max(under(x) for x in range(RX, EX1))
    ent = np.zeros((bh, EX1 - RX, 4), np.uint8)
    def src(x): return 352 + (x - 352) % 70
    sill = max(bh - under(x) for x in range(RX, EX1)) + 298 - 262    # (the band's foot, level as there; its head
                                                                     # the windows' frame, row 262, carried up)
    for x in range(RX, EX1):
        c = x - RX; foot = 365
        top = f[x] + 10
        col = art[top:foot + 1, x].copy()
        h = under(x)
        shown = col[-min(len(col), h):]
        ent[bh - len(shown):, c] = shown
        sx = src(x)
        for r in range(bh - h, min(sill, bh - len(shown))): ent[r, c] = art[max(262, 298 - (sill - r)), sx]
        ent[sill:bh - len(shown), c] = art[300, sx]
        ent[bh - h:bh - h + 3, c] = dark
    # (the strut and the potted plant stand in front of its right end: built, the wall behind them carried down)
    ent_clean = ent.copy()
    for x in range(668 - RX, EX1 - RX):                              # (the door's boards carried on behind the strut)
        ent_clean[:, x] = ent[:, 660 - RX - (x - (668 - RX)) % 8]
    ent_clean[ent[..., 3] == 0] = 0
    pm = np.zeros(ent.shape[:2], bool); pm[bh - (366 - 346):, 626 - RX:684 - RX] = True
    ent_clean = column_fill(ent_clean, pm, lambda x: range(bh - (366 - 346) - 1, 0, -1))
    runs = []                                                        # the band's windows: runs of columns with glass
    gl = glass(ent_clean)
    for c in range(ent_clean.shape[1]):
        ys = np.nonzero(gl[:bh - (365 - 305) - 2, c])[0]
        if not len(ys): continue
        if runs and c == runs[-1][1]: runs[-1][1:] = [c + 1, min(runs[-1][2], int(ys.min())), max(runs[-1][3], int(ys.max()) + 1)]
        else: runs.append([c, c + 1, int(ys.min()), int(ys.max()) + 1])
    e_panes, e_glow = hand_panes(ent_clean, [(568 - RX, bh - (365 - 305) - 1, 42, 54, 1)]
                                 + [(a, y0, b - a, y1 - y0, 1) for (a, b, y0, y1) in runs if b - a > 6 and y1 - y0 > 6])
    ent_r = at.add(ent_clean, e_glow)
    ents = render_side(under(EX1 - 1), DEEP - DB, 0); ents_r = at.add(ents)
    # --- the fascia across the overhang (the upper storey's front carries it to its right end)
    fas_top = f[RX]; fas = np.zeros((f[R1 - 1] + 10 - fas_top, R1 - RX, 4), np.uint8)
    for x in range(RX, R1): fas[f[x] - fas_top:f[x] + 10 - fas_top, x - RX] = art[f[x]:f[x] + 10, x]
    fas_r = at.add(fas)
    # --- the ground storey: its front (rows 340..443) with its glazing, curtains and doors run down to the ground (they
    # stand back behind the frame in the art, their foot drawn higher), the potted plant before it built
    GX0, GX1 = 50, 546
    gf = np.zeros((G - 340, GX1 - GX0, 4), np.uint8)
    for x in range(GX0, GX1):
        col = art[340:G, x].copy()
        op = np.where(col[:, 3] > 0)[0]; b = int(op.max()) if len(op) else 0
        if b < G - 340 - 3:
            k = G - 340 - 1 - b; at_r = max(30, b - 14)
            col = np.concatenate([col[:at_r], np.repeat(col[at_r:at_r + 1], k, 0), col[at_r:b + 1]])
        gf[:, x - GX0] = col[:G - 340]
    pl = np.zeros(gf.shape[:2], bool); pl[386 - 340:, 228 - GX0:283 - GX0] = True
    gf = column_fill(gf, pl, lambda x: range(385 - 340, 0, -1))
    gf_panes, gf_glow = hand_panes(gf, [(120 - GX0, 27, 98, 76, 1), (295 - GX0, 27, 72, 76, 1), (400 - GX0, 27, 74, 76, 1)])
    gf_r = at.add(gf, gf_glow)
    gfs, _ = side_wall(column_rows(gf, list(range(GX1 - GX0 - 16, GX1 - GX0 - 4))), 150, None, 0, (), rng, shade(frame_c, .8) + (255,))
    gfs_r = at.add(gfs)
    # --- the terrace's floor from above (rows 190..339: 150 deep), what stands on it painted out with the floor's own
    # tiles from along its row (the rug's from the rug)
    fl0 = 190
    floor = art[fl0:340, GX0:GX1].copy()
    m = np.zeros(floor.shape[:2], bool)
    def rect(x0, y0, x1, y1): m[max(0, y0 - fl0):max(0, y1 - fl0), max(0, x0 - GX0):max(0, x1 - GX0)] = True
    rect(94, 178, 244, 306)                                          # the parasol, its base, the chairs and the table
    rect(50, 172, 66, 340); rect(60, 318, 546, 332)                  # the railings (left, front)
    rect(290, 250, 322, 334)                                         # the column
    rug = np.zeros_like(m); rug[258 - fl0:304 - fl0, 122 - GX0:218 - GX0] = True
    for (yy, xx) in zip(*np.nonzero(m)):
        for k in (16, -16, 32, -32, 48, -48, 64, -64, 80, -80, 96, -96, 112, -112, 128, -128):
            sx = xx + k
            if 0 <= sx < floor.shape[1] and not m[yy, sx] and rug[yy, sx] == rug[yy, xx]:
                floor[yy, xx] = floor[yy, sx]; break
    floor[..., 3] = 255
    floor_r = at.add(floor)
    # --- the deck behind the terrace: its tiled wall (rows 150..190 at depth 150), its glass railing (the art's, with
    # its half-clear glass)
    DK = 150
    dk_h = yof(149, DK)
    dkf = np.concatenate([art[150:191, 150:285]] + [art[154:180, 150:285]] * 6)[:dk_h]
    dkf = tile_x(dkf, 285 - 4)
    dk_r = at.add(dkf)
    dks, _ = side_wall(column_rows(dkf, list(range(0, 60))), DEEP - DK, None, 0, (), rng, shade(tile_c, .8) + (255,))
    dks_r = at.add(dks)
    dk_rail = tile_x(raw[118:144, 160:280], 285 - 4)
    dk_rail_r = at.add(dk_rail)
    # --- the column under the roof's left corner: its bottom rows as drawn
    ch = under(300) - T
    colpic = art[331 - ch:331, 297:319]
    col_r = at.add(colpic)
    # --- the slanted strut and the potted plants: cards as drawn
    strut = art[300:392, 668:716].copy()
    srgb = strut[..., :3].astype(int)
    strut[(srgb[..., 1] > srgb[..., 0] + 20) & (srgb[..., 1] > srgb[..., 2])] = 0
    # (only the strut: not the door beside its foot, nor the roof's fascia over its head, which the roof draws)
    strut[~polygon(strut.shape, [(1, 91), (23, 91), (41, 14), (16, 14)])] = 0
    strut_r = at.add(strut)
    plant_l = art[386:G, 228:283]; plant_r = art[340:400, 626:684]
    plant_l_r = at.add(plant_l); plant_r_r = at.add(plant_r)
    # --- the parasol: its canopy a low pyramid (the art's from above: the front face every other row, one screen row
    # each; its sides the art's wedges), the cantilever's mast, arm and hanger, its foot
    CX, CD = 168, 55                                                 # the canopy's middle
    cx0, cx1, cr0, cr1 = 120, 217, 180, 260
    crow = cr0 + (CD - 17) * (cr1 - cr0) // 76                        # the art's row of its middle
    cfront = art[crow:cr1:2, cx0:cx1].copy(); ch_rows = cfront.shape[0]
    cfront = cut(cfront, polygon(cfront.shape, [(CX - cx0, 0), (cx1 - cx0 - 1, ch_rows - 1), (0, ch_rows - 1)]))
    crise = rise_for(ch_rows, cr1 - crow)
    cfront_r = at.add(cfront)
    cl = cut(art[cr0:cr1, cx0:CX + 1], polygon((cr1 - cr0, CX + 1 - cx0), [(0, 0), (CX - cx0, crow - cr0), (0, cr1 - cr0 - 1)]))
    cr = cut(art[cr0:cr1, CX:cx1][:, ::-1], polygon((cr1 - cr0, cx1 - CX), [(0, 0), (cx1 - CX - 1, crow - cr0), (0, cr1 - cr0 - 1)]))
    cl_r = at.add(cl); cr_r = at.add(cr)
    ceave = yof(cr1 - 1, 17)
    cq_l = [0, cr1 - cr0 - 1, 0, 0, cl.shape[1] - 1, crow - cr0 - 1, cl.shape[1] - 1, crow - cr0 + 1]
    cq_r = [0, cr1 - cr0 - 1, 0, 0, cr.shape[1] - 1, crow - cr0 - 1, cr.shape[1] - 1, crow - cr0 + 1]
    mast_c = art[240, 112]; mast_s = art[240, 116]
    # --- the furniture on the terrace: cards as drawn, every other row (the art draws their seats from above)
    chair = keep_boxes(art[238:302, 202:240], 202, 238, [(209, 238, 231, 262), (202, 262, 240, 302)])[::2]
    table = keep_boxes(art[262:300, 159:188], 159, 262, [(159, 276, 188, 300), (165, 262, 174, 276), (175, 268, 184, 276)])[::2]
    chair_r = at.add(chair); chair_l = at.add(chair[:, ::-1].copy()); table_r = at.add(table)
    # --- the stair up the left side to the terrace: its steps (the art's colours), its glass railings
    tread, riser, stringer = art[260, 40], art[264, 40], art[300, 5]
    steps = 13; rise = T / steps; run = 10; S0 = DK - steps * run
    glass_c = raw[200, 46]; bar_c = art[322, 200]
    parts = [
        # the deck behind the terrace, its railing
        box((4, 285), (0, dk_h), (DK, DEEP), (floor_c, tile_c, tile_c), ft=dk_r, st=dks_r),
        box((4, 285), (dk_h, dk_h + dk_rail.shape[0]), (DK + 2, DEEP), (bar_c, bar_c, bar_c), ft=dk_rail_r, card=True),
        # under the upper storey's back half (behind the ground storey; the terrace's deck and the entrance
        # hide its sides)
        box((R0 + 1, RX), (0, T), (DK, DEEP), (frame_c, frame_c, frame_c), top=False, hide='lr'),
        # the ground storey, the terrace on its roof
        box((GX0, GX1), (0, T), (0, DK), (floor_c, frame_c, frame_c), ft=gf_r, tt=floor_r, st=gfs_r, panes=gf_panes),
        # the upper storey's high left end, the upper storey
        box((R0, R0 + 4), (T, y_hi), (DM, DEEP), (wall_c, wall_c, wall_c), st=hs_r, top=False),
        box((R0, RX), (T, under(RX - 1)), (DM, DEEP), (wall_c, wall_c, wall_c), ft=face_r, fx=R0, st=a1s_r, panes=face_panes, top=False, hide='l'),
        # the entrance under the overhang
        box((RX, EX1), (0, under(EX1 - 1)), (DB, DEEP), (wall_c, wall_c, wall_c), ft=ent_r, fx=RX, st=ents_r, panes=e_panes, top=False, hide='l'),
        # the roof: its slab from above on its single pitch, the fascia across the overhang
        slope_only((R0, R1), (y_lo, y_hi), (DM, DEEP), slab_r, (rw - 1, f[R1 - 1], 0, f[R0], 0, tops[R0], rw - 1, tops[R1 - 1]),
                   (art[f[400] + 3, 400], dark), art[150, 500], art[f[400] - 1, 400]),
        box((RX, R1), (y_lo - 10, under(RX) + 10), (DM, DM + 2), (art[f[600] + 2, 600],) * 3, ft=fas_r, fx=RX, card=True),
        # the column under its left corner, the strut under its right
        box((297, 319), (T, under(300)), (DM - 8, DM), (art[300, 308], art[300, 308], shade(art[300, 308], .8)), ft=col_r),
        box((668, 716), (0, strut.shape[0]), (52, 54), (art[330, 700],) * 3, ft=strut_r, card=True),
        # the parasol: its foot, mast, arm and hanger, its canopy; the chairs and the table
        box((96, 131), (T, T + 3), (40, 68), (art[270, 100], art[284, 100], art[284, 100]), outline=True),
        box((109, 117), (T + 3, yof(196, CD)), (CD - 3, CD + 3), (mast_c, mast_c, mast_s), outline=True),
        box((109, 172), (yof(204, CD), yof(196, CD)), (CD - 3, CD + 3), (mast_c, mast_c, mast_s), outline=True),
        box((164, 172), (ceave + crise, yof(204, CD)), (CD - 3, CD + 3), (mast_c, mast_c, mast_s), outline=True),
        box((136, 174), (T, T + chair.shape[0]), (CD + 4, CD + 6), (floor_c,) * 3, ft=chair_l, card=True),
        mhip((cx0, cx1), (ceave, ceave + crise), (17, 93), (CX - 1, CX + 1, CD - 1, CD + 1), cfront_r, cl_r, cq_l, cr_r, cq_r, art[230, 160]),
        box((159, 188), (T, T + table.shape[0]), (42, 44), (floor_c,) * 3, ft=table_r, card=True),
        box((202, 240), (T, T + chair.shape[0]), (38, 40), (floor_c,) * 3, ft=chair_r, card=True),
        # the terrace's railings: along its front, along its left edge (open at the stair's head)
        mrail((60, T, 4), (544, T, 4), 15, bar_c, posts=120),
        mrail((52, T, 4), (52, T, DK - 26), 15, bar_c, glass_c, 70, posts=0),
    ]
    # the stair: its steps from the front up to the terrace's height at the deck, their glass railing outside
    for i in reversed(range(steps)):                                 # (the far, high ones first: the near ones before them)
        parts.append(box((6, 48), (0, (i + 1) * rise), (S0 + i * run, S0 + (i + 1) * run), (tread, riser, stringer), outline=True))
    parts.append(mrail((5, rise, S0), (5, T, DK), 16, bar_c, glass_c, 70))
    # the plants before the house
    parts += [box((228, 283), (0, plant_l.shape[0]), (-6, -4), (art[440, 255],) * 3, ft=plant_l_r, card=True),
              box((626, 684), (0, plant_r.shape[0]), (44, 46), (art[396, 655],) * 3, ft=plant_r_r, card=True)]
    setback = int(shop.get('yard', {}).get('front', 2)) * 32 + 24
    style = shop.get('yard', {}).get('style', 'compact')
    lawn, fence, fence_snow = yard(rng, W, setback + DEEP, True, (572, 606, setback + DB), style, [(566, 612)])
    # the whole front for what does not build it (the corner turn, the wet road's mirror), rows from the roof's top down
    H = y_hi + 1
    front = np.zeros((H, W, 4), np.uint8); emis = np.zeros_like(front)
    def over(pic, x, y0, glow=None):
        h, w = pic.shape[:2]; r0 = H - y0 - h; m = pic[..., 3] > 0
        front[r0:r0 + h, x:x + w][m] = pic[m]
        emis[r0:r0 + h, x:x + w][m] = glow[m] if glow is not None else 0
    over(dkf, 4, 0); over(ent_clean, RX, 0, e_glow); over(face, R0, T, face_glow); over(fas, RX, y_lo - 10); over(gf, GX0, 0, gf_glow)
    over(colpic, 297, T); over(strut, 668, 0); over(plant_l, 228, 0); over(plant_r, 626, 0)
    model = dict(kind='estate', w=W, frontX=(224 - W) / 2, setback=setback, lawn='grass', style=style, parts=parts,
                 fence=dict(d=3, foot=28 if style != 'formal' else 32) if fence is not None else None,
                 roofTint=rgb_int(art[150, 500]), lamp=[620, 64, DB - 2], lights='home', smoke=[], fpx=0)
    return model, at, front, a1s, emis, lawn, fence, fence_snow


ESTATES = {'estate_japanese_house': estate_japanese, 'estate_modern_house': estate_modern}
