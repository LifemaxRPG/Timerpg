"""Pixel art for Thimble Trails, including condition variants and pane masks."""
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
    x,y,w,h=(CAT[key][i] for i in ("x","y","w","h"))
    return ATLAS.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST)

def save(key,art):
    art.resize((art.width*2,art.height*2),Image.Resampling.NEAREST).save(OUT/f"{key}.png")

def texture(key,shared,colour,seed,mode):
    base=max(swatch(shared).getcolors(),key=lambda item:item[0])[1]
    tint=tuple(round(.14*a+.86*b) for a,b in zip(base,colour))
    shades=[tuple(max(0,min(255,c+n)) for c in tint) for n in (-14,-7,0,8)]
    im=Image.new("RGB",(64,64),shades[2])
    dr=ImageDraw.Draw(im)
    rng=Random(seed)
    for _ in range(198 if mode!="water" else 104):
        x,y=rng.randrange(64),rng.randrange(64)
        dr.line((x,y,min(63,x+rng.randrange(1,8 if mode=="water" else 4)),y),fill=rng.choice(shades))
    if mode=="siding":
        for y in range(0,64,6):
            dr.line((0,y,63,y),fill=shades[0])
            dr.line((0,y+1,63,y+1),fill=shades[3])
    if mode=="weathered":
        for x,y in ((2,9),(41,31),(13,47),(59,59)):
            for j in range(-3,4):
                dr.line((x,(y+j)%64,min(63,x+14-3*abs(j)),(y+j)%64),fill=shades[(j+3)%2])
    save(key,im)

base=(
    ("yard","roof_membrane_green",(95,119,72),"plain"),
    ("course","roof_membrane_green",(67,130,84),"plain"),
    ("path","pavement",(166,151,111),"weathered"),
    ("cream","plank_horizontal",(215,201,162),"siding"),
    ("red","roof_tin_red",(171,78,62),"weathered"),
    ("blue","roof_membrane_grey",(63,111,143),"plain"),
    ("water","roof_membrane_green",(66,112,106),"water"),
    ("trim","roof_membrane_grey",(76,83,79),"weathered"),
)
for i,(name,key,hue,mode) in enumerate(base):
    for era,saturation in (("1962",1.0),("1990",.61),("2006",.44)):
        name_out=f"{name}-{era}"
        tone=int(sum(hue)/3)
        target=tuple(round(tone+(c-tone)*saturation-(5 if era=="2006" else 0)) for c in hue)
        if era=="2006":
            target={"yard":(101,110,69),"course":(86,106,65),"blue":(75,91,87),
                    "red":(125,93,72),"cream":(177,164,130)}.get(name,target)
        texture(name_out,key,target,736+i*17,"weathered" if era=="2006" and mode=="plain" else mode)

with Image.open(OUT/"course-2006.png") as source:
    worn=source.resize((64,64),Image.Resampling.NEAREST).convert("RGB")
d=ImageDraw.Draw(worn)
rng=Random(1956)
for x,y in ((5,25),(32,11),(47,49),(18,53),(59,22)):
    trace=[(x,y)]
    for step in range(1,13):
        trace.append((min(63,max(0,trace[-1][0]+rng.choice((-2,-1,0,2)))),(y+step)%64))
    for a,b in zip(trace,trace[1:]):
        if b[1]>a[1]:
            d.line((*a,*b),fill="#494e3d")
            d.line((a[0]+1,a[1],b[0]+1,b[1]),fill="#827957")
    for k in (4,8):
        a=trace[k]
        d.line((*a,a[0]-3,min(63,a[1]+2)),fill="#494e3d")
save("course-2006",worn)

for year in ("1962","1990","2006"):
    with Image.open(OUT/f"cream-{year}.png") as source:
        ball=source.resize((64,64),Image.Resampling.NEAREST).convert("RGB")
    dots=ImageDraw.Draw(ball)
    for y in range(3,64,5):
        for x in range(3+(y%2)*2,64,6):
            r,g,b=ball.getpixel((x,y))
            dots.rectangle((x,y,x+1,y+1),fill=(r-23,g-23,b-22))
            dots.line((x,y+2,x+1,y+2),fill=(min(255,r+12),min(255,g+12),min(255,b+12)))
    save(f"ball-{year}",ball)

for dark in (False,True):
    im=Image.new("RGBA",(16,16),(48,59,50,255))
    mask=Image.new("RGB",im.size)
    g,m=ImageDraw.Draw(im),ImageDraw.Draw(mask)
    g.rectangle((1,1,14,14),fill=(64,74,67,223) if dark else (117,147,133,70))
    g.line((2,10,7,5),fill=(141,151,132,144),width=2)
    if dark:
        g.line((8,2,6,7,9,12,8,14),fill=(34,46,41,255))
    else:
        m.rectangle((1,1,14,14),fill="#dfba79")
    suffix="-dark" if dark else ""
    save("glass"+suffix,im)
    save("pane-mask"+suffix,mask)

chain=Image.new("RGBA",(16,16))
for y in range(16):
    for x in range(16):
        if (x+y)%8==0 or (x-y)%8==0:
            chain.putpixel((x,y),(113,121,106,255))
save("chain",chain)

image=Image.new("RGB",(256,256),"#344338")
emission=Image.new("RGB",image.size)
paint,glow=ImageDraw.Draw(image),ImageDraw.Draw(emission)
regions={}

def plate(key,x,y,w,h,label,bg,ink,scale=1,lit=False):
    regions[key]=[x*2,y*2,w*2,h*2]
    paint.rectangle((x,y,x+w-1,y+h-1),fill="#364137")
    paint.rectangle((x+1,y+1,x+w-2,y+h-2),fill=bg)
    paint.line((x+1,y+1,x+w-2,y+1),fill="#aa9e78")
    lines=label.split("|")
    full_h=(len(lines)*8-1)*scale
    assert h>=full_h+2,key
    for i,line in enumerate(lines):
        text_w=(len(line)*6-1)*scale
        left=(w-text_w)//2
        assert min(left,w-text_w-left)>=2,key
        for j,ch in enumerate(line):
            for r,row in enumerate(FONT.get(ch,())):
                for col,bit in enumerate(row):
                    if bit=="1":
                        px=x+left+(j*6+col)*scale
                        py=y+(h-full_h)//2+(i*8+r)*scale
                        paint.rectangle((px,py,px+scale-1,py+scale-1),fill=ink)
                        if lit:
                            glow.rectangle((px,py,px+scale-1,py+scale-1),fill=ink)

plate("name_1962",0,0,104,29,"THIMBLE TRAILS|MINI GOLF","#796f4b","#f2cf87",lit=True)
plate("name_1990",0,32,104,29,"THIMBLE TRAILS|MINI GOLF","#776f57","#cbad79",lit=True)
plate("name_2006",0,64,104,29,"TH MBLE TRA LS|MINI GOLF","#6b664d","#a99972")
plate("closed",0,96,59,15,"CLOSED","#78634c","#d0b78a")
plate("tickets",0,114,62,14,"TICKETS","#596e54","#e8cc91")
plate("exit",66,114,45,14,"STAFF","#596e54","#e8cc91")
for i in range(18):
    plate(f"hole_{i+1}",(i%9)*26,136+(i//9)*15,16,12,str(i+1),"#5b7358","#eccd8e")
plate("graffiti",112,69,54,12,"GHOSTS","#777971","#835d73")
plate("last",104,96,60,12,"LAST PUTT","#4e6b67","#ead49d")
for i,(name,tint) in enumerate((("rose","#f8a59e"),("mint","#a9dcae"),("amber","#ffd485"),("white","#f2e5ae"))):
    x=110+i*10
    y=120
    regions["lamp_"+name]=[2*x,2*y,16,16]
    paint.rectangle((x,y,x+7,y+7),fill="#3d4c44")
    paint.rectangle((x+1,y+1,x+6,y+6),fill=tint)
    paint.line((x+2,y+1,x+5,y+1),fill="#fff3ce")
    glow.rectangle((x+1,y+1,x+6,y+6),fill=tint)
save("signs",image)
save("sign-mask",emission)
(OUT/"sign-regions.json").write_text(json.dumps(regions,indent=2)+"\n")
