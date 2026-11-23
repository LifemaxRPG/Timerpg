"""Paint the station's pixel signs, window masks and missing finish swatches."""
import json
from pathlib import Path
from random import Random

from PIL import Image, ImageDraw
from pixel_font import FONT

ROOT = Path(__file__).resolve().parent
LIB = ROOT.parents[1] / "kotlin-street/tools/swatches/atlas"
OUT = ROOT / "textures"
OUT.mkdir(exist_ok=True)
ATLAS = Image.open(LIB / "atlas.png").convert("RGB")
RECTS = json.loads((LIB / "atlas.json").read_text())


def save(name, im):
    im.resize((im.width * 2, im.height * 2), Image.Resampling.NEAREST).save(OUT / f"{name}.png")


def swatch(key):
    x, y, w, h = (RECTS[key][field] for field in ("x", "y", "w", "h"))
    return ATLAS.crop((x, y, x + w, y + h)).resize((w // 2, h // 2), Image.Resampling.NEAREST)


for name, key in (("brick", "brick_red"), ("asphalt", "asphalt_worn")):
    save(name, swatch(key))


def finish(name, key, target, course, seed):
    base = swatch(key)
    common = max(base.getcolors())[1]
    palette = [tuple(max(0, min(255, int(a * .31 + b * .69) + d))
                     for a, b in zip(common, target)) for d in (-25, -10, 0, 12)]
    im = Image.new("RGB", (32, 32), palette[2])
    d = ImageDraw.Draw(im)
    rng = Random(seed)
    for _ in range(120):
        x, y = rng.randrange(32), rng.randrange(32)
        d.line((x, y, x + rng.randrange(1, 4), y), fill=rng.choice(palette[1:]))
    if course:
        for y in range(0, 32, course):
            d.line((0, y, 31, y), fill=palette[0])
            d.line((5, y + 1, 21, y + 1), fill=palette[3])
            for x in range(y % 12, 32, 16):
                d.line((x, y, x, y + course - 1), fill=palette[0])
    save(name, im)


finish("stone", "pavement", (207, 187, 148), 8, 22)
finish("block", "block_courses", (154, 153, 135), 8, 65)
finish("wood", "plank_horizontal", (99, 59, 43), 4, 29)
finish("floor", "pavement", (133, 146, 125), 8, 15)
finish("metal", "wall_slate_dark", (63, 80, 82), 0, 73)
finish("roof", "roof_membrane_grey", (96, 97, 98), 16, 37)

# All mullions and dark edges in the base texture are black in the emission map.
for name, glass, light, alpha in (
    ("sash", "#577481", "#f0c08a", 255),
    ("cell", "#576876", "#8d7c73", 255),
    ("vestibule", "#8eabb1", "#c0a179", 51),
):
    im = Image.new("RGBA", (16, 16), "#2d363b")
    mask = Image.new("RGB", im.size, "black")
    for y in range(16):
        for x in range(16):
            if x not in (0, 1, 8, 15) and y not in (0, 1, 8, 15):
                rgb = bytes.fromhex(glass[1:])
                shade = 12 if (x + y * 2) % 11 < 3 else 0
                im.putpixel((x, y), tuple(i + shade for i in rgb) + (alpha,))
                mask.putpixel((x, y), tuple(bytes.fromhex(light[1:])))
            elif x == 1 or y == 1:
                im.putpixel((x, y), (113, 111, 98, 255))
    save(name, im)
    save(name + "-mask", mask)


def lettering(draw, text, x, y, color, scale=1):
    for i, char in enumerate(text):
        for r, line in enumerate(FONT.get(char, ())):
            for c, bit in enumerate(line):
                if bit == "1":
                    xx, yy = x + (6 * i + c) * scale, y + r * scale
                    draw.rectangle((xx, yy, xx + scale - 1, yy + scale - 1), fill=color)


signs = Image.new("RGB", (256, 128), "#303d45")
d = ImageDraw.Draw(signs)
regions = {}


def panel(key, rect, label, paper="#343e49", ink="#e2cfa2", scale=1):
    x, y, w, h = rect
    d.rectangle((x, y, x + w - 1, y + h - 1), fill="#222c32")
    d.rectangle((x + 1, y + 1, x + w - 2, y + h - 2), fill=paper)
    d.line((x + 1, y + 1, x + w - 2, y + 1), fill="#8d8e81")
    if label:
        lettering(d, label, x + (w - (6 * len(label) - 1) * scale) // 2,
                  y + (h - 7 * scale) // 2, ink, scale)
    regions[key] = [2 * n for n in rect]


panel("name", (0, 0, 94, 13), "BRIARFORD", "#343e49")
for key, x, y, text in (
    ("police", 0, 16, "POLICE"), ("jail", 52, 16, "TOWN JAIL"),
    ("booking", 110, 16, "BOOKING"), ("interview", 0, 32, "INTERVIEW"),
    ("records", 76, 32, "RECORDS"), ("desk", 140, 32, "DESK"),
    ("garage", 0, 48, "GARAGE"), ("sally", 88, 48, "SALLY PORT"),
    ("wanted", 0, 66, "WANTED"), ("chief", 52, 66, "CHIEF"),
    ("holding", 104, 66, "HOLDING"),
):
    panel(key, (x, y, max(30, 6 * len(text) + 3), 11), text, "#343e49")
panel("paper_1", (164, 65, 11, 15), None, "#cbb68e")
panel("paper_2", (179, 65, 11, 15), None, "#cbb68e")
panel("height", (0, 82, 24, 36), None, "#c9c3a8")
# Booking grid at integral physical heights, with a legible compact numeral face.
numerals = {
    2: ("111","001","111","100","111"),
    3: ("111","001","111","001","111"),
    4: ("101","101","111","001","001"),
    5: ("111","100","111","001","111"),
    6: ("111","100","111","101","111"),
    7: ("111","001","010","010","010"),
}
for num, glyph in numerals.items():
    yy = 82 + round((.10 + 36 * .0776 - num * .3048) / .0776)
    d.line((3, yy, 15, yy), fill="#5b574b")
    for i, line in enumerate(glyph):
        for j, bit in enumerate(line):
            if bit == "1":
                d.point((17 + (num % 2) * 3 + j, yy - 2 + i), fill="#535046")
save("signs", signs)
(OUT / "sign-regions.json").write_text(json.dumps(regions, indent=2) + "\n")
globe = Image.new("RGB", (16, 16), "#f5d595")
d = ImageDraw.Draw(globe)
d.rectangle((0, 0, 15, 15), outline="#4f5955")
lettering(d, "PD", 2, 4, "#365064")
save("globe", globe)
