"""LimeZu's Modern Farm (32 px art) as 3D buildings and props through the usual pipeline (models.json + one atlas per
building, built by FilamentModels). Keys are `fm_*`; `builder(key)` is reached from make_buildings.build_model, the
showcase from `make_buildings.py --farm-showcase DIR`. The pack is not in the repo (licence): point FARM_ART at its 32x32
folder (the zip's `32x32`).

The lesson of the chapel applies: LimeZu draws 3/4 top-down, so each picture is cut into its parts: a wall's front is
a true elevation (used as drawn), the roof the art draws from above goes on the real roof, what stands out of a wall
(windows, lintels, beams) is a box standing out of it, and the sides and the back are built from the art's own boards."""
import os
import numpy as np
from PIL import Image
import make_buildings as mb
import buildings_apt as apt
import buildings_civic as civ
from make_buildings import Atlas, mbox, shade, solid, rgb_int, polygon, cut

FARM = os.environ.get('FARM_ART') or '/tmp/claude-0/-home-user-Timerpg/f2eefd9e-16c9-5542-9daa-dcab8571e817/scratchpad/farm/pack/32x32'
PROPS = FARM + '/Single_Files_32x32/Props_and_Buildings_32x32/%s_32x32.png'
GLOW = (255, 196, 110, 235)
WHITE, TRIM, INK = (216, 208, 224), (152, 158, 190), (58, 58, 80)


def fart(name, folder=None):
    """A picture of the pack's single files (Props_and_Buildings by default) without its half-transparent ground shadow."""
    p = (folder + '/%s_32x32.png' % name) if folder else PROPS % name
    if not os.path.exists(p): raise SystemExit(f'missing farm picture {p} (set FARM_ART)')
    return solid(np.array(Image.open(p).convert('RGBA')))


def med(a, box=None):
    r0, r1, c0, c1 = box or (0, a.shape[0], 0, a.shape[1])
    px = a[r0:r1, c0:c1].reshape(-1, 4); px = px[px[:, 3] == 255]
    return tuple(int(v) for v in np.median(px, axis=0)[:3])


def lit(pic, lo=(0, 0, 0), hi=(255, 255, 255)):
    """`pic` and where its glass (the farm's dark teal panes) glows at night."""
    rgb = pic[..., :3].astype(int); r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    m = (pic[..., 3] == 255) & (r < 80) & (g > r + 25) & (b > r + 25) & (g < 140)
    glow = np.zeros_like(pic); glow[m] = GLOW
    return pic, glow


def tile(pic, h, w, flip=False):
    """`pic` repeated to h rows by w columns (every other repeat mirrored across when `flip`: boards do not repeat so plainly)."""
    ry, rx = h // pic.shape[0] + 1, w // pic.shape[1] + 1
    row = [pic[:, ::-1] if (flip and i % 2) else pic for i in range(rx)]
    out = np.concatenate(row, 1)
    col = [out[::-1] if (flip and i % 2) else out for i in range(ry)]
    return np.ascontiguousarray(np.concatenate(col, 0)[:h, :w])


def paint(img, rect, src):
    """`img` with the (x0, y0, x1, y1) rectangle replaced by `src`'s same place (tiled to the image)."""
    x0, y0, x1, y1 = rect
    img[y0:y1, x0:x1] = src[y0:y1, x0:x1]
    return img


def lot_for(shop, rng, W, D, door):
    return civ.lot_for(shop or dict(yard=dict(front=1, style='natural')), rng, W, D, door)


def finish(model_kind, W, D, shop, rng, parts, roof_c, at, front, door=(0, 32), lamp=None):
    """The tuple make_buildings.build_model wants."""
    setback, style, lawn, fence, fence_snow = lot_for(shop, rng, W, D, (door[0], door[1], 16))
    model = apt.model_of(model_kind, W, setback, style, parts, roof_c, 'shop', 'grass', None, lamp=lamp)
    side = np.zeros((8, 8, 4), np.uint8); emis = np.zeros_like(front)
    return model, at, front, side, emis, lawn, None, None


# ------------------------------------------------------------------------------------------------ the big barns
# Each of the four (Front_Hayloft_<colour>, 480 x 224) is a front elevation under a roof the art draws from above: a
# taller central hayloft bay (7 tiles wide, its front gable's rake running from row 65 at x 112 to the apex at row 3, x
# 240) between two low aisles (8 x 128 and 352 x 480, eave at row 112), the roof behind in dark corrugated metal. Built
# as it reads: a nave with a gable roof over two lean-to aisles, the art's roof strip (Roof_Hayloft_Middle_Modular: the
# aisle, the slopes and the ridge from above) laid on each slope along the depth.
FOOT = 224
BARN_COLOURS = {'red': 'Red', 'grey': 'Grey', 'yellow': 'Yellow', 'teal': 'Green'}
AE, AH = 112, 140            # the aisles' eave and the height their roof reaches at the nave's wall
NE, NR = 158, 221            # the nave's walls and its ridge (the apex is on the art's row 3)
NX0, NX1 = 128, 352          # the nave's walls; its roof overhangs to 112 and 368
RD = 14                      # the doors' recess


def rows(r0, r1):
    return FOOT - r1, FOOT - r0     # art rows (r0 top, r1 bottom) to heights y0 < y1


class Boards:
    """The barn's own boards: the clean stretch of its front (x 130..186, rows 150..206) tiled, the fascia and the plinth
    cut from the front as well, so every wall is made of what the art's wall is made of."""
    def __init__(self, art):
        self.field = np.ascontiguousarray(art[158:204, 142:186])
        self.plinth = np.ascontiguousarray(art[206:224, 140:186])
        self.fascia = np.ascontiguousarray(art[112:126, 20:76])
        self.edge = np.ascontiguousarray(art[126:150, 130:186])    # the boards just under the fascia

    def wall(self, h, w, fascia=True, plinth=True, flip=True):
        top = self.fascia.shape[0] if fascia else 0
        bot = self.plinth.shape[0] if plinth else 0
        out = np.zeros((h, w, 4), np.uint8); out[..., 3] = 255
        out[:, :, :] = tile(self.field, h, w, flip)
        if fascia: out[:top] = tile(self.fascia, top, w, flip)
        if plinth: out[h - bot:] = tile(self.plinth, bot, w, flip)
        return out


def barn(spec, shop, rng):
    key = spec['kit']; colour = BARN_COLOURS[spec['colour']]
    D = spec.get('depth', 384)
    art = fart('Front_Hayloft_' + colour)
    roof_art = fart('Roof_Hayloft_Middle_Modular')
    at = Atlas(1024)
    B = Boards(art)
    wall_c = med(B.field); trim_c = WHITE
    parts = []
    # ---------------------------------------------------------------- the front's pieces (art coordinates; rows from the top)
    nf = np.ascontiguousarray(art[3:FOOT, NX0 - 16:NX1 + 16])        # the nave's front incl. the gable (x 112..368)
    nx_off, nr_off = NX0 - 16, 3                                    # its top left in the art
    def at_art(c0, r0, c1, r1): return np.ascontiguousarray(art[r0:r1, c0:c1])
    wall_bg = tile(B.field, FOOT, 480, True)                        # the boards painted over what stands out
    # what stands out of the nave's front: two hayloft windows, the lintel beam, the hoist beam
    LOFT = [(150, 74, 212, 118), (270, 74, 332, 118)]
    BEAM = (164, 128, 316, 148)
    HOIST = (232, 8, 250, 54)
    DOOR = (188, 146, 292, FOOT)
    windows = [(30, 144, 66, 186), (86, 144, 122, 186), (358, 144, 394, 186), (414, 144, 450, 186)]
    face_art = np.ascontiguousarray(art[:, :]); face_art = face_art.copy()
    protr = []
    for (x0, y0, x1, y1) in LOFT + [BEAM] + windows:
        protr.append(((x0, y0, x1, y1), np.ascontiguousarray(art[y0:y1, x0:x1])))
        face_art = paint(face_art, (x0, y0, x1, y1), wall_bg)
    face_art[HOIST[1]:HOIST[3], HOIST[0]:HOIST[2]] = art[HOIST[1]:HOIST[3], HOIST[0] - 18:HOIST[2] - 18]
    # the gable's picture: the art's rake and what is under it, no roof from above
    gx0, gx1 = 112, 368
    gh = 66 - 3
    gf = np.ascontiguousarray(face_art[3:66, gx0:gx1]).copy()
    xs = np.arange(gx0, gx1) + .5
    rake = 3 + np.abs(xs - 240) / 128.0 * 62
    keep = (np.arange(3, 66)[:, None] >= rake[None, :] - 6)
    gf = cut(gf, keep)
    face_r = at.add(gf)
    # ---------------------------------------------------------------- the walls
    # the nave: the piers and the header carry the front, the doors stand back RD, the box behind is the nave
    ys = lambda r0, r1: (FOOT - r1, FOOT - r0)
    fr = lambda c0, r0, c1, r1: np.ascontiguousarray(face_art[r0:r1, c0:c1])
    jamb_st = at.add(np.ascontiguousarray(art[150:224, 190:196]))
    bst = at.add(B.wall(NE, 28, fascia=False, plinth=False))
    pier_h = FOOT - 66
    parts.append(mbox((NX0, 188), (0, pier_h), (0, RD), (wall_c, wall_c, shade(wall_c, .8)), ft=at.add(fr(NX0, 66, 188, FOOT)), fx=NX0, st=bst, top=False))
    parts.append(mbox((292, NX1), (0, pier_h), (0, RD), (wall_c, wall_c, shade(wall_c, .8)), ft=at.add(fr(292, 66, NX1, FOOT)), fx=292, st=bst, top=False))
    parts.append(mbox((188, 292), (FOOT - 146, pier_h), (0, RD), (wall_c, wall_c, shade(wall_c, .8)), ft=at.add(fr(188, 66, 292, 146)), fx=188, st=bst, top=False))
    # the door jambs (the art's white posts) and the doors at the back of the recess
    door_pic = np.ascontiguousarray(art[146:FOOT, 188:292])
    parts.append(mbox((188, 198), (0, FOOT - 146), (0, RD), (WHITE, WHITE, shade(WHITE, .8)), ft=at.add(door_pic[:, :10]), fx=188, st=jamb_st, top=False))
    parts.append(mbox((282, 292), (0, FOOT - 146), (0, RD), (WHITE, WHITE, shade(WHITE, .8)), ft=at.add(door_pic[:, -10:]), fx=282, st=jamb_st, top=False))
    leaves = np.ascontiguousarray(door_pic[:, 10:-10])
    nave = mbox((NX0, NX1), (0, NE + 1), (RD, D), (wall_c, wall_c, shade(wall_c, .8)), ft=at.add(leaves), fx=198, st=at.add(B.wall(NE + 1, 96, fascia=False, plinth=True)), top=False)
    parts.append(nave)
    # a filler under the overhanging eaves: the wall up to the roof where it meets the eave's slope
    parts.append(mbox((NX0, NX1), (NE, NE + 8), (1, D), (wall_c, wall_c, shade(wall_c, .8)), st=bst, top=False))
    # the aisles: each a box wearing the art's front under its eave (the fascia in it), its outer side boards and windows
    bay = B.wall(AE, 96, fascia=True, plinth=True)
    win = np.ascontiguousarray(art[144:186, 86:122])
    bw, bg = mb_lit(win)
    bay[32:32 + win.shape[0], 30:30 + win.shape[1]] = win
    bay_glow = np.zeros_like(bay); bay_glow[32:32 + win.shape[0], 30:30 + win.shape[1]] = bg
    bay_r = at.add(bay, bay_glow)
    for (x0, x1, c0, hs) in ((0, NX0, 0, 'r'), (NX1, 480, 352, 'l')):
        b = mbox((x0, x1), (0, AE), (0, D), (wall_c, wall_c, shade(wall_c, .8)), ft=at.add(fr(c0, 112, c0 + (NX0 - x0 if x0 == 0 else 480 - NX1), FOOT)), fx=x0, st=bay_r, top=False)
        b['hs'] = hs
        parts.append(b)
    # ---------------------------------------------------------------- the roofs
    def strip(c0, c1, rows_):
        return np.ascontiguousarray(tile(roof_art[:, c0:c1], rows_, c1 - c0))
    nd = D
    # the nave: the art's two slopes (the lit left one; the right is the art's right), eaves overhanging 16
    lf = strip(112, 240, nd); rf = strip(240, 368, nd); hw = 128
    lq = [(0, nd), (hw, nd), (hw, 0), (0, 0)]; rq = [(hw, nd), (0, nd), (0, 0), (hw, 0)]
    roof_c = med(roof_art)
    flat = lambda q: [v for p in q for v in p]
    gab = mb.mgable((112, 368), (NE, NR), (0, D), at.add(lf), flat(lq), at.add(rf), flat(rq), (WHITE, TRIM), roof_c, face=face_r, fx=gx0, fy=NE, lip=WHITE)
    parts.insert(0, gab)
    # the aisles' lean-to roofs: each rises to the nave's wall
    al = strip(0, 128, nd); ar = strip(352, 480, nd)
    aq_l = [(0, nd), (128, nd), (128, 0), (0, 0)]; aq_r = [(128, nd), (0, nd), (0, 0), (128, 0)]
    left = mb.mgable((0, NX0), (AE, AH), (0, D), at.add(al), flat(aq_l), None, (0,) * 8, (WHITE, TRIM), roof_c, lip=WHITE, ax=NX0)
    right = mb.mgable((NX1, 480), (AE, AH), (0, D), None, (0,) * 8, at.add(ar), flat(aq_r), (WHITE, TRIM), roof_c, lip=WHITE, ax=NX1)
    del right['l'], right['lp']
    parts += [left, right]
    # the ridge's cap
    ridge = np.ascontiguousarray(tile(roof_art[:, 232:248], D, 16))
    parts.append(mbox((236, 244), (NR - 2, NR + 3), (0, D), (INK, TRIM, TRIM), tt=at.add(ridge[:, 4:12])))
    # ---------------------------------------------------------------- standing out of the walls
    for ((x0, y0, x1, y1), pic) in protr:
        lo, hi = FOOT - y1, FOOT - y0
        p, g = mb_lit(pic)
        depth = 5 if (x1, y1) == (BEAM[2], BEAM[3]) else 4
        parts.append(mbox((x0, x1), (lo, hi), (-depth, 0), (TRIM, WHITE, shade(TRIM, .8)), ft=at.add(p, g), fx=x0, st=at.add(np.ascontiguousarray(p[:, :1].repeat(depth, 1)))))
    # the doors' threshold slab and a weathercock on the ridge
    slab = at.add(np.ascontiguousarray(art[206:216, 130:186]))
    parts.append(mbox((184, 296), (0, 3), (-14, 0), (TRIM, TRIM, shade(TRIM, .8)), ft=slab, st=slab, tt=at.add(np.ascontiguousarray(art[208:222, 140:170]))))
    vane = fart('Weathercock_Roof_Farmer_House')
    parts.append(mbox((224, 256), (NR + 2, NR + 2 + vane.shape[0]), (D * .12, D * .12), (TRIM, TRIM, TRIM), ft=at.add(vane), fx=224, card=True))
    # the hoist beam out of the gable's peak, with the pulley's block
    timber = med(art, (14, 48, 236, 244))
    parts.append(mbox((237, 243), (FOOT - 52, FOOT - 44), (-28, 3), (shade(timber, 1.1), timber, shade(timber, .7))))
    parts.append(mbox((238, 242), (FOOT - 60, FOOT - 52), (-26, -22), (shade(timber, .8), shade(timber, .9), shade(timber, .6))))
    # ---------------------------------------------------------------- the back: its own elevation
    bk, bkg = back_wall(art, B, door_pic, protr[0][1], D)
    parts.append(mbox((0, 480), (0, bk.shape[0]), (D + .5, D + .5), (wall_c, wall_c, wall_c), ft=at.add(bk, bkg), card=True))
    door = (NX0, NX1)
    front = np.ascontiguousarray(art)
    return finish('barn', 480, D, shop, rng, parts, roof_c, at, front, door)


def mb_lit(pic):
    return lit(pic)


def back_wall(art, B, door_pic, loft, D):
    """The barn's back elevation as seen from behind: boards up the aisles' lean-to lines and into the nave's gable, the
    plinth, white corner posts, the doors and one hayloft window."""
    H = NR; W = 480
    img = B.wall(H, W, fascia=False, plinth=True)
    glow = np.zeros_like(img)
    keep = polygon((H, W), [(0, H), (0, H - AE), (NX0, H - AH), (NX0, H - NE), (112, H - NE), (240, 0), (368, H - NE), (NX1, H - NE), (NX1, H - AH), (480, H - AE), (480, H)])
    # (a thin eave line along the aisles' slope: the roof's edge)
    img[~keep] = 0
    # the doors: the art's pair in its white jambs
    h = door_pic.shape[0]
    img[H - h:H, 188:292] = door_pic
    # a hayloft window high in the gable
    lp, lg = lit(loft)
    y0 = H - 118; x0 = 240 - lp.shape[1] // 2
    img[y0:y0 + lp.shape[0], x0:x0 + lp.shape[1]] = lp; glow[y0:y0 + lp.shape[0], x0:x0 + lp.shape[1]] = lg
    # white corner posts at the aisles' outer corners
    for (a, b) in ((0, 12), (468, 480)):
        img[H - AE:H, a:b] = tile(art[130:224, 0:12], AE, 12)
    return img, glow



def proud(at, art, rect, foot, depth, wall_bg=None, col=TRIM, side=None, glowing=True):
    """A thing that stands out of a wall (a window with its frame, a lintel, a door's frame): the art's crop `rect`
    (x0, y0, x1, y1) as a box `depth` out of the wall. The wall it stood in is painted over in `wall_bg` (when given)
    and returned with the box."""
    x0, y0, x1, y1 = rect
    crop = np.ascontiguousarray(art[y0:y1, x0:x1])
    if wall_bg is not None: art[y0:y1, x0:x1] = wall_bg[y0:y1, x0:x1]
    pic, glow = lit(crop) if glowing else (crop, np.zeros_like(crop))
    st = side if side is not None else at.add(np.ascontiguousarray(crop[:, :1].repeat(max(2, depth), 1)))
    return mbox((x0, x1), (foot - y1, foot - y0), (-depth, 0), (col, col, shade(col, .8)), ft=at.add(pic, glow), fx=x0, st=st)


def fill_of(art, box):
    """A field of wall to paint over what is cut out of it: the art's clean `box` (r0, r1, c0, c1) tiled (mirrored)."""
    r0, r1, c0, c1 = box
    return tile(np.ascontiguousarray(art[r0:r1, c0:c1]), art.shape[0], art.shape[1], True)


def under_rake(h, w, pts):
    """A keep mask for a picture h x w: inside the polygon of `pts` (x, row)."""
    return polygon((h, w), pts)


def side_wall(field, h, w, flip=True):
    return np.ascontiguousarray(tile(field, h, w, flip))


def slab(at, x, d, y0, y1, col, pic=None, top=None):
    """A plain stone or timber block (a step, a kerb)."""
    r = at.add(pic) if pic is not None else None
    return mbox(x, (y0, y1), d, (col, col, shade(col, .8)), ft=r, st=r, tt=at.add(top) if top is not None else None)



def siding(h, w, base, seam, post=None, period=12, post_w=8, speck=None, seed=0):
    """Plank siding (horizontal seams every `period` rows from the foot up), flecked with the art's own shades, with timber
    corner posts at both ends when `post` is given."""
    rng = np.random.default_rng(seed)
    img = np.zeros((h, w, 4), np.uint8); img[..., 3] = 255; img[..., :3] = base
    if speck:
        m = rng.random((h, w)) < .08
        img[m, :3] = np.array(speck)[rng.integers(0, len(speck), m.sum())]
    for y in range(h - 1, 0, -period): img[y - 1:y + 1, :, :3] = seam
    if post is not None:
        for (a, b) in ((0, post_w), (w - post_w, w)):
            img[:, a:b, :3] = post; img[:, a:a + 1, :3] = INK; img[:, b - 1:b, :3] = INK
    return img


def bricks(h, w, base, mortar, bw=10, bh=5, seed=0):
    rng = np.random.default_rng(seed)
    img = np.zeros((h, w, 4), np.uint8); img[..., 3] = 255; img[..., :3] = base
    for k, r1 in enumerate(range(h, 0, -bh)):
        r0 = max(0, r1 - bh); img[r0:r0 + 1, :, :3] = mortar
        off = (bw // 2) if k % 2 else 0
        for x in range(-off, w, bw):
            if 0 <= x < w: img[r0:r1, x, :3] = mortar
            c = np.clip(np.array(base) + rng.integers(-9, 10), 0, 255)
            img[r0 + 1:r1, max(0, x + 1):min(w, x + bw), :3] = c
    return img


def chimney(at, art, x, y, d, size=20, height=30):
    """A brick chimney with a stone cap (the art's rim from above) standing on a roof at height y."""
    chim = fart('Chimney_Roof_Farmer_House')
    red = med(chim, (40, 60, 8, 20)); dark = shade(red, .7)
    br = at.add(bricks(height, size, red, dark, seed=3))
    cap = at.add(np.ascontiguousarray(chim[2:34, 8:44]))
    return [mbox((x, x + size), (y, y + height), (d, d + size), (INK, red, shade(red, .8)), ft=br, st=br),
            mbox((x - 2, x + size + 2), (y + height, y + height + 4), (d - 2, d + size + 2), (TRIM, TRIM, shade(TRIM, .8)), tt=cap)]

# ------------------------------------------------------------------------------------------------ farmhouse 1
# Farmer_House_1 (256 x 320, foot on row 318): a front-gabled main house (walls x 112..240, a clay-tile roof drawn from above
# with a timber ridge board) beside a lower lean-to wing on the left (its rake the diagonal from (20, 256) up to (104, 207)).
# The roof from above is low pitch in the art; here the main gable rises 30 over a half-width of 72.
def house1(spec, shop, rng):
    art = fart('Farmer_House_1'); F = 318
    at = Atlas(1024)
    wall_f = fill_of(art, (248, 284, 206, 224))
    parts = []
    M0, M1, DM = 112, 240, 168
    wing0, wing1, DW = 20, 112, 132
    EM = F - 186                                   # the main walls' height (126)
    RISE = 30
    wall_c = med(wall_f)
    # --- what stands out of the walls: the upper window, the lower left window, the door's frame
    protr = []
    for rect, depth in (((150, 188, 202, 230), 5), ((44, 252, 106, 296), 5), ((148, 252, 200, 318), 6)):
        protr.append((rect, depth))
    outs = []
    for rect, depth in protr: outs.append(proud(at, art, rect, F, depth, wall_f))
    # --- main walls: the front the art's, from the eave down; sides in its boards, a window in each
    front = np.ascontiguousarray(art[186:F, M0:M1])
    post_c = med(art, (250, 300, 114, 124)); seam_c = shade(wall_c, .82); speck = [shade(wall_c, .94), shade(wall_c, 1.06)]
    side = siding(EM, 96, wall_c, seam_c, post_c, speck=speck, seed=1)
    winp = np.ascontiguousarray(art[190:226, 156:198]); wp, wg = lit(winp)
    sidew = side.copy(); sg = np.zeros_like(sidew); sidew[34:34 + winp.shape[0], 28:28 + winp.shape[1]] = wp; sg[34:34 + winp.shape[0], 28:28 + winp.shape[1]] = wg
    sidew[EM - 8:] = np.tile(art[F - 8:F, 118:130], (1, 9, 1))[:, :96]
    main = mbox((M0, M1), (0, EM), (0, DM), (wall_c, wall_c, shade(wall_c, .8)), ft=at.add(front), fx=M0, st=at.add(sidew, sg), top=False)
    parts.append(main)
    # the main gable roof: the art's two slopes of clay tiles (courses unrolled along the depth), a timber ridge board
    lf = tile(np.ascontiguousarray(art[44:170, 108:166]), DM + 4, 58); rf = tile(np.ascontiguousarray(art[44:170, 188:246]), DM + 4, 58)
    hw = lf.shape[1]; H_ = DM + 4
    lq = [(0, H_), (hw, H_), (hw, 0), (0, 0)]; rq = [(hw, H_), (0, H_), (0, 0), (hw, 0)]
    flat = lambda q: [v for p_ in q for v in p_]
    roof_c = med(lf)
    # (the eaves overhang 8 each side and 2 at the front: the art's slope rows start at its front edge)
    # the gable's face: the wall's boards up into the triangle, a round vent
    gh = RISE + 2
    gf = np.ascontiguousarray(tile(np.ascontiguousarray(art[200:214, 120:150]), gh, M1 - M0 + 16, True))
    keep = polygon((gh, M1 - M0 + 16), [(0, gh), ((M1 - M0 + 16) / 2, 0), (M1 - M0 + 16, gh)])
    gf = cut(gf, keep)
    gab = mb.mgable((M0 - 8, M1 + 8), (EM, EM + RISE), (0, DM), at.add(lf), flat(lq), at.add(rf), flat(rq), (WHITE, TRIM), roof_c, face=at.add(gf), fx=M0 - 8, fy=EM, lip=med(art, (180, 184, 112, 240)))
    parts.insert(0, gab)
    ridge = tile(np.ascontiguousarray(art[40:160, 171:183]), DM + 4, 12)
    parts.append(mbox((170, 182), (EM + RISE - 1, EM + RISE + 4), (-1, DM + 2), (INK, med(ridge), shade(med(ridge), .8)), tt=at.add(ridge)))
    # --- the left wing: a lean-to, its front the art's elevation under the rake, its roof a single slope of the art's tiles
    wh0 = F - 256                                      # eave
    wh1 = F - 207                                      # high edge, against the main wall
    wfront = np.ascontiguousarray(art[256:F, wing0:wing1])
    wface = np.ascontiguousarray(art[207:256, wing0:wing1]).copy()
    # (under the rake: from the eave at the left edge up to the wall's top at x 104, level to the main wall)
    ys = np.arange(wface.shape[0])[:, None]; xs_ = np.arange(wing1 - wing0)[None, :]
    rake_row = wface.shape[0] - np.minimum(49.0, 49.0 * xs_ / 84.0)             # (row of the rake line in the face picture)
    wface = cut(wface, ys >= rake_row - 1)
    wside = siding(wh0 + 8, 88, wall_c, seam_c, None, speck=speck, seed=2)
    wing = mbox((wing0, wing1), (0, wh0), (0, DW), (wall_c, wall_c, shade(wall_c, .8)), ft=at.add(wfront), fx=wing0, st=at.add(wside), top=False)
    parts.append(wing)
    wl = tile(np.ascontiguousarray(art[158:214, 38:98]), DW + 4, 60)
    wq = [(0, DW + 4), (60, DW + 4), (60, 0), (0, 0)]
    wroof = mb.mgable((wing0 - 4, wing1), (wh0, wh1), (0, DW), at.add(wl), flat(wq), None, (0,) * 8, (WHITE, TRIM), med(wl), face=at.add(wface), fx=wing0, fy=wh0, lip=TRIM, ax=wing1)
    parts.insert(1, wroof)
    # --- the porch over the door: two posts, a small shed roof on them, three steps up (the art has the door flush)
    px0, px1 = 140, 208
    steps = np.ascontiguousarray(art[F - 8:F, 118:140])
    for i in range(3):
        parts.append(slab(at, (px0 + 4 + i * 4, px1 - 4 - i * 4), (-24 + i * 6 - 2, -24 + i * 6 + 6 - 2 + (6 if i == 2 else 0)), 0, 3 * (3 - i) if False else 3 * (i + 1) - 0, (170, 164, 190), steps))
    # (steps rising toward the door: the lowest the farthest out)
    wood = (205, 134, 78)
    for x in (px0 + 2, px1 - 6):
        parts.append(mbox((x, x + 4), (3, 70), (-26, -22), (shade(wood, 1.1), wood, shade(wood, .7))))
    roof_slab = at.add(np.ascontiguousarray(art[200:212, 206:224])[:, :]) if False else None
    parts.append(dict(t='slope', x=[px0 - 6, px1 + 6], y=[66, 78], d=[-30, 0], c=[rgb_int(shade(roof_c, 1.0)), rgb_int(roof_c), rgb_int(shade(roof_c, .7))], th=3.0,
                      tt=at.add(tile(np.ascontiguousarray(art[60:120, 112:160]), 30, px1 - px0 + 12)), ft=at.add(np.ascontiguousarray(art[160:164, 112:160]))))
    parts += outs
    # the chimney on the right slope, a weathercock on the ridge's front end
    parts += chimney(at, art, 206, EM + 11, 92)
    vane = fart('Weathercock_Roof_Farmer_House')
    parts.append(mbox((160, 192), (EM + RISE + 3, EM + RISE + 3 + vane.shape[0]), (DM * .85, DM * .85), (TRIM, TRIM, TRIM), ft=at.add(vane), fx=160, card=True))
    # the back wall: its own elevation in the same boards, a window
    bw = np.ascontiguousarray(tile(wall_f[246:288, 206:224], EM + RISE, M1 - M0 + 16, True)); bwg = np.zeros_like(bw)
    bk = polygon(bw.shape[:2], [(0, bw.shape[0]), (0, bw.shape[0] - EM), ((M1 - M0 + 16) / 2, 0), (M1 - M0 + 16, bw.shape[0] - EM), (M1 - M0 + 16, bw.shape[0])])
    bw = cut(bw, bk)
    bw[40:40 + winp.shape[0], 56:56 + winp.shape[1]] = wp; bwg[40:40 + winp.shape[0], 56:56 + winp.shape[1]] = wg
    parts.append(mbox((M0 - 8, M1 + 8), (0, bw.shape[0]), (DM + .5, DM + .5), (wall_c, wall_c, wall_c), ft=at.add(bw, bwg), fx=M0 - 8, card=True))
    return finish('farmhouse', 256, DM, shop, rng, parts, roof_c, at, front, (px0, px1))



# ------------------------------------------------------------------------------------------------ farmhouse 2
# Farmer_House_2 (320 x 288, foot on row 284): a steep front gable (walls x 12..196, its rake from the eaves at x 3 and 205 up to
# the ridge at x 104, row 150), a bay window under a red awning, the roof's two slopes drawn from above either side of a
# timber ridge board; and a low wing to the right (x 196..312) whose front-facing roof of red panels runs up to a flat top,
# a door under a small canopy and a tall narrow window.
def house2(spec, shop, rng):
    art = fart('Farmer_House_2'); F = 284
    at = Atlas(1024)
    wall_f = fill_of(art, (236, 276, 100, 116))
    parts = []
    M0, M1, DM = 12, 196, 176
    EM, RIDGE = F - 218, F - 150                    # eaves 66, ridge 134
    wall_c = med(art, (236, 276, 100, 116)); roof_c = med(art, (60, 140, 28, 80))
    post_c = med(art, (240, 280, 14, 24)); seam_c = shade(wall_c, .82)
    speck = [shade(wall_c, .94), shade(wall_c, 1.06)]
    # what stands out: the bay window, its awning, the round vent, the door's frame and canopy, the narrow window
    outs = [proud(at, art, (50, 232, 162, 268), F, 10, wall_f), proud(at, art, (88, 166, 118, 192), F, 3, wall_f),
            proud(at, art, (222, 238, 262, 284), F, 5, wall_f), proud(at, art, (268, 236, 294, 270), F, 3, wall_f)]
    awn = np.ascontiguousarray(art[200:234, 46:166]); art[200:234, 46:166] = wall_f[200:234, 46:166]
    parts.append(dict(t='slope', x=[46, 166], y=[F - 233 + 2, F - 233 + 18], d=[-16, 0], c=[rgb_int(roof_c), rgb_int(roof_c), rgb_int(shade(roof_c, .7))], th=3.0,
                      tt=at.add(awn[:28]), ft=at.add(np.ascontiguousarray(awn[28:34]))))
    can = np.ascontiguousarray(art[222:234, 214:270]); art[222:234, 214:270] = wall_f[222:234, 214:270]
    parts.append(dict(t='slope', x=[214, 270], y=[F - 234 + 1, F - 234 + 9], d=[-14, 0], c=[rgb_int(TRIM), rgb_int(TRIM), rgb_int(shade(TRIM, .7))], th=3.0,
                      tt=at.add(can[:8]), ft=at.add(np.ascontiguousarray(can[8:12]))))
    # --- the main walls: the front under the gable's foot, its sides in plank siding with a window each, the posts
    front = np.ascontiguousarray(art[218:F, M0:M1])
    win = np.ascontiguousarray(art[236:266, 60:90]); wp, wg = lit(win)
    side = siding(EM, 96, wall_c, seam_c, post_c, speck=speck, seed=4); sg = np.zeros_like(side)
    side[18:18 + win.shape[0], 34:34 + win.shape[1]] = wp; sg[18:18 + win.shape[0], 34:34 + win.shape[1]] = wg
    main = mbox((M0, M1), (0, EM), (0, DM), (wall_c, wall_c, shade(wall_c, .8)), ft=at.add(front), fx=M0, st=at.add(side, sg), top=False)
    parts.append(main)
    # --- the roof: the two slopes of clay tiles from above, a ridge board
    lf = tile(np.ascontiguousarray(art[30:140, 30:86]), DM + 4, 100, True)
    lf = np.ascontiguousarray(np.concatenate([tile(np.ascontiguousarray(art[30:140, 30:86]), DM + 4, 56, True), tile(np.ascontiguousarray(art[30:140, 30:86]), DM + 4, 44, True)], 1))
    rf = np.ascontiguousarray(lf[:, ::-1])
    hw = lf.shape[1]; H_ = DM + 4
    lq = [(0, H_), (hw, H_), (hw, 0), (0, 0)]; rq = [(hw, H_), (0, H_), (0, 0), (hw, 0)]
    flat = lambda q: [v for p_ in q for v in p_]
    gh = RIDGE - EM
    gf = np.ascontiguousarray(art[150:218, 3:205]).copy()
    gf[:, M0 - 3:M0 - 3] = 0
    xs = np.arange(3, 205) + .5
    keep = (np.arange(150, 218)[:, None] >= 150 + np.abs(xs - 104)[None, :] * (gh / 101.0) - 6)
    gf = cut(gf, keep)
    gab = mb.mgable((3, 205), (EM, RIDGE), (0, DM), at.add(lf), flat(lq), at.add(rf), flat(rq), (WHITE, TRIM), roof_c, face=at.add(gf), fx=3, fy=EM, lip=med(art, (222, 226, 20, 40)))
    parts.insert(0, gab)
    ridge = tile(np.ascontiguousarray(art[30:140, 92:116]), DM + 4, 14)
    parts.append(mbox((97, 111), (RIDGE - 1, RIDGE + 4), (-1, DM + 2), (INK, med(ridge), shade(med(ridge), .8)), tt=at.add(ridge)))
    # --- the right wing: its walls set back 6, a hip-topped roof of the art's red panels
    W0, W1, DW, d0 = 196, 312, 112, 6
    wfront = np.ascontiguousarray(art[216:F, W0:W1])
    EW = F - 216
    wside = siding(EW, 88, wall_c, seam_c, post_c, speck=speck, seed=5)
    wing = mbox((W0, W1), (0, EW), (d0, d0 + DW), (wall_c, wall_c, shade(wall_c, .8)), ft=at.add(wfront), fx=W0, st=at.add(wside), top=False)
    parts.append(wing)
    hip, rise = civ.hip_roof(at, art, (W0 - 6, W1 + 6), EW, (150, 214), (W0 - 6, W1 + 6), 8, DW + 6, (112, 143, 202, 304), (20, 80), med(art, (160, 200, 220, 280)), med(art, (150, 152, 230, 232)), d0=d0 - 3)
    parts.append(hip)
    # fascia board along the wing's eave (the art's orange board)
    parts.append(mbox((W0 - 8, W1 + 8), (EW - 1, EW + 5), (d0 - 4, d0 - 2), (INK, med(art, (143, 150, 200, 300)), shade(med(art, (143, 150, 200, 300)), .8)), ft=at.add(tile(np.ascontiguousarray(art[144:150, 200:300]), 6, W1 - W0 + 16))))
    # --- the stoop at the wing's door: steps and two posts under the canopy
    for i in range(2):
        parts.append(slab(at, (220 + i * 4, 264 - i * 4), (-8 - 6 * (1 - i), -2 - 6 * (1 - i) + (4 if i else 0)), 0, 3 * (i + 1), (170, 164, 190), np.ascontiguousarray(art[F - 8:F, 200:212])))
    for x in (216, 262):
        parts.append(mbox((x, x + 4), (3, EW - 1), (-14, -10), (shade(post_c, 1.1), post_c, shade(post_c, .7))))
    parts += outs
    parts += chimney(at, art, 140, EM + 40, 60, size=22, height=34)
    # the back: boards up into the gable, a window
    bw = siding(RIDGE, M1 - M0 + 20, wall_c, seam_c, None, speck=speck, seed=6); bwg = np.zeros_like(bw)
    bk = polygon(bw.shape[:2], [(0, RIDGE), (0, RIDGE - EM), ((M1 - M0 + 20) / 2, 0), (M1 - M0 + 20, RIDGE - EM), (M1 - M0 + 20, RIDGE)])
    bw = cut(bw, bk)
    bw[30:30 + win.shape[0], 40:40 + win.shape[1]] = wp; bwg[30:30 + win.shape[0], 40:40 + win.shape[1]] = wg
    bw[30:30 + win.shape[0], M1 - M0 - 30 - win.shape[1] + 20:M1 - M0 - 30 + 20] = wp; bwg[30:30 + win.shape[0], M1 - M0 - 30 - win.shape[1] + 20:M1 - M0 - 30 + 20] = wg
    parts.append(mbox((M0 - 10, M1 + 10), (0, RIDGE), (DM + .5, DM + .5), (wall_c, wall_c, wall_c), ft=at.add(bw, bwg), fx=M0 - 10, card=True))
    return finish('farmhouse', 320, DM, shop, rng, parts, roof_c, at, front, (222, 262))


# ------------------------------------------------------------------------------------------------ the metal shed
# Barn_Small (256 x 320, foot on row 316): cream boards in an orange frame under a barrel roof of riveted grey metal, drawn
# from above in the art (a ridge cap, a flat panel and a curved lower panel either side). The roof is built as an arch of
# straight strips following the front wall's own arch, the art's panels laid on them by columns (identity), the ridge cap a
# board; a roll-up door and a louvred window stand proud of the front.
def shed(spec, shop, rng):
    art = fart('Barn_Small'); F = 317
    at = Atlas(1024)
    D = spec.get('depth', 176)
    X0, X1 = 14, 242
    # the wall's top (an art row) by column: the front's arch, read off the picture by eye (symmetric about x 128)
    px_ = [14, 24, 32, 46, 60, 84, 100, 120, 128]; pr_ = [238, 226, 218, 207, 201, 195, 191, 187, 186]
    top = {x: float(np.interp(min(x, 256 - x), px_, pr_)) for x in range(0, 257)}
    ht = lambda x: int(round(F - top[int(min(max(x, X0), X1 - 1))]))
    EV = int(round(np.median([ht(x) for x in range(X0 + 4, X0 + 14)])))          # the walls where the arch springs
    wall_c = med(art, (290, 312, 140, 200))
    outs = [proud(at, art, (146, 214, 210, 256), F, 3, fill_of(art, (284, 312, 140, 210))), proud(at, art, (44, 244, 112, 317), F, 4, fill_of(art, (284, 312, 140, 210)))]
    # the front: board and timber up to the arch (cut under its profile)
    fh = F - 186
    front = np.ascontiguousarray(art[186:F, X0:X1]).copy()
    prof = np.array([ht(x) for x in range(X0, X1)])
    keep = (np.arange(fh)[::-1][:, None] <= prof[None, :] + 1)
    front = cut(front, keep)
    side_field = fill_of(art, (284, 312, 140, 210))
    board = np.ascontiguousarray(side_field[:EV, :88]).copy()
    wside = siding(EV + 2, 88, wall_c, shade(wall_c, .85), med(art, (270, 300, 20, 30)), period=16, seed=7)
    bay = wside.copy(); bg = np.zeros_like(bay)
    lw = np.ascontiguousarray(art[216:254, 148:210]); lp_, lg_ = lit(lw)
    bay[28:28 + lw.shape[0], 12:12 + lw.shape[1]] = lp_
    body = mbox((X0, X1), (0, EV), (0, D), (wall_c, wall_c, shade(wall_c, .8)), ft=at.add(front), fx=X0, st=at.add(bay, bg), top=False)
    parts = [body]
    # the arch: three straight strips each side, the art's roof columns on them, the panels from its front edge back
    roof_art = art[8:182]
    flat = lambda q: [v for p_ in q for v in p_]
    pts = [14, 46, 84, 120]; hs = [ht(14), ht(46), ht(84), ht(120)]
    hs = [max(EV, h) for h in hs]; hs[-1] = max(hs[-1], hs[-2] + 6)
    roof_c = med(roof_art, (20, 120, 60, 112))
    L = []
    for i in range(3):
        a, b = pts[i], pts[i + 1]
        pic = tile(np.ascontiguousarray(roof_art[10:166, a:b]), D + 4, b - a)
        q = [(0, D + 4), (b - a, D + 4), (b - a, 0), (0, 0)]
        L.append(mb.mgable((a, b), (hs[i], hs[i + 1]), (0, D), at.add(pic), flat(q), None, (0,) * 8, (WHITE, TRIM), roof_c, lip=TRIM, ax=b))
    xs2 = [242, 210, 172, 136]
    h2 = [ht(241), ht(210), ht(172), ht(136)]; h2 = [max(EV, h) for h in h2]; h2[-1] = hs[-1]
    for i in range(3):
        a, b = xs2[i + 1], xs2[i]               # a < b : the high edge at a
        pic = tile(np.ascontiguousarray(roof_art[10:166, a:b]), D + 4, b - a)
        q = [(b - a, D + 4), (0, D + 4), (0, 0), (b - a, 0)]
        r = mb.mgable((a, b), (h2[i], h2[i + 1]), (0, D), None, (0,) * 8, at.add(pic), flat(q), (WHITE, TRIM), roof_c, lip=TRIM, ax=a)
        del r['l'], r['lp']
        L.append(r)
    parts = L + parts
    # the ridge cap
    cap = tile(np.ascontiguousarray(roof_art[10:166, 120:136]), D + 4, 16)
    parts.append(mbox((121, 135), (hs[-1] - 1, hs[-1] + 4), (-2, D + 2), (INK, med(cap), shade(med(cap), .8)), tt=at.add(cap)))
    parts += outs
    # the back: boards up into the arch, a door
    bwid = X1 - X0
    bw = siding(hs[-1], bwid, wall_c, shade(wall_c, .85), med(art, (270, 300, 20, 30)), period=16, seed=8)
    bprof = np.array([ht(x) for x in range(X0, X1)])
    bw = cut(bw, (np.arange(hs[-1])[::-1][:, None] <= bprof[None, :] + 1))
    parts.append(mbox((X0, X1), (0, hs[-1]), (D + .5, D + .5), (wall_c, wall_c, wall_c), ft=at.add(bw), fx=X0, card=True))
    return finish('shed', 256, D, shop, rng, parts, roof_c, at, front, (44, 112))

# ------------------------------------------------------------------------------------------------ builder and showcase
def builder(key):
    if not key.startswith('fm_'): return None
    def make(shop, rng):
        sp = shop['spec']; k = sp['kit']
        return KITS[k](sp, shop, rng)
    return make


KITS = {'barn': barn, 'house1': house1, 'house2': house2, 'shed': shed}


def specs():
    return [dict(id='fm_house1', kit='house1'), dict(id='fm_house2', kit='house2'), dict(id='fm_shed', kit='shed'), dict(id='fm_barn_red', kit='barn', colour='red', depth=416), dict(id='fm_barn_grey', kit='barn', colour='grey', depth=384),
            dict(id='fm_barn_yellow', kit='barn', colour='yellow', depth=448), dict(id='fm_barn_teal', kit='barn', colour='teal', depth=352)]


def showcase(out):
    import json
    shops = []
    all_specs = specs()
    for sp in all_specs:
        shops.append(dict(id=f'show:{sp["id"]}', sprite=sp['id'], home=False, name=sp['id'], kind='Shop', owner='', colour='base',
                          parts=[dict(key=sp['id'], w=sp.get('widthTiles', 15) * 32, h=200, y=0)], yard=dict(front=1, style='natural'), spec=sp))
    os.makedirs(out + '/buildings', exist_ok=True)
    json.dump(dict(town='farm', year=2025, type='farm showcase', shops=shops,
                   streets=[dict(name='Barns', buildings=[s['id'] for s in all_specs if s['kit'] == 'barn']), dict(name='Houses and sheds', buildings=[s['id'] for s in all_specs if s['kit'].startswith(('house', 'shed'))])]),
              open(out + '/street.json', 'w'), indent=1)
    return out + '/street.json', out + '/buildings'
