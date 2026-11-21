"""Label the 14 palm portraits and four near/distance comparisons."""
from pathlib import Path
import json

from PIL import Image,ImageDraw,ImageFont,ImageOps

ROOT=Path(__file__).resolve().parent
info=json.loads((ROOT/"model-info.json").read_text())
catalog={r["id"]:r for r in info["types"]}
font=lambda s:ImageFont.truetype("DejaVuSans.ttf",s)


def grid(file,title,cards,columns):
    width,height=324,342
    rows=(len(cards)+columns-1)//columns
    out=Image.new("RGB",(columns*width+24,74+rows*height+12),"#293939")
    draw=ImageDraw.Draw(out)
    draw.text((20,19),title,font=font(29),fill="#eadec5")
    for i,(image,note,detail) in enumerate(cards):
        x=12+i%columns*width
        y=72+i//columns*height
        with Image.open(ROOT/"renders"/f"{image}.png") as im:
            photo=ImageOps.contain(im.convert("RGB"),(313,286))
        out.paste(photo,(x+(313-photo.width)//2,y))
        draw.text((x+8,y+289),note,font=font(17),fill="#f3e8cb")
        draw.text((x+8,y+313),detail,font=font(14),fill="#c4cbb9")
    out.save(ROOT/file,quality=94)


order=["mexican-fan","california-fan","royal","coconut","true-date","canary-date","queen","foxtail",
       "sabal","windmill","areca","pygmy-date","mediterranean-fan","saw-palmetto"]
cards=[]
for key in order:
    if key=="coconut":
        if (ROOT/"renders"/"coconut.png").exists():
            cards.append((key,"Coconut palm","9.9 m · Existing Coastal Palm"))
        continue
    record=catalog[key]
    cards.append((key,record["name"],f'{record["near"]["max_gltf_m"][1]:.1f} m at modeled scale'))
grid("review.jpg","Timerpg · Landscape palms",cards,4)

cards=[]
for key in ("royal","california-fan","mediterranean-fan","areca"):
    record=catalog[key]
    for level,suffix in (("near",""),("mid","-mid")):
        cards.append((key+suffix,record["name"],f'{level.title()} · {record[level]["triangles"]:,} triangles'))
grid("distance-review.jpg","Four near and distance comparisons",cards,2)
filament=[]
for key in ("royal","sabal","areca"):
    if (ROOT/"renders"/f"{key}-filament.png").exists():
        filament.append((key+"-filament",catalog[key]["name"],"Exported GLB in desktop Filament"))
if filament:
    grid("filament-review.jpg","Desktop Filament checks",filament,3)
