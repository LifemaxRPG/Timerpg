"""Build the rest area's pixel textures at two texels per art pixel."""
import json
from pathlib import Path
from random import Random

from PIL import Image, ImageDraw
from pixel_font import FONT

ROOT=Path(__file__).resolve().parent
OUT=ROOT/"textures"
OUT.mkdir(exist_ok=True)


def save(name,image):
    image.resize((image.width*2,image.height*2),Image.Resampling.NEAREST).save(OUT/f"{name}.png")


atlas_dir=ROOT.parents[1]/"kotlin-street/tools/swatches/atlas"
atlas=Image.open(atlas_dir/"atlas.png").convert("RGB")
catalog=json.loads((atlas_dir/"atlas.json").read_text())
for name in ("pavement","asphalt_worn","brick_courses","roof_tin_blue","plank_horizontal"):
    x,y,w,h=[catalog[name][key] for key in ("x","y","w","h")]
    art=atlas.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST)
    recolor={
        "brick_courses":{(70,70,76):(111,97,78),(90,90,90):(154,128,99),
            (130,130,130):(180,155,119),(170,170,170):(194,170,136),(220,220,220):(209,192,158)},
        "roof_tin_blue":{(77,155,185):(88,111,115),(78,174,194):(100,124,127),
            (53,118,176):(77,96,105),(54,88,146):(62,79,90)}
    }.get(name,{})
    art.putdata([recolor.get(c,c) for c in art.get_flattened_data()])
    save(name,art)
for i,(name,palette) in enumerate({
    "grass":("#69775d","#758468","#55644f","#68745e"),
    "trim":("#d4c5a2","#e1d3b3","#b7ad93","#a9a28b"),
    "teal":("#465f5b","#5c726a","#364e4c","#617970"),
    "glass":("#576d70","#768481","#43595e","#a18d65"),
    "lamp":("#9f8965","#d2b98c","#766d52","#bda174"),
}.items()):
    rng=Random(7463+i)
    im=Image.new("RGB",(64,64),palette[0])
    d=ImageDraw.Draw(im)
    for _ in range(65):
        x,y=rng.randrange(60),rng.randrange(64)
        d.line((x,y,x+rng.randrange(1,5),y),fill=rng.choice(palette))
    if name=="glass":
        d.rectangle((0,0,63,63),outline=palette[2],width=2)
        d.line((2,7,61,7),fill=palette[1])
        d.line((3,31,19,20),fill=palette[1])
    save(name,im)

im=Image.new("RGB",(256,128),"#314b47")
d=ImageDraw.Draw(im)


def label(text,rect,foreground="#e6dcc0",background="#344e49",scale=1):
    x,y,w,h=rect
    d.rectangle((x,y,x+w-1,y+h-1),fill=background)
    d.rectangle((x+1,y+1,x+w-2,y+h-2),outline="#8a9a81")
    position=x+(w-(len(text)*6-1)*scale)//2
    for ch in text:
        for j,row in enumerate(FONT.get(ch,())):
            for i,bit in enumerate(row):
                if bit=="1":
                    xx=position+i*scale
                    yy=y+(h-7*scale)//2+j*scale
                    d.rectangle((xx,yy,xx+scale-1,yy+scale-1),fill=foreground)
        position+=6*scale


label("PINE BEND REST AREA",(0,0,128,16))
label("RESTROOMS",(0,16,64,16))
label("VENDING",(64,16,64,16))
label("TRUCKS",(0,32,64,16))
label("CARS",(64,32,64,16))
label("PICNIC",(0,48,64,16))
for i,c in enumerate(("#9b684f","#537c73","#82744e")):
    x=138+i*20
    d.rectangle((x,3,x+15,29),fill=c,outline="#303f3c")
    d.rectangle((x+2,6,x+11,22),fill="#3e504c")
    for row in range(3):
        d.line((x+3,8+row*5,x+10,8+row*5),fill="#c1ab82")
        for col in range(3):
            d.point((x+3+col*3,7+row*5),fill=("#c1825c","#d2ba82","#8d9d84")[col])
    d.rectangle((x+11,12,x+13,15),fill="#d5bf91")
    d.rectangle((x+3,24,x+11,26),fill="#32413d")
save("signs",im)
