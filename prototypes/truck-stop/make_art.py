"""Paint Cedar Relay's pixel materials and interchangeable sign plates."""
from pathlib import Path
from random import Random
import json

from PIL import Image, ImageDraw
from pixel_font import FONT

ROOT=Path(__file__).resolve().parent
OUT=ROOT/"textures"
OUT.mkdir(exist_ok=True)
LIB=ROOT.parents[1]/"kotlin-street/tools/swatches/atlas"
CAT=json.loads((LIB/"atlas.json").read_text())
ATLAS=Image.open(LIB/"atlas.png").convert("RGB")


def swatch(key):
    x,y,w,h=(CAT[key][k] for k in ("x","y","w","h"))
    return ATLAS.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST)


def save(name, image):
    image.resize((image.width*2,image.height*2),Image.Resampling.NEAREST).save(OUT/f"{name}.png")


for name,key in (("brick","brick_red"),("paving","pavement"),("wood","plank_horizontal")):
    save(name,swatch(key))

specs=(
    ("asphalt","asphalt_worn",(81,84,76),121,"wear"),
    ("roof","roof_membrane_grey",(113,117,111),72,"seams"),
    ("trim","roof_membrane_green",(48,63,58),19,"plain"),
    ("white","roof_membrane_tan",(207,198,168),52,"plain"),
    ("ochre","roof_tin_brown",(167,117,70),76,"plain"),
    ("rust","roof_tin_red",(144,77,59),21,"plain"),
    ("teal","roof_membrane_green",(60,116,108),32,"plain"),
    ("floor","pavement",(158,163,149),51,"tile"),
)
for name,ref,target,seed,pattern in specs:
    base=max(swatch(ref).getcolors(),key=lambda item:item[0])[1]
    tint=tuple(round(.21*a+.79*b) for a,b in zip(base,target))
    colours=[tuple(max(0,min(255,c+s)) for c in tint) for s in (-14,-7,0,7)]
    image=Image.new("RGB",(64,64),colours[2])
    d=ImageDraw.Draw(image)
    rng=Random(seed)
    for _ in range(150):
        x,y=rng.randrange(64),rng.randrange(64)
        d.line((x,y,min(63,x+rng.randrange(1,6)),y),fill=rng.choice(colours))
    if pattern=="seams":
        for yy in (0,32):
            d.line((0,yy,63,yy),fill=colours[0])
            d.line((0,yy+1,63,yy+1),fill=colours[3])
            x=15 if yy==0 else 47
            d.line((x,yy,x,yy+31),fill=colours[1])
    if pattern=="tile":
        for i in range(0,64,8):
            d.line((0,i,63,i),fill=colours[1])
            d.line((i,0,i,63),fill=colours[1])
    if pattern=="wear":
        for x,y in ((13,38),(46,13),(44,58)):
            d.line((x,y,x+4,y-3,x+7,y-3,x+14,y+2),fill=colours[0])
    save(name,image)

glass=Image.new("RGBA",(32,32),(48,61,56,255))
pane=Image.new("RGB",(32,32))
g,p=ImageDraw.Draw(glass),ImageDraw.Draw(pane)
g.rectangle((1,1,30,30),fill=(112,135,132,65))
g.line((3,17,14,6),fill=(172,183,162,126),width=2)
g.line((17,23,25,15),fill=(153,167,154,110))
p.rectangle((1,1,30,30),fill="#e1b783")
save("glass",glass)
save("pane-mask",pane)

atlas=Image.new("RGB",(256,256),"#364742")
mask=Image.new("RGB",atlas.size)
paint,glow=ImageDraw.Draw(atlas),ImageDraw.Draw(mask)
regions={}
light_regions={"pole_old","pole_new","store_old","store_new","diesel_old","diesel_new","car_old","car_new"}

def sign(key,x,y,w,h,lines,base,ink,scale=1):
    regions[key]=[x*2,y*2,w*2,h*2]
    paint.rectangle((x,y,x+w-1,y+h-1),fill="#303f38")
    paint.rectangle((x+1,y+1,x+w-2,y+h-2),fill=base)
    paint.line((x+1,y+1,x+w-2,y+1),fill="#b9a47d")
    lines=lines.split("|")
    height=(len(lines)*8-1)*scale
    assert h>=height+2,key
    for i,line in enumerate(lines):
        width=(len(line)*6-1)*scale
        left=(w-width)//2
        assert min(left,w-width-left)>=2,key
        for j,ch in enumerate(line):
            for row,glyph in enumerate(FONT.get(ch,())):
                for col,bit in enumerate(glyph):
                    if bit=="1":
                        px=x+left+(j*6+col)*scale
                        py=y+(h-height)//2+(i*8+row)*scale
                        rect=(px,py,px+scale-1,py+scale-1)
                        paint.rectangle(rect,fill=ink)
                        if key in light_regions:
                            glow.rectangle(rect,fill=ink)

sign("pole_old",0,0,151,48,"CEDAR|RELAY","#6e4a40","#f3bf85",2)
sign("pole_new",0,52,151,48,"RELAY|TRAVEL","#366760","#e6d9ac",2)
sign("store_old",0,104,251,24,"CEDAR RELAY DINER","#754b40","#f0d59e",2)
sign("store_new",0,132,251,24,"RELAY TRAVEL CENTER","#366760","#efe1b6",2)
sign("diesel_old",0,160,112,16,"DIESEL","#754b40","#f0d59e")
sign("diesel_new",116,160,112,16,"TRUCK DIESEL","#366760","#e6d9ac")
sign("car_old",0,180,100,16,"GASOLINE","#754b40","#f0d59e")
sign("car_new",104,180,105,16,"AUTO FUEL","#366760","#e6d9ac")
sign("showers",0,200,61,12,"SHOWERS","#52685b","#ebe0b3")
sign("service",65,200,57,12,"SERVICE","#675e4e","#e6d0a4")
sign("scale",127,200,57,12,"SCALE","#52685b","#ece0b3")
sign("menu",0,215,107,22,"HOT MEALS|COFFEE","#6b584d","#ece0b3")
save("signs",atlas)
save("sign-light-mask",mask)
(OUT/"sign-regions.json").write_text(json.dumps(regions,indent=2)+"\n")
