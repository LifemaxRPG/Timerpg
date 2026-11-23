"""Prepare two-texel artwork and repaintable school signs."""
import json
from pathlib import Path
from random import Random

from PIL import Image, ImageDraw
from pixel_font import FONT

ROOT = Path(__file__).resolve().parent
OUT = ROOT/"textures"
OUT.mkdir(exist_ok=True)
LIB = ROOT.parents[1]/"kotlin-street/tools/swatches/atlas"
atlas = Image.open(LIB/"atlas.png").convert("RGB")
catalogue = json.loads((LIB/"atlas.json").read_text())

def swatch(key):
    x,y,w,h = (catalogue[key][dim] for dim in ("x","y","w","h"))
    return atlas.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST)

def save(key, image):
    image.resize((image.width*2,image.height*2),Image.Resampling.NEAREST).save(OUT/f"{key}.png")

for output,key in (("brick","brick_red"),("wood","plank_horizontal"),("gravel","ballast"),
                   ("roof_slate","roof_slate")):
    save(output,swatch(key))

def painted(name, key, rgb, mode, seed):
    source = max(swatch(key).getcolors(),key=lambda item:item[0])[1]
    rgb = tuple(round(a*.13+b*.87) for a,b in zip(source,rgb))
    palette = [tuple(max(0,min(255,c+shade)) for c in rgb) for shade in (-17,-8,0,10)]
    image = Image.new("RGB",(64,64),palette[2])
    draw = ImageDraw.Draw(image)
    rng = Random(seed)
    for _ in range(150):
        x,y = rng.randrange(64),rng.randrange(64)
        draw.line((x,y,x+rng.randrange(1,5),y),fill=rng.choice(palette))
    if mode=="siding":
        for row in range(0,64,4):
            draw.line((0,row,63,row),fill=palette[0])
            draw.line((0,row+1,63,row+1),fill=palette[3])
    if mode=="tile":
        for i in range(0,64,8):
            draw.line((i,0,i,63),fill=palette[1])
            draw.line((0,i,63,i),fill=palette[1])
    if mode=="seams":
        for i in (0,32):
            draw.line((0,i,63,i),fill=palette[1])
            draw.line((0,i+1,63,i+1),fill=palette[3])
    save(name,image)

for spec in (
    ("clapboard","plank_horizontal",(219,218,190),"siding",21),
    ("faded","plank_horizontal",(188,180,154),"siding",17),
    ("metal","roof_membrane_green",(60,76,65),"plain",9),
    ("terracotta","roof_tin_brown",(163,95,62),"plain",11),
    ("grass","roof_membrane_green",(96,120,73),"plain",95),
    ("tile","pavement",(184,177,154),"tile",23),
    ("roof","roof_membrane_grey",(98,107,112),"seams",12),
    ("asphalt","asphalt",(107,108,100),"plain",26),
    ("chalk","pavement",(218,205,164),"plain",29),
):
    painted(*spec)

for dark in (False,True):
    image = Image.new("RGB",(16,16),"#394842")
    mask = Image.new("RGB",(16,16))
    dr = ImageDraw.Draw(image)
    dr.rectangle((1,1,14,14),fill="#536160" if dark else "#82928b")
    dr.line((2,1,14,1),fill="#878c75" if dark else "#bac4a9")
    dr.line((3,12,9,6),fill="#76817b" if dark else "#a1aca0",width=2)
    if not dark:
        ImageDraw.Draw(mask).rectangle((1,1,14,14),fill="#e2b978")
    suffix = "-dark" if dark else ""
    save("glass"+suffix,image)
    save("pane-mask"+suffix,mask)

image = Image.new("RGB",(256,128),"#424942")
dr = ImageDraw.Draw(image)
regions = {}
for name,y,w,h,text,bg,ink,scale in (
    ("rural",0,116,13,"MILL BRANCH SCHOOL","#596950","#f0ddaa",1),
    ("campus",15,225,24,"WESTMEADOW ELEMENTARY","#4f6a60","#efdca6",1),
    ("gym",41,114,13,"GYM AND LUNCHROOM","#62514a","#f0ddaa",1),
    ("portable_a",57,62,12,"ROOM A","#76684e","#e9d9b0",1),
    ("portable_b",72,62,12,"ROOM B","#76684e","#e9d9b0",1),
    ("office",88,63,12,"OFFICE","#6b5949","#e9d9b0",1),
):
    regions[name] = [0,y*2,w*2,h*2]
    dr.rectangle((0,y,w-1,y+h-1),fill="#343d33")
    dr.rectangle((1,y+1,w-2,y+h-2),fill=bg)
    dr.line((1,y+1,w-2,y+1),fill="#b3a880")
    columns=(0,1,1,2,3,3,4) if name=="campus" else tuple(range(5))
    gap=2 if name=="campus" else 1
    stride=len(columns)+gap
    height=2 if name=="campus" else 1
    length=len(text)*stride-gap
    left=(w-length)//2
    assert min(left,w-length-left)>=2, name
    for j,ch in enumerate(text):
        for yy,glyph in enumerate(FONT.get(ch,())):
            for xx,col in enumerate(columns):
                if glyph[col]=="1":
                    x=left+j*stride+xx
                    py=y+(h-7*height)//2+yy*height
                    dr.line((x,py,x,py+height-1),fill=ink)
save("signs",image)
(OUT/"sign-regions.json").write_text(json.dumps(regions,indent=2)+"\n")
