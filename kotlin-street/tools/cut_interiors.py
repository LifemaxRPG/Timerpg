#!/usr/bin/env python3
"""Cuts the 3D interiors trial's pictures out of LimeZu's Modern Interiors pack (docs/INTERIORS_3D.md).

    python3 tools/cut_interiors.py <Modern_Interiors_v41.4.zip> [out-dir]

Reads the pack's zip directly (rebuild it with packs/join.sh into a scratch folder, never into the repo) and writes one
PNG a piece, as the pack draws it (the 32 px scale, the town's: 1 px = one texel, 26.29 street units a 32 px tile), into
out-dir (default app/src/main/assets/room3d/diner). How each picture becomes geometry is RoomKit's (scene3d/RoomKit.kt):
this tool only cuts, so the shipped pictures are exactly the artist's pixels.
"""
import io
import os
import sys
import zipfile

from PIL import Image

I32 = "1_Interiors/32x32/"
SINGLES = I32 + "Theme_Sorter_Shadowless_Singles_32x32/"
THEMES = {
    "kitchen": "12_Kitchen_Singles_Shadowless_32x32/Kitchen_Singles_Shadowless_32x32_%d.png",
    "icecream": "24_Ice_Cream_Shop_Shadowless_32x32/Ice_Cream_Shop_Singles_Shadowless_32x32_%d.png",
    "basement": "14_Basement_Singles_Shadowless_32x32/Basement_Singles_Shadowless_32x32_%d.png",
    "living": "2_Living_Room_Singles_Shadowless_32x32/Living_Room_Singles_Shadowless_32x32_%d.png",
    "condo": "26_Condominium_Singles_Shadowless_32x32/Condominium_Singles_Shadowless_32x32_%d.png",
    # the 16 px version of the same pack: the artist's own smaller drawing, for things the 32 px art draws far too big
    # for the room (a plate as wide as half a table); twice the texel density of the rest, but no texel resampled
    "kitchen16": "../../16x16/Theme_Sorter_Shadowless_Singles/12_Kitchen_Singles_Shadowless/Kitchen_Singles_Shadowless_%d.png",
}
GENERIC = I32 + "Theme_Sorter_Shadowless_32x32/1_Generic_Shadowless32x32.png"
WALLS = I32 + "Room_Bulder_subfiles_32x32/Room_Builder_Walls_32x32.png"
FLOORS = I32 + "Room_Bulder_subfiles_32x32/Room_Builder_Floors_32x32.png"

# name: (theme single, number) or (sheet, x, y, w, h)
PIECES = {
    # box furniture: the 3/4 picture's top and front laid on a box
    "counter": ("icecream", 100),
    "display": ("icecream", 57),
    "fridge": ("kitchen", 158),
    "stove": ("kitchen", 149),
    "kitchenCounter": ("kitchen", 121),
    "jukebox": ("basement", 218),
    "sideTable": ("living", 65),
    # few-part shapes
    "booth": (GENERIC, 192, 344, 64, 64),
    "clothTable": ("kitchen", 336),
    "roundTable": ("basement", 2),
    "stool": ("basement", 105),
    "chair": ("kitchen", 280),
    "lamp": ("living", 75),
    # billboards and flat pieces
    "plantBig": ("living", 13),
    "palm": ("living", 14),
    "plantSmall": ("living", 16),
    "coffee": ("kitchen", 178),
    "plateFood": ("kitchen16", 385),
    "plateFood2": ("kitchen16", 387),
    "picture1": ("kitchen", 268),
    "picture2": ("kitchen", 270),
    "window": (GENERIC, 288, 928, 64, 64),
    "door": ("condo", 85),
    # the room's shell: a wall's 3-tile block (left end, middle, right end; its cap the top) and a floor's
    "wall": (WALLS, 22 * 32, 24 * 32, 96, 64),
    "floor": (FLOORS, 12 * 32, 4 * 32, 96, 64),
}


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    here = os.path.dirname(os.path.abspath(__file__))
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(here, "..", "app", "src", "main", "assets", "room3d", "diner")
    os.makedirs(out, exist_ok=True)
    with zipfile.ZipFile(sys.argv[1]) as z:
        def load(path):
            return Image.open(io.BytesIO(z.read(path))).convert("RGBA")
        for name, src in PIECES.items():
            if len(src) == 2:
                im = load(os.path.normpath(SINGLES + THEMES[src[0]] % src[1]))
            else:
                sheet, x, y, w, h = src
                im = load(sheet).crop((x, y, x + w, y + h))
            im.save(os.path.join(out, name + ".png"), optimize=True)
            print("%-15s %3dx%-3d" % (name, im.width, im.height))


if __name__ == "__main__":
    main()
