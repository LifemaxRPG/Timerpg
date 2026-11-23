"""Draw signs, pane masks and muted shop finishes on Timerpg's art-pixel grid."""
import json
from pathlib import Path
from random import Random
from PIL import Image, ImageDraw
from pixel_font import FONT

ROOT=Path(__file__).resolve().parent
OUT=ROOT/"textures"
OUT.mkdir(exist_ok=True)
LIB=ROOT.parents[1]/"kotlin-street/tools/swatches/atlas"
CAT=json.loads((LIB/"atlas.json").read_text())
ATLAS=Image.open(LIB/"atlas.png").convert("RGB")

def swatch(name):
    x,y,w,h=(CAT[name][k] for k in ("x","y","w","h"))
    return ATLAS.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST)

def save(name,im):
    im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(OUT/f"{name}.png")

for name,key in (("brick","brick_red"),("wood","plank_horizontal"),("paving","pavement")):
    save(name,swatch(key))

styles={
    "barber-1940": ((126,63,55),(119,58,52)),
    "barber-1980": ((103,122,110),(77,87,76)),
    "barber-2010": ((50,70,66),(115,80,63)),
    "salon-1940": ((120,140,126),(148,104,103)),
    "salon-1980": ((155,114,121),(115,97,112)),
    "salon-2010": ((162,163,144),(105,132,110)),
}

def painted(name, source, hue, seed, pattern=None):
    swatch_colour=max(swatch(source).getcolors(),key=lambda n:n[0])[1]
    base=[round(.09*a+.91*b) for a,b in zip(swatch_colour,hue)]
    shades=[tuple(max(0,min(255,c+n)) for c in base) for n in (-17,-8,0,10)]
    im=Image.new("RGB",(64,64),shades[2])
    draw=ImageDraw.Draw(im)
    rng=Random(seed)
    for _ in range(164):
        x,y=rng.randrange(64),rng.randrange(64)
        draw.line((x,y,x+rng.randrange(1,5),y),fill=rng.choice(shades))
    if pattern=="tile":
        for i in range(0,64,8):
            draw.line((0,i,63,i),fill=shades[1])
            draw.line((i,0,i,63),fill=shades[1])
        for i in range(0,64,8):
            for j in range(0,64,8):
                draw.rectangle((i+1,j+1,i+6,j+6),fill=shades[0 if (i+j)%16==0 else 3])
    if pattern=="membrane":
        for y in (0,32):
            draw.line((0,y,63,y),fill=shades[1])
            draw.line((0,y+1,63,y+1),fill=shades[3])
            x=16 if y==0 else 47
            draw.line((x,y+2,x,y+31),fill=shades[1])
    save(name,im)

for i,(name,(face,seat)) in enumerate(styles.items()):
    painted("front-"+name,"roof_membrane_tan",face,310+i)
    painted("seat-"+name,"roof_membrane_tan",seat,440+i)
for name,source,hue,seed,pattern in (
    ("matte_steel","roof_membrane_grey",(59,71,66),210,None),
    ("roof","roof_membrane_grey",(92,100,102),228,"membrane"),
    ("tile","pavement",(170,166,146),198,"tile"),
    ("terrazzo","pavement",(151,149,131),412,None),
):
    painted(name,source,hue,seed,pattern)

glass=Image.new("RGBA",(64,64),(108,135,130,53))
mask=Image.new("RGB",(64,64))
g,m=ImageDraw.Draw(glass),ImageDraw.Draw(mask)
g.rectangle((0,0,63,63),fill=(46,58,54,255))
g.rectangle((1,1,62,62),fill=(108,135,130,53))
m.rectangle((1,1,62,62),fill="#e8bd80")
for x,y in ((7,21),(22,33),(43,54),(53,18)):
    for i in range(7):
        g.line((x+i,y-i,x+i+1,y-i),fill=(165,181,165,127))
save("glass",glass)
save("pane-mask",mask)

mirror=Image.new("RGB",(16,24),"#344740")
d=ImageDraw.Draw(mirror)
d.rectangle((1,1,14,22),fill="#9aa89c")
d.line((1,1,14,1),fill="#d6d0aa")
d.line((3,17,13,7),fill="#c1c7ae",width=2)
d.line((2,20,7,15),fill="#82978d")
save("mirror",mirror)

pole=Image.new("RGB",(16,32))
bands=("#aa584a","#aa584a","#e7ddb8","#e7ddb8","#59727d","#59727d","#e7ddb8","#e7ddb8")
for y in range(32):
    for x in range(16):
        pole.putpixel((x,y),tuple(bytes.fromhex(bands[(x+y)%8][1:])))
save("pole",pole)

art=Image.new("RGB",(128,256),"#3c4940")
draw=ImageDraw.Draw(art)
regions={}
for key,y,w,h,name,background,letters in (
    ("barber-1940",0,69,18,"MERCER|BARBER","#795048","#ebd5a4"),
    ("barber-1980",20,69,18,"MERCER|HAIR CO.","#d3c3a3","#3a645b"),
    ("barber-2010",40,69,18,"MERCER|BARBER CO.","#3c5650","#e9cf9d"),
    ("salon-1940",60,69,18,"LILAC|BEAUTY","#6c8b7b","#f0d9ae"),
    ("salon-1980",80,69,18,"LILAC|STYLING","#946e75","#fff0c5"),
    ("salon-2010",100,69,18,"LILAC|NAIL ROOM","#a3a58b","#374e47"),
    ("rear",121,36,10,"STAFF","#646d5d","#e3cea7"),
    ("barber_window",133,23,12,"CUTS","#6e5c48","#fbde9e"),
    ("salon_window",147,23,12,"NAILS","#726954","#e4cc9d"),
):
    draw.rectangle((0,y,w-1,y+h-1),fill="#303a34")
    draw.rectangle((1,y+1,w-2,y+h-2),fill=background)
    draw.line((1,y+1,w-2,y+1),fill="#b2a385")
    narrow_n=("1001","1101","1101","1011","1011","1001","1001")
    # Give both lines a full seven-pixel glyph height.
    compact="|" in name
    lines=name.split("|")
    for line_no,line in enumerate(lines):
        glyphs=[]
        for ch in line:
            glyph=FONT.get(ch, ("00000",)*7)
            if key.endswith("_window"):
                if ch=="N":
                    glyph=narrow_n
                elif ch=="I":
                    glyph=("1",)*7
                else:
                    glyph=tuple("".join(row[k] for k in (0,2,4)) for row in glyph)
            glyphs.append(glyph)
        text_width=sum(len(glyph[0])+1 for glyph in glyphs)-1
        start=(w-text_width)//2
        assert min(start,w-text_width-start)>=2,key
        top=y+1+line_no*8 if compact else y+(h-7)//2
        x=start
        for glyph in glyphs:
            for j,row in enumerate(glyph):
                for k,bit in enumerate(row):
                    if bit=="1":
                        draw.point((x+k,top+j),fill=letters)
            x+=len(glyph[0])+1
    regions[key]=[0,y*2,w*2,h*2]
save("signs",art)
(OUT/"sign-regions.json").write_text(json.dumps(regions,indent=2)+"\n")
