"""Paint the shared nearest-filtered prop atlas at Timerpg's physical art-pixel scale."""
from pathlib import Path
from random import Random
import json

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "textures"
OUT.mkdir(exist_ok=True)
TILE = 64  # Art pixels. Every sample becomes a 2×2 texel block.
COLORS = {
    "cream": ("#ddd0a9", "#eee2bd", "#b4a989", "#c8bd99"),
    "coral": ("#bb6753", "#d7896e", "#93483f", "#c87859"),
    "teal": ("#579591", "#78afa4", "#356d70", "#64a39c"),
    "navy": ("#395969", "#547583", "#2d4250", "#486574"),
    "yellow": ("#c8a253", "#e1bf6e", "#a17b40", "#d2ab54"),
    "orange": ("#c87c4b", "#dd9c66", "#9e583d", "#d28c56"),
    "pink": ("#c48288", "#dfa3a0", "#9a636f", "#d29395"),
    "sage": ("#849379", "#a6aa87", "#65735e", "#95a089"),
    "white": ("#e3dbbd", "#f0e9cc", "#b9b7a0", "#d5cfb2"),
    "rubber": ("#454c4c", "#626460", "#333b3c", "#505852"),
    "metal": ("#747c77", "#a4a699", "#5c645c", "#92998b"),
    "sand": ("#c8ab7e", "#dec198", "#a88d6a", "#d2b689"),
    "rope": ("#997954", "#c49d70", "#795b45", "#b49064"),
    "canvas": ("#b39e77", "#c9b68c", "#937c5e", "#bea681"),
}
PATTERNS = ("towel_coral", "towel_teal", "longboard", "fishboard", "shortboard", "bodyboard", "chair", "lounger")

def paint(name, colors, seed):
    im = Image.new("RGB", (TILE, TILE), colors[0])
    draw = ImageDraw.Draw(im)
    rng = Random(seed)
    for _ in range(195):
        x, y = rng.randrange(TILE-3), rng.randrange(TILE)
        draw.line((x,y,x+rng.randint(0,3),y), fill=rng.choice(colors))
    if name in ("canvas","rope"):
        for y in range(0, TILE, 3):
            for x in range(y % 2, TILE, 2):
                draw.point((x,y), fill=colors[3])
    return im

atl = Image.new("RGB", (TILE*8,TILE*4))
tiles = {}
def add(name, im):
    index = len(tiles)
    x,y = index % 8 * TILE, index // 8 * TILE
    atl.paste(im,(x,y))
    tiles[name] = [x*2,y*2,TILE*2,TILE*2]

for i,(name,colors) in enumerate(COLORS.items()):
    add(name,paint(name,colors,386+i))

swatches = ROOT.parents[1]/"kotlin-street/tools/swatches/atlas"
entry = json.loads((swatches/"atlas.json").read_text())["plank_horizontal"]
x,y,w,h = [entry[k] for k in ("x","y","w","h")]
source = Image.open(swatches/"atlas.png").convert("RGB")
sample = source.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST)
wood = Image.new("RGB", (TILE,TILE))
for a in range(0,TILE,sample.width):
    for b in range(0,TILE,sample.height):
        wood.paste(sample,(a,b))
add("wood",wood)

for i,name in enumerate(PATTERNS):
    base = {"towel_coral":"coral","towel_teal":"teal","longboard":"cream","fishboard":"sage",
            "shortboard":"cream","bodyboard":"yellow","chair":"canvas","lounger":"teal"}[name]
    im = paint(name,COLORS[base],910+i)
    d = ImageDraw.Draw(im)
    a = COLORS
    if name.startswith("towel"):
        for y in (22,24,37,39):
            d.rectangle((0,y,63,y),fill=a["cream"][0])
        if name=="towel_teal":
            d.rectangle((0,28,63,34),fill=a["navy"][0])
            d.line((0,31,63,31),fill=a["cream"][0])
        else:
            for y in (25,35):
                d.line((0,y,63,y),fill=a["pink"][1])
    elif name=="longboard":
        for x, width, c in [(28,3,"teal"),(31,1,"rope"),(32,3,"teal")]:
            d.rectangle((x,0,x+width-1,63),fill=a[c][0])
        for y in range(22,35,3):
            d.line((24,y,26,y),fill=a["white"][1])
    elif name=="fishboard":
        d.rectangle((0,22,63,26),fill=a["teal"][0])
        d.rectangle((0,27,63,28),fill=a["cream"][0])
        d.line((32,0,32,63),fill=a["rope"][0])
    elif name=="shortboard":
        d.polygon([(19,37),(39,19),(45,19),(25,37)],fill=a["coral"][0])
        d.polygon([(22,39),(42,21),(44,21),(24,39)],fill=a["yellow"][0])
    elif name=="bodyboard":
        d.polygon([(0,34),(63,21),(63,31),(0,44)],fill=a["navy"][0])
        d.line((0,34,63,21),fill=a["cream"][0])
    else:
        for x in range(17,46,4):
            d.rectangle((x,0,x+1,63),fill=a["cream"][0] if name=="chair" else a["navy"][0])
    add(name,im)

atl.resize((atl.width*2,atl.height*2),Image.Resampling.NEAREST).save(OUT/"beach-atlas.png")
(OUT/"tiles.json").write_text(json.dumps(tiles,indent=2)+"\n")
