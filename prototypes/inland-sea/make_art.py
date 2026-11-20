"""Author small repeatable foundry surfaces on Timerpg's art-pixel grid."""
from pathlib import Path
import json, random
from PIL import Image, ImageDraw
from pixel_font import FONT

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "textures"
OUT.mkdir(exist_ok=True)
atlas_dir = ROOT.parents[1] / "kotlin-street/tools/swatches/atlas"
atlas = Image.open(atlas_dir / "atlas.png")
entry = json.loads((atlas_dir / "atlas.json").read_text())["pavement"]
x, y, w, h = (entry[k] for k in ("x", "y", "w", "h"))
paving = atlas.crop((x,y,x+w,y+h)).convert("RGB")

def save(im, name):
    im.resize((im.width*2, im.height*2),Image.Resampling.NEAREST).save(OUT/(name+".png"))

save(paving.resize((w//2,h//2), Image.Resampling.NEAREST), "paving")
colors = {
    "verdigris": ("#447c79","#518986","#366d6b","#67968a"),
    "deep": ("#244c54","#315a61","#20434b","#41656b"),
    "brass": ("#c49149","#d6ab68","#ae803f","#e2bc79"),
    "ivory": ("#d6c393","#e0cea6","#c2ac81","#e9deba"),
    "basalt": ("#3d5459","#4b6365","#354b50","#637575"),
    "cypress": ("#495f48","#526c50","#405841","#67805d"),
    "water": ("#467678","#568384","#426e72","#67908c"),
    "lens": ("#edbf71","#ffdd96","#cf995b","#ffedbe")
}
for index,(name,pal) in enumerate(colors.items()):
    rand=random.Random(1913+index)
    im=Image.new("RGB",(64,64),pal[0])
    draw=ImageDraw.Draw(im)
    for _ in range(135):
        x,y=rand.randrange(64),rand.randrange(64)
        draw.line((x,y,x+rand.randrange(1,5),y),fill=rand.choice(pal[1:]))
    if name in ("verdigris","deep"):
        for y in (0,32):
            x=8 if y else 40
            draw.line((0,y,63,y), fill=pal[2])
            draw.line((x,y,x,y+31), fill=pal[2])
            for xx in range(5,64,12):
                draw.point((xx,y+2),fill=pal[3])
    save(im,name)
im=Image.new("RGB",(128,32),"#293e40")
d=ImageDraw.Draw(im)
d.rectangle((1,1,126,30),outline="#b18b51")
def letters(s,x,y,c):
    for char in s:
        for j,row in enumerate(FONT.get(char,[])):
            for i,bit in enumerate(row):
                if bit=="1":d.point((x+i,y+j),fill=c)
        x+=6
letters("THE INLAND SEA",23,7,"#eee0b9")
letters("FOUNDRY COURT",26,18,"#c29e63")
save(im,"name")
