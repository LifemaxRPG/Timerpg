"""Create Vale Lantern's pixel-art finishes, signs and emission masks."""
import json
from pathlib import Path
from random import Random

from PIL import Image, ImageDraw
from pixel_font import FONT

ROOT=Path(__file__).resolve().parent
OUT=ROOT/"textures"
OUT.mkdir(exist_ok=True)
LIB=ROOT.parents[1]/"kotlin-street/tools/swatches/atlas"
ATLAS=Image.open(LIB/"atlas.png").convert("RGB")
SWATCHES=json.loads((LIB/"atlas.json").read_text())


def swatch(key):
    x,y,w,h=(SWATCHES[key][p] for p in ("x","y","w","h"))
    return ATLAS.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST)


def save(im,name):
    im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(OUT/(name+".png"))


for file,key in (("shingle","roof_slate"),("asphalt","asphalt_worn"),("concrete","pavement")):
    save(swatch(key),file)


def finish(file,key,target,seed,seams=0):
    src=max(swatch(key).getcolors())[1]
    rgb=[round(a*.24+b*.76) for a,b in zip(src,target)]
    palette=[tuple(max(0,min(255,c+i)) for c in rgb) for i in (-21,-12,0,8,16)]
    im=Image.new("RGB",(32,32),palette[2])
    d=ImageDraw.Draw(im)
    rng=Random(seed)
    for _ in range(126):
        x,y=rng.randrange(32),rng.randrange(32)
        d.line((x,y,min(31,x+rng.randrange(1,4)),y),fill=rng.choice(palette[1:]))
    if seams:
        for y in range(0,32,seams):
            d.line((0,y,31,y),fill=palette[1])
            d.line((0,y+1,31,y+1),fill=palette[3])
    save(im,file)


for spec in (
    ("cottage","plank_horizontal",(207,187,150),38,4),
    ("enamel","wall_slate_light",(221,216,190),91,16),
    ("stucco","block_courses",(171,157,128),100,8),
    ("membrane","roof_membrane_grey",(96,106,106),62,16),
    ("teal","roof_membrane_green",(45,91,89),1),
    ("orange","roof_tin_orange",(173,91,46),96),
    ("metal","wall_slate_dark",(82,94,92),78),
):
    finish(*spec)


for name in ("glass","glass-mask","globe"):
    im=Image.new("RGBA",(16,16),(39,48,46,255) if name=="glass" else (0,0,0,255))
    d=ImageDraw.Draw(im)
    if name=="globe":
        d.rectangle((0,0,15,15),fill=(104,149,145,123))
        for x,y in ((1,1),(4,6),(11,3),(7,12)):
            d.line((x,y,x+3,y+1),fill=(177,190,163,161))
    else:
        for x0,x1 in ((2,7),(9,13)):
            for y0,y1 in ((2,7),(9,13)):
                d.rectangle((x0,y0,x1,y1),fill="#607474" if name=="glass" else "#e2ac77")
                if name=="glass":
                    d.line((x0,y0,x1,y0),fill="#85948a")
        if name=="glass":
            d.line((1,0,1,15),fill="#879183")
            d.line((0,14,15,14),fill="#657262")
    save(im,name)


def text(d,label,x,y,color,scale=1):
    for i,char in enumerate(label):
        for row,line in enumerate(FONT.get(char,())):
            for col,bit in enumerate(line):
                if bit=="1":
                    xx,yy=x+(6*i+col)*scale,y+row*scale
                    d.rectangle((xx,yy,xx+scale-1,yy+scale-1),fill=color)


art=Image.new("RGB",(256,128),"#273331")
glow=Image.new("RGB",art.size,"black")
d,g=ImageDraw.Draw(art),ImageDraw.Draw(glow)
regions={}
styles={
    "1925":{"ink":"#eddbad","paper":"#483d32","face":"serif-block","glowColor":"#d1aa72"},
    "1945":{"ink":"#ffe4a5","paper":"#294d49","face":"rounded","glowColor":"#f3aa63"},
    "1975":{"ink":"#efedd2","paper":"#304c56","face":"sans","glowColor":"#d6ede2"},
}


def plate(key,r,label,ink,paper,emission=None,scale=1):
    x,y,w,h=r
    d.rectangle((x,y,x+w-1,y+h-1),fill="#27302e")
    d.rectangle((x+1,y+1,x+w-2,y+h-2),fill=paper)
    d.line((x+1,y+1,x+w-2,y+1),fill="#827b67")
    px=x+(w-(6*len(label)-1)*scale)//2
    py=y+(h-7*scale)//2
    text(d,label,px,py,ink,scale)
    if emission:
        text(g,label,px,py,emission,scale)
    regions[key]=[2*n for n in r]


for i,(year,s) in enumerate(styles.items()):
    plate("name-"+year,(0,i*17,80,15),"VALE LANTERN",s["ink"],s["paper"],s["glowColor"])
    labels=["24.9","37.9","2.99"]
    plate("price-"+year,(86,i*19,51,18),labels[i],"#d58354" if i==2 else "#efdfad","#393f3b",
          "#ff854e" if i==2 else "#ba8f58",2)

for key,x,y,width,label in (
    ("gas",147,0,26,"GAS"),("market",179,0,51,"MARKET"),("office",147,14,43,"OFFICE"),
    ("service",147,28,50,"SERVICE"),("air",205,28,25,"AIR"),("oil",147,42,25,"OIL"),
    ("ice",181,42,25,"ICE"),("phone",214,42,41,"PHONE"),("regular",147,57,52,"REGULAR"),
):
    plate(key,(x,y,width,11),label,"#e8ddba","#2d4d47", "#e4c782" if key in ("gas","market") else None)
for i in range(6):
    plate("pump-"+str(i+1),(i*16,61,7,9),str(i+1),"#e7dbad","#3b5b51")
plate("pump-meter",(0,77,27,14),"0.00","#343933","#c3b78e")
d.rectangle((61,77,71,81),fill="#313a36")
d.line((62,78,69,78),fill="#d1bf91")
for xx in (62,64,66,68):
    d.line((xx,79,xx,80),fill="#b19b6d")
regions["meter-small"]=[122,154,22,10]
# Lantern emblem and a neutral sign stripe used on both box-station fascias.
d.rounded_rectangle((36,75,53,92),radius=1,fill="#293936")
d.polygon(((44,76),(47,80),(50,83),(47,89),(41,89),(38,83)),fill="#d4a064")
d.rectangle((42,81,46,86),fill="#eee1a6")
regions["emblem"]=[72,150,36,36]
# The game's price painter replaces a whole material texture. Keep the fuel
# board separate from the owner atlas, with a full 0..1 UV rect on each face.
for year in styles:
    x,y,w,h=(k//2 for k in regions["price-"+year])
    bounds=(x,y,x+w,y+h)
    save(art.crop(bounds),"price-"+year)
    save(glow.crop(bounds),"price-"+year+"-mask")
save(art,"signs")
save(glow,"sign-mask")
(OUT/"regions.json").write_text(json.dumps({"rects":regions,"styles":styles},indent=2)+"\n")
