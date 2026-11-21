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
    "hull":("#eeefe8","#f5f5ee","#d8dedb","#e2e7e1"),
    "ivory":("#f1efe6","#f7f5ee","#dddcd3","#e9e7dc"),
    "boot":("#dde1db","#e9ebe5","#c9d1ce","#d3dad5"),
    "trim":("#535d5b","#68736c","#374a49","#536866"),
    "champagne":("#a38a5e","#c2aa75","#84734e","#a69670"),
    "olive":("#d7d7ce","#e4e3d9","#bdbfb7","#cccec4"),
    "linen":("#e3dece","#efe9db","#cdc7b9","#dcd7c8"),
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

im=Image.new("RGB",(42,11),palette["hull"][0])
draw=ImageDraw.Draw(im)
for i,ch in enumerate("VESPER"):
    for y,row in enumerate(FONT[ch]):
        for x,p in enumerate(row):
            if p=="1":
                draw.point((3+i*6+x,2+y),fill="#414f50")
save("name",im)
