"""Paint matte bark scars and four foliage tones on Timerpg's art-pixel grid."""
from pathlib import Path
from random import Random

from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parent
OUT=ROOT/"textures"
OUT.mkdir(exist_ok=True)


def save(name,image):
    image.resize((image.width*2,image.height*2),Image.Resampling.NEAREST).save(OUT/f"{name}.png")


rng=Random(1095)
im=Image.new("RGB",(64,128),"#95806b")
draw=ImageDraw.Draw(im)
for i in range(330):
    x,y=rng.randrange(64),rng.randrange(128)
    c=rng.choice(("#a58e75","#887461","#897867","#aa947c","#756758"))
    draw.line((x,y,x,y+rng.randrange(1,6)),fill=c)
for y in range(4,128,4):
    for x in range(64):
        ridge=y+((x//8+y//4)%3==0)
        if rng.random()<.94:
            draw.point((x,ridge),fill=rng.choice(("#5e544a","#6f6154","#776656")))
        if rng.random()<.73:
            draw.point((x,ridge+1),fill=rng.choice(("#b19b82","#a99177","#9b876c")))
save("ringed-bark",im)

# Ranges borrow the warm-shadow and sunlit-leaf relationship in TreeKit.
im=Image.new("RGB",(128,128))
draw=ImageDraw.Draw(im)
palettes=[
    ("#4d7752","#638761","#3b634a","#829b66"),
    ("#648453","#7d975b","#506d48","#a0a76d"),
    ("#3c634c","#52765a","#305242","#63845d"),
    ("#796f4f","#91815b","#655c44","#a18b62")
]
for i,(base,light,dark,glint) in enumerate(palettes):
    x0,y0=(i%2)*64,(i//2)*64
    draw.rectangle((x0,y0,x0+63,y0+63),fill=base)
    for y in range(1,63,5):
        for x in range(1,58,9):
            dx=x0+x+rng.randrange(5)
            dy=y0+y+rng.randrange(3)
            draw.line((dx,dy,dx+rng.randrange(2,6),dy),fill=rng.choice((dark,light,light,glint)))
            if rng.random()<.6:
                draw.point((dx,dy+1),fill=dark)
save("frond-atlas",im)
