"""Prepare two-texel artwork and repaintable school signs."""
import json
from pathlib import Path
from random import Random

from PIL import Image, ImageDraw
from pixel_font import FONT

ROOT = Path(__file__).resolve().parent
OUT = ROOT/"textures"
OUT.mkdir(exist_ok=True)
LIB = ROOT.parents[1]/"kotlin-street/tools/swatches/atlas"
atlas = Image.open(LIB/"atlas.png").convert("RGB")
catalogue = json.loads((LIB/"atlas.json").read_text())

def swatch(key):
    x,y,w,h = (catalogue[key][dim] for dim in ("x","y","w","h"))
    return atlas.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST)

def save(key, image):
    image.resize((image.width*2,image.height*2),Image.Resampling.NEAREST).save(OUT/f"{key}.png")

for output,key in (("brick","brick_red"),("wood","plank_horizontal"),("gravel","ballast"),
                   ("roof_slate","roof_slate")):
    save(output,swatch(key))

def painted(name, key, rgb, mode, seed):
    source = max(swatch(key).getcolors(),key=lambda item:item[0])[1]
    rgb = tuple(round(a*.13+b*.87) for a,b in zip(source,rgb))
    palette = [tuple(max(0,min(255,c+shade)) for c in rgb) for shade in (-17,-8,0,10)]
    image = Image.new("RGB",(64,64),palette[2])
    draw = ImageDraw.Draw(image)
    rng = Random(seed)
    for _ in range(150):
        x,y = rng.randrange(64),rng.randrange(64)
        draw.line((x,y,x+rng.randrange(1,5),y),fill=rng.choice(palette))
    if mode=="siding":
        for row in range(0,64,4):
            draw.line((0,row,63,row),fill=palette[0])
            draw.line((0,row+1,63,row+1),fill=palette[3])
    if mode=="tile":
        for i in range(0,64,8):
            draw.line((i,0,i,63),fill=palette[1])
            draw.line((0,i,63,i),fill=palette[1])
    if mode=="seams":
        for i in (0,32):
            draw.line((0,i,63,i),fill=palette[1])
            draw.line((0,i+1,63,i+1),fill=palette[3])
    save(name,image)

for spec in (
    ("clapboard","plank_horizontal",(219,218,190),"siding",21),
    ("faded","plank_horizontal",(188,180,154),"siding",17),
    ("metal","roof_membrane_green",(60,76,65),"plain",9),
    ("terracotta","roof_tin_brown",(163,95,62),"plain",11),
    ("grass","roof_membrane_green",(96,120,73),"plain",95),
    ("tile","pavement",(184,177,154),"tile",23),
    ("roof","roof_membrane_grey",(98,107,112),"seams",12),
    ("asphalt","asphalt",(107,108,100),"plain",26),
    ("chalk","pavement",(218,205,164),"plain",29),
):
    painted(*spec)

# Delaminating boards, loose shingle colour, and a gravel roof aggregate derived from ballast.
rng=Random(4004)
with Image.open(OUT/"faded.png") as base:
    tile=base.resize((64,64),Image.Resampling.NEAREST).convert("RGB")
peeling=Image.new("RGB",(128,128))
for y in (0,64):
    for x in (0,64):
        peeling.paste(tile,(x,y))
d=ImageDraw.Draw(peeling)
for x,y,span,rows in ((4,13,37,9),(67,52,49,13),(32,80,45,12),(100,113,25,8),(92,5,29,10),(9,61,25,6)):
    for j in range(-rows,rows+1):
        yy=(y+j)%128
        extent=max(4,span-int(abs(j)*span/(rows+1))+rng.randrange(-5,6))
        start=max(0,x+rng.randrange(-5,6))
        end=min(127,start+extent)
        colour=("#676862","#78786d","#878678","#969485")[yy%4]
        d.line((start,yy,end,yy),fill=colour)
        if j%4==1:
            d.line((start+2,yy,min(end,start+10),yy),fill="#a5a28d")
for _ in range(800):
    x,y=rng.randrange(128),rng.randrange(128)
    if y%4!=0:
        d.line((x,y,min(127,x+rng.randrange(1,4)),y),fill=rng.choice(("#a4a396","#c5baa0","#929187")))
save("faded",peeling)

# Dry streaks in the unmown verge are small clusters of whole art pixels.
with Image.open(OUT/"grass.png") as base:
    dry=base.resize((64,64),Image.Resampling.NEAREST).convert("RGB")
d=ImageDraw.Draw(dry)
for _ in range(93):
    x,y=rng.randrange(64),rng.randrange(64)
    d.line((x,y,min(63,x+rng.randrange(1,5)),y),fill=rng.choice(("#89854e","#7b7c49","#a0985f")))
save("grass-neglected",dry)

aggregate=swatch("ballast")
tar=Image.new("RGB",(64,64))
raw=aggregate.load()
for y in range(64):
    for x in range(64):
        intensity=sum(raw[x%aggregate.width,y%aggregate.height])//3
        grain=(intensity-128)//5
        drift=3 if (x//8+y//4)%3==0 else -2
        tar.putpixel((x,y),(76+grain+drift,78+grain+drift,73+grain+drift))
save("roof-tar",tar)

fence=Image.new("RGBA",(16,16))
for y in range(16):
    for x in range(16):
        if (x+y)%8==0 or (x-y)%8==0:
            fence.putpixel((x,y),(109+(y%2)*18,120+(y%2)*18,112+(y%2)*18,255))
save("chain-link",fence)

for dark in (False,True):
    image = Image.new("RGB",(16,16),"#394842")
    mask = Image.new("RGB",(16,16))
    dr = ImageDraw.Draw(image)
    dr.rectangle((1,1,14,14),fill="#536160" if dark else "#82928b")
    dr.line((2,1,14,1),fill="#878c75" if dark else "#bac4a9")
    dr.line((3,12,9,6),fill="#76817b" if dark else "#a1aca0",width=2)
    if dark:
        fracture=[(2,3),(7,3),(11,2),(13,5),(11,7),(14,11),(9,14),(7,12),(4,14),(4,9),(2,7)]
        dr.polygon(fracture,fill="#151e1c")
        dr.line([(7,3),(7,7),(5,9),(7,12)],fill="#6b7971")
        dr.line([(13,5),(10,7),(11,10),(14,11)],fill="#8b9385")
    if not dark:
        ImageDraw.Draw(mask).rectangle((1,1,14,14),fill="#e2b978")
    suffix = "-dark" if dark else ""
    save("glass"+suffix,image)
    save("pane-mask"+suffix,mask)

image = Image.new("RGB",(256,256),"#424942")
dr = ImageDraw.Draw(image)
regions = {}
for name,y,w,h,text,bg,ink,scale in (
    ("rural",0,116,13,"MILL BRANCH SCHOOL","#596950","#f0ddaa",1),
    ("campus",15,225,24,"WESTMEADOW ELEMENTARY","#4f6a60","#efdca6",1),
    ("gym",41,114,13,"GYM AND LUNCHROOM","#62514a","#f0ddaa",1),
    ("portable_a",57,62,12,"ROOM A","#76684e","#e9d9b0",1),
    ("portable_b",72,62,12,"ROOM B","#76684e","#e9d9b0",1),
    ("office",88,63,12,"OFFICE","#6b5949","#e9d9b0",1),
    ("rural_faded",102,116,13,"M LL BR NCH SCH OL","#706c56","#9e987b",1),
    ("campus_modern",117,225,24,"WESTMEADOW ELEMENTARY","#354e43","#eee0b5",1),
    ("marquee",144,86,14,"BOOK FAIR FRI","#eadbb5","#403f34",1),
):
    regions[name] = [0,y*2,w*2,h*2]
    dr.rectangle((0,y,w-1,y+h-1),fill="#343d33")
    dr.rectangle((1,y+1,w-2,y+h-2),fill=bg)
    dr.line((1,y+1,w-2,y+1),fill="#b3a880")
    columns=(0,1,1,2,3,3,4) if name.startswith("campus") else tuple(range(5))
    gap=2 if name.startswith("campus") else 1
    stride=len(columns)+gap
    height=2 if name.startswith("campus") else 1
    length=len(text)*stride-gap
    left=(w-length)//2
    assert min(left,w-length-left)>=2, name
    for j,ch in enumerate(text):
        for yy,glyph in enumerate(FONT.get(ch,())):
            for xx,col in enumerate(columns):
                if glyph[col]=="1":
                    x=left+j*stride+xx
                    py=y+(h-7*height)//2+yy*height
                    dr.line((x,py,x,py+height-1),fill=ink)
# One repeatable locker-front module and a bulletin board share the sign material.
regions["lockers"]=[0,324,32,52]
dr.rectangle((0,162,15,187),fill="#344a46")
for x in (1,8):
    dr.rectangle((x,163,x+5,186),fill="#71877a")
    dr.line((x,163,x+5,163),fill="#adb4a0")
    for y in (166,168,182,184):
        dr.line((x+1,y,x+4,y),fill="#394f47")
    dr.line((x+4,174,x+4,176),fill="#d6c7a0")
regions["bulletin"]=[0,380,76,36]
dr.rectangle((0,190,37,207),fill="#594a3c")
dr.rectangle((1,191,36,206),fill="#988363")
for x,y,w,h,colour in ((3,193,7,10,"#e9dfb8"),(13,194,7,7,"#afbbb2"),(22,192,12,10,"#dcc38a"),(15,199,8,6,"#d5a894")):
    dr.rectangle((x,y,x+w,y+h),fill=colour)
    dr.line((x+1,y+2,x+w-1,y+2),fill="#737361")
save("signs",image)
(OUT/"sign-regions.json").write_text(json.dumps(regions,indent=2)+"\n")
