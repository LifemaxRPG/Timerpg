"""The apartment blocks (condo_3_44, condo_3_45, condo_5_1..3, condo_9) and the hotel (landmark_hotel_1) as modelled
buildings: the parts the engine builds each from (models.json) and the pictures they wear, as make_buildings.py's
build_model makes the villas and landmarks.

The art draws each as its front elevation under a flat roof seen from above (the parapet's coping round a gravel or tiled
floor, the back parapet's inner face, sometimes a ledge's top between storeys or a plinth's). In the street's nearly
level view those tops would stand as tall bands of roof on the wall, so the front is made a true elevation: the rows the
art draws from above are left out (a ledge's top, the roof), and what they show is built: the roof as a floor inside a
parapet of four boxes (coping on top, the front's own cornice and the back's inner face as drawn), each ledge, sill,
lintel, plinth, door frame or porch as a shallow box standing out of the wall wearing its own front, steps as boxes, an
antenna as a card and a mast as a thin box, the hotel's sign as a card on its roof. The art is 2x pixel art seen from above at
one row of top per pixel of depth, so its tops give the depths."""
import numpy as np
import make_buildings as mb
from make_buildings import (Atlas, GLOW, column_rows, cut, dilate, fill_rows, glass, hand_panes, load, mbox, panes_of,
                            rgb_int, shade, solid, yard)


class Elev:
    """A front as a true elevation: the art's rows `keep` (ranges, top to bottom, the last ending on the ground row),
    what the art draws from above between them left out. Heights are pixels above the ground."""
    def __init__(self, art, keep):
        self.rows = [r for (a, b) in keep for r in range(a, b)]
        self.at = {r: i for i, r in enumerate(self.rows)}
        self.h = len(self.rows)
        self.pic = art[self.rows]

    def y(self, r):
        """The height of the top edge of the art's row `r`."""
        return self.h - self.at[r]

    def span(self, r0, r1):
        """The foot and the top of the art's rows r0..r1-1."""
        return self.h - self.at[r1 - 1] - 1, self.h - self.at[r0]

    def crop(self, r0, r1, x0, x1):
        return np.ascontiguousarray(self.pic[self.at[r0]:self.at[r1 - 1] + 1, x0:x1])


def median_c(px):
    px = px.reshape(-1, px.shape[-1]); px = px[px[:, 3] > 200] if px.shape[-1] == 4 else px
    return tuple(int(v) for v in np.median(px, axis=0)[:3]) if len(px) else (90, 90, 100)


def relief(at, el, x0, x1, r0, r1, d0, d1=0, top=None, top_c=None, side_c=None, alpha=False):
    """A shallow box standing out of the wall (a ledge, a sill, a lintel, a jamb, a plinth): the art's columns x0..x1 of
    its rows r0..r1 as its front, `top` (the art's rows of its top from above, as many as it is deep) as its top."""
    pic = el.crop(r0, r1, x0, x1)
    if not alpha: pic = pic.copy(); pic[..., 3] = np.where(pic[..., 3] > 0, 255, 0)
    front_c = median_c(pic)
    tc = top_c if top_c is not None else median_c(top) if top is not None else front_c
    sc = side_c if side_c is not None else shade(front_c, .8)
    return mbox((x0, x1), el.span(r0, r1), (d0, d1), (tc, front_c, sc), ft=at.add(pic), fx=x0,
                tt=at.add(np.ascontiguousarray(top)) if top is not None else None)


def flat_roof(at, art, x0, x1, y0, y1, d1, t, front_face, back_face, top_rows, cols=None):
    """A flat roof inside its parapet: the floor (the art's own from above, `floor`) is the body's top, and the parapet
    four boxes `t` thick from y0 (the floor) up to y1 round x0..x1 by 0..d1: its front wearing `front_face` (the
    front's own top rows), its back the back's inner face as the art draws it (`back_face`), its coping on top (the
    art's rows from above: `top_rows` gives the back, front and side copings' pictures). Returns the parts, drawn back
    to front."""
    back_top, front_top, side_top = top_rows
    coping = median_c(front_top); face = median_c(front_face)
    side = np.repeat(np.repeat(np.array(face + (255,), np.uint8)[None, None], y1 - y0, 0), d1, 1)
    # the parapet's outer faces carry the front's cornice round the corner: its rows' colours along the depth
    rows = column_rows(front_face, cols if cols is not None else list(range(front_face.shape[1] // 3, 2 * front_face.shape[1] // 3)))
    side[:] = rows[:, None, :]; side[..., 3] = 255
    side_r = at.add(side)
    parts = [
        mbox((x0, x1), (y0, y1), (d1 - t, d1), (coping, median_c(back_face), shade(face, .8)), ft=at.add(back_face), tt=at.add(back_top), st=side_r),
        mbox((x0, x0 + t), (y0, y1), (t, d1 - t), (coping, face, shade(face, .8)), tt=at.add(side_top[0]), st=side_r),
        mbox((x1 - t, x1), (y0, y1), (t, d1 - t), (coping, face, shade(face, .8)), tt=at.add(side_top[1]), st=side_r),
    ]
    front = mbox((x0, x1), (y0, y1), (0, t), (coping, face, shade(face, .8)), ft=at.add(front_face), fx=x0, tt=at.add(front_top), st=side_r)
    return parts, front


def glass_panes(pic, least=330):
    """A picture's window panes (x, y, w, h, 1) and where they glow: its glass's boxes (the clean blue panes, as the shops'
    are found), each glowing over its lighter pixels (the glass, a curtain), not its frame's wood or ink."""
    boxes = []
    for (x0, y0, x1, y1) in mb.components(dilate(glass(pic), 1), 30):
        boxes.append((int(x0), int(y0), int(x1 - x0 + 1), int(y1 - y0 + 1), 1))
    return hand_panes(pic, boxes, least)


def side_windows(rows, depth, wins, rng, corner):
    """A side wall `depth` deep: each row the front's own colour for that row (`rows`), the front's windows (each its
    picture, frame and all, its top row, the fractions of the depth its middle stands at, and its glass boxes in the
    picture: x, y, w, h) laid along it, darker corner boards and a fine grain. Returns it and where its windows glow."""
    return side_windows_lit(rows, depth, [(p, y, f, [tuple(q[:4]) + (330,) for q in pn]) for (p, y, f, pn) in wins], rng, corner)


def side_windows_lit(rows, depth, wins, rng, corner):
    """side_windows with panes placed by hand (x, y, w, h, least: see lit_panes)."""
    h = len(rows)
    side = np.repeat(rows[:, None, :], depth, axis=1).copy(); side[..., 3] = 255
    glow = np.zeros_like(side)
    for (pic, wy, at_, panes) in wins:
        wh, ww = pic.shape[:2]; m = pic[..., 3] > 200
        _, g = lit_panes(pic, panes)
        gm = g[..., 3] > 0
        for f in at_:
            cx = int(depth * f) - ww // 2
            side[wy:wy + wh, cx:cx + ww][m] = pic[m]
            glow[wy:wy + wh, cx:cx + ww][gm] = g[gm]
    for c in (0, 1, depth - 2, depth - 1): side[:, c, :3] = corner[:3]
    grain = rng.integers(-4, 5, (h // 2 + 1, depth // 2 + 1))
    grain = np.kron(grain, np.ones((2, 2), np.int32))[:h, :depth]
    side[..., :3] = np.clip(side[..., :3].astype(int) + grain[..., None], 0, 255).astype(np.uint8)
    return side, glow


def lot(shop, rng, W, D, setback_extra, path=None, ground='paving'):
    """The paved forecourt before the block (the game's front yard, as deep), and its fence or hedge if the game has one."""
    setback = int(shop.get('yard', {}).get('front', 1)) * 32 + setback_extra
    style = shop.get('yard', {}).get('style', 'natural')
    p = path or (0, 0, 0)
    lawn, fence, fence_snow = yard(rng, W, setback + D, ground == 'grass', p, style, [(p[0] - 6, p[1] + 6)] if path else [], ground=ground)
    return setback, style, lawn, fence, fence_snow


def model_of(kind, W, setback, style, parts, roof_c, lights, lawn_kind, fence, lamp=None, fpx=0):
    return dict(kind=kind, w=W, frontX=(224 - W) / 2, setback=setback, lawn=lawn_kind, style=style, parts=parts,
                fence=dict(d=3, foot=28 if style != 'formal' else 32) if fence is not None else None,
                roofTint=rgb_int(roof_c), lamp=lamp, lights=lights, smoke=[], fpx=fpx)


# ------------------------------------------------------------------------------------------------ condo_3_44, condo_3_45
# Two small brick blocks under a flat roof with a tan parapet: condo_3_44 one storey (its door, a window either side
# with a lintel over it and a flower box under it, a plinth along its foot either side of the door), condo_3_45 the
# same with a storey of two windows over it on a ledge. Their places in the art's own pixels; rows are the art's.
CONDO3 = {
    'condo_3_44': dict(G=250, roof=(28, 38, 48, 50, 142, 144, 152), face=152, keep=[(152, 250)], ledges=[], windows=[
        # lintel (x0, x1, r0, r1), sill, glass boxes in the front's own pixels
        ((16, 48, 176, 186), (14, 48, 216, 226)), ((112, 176, 178, 190), (112, 176, 216, 226))],
        door=(58, 102, 186, 194, 248), plinth=[(0, 60), (100, 192)], side=[0]),
    'condo_3_45': dict(G=314, roof=(8, 18, 28, 30, 122, 124, 132), face=132, keep=[(132, 210), (216, 314)],
        ledges=[(0, 192, 216, 232, 210)], windows=[
        ((16, 48, 240, 250), (14, 48, 280, 290)), ((112, 176, 242, 254), (112, 176, 280, 290)),
        ((24, 86, 152, 164), (24, 86, 192, 204)), ((106, 168, 152, 164), (106, 168, 192, 204))],
        door=(58, 102, 250, 258, 312), plinth=[(0, 60), (100, 192)], side=[0, 2], antenna=(100, 180, 80)),
}


def condo3(shop, rng):
    key = shop['parts'][0]['key']; cfg = CONDO3[key]
    raw = np.array(load(key)); art = solid(raw)
    W = 192; at = Atlas(512)
    G = cfg['G']
    bk0, bf0, bf1, fl0, fl1, fc0, fc1 = cfg['roof']       # back coping, its inner face, the floor, the front coping
    el = Elev(art, cfg['keep'])
    H = el.h
    P = bf1 - bf0                                          # the parapet's height over the floor (its inner face as drawn)
    D = fc1 - bk0                                          # the roof's depth, one art row of top per pixel
    T = bf0 - bk0                                          # the coping's width, front to back
    Y = H - P                                              # the floor's height
    wall_c = art[G - 30, 52]; roof_c = art[(fl0 + fl1) // 2, 100]
    # --- a TV antenna standing on the roof (condo_3_45): the art draws it over the floor and the back parapet, its mast
    # upright; what differs there from condo_3_44's roof (the same roof without it) is the antenna. It is taken off the
    # roof (condo_3_44's pixels put back) and stands on the floor as a card, its foot where the art's mast ends
    aerial = None
    if cfg.get('antenna'):
        ax0, ax1, ar1 = cfg['antenna']
        other = solid(np.array(load('condo_3_44')))
        off = 28 - bk0
        m = np.zeros(art.shape[:2], bool)
        m[bk0:ar1, ax0:ax1] = (art[bk0:ar1, ax0:ax1] != other[bk0 + off:ar1 + off, ax0:ax1]).any(-1)
        m[:bk0, ax0:ax1] = art[:bk0, ax0:ax1, 3] > 0
        ys, xs = np.nonzero(m)
        foot = int(ys.max())
        pic = cut(art[:foot + 1, ax0:ax1], m[:foot + 1, ax0:ax1])
        art = art.copy(); art[bk0:fc1][m[bk0:fc1]] = other[bk0 + off:fc1 + off][m[bk0:fc1]]
        aerial = (ax0, ax1, pic, fc1 - foot)
    # --- the roof: the floor from above with the back parapet's inner face (drawn on it in the art) replaced by the
    # floor's own rows just in front of it; the parapet's front its top rows of the front, its back that inner face
    floor = art[bk0:fc1, 2:190].copy()
    n = fl0 - bf0
    floor[bf0 - bk0:fl0 - bk0] = art[fl0 + 2:fl0 + 2 + n, 2:190]
    parts, front_par = flat_roof(at, art, 0, W, Y, H, D, T, el.crop(cfg['face'], cfg['face'] + P, 0, W), art[bf0:bf1, 0:W].copy(),
                                 (art[bk0:bf0, 0:W].copy(), art[fc0 - 2:fc1, 0:W].copy(),
                                  (art[bf0:fc0 - 2, 0:T].copy(), art[bf0:fc0 - 2, W - T:W].copy())))
    # --- the body: the elevation under the parapet (the cornice's dentils overhang the wall by its outline), the
    # windows' glass (picked by hand: the brick shares the glass's blue-greys in places)
    body = el.crop(cfg['face'] + P, G, 0, W)
    b_panes, b_glow = glass_panes(body)
    body_r = at.add(body, b_glow)
    # the side walls: each row the front's wall colour clear of its windows and door, a window of the front's on each
    # storey (the lower's with its flower box)
    plain = [c for c in list(range(6, 12)) + list(range(50, 56)) + list(range(104, 110))]
    rows = column_rows(body, plain)
    wins = []
    for i in cfg['side']:
        (lx0, lx1, lr0, lr1), (sx0, sx1, sr0, sr1) = cfg['windows'][i]
        x0, x1 = min(lx0, sx0), max(lx1, sx1)
        pic = el.crop(lr0, sr1, x0, x1)
        wins.append((pic, el.at[lr0] - P, (.3, .72), glass_panes(pic)[0]))
    corner = shade(wall_c, .8) + (255,)
    sidew, side_g = side_windows(rows, D, wins, rng, corner)
    side_r = at.add(sidew, side_g)
    tt = at.add(floor)
    parts.insert(0, mbox((2, W - 2), (0, Y), (0, D), (roof_c, wall_c, shade(wall_c, .8)), ft=body_r, fx=0, tt=tt, st=side_r, panes=b_panes))
    # --- what stands out of the wall: the ledge between the storeys (its top the art's rows from above), the lintels
    # and sills, the door's frame, the plinth either side of the door
    for (x0, x1, r0, r1, t0) in cfg['ledges']:
        parts.append(relief(at, el, x0, x1, r0, r1, -(r0 - t0), top=art[t0:r0, x0:x1].copy()))
    for ((lx0, lx1, lr0, lr1), (sx0, sx1, sr0, sr1)) in cfg['windows']:
        parts.append(relief(at, el, lx0, lx1, lr0, lr1, -3))
        parts.append(relief(at, el, sx0, sx1, sr0, sr1, -3))
    dx0, dx1, dl0, dl1, dfoot = cfg['door']
    parts += [relief(at, el, dx0, dx1, dl0, dl1, -3), relief(at, el, dx0, dx0 + 6, dl1, G, -3), relief(at, el, dx1 - 6, dx1, dl1, G, -3)]
    for (x0, x1) in cfg['plinth']:
        parts.append(relief(at, el, x0, x1, G - 16, G, -4, top=art[G - 18:G - 16, x0:x1].copy()))
    if aerial is not None:
        ax0, ax1, pic, d = aerial
        c = median_c(pic)
        parts.insert(4, mbox((ax0, ax1), (Y, Y + pic.shape[0]), (d, d), (c, c, c), ft=at.add(pic), fx=ax0, card=True))
    parts.append(front_par)
    setback, style, lawn, fence, fence_snow = lot(shop, rng, W, D, 8, (dx0 + 6, dx1 - 6, 8), 'paving')
    model = model_of('apartment', W, setback, style, parts, roof_c, 'home', 'paving', fence, lamp=[(dx0 + dx1) / 2, el.y(dl0) + 4, -3])
    front = el.pic.copy(); emis = np.zeros_like(front); emis[P:] = b_glow
    return model, at, front, sidew, emis, lawn, fence, fence_snow


def lit_panes(img, boxes):
    """Window panes placed by hand (x, y, w, h in `img`'s pixels, and how light a pixel must be to glow: the glass and a
    curtain, not the frame, the bars or the wall, whose shades differ from pane to pane) and where they glow."""
    out = []; glow = np.zeros_like(img)
    for (x, y, w, h, least) in boxes:
        b, g = hand_panes(img, [(x, y, w, h, 1)], least)
        out += b; m = g[..., 3] > 0; glow[m] = g[m]
    return out, glow


# ------------------------------------------------------------------------------------------------ condo_5_1..3
# A three-storey block in three colourways (the same pixels in each place): a flat roof in a cream coping, two storeys
# each in a cream frame standing out of the brick (a post either side, a ledge under it whose top the art draws from
# above), the ground storey on a plinth, its door deep in a portal with steps up into it and one more out on the
# pavement. Its walls share the glass's blue-greys, so its panes are placed by hand.
C5_KEEP = [(104, 172), (176, 236), (240, 338)]


def condo5(shop, rng):
    key = shop['parts'][0]['key']
    art = solid(np.array(load(key)))
    W = 192; at = Atlas(512)
    G = 338
    bk0, bf0, bf1, fl0, fc1 = 18, 24, 30, 32, 104                   # back coping, its inner face, the floor, the front coping's end
    T = bf0 - bk0; P = bf1 - bf0; D = fc1 - bk0
    el = Elev(art, C5_KEEP)
    H = el.h; Y = H - P
    wall_c = art[300, 180]; roof_c = art[60, 96]; frame_c = tuple(int(v) for v in art[150, 7, :3])
    # --- the roof: its floor (the back's inner face on it in the art replaced by the floor's own rows), the parapet
    floor = art[bk0:fc1, 2:190].copy()
    floor[bf0 - bk0:fl0 - bk0] = art[fl0 + 4:fl0 + 4 + fl0 - bf0, 2:190]
    parts, _ = flat_roof(at, art, 0, W, Y, H, D, T, el.crop(104, 104 + P, 0, W), art[bf0:bf1, 0:W].copy(),
                         (art[bk0:bf0, 0:W].copy(), art[fc1 - T:fc1, 0:W].copy(),
                          (art[bk0 + T:fc1 - T, 0:T].copy(), art[bk0 + T:fc1 - T, W - T:W].copy())))
    # --- the body: the elevation under the parapet; its panes in the art's rows, made the body's
    body = el.crop(104 + P, G, 0, W)
    o = el.at[104 + P]
    def b(x, r, w, h, least): return (x, el.at[r] - o, w, h, least)
    boxes = []
    for dr in (0, 64):
        boxes += [b(33, 132 + dr, 30, 20, 540), b(73, 136 + dr, 46, 14, 540), b(129, 132 + dr, 30, 20, 540)]
    boxes += [b(26, 254, 140, 12, 540), b(72, 278, 46, 26, 380)]
    b_panes, b_glow = lit_panes(body, boxes)
    body_r = at.add(body, b_glow)
    # the side walls: the storeys' brick (beside their windows, inside the frame) and the ledges' bands, the ground
    # storey's brick beside its barred window; a window of the front's on each storey
    up_rows = column_rows(body, list(range(18, 28)))
    gr_rows = column_rows(body, list(range(178, 184)))
    g0 = el.at[248] - o
    rows = np.concatenate([up_rows[:g0], gr_rows[g0:]])
    win = el.crop(130, 156, 30, 66)
    wins = [(win, el.at[130] - o, (.3, .72), [(3, 2, 30, 20)]), (win, el.at[194] - o, (.3, .72), [(3, 2, 30, 20)])]
    wins = [(p_, y_, f_, [q + (540,) for q in pn]) for (p_, y_, f_, pn) in wins]
    side, side_g = side_windows_lit(rows, D, wins, rng, shade(wall_c, .8) + (255,))
    parts.insert(0, mbox((2, W - 2), (0, Y), (0, D), (roof_c, wall_c, shade(wall_c, .8)), ft=body_r, fx=0, tt=at.add(floor),
                         st=at.add(side, side_g), panes=b_panes))
    # --- the frames standing out: the band under the roof (with the parapet's front in it), the ledges (their tops
    # the art's rows from above), the posts either side of each storey
    parts.append(relief(at, el, 0, W, 176, 184, -4, top=art[172:176, 0:W].copy()))
    parts.append(relief(at, el, 0, W, 240, 248, -4, top=art[236:240, 0:W].copy()))
    for (r0, r1) in ((120, 172), (184, 236)):
        parts.append(relief(at, el, 0, 12, r0, r1, -4, top_c=frame_c))
        parts.append(relief(at, el, W - 12, W, r0, r1, -4, top_c=frame_c))
    # --- the ground storey: the plinth either side of the portal, the portal (its lintel and jambs), the step out
    parts.append(relief(at, el, 2, 60, 322, G, -4, top=art[322:326, 2:60].copy()))
    parts.append(relief(at, el, 128, 190, 322, G, -4, top=art[322:326, 128:190].copy()))
    parts += [relief(at, el, 60, 72, 280, 330, -6), relief(at, el, 116, 128, 280, 330, -6), relief(at, el, 60, 128, 268, 280, -6)]
    tread, riser = art[344, 96], art[348, 96]
    parts.append(mbox((64, 128), (0, 6), (-14, 0), (tread, riser, shade(riser, .85)), outline=True))
    parts.append(relief(at, el, 0, W, 104, 120, -4, T, top=art[fc1 - T:fc1, 0:W].copy()))
    setback, style, lawn, fence, fence_snow = lot(shop, rng, W, D, 16, (70, 122, 16), 'paving')
    model = model_of('apartment', W, setback, style, parts, roof_c, 'home', 'paving', fence, lamp=[94, el.y(268) + 3, -6])
    front = el.pic.copy(); emis = np.zeros_like(front); emis[P:] = b_glow
    return model, at, front, side, emis, lawn, fence, fence_snow


# ------------------------------------------------------------------------------------------------ condo_9
# A narrow block drawn obliquely: its front (columns 18..133) with a porthole and a tall window on a small balcony, its
# right side wall beside it (columns 134..191, two copper downpipes) drawn with its foot 32 rows higher, as a wall
# running back; one flat roof over both from above, a mast standing up at its front left corner. Rebuilt as one box:
# the front strip its front, the side strip its side (the rows its foot hides put back in its brickwork, the plain
# wall between the pipes carried back along the depth), the roof as the others' (the art's roof narrowed to the front's
# width: the stretch over the side strip taken out), the mast and the balcony as boxes.
C9_X0, C9_X1, C9_SIDE = 18, 134, 192


def c9_narrow(a):
    """The art's roof (columns 18..191) as wide as the front: its middle stretch over the side strip taken out."""
    return np.ascontiguousarray(np.concatenate([a[:, 18:118], a[:, 176:192]], axis=1))


def condo9(shop, rng):
    art = solid(np.array(load('condo_9')))
    W = 192; at = Atlas(512)
    G = 384
    bk0, bf0, bf1, fl0, fc1 = 38, 54, 70, 74, 180
    T = bf0 - bk0; P = bf1 - bf0; D = fc1 - bk0
    BW = C9_X1 - C9_X0
    # --- the mast: the art's pole (columns 24..31, its cap at the top), standing on the left parapet; painted off the
    # roof's pictures (each row's coping carried across it)
    mast_pic = art[2:153, 24:32].copy()
    mast_pic[110 - 2:146 - 2] = art[40:76, 24:32]                     # (its foot drawn in the parapet's shade: the engine shades it)
    mast = np.zeros(art.shape[:2], bool); mast[:153, 24:32] = art[:153, 24:32, 3] > 0
    roof = art.copy()
    for y in range(153):
        src = next((x for x in (22, 21, 33, 34) if not mast[y, x]), None)
        if src is not None: roof[y, 24:32][mast[y, 24:32]] = art[y, src]
    el = Elev(art, [(180, G)])
    H = el.h; Y = H - P
    face = el.crop(180, 180 + P, C9_X0, C9_X1).copy()
    face[:, -2:, :3] = art[190, 18, :3]                              # the parapet's corner outline, as at its left end
    wall_c = art[260, 60]; roof_c = art[120, 100]; ink = tuple(int(v) for v in art[190, 18, :3])
    floor = c9_narrow(roof[bk0:fc1])
    floor[bf0 - bk0:fl0 - bk0] = c9_narrow(roof[fl0 + 4:fl0 + 4 + fl0 - bf0])
    parts, front_par = flat_roof(at, art, 0, BW, Y, H, D, T, face, c9_narrow(roof[bf0:bf1]),
                                 (c9_narrow(roof[bk0:bf0]), c9_narrow(roof[fc1 - T:fc1]),
                                  (np.ascontiguousarray(roof[bk0 + T:fc1 - T, 18:18 + T]), np.ascontiguousarray(roof[bk0 + T:fc1 - T, 192 - T:192]))))
    # (the parapets' places are the box's own, 0..BW: shift them to the front's columns)
    for q in parts + [front_par]:
        q['x'] = [q['x'][0] + C9_X0, q['x'][1] + C9_X0]
        if 'fx' in q: q['fx'] += C9_X0
    # --- the body: the front strip under the parapet
    body = el.crop(180 + P, G, C9_X0, C9_X1)
    b_panes, b_glow = glass_panes(body)
    body_r = at.add(body, b_glow)
    # --- the side: the side strip, its foot put back 32 rows (its brick courses, 304..335, once more above its plinth),
    # the parapet's face over it; along the depth its corner and both pipes, then its plain wall, then its back corner
    strip = art[198:352, C9_X1:C9_SIDE]
    strip = np.concatenate([strip[:336 - 198], art[304:336, C9_X1:C9_SIDE], strip[336 - 198:]])
    top = np.repeat(column_rows(art[196:198], list(range(40, 110)))[:, None], C9_SIDE - C9_X1, axis=1)
    strip = np.concatenate([top, strip])                             # (the two rows of the parapet's outline over it)
    plain = strip[:, 172 - C9_X1:186 - C9_X1]
    n = D - (172 - C9_X1) - 6
    side = np.concatenate([strip[:, :172 - C9_X1], np.tile(plain, (1, n // plain.shape[1] + 1, 1))[:, :n], strip[:, -6:]], axis=1)
    side = side.copy(); side[..., 3] = 255
    side[:, -2:, :3] = ink
    grain = np.kron(rng.integers(-3, 4, (side.shape[0] // 2 + 1, D // 2 + 1)), np.ones((2, 2), np.int32))[:side.shape[0], :D]
    side[..., :3] = np.clip(side[..., :3].astype(int) + grain[..., None], 0, 255).astype(np.uint8)
    assert side.shape[0] == Y, (side.shape, Y)
    parts.insert(0, mbox((C9_X0, C9_X1), (0, Y), (0, D), (roof_c, wall_c, shade(wall_c, .8)), ft=body_r, fx=C9_X0,
                         tt=at.add(floor), st=at.add(side), panes=b_panes))
    # --- the mast on the left parapet, as far back as the art stands it (its foot 28 rows behind the roof's front edge)
    mc = median_c(mast_pic)
    parts.insert(1, mbox((24, 32), (H, H + mast_pic.shape[0]), (24, 32), (mc, mc, shade(mc, .8)), ft=at.add(mast_pic), st=at.add(mast_pic)))
    # --- the balcony under the tall window: its slab (rows 286..295), its railing (rows 270..285) open between the
    # balusters (the art's light grey there is the wall behind), its two end rails
    rail = el.crop(270, 286, 70, 116).copy()
    lum = rail[..., :3].astype(int).sum(-1)
    rail[6:, :][lum[6:] > 480] = 0
    slab_c = tuple(int(v) for v in art[287, 90, :3])
    parts.append(relief(at, el, 70, 116, 286, 296, -8, top_c=slab_c))
    rc = tuple(int(v) for v in art[273, 90, :3])
    y0 = el.span(286, 296)[1]; y1 = el.span(270, 286)[1]
    parts.append(mbox((70, 73), (y0, y1), (-8, 0), (rc, ink, ink), outline=True))
    parts.append(mbox((113, 116), (y0, y1), (-8, 0), (rc, ink, ink), outline=True))
    parts.append(mbox((70, 116), (y0, y1), (-8, -8), (rc, rc, rc), ft=at.add(rail, None), fx=70, card=True, panes=None))
    parts.append(front_par)
    setback, style, lawn, fence, fence_snow = lot(shop, rng, W, D, 8, None, 'paving')
    model = model_of('apartment', W, setback, style, parts, roof_c, 'home', 'paving', fence)
    front = np.zeros((H, W, 4), np.uint8); front[:, C9_X0:C9_X1] = el.crop(180, G, C9_X0, C9_X1)
    emis = np.zeros_like(front); emis[P:, C9_X0:C9_X1] = b_glow
    return model, at, front, side, emis, lawn, fence, fence_snow


# ------------------------------------------------------------------------------------------------ the hotel
# landmark_hotel_1: a tall front of four green-capped brick pilasters (columns 0..63, 160..223, 320..383, 480..543)
# standing 4 pixels out of the two side bays between them (their feet 4 rows higher in the art), and the middle bay
# (224..319) set back 32 (its foot 30 higher): each wall's front as drawn, from its roof's front edge down. Above, the
# art draws the flat roof from above: beige over the bays, the pilasters' green copper running back the whole depth, and
# the HOTEL sign standing on it, its shadow below it. Rebuilt as boxes: the pilasters a little taller than the bays (their
# copper tops standing 6 proud of the roof, the front's green band carried up), the bays and the middle bay wearing
# their roofs from above, the sign taken off the roof as a card on the two middle pilasters' copper, set back as far as
# the art stands it. The building's sides are the outer pilasters' sides: the bays' brick with their arched windows.
H_PIL = ((0, 64), (160, 224), (320, 384), (480, 544))
H_BAY = ((64, 160), (384, 480))
H_MID = (224, 320)
H_D = 200


def hotel(shop, rng):
    art = solid(np.array(load('landmark_hotel_1')))
    W = 544; at = Atlas(1100)
    # --- the sign: what is not the roof's own colours (sampled clear of it), with the ink of its outlines and its
    # letters' depth between the topmost and lowest of those in each column (not the roof's colours there: the gaps
    # between the letters, inside the O)
    sx0, sy0, sx1, sy1 = 120, 28, 420, 142
    ref = set(map(tuple, np.concatenate([art[152:196].reshape(-1, 4), art[0:28].reshape(-1, 4)]).tolist()))
    box = art[sy0:sy1, sx0:sx1]
    own = np.array([[tuple(p) not in ref for p in row] for row in box.tolist()])
    ink = (box[..., :3].astype(int).sum(-1) < 280) & (box[..., 2].astype(int) > box[..., 0].astype(int) + 10)
    sign = own.copy()
    for x in range(box.shape[1]):
        ys = np.nonzero(own[:, x])[0]
        if len(ys): sign[ys.min():ys.max() + 1, x] |= ink[ys.min():ys.max() + 1, x]
    ys, xs = np.nonzero(sign)
    sign_pic = cut(box, sign)[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    sgx0, sgx1 = sx0 + int(xs.min()), sx0 + int(xs.max()) + 1
    sign_foot = sy0 + int(ys.max())
    # the roof under it (and its shadow) painted over with the roof's own rows below it, column by column
    roof = art.copy()
    clean = roof[152:196]
    for r in range(sy0, 152):
        roof[r, sx0:sx1] = clean[(r - sy0) % len(clean), sx0:sx1]
    # --- the walls: each front from its roof's front edge down to its foot
    PG, BG, MG = 734, 730, 704                                         # the pilasters', the bays' and the middle bay's ground rows
    PT, BT, MT = 216, 204, 196                                         # where their fronts start (their roofs' front edges)
    lift = 6                                                           # the pilasters' copper stands this far above the bays' roofs
    pil_h = (BG - BT) + lift
    def pil_front(x0, x1):
        f = art[PT:PG, x0:x1]
        n = pil_h - f.shape[0]                                         # (the green band at its top carried up to the copper)
        return np.ascontiguousarray(np.concatenate([np.repeat(f[0:2], n // 2 + 1, 0)[:n], f]))
    mid = art[MT:MG, H_MID[0]:H_MID[1]].copy()
    mid[697 - MT:, 240 - H_MID[0]:304 - H_MID[0]] = art[700, 216]     # the doorstep under its door (the art's stone course)
    mid[697 - MT, 240 - H_MID[0]:304 - H_MID[0], :3] = shade(art[700, 216], .7)
    stone = tuple(int(v) for v in art[400, 30, :3]); brick = tuple(int(v) for v in art[400, 100, :3])
    green = tuple(int(v) for v in art[100, 30, :3]); beige = tuple(int(v) for v in art[100, 100, :3])
    # the pilasters' inner sides (in the middle bay's recess, and a sliver beside each bay): their own brick and bands
    pside_rows = column_rows(pil_front(0, 64), list(range(6, 18)) + list(range(46, 58)))
    pside = np.repeat(pside_rows[:, None], H_D - 32, axis=1).copy(); pside[..., 3] = 255
    pside_r = at.add(pside)
    # the building's sides: the bays' brick under their cornice, an arched window of the front's on each storey
    bay = art[BT:BG, 64:160]
    rows = column_rows(bay, list(range(4, 14)) + list(range(84, 92)))
    win = art[244:370, 80:144]
    w_p = glass_panes(win)[0]
    wins = [(win, (244 - BT) + k * 126, (.3, .72), w_p) for k in range(3)]
    side, side_g = side_windows(rows, H_D, wins, rng, shade(stone, .8) + (255,))
    BH = BG - BT
    # the whole front (the corner turn, the wet road, and the walls' pictures): each wall at its foot
    front = np.zeros((pil_h, W, 4), np.uint8)
    for (x0, x1) in H_PIL: front[:, x0:x1] = pil_front(x0, x1)
    for (x0, x1) in H_BAY: front[lift:, x0:x1] = art[BT:BG, x0:x1]
    front[pil_h - mid.shape[0]:, H_MID[0]:H_MID[1]] = mid
    walls = front[lift:].copy(); walls[:, H_MID[0]:H_MID[1]] = 0
    w_panes, w_glow = glass_panes(walls)
    m_panes, m_glow = glass_panes(mid)
    emis = np.zeros_like(front); emis[lift:] = w_glow; emis[pil_h - mid.shape[0]:, H_MID[0]:H_MID[1]] = m_glow
    # Drawn in this order, so that whichever side the view sees, no face is painted over a wall standing in front of
    # it (the engine draws the parts in their order): the blocks behind the front line of the middle bay, the middle
    # bay, the two walls in front of them either side of it (their fronts the bays' with the pilasters' between), the
    # pilasters standing 4 out of those, the pilasters' copper running back along the roof, the sign
    F = 32
    parts = []
    for (x0, x1) in ((0, H_MID[0]), (H_MID[1], W)):
        parts.append(mbox((x0, x1), (0, BH), (F, H_D), (beige, brick, shade(brick, .8)),
                          tt=at.add(np.ascontiguousarray(roof[8:BT - (F - 4), x0:x1])), st=at.add(np.ascontiguousarray(side[:, F:]), np.ascontiguousarray(side_g[:, F:]))))
    parts.append(mbox(H_MID, (0, MG - MT), (F, H_D), (beige, brick, shade(brick, .8)), ft=at.add(mid, m_glow), fx=H_MID[0],
                      tt=at.add(np.ascontiguousarray(roof[32:MT, H_MID[0]:H_MID[1]])), st=pside_r, panes=m_panes))
    for (x0, x1) in ((0, H_MID[0]), (H_MID[1], W)):
        pn = [q for q in w_panes if x0 <= q[0] < x1]
        pn = [[q[0] - x0] + list(q[1:]) for q in pn]
        parts.append(mbox((x0, x1), (0, BH), (4, F), (beige, brick, shade(brick, .8)), ft=at.add(np.ascontiguousarray(walls[:, x0:x1]), np.ascontiguousarray(w_glow[:, x0:x1])), fx=x0,
                          tt=at.add(np.ascontiguousarray(roof[BT - (F - 4):BT, x0:x1])), st=at.add(np.ascontiguousarray(side[:, :F])), panes=pn))
    for (x0, x1) in H_PIL:
        parts.append(mbox((x0, x1), (0, pil_h), (0, 4), (green, stone, shade(stone, .8)), ft=at.add(pil_front(x0, x1)), fx=x0,
                          tt=at.add(np.ascontiguousarray(roof[PT - 4:PT, x0:x1])), st=pside_r))
    for (x0, x1) in H_PIL:
        parts.append(mbox((x0, x1), (BH, pil_h), (4, H_D), (green, green, shade(green, .75)), tt=at.add(np.ascontiguousarray(roof[0:PT - 4, x0:x1]))))
    # --- the sign on the two middle pilasters' copper, its foot as far back as the art draws it behind the roof's edge
    sd = PT - sign_foot
    sc = median_c(sign_pic)
    parts.append(mbox((sgx0, sgx1), (pil_h, pil_h + sign_pic.shape[0]), (sd, sd), (sc, sc, sc), ft=at.add(sign_pic, sign_glow(sign_pic)), fx=sgx0, card=True))
    setback, style, lawn, fence, fence_snow = lot(shop, rng, W, H_D, 0, (248, 296, 40), 'paving')
    model = model_of('hotel', W, setback, style, parts, beige, 'lamp', 'paving', fence, lamp=[272, MG - 610 + 4, 32])
    return model, at, front, side, emis, lawn, fence, fence_snow


def sign_glow(pic):
    """Where the sign lights at night: its letters' faces (the light lavender and white) and its bulbs."""
    rgb = pic[..., :3].astype(int)
    m = (rgb.sum(-1) > 600) & (pic[..., 3] > 0)
    g = np.zeros_like(pic); g[m] = (255, 240, 210, 235)
    return g


def maker(key):
    """The builder for one of this module's sprites, or None."""
    if key in CONDO3: return condo3
    if key in ('condo_5_1', 'condo_5_2', 'condo_5_3'): return condo5
    if key == 'condo_9': return condo9
    if key == 'landmark_hotel_1': return hotel
    return None
