"""Create Alder Bend's original art and cut the shared Timerpg swatches."""
import json
from pathlib import Path
from random import Random

from PIL import Image, ImageDraw
from pixel_font import FONT

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "textures"
OUT.mkdir(exist_ok=True)
LIB = ROOT.parents[1] / "kotlin-street/tools/swatches/atlas"
ATLAS = Image.open(LIB/"atlas.png").convert("RGB")
CAT = json.loads((LIB/"atlas.json").read_text())

def art(name):
    x,y,w,h = (CAT[name][key] for key in ("x","y","w","h"))
    return ATLAS.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST)

def save(name, image):
    image.resize((image.width*2,image.height*2),Image.Resampling.NEAREST).save(OUT/f"{name}.png")

for name, original in (("wood","plank_horizontal"),("brick","brick_red"),("gravel","ballast")):
    save(name,art(original))

palette = {
    "grass": ("roof_membrane_green", (97,122,73)),
    "dirt": ("roof_membrane_tan", (167,117,81)),
    "roof": ("roof_membrane_green", (51,80,73)),
    "steel": ("roof_membrane_grey", (76,81,73)),
    "aluminium": ("roof_membrane_grey", (148,155,143)),
    "chalk": ("roof_membrane_tan", (220,210,181)),
}
for index, (name,(source,tint)) in enumerate(palette.items()):
    dominant = max(art(source).getcolors(), key=lambda p:p[0])[1]
    base = tuple(round(a*.12+b*.88) for a,b in zip(dominant,tint))
    colours = [tuple(max(0,min(255,v+d)) for v in base) for d in (-12,-6,0,6)]
    image = Image.new("RGB",(64,64),colours[2])
    draw = ImageDraw.Draw(image)
    rng = Random(351+index)
    for _ in range(210):
        x,y = rng.randrange(64),rng.randrange(64)
        d = rng.randrange(4)
        if name.startswith("grass") and d > 1:
            draw.line((x,y,x+2,y),fill=colours[d])
            draw.point((x,y-1),fill=colours[1])
        else:
            draw.line((x,y,x+rng.randrange(1,4),y),fill=colours[d])
    if name in ("roof","steel","aluminium"):
        for y in range(0,64,16 if name=="roof" else 32):
            draw.line((0,y,63,y),fill=colours[0])
            draw.line((0,y+1,63,y+1),fill=colours[3])
    save(name,image)

# The renovation adds alternating 5 m mowing passes without changing texel density.
with Image.open(OUT/"grass.png") as source:
    turf_tile = source.resize((64,64),Image.Resampling.NEAREST).convert("RGB")
renovated = Image.new("RGB",(128,128))
lighter = turf_tile.point(lambda v: min(255,v+8))
for y,tile in ((0,turf_tile),(64,lighter)):
    for x in (0,64):
        renovated.paste(tile,(x,y))
save("grass_mown",renovated)

glass = Image.new("RGB",(16,16),"#384840")
mask = Image.new("RGB",(16,16))
glass_draw = ImageDraw.Draw(glass)
glass_draw.rectangle((1,1,14,14),fill="#82918a")
glass_draw.line((2,1,14,1),fill="#bbc3a5")
glass_draw.line((3,12,11,4),fill="#9da695",width=2)
ImageDraw.Draw(mask).rectangle((1,1,14,14),fill="#eac28b")
save("glass",glass)
save("pane-mask",mask)

signs = Image.new("RGB",(256,256),"#394739")
regions = {}
draw = ImageDraw.Draw(signs)

def plate(key,x,y,w,h,wording,background,ink,scale=1):
    regions[key] = [x*2,y*2,w*2,h*2]
    draw.rectangle((x,y,x+w-1,y+h-1), fill="#343c32")
    draw.rectangle((x+1,y+1,x+w-2,y+h-2), fill=background)
    draw.line((x+1,y+1,x+w-2,y+1),fill="#a49872")
    lines = wording.split("|")
    text_h = (len(lines)*9-2)*scale
    for i,words in enumerate(lines):
        text_w = (len(words)*6-1)*scale
        left = (w-text_w)//2
        assert min(left,w-text_w-left) >= 2 and h-text_h >= 2, (key,words)
        for j,ch in enumerate(words):
            for row,pixels in enumerate(FONT.get(ch,())):
                for col,bit in enumerate(pixels):
                    if bit=="1":
                        px = x+left+(j*6+col)*scale
                        py = y+(h-text_h)//2+(i*9+row)*scale
                        draw.rectangle((px,py,px+scale-1,py+scale-1), fill=ink)

plate("name",0,0,240,25,"ALDER BEND BALLPARK","#466052","#e3d7a5",2)
plate("tickets",0,28,59,13,"TICKETS","#6c4844","#eee0ad")
plate("concession",63,28,100,13,"REFRESHMENTS","#6c4844","#eee0ad")
plate("board_old",0,45,148,41,"VISITOR  0|ALDER    0|INNING   1","#4e6450","#e7d7a4")
plate("board_new",0,89,168,53,"GUEST  HOME|00     00|INNING 01","#354545","#dccd8d",2)
plate("ad_coal",0,144,125,23,"PINE COAL & ICE","#71604a","#e6d5a6")
plate("ad_feed",128,144,124,23,"LARK BEND FEED","#a18a61","#464d40")
plate("ad_tires",0,170,125,23,"MILL ROAD TIRES","#755448","#e6d5a6")
plate("ad_soda",128,170,124,23,"WILLOW ROOT SODA","#596a53","#e8d8ad")
save("signs",signs)
(OUT/"sign-regions.json").write_text(json.dumps(regions,indent=2)+"\n")
