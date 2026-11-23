"""Create the pawn shop's nearest-filtered swatches and sign atlas."""
import json
from pathlib import Path
from random import Random

from PIL import Image, ImageDraw
from pixel_font import FONT

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "textures"
OUT.mkdir(exist_ok=True)
LIB = ROOT.parents[1] / "kotlin-street/tools/swatches/atlas"
ATLAS = Image.open(LIB / "atlas.png").convert("RGB")
RECTS = json.loads((LIB / "atlas.json").read_text())


def save(key, image):
    image.resize((image.width*2,image.height*2),Image.Resampling.NEAREST).save(OUT/f"{key}.png")


def swatch(key):
    r=RECTS[key]
    x,y,w,h=(r[k] for k in ("x","y","w","h"))
    return ATLAS.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST)


for file,key in (("brick","brick_red"),("plank","plank_horizontal"),("paving","pavement")):
    save(file,swatch(key))


def painted(file,key,target,seed,seams=0):
    sw=swatch(key)
    most_common=max(sw.getcolors())[1]
    rgb=[round(.23*a+.77*b) for a,b in zip(most_common,target)]
    colors=[tuple(max(0,min(255,c+n)) for c in rgb) for n in (-22,-12,0,8,16)]
    im=Image.new("RGB",(64,64),colors[2])
    d=ImageDraw.Draw(im)
    rng=Random(seed)
    for _ in range(390):
        x,y=rng.randrange(64),rng.randrange(64)
        d.line((x,y,min(63,x+rng.randrange(1,5)),y),fill=rng.choice(colors[1:]))
    if seams:
        for y in range(0,64,seams):
            d.line((0,y,63,y),fill=colors[1])
            d.line((0,y+1,63,y+1),fill=colors[3])
    save(file,im)


for spec in (
    ("stone","pavement",(186,170,139),4,16),
    ("roof","roof_membrane_grey",(75,78,78),6,24),
    ("teal","roof_membrane_green",(55,87,83),7,16),
    ("ochre","roof_membrane_ochre",(168,111,57),11,16),
    ("graphite","wall_slate_dark",(68,76,78),2,16),
    ("brass","roof_membrane_ochre",(171,132,64),26),
    ("metal","wall_slate_dark",(52,61,60),29),
):
    painted(*spec)


def lettering(d,text,x,y,ink):
    for i,letter in enumerate(text):
        for row,line in enumerate(FONT.get(letter,())):
            for column,pixel in enumerate(line):
                if pixel=="1":
                    d.point((x+6*i+column,y+row),fill=ink)


# A real clear central pane with perimeter sash, faint stepped reflections and
# black emissive pixels at the frame. Door glass and counter glass use different
# UV-sized panels in the same texture.
for kind in ("window","loans","plain"):
    im=Image.new("RGBA",(32,32),(56,69,69,255))
    mask=Image.new("RGB",im.size,"black")
    d=ImageDraw.Draw(im)
    dm=ImageDraw.Draw(mask)
    d.rectangle((1,1,30,30),fill=(127,159,151,51),outline=(124,112,89,255))
    dm.rectangle((2,2,29,29),fill="#bc966e")
    for x,y in ((5,4),(19,14),(9,24)):
        d.line((x,y,x+4,y),fill=(178,187,162,74))
        d.line((x+3,y-1,x+5,y-1),fill=(178,187,162,74))
    if kind=="loans":
        lettering(d,"LOANS",2,19,(231,203,149,255))
        for y in range(32):
            for x in range(32):
                if im.getpixel((x,y))[3]==255:
                    mask.putpixel((x,y),(0,0,0))
    if kind=="plain":
        d.rectangle((0,0,31,31),fill=(148,170,161,47))
        dm.rectangle((0,0,31,31),fill="#000000")
    save(kind,im)
    if kind!="plain":
        save(kind+"-mask",mask)


atlas=Image.new("RGB",(256,256),"#292c30")
emit=Image.new("RGB",atlas.size,"black")
d=ImageDraw.Draw(atlas)
em=ImageDraw.Draw(emit)
regions={}


def panel(key, rect, text, ink, paper, glow=False, led=False):
    x,y,w,h=rect
    d.rectangle((x,y,x+w-1,y+h-1),fill="#2a2928")
    d.rectangle((x+1,y+1,x+w-2,y+h-2),fill=paper)
    d.line((x+1,y+1,x+w-2,y+1),fill="#858178")
    tx=x+(w-(len(text)*6-1))//2
    ty=y+(h-7)//2
    lettering(d,text,tx,ty,ink)
    if glow:
        lettering(em,text,tx,ty,ink)
    if led:
        # Keep the printed LED pattern snapped to whole art pixels.
        for xx in range(x+2,x+w-2,2):
            d.point((xx,y+h-3),fill="#a15a2f")
            em.point((xx,y+h-3),fill="#754736")
    regions[key]=[2*v for v in rect]


for i,(key,ink,paper) in enumerate((
        ("1920","#efd8a1","#2d514d"),("1960","#ffe1ae","#694a34"),("2000","#f2dbb9","#363c40"))):
    panel(f"name-{key}",(0,i*18,80,14),"LATCH & LYRE",ink,paper)
panel("pawn",(87,15,32,11),"PAWN","#ef814d","#383432",True)
panel("goldtv",(86,30,102,11),"GUNS - GOLD - TV","#e9b474","#4c3936",True)
panel("buygold",(86,44,71,11),"WE BUY GOLD","#ffd080","#393037",True,True)
panel("service",(16,57,45,11),"PRIVATE","#dbc697","#454847")
panel("records",(72,57,50,11),"RECEIVING","#ddc7a3","#454847")
lamp=Image.new("RGB",(16,16),"#ddab63")
light=ImageDraw.Draw(lamp)
light.line((0,0,15,0),fill="#bb925d")
light.line((2,5,12,5),fill="#ebbf77")
light.line((5,10,13,10),fill="#f5d19b")
save("lamp",lamp)
# The original painted advertisement weathers in place as the shop is refitted.
brick_art=swatch("brick_red")
for i,(era,loss,ink) in enumerate((("1920",.08,"#c6b58b"),
                                    ("1960",.35,"#ba9d77"),("2000",.67,"#ad846b"))):
    ox,oy=0,84+34*i
    tile_pattern=Image.new("RGB",(80,32))
    for yy in range(0,32,brick_art.height):
        for xx in range(0,80,brick_art.width):
            tile_pattern.paste(brick_art,(xx,yy))
    atlas.paste(tile_pattern,(ox,oy))
    marks=Image.new("1",tile_pattern.size)
    md=ImageDraw.Draw(marks)
    lettering(md,"MUSICAL",20,5,1)
    lettering(md,"INSTRUMENTS",7,17,1)
    rng=Random(1011)
    wear={(x,y) for y in range(32) for x in range(80)
          if rng.random()<loss or (x+y*3)%33==1}
    for y in range(32):
        for x in range(80):
            if marks.getpixel((x,y)) and (x,y) not in wear:
                d.point((ox+x,oy+y),fill=ink)
    regions[f"wall-{era}"]=[ox*2,oy*2,160,64]

save("signs",atlas)
save("sign-mask",emit)
(OUT/"regions.json").write_text(json.dumps(regions,indent=2)+"\n")
