"""Author storefront patterns and lettering on the Timerpg art pixel grid."""
from pathlib import Path
from random import Random
import json

from PIL import Image, ImageDraw
from pixel_font import FONT

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "textures"
OUT.mkdir(exist_ok=True)

def save(image, name):
    image.resize((image.width*2,image.height*2),Image.Resampling.NEAREST).save(OUT/f"{name}.png")

atlas_path = ROOT.parents[1]/"kotlin-street/tools/swatches/atlas"
atlas = Image.open(atlas_path/"atlas.png").convert("RGB")
catalog = json.loads((atlas_path/"atlas.json").read_text())
for key,name in (("plank_horizontal","floorwood"),("roof_tin_brown","roof-brown"),("pavement","sidewalk")):
    x,y,w,h = (catalog[key][field] for field in ("x","y","w","h"))
    save(atlas.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST),name)

attrs = {
    "teal-siding": ("#628b83","#85a696","#4a6c66","#70998e"),
    "ivory": ("#d0c9af","#e2d8bd","#b0b19b","#c5bfaa"),
    "navy": ("#425a61","#5a7277","#2d414b","#4d6870"),
    "cedar": ("#a3835d","#bfa07b","#7f6751","#ae906a"),
    "concrete": ("#9b9e8e","#b5b8a5","#7c8477","#a0aa9a"),
    "roof-blue": ("#46585d","#64777a","#34474e","#526267"),
    "coral": ("#ac5f4e","#d18b6a","#824937","#b86c52"),
    "warm": ("#d4aa6b","#ebc38a","#a88359","#e1b77c"),
}
seeds={"teal-siding":600,"ivory":602,"navy":603,"cedar":604,"concrete":606,
       "roof-blue":607,"coral":608,"warm":609}
for name,c in attrs.items():
    image=Image.new("RGB",(64,64),c[0])
    draw=ImageDraw.Draw(image)
    rng=Random(seeds[name])
    for _ in range(235):
        x,y=rng.randrange(60),rng.randrange(63)
        draw.line((x,y,x+rng.randint(1,4),y),fill=rng.choice(c))
        if rng.randrange(5)==0:
            draw.point((x,y+1),fill=c[2])
    if name in ("teal-siding","cedar","concrete"):
        period={"teal-siding":4,"cedar":5,"concrete":16}[name]
        for t in range(0,64,period):
            a=(t,0,t,63) if name=="cedar" else (0,t,63,t)
            b=(t+1,0,t+1,63) if name=="cedar" else (0,t+1,63,t+1)
            draw.line(a,fill=c[2])
            draw.line(b,fill=c[1])
            if name=="concrete":
                for x in range((t//period%2)*8,64,16):
                    draw.line((x,t,x,min(63,t+period-1)),fill=c[2])
    if name=="roof-blue":
        for x in range(0,64,8):
            draw.line((x,0,x,63),fill=c[2])
            draw.line((x+1,0,x+1,63),fill=c[1])
    save(image,name)

# This surface remains transparent in a standard glTF viewer. Timerpg's masked
# material keeps the highest-opacity reflections and leaves the bulk as open pixels.
pane=Image.new("RGBA",(64,64),(151,184,181,26))
draw=ImageDraw.Draw(pane)
for x0,y0 in ((4,38),(11,42),(37,21),(41,27),(48,32)):
    for d in range(0,13):
        x,y=x0+d,y0-d
        draw.line((x,y,x+2,y),fill=(173,203,191,68 if d%4 else 42))
for x,y in ((2,7),(6,9),(50,55),(51,54),(53,56),(58,51),(12,17),(20,25)):
    draw.point((x,y),fill=(158,190,182,183))
save(pane,"clear-display")

im=Image.new("RGB",(128,128),"#384c50")
draw=ImageDraw.Draw(im)
signs=[
    (0,116,20,"DRIFTLINE","#456f68"),
    (21,70,11,"SURF REPAIR","#456f68"),
    (33,110,19,"TIDEGLASS","#3e555a"),
    (53,62,11,"BOARD CO","#3e555a"),
    (65,87,11,"SURF SHOP","#7c4f44"),
    (77,51,11,"SERVICE","#4f6662"),
    (89,65,10,"RENTALS","#536d6b"),
    (100,53,12,"REPAIRS","#755348"),
]
for y,w,h,line,bg in signs:
    draw.rectangle((0,y,w-1,y+h-1),fill="#b4a07a")
    draw.rectangle((1,y+1,w-2,y+h-2),fill=bg)
    size=2 if h>=19 else 1
    start=(w-(len(line)*6-1)*size)//2
    for i,ch in enumerate(line):
        for dy,row in enumerate(FONT.get(ch,())):
            for dx,bit in enumerate(row):
                if bit=="1":
                    x=start+(i*6+dx)*size
                    py=y+(h-7*size)//2+dy*size
                    draw.rectangle((x,py,x+size-1,py+size-1),fill="#efe3c2")
save(im,"sign")
