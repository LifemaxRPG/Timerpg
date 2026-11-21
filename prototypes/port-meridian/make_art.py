"""Paint original port surfaces and wayfinding at Timerpg's art pixel scale."""
from pathlib import Path
from random import Random
import json

from PIL import Image, ImageDraw
from pixel_font import FONT

ROOT=Path(__file__).resolve().parent
OUT=ROOT/"textures"
OUT.mkdir(exist_ok=True)

def save(name, image):
    image.resize((image.width*2,image.height*2),Image.Resampling.NEAREST).save(OUT/f"{name}.png")

atlas_path=ROOT.parents[1]/"kotlin-street/tools/swatches/atlas"
atlas=Image.open(atlas_path/"atlas.png").convert("RGB")
entries=json.loads((atlas_path/"atlas.json").read_text())
for key,name in (("plank_horizontal","timber"),):
    x,y,w,h=(entries[key][coord] for coord in ("x","y","w","h"))
    save(name,atlas.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST))

colors={
    "stone":("#888b7c","#a4a492","#717569","#959784"),
    "road":("#515756","#616660","#424948","#575d57"),
    "brick":("#8a6352","#ac8069","#735346","#94735e"),
    "cream":("#c6bfaa","#d8d1b9","#9c9d8c","#b2b2a0"),
    "copper":("#647970","#78928a","#4c615f","#697f78"),
    "steel":("#414f52","#596668","#323e42","#4b5b5c"),
    "glass":("#536f72","#7c9794","#3f5b61","#638583"),
    "amber":("#cca375","#e9c499","#927650","#d8b086"),
    "ochre":("#b8894d","#d3ad69","#84663f","#c39a5d"),
    "rubber":("#373e3d","#4d5753","#293231","#414a45"),
}
for index,(name,c) in enumerate(colors.items()):
    im=Image.new("RGB",(64,64),c[0])
    d=ImageDraw.Draw(im)
    rng=Random(183+index)
    for _ in range(155):
        x,y=rng.randrange(60),rng.randrange(63)
        d.line((x,y,x+rng.randrange(1,5),y),fill=rng.choice(c))
        if rng.randrange(5)==0:
            d.point((x,y+1),fill=c[2])
    if name=="brick":
        for y in range(0,64,4):
            d.line((0,y,63,y),fill="#ad9180")
            for x in range((y//4%2)*4,64,8):
                d.line((x,y,x,y+3),fill="#ad9180")
                d.line((x+1,y+1,x+6,y+1),fill=c[1])
    if name=="copper":
        for x in range(0,64,12):
            d.line((x,0,x,63),fill=c[2])
            d.line((x+1,0,x+1,63),fill=c[1])
    if name in ("stone","cream"):
        for y in (4,27,51):
            d.line((0,y,63,y),fill=c[2])
            d.line((0,y+1,63,y+1),fill=c[1])
    if name=="glass":
        for i in (0,32):
            d.rectangle((i,0,i+31,63),outline=c[2])
            d.line((i+1,1,i+1,61),fill=c[1])
            d.line((i+3,56,i+19,40), fill=c[3],width=2)
            d.line((i+12,27,i+26,12),fill=c[3],width=3)
    save(name,im)

sign=Image.new("RGB",(256,128),"#2f4749")
draw=ImageDraw.Draw(sign)
for y,w,h,words in [(0,168,22,"PORT MERIDIAN"),(23,133,15,"DEPARTURES"),(39,116,15,"ARRIVALS"),
                    (55,117,17,"BERTH 01"),(73,101,14,"GATE 01"),(88,128,15,"PASSENGER PORT"),
                    (104,94,13,"SERVICE")]:
    draw.rectangle((0,y,w-1,y+h-1),fill="#ac9e82")
    draw.rectangle((1,y+1,w-2,y+h-2),fill="#334f50")
    factor=2 if (len(words)*6-1)*2<=w-8 and h>=17 else 1
    left=(w-(len(words)*6-1)*factor)//2
    for i,ch in enumerate(words):
        for dy,row in enumerate(FONT.get(ch,())):
            for dx,pix in enumerate(row):
                if pix=="1":
                    px=left+(i*6+dx)*factor
                    py=y+(h-7*factor)//2+dy*factor
                    draw.rectangle((px,py,px+factor-1,py+factor-1),fill="#e8ddc3")
save("sign",sign)
