"""Original marine pixel textures and Timerpg deck swatches."""
import json
from pathlib import Path
from random import Random

from PIL import Image, ImageDraw
from pixel_font import FONT

root = Path(__file__).resolve().parent
textures = root/"textures"
textures.mkdir(exist_ok=True)


def save(name, im):
    im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(textures/f"{name}.png")


palette = {
    "navy":("#30444e","#3e5660","#263943","#485a60"),
    "white":("#ccc7b4","#ded7c5","#aead9d","#bcb8a6"),
    "copper":("#ab6146","#bd7553","#88513e","#c68963"),
    "teal":("#527970","#698f85","#456a62","#7b9b88"),
    "water":("#48888a","#559699","#38787b","#7aa8a3"),
    "rubber":("#494f49","#626861","#343c3b","#535956"),
    "brass":("#b6965b","#d6b47a","#8d764d","#c5a975"),
    "amber":("#c5a474","#efce91","#7d6950","#d7b989"),
}
for index,(name,(base,light,dark,mid)) in enumerate(palette.items()):
    rng=Random(926+index)
    im=Image.new("RGB",(128,128),base)
    draw=ImageDraw.Draw(im)
    for _ in range(220):
        x,y=rng.randrange(123),rng.randrange(128)
        draw.line((x,y,x+rng.randrange(1,5),y),fill=rng.choice((light,dark,mid,mid)))
    if name in ("navy","white","copper"):
        for y in range(31,128,32):
            draw.line((0,y,127,y),fill=mid)
    if name=="water":
        for _ in range(120):
            x,y=rng.randrange(114),rng.randrange(128)
            draw.line((x,y,x+rng.randrange(3,14),y),fill=rng.choice((light,mid,base)))
    save(name,im)

atlas_dir=root.parents[1]/"kotlin-street/tools/swatches/atlas"
atlas=Image.open(atlas_dir/"atlas.png").convert("RGB")
items=json.loads((atlas_dir/"atlas.json").read_text())
x,y,w,h=[items["plank_horizontal"][f] for f in ("x","y","w","h")]
save("teak",atlas.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST))

# Repeat at exactly 40 art pixels horizontally and vertically per cabin.
im=Image.new("RGB",(40,40),"#c9c2ac")
draw=ImageDraw.Draw(im)
draw.rectangle((2,3,37,36),fill="#787f75")
draw.rectangle((4,4,35,26),fill="#344e54")
draw.rectangle((7,6,17,25),fill="#516267")
draw.rectangle((20,6,31,25),fill="#688080")
draw.rectangle((8,12,16,24),fill="#7f8272")
draw.line((18,5,18,26),fill="#c6c0a5",width=2)
draw.line((4,8,35,8),fill="#87958b")
draw.rectangle((3,27,36,35),fill="#5e746d")
draw.line((2,27,37,27),fill="#d1c9ad")
for x in (5,14,25,34):
    draw.line((x,28,x,35),fill="#8f9b8d")
draw.line((0,37,39,37),fill="#e4d7b9")
draw.line((0,38,39,38),fill="#a39881")
save("balconies",im)

im=Image.new("RGB",(32,32),"#344e56")
draw=ImageDraw.Draw(im)
for y,c in ((0,"#536e73"),(7,"#678186"),(14,"#405e65"),(26,"#304a51")):
    draw.rectangle((0,y,31,min(31,y+7)),fill=c)
draw.line((0,0,31,0),fill="#96aaa3")
draw.line((0,31,31,31),fill="#1e343a")
draw.line((0,1,0,30),fill="#263a40",width=2)
draw.line((2,1,2,30),fill="#87968e")
draw.line((11,18,23,13),fill="#6e8788")
save("glazing",im)

im=Image.new("RGB",(192,32),"#314752")
draw=ImageDraw.Draw(im)
draw.line((0,0,191,0),fill="#b89b66")
draw.line((0,31,191,31),fill="#9d8154")
text="COPPERWAKE"
x=(192-len(text)*12)//2
for letter in text:
    for y,row in enumerate(FONT[letter]):
        for i,value in enumerate(row):
            if value=="1":
                draw.rectangle((x+i*2,8+y*2,x+i*2+1,9+y*2),fill="#e0c693")
    x+=12
save("name",im)
