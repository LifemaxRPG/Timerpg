"""Author Needle Point's swatches, coping and signs on the 2×2 texel art grid."""
from pathlib import Path
from random import Random
import json

from PIL import Image, ImageDraw
from pixel_font import FONT

ROOT=Path(__file__).resolve().parent
OUT=ROOT/"textures"
OUT.mkdir(exist_ok=True)

def save(im,name):
    im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(OUT/f"{name}.png")

atlas_path=ROOT.parents[1]/"kotlin-street/tools/swatches/atlas"
catalog=json.loads((atlas_path/"atlas.json").read_text())
atlas=Image.open(atlas_path/"atlas.png").convert("RGB")
for key,name in (("plank_horizontal","timber"),("roof_tin_red","cottage-roof")):
    x,y,w,h=[catalog[key][axis] for axis in ("x","y","w","h")]
    save(atlas.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST),name)

attrs={
    "whitewash":("#cec8b0","#e1dac1","#b4b29f","#d2ccb7"),
    "concrete":("#c1c7bd","#d6d8cc","#a2ada5","#bcc3b9"),
    "siding":("#b9b8a0","#d1ccb0","#969d8c","#b2b59e"),
    "cladding":("#587475","#75918c","#415c60","#64827f"),
    "red":("#91584b","#b67961","#70423b","#9b614f"),
    "roof-blue":("#445d62","#628083","#33494f","#516c6f"),
    "iron":("#454c47","#667068","#333d3b","#515c53"),
    "glass":("#50666a","#80968e","#394e55","#668080"),
    "amber":("#dfb675","#f0d391","#b68b58","#e3c183"),
    "ivory-lens":("#dfcf9d","#eee1b8","#aab3a0","#dfd6ae"),
    "rock":("#636763","#858378","#4d5551","#747870"),
    "path":("#969685","#b0ae99","#797f72","#8b8c7c"),
    "grass":("#7c8667","#96a07a","#637357","#89916e"),
}
for index,(name,p) in enumerate(attrs.items()):
    im=Image.new("RGB",(64,64),p[0])
    d=ImageDraw.Draw(im)
    rng=Random(591+index)
    for _ in range(250):
        x,y=rng.randrange(61),rng.randrange(63)
        n=rng.randrange(1,4)
        d.line((x,y,x+n,y),fill=rng.choice(p))
        if rng.randrange(6)==0:
            d.point((x,y+1),fill=p[2])
    if name in ("whitewash","siding","path","cladding"):
        step={"whitewash":8,"siding":3,"path":16,"cladding":12}[name]
        for y in range(0,64,step):
            d.line((0,y,63,y),fill=p[2])
            d.line((0,y+1,63,y+1),fill=p[1])
            if name in ("whitewash","path"):
                for x in range((y//step%2)*8,64,16):
                    d.line((x,y,x,min(y+step,63)),fill=p[3])
    if name=="concrete":
        for x in (8,31,53):
            d.line((x,0,x,63),fill=p[3])
            for y in (6,32,54):
                d.point((x+2,y),fill=p[2])
    if name in ("red","roof-blue"):
        for x in range(0,64,9):
            d.line((x,0,x,63),fill=p[2])
            d.line((x+1,0,x+1,63),fill=p[1])
    if name=="glass":
        for x in (0,32):
            d.rectangle((x,0,x+31,63),outline=p[2])
            d.line((x+2,57,x+22,25),fill=p[3],width=2)
            d.line((x+10,34,x+25,10),fill=p[3])
    if name in ("amber","ivory-lens"):
        for y in range(0,64,3):
            d.line((0,y,63,y),fill=p[1] if y%2 else p[3])
    save(im,name)

sign=Image.new("RGB",(128,64),"#30484a")
d=ImageDraw.Draw(sign)
for y,w,h,words in [(0,111,15,"NEEDLE POINT"),(17,83,13,"KEEPER"),(32,108,13,"LIGHT STATION"),(47,79,13,"SERVICE")]:
    d.rectangle((0,y,w-1,y+h-1),fill="#ae9a7c")
    d.rectangle((1,y+1,w-2,y+h-2),fill="#364e4e")
    left=(w-(len(words)*6-1))//2
    for i,ch in enumerate(words):
        for j,line in enumerate(FONT.get(ch,())):
            for x,b in enumerate(line):
                if b=="1":
                    d.point((left+i*6+x,y+(h-7)//2+j),fill="#eae2c6")
save(sign,"sign")
