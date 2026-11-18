"""The estate houses of group A, the one-story house and the country house, as the parts make_buildings.py's
build_model writes to models.json (see there and docs/SPRITES_3D.md). Kept apart from make_buildings.py so each group's
builders merge cleanly; the helpers are its own."""
import numpy as np
from make_buildings import *
from make_buildings import rgb_int, shade

# (helpers of the estates' own, here so this module does not lean on another group's)
def hand_panes(img, boxes, least=300):
    """Window panes placed by hand (x, y, w, h in `img`'s pixels, then 1 square or 0 round), for art whose walls share
    the glass's blue-greys (a search for the glass finds the wall too); each glows at night over its lighter pixels
    (the glass, a blind's slats), not its frame's ink, a little brighter where the art's glass is lighter."""
    lum = img[..., :3].astype(int).sum(-1)
    glow = np.zeros_like(img)
    for (x, y, w, h, sq) in boxes:
        sub = lum[y:y + h, x:x + w]; m = (sub >= least) & (img[y:y + h, x:x + w, 3] > 200)
        if not m.any(): continue
        lo, hi = sub[m].min(), sub[m].max(); f = .8 + .2 * (sub - lo) / max(1, hi - lo)
        g = np.stack([np.round(GLOW[0] * f), np.round(GLOW[1] * f), np.round(GLOW[2] * f), np.full(f.shape, GLOW[3])], -1).astype(np.uint8)
        glow[y:y + h, x:x + w][m] = g[m]
    return [list(b) for b in boxes], glow

def board_side(lit, depth, h, rng, corner, windows=(), base=None):
    """A side wall of upright boards, `depth` deep and `h` tall, the front's own: `lit` one period of the front's lit
    boards (a colour per column) repeated along the depth, so they run up the side as up the front; the front's windows
    (each its picture, frame and all, the fraction of the depth its middle stands at, its top row and its glass box);
    `base` the colours of the wall's foot, a row each; darker corner boards and a fine grain. Returns it and where its
    windows glow."""
    side = np.zeros((h, depth, 4), np.uint8)
    side[:] = lit[np.arange(depth) % len(lit)][None]
    side[..., 3] = 255
    if base is not None: side[h - len(base):, :, :3] = np.asarray(base)[:, None, :3]
    glow = np.zeros_like(side)
    for (pic, f, wy, pane) in windows:
        wh, ww = pic.shape[:2]; m = pic[..., 3] > 200
        cx = int(depth * f) - ww // 2
        side[wy:wy + wh, cx:cx + ww][m] = pic[m]
        g = hand_panes(pic, [pane])[1]; gm = g[..., 3] > 0
        glow[wy:wy + wh, cx:cx + ww][gm] = g[gm]
    for c in (0, 1, depth - 2, depth - 1): side[:, c, :3] = corner[:3]
    grain = rng.integers(-3, 4, (h // 2 + 1, depth // 2 + 1))
    grain = np.kron(grain, np.ones((2, 2), np.int32))[:h, :depth]
    side[..., :3] = np.clip(side[..., :3].astype(int) + grain[..., None], 0, 255).astype(np.uint8)
    return side, glow

def gable_end(rows, depth):
    """A pitched roof's gable end as a hipped roof's side piece (columns up from the eave to the ridge, rows along the
    depth): each column the wall's course at that height (`rows`, the wall's colours from its foot up), so its siding
    runs on up into the triangle."""
    h = len(rows)
    pic = np.repeat(np.asarray(rows)[None, :, :], depth, axis=0).copy(); pic[..., 3] = 255
    return pic

def tile_x(pic, w):
    """`pic` repeated across to `w` columns."""
    return np.concatenate([pic] * (w // pic.shape[1] + 1), axis=1)[:, :w].copy()

def pent_end(color, run, rise):
    """A pent roof's end as a hipped roof's side piece (columns up the slope, rows along its depth): its edge colour."""
    pic = np.zeros((run, max(2, int(rise)), 4), np.uint8); pic[..., :3] = color[:3]; pic[..., 3] = 255
    return pic

def end_cut(pic, q, verge=None):
    """A roof's end piece (a hipped roof's side piece) cut to its quad `q` (the front eave, the back eave, the top's back
    and front ends, as x, y in its pixels): the engine maps the whole picture by those four points, so what lies
    outside them would stand above the roof. `verge`: the colours of a board along its sloping edges (its outer row,
    then two rows under it), as a gable end's verge board."""
    pic = pic.copy(); h, w = pic.shape[:2]
    m = polygon(pic.shape, [(q[0], q[1]), (q[2], q[3]), (q[4], q[5]), (q[6], q[7])])
    pic[~m] = 0
    if verge is not None:
        for r in range(h):
            cs = np.nonzero(m[r])[0]
            if not len(cs): continue
            top = cs.max()
            pic[r, top, :3] = verge[0][:3]
            pic[r, max(0, top - 2):top, :3] = verge[1][:3]
    return pic

def estate_one_story(shop, rng):
    art = solid(np.array(load('estate_one_story_house')))
    at = Atlas(512)
    G = 445                                                          # the ground row
    AX, X0, X1 = 240, 16, 464                                        # the main gable's apex and eaves (art columns)
    P = 26                                                           # the wall's boards repeat every 26 columns
    def rake(x): return 192 + abs(x - AX) // 2                        # the main gable's front rake (row) in column x
    roof_c = art[150, 120]; wall_c = art[300, 300]; rake_c = art[rake(300) + 1, 300]; edge_c = art[rake(300) + 4, 300]
    W = 480
    # --- the front: the main gable's face down to the ground, the garage and the porch as drawn, but what the art draws
    # from above in it (the garage's and the porch's roofs, the porch's floor) and what stands in front of it (the porch
    # posts) painted out: the engine builds them
    src = art.copy()
    gm = polygon(art.shape, [(131, 250), (2, 314), (2, 345), (131, 281), (258, 345), (258, 316)])   # the garage roof
    pm = np.zeros(art.shape[:2], bool); pm[318:360, 258:446] = True                                 # the porch roof, its beam
    # (and the shading the art draws on the wall along those roofs' top edges, a few rows deep, and the garage ridge's
    # cap where it meets the wall)
    paint = gm | pm
    for s in range(1, 7): paint |= np.roll(gm, -s, 0)
    paint[240:268, 126:150] = True
    # the wall behind those: the art's own boards, from where it draws the wall clear. The art shades the wall darker
    # for some 36 rows under the eave and is plain below: in the shade each painted pixel is the board of the same place
    # in the 26-column run, as far below the rake, from the nearest such column; in the plain wall it is the same column
    # (or the nearest of the same place) whole board lengths higher, so the boards' streaks run on and no shading of
    # another part of the wall comes with them
    clear = np.zeros(art.shape[:2], bool)
    for x in range(30, 446):
        top = 250 + (131 - x) * 64 // 129 if x < 131 else 250 + (x - 131) * 66 // 127 if x <= 258 else 318
        clear[rake(x):min(318, top - 7), x] = True
    clear &= ~paint; clear[210:282, 220:264] = False                 # (not the window in the gable)
    SH = 36
    def plain(y, x):
        for k in sorted(range(-12, 13), key=abs):
            xs = x + k * P
            if not 30 <= xs < 446: continue
            for ys in range(y - 52, rake(xs) + SH - 1, -52):
                if ys < 318 and clear[ys, xs]: return art[ys, xs]
        for k in sorted(range(-12, 13), key=abs):
            xs = x + k * P
            if 30 <= xs < 446:
                rows = np.nonzero(clear[rake(xs) + SH:, xs])[0]
                if len(rows): return art[rake(xs) + SH + rows[np.abs(rows + rake(xs) + SH - y).argmin()], xs]
        return art[300, 268 + (x - 268) % P + P * 3]
    for (y, x) in zip(*np.nonzero(paint)):
        dy = y - rake(x)
        if dy < 0: continue
        if dy >= SH: src[y, x] = plain(y, x); continue
        for k in sorted(range(-12, 13), key=abs):
            xs = x + k * P
            if k and 0 <= xs < W and clear[rake(xs) + dy, xs]: src[y, x] = art[rake(xs) + dy, xs]; break
        else: src[y, x] = plain(y, x)
    # the porch posts: the narrow framed panel left of the first (328..343, its number plate taken off) behind each
    panel = art[360:438, 328:344].copy(); panel[18:28] = panel[32]
    src[360:438, 344:360] = panel; src[360:438, 424:440] = panel; src[360:438, 440:442] = panel[:, 14:16]
    # the porch floor is drawn from above (rows 414..437), the wall's foot at its back edge: as an elevation the wall
    # under the porch roof (its door, window, plate) stands on the floor's front line, where the posts stand, so it
    # moves down 25 rows, and the boards above it carry on down under the porch roof, in its shade
    src[389:438, 248:446] = src[364:413, 248:446].copy()
    src[417:438, 446:458] = src[416:417, 446:458]                     # (the corner's post down to the floor)
    # (the wall it uncovers, in the porch roof's shade: under the beam's own shadow edge, rows 360..363, the boards at
    # the tint the art gives the wall there, a little darker up under the edge, and fading into the art's own flat shade
    # where the moved wall begins, so no line runs along the top of the door)
    tint = art[366:374, 254:270, :3].reshape(-1, 3).mean(0) / art[300:316, 254 + 3 * P:270 + 3 * P, :3].reshape(-1, 3).mean(0)
    flat = [x for x in range(248, 446) if not 270 <= x < 320 and not 326 <= x < 346 and not 358 <= x < 424
            and art[366, x, :3].astype(int).sum() >= 380 and (np.ptp(art[364:376, x, :3].astype(int).sum(-1)) < 30)]
    for y in range(364, 389):
        for x in range(248, 446):
            c = np.asarray(plain(y, x)[:3], float) * tint * min(1, .9 + .1 * (y - 364) / 8)
            if x in flat and y > 376: c += (art[364, x, :3] - c) * (y - 376) / 13
            src[y, x, :3] = np.clip(c, 0, 255).astype(np.uint8)
    src[438:G + 1, 248:458, :3] = art[442, 300, :3]
    face = src[192:G + 1, 0:W].copy(); fh = face.shape[0]
    for x in range(W):
        face[:max(0, rake(x) - 192), x] = 0
    # (left of the main gable's eave the garage roof stands against the sky: nothing there)
    mask = gm[192:G + 1, 0:W]
    for x in list(range(0, X0 + 2)) + list(range(X1 - 1, W)): face[:345 - 192, x][mask[:345 - 192, x]] = 0
    face[:, 458:] = 0; face[:, :18] = 0                               # (past the walls: the roof's slopes and rakes build it)
    face_panes, face_glow = hand_panes(face, [(232, 42, 18, 26, 1), (284, 197, 26, 20, 1), (378, 199, 38, 36, 1)])
    face_r = at.add(face, face_glow)
    # --- the main gable's slopes from above (each up to its front rake), as the villas'
    keep = np.zeros(art.shape[:2], bool)
    for x in range(X0, X1 + 1): keep[:rake(x), x] = art[:rake(x), x, 3] > 0
    left = at.add(cut(art[0:rake(X0) + 1, X0:AX], keep[0:rake(X0) + 1, X0:AX]))
    right = at.add(cut(art[0:rake(X1) + 1, AX:X1 + 1], keep[0:rake(X1) + 1, AX:X1 + 1]))
    lw, rw = AX - X0, X1 + 1 - AX
    # --- the garage: its wall and its own gable's face as drawn, and its two slopes from above
    gw = art[260:G + 1, 0:262].copy()
    def grake(x): return 281 + abs(x - 131) * 64 // 127               # its front rake
    for x in range(262): gw[:max(0, grake(x) - 260 + 2), x] = 0
    gw[:, :14] = 0; gw[346 - 260:, 248:] = 0                            # (below its eave, right of it: the main wall)
    g_panes, g_glow = hand_panes(gw, [(124, 50, 16, 16, 0)])
    g_panes.append([64, 112, 138, 6, 1])                            # the garage doors' lights: they mirror, they never glow
    garage_r = at.add(gw, g_glow)
    gl = at.add(cut(art[250:346, 2:131], gm[250:346, 2:131])); gr = at.add(cut(art[250:346, 131:259], gm[250:346, 131:259]))
    # --- the porch: its roof's boards from above, its beam, its two posts, its floor (the posts taken off its boards)
    # its roof is a lean-to from the beam up to the wall: the art's boards from above every other row (one screen row each)
    pent = art[322:352:2, 258:446].copy(); pent[..., 3] = 255
    prun = 37; prise = rise_for(pent.shape[0], prun)
    pent_r = at.add(pent); pend = pent_end(art[350, 300], prun, prise)
    pq = [0, prun - 1, 0, 0, pend.shape[1] - 1, 0, pend.shape[1] - 1, 1]
    pend = end_cut(pend, pq); pend_r = at.add(pend)
    beam = at.add(art[352:360, 258:446])
    post1 = at.add(art[360:438, 344:359]); post2 = at.add(art[360:438, 424:440])
    pmask = np.zeros((28, 210), bool); pmask[:, 96:112] = pmask[:, 176:194] = True
    floor = fill_rows(art[418:446, 248:458], pmask)
    floor_top = at.add(floor[:18]); floor_front = at.add(floor[18:])
    # --- the side walls: the front's boards along the depth, its windows (the gable's, at the porch window's height)
    corner = shade(wall_c, .78) + (255,)
    lit = art[318, 268:268 + P]
    foot = [shade(wall_c, .7)] * 2 + [tuple(art[442, 300, :3])] * 3
    win = art[222:275, 222:262]
    ms, ms_glow = board_side(lit, 192, G - 304, rng, corner, [(win, .3, 50, (10, 12, 18, 26, 1)), (win, .72, 50, (10, 12, 18, 26, 1))], foot)
    gs, _ = board_side(lit, 30, 100, rng, corner, (), foot)
    ms_r = at.add(ms, ms_glow); gs_r = at.add(gs)
    post_c = art[400, 350]; beam_c = art[355, 300]; porch_c = art[340, 300]; floor_c = art[426, 300]; ffront_c = art[440, 300]
    parts = [
        # the house: its eave walls up to the main gable's eaves (the face rises over them to the apex)
        mbox((18, 458), (0, G - 304), (0, 192), (wall_c, wall_c, wall_c), ft=face_r, fx=0, st=ms_r, panes=face_panes, top=False),
        mgable((X0, X1), (G - 304, G - 192), (0, 192), left, (0, rake(X0), lw, 192, lw, 0, 0, 112), right, (rw - 1, rake(X1), 0, 192, 0, 0, rw - 1, 112),
               (rake_c, edge_c), roof_c, lip=art[rake(300) - 1, 300]),
        # the garage, a little forward, and its gable (its slopes run back into the main roof)
        mbox((14, 248), (0, G - 345), (-24, 0), (wall_c, wall_c, wall_c), ft=garage_r, fx=0, st=gs_r, panes=g_panes, top=False),
        mgable((2, 260), (G - 345, G - 281), (-24, 8), gl, (0, 93, 129, 31, 129, 0, 0, 64), gr, (127, 93, 0, 31, 0, 0, 127, 64),
               (rake_c, edge_c), roof_c, de=0, lip=art[grake(60) - 1, 60]),
        # the porch: its floor, its roof on two posts
        mbox((248, 458), (0, G - 437), (-37, 0), (floor_c, ffront_c, shade(ffront_c, .85)), ft=floor_front, tt=floor_top),
        mbox((344, 359), (G - 437, G - 359), (-31, -25), (post_c, post_c, shade(post_c, .8)), ft=post1),
        mbox((424, 440), (G - 437, G - 359), (-31, -25), (post_c, post_c, shade(post_c, .8)), ft=post2),
        mbox((258, 446), (G - 359, G - 351), (-37, -31), (porch_c, beam_c, shade(beam_c, .85)), ft=beam, top=False),
        mhip((258, 446), (G - 351, G - 351 + prise), (-37, 0), (258, 446, -1, 0), pent_r, pend_r, pq, pend_r, pq, porch_c),
    ]
    setback = int(shop.get('yard', {}).get('front', 2)) * 32 + 40
    style = shop.get('yard', {}).get('style', 'compact')
    lawn, fence, fence_snow = yard(rng, W, setback + 192, True, (282, 314, setback - 37), style, [(276, 320)])
    # the whole front for what does not build it (the corner turn, the wet road's mirror): the face, the garage over it,
    # the porch's roof, beam, posts and floor as the street sees them from straight ahead
    front = face.copy(); emis = face_glow.copy()
    def over(pic, x, y, glow=None):
        h, w = pic.shape[:2]; m = pic[..., 3] > 0
        front[y:y + h, x:x + w][m] = pic[m]
        if glow is not None: emis[y:y + h, x:x + w][m] = glow[m]
        elif m.any(): emis[y:y + h, x:x + w][m] = 0
    over(gw, 0, 260 - 192, g_glow); over(art[352:360, 258:446], 258, 352 - 192)
    over(art[360:438, 344:359], 344, 360 - 192); over(art[360:438, 424:440], 424, 360 - 192); over(floor[18:], 248, 436 - 192)
    side = ms
    model = dict(kind='estate', w=W, frontX=(224 - W) / 2, setback=setback, lawn='grass', style=style, parts=parts,
                 fence=dict(d=3, foot=28 if style != 'formal' else 32) if fence is not None else None,
                 roofTint=rgb_int(roof_c), lamp=[325, 38, 0], lights='home', smoke=[], fpx=0)
    return model, at, front, side, emis, lawn, fence, fence_snow

def estate_country(shop, rng):
    """The country house: a front-gabled main house of two storeys, a balcony round its front and right side on the
    porch's roof, the porch under it (a deck with its railing round the front and side, a step down at the left), a
    chimney up its right side, and a lower wing on the left set back, its pitched roof's ridge along the street, a deck
    and steps before its door. The art draws the balcony's and the wing deck's floors and the roofs from above inside
    the front; as an elevation the upper storey stands on the balcony's floor at its front edge (it moves down 50 rows,
    the floor's depth as drawn), and the rest is built."""
    art = solid(np.array(load('estate_country_house')))
    at = Atlas(512)
    G = 505                                                          # the ground row (the porch deck's foot)
    AX, X0, X1 = 340, 172, 510                                       # the main gable's apex and eaves
    def rake(x): return int(176 + .8 * abs(x - AX))                   # its rake board's top in column x
    UP = 455                                                         # the upper storey's rows: height = UP - row
    W = 556
    roof_c = art[100, 250]; rake_c = art[rake(250) + 10, 250]; edge_c = art[rake(250) + 2, 250]; lip_c = art[rake(250) - 2, 250]
    wall_c = art[330, 330]; band_c = art[272, 330]
    # --- the upper storey's front: the gable as drawn down to the balcony's floor (row 355), outside the walls only the
    # rake board (under the eaves the wing and the balcony show in the art)
    up = art[176:356, X0:X1 + 1].copy()
    for x in range(X0, X1 + 1):
        c = x - X0
        up[:max(0, rake(x) - 176), c] = 0
        if x < 194 or x > 492: up[max(0, rake(x) - 176 + 22):, c] = 0
    up[330 - 176:, 196 - X0:210 - X0] = up[328 - 176:329 - 176, 196 - X0:210 - X0]   # (the balcony's side rail, from above)
    up_panes, up_glow = hand_panes(up, [(73, 114, 56, 32, 1), (180, 117, 56, 37, 1), (154, 32, 30, 34, 1)])
    up_r = at.add(up, up_glow)
    # --- the main roof's slopes from above, each the parallelogram between its back edge and its rake: the dormer on
    # the right one, the notch it leaves in the slope's back edge and the left one's ragged back edge filled with the
    # tiles of the same column one or more courses (14 rows) further down the slope
    def slope(x0, x1, back):
        pic = art[0:max(rake(x0), rake(x1)) + 1, x0:x1].copy()
        h, w = pic.shape[:2]
        band = np.zeros((h, w), bool)
        for c in range(w): band[int(back(c)):rake(x0 + c) + 1, c] = True
        bad = band & (pic[..., 3] == 0)
        if x0 == AX: bad[95:212, 398 - AX:464 - AX] = band[95:212, 398 - AX:464 - AX]
        for (y, c) in zip(*np.nonzero(bad)):
            sy = y + 14
            while sy < h and (bad[sy, c] or not band[sy, c]): sy += 14
            if sy >= h:
                sy = y - 14
                while sy >= 0 and (bad[sy, c] or not band[sy, c]): sy -= 14
            if sy >= 0: pic[y, c] = pic[sy, c]
        for c in range(w): band[rake(x0 + c):, c] = False              # (up to the rake, as drawn on the face)
        pic[..., 3] = 255
        return cut(pic, band)
    left = at.add(slope(X0, AX, lambda c: (AX - X0 - c) * 135 / (AX - X0)))
    right = at.add(slope(AX, X1 + 1, lambda c: c * 135 / (X1 - AX)))
    lw, rw = AX - X0, X1 + 1 - AX
    # --- the ground floor's wall (rows 418..468: cream over a plank skirt; the porch railing in front of the rest), its
    # skirt carried down to the deck
    gfw = art[418:499, 194:494].copy()
    skirt = art[445:468, 194:494]
    gfw[50:] = np.concatenate([skirt] * 3)[:31]
    gf_r = at.add(gfw)
    # --- the balcony: its fascia, its floor from above (the downpipe's foot painted out), its railing
    fascia = art[405:418, 186:536]
    fl = art[352:382, 205:530].copy(); m = np.zeros(fl.shape[:2], bool); m[:12, 228:268] = True
    fl = fill_rows(fl, m); fl = np.concatenate([fl[:, :1].repeat(19, 1), fl, fl[:, -1:].repeat(6, 1)], axis=1)
    fascia_r = at.add(fascia); bfloor_r = at.add(fl); fside_r = at.add(tile_x(fascia[:, 100:200], 250))
    brail = art[382:405, 192:536]
    brail_r = at.add(brail); bside_r = at.add(tile_x(art[382:405, 300:400], 250))
    # the balcony runs on back down the right side, the whole house deep: its floor as the art draws it from above beside
    # the roof (rows 186..352, the depth from the back), a short rail across its back end
    sfl = art[186:352, 494:530].copy(); sfl[..., 3] = 255; sfloor_r = at.add(sfl)
    bback_r = at.add(tile_x(art[382:405, 300:400], 44))
    # --- the porch: its railing (rows 465..499), its posts, its deck (the wing deck's planks, from above)
    prail = art[465:499, 195:536]
    prail_r = at.add(prail); pside_r = at.add(tile_x(art[465:499, 300:400], 250))
    lpost = at.add(art[418:499, 207:221]); rpost = at.add(art[418:499, 515:531])
    # (the side porch's back end: the art draws its planked end wall in the gap between the house's corner and the post,
    # x 494..512, which a straight view sees down the side porch)
    pend_pic = np.resize(art[418:465, 496:512], (81, 16, 4)); pend_pic = tile_x(pend_pic, 44); pend_pic[..., 3] = 255
    pback_r = at.add(pend_pic)
    planks = art[345:385, 64:176]
    pdeck_r = at.add(tile_x(planks, 356)[:40]); pfront_r = at.add(art[499:505, 180:536])
    # --- the wing: its wall (rows 290..345), its roof's near slope (every other row: one screen row each), its gable
    # end in its wall's courses, its deck's planks from above and its steps
    ww = art[290:345, 28:196].copy()                                 # (its left corner board at 28)
    # (its right end runs on under the main roof's eave, which the art draws over it, the roof's blue-greys: the window's
    # right frame its left one mirrored, past it the wall's own colour, row by row, from the plain wall between the plate
    # and the window)
    for x in range(164, 196):
        for y in range(290, min(345, rake(x) + 24)):
            r, g, b = (int(v) for v in art[y, x, :3])
            if b < r and not (x >= 172 and y < rake(x) + 20): continue
            ww[y - 290, x - 28] = art[y, 136 + 172 - x] if x < 172 else art[y, 130]
    w_panes, w_glow = hand_panes(ww, [(40, 2, 28, 22, 1), (110, 0, 34, 34, 1)])
    ww_r = at.add(ww, w_glow)
    # the art draws the wing a little turned: its slope's back edge and its eave both fall to the right. Each column's
    # slope, from its back edge to its eave, taken at 51 rows (about every other row: one screen row each), so the eave
    # runs level along the street
    ys = np.arange(51)
    cols = []
    for x in range(12, 164):
        t, e = 188 - (x - 12) * .125, 302 - (x - 12) * .165
        cols.append(art[np.round(t + ys * (e - t) / 51).astype(int), x])
    wface = np.stack(cols, axis=1)
    # the roof runs on to the main house's wall under its eave (the art's main roof hides that end, and its own edge is
    # drawn there): its last 32 columns again
    wface = np.concatenate([wface, wface[:, -32:]], axis=1)
    wface[..., 3] = 255
    wrun = 63; wrise = rise_for(wface.shape[0], wrun)
    wface_r = at.add(wface)
    wrows = column_rows(ww, list(range(74, 104)))[::-1]
    wend = gable_end(np.resize(wrows, (int(wrise),) + wrows.shape[1:]), 2 * wrun)
    wq = [0, 2 * wrun - 1, 0, 0, wend.shape[1] - 1, wrun - 1, wend.shape[1] - 1, wrun + 1]
    wend = end_cut(wend, wq, (edge_c, rake_c))                      # (the main gable's rake board)
    wend_r = at.add(wend)
    wdeck_r = at.add(art[345:385, 48:180]); wdfront_r = at.add(np.concatenate([art[350:368, 48:180]]))
    # --- the side walls: each storey's own courses, a window of the front's
    corner = shade(art[330, 200], .85) + (255,)
    us, us_glow = side_wall(column_rows(up[134:179], list(range(150, 175))), 200, art[205:245, 320:362], 2, (.3, .72), rng, corner)
    gs, _ = side_wall(column_rows(gfw, list(range(140, 160))), 200, None, 0, (), rng, corner)
    ws, _ = side_wall(column_rows(ww, list(range(74, 104))), 110, None, 0, (), rng, corner)
    us_r = at.add(us, us_glow); gs_r = at.add(gs); ws_r = at.add(ws)
    rail_top = art[384, 300]; rail_c = art[395, 300]; post_c = art[450, 212]; deck_c = art[365, 120]; deck_f = art[502, 300]
    tread = art[392, 100]; riser = art[400, 100]
    E = UP - rake(X0)                                                # the main eaves' height
    # --- the dormer on the right slope: a box with a level roof standing out of the slope, its front facing the street.
    # The art draws its front (rows 157..206, x 400..462: a fascia over a wall) cut by the slope in front of it along a
    # diagonal, its roof from above (rows 103..156), its right cheek not at all (the wall's courses)
    def hs(x): return (UP - 176) - (x - AX) * (UP - 176 - E) / (X1 - AX)      # the right slope's height in column x
    DX0, DX1, DD0, DD1 = 400, 462, 75, 165
    dh0, dh1 = int(round(hs(DX1))), int(round(hs(DX0)))
    dfront = art[157:157 + dh1 - dh0, DX0:DX1].copy()
    for c in range(DX1 - DX0):
        dfront[int((c + .5) * (UP - 176 - E) / (X1 - AX)) + 2:, c] = 0     # (below the slope's line in front of it)
    dfront_r = at.add(dfront)
    dtop = art[103:157, DX0 - 2:DX1 + 2].copy(); dtop[..., 3] = 255; dtop_r = at.add(dtop)
    dfascia_r = at.add(art[157:161, DX0 - 2:DX1 + 2])
    dcheek = np.repeat(np.asarray(column_rows(up[110:170], list(range(140, 170))))[:dh1 - dh0, None, :], DD1 - DD0, 1).copy()
    dcheek[..., 3] = 255; dcheek[:4] = art[157:161, 430][:, None]; dcheek_r = at.add(dcheek)   # (the fascia along its top)
    WE = 73                                                          # the wing's eaves
    parts = [
        # the wing, set back, under its pitched roof; its deck and steps
        # (on its plinth: the wing's floor is the deck's, its walls stand on a foundation down to the ground)
        mbox((28, 196), (0, 18), (60, 170), (wall_c, shade(wall_c, .6), shade(wall_c, .5)), top=False),
        mbox((28, 196), (18, WE), (60, 170), (wall_c, wall_c, wall_c), ft=ww_r, st=ws_r, panes=w_panes, top=False),
        mhip((12, 196), (WE, WE + wrise), (52, 52 + 2 * wrun), (12, 196, 52 + wrun - 1, 52 + wrun + 1), wface_r, wend_r,
             wq, wend_r, wq, roof_c),
        mbox((48, 180), (0, 18), (20, 60), (deck_c, deck_c, shade(deck_c, .85)), ft=wdfront_r, tt=wdeck_r),
        mbox((60, 156), (12, 18), (14, 20), (tread, riser, shade(riser, .85)), outline=True),
        mbox((60, 156), (6, 12), (8, 20), (tread, riser, shade(riser, .85)), outline=True),
        mbox((60, 156), (0, 6), (2, 20), (tread, riser, shade(riser, .85)), outline=True),
        # the main house: the ground floor, the floor between, the upper storey wearing the gable; its roof
        mbox((196, 492), (6, 87), (0, 200), (wall_c, wall_c, wall_c), ft=gf_r, st=gs_r, top=False),
        mbox((196, 492), (87, 100), (0, 200), (art[410, 300], art[410, 300], shade(art[410, 300], .85)), top=False),
        mgable((X0, X1), (E, UP - 176), (0, 200), left, (0, rake(X0), lw, 176, lw, 0, 0, 135), right, (rw - 1, rake(X1), 0, 176, 0, 0, rw - 1, 135),
               (rake_c, edge_c), roof_c, lip=lip_c),
        mbox((196, 492), (100, E), (0, 200), (wall_c, wall_c, wall_c), ft=up_r, fx=X0, st=us_r, panes=up_panes, top=False),
        # the dormer: its cheek, its front (the cheek's inner face and what the slope hides fall behind these two), its roof
        # (its back wall, rising out of the slope behind it: closed, not open into the roof)
        mbox((DX0, DX1), (dh0, dh1), (DD1, DD1), (wall_c, shade(wall_c, .85), shade(wall_c, .85)), card=True, top=False),
        mbox((DX1 - 6, DX1), (dh0, dh1), (DD0, DD1), (wall_c, wall_c, shade(wall_c, .85)), st=dcheek_r, top=False),
        mbox((DX0, DX1), (dh0, dh1), (DD0, DD0), (wall_c, wall_c, wall_c), ft=dfront_r, card=True, top=False),
        mbox((DX0 - 2, DX1 + 2), (dh1, dh1 + 4), (DD0 - 2, DD1), (roof_c, art[158, 430], shade(art[158, 430], .85)), ft=dfascia_r, tt=dtop_r),
        # the porch: its deck round the front and the right side, a step down at the left, its posts and railings
        mbox((180, 536), (0, 6), (-50, 0), (deck_c, deck_f, shade(deck_f, .85)), ft=pfront_r, tt=pdeck_r),
        mbox((492, 536), (0, 6), (0, 200), (deck_c, deck_f, shade(deck_f, .85)), tt=pdeck_r),
        mbox((164, 182), (0, 3), (-40, -14), (deck_c, deck_f, shade(deck_f, .85)), outline=True),
        mbox((207, 221), (6, 87), (-50, -44), (post_c, post_c, shade(post_c, .8)), ft=lpost),
        mbox((515, 531), (6, 87), (-50, -44), (post_c, post_c, shade(post_c, .8)), ft=rpost),
        mbox((515, 531), (6, 87), (94, 100), (post_c, post_c, shade(post_c, .8)), ft=rpost),
        mbox((492, 536), (6, 87), (198, 200), (post_c, post_c, shade(post_c, .8)), ft=pback_r, top=False),
        mbox((515, 531), (6, 87), (194, 200), (post_c, post_c, shade(post_c, .8)), ft=rpost),
        mbox((530, 536), (6, 40), (-50, 200), (rail_top, rail_c, rail_c), st=pside_r),
        # (the left side open where the step comes up)
        mbox((182, 188), (6, 40), (-50, -42), (rail_top, rail_c, rail_c), st=pside_r),
        mbox((182, 188), (6, 40), (-12, 0), (rail_top, rail_c, rail_c), st=pside_r),
        mbox((195, 536), (6, 40), (-50, -50), (rail_top, rail_c, rail_c), ft=prail_r, card=True),
        # the balcony on the porch's roof, round the front and the right side, its railings
        mbox((186, 536), (87, 100), (-50, 0), (deck_c, art[410, 300], shade(art[410, 300], .85)), ft=fascia_r, tt=bfloor_r),
        mbox((492, 536), (87, 100), (0, 200), (deck_c, art[410, 300], shade(art[410, 300], .85)), tt=sfloor_r, st=fside_r),
        # (the back rail first: drawn in turn, the side rail stands in front of its end)
        mbox((492, 536), (100, 123), (196, 196), (rail_top, rail_c, rail_c), ft=bback_r, card=True),
        mbox((530, 536), (100, 123), (-50, 200), (rail_top, rail_c, rail_c), st=bside_r),
        mbox((190, 196), (100, 123), (-50, 0), (rail_top, rail_c, rail_c), st=bside_r),
        mbox((192, 536), (100, 123), (-50, -50), (rail_top, rail_c, rail_c), ft=brail_r, card=True),
    ]
    setback = int(shop.get('yard', {}).get('front', 2)) * 32 + 56
    style = shop.get('yard', {}).get('style', 'compact')
    lawn, fence, fence_snow = yard(rng, W, setback + 200, True, (92, 124, setback + 2), style, [(86, 130)])
    # the whole front for what does not build it (the corner turn, the wet road's mirror), rows from the ridge down
    H = UP - 176 + 1
    front = np.zeros((H, W, 4), np.uint8); emis = np.zeros_like(front)
    def over(pic, x, y_top, glow=None):
        r0 = H - y_top; h, w = pic.shape[:2]; m = pic[..., 3] > 0
        front[r0:r0 + h, x:x + w][m] = pic[m]
        emis[r0:r0 + h, x:x + w][m] = glow[m] if glow is not None else 0
    over(wface, 12, int(WE + wrise)); over(ww, 28, WE, w_glow); over(gfw, 194, 87); over(up, X0, UP - 176, up_glow)
    over(fascia, 186, 100); over(brail, 192, 123); over(prail, 195, 40); over(art[499:505, 180:536], 180, 6)
    model = dict(kind='estate', w=W, frontX=(224 - W) / 2, setback=setback, lawn='grass', style=style, parts=parts,
                 fence=dict(d=3, foot=28 if style != 'formal' else 32) if fence is not None else None,
                 roofTint=rgb_int(roof_c), lamp=[253, 140, 0], lights='home', smoke=[], fpx=0)
    return model, at, front, us, emis, lawn, fence, fence_snow
