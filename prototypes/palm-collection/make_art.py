"""Paint physical-scale bark and foliage atlases with hard art-pixel edges."""
from pathlib import Path
from random import Random

from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parent
OUT=ROOT/"textures"
OUT.mkdir(exist_ok=True)
bark=[
    ("#928b79","#a39a87","#6d6b61","#847c6a"),
    ("#917b61","#ab9273","#625846","#806c57"),
    ("#665448","#8a7154","#493d34","#725d49"),
    ("#a99863","#c3b575","#736e4a","#8b824f")
]
leaf=[
    ("#4e7354","#659060","#395e47","#839c6c"),
    ("#70824c","#829d60","#546d43","#a2a972"),
    ("#59786d","#719286","#3d605b","#8aa392"),
    ("#8c7853","#9e895f","#68573f","#a99570")
]


def atlas(name,palette,wood):
    image=Image.new("RGB",(128,128))
    draw=ImageDraw.Draw(image)
    for i,(base,light,dark,mid) in enumerate(palette):
        rng=Random(3247+41*i+31*wood)
        ox,oy=i%2*64,i//2*64
        draw.rectangle((ox,oy,ox+63,oy+63),fill=base)
        for y in range(0,64,4 if wood else 5):
            if wood and i in (0,3):
                for x in range(64):
                    y0=min(63,y+((x//10+y//4)%4==0))
                    draw.point((ox+x,oy+y0),fill=dark if rng.random()<.78 else mid)
                    if y0<63 and rng.random()<.73:
                        draw.point((ox+x,oy+y0+1),fill=light)
            elif wood and i==1:
                for x in range(-4,64,6):
                    for yy in range(4):
                        ax=min(63,max(0,x+yy+(y%8)))
                        draw.point((ox+ax,oy+min(y+yy,63)),fill=dark)
                        if 0<ax<63:
                            draw.point((ox+ax-1,oy+min(y+yy,63)),fill=light)
            for x in range(1,58,7):
                a=ox+x+rng.randrange(4)
                b=oy+min(63,y+rng.randrange(4))
                if wood and i==2:
                    draw.line((a,b,a,oy+min(63,b-oy+2+rng.randrange(7))),fill=rng.choice((dark,mid,light)))
                else:
                    draw.line((a,b,min(ox+63,a+1+rng.randrange(4)),b),fill=rng.choice((mid,light,dark,base)))
    image.resize((256,256),Image.Resampling.NEAREST).save(OUT/name)


atlas("palm-bark.png",bark,True)
atlas("palm-leaves.png",leaf,False)
