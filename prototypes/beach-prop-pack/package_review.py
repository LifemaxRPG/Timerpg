"""Make a labeled catalogue from the camera render of the exported props."""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parent
data=json.loads((ROOT/"model-info.json").read_text())["props"]
props=Image.open(ROOT/"renders"/"catalog.png").convert("RGBA")
board=Image.new("RGBA",(1680,1100),"#e6dfce")
d=ImageDraw.Draw(board)
font="DejaVuSans.ttf"
bold="DejaVuSans-Bold.ttf"
stamp=ImageFont.truetype(bold,47)
label_fonts=[ImageFont.truetype(bold,size) for size in (18,17,16,15,14)]
body=ImageFont.truetype(font,15)
d.rectangle((0,0,1680,112),fill="#365b61")
d.text((33,20),"Beach Day",font=stamp,fill="#f0e4cc")
d.text((360,51),"24 individually placeable props",font=body,fill="#f0e4cc")
offset=112
for i,entry in enumerate(data):
    x,y=i%6*280,offset+i//6*241
    if (i%6+i//6)%2==0:
        d.rectangle((x,y,x+279,y+240),fill="#eae4d5")
    d.line((x+269,y+19,x+269,y+222),fill="#cdc5b1")
board.alpha_composite(props,(0,offset))
for i,entry in enumerate(data):
    x,y=i%6*280,offset+i//6*241
    title=next((font for font in label_fonts if d.textlength(entry["name"],font=font)<250),label_fonts[-1])
    d.text((x+15,y+194),entry["name"],font=title,fill="#304d52")
    d.text((x+15,y+219),f'{entry["category"]}  ·  {entry["triangles"]} tris',font=body,fill="#526565")
d.text((35,1078),"Models framed independently. See the beach scene for relative scale.",font=body,fill="#526565")
board.convert("RGB").save(ROOT/"review.jpg",quality=93)
