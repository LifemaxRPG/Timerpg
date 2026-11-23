"""Paint physical-scale nearest-filtered C017 textures and masks."""
import json
from pathlib import Path
from random import Random

from PIL import Image, ImageColor, ImageDraw

ROOT=Path(__file__).resolve().parent
OUT=ROOT/"textures"
OUT.mkdir(exist_ok=True)
FINISH={
    "rubber":(28,24),"dark":(83,24),"panel":(145,28),"metal":(219,29),
    "bright":(31,88),"mesh":(90,82),"fabric":(146,98),"wood":(217,99),
    "grille":(34,144),"dash":(119,154),"badge":(186,159),"edge":(237,151),
    "belt":(41,197),"loom":(110,224),"wheel":(205,215),
}
LAMPS={"head":(28,32),"turn":(85,32),"tail":(142,32),"brake":(198,32),"reverse":(30,97)}
COLORS={
    "1968":{"primary":"#ce5e31","secondary":"#353b3a","fabric":"#7b563c","panel":"#44392e"},
    "1988":{"primary":"#d5a843","secondary":"#323c45","fabric":"#5b6469","panel":"#40464a"},
}


def rgb(s):
    return ImageColor.getrgb(s) if isinstance(s,str) else s


def offset(c,delta):
    return tuple(max(0,min(255,k+delta)) for k in rgb(c))


def save(im,name):
    im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(OUT/(name+".png"))


def tile(c,seed):
    rng=Random(seed)
    im=Image.new("RGB",(32,32),rgb(c))
    d=ImageDraw.Draw(im)
    for _ in range(136):
        x,y=rng.randrange(32),rng.randrange(32)
        d.line((x,y,min(31,x+rng.randrange(1,4)),y),fill=offset(c,rng.choice((-11,-6,4,9))))
    return im


for i,(era,c) in enumerate(COLORS.items()):
    slug=f"torrel-{era}"
    for role in ("primary","secondary"):
        save(tile(c[role],48+i),slug+"-"+role)
        save(Image.new("L",(32,32),255),slug+"-"+role+"-mask")
    glass=Image.new("RGBA",(32,32),(79,117,124,61))
    gd=ImageDraw.Draw(glass)
    for row in (4,16,27):
        for x in range(3,29):
            y=(row+x//4)%32
            gd.point((x,y),fill=(163,184,177,87))
            if x%4 in (1,2):
                gd.point((x,y+1),fill=(134,161,157,79))
    save(glass,slug+"-glass")

    im=Image.new("RGB",(128,128),"#3e423f")
    draw=ImageDraw.Draw(im)
    palette={"rubber":"#252a2a","dark":"#39413e","panel":c["panel"],"metal":"#939e9c",
             "bright":"#c0c7bc","mesh":"#3d4745","fabric":c["fabric"],"wood":"#916048",
             "grille":"#353d3b","dash":"#2a3535","badge":"#313b39","edge":"#767c70",
             "belt":"#4a504b","loom":"#756c57","wheel":"#b3a88d" if i==0 else "#858e94"}
    keys={
        "rubber":(13,11),"dark":(12,11),"panel":(16,11),"metal":(13,10),
        "bright":(14,12),"mesh":(12,10),"fabric":(15,13),"wood":(14,11),
        "grille":(16,10),"dash":(17,11),"badge":(13,11),"edge":(8,10),
        "belt":(18,9),"loom":(17,11),"wheel":(21,16)
    }
    rng=Random(52+i)
    for key,(cx,cy) in FINISH.items():
        x,y=cx//2,cy//2
        rx,ry=keys[key]
        ink=palette[key]
        draw.rectangle((x-rx,y-ry,x+rx,y+ry),fill=ink)
        for _ in range(rx*ry//2):
            px,py=x+rng.randint(-rx,rx),y+rng.randint(-ry,ry)
            draw.point((px,py),fill=offset(ink,rng.choice((-11,-4,6,13))))
        if key in ("metal","bright","wheel","wood","mesh","grille","fabric","rubber"):
            for py in range(y-ry+2,y+ry,3):
                draw.line((x-rx,py,x+rx,py),fill=offset(ink,16 if key!="rubber" else 4))
                if py%2:
                    draw.line((x-rx,py+1,x+rx,py+1),fill=offset(ink,-12))
        if key=="fabric":
            for px in range(x-rx+3,x+rx,6):
                draw.line((px,y-ry,px,y+ry),fill=offset(ink,-22))
        if key=="dash":
            for px,r in ((x-4,2),(x,3),(x+5,2)):
                draw.ellipse((px-r,y-r,px+r,y+r),fill="#222c2b",outline="#858878")
                draw.line((px,y,px+1,y-1),fill="#e0bb76")
            if i:
                draw.line((x-6,y+5,x+7,y+5),fill="#7e7b53")
        if key=="badge":
            draw.rectangle((x-3,y-3,x+3,y+3),fill="#2f4943")
            draw.polygon([(x-3,y+2),(x-1,y-3),(x+1,y-3),(x+3,y+2),(x,y)],fill="#e1c28a")
    save(im,slug+"-finish")

    lenses=Image.new("RGB",(128,128),"#283431")
    emission=Image.new("RGB",(128,128),"black")
    d,e=ImageDraw.Draw(lenses),ImageDraw.Draw(emission)
    hues={"head":"#fff0c8","turn":"#df9448","tail":"#b64a3d","brake":"#e3614a","reverse":"#e3d9b8"}
    for key,(cx,cy) in LAMPS.items():
        x,y=cx//2,cy//2
        color=hues[key]
        d.rectangle((x-8,y-5,x+8,y+5),fill=offset(color,-57))
        d.rectangle((x-6,y-3,x+6,y+3),fill=color)
        e.rectangle((x-6,y-3,x+6,y+3),fill=color)
        for col in range(x-5,x+6,2):
            d.line((col,y-2,col,y+3),fill=offset(color,-27))
        d.line((x-5,y-3,x+5,y-3),fill=offset(color,17))
    save(lenses,slug+"-lamps")
    save(emission,slug+"-emission")

(OUT/"regions.json").write_text(json.dumps({"finish":FINISH,"lamps":LAMPS},indent=2)+"\n")
