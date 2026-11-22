"""Paint the store's signs and season-independent textures on a 2×2 texel grid."""
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

path=ROOT.parents[1]/"kotlin-street/tools/swatches/atlas"
catalog=json.loads((path/"atlas.json").read_text())
atlas=Image.open(path/"atlas.png").convert("RGB")
for key,out in (("brick_red","brick"),("plank_horizontal","wood"),("roof_tar_brown","tar"),
                ("pavement","walk"),("block_courses","block")):
    x,y,w,h=(catalog[key][a] for a in ("x","y","w","h"))
    save(atlas.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST),out)

pal={
    "limestone":("#b6ae94","#d0c6a7","#929482","#aaa58e"),
    "iron":("#444d4a","#5c6257","#353c3d","#4e5650"),
    "oxblood":("#844d46","#a06350","#633b39","#8f5547"),
    "tile":("#b9b8a2","#d2cab0","#929583","#a9a996"),
    "warm":("#e0b878","#f1d291","#ba955b","#d4b17b"),
    "fluoro":("#c0c7ab","#dce2c9","#9fa98e","#ccd0b9"),
}
for i,(name,c) in enumerate(pal.items()):
    im=Image.new("RGB",(64,64),c[0]); d=ImageDraw.Draw(im); rng=Random(680+i)
    for _ in range(180):
        x,y=rng.randrange(60),rng.randrange(63)
        d.line((x,y,x+rng.randint(0,4),y),fill=rng.choice(c))
    if name in ("limestone","tile"):
        for y in range(0,64,8 if name=="tile" else 16):
            d.line((0,y,63,y),fill=c[2])
            d.line((0,y+1,63,y+1),fill=c[1])
            for x in range(0,64,8 if name=="tile" else 16):
                d.line((x,y,x,min(y+7 if name=="tile" else y+15,63)),fill=c[2])
    save(im,name)

for state in ("1925","1970","2000","closed"):
    dark=state=="closed"
    im=Image.new("RGBA",(64,64),(55,67,70,224) if dark else (103,133,129,43))
    e=Image.new("RGB",(64,64),(0,0,0)); a=ImageDraw.Draw(im); b=ImageDraw.Draw(e)
    if not dark:
        b.rectangle((0,0,63,63),fill=(224,190,153))
    for x,y in ((5,24),(15,29),(34,50),(52,22)):
        for k in range(9):
            a.line((x+k,y-k,x+k+1,y-k),fill=(161,183,170,126 if dark else 102))
    # Obscure edge seals and keep them unlit in every state.
    for x in (0,63):
        a.line((x,0,x,63),fill=(52,56,50,255)); b.line((x,0,x,63),fill=(0,0,0))
    for y in (0,63):
        a.line((0,y,63,y),fill=(52,56,50,255)); b.line((0,y,63,y),fill=(0,0,0))
    save(im,"glass-"+state); save(e,"pane-mask-"+state)

signs={
    "name-1925":(0,115,19,"MARLOW DIME","#814c43",(233,200,133),2),
    "name-1970":(20,113,15,"MARLOW VARIETY","#d1ccb0",(62,86,81),1),
    "name-2000":(36,116,17,"DOLLAR COVE","#4d7464",(238,224,174),2),
    "name-closed":(54,113,16,"MARLOW VARIETY","#716d60",(190,182,151),1),
    "lease":(71,56,12,"FOR LEASE","#d1b992",(103,65,53),1),
    "value":(84,34,19,"VALUE","#b98857",(242,217,155),1),
    "sale":(104,31,17,"SALE","#816459",(236,210,155),1),
    "receiving":(122,67,11,"RECEIVING","#53615e",(230,218,185),1),
    "candy":(134,67,11,"SODA CANDY","#7e4e46",(230,203,144),1),
}
im=Image.new("RGB",(128,256),("#55483c")); d=ImageDraw.Draw(im)
e=Image.new("RGB",im.size); m=ImageDraw.Draw(e)
for name,(y,w,h,line,bg,text,scale) in signs.items():
    d.rectangle((0,y,w-1,y+h-1),fill="#ab946f")
    d.rectangle((1,y+1,w-2,y+h-2),fill=bg)
    start=(w-(len(line)*6-1)*scale)//2
    for i,ch in enumerate(line):
        for j,row in enumerate(FONT.get(ch,())):
            for k,bit in enumerate(row):
                if bit=="1":
                    x=start+(i*6+k)*scale; z=y+(h-7*scale)//2+j*scale
                    rect=(x,z,x+scale-1,z+scale-1)
                    d.rectangle(rect,fill=text)
                    if name in ("name-1925","name-1970","name-2000"):
                        m.rectangle(rect,fill=(232,208,169))
save(im,"signs"); save(e,"sign-mask")
(OUT/"sign-regions.json").write_text(json.dumps({key:list(v[:3]) for key,v in signs.items()},indent=2)+"\n")
