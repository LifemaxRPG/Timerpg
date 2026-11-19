#!/usr/bin/env python3
"""Cuts the Modern Interiors furniture into 3D pieces: `pieces.json` plus small atlases (docs/INTERIORS_3D.md, step 2).

    python3 tools/cut_pieces.py <Modern_Interiors_v41.4.zip> [out-dir] [--sheet DIR]   (DIR: contact sheets per family)

Reads the pack's zip directly (rebuild it with packs/join.sh into a scratch folder, never into the repo), takes the 32 px
theme sheets (the town's scale: 1 art pixel = 1 texel), finds every object on them (connected components), groups colour
variants into one *design*, and for each design guesses how RoomKit should build it:

  kind    box    one block: the top seen from above over the front (a wardrobe, a counter, a fridge)
          parts  a few-part family built by RoomKit (bed, sofa, toilet, tub, desk, chair, table, lamp ...)
          card   a camera-facing card (plants, small clutter, anything irregular)
          flat   lying on the floor or a surface (rugs, mats, cloths, plates)
          hand   wall things (pictures, windows, doors, clocks) and what must be assembled by hand
  family what it is (bed, sofa, wardrobe ...), from theme + size rules and a few hand labels (FAMILY_RULES below)
  split  for boxes the guessed top/front split: the art's 3/4 view draws the top in the rows nearer its top and the
         front under them; the front starts at the first interior row of the outline colour (the top's front edge),
         else at the biggest brightness step. top = [t0, t1), front = [f0, f1) rows of the design's own picture.

pieces.json lists every design (`designs`), each with its atlas place for the representative and its colour variants.
Only the families rooms need are put in the atlases (the others are listed with kind and split but cost no pixels), so
the shipped art is small. The raw pack never goes into the tree.
"""
import io
import json
import os
import sys
import zipfile
import collections

import numpy as np
from PIL import Image
from scipy import ndimage as ndi

I32 = "1_Interiors/32x32/Theme_Sorter_Shadowless_32x32/"
SHEETS = {  # theme key -> sheet file
    "generic": "1_Generic_Shadowless32x32.png", "living": "2_LivingRoom_Shadowless_32x32.png",
    "bath": "3_Bathroom_Shadowless_32x32.png", "bedroom": "4_Bedroom_Shadowless_32x32.png",
    "classroom": "5_Classroom_and_library_Shadowless_32x32.png", "music": "6_Music_and_sport_Shadowless_32x32.png",
    "art": "7_Art_Shadowless_32x32.png", "gym": "8_Gym_Shadowless_32x32.png", "fishing": "9_Fishing_Shadowless_32x32.png",
    "party": "10_Birthday_party_Shadowless_32x32.png", "halloween": "11_Halloween_Shadowless_32x32.png",
    "kitchen": "12_Kitchen_Shadowless_32x32.png", "conference": "13_Conference_Hall_Shadowless_32x32.png",
    "basement": "14_Basement_Shadowless_32x32.png", "christmas": "15_Christmas_Shadowless_32x32.png",
    "grocery": "16_Grocery_store_Shadowless_32x32.png", "jail": "18_Jail_Shadowless_32x32.png",
    "hospital": "19_Hospital_Shadowless_32x32.png", "japanese": "20_Japanese_interiors_Shadowless_32x32.png",
    "clothing": "21_Clothing_Store_Shadowless_32x32.png", "museum": "22_Museum_Shadowless_32x32.png",
    "studio": "23_Television_and_Film_Studio_Shadowless_32x32.png", "icecream": "24_Ice_Cream_Shop_Shadowless_32x32.png",
    "shooting": "25_Shooting_Range_Shadowless_32x32.png", "condo": "26_Condominium_Shadowless_32x32.png",
}

# ---- family rules ----------------------------------------------------------------------------------------------------
# (theme, w, h) -> (family, kind): the pack draws each design at one size, so size picks out the families rooms need most.
# Found by looking at the sheets (the contact sheets `--sheet` writes); anything not listed gets the automatic guess.
SIZE_RULES = {
    ("bedroom", 72, 50): ("bed_side", "parts"), ("bedroom", 32, 76): ("bed_up", "parts"), ("bedroom", 32, 72): ("bed_up", "parts"),
    ("bedroom", 64, 76): ("bed_up", "parts"), ("bedroom", 64, 72): ("bed_up", "parts"),
    ("bedroom", 78, 82): ("bunk", "hand"), ("bedroom", 54, 82): ("bunk", "hand"),
    ("bedroom", 30, 80): ("wardrobe", "box"), ("bedroom", 32, 80): ("wardrobe", "box"), ("bedroom", 32, 76): ("bed_up", "parts"),
    ("bedroom", 60, 76): ("wardrobe", "box"), ("bedroom", 62, 72): ("wardrobe", "box"), ("bedroom", 62, 64): ("dresser", "box"),
    ("bath", 24, 68): ("toilet", "parts"), ("bath", 28, 68): ("toilet", "parts"), 
    ("bath", 48, 68): ("tub", "parts"), ("bath", 50, 72): ("tub", "parts"), ("bath", 48, 72): ("tub", "parts"),
    ("bath", 62, 50): ("sink", "box"), ("bath", 62, 68): ("sink", "box"), ("bath", 62, 40): ("sink", "box"),
    ("bath", 64, 80): ("shower", "box"), ("bath", 32, 80): ("shower", "box"),
    ("classroom", 64, 78): ("bookcase", "parts"), ("classroom", 50, 78): ("bookcase", "parts"), ("classroom", 50, 60): ("bookcase", "parts"),
    ("classroom", 54, 76): ("bookcase", "parts"), ("classroom", 30, 78): ("bookcase", "parts"), ("classroom", 24, 78): ("bookcase", "parts"),
    ("classroom", 32, 54): ("desk", "parts"), ("classroom", 32, 40): ("desk", "parts"), ("classroom", 44, 42): ("desk", "parts"),
    ("classroom", 44, 44): ("desk", "parts"), ("classroom", 56, 48): ("desk", "parts"), ("classroom", 56, 70): ("desk", "parts"),
    ("classroom", 48, 68): ("desk", "parts"), ("classroom", 64, 38): ("wall_decor", "hand"),
    ("living", 54, 76): ("wardrobe", "box"), ("living", 54, 62): ("wardrobe", "box"), ("living", 32, 76): ("wardrobe", "box"),
    ("living", 28, 76): ("wardrobe", "box"), ("living", 26, 76): ("wardrobe", "box"), ("living", 54, 76): ("wardrobe", "box"),
    ("living", 54, 46): ("dresser", "box"), ("living", 50, 42): ("dresser", "box"), ("living", 64, 42): ("dresser", "box"),
    ("living", 64, 60): ("dresser", "box"), ("living", 50, 62): ("dresser", "box"), ("living", 32, 40): ("nightstand", "box"),
    ("living", 32, 62): ("nightstand", "box"), ("living", 32, 34): ("nightstand", "box"), ("living", 30, 64): ("lamp", "card"),
    ("living", 56, 58): ("dresser", "box"), ("living", 62, 58): ("dresser", "box"),
    ("kitchen", 32, 52): ("fridge", "box"), ("kitchen", 32, 70): ("fridge", "box"), ("kitchen", 26, 74): ("fridge", "box"),
    ("kitchen", 52, 74): ("fridge", "box"), ("kitchen", 32, 48): ("stove", "box"), ("kitchen", 46, 48): ("stove", "box"),
    ("kitchen", 32, 56): ("stove", "box"), ("kitchen", 46, 56): ("stove", "box"), ("kitchen", 58, 28): ("counter", "box"),
    ("kitchen", 28, 28): ("counter", "box"), ("kitchen", 32, 28): ("counter", "box"), ("kitchen", 60, 44): ("table", "parts"),
    ("kitchen", 60, 50): ("table", "parts"), ("kitchen", 60, 44): ("table", "parts"), ("kitchen", 26, 42): ("chair", "parts"),
    ("kitchen", 20, 42): ("chair", "parts"), ("kitchen", 64, 32): ("display", "box"), ("kitchen", 48, 28): ("sink", "box"),
    ("grocery", 52, 70): ("shop_shelf", "box"), ("grocery", 52, 74): ("shop_shelf", "box"), ("grocery", 32, 74): ("shop_shelf", "box"),
    ("grocery", 44, 90): ("shop_shelf", "box"), ("grocery", 46, 90): ("shop_shelf", "box"), ("grocery", 62, 94): ("register", "box"),
    ("grocery", 56, 40): ("register", "box"), ("grocery", 62, 62): ("register", "box"), ("grocery", 32, 66): ("shop_shelf", "box"),
    ("grocery", 62, 52): ("shop_counter", "box"), ("grocery", 32, 52): ("shop_counter", "box"), ("grocery", 40, 74): ("shop_shelf", "box"),
    ("grocery", 32, 76): ("shop_shelf", "box"), ("grocery", 56, 58): ("oven", "box"), ("grocery", 50, 60): ("oven", "box"),
}
# theme-wide families for objects the size rules do not name (a design must still be a box to get one)
THEME_BOX_FAMILY = {"bedroom": "dresser", "living": "dresser", "classroom": "bookcase", "kitchen": "counter", "grocery": "shop_shelf",
                    "clothing": "shop_shelf", "hospital": "cabinet", "conference": "desk", "bath": "cabinet"}
# Objects the numbered singles lack and that touch their neighbours on the sheets (so they come out merged) are cut by hand:
# (theme, x, y, w, h) of a rectangle round the object on its sheet -> (id, family, kind). The art is cropped to what is opaque.
HAND_RECTS = [
    ("living", 32, 906, 96, 54, "living_sofa_blue", "sofa", "parts"), ("living", 128, 906, 96, 54, "living_sofa_grey", "sofa", "parts"),
    ("living", 224, 906, 96, 54, "living_sofa_brown", "sofa", "parts"), ("living", 224, 974, 96, 50, "living_sofa_white", "sofa", "parts"),
    ("living", 32, 960, 63, 56, "living_corner_blue", "sofa_corner", "box"), ("living", 96, 962, 33, 44, "living_ottoman_blue", "ottoman", "box"),
    ("living", 128, 962, 63, 56, "living_corner_grey", "sofa_corner", "box"), ("living", 192, 962, 33, 44, "living_ottoman_grey", "ottoman", "box"),
]
# by id (theme@x,y of a sheet object, or theme_n of a single) -> (family, kind): looked at on the contact sheets
HAND_FAMILIES = {"bedroom_513": ("wardrobe", "box"), "bedroom_514": ("wardrobe", "box"), "bedroom_516": ("wardrobe", "box")}
for _x, _y in [(26, 162), (26, 258), (26, 1090), (186, 1090), (26, 1186), (186, 1186), (170, 208), (424, 1010)]:
    HAND_FAMILIES["generic@%d,%d" % (_x, _y)] = ("table", "parts")
for _x, _y in [(128, 162), (128, 258), (128, 1090), (288, 1090), (128, 1186), (288, 1186)]:
    HAND_FAMILIES["generic@%d,%d" % (_x, _y)] = ("table_end", "parts")
for _x, _y in [(2, 508), (66, 508)]:
    HAND_FAMILIES["generic@%d,%d" % (_x, _y)] = ("bench", "parts")
for _x, _y in [(132, 364), (162, 364), (292, 1602), (324, 1602), (356, 1602), (388, 1602), (416, 1602), (448, 1602), (480, 1602),
               (290, 1666), (322, 1666), (354, 1666), (386, 1666), (428, 1666), (460, 1666), (492, 1666)]:
    HAND_FAMILIES["generic@%d,%d" % (_x, _y)] = ("chair", "parts")
for _x, _y in [(198, 1390), (262, 1390), (326, 1390), (390, 1390), (454, 1390), (454, 1454), (454, 1518), (166, 302), (232, 274), (6, 248)]:
    HAND_FAMILIES["generic@%d,%d" % (_x, _y)] = ("window", "wall")
for _x, _y in [(2, 1302), (34, 1302), (66, 1302), (2, 1366), (34, 1366), (66, 1366)]:
    HAND_FAMILIES["generic@%d,%d" % (_x, _y)] = ("door", "wall")
for _x, _y in [(174, 1454), (268, 1454), (364, 1454), (172, 1550), (268, 1550), (364, 1550)]:
    HAND_FAMILIES["generic@%d,%d" % (_x, _y)] = ("curtain", "wall")
for _x, _y in [(10, 436), (68, 438), (132, 438), (298, 338), (360, 338)]:
    HAND_FAMILIES["generic@%d,%d" % (_x, _y)] = ("picture", "wall")
for _x, _y in [(106, 1422), (106, 1488)]:
    HAND_FAMILIES["generic@%d,%d" % (_x, _y)] = ("tv", "wall")
for _x, _y in [(0, 2128), (0, 2224), (128, 2320), (0, 2320)]:
    HAND_FAMILIES["generic@%d,%d" % (_x, _y)] = ("vanity", "box")
for _x, _y in [(354, 2158), (386, 2158), (418, 2158)]:
    HAND_FAMILIES["generic@%d,%d" % (_x, _y)] = ("toilet", "parts")
for _x, _y in [(354, 2240), (354, 2318)]:
    HAND_FAMILIES["generic@%d,%d" % (_x, _y)] = ("sink", "box")
for _x, _y in [(424, 164), (424, 292), (262, 422), (6, 1412), (0, 1476), (298, 338 + 0), (170, 208 + 0)]:
    if (_x, _y) in [(424, 164), (424, 292), (262, 422), (6, 1412), (0, 1476)]:
        HAND_FAMILIES["generic@%d,%d" % (_x, _y)] = ("rug", "flat")
for _n in range(13, 19):
    HAND_FAMILIES["living_%d" % _n] = ("plant", "card")
for _n in (36, 39, 32):
    HAND_FAMILIES["classroom_%d" % _n] = ("board", "wall")
for _n in (50, 51, 52):
    HAND_FAMILIES["conference_%d" % _n] = ("filing", "box")
for _n in (37, 39):
    HAND_FAMILIES["conference_%d" % _n] = ("chair_front", "parts")
for _x, _y in [(424, 16), (430, 914), (480, 914), (266, 1810), (226, 1816), (448, 2022), (480, 2042)]:
    HAND_FAMILIES["generic@%d,%d" % (_x, _y)] = ("plant", "card")

DARK = 40  # how far above the darkest outline pixel's brightness still counts as the outline colour


def lum(a):
    return a[..., 0] * .3 + a[..., 1] * .59 + a[..., 2] * .11


SINGLES = "1_Interiors/32x32/Theme_Sorter_Shadowless_Singles_32x32/"
THEME_NO = {"living": 2, "bath": 3, "bedroom": 4, "classroom": 5, "music": 6, "art": 7, "gym": 8, "fishing": 9, "party": 10,
            "halloween": 11, "kitchen": 12, "conference": 13, "basement": 14, "christmas": 15, "grocery": 16, "jail": 18,
            "hospital": 19, "japanese": 20, "clothing": 21, "museum": 22, "studio": 23, "icecream": 24, "shooting": 25, "condo": 26}


def bbox_crop(a):
    ys, xs = np.where(a[..., 3] > 127)
    return a[ys.min():ys.max() + 1, xs.min():xs.max() + 1].copy(), xs.min(), ys.min()


def singles(z, theme, names):
    """The pack's numbered single pictures of a theme, cropped to their art: [dict(id, a)]."""
    import re
    pre = "%d_" % THEME_NO[theme]
    out = []
    for n in names:
        if n.startswith(SINGLES) and n.endswith(".png") and os.path.basename(os.path.dirname(n)).startswith(pre):
            a = np.array(Image.open(io.BytesIO(z.read(n))).convert("RGBA"))
            if (a[..., 3] > 127).sum() < 24:
                continue
            num = int(re.search(r"_(\d+)\.png", n).group(1))
            out.append(dict(id="%s_%d" % (theme, num), a=bbox_crop(a)[0], num=num))
    out.sort(key=lambda c: c["num"])
    return out


def components(sheet):
    """Every object on a sheet: 8-connected opaque pixels, a piece wholly inside another's box (a lamp's glow, an item on a
    desk drawn apart) joins it. Touching objects stay together; the sofas, tubs and so on that the singles lack come out
    whole because the pack keeps one transparent column or row between neighbours."""
    al = sheet[..., 3] > 127
    lab, n = ndi.label(al, structure=np.ones((3, 3)))
    boxes = []
    for i, sl in enumerate(ndi.find_objects(lab), 1):
        boxes.append([i, sl[1].start, sl[0].start, sl[1].stop, sl[0].stop, int((lab[sl] == i).sum())])
    owner = {b[0]: b[0] for b in boxes}
    for b in sorted(boxes, key=lambda b: b[5]):
        for o in sorted(boxes, key=lambda o: -o[5]):
            if o[0] != b[0] and o[5] > b[5] and o[1] - 1 <= b[1] and o[2] - 1 <= b[2] and o[3] + 1 >= b[3] and o[4] + 1 >= b[4]:
                owner[b[0]] = o[0]
                break
    groups = collections.defaultdict(list)
    for b in boxes:
        r = b[0]
        while owner[r] != r:
            r = owner[r]
        groups[r].append(b[0])
    out = []
    for r, ids in groups.items():
        m = np.isin(lab, ids)
        ys, xs = np.where(m)
        if len(ys) < 24:
            continue
        sub = sheet[ys.min():ys.max() + 1, xs.min():xs.max() + 1].copy()
        sub[~m[ys.min():ys.max() + 1, xs.min():xs.max() + 1]] = 0
        out.append(dict(x=int(xs.min()), y=int(ys.min()), a=sub))
    return out


def structure_key(a):
    al = a[..., 3] > 127
    return (a.shape[1], a.shape[0], al.tobytes())


def gray(a):
    m = a[..., 3] > 127
    g = lum(a)
    v = g[m]
    g = (g - v.mean()) / (v.std() + 1e-6)
    g[~m] = 0
    return g, m


def group_designs(comps):
    """Colour variants share a design: same silhouette and a brightness pattern that matches (palette swaps keep it)."""
    by = collections.OrderedDict()
    for c in comps:
        by.setdefault(structure_key(c["a"]), []).append(c)
    designs = []
    for key, cs in by.items():
        reps = []
        for c in cs:
            g, m = gray(c["a"])
            for r in reps:
                if (r[0][m] * g[m]).mean() > .75:
                    r[1].append(c)
                    break
            else:
                reps.append((g, [c]))
        for g, mem in reps:
            designs.append(mem)
    return designs


def guess_split(a):
    """Where the top ends and the front begins in a 3/4 picture: the first interior row of the outline colour that spans
    most of the width (the top's front edge), else the biggest brightness step going down. Returns (row, how)."""
    m = a[..., 3] > 127
    h, w = m.shape
    L = lum(a)
    out = L[m].min()
    dark = m & (L <= out + DARK)
    opq = m.sum(1)
    frac = np.where(opq > 0, dark.sum(1) / np.maximum(opq, 1), 0)
    for y in range(3, h - 3):
        if frac[y] >= .6 and opq[y] >= .6 * w:
            return y, "edge"
    rowl = np.array([L[y][m[y]].mean() if opq[y] else 0 for y in range(h)])
    best, by = 0, h // 3
    for y in range(3, h - 4):
        up = rowl[max(0, y - 3):y].mean(); dn = rowl[y:y + 3].mean()
        if up - dn > best:
            best, by = up - dn, y
    return by, ("step" if best > 12 else "third")


def runs(mask):
    """[(start, end)) of the True runs of a 1-D bool array."""
    out, i = [], 0
    while i < len(mask):
        if mask[i]:
            j = i
            while j < len(mask) and mask[j]:
                j += 1
            out.append((i, j))
            i = j
        else:
            i += 1
    return out


def dark_profile(a):
    """Per row and per column: the share of the opaque pixels that are in the outline colour."""
    m = a[..., 3] > 127
    L = lum(a)
    dark = m & (L <= L[m].min() + DARK)
    rf = dark.sum(1) / np.maximum(m.sum(1), 1)
    cf = dark.sum(0) / np.maximum(m.sum(0), 1)
    return m, rf, cf


def spec_for(family, a):
    """The row/column ranges RoomKit's builder for [family] cuts the picture at, found from the art (see RoomKit)."""
    h, w = a.shape[:2]
    m, rf, cf = dark_profile(a)
    opq = m.sum(1) / w
    S = {}
    if family in ("sofa", "chair_front"):
        # backrest (its top, its face) over the seat (its top, its front): the seam between them is the darkest row of the
        # middle of the picture; the seat's front starts 8 rows above the plinth's outline
        mid = range(int(h * .25), int(h * .7))
        s = max(mid, key=lambda y: rf[y])
        first = int(np.argmax(m.any(1)))
        bt = min(9, max(3, (s - first) // 3))
        S["back_top"] = [first, first + bt]; S["back_face"] = [first + bt, s]
        low = [r for r in runs(rf[s + 3:] > .5)]
        d2 = (s + 3 + low[0][0]) if low else int(h * .85)
        S["seat_top"] = [s + 1, max(s + 3, d2 - 8)]; S["seat_front"] = [S["seat_top"][1], h]
        seam_cols = [x for x, _ in runs(cf[:] > .0) if False]
        zone = m[S["seat_top"][0]:S["seat_top"][1]]
        L = lum(a)
        seams = []
        if zone.size:
            dk = (L[S["seat_top"][0]:S["seat_top"][1]] < L[m].min() + 60) & zone
            col = dk.sum(0) / max(1, zone.shape[0])
            seams = [int((x0 + x1) // 2) for x0, x1 in runs(col > .55) if 6 < x0 < w - 6]
        S["seams"] = seams or ([w // 3, 2 * w // 3] if w >= 80 else [])
    elif family == "bed_up":
        # head (the headboard seen from the front), pillows, body (blanket), rail (the foot's front), legs: the dark lines
        # between them are the picture's own rows
        rr = runs(rf > .55)
        lines = [r for r in rr if r[0] > 6]
        c1 = next((r for r in lines if r[0] > h * .25), (int(h * .42), int(h * .44)))
        c2 = next((r for r in lines if r[0] > c1[1] + 8), (int(h * .84), int(h * .86)))
        rail_end = max(y for y in range(h) if opq[y] > .55) + 1
        S["along"] = dict(head=[0, c1[0] - 15], pillow=[c1[0] - 15, c1[1]], body=[c1[1], c2[0]], rail=[c2[1], rail_end], legs=[rail_end, h])
    elif family == "bed_side":
        # the plan seen from the side: the headboard strip at one end (the end whose first opaque row is highest), the pillow
        # next to it, the blanket, and under the front edge the rail rows and the legs
        lc = int(np.argmax(m[:, :6].any(1))); rc = int(np.argmax(m[:, -6:].any(1)))
        right = bool(rc < lc)
        S["head_right"] = right
        S["cols"] = dict(head=[w - 8, w] if right else [0, 8], pillow=[w - 22, w - 8] if right else [8, 22], body=[0, w - 22] if right else [22, w])
        rrows = runs(rf > .55)
        c = next((r for r in rrows if r[0] > h * .6), (int(h * .75), int(h * .78)))
        top0 = int(np.argmax(m[:, w // 2]))
        S["rows"] = dict(top=[top0, c[0]], rail=[c[1], max(y for y in range(h) if opq[y] > .55) + 1], legs=[max(y for y in range(h) if opq[y] > .55) + 1, h])
    elif family == "chair":
        # a side view (the art's post is the tall column at one end): seat = the rows where it is wide, back above, legs under
        wide = runs(opq > .55)
        r0, r1 = max(wide, key=lambda r: r[1] - r[0]) if wide else (h // 2, h * 3 // 4)
        left = int(np.argmax(m[:, :4].any(1))) <= int(np.argmax(m[:, -4:].any(1)))
        S["seat"] = [r0, r1]; S["back"] = [0, r0]; S["legs"] = [r1, h]; S["post_left"] = bool(left)
    elif family == "toilet":
        ch = np.array([[max(px[0], px[1], px[2]) - min(px[0], px[1], px[2]) for px in row] for row in a[..., :3].astype(int)])
        warm = [y for y in range(h) if (ch[y][m[y]] > 50).sum() > 5] if m.any() else []
        r1 = warm[0] if warm else h // 4
        S["tank"] = [0, r1]; S["bowl"] = [r1, h - 10]; S["front"] = [h - 10, h]
    elif family == "tub":
        S["top"] = [0, h - 10]; S["front"] = [h - 10, h]
    elif family in ("desk", "table", "table_end", "bench"):
        full = runs(opq > .85)
        r0, r1 = max(full, key=lambda r: r[1] - r[0]) if full else (0, h)
        chair = r0 if r0 >= 8 else 0
        dk = [r for r in runs(rf > .5) if r0 + (r1 - r0) // 2 < r[0] < r1 - 2]
        rail0 = dk[-1][0] if dk else max(r0 + 4, r1 - 7)
        S["chair"] = [0, chair]; S["top"] = [r0 if chair else 0, rail0]; S["rail"] = [rail0, r1]; S["legs"] = [r1, h]
    elif family == "bookcase":
        y, how = guess_split(a)
        S["top"] = [0, y]; S["front"] = [y, h]
    return S


def classify(theme, a, override=None, sheet_obj=False):
    h, w = a.shape[:2]
    m = a[..., 3] > 127
    fill = m.mean()
    fam = kind = None
    if override:
        fam, kind = override
    elif (theme, w, h) in SIZE_RULES and not (sheet_obj and SIZE_RULES[(theme, w, h)][0] in ("bed_up", "bed_side", "toilet", "tub")):
        fam, kind = SIZE_RULES[(theme, w, h)]
    info = dict(fill=round(float(fill), 2))
    if fam is not None:
        info["reviewed"] = True  # named by a size rule or a hand label (looked at on the sheets), not just guessed
    if kind is None:
        g = a[..., :3].astype(int)[m]
        green = ((g[:, 1] > g[:, 0] + 12) & (g[:, 1] > g[:, 2] + 12)).mean() if len(g) else 0
        if green > .3 and fill < .85:
            kind, fam = "card", "plant"
        elif w * h <= 28 * 28 and not (w >= 22 and h >= 22 and fill > .85):
            kind, fam = "card", "clutter"
        elif fill < .62:
            kind, fam = "card", "plant_or_prop"
        else:
            y, how = guess_split(a)
            L = lum(a)
            out = L[m].min()
            # flat: no top/front structure (a rug, a mat, a cloth): no interior outline row and a plain interior
            interior = a[2:-2, 2:-2][m[2:-2, 2:-2]] if h > 5 and w > 5 else a[m]
            if how != "edge" and fill > .85 and (w >= 24 and h >= 20):
                kind, fam = "flat", "rug"
            elif how == "edge" or how == "step":
                kind, fam = "box", THEME_BOX_FAMILY.get(theme, "cabinet")
            else:
                kind, fam = "card", "prop"
    if kind == "parts" or fam == "bookcase":
        S = spec_for("chair_front" if fam == "chair_front" else fam, a)
        if S:
            info["spec"] = S
    if kind in ("box", "parts"):
        y, how = guess_split(a)
        info["split"] = dict(row=int(y), how=how, top=[1, int(y)], front=[int(y), int(h)])
        info["depth"] = int(max(4, y - 1))
        info["height"] = int(max(4, h - y))
        info["footprint"] = [int(w), info["depth"]]
    return fam, kind, info


# the atlases hold these families only (what rooms need most); everything else is listed with its kind and split
ATLAS_FAMILIES = {"plant", "board", "chair_front", "table_end", "bench", "vanity", "window", "door", "curtain", "picture", "ottoman", "sofa_corner", "bed_side", "bed_up", "bunk", "sofa", "armchair", "table", "chair", "desk", "wardrobe", "dresser", "nightstand",
                  "bookcase", "shelf", "counter", "stove", "fridge", "sink", "toilet", "tub", "shower", "tv", "lamp", "plant",
                  "rug", "wall_decor", "shop_shelf", "shop_counter", "register", "filing", "cabinet", "display", "oven"}
MAX_VARIANTS = 6


def pack_atlas(items, width=1024):
    """Shelf-pack [(key, array)] into atlases; returns (list of PIL images, {key: (atlas index, x, y)})."""
    items = sorted(items, key=lambda kv: -kv[1].shape[0])
    atlases, where = [], {}
    x = y = rowh = 0
    cur = []
    maxh = 2048
    def flush():
        nonlocal cur, x, y, rowh
        if cur:
            H = y + rowh
            im = np.zeros((H, width, 4), np.uint8)
            for k, a, ax, ay in cur:
                im[ay:ay + a.shape[0], ax:ax + a.shape[1]] = a
            atlases.append(Image.fromarray(im, "RGBA"))
        cur = []; x = y = rowh = 0
    for k, a in items:
        h, w = a.shape[:2]
        if x + w + 1 > width:
            x = 0; y += rowh + 1; rowh = 0
        if y + h > maxh:
            flush()
        where[k] = (len(atlases), x, y)
        cur.append((k, a, x, y))
        x += w + 1; rowh = max(rowh, h)
    flush()
    return atlases, where


def contact_sheets(designs, dirname, per=120, cols=12, cw=118, ch=100):
    """One labelled contact sheet per (kind, family) group (--sheet DIR): how the guesses are checked by eye."""
    from PIL import ImageDraw
    os.makedirs(dirname, exist_ok=True)
    groups = collections.defaultdict(list)
    for d in designs:
        groups[(d["kind"], d["family"], d["theme"] if d["family"] in ("cabinet", "clutter", "prop", "plant_or_prop") else "")].append(d)
    for (kind, fam, th), ds in groups.items():
        for part in range(0, len(ds), per):
            chunk = ds[part:part + per]
            rows = (len(chunk) + cols - 1) // cols
            im = Image.new("RGB", (cols * cw, rows * ch), (110, 125, 110))
            dr = ImageDraw.Draw(im)
            for i, d in enumerate(chunk):
                a = Image.fromarray(d["_mem"][0]["a"], "RGBA")
                a = a.crop((0, 0, min(a.width, cw - 4), min(a.height, ch - 14)))
                x, y = (i % cols) * cw, (i // cols) * ch
                im.paste(a, (x + 2, y + 12), a)
                dr.text((x + 2, y), "%s %dx%d" % (d["id"].replace("@", " ")[:18], d["w"], d["h"]), fill=(255, 255, 255))
            im.save(os.path.join(dirname, "%s_%s%s_%d.png" % (kind, fam, ("_" + th) if th else "", part // per)))


def main():
    sheetdir = None
    if "--sheet" in sys.argv:
        i = sys.argv.index("--sheet")
        sheetdir = sys.argv[i + 1]
        del sys.argv[i:i + 2]
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        sys.exit(__doc__)
    here = os.path.dirname(os.path.abspath(__file__))
    out = args[1] if len(args) > 1 else os.path.join(here, "..", "app", "src", "main", "assets", "room3d", "pieces")
    os.makedirs(out, exist_ok=True)
    designs_out, all_items = [], []
    stats = collections.Counter()
    with zipfile.ZipFile(args[0]) as z:
        names = z.namelist()
        for theme, fn in SHEETS.items():
            sheet = np.array(Image.open(io.BytesIO(z.read(I32 + fn))).convert("RGBA"))
            comps = components(sheet)
            for c in comps:
                c["id"] = "%s@%d,%d" % (theme, c["x"], c["y"])
            comps = [c for c in comps if 8 <= c["a"].shape[0] <= 130 and 8 <= c["a"].shape[1] <= 110]
            have = set()
            if theme in THEME_NO:
                sg = singles(z, theme, names)
                have = {structure_key(c["a"]) for c in sg}
                # a sheet object the singles lack (they leave out many multi-tile things: sofas, tubs, ...)
                comps = sg + [c for c in comps if structure_key(c["a"]) not in have]
            for r in HAND_RECTS:
                if r[0] == theme:
                    sub = sheet[r[2]:r[2] + r[4], r[1]:r[1] + r[3]].copy()
                    if (sub[..., 3] > 127).any():
                        comps.append(dict(id=r[5], a=bbox_crop(sub)[0], hand=(r[6], r[7])))
            for mem in group_designs(comps):
                rep = mem[0]
                a = rep["a"]
                ov = rep.get("hand") or HAND_FAMILIES.get(rep["id"])
                fam, kind, info = classify(theme, a, ov, sheet_obj="@" in rep["id"])
                d = dict(id=rep["id"], theme=theme, w=int(a.shape[1]), h=int(a.shape[0]), kind=kind, family=fam,
                         variants=len(mem), members=[c["id"] for c in mem], source="single" if "@" not in rep["id"] else "sheet", **info)
                d["_mem"] = mem
                designs_out.append(d)
                # an armchair from every sofa: its seat columns cut down to one (RoomKit.seat)
                if fam == "sofa" and info.get("spec", {}).get("seams"):
                    d2 = dict(d, id=rep["id"] + "_armchair", family="armchair", variants=1, members=[rep["id"]], derived=rep["id"])
                    d2["_mem"] = mem[:1]
                    designs_out.append(d2)
    if sheetdir:
        contact_sheets(designs_out, sheetdir)
    # atlases: the families rooms need, the representative first then up to MAX_VARIANTS - 1 colour variants
    items = []
    for d in designs_out:
        if d.get("derived"):
            d["atlas_of"] = d["derived"]
        elif d.get("reviewed") and d["family"] in ATLAS_FAMILIES or d["family"] == "plant" and d["fill"] < .8 and d["theme"] in ("living", "generic"):
            for i, c in enumerate(d["_mem"][:1 if d["family"] == "plant" else MAX_VARIANTS]):
                items.append(((d["id"], i), c["a"]))
    atlases, where = pack_atlas(items)
    for i, im in enumerate(atlases):
        im.save(os.path.join(out, "atlas%d.png" % i), optimize=True)
    for d in designs_out:
        mem = d.pop("_mem")
        if (d["id"], 0) in where:
            d["atlas"] = [dict(file="atlas%d.png" % where[(d["id"], i)][0], x=where[(d["id"], i)][1], y=where[(d["id"], i)][2])
                          for i in range(min(len(mem), 1 if d["family"] == "plant" else MAX_VARIANTS))]
    for d in designs_out:
        d["members"] = d["members"][:MAX_VARIANTS] if "atlas" in d else len(d["members"])
    for d in designs_out:
        if "atlas" not in d and not d.get("derived"):
            # listed only (no pixels shipped): kind, family and the box split, nothing else
            for k in ("spec", "depth", "height", "footprint", "fill", "members", "source", "reviewed"):
                d.pop(k, None)
            if "split" in d:
                d["split"] = dict(row=d["split"]["row"])
    by = {d["id"]: d for d in designs_out}
    for d in designs_out:
        if d.get("derived"):
            d["atlas"] = by[d["derived"]].get("atlas", [])[:1]
    json.dump(dict(texel_px=32, designs=designs_out), open(os.path.join(out, "pieces.json"), "w"), separators=(",", ":"))
    kinds = collections.Counter(d["kind"] for d in designs_out)
    print("designs", len(designs_out), dict(kinds))
    fams = collections.Counter((d["kind"], d["family"]) for d in designs_out if "atlas" in d)
    print("in atlases:", sum(1 for d in designs_out if "atlas" in d), "designs;", len(items), "pictures;", len(atlases), "atlases")
    for k, v in sorted(fams.items(), key=lambda kv: -kv[1]):
        print("  %-6s %-14s %d" % (k[0], k[1], v))


if __name__ == "__main__":
    main()
