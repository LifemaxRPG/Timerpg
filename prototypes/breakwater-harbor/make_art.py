"""Paint original marina swatches and signs at one sample per art pixel."""
from pathlib import Path
from random import Random
import json

from PIL import Image, ImageDraw
from pixel_font import FONT

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "textures"
OUT.mkdir(exist_ok=True)
GRID = 2

def save(name, image):
    image.resize((image.width * GRID, image.height * GRID), Image.Resampling.NEAREST).save(OUT / f"{name}.png")

atlas_dir = ROOT.parents[1] / "kotlin-street/tools/swatches/atlas"
entry = json.loads((atlas_dir / "atlas.json").read_text())["plank_horizontal"]
x, y, w, h = (entry[key] for key in ("x", "y", "w", "h"))
atlas = Image.open(atlas_dir / "atlas.png").convert("RGB")
save("dock", atlas.crop((x, y, x + w, y + h)).resize((w // 2, h // 2), Image.Resampling.NEAREST))

palette = {
    "concrete": ("#9b9b8e", "#b3b2a2", "#818376", "#8b8d80"),
    "asphalt": ("#555951", "#62645a", "#484e46", "#51574e"),
    "steel": ("#414e50", "#566263", "#353e41", "#495456"),
    "teal": ("#4e6e6a", "#6b8880", "#3c5958", "#537570"),
    "cream": ("#d1c9b2", "#e2d8bf", "#aaa68e", "#bdb7a0"),
    "stone": ("#626761", "#7e8276", "#4e5650", "#6f7469"),
    "orange": ("#b58548", "#d4ac65", "#876539", "#9f773f"),
    "glass": ("#536d70", "#79928e", "#3e565a", "#637d7e"),
    "warm": ("#cc9f60", "#e3ba7c", "#ab8246", "#d2a669"),
    "red": ("#985b4a", "#b46e57", "#744b43", "#a16553"),
}
for idx, (name, colors) in enumerate(palette.items()):
    image = Image.new("RGB", (64, 64), colors[0])
    draw = ImageDraw.Draw(image)
    rng = Random(614 + idx)
    for _ in range(180):
        px, py = rng.randrange(61), rng.randrange(64)
        draw.line((px, py, px + rng.randrange(1, 4), py), fill=rng.choice(colors))
    if name in ("teal", "steel"):
        for px in range(0, 64, 4 if name == "teal" else 8):
            draw.line((px, 0, px, 63), fill=colors[2])
            draw.line((px + 1, 0, px + 1, 63), fill=colors[1])
    if name in ("concrete", "cream"):
        for py in range(0, 64, 8):
            draw.line((0, py, 63, py), fill=colors[2])
            draw.line((0, py + 1, 63, py + 1), fill=colors[1])
    if name == "glass":
        draw.polygon([(4, 60), (51, 8), (56, 8), (9, 60)], fill=colors[3])
    save(name, image)

image = Image.new("RGB", (128, 64), "#304c4e")
draw = ImageDraw.Draw(image)
for y, width, height, label in [(0, 118, 16, "BREAKWATER MARINA"), (17, 73, 11, "DRY STACK"),
                                (29, 56, 11, "HARBOR"), (41, 49, 11, "LAUNCH")]:
    draw.rectangle((0, y, width - 1, y + height - 1), fill="#e5d7b2")
    draw.rectangle((1, y + 1, width - 2, y + height - 2), fill="#38595b")
    left = (width - (len(label) * 6 - 1)) // 2
    for i, ch in enumerate(label):
        for dy, row in enumerate(FONT.get(ch, ())):
            for dx, pix in enumerate(row):
                if pix == "1":
                    draw.point((left + i * 6 + dx, y + (height - 7) // 2 + dy), fill="#efe2c2")
save("signs", image)
