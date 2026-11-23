"""Generate the Lark Bend textures and repaintable signs on the art-pixel grid."""
import json
from pathlib import Path
from random import Random

from PIL import Image, ImageDraw
from pixel_font import FONT

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "textures"
OUT.mkdir(exist_ok=True)
LIB = ROOT.parents[1] / "kotlin-street/tools/swatches/atlas"
catalogue = json.loads((LIB / "atlas.json").read_text())
atlas = Image.open(LIB / "atlas.png").convert("RGB")
SWATCHES = {
    "wood": "plank_vertical",
    "concrete": "roof_membrane_tan",
    "steel": "roof_tin_brown",
    "roof": "roof_membrane_grey",
    "brick": "brick_red",
    "yard": "asphalt_worn",
    "gravel": "ballast",
}

def save(name, art):
    art.resize((art.width*2, art.height*2), Image.Resampling.NEAREST).save(OUT / f"{name}.png")

def swatch(key):
    entry = catalogue[key]
    x, y, w, h = (entry[k] for k in ("x", "y", "w", "h"))
    return atlas.crop((x, y, x+w, y+h)).resize((w//2, h//2), Image.Resampling.NEAREST)

for name, key in SWATCHES.items():
    if name not in ("concrete", "roof", "steel"):
        save(name, swatch(key))

# Paint the slip-form streaks and the dull metal/roof finishes from shared swatch colours.
for name, mix, offsets in (
    ("concrete", (206, 203, 187), (-14, -7, 0, 6)),
    ("roof", (91, 103, 105), (-12, -6, 0, 6)),
    ("steel", (101, 100, 81), (-15, -6, 0, 9)),
):
    ref = max(swatch(SWATCHES[name]).getcolors(), key=lambda p: p[0])[1]
    base = tuple(round(.21*a + .79*b) for a, b in zip(ref, mix))
    colours = [tuple(c+shift for c in base) for shift in offsets]
    surface = Image.new("RGB", (64, 64), colours[2])
    d = ImageDraw.Draw(surface)
    rng = Random({"concrete":211, "roof":223, "steel":233}[name])
    for _ in range(155):
        x, y = rng.randrange(64), rng.randrange(64)
        streak = rng.randint(1, 11 if name == "concrete" else 4)
        dx, dy = (0, streak) if name == "concrete" else (streak, 0)
        d.line((x, y, x+dx, y+dy), fill=rng.choice(colours))
    if name == "concrete":
        d.line((0, 13, 63, 13), fill=colours[1])
        d.line((0, 14, 63, 14), fill=colours[3])
    if name == "steel":
        for x in range(0,64,16):
            d.line((x, 0, x, 63), fill=colours[0])
            d.line((x+1, 0, x+1, 63), fill=colours[3])
    save(name, surface)

glass = Image.new("RGB", (16, 16), "#71868b")
mask = Image.new("RGB", glass.size)
g, m = ImageDraw.Draw(glass), ImageDraw.Draw(mask)
g.rectangle((0,0,15,15), fill="#333e40")
g.rectangle((1,1,14,14), fill="#74878b")
g.line((1,1,14,1), fill="#b4b7a3")
g.line((2,10,7,5), fill="#91a19c", width=2)
g.line((9,12,13,8), fill="#91a19c")
m.rectangle((1,1,14,14), fill="#dab077")
save("glass", glass)
save("pane-mask", mask)

letters = Image.new("RGBA", (128, 128))
draw = ImageDraw.Draw(letters)
regions = {}
rng = Random(1925)
for name, y, w, h, wording, scale, board, ink in (
    ("office", 0, 49, 12, "OFFICE", 1, "#4b645a", "#efdcaa"),
    ("wood", 14, 96, 19, "LARK BEND", 2, "#725548", "#ead3a7"),
    ("name", 35, 60, 15, "LARK BEND", 1, None, "#665e50"),
    ("grain", 52, 60, 15, "GRAIN", 2, None, "#665e50"),
    ("coop", 69, 60, 15, "CO-OP", 2, None, "#526653"),
    ("scale", 86, 49, 12, "SCALE", 1, "#4b645a", "#efdcaa"),
):
    regions[name] = [0, y*2, w*2, h*2]
    if board:
        draw.rectangle((0, y, w-1, y+h-1), fill="#393e38")
        draw.rectangle((1, y+1, w-2, y+h-2), fill=board)
        draw.line((1, y+1, w-2, y+1), fill="#8d8067")
    columns = (0, 1, 1, 2, 3, 3, 4) if scale == 2 else tuple(range(5))
    gap = 2 if scale == 2 else 1
    stride = len(columns) + gap
    width = len(wording)*stride-gap
    left = (w-width)//2
    height_scale = 2 if name == "name" else scale
    assert min(left, w-width-left) >= 2 and 7*height_scale <= h
    for i, ch in enumerate(wording):
        for j, row in enumerate(FONT.get(ch, ())):
            for k, col in enumerate(columns):
                if row[col] == "1":
                    x = left+stride*i+k
                    z = y+(h-7*height_scale)//2+j*height_scale
                    draw.rectangle((x, z, x, z+height_scale-1), fill=ink)
                    if not board and rng.random() < .08:
                        draw.point((x,z), fill="#7f7966")
save("signs", letters)
(OUT / "sign-regions.json").write_text(json.dumps(regions, indent=2)+"\n")
