"""Generate deterministic, whole-art-pixel textures for the Union Arch."""
import json
from pathlib import Path
from random import Random

from PIL import Image, ImageDraw
from pixel_font import FONT

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "textures"
OUT.mkdir(exist_ok=True)
ATLAS = ROOT.parents[1] / "kotlin-street/tools/swatches/atlas"


def save(name, image):
    image.resize((image.width * 2, image.height * 2), Image.Resampling.NEAREST).save(OUT / f"{name}.png")


atlas = Image.open(ATLAS / "atlas.png").convert("RGB")
swatches = json.loads((ATLAS / "atlas.json").read_text())
for name in ("pavement", "plank_horizontal"):
    x, y, w, h = [swatches[name][k] for k in ("x", "y", "w", "h")]
    save(name, atlas.crop((x, y, x+w, y+h)).resize((w//2, h//2), Image.Resampling.NEAREST))

rng = Random(1929)
palettes = {
    "ashlar": ("#b7a990", "#c5b69c", "#9f927c", "#ad9e86"),
    "carving": ("#d1c2a0", "#dfd1b3", "#b6a787", "#c2b294"),
    "granite": ("#807b71", "#9b9383", "#625f56", "#746d64"),
    "bronze": ("#56615a", "#758072", "#394743", "#627269"),
    "leaf": ("#677759", "#738665", "#505e49", "#5a6a4d"),
    "grass": ("#718065", "#7a886b", "#67755a", "#6c795e"),
    "lamp": ("#dfba79", "#f4dda1", "#a78152", "#ceb17c"),
    "copper": ("#64736a", "#819187", "#4d5a54", "#566961")
}
for name, (base, light, dark, mid) in palettes.items():
    im = Image.new("RGB", (128, 128), base)
    draw = ImageDraw.Draw(im)
    if name in ("ashlar", "granite", "copper"):
        for row in range(8):
            for x in range(-32 + 16*(row % 2), 128, 32):
                y = row*16
                draw.rectangle((x, y, x+31, y+15), fill=rng.choice([base, base, mid]))
                draw.line((x, y+15, x+31, y+15), fill=dark)
                draw.line((x, y, x+31, y), fill=light)
                draw.line((x, y+1, x, y+14), fill=dark)
                draw.line((x+1, y+1, x+1, y+13), fill=light)
    for _ in range(160):
        x, y = rng.randrange(2, 122), rng.randrange(2, 126)
        if name in ("ashlar", "granite", "copper") and y % 16 in (0, 15):
            continue
        draw.line((x, y, x+rng.randrange(1, 5), y), fill=rng.choice([light, dark, mid, mid]))
        if rng.random() < .28:
            draw.point((x+1, y-1), fill=mid)
    save(name, im)

im = Image.new("RGB", (128, 16), "#78694f")
draw = ImageDraw.Draw(im)
draw.rectangle((1, 1, 126, 14), fill="#a69371", outline="#ccb992")
draw.line((2, 13, 125, 13), fill="#645944")
label = "UNION ARCH"
x = (128 - (len(label)*6-1))//2
for ch in label:
    for y, row in enumerate(FONT.get(ch, [])):
        for i, cell in enumerate(row):
            if cell == "1":
                draw.point((x+i, y+5), fill="#655942")
                draw.point((x+i, y+4), fill="#433f34")
    x += 6
save("name", im)
