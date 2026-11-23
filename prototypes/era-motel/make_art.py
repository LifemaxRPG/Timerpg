"""Generate Birchbend's shared swatches and era-specific signs."""
from pathlib import Path
import json
from random import Random

from PIL import Image, ImageDraw
from pixel_font import FONT

ROOT=Path(__file__).resolve().parent
OUT=ROOT/"textures"
OUT.mkdir(exist_ok=True)
LIB=ROOT.parents[1]/"kotlin-street/tools/swatches/atlas"
atlas=Image.open(LIB/"atlas.png").convert("RGB")
library=json.loads((LIB/"atlas.json").read_text())


def swatch(name):
    x,y,w,h=(library[name][key] for key in ("x","y","w","h"))
    return atlas.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST)


def save(im,name):
    im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(OUT/(name+".png"))


for name,key in (("asphalt","asphalt_worn"),("gravel","ballast"),("paving","pavement"),
                 ("shingles","roof_slate"),("wood","plank_horizontal")):
    save(swatch(key),name)


def finish(name,key,rgb,seed,lap=0):
    base=max(swatch(key).getcolors())[1]
    hue=[round(a*.20+b*.80) for a,b in zip(base,rgb)]
    colors=[tuple(max(0,min(255,a+b)) for a in hue) for b in (-24,-10,0,9,17)]
    im=Image.new("RGB",(48,48),colors[2])
    d=ImageDraw.Draw(im)
    rng=Random(seed)
    for _ in range(280):
        x,y=rng.randrange(48),rng.randrange(48)
        d.line((x,y,min(47,x+rng.randrange(1,6)),y),fill=rng.choice(colors[1:]))
    if lap:
        for y in range(0,48,lap):
            d.line((0,y,47,y),fill=colors[0])
            d.line((0,y+1,47,y+1),fill=colors[3])
            for x in range((y%16),48,16):
                d.line((x,y+2,x,y+lap-1),fill=colors[1])
    save(im,name)


for spec in (
    ("sage","plank_horizontal",(118,129,100),17,4),
    ("coral","wall_stucco_red",(164,99,78),37,8),
    ("beige","block_courses",(172,147,119),47,12),
    ("awning","roof_membrane_grey",(93,104,109),18,16),
    ("cream","pavement",(204,183,137),85,0),
    ("dark_sage","wall_slate_dark",(55,79,69),92,0),
    ("teal","roof_tin_blue",(40,92,91),61,0),
    ("water","roof_membrane_green",(51,123,133),31,0),
    ("arrow","roof_membrane_ochre",(235,156,82),53,0),
):
    finish(*spec)


for name in ("glass","glass-mask"):
    im=Image.new("RGB",(16,16),"#283736" if name=="glass" else "black")
    d=ImageDraw.Draw(im)
    for x0,x1 in ((2,7),(9,13)):
        for y0,y1 in ((2,7),(9,13)):
            d.rectangle((x0,y0,x1,y1),fill="#546c68" if name=="glass" else "#d7a577")
            if name=="glass":
                d.line((x0,y0,x1-1,y0),fill="#76857a")
                d.line((x1-1,y1,x1,y1-1),fill="#7d8878")
    if name=="glass":
        d.line((1,0,1,15),fill="#849183")
        d.line((0,14,15,14),fill="#526058")
    save(im,name)


def lettering(d,text,x,y,ink):
    for i,ch in enumerate(text):
        for row,line in enumerate(FONT.get(ch,())):
            for col,bit in enumerate(line):
                if bit=="1":
                    d.point((x+6*i+col,y+row),fill=ink)


sign=Image.new("RGB",(256,128),"#222b2c")
mask=Image.new("RGB",sign.size,"black")
d,e=ImageDraw.Draw(sign),ImageDraw.Draw(mask)
regions={}
schemes={
    "1935":{"paper":"#403d30","ink":"#ebd09b","glow":"#d1a56c","face":"serif-block"},
    "1955":{"paper":"#284849","ink":"#ffe2a0","glow":"#ffab5d","face":"rounded"},
    "1985":{"paper":"#304f61","ink":"#e9e7ca","glow":"#dbebd7","face":"sans"},
}


def panel(key,rect,text,ink,paper,glow=None,backlit=False):
    x,y,w,h=rect
    d.rectangle((x,y,x+w-1,y+h-1),fill="#262f2e")
    d.rectangle((x+1,y+1,x+w-2,y+h-2),fill=paper)
    d.line((x+1,y+1,x+w-2,y+1),fill="#857d65")
    for idx in range(7):
        px=x+3+(idx*11+2)%(w-5)
        py=y+(1 if idx%2 else h-2)
        d.point((px,py),fill="#a99e80")
    if backlit:
        e.rectangle((x+2,y+2,x+w-3,y+h-3),fill="#303d48")
    a=x+(w-(6*len(text)-1))//2
    b=y+(h-7)//2
    lettering(d,text,a,b,ink)
    if glow:
        lettering(e,text,a,b,glow)
    regions[key]=[2*n for n in rect]


for i,(year,s) in enumerate(schemes.items()):
    panel("name-"+year,(0,18*i,74,15),"BIRCHBEND",s["ink"],s["paper"],s["glow"],i==2)
for key,x,y,width,label,ink,paper in (
    ("motor",80,0,70,"MOTOR COURT","#eee1b3","#464535"),
    ("motel",80,15,36,"MOTEL","#ffe3a7","#3d4c42"),
    ("office",122,15,41,"OFFICE","#efdbaf","#304449"),
    ("rooms",80,30,38,"ROOMS","#ede0c1","#395261"),
    ("ice",126,30,25,"ICE","#d4e9df","#465e62"),
    ("soda",158,30,33,"SODA","#ddc39c","#2a4f4e"),
    ("key",80,45,28,"KEYS","#e6cea3","#625748"),
    ("pool",115,45,31,"POOL","#e9e3ba","#2f5d58"),
):
    panel(key,(x,y,width,11),label,ink,paper,ink if key in ("motor","motel","rooms") else None)
panel("vacancy",(80,62,48,11),"VACANCY","#b9edd9","#304344","#7de2d0")
panel("no",(133,62,17,11),"NO","#6c493d","#453f37","#fc8866")
for i in range(16):
    panel("room-"+str(i+1),((i%10)*18,82+(i//10)*13,15,11),str(i+1),"#eaddb5","#3d504a")
save(sign,"signs")
save(mask,"sign-mask")
(OUT/"regions.json").write_text(json.dumps({"rects":regions,"schemes":schemes},indent=2)+"\n")
