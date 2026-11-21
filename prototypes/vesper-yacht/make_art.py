"""Author crisp marine pixel textures on Timerpg's physical texel grid."""
import json
from pathlib import Path
from random import Random

from PIL import Image, ImageDraw
from pixel_font import FONT

ROOT=Path(__file__).resolve().parent
OUT=ROOT/"textures"
OUT.mkdir(exist_ok=True)


def save(name,im):
    im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(OUT/f"{name}.png")


atlas_dir=ROOT.parents[1]/"kotlin-street/tools/swatches/atlas"
atlas=Image.open(atlas_dir/"atlas.png").convert("RGB")
catalog=json.loads((atlas_dir/"atlas.json").read_text())
x,y,w,h=[catalog["plank_horizontal"][key] for key in ("x","y","w","h")]
save("teak",atlas.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST))

palette={
    "hull":("#344c58","#455d64","#2d404b","#52656a"),
    "ivory":("#c8c2ac","#dcd2bb","#a5a597","#b7b5a5"),
    "boot":("#915c4a","#a6634d","#734b40","#a16952"),
    "trim":("#535d5b","#68736c","#374a49","#536866"),
    "champagne":("#a38a5e","#c2aa75","#84734e","#a69670"),
    "olive":("#657969","#798c78","#4b6258","#6e7f68"),
    "linen":("#bbab8b","#cfc0a0","#998c74","#c0b096"),
    "amber":("#bda06f","#dfc18a","#927954","#cdad77"),
}
for i,(name,cols) in enumerate(palette.items()):
    im=Image.new("RGB",(128,128),cols[0])
    d=ImageDraw.Draw(im)
    rng=Random(915+i)
    for _ in range(190):
        x,y=rng.randrange(122),rng.randrange(127)
        d.line((x,y,x+rng.randrange(1,6),y),fill=rng.choice(cols))
        if rng.random()<.3:
            d.point((x,y+1),fill=cols[2])
    if name in ("ivory","hull"):
        for y in (32,87):
            d.line((0,y,127,y),fill=cols[3])
            d.line((0,y+1,127,y+1),fill=cols[1])
    save(name,im)

im=Image.new("RGB",(96,32),"#344c52")
draw=ImageDraw.Draw(im)
for i in range(3):
    base=i*32
    draw.rectangle((base+1,1,base+31,30),fill=("#48646a","#526b6c","#405b63")[i])
    draw.rectangle((base+2,2,base+30,8),fill="#68817e")
    draw.line((base+2,30,base+31,30),fill="#728883")
    draw.line((base+3,18,base+20,11),fill="#78908a",width=2)
    draw.line((base+8,23,base+22,18),fill="#657d79")
save("glass",im)

im=Image.new("RGB",(42,11),"#344c58")
draw=ImageDraw.Draw(im)
for i,ch in enumerate("VESPER"):
    for y,row in enumerate(FONT[ch]):
        for x,p in enumerate(row):
            if p=="1":
                draw.point((3+i*6+x,2+y),fill="#d8c394")
save("name",im)
