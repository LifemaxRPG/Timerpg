"""Original pixel textures and matching repaint/emission masks."""
from pathlib import Path
from random import Random
import json
from PIL import Image, ImageDraw, ImageColor

from designs import DESIGNS

ROOT=Path(__file__).resolve().parent
OUT=ROOT/"textures"
OUT.mkdir(exist_ok=True)
ATLAS=256
REGIONS={
    "dark":(16,16), "rubber":(96,16), "metal":(184,24), "fabric":(28,96),
    "whitewall":(93,94), "grille":(174,110), "panel":(23,153), "dash":(104,156),
    "bright":(183,179), "wood":(26,213), "badge":(103,216), "fins":(72,242),
}
LENSES={"head":(16,16),"tail":(42,16),"brake":(68,16),"turn":(94,16),"reverse":(120,16)}


def rgb(value):
    return ImageColor.getrgb(value) if isinstance(value,str) else value


def offset(value,change):
    return tuple(max(0,min(255,c+change)) for c in rgb(value))


def doubled(im):
    return im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST)


def save(im,file):
    doubled(im).save(OUT/f"{file}.png")


def tile(color,seed,size=32):
    rng=Random(seed)
    im=Image.new("RGB",(size,size),rgb(color))
    draw=ImageDraw.Draw(im)
    for _ in range(size*size//9):
        x,y=rng.randrange(size),rng.randrange(size)
        draw.line((x,y,min(size-1,x+rng.randrange(0,3)),y),fill=offset(color,rng.choice((-13,-7,5,10))))
    return im


for i,design in enumerate(DESIGNS):
    root=design.slug
    for channel,color in (("primary",design.colours[0]),("secondary",design.accent)):
        im=tile(color,614+i)
        save(im,f"{root}-{channel}")
        mask=Image.new("L",im.size,255)
        for p in ((0,1),(9,26),(19,28),(29,3),(22,18)):
            mask.putpixel(p,66)
        save(mask,f"{root}-{channel}-mask")
    glass=Image.new("RGBA",(32,32),(105,147,154,94 if i==2 else 48))
    gd=ImageDraw.Draw(glass)
    for y in (3,16,24):
        for x in range(3,29):
            n=(x+y)//4
            yy=(y-n)%32
            gd.point((x,yy),fill=(179,196,169,88))
            if y==3 and x%3:
                gd.point((x,(yy+1)%32),fill=(148,179,165,77))
    save(glass,f"{root}-glass")

    finish=Image.new("RGB",(ATLAS//2,ATLAS//2),"#3c413d")
    draw=ImageDraw.Draw(finish)
    colours={
        "metal":"#8e9990",
        "dark":"#323936","rubber":"#303331","fabric":("#48433c","#4c4d44","#3c4442")[i],
        "whitewall":"#d3cfac","grille":"#4e5550","panel":("#665749","#535b50","#3f5150")[i],
        "dash":"#4e5145","bright":"#c1c9af","wood":"#745445","badge":"#8e9a73","fins":"#6b776b"
    }
    sizes={"metal":(31,14),"dark":(18,8),"rubber":(18,8),"fabric":(16,15),"whitewall":(15,15),
           "grille":(18,18),"panel":(17,12),"dash":(17,12),"bright":(24,13),"wood":(17,11),"badge":(7,7),"fins":(12,6)}
    rng=Random(819+i)
    for key,center in REGIONS.items():
        x,y=[k//2 for k in center]
        rx,ry=sizes[key]
        c=colours[key]
        draw.rectangle((x-rx,y-ry,x+rx,y+ry),fill=c)
        for _ in range(rx*ry//2):
            u,v=x+rng.randint(-rx,rx-1),y+rng.randint(-ry,ry)
            draw.point((u,v),fill=offset(c,rng.choice((-14,-5,5,12))))
        if key=="grille":
            for v in range(y-ry,y+ry,2 if i==1 else 3):
                draw.line((x-rx,v,x+rx,v),fill="#949b90" if i in (0,1) else "#747c74")
            if i in (0,3):
                for u in range(x-rx+1,x+rx,4):
                    for v in range(y-ry+1,y+ry,3):
                        draw.point((u,v),fill="#8e978b")
        if key=="fabric":
            for u in range(x-rx+2,x+rx-2,3):
                draw.line((u,y-ry,u,y+ry),fill=offset(c,-15))
                draw.line((u+1,y-ry,u+1,y+ry),fill=offset(c,7))
        if key in ("metal","bright"):
            for v in (y-3,y+3,y+6):
                draw.line((x-rx,v,x+rx,v),fill=offset(c,22 if v==y-3 else -18))
        if key=="dash":
            for u in (x-4,x,x+4):
                draw.ellipse((u-2,y-2,u+2,y+2),fill="#272f2d",outline="#a69b72")
                draw.point((u,y),fill="#dfc99e")
        if key=="badge":
            draw.rectangle((x-3,y-3,x+3,y+3),fill="#36443b")
            draw.polygon([(x,y-3),(x+3,y),(x,y+3),(x-3,y)],fill=("#e4c693","#e4cf9a","#c3d6c7")[i])
            draw.point((x,y),fill="#3b5348")
    # Even without any baked lighting, the die-cast fin and gauge patterns read at walking height.
    x,y=(36,121)
    for v in range(y-6,y+5):
        draw.line((x-12,v,x+12,v),fill="#a0a399" if v%2==0 else "#4a5551")
    save(finish,f"{root}-finish")

    lamps=Image.new("RGB",(ATLAS//2,ATLAS//2),"#28342f")
    emission=Image.new("RGB",lamps.size)
    l,e=ImageDraw.Draw(lamps),ImageDraw.Draw(emission)
    for key,(sx,sy) in LENSES.items():
        x,y=sx//2,sy//2
        hue={"head":"#fae3af","tail":"#b6453a","brake":"#f16b54","turn":"#e9a253","reverse":"#e6dcb5"}[key]
        l.rectangle((x-4,y-4,x+4,y+4),fill=offset(hue,-44))
        l.rectangle((x-3,y-3,x+3,y+3),fill=hue)
        e.rectangle((x-3,y-3,x+3,y+3),fill=hue)
        for u in range(x-3,x+4,2):
            l.line((u,y-3,u,y+3),fill=offset(hue,-15))
        l.line((x-2,y-3,x+2,y-3),fill=offset(hue,20))
        if i==2 and key in ("head","tail"):
            l.rectangle((x-6,y-4,x+6,y+4),fill=offset(hue,-52))
            l.rectangle((x-5,y-1,x+5,y+1),fill=hue)
            l.line((x-5,y-1,x+5,y-1),fill=offset(hue,18))
            e.rectangle((x-6,y-4,x+6,y+4),fill=0)
            e.rectangle((x-5,y-1,x+5,y+1),fill=hue)
    save(lamps,f"{root}-lamps")
    save(emission,f"{root}-emission")

(OUT/"regions.json").write_text(json.dumps({"finish":REGIONS,"lamps":LENSES,"atlas_texels":ATLAS},indent=2)+"\n")
