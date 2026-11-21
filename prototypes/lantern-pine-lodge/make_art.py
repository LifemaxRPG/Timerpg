"""Create matte woodland lodge textures with whole 2×2 texel art pixels."""
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
for name in ("plank_horizontal","roof_slate"):
    x,y,w,h=[catalog[name][key] for key in ("x","y","w","h")]
    art=atlas.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST)
    if name=="roof_slate":
        art.putdata([(round(.62*r+15),round(.69*g+15),round(.70*b+15)) for r,g,b in art.get_flattened_data()])
    save(name,art)

palettes={
    "needle_ground":("#6c6b53","#77755b","#565947","#64644d"),
    "pine":("#4c6250","#607257","#3d5143","#53684e"),
    "path":("#a09477","#b8a686","#85795f","#9f9172"),
    "hewn_log":("#93694a","#a97a55","#765338","#9a704c"),
    "trim":("#c2a17b","#d1b38a","#a78564","#b2926f"),
    "iron":("#3a4844","#4c5952","#293b3a","#53615a"),
    "lamp":("#e0bc7d","#f1d7a4","#97764d","#c9a66d"),
}
for i,(name,palette) in enumerate(palettes.items()):
    rng=Random(234+i)
    im=Image.new("RGB",(128,128),palette[0])
    draw=ImageDraw.Draw(im)
    for j in range(240):
        x,y=rng.randrange(118),rng.randrange(128)
        width=rng.randrange(2,11) if name in ("hewn_log","trim") else rng.randrange(1,7)
        draw.line((x,y,x+width,y),fill=rng.choice(palette))
        if name in ("needle_ground","pine") and j%3==0:
            draw.point((x+1,y-1),fill=palette[2])
    if name=="hewn_log":
        for x,y in ((9,11),(59,56),(90,28),(19,87),(88,104)):
            draw.line((x,y,x+19,y),fill=palette[2])
            draw.arc((x+6,y-2,x+14,y+2),0,359,fill=palette[2])
            draw.line((x+7,y-1,x+11,y-1),fill=palette[1])
    save(name,im)

rng=Random(1947)
im=Image.new("RGB",(128,128),"#514f44")
d=ImageDraw.Draw(im)
colors=("#706c5a","#807969","#706b61","#91836e","#7a786b")
for row in range(8):
    y=row*16
    for x in range(-24+(row%2)*8,128,24):
        offset=rng.randrange(4)
        d.polygon([(x+2,y+1),(x+19,y+1),(x+23,y+4),(x+22,y+12),
                   (x+18,y+15),(x+4,y+15),(x,y+11),(x+offset,y+5)],
                  fill=rng.choice(colors))
        d.line((x+4,y+2,x+17,y+2),fill="#9c9075")
        d.line((x+3,y+13,x+19,y+13),fill="#5e5b4e")
        for _ in range(8):
            xx=x+rng.randrange(4,18)
            yy=y+rng.randrange(3,12)
            d.line((xx,yy,xx+2,yy),fill=rng.choice(colors))
save("fieldstone",im)

im=Image.new("RGB",(128,128),"#425552")
d=ImageDraw.Draw(im)
for i in range(4):
    u,v=(i%2)*64,(i//2)*64
    d.rectangle((u+1,v+1,u+62,v+62),fill=("#677b73","#718477","#566e69","#647871")[i])
    d.line((u+4,v+7,u+59,v+7),fill="#899483")
    for x,y in ((4,9),(18,12),(32,7)):
        d.line((u+x,v+y,u+x+12,v+y-5),fill="#80968d")
    for j in range(5):
        d.line((u+4,v+47+j,u+60,v+47+j),fill=("#728073","#807d69","#91836a")[j%3])
save("glass",im)

im=Image.new("RGB",(48,18),"#41574b")
d=ImageDraw.Draw(im)
d.rectangle((0,0,47,17),outline="#9b9d79")
d.line((1,1,46,1),fill="#c2ac82")
for text,y in (("LANTERN",2),("PINE",10)):
    x=(48-(len(text)*6-1))//2
    for ch in text:
        for i,row in enumerate(FONT.get(ch,[])):
            for j,b in enumerate(row):
                if b=="1":
                    d.point((x+j,y+i),fill="#eadabd")
        x+=6
save("name",im)
