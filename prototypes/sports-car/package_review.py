"""Label the eleven exported views of each Torrel generation."""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parent
W,H,GAP=297,208,12
ROWS=(
    ("1968","1968–1976   /   Chrome bumpers, narrow tyres and a wood-rim cockpit"),
    ("1988","1988–1996   /   Wider arches, staggered tyres and a cutoff wing"),
)
VIEWS=(
    ("hero","Low front three-quarter"),("road","Twisty road"),("front","Front three-quarter"),
    ("rear","Rear three-quarter"),("side","Side profile"),("top","Overhead"),
    ("night","Headlamps at night"),("scale","1.75 m scale figure"),
    ("open","Doors open and targa removed"),("interior","From the driver's door"),
    ("cargo","Engine and front luggage compartment"),
)
title=ImageFont.truetype("DejaVuSans-Bold.ttf",28)
header=ImageFont.truetype("DejaVuSans.ttf",17)
label=ImageFont.truetype("DejaVuSans.ttf",13)
size=(4*W+5*GAP,79+2*(44+3*(H+23)))
sheet=Image.new("RGB",size,"#263037")
d=ImageDraw.Draw(sheet)
d.text((GAP,10),"Rillhaven Torrel / Torrel RS",font=title,fill="#f1deb8")
d.text((GAP,48),"C017   /   TIMERPG   /   BLENDER DESKTOP RENDERS",font=label,fill="#aebcb6")
for i,(era,caption) in enumerate(ROWS):
    start=79+i*(44+3*(H+23))
    d.text((GAP,start),caption,font=header,fill="#f1deb8")
    for j,(slug,view) in enumerate(VIEWS):
        x=GAP+(j%4)*(W+GAP)
        y=start+27+(j//4)*(H+23)
        with Image.open(ROOT/"renders"/f"torrel-{era}-{slug}.png") as im:
            sheet.paste(im.convert("RGB").resize((W,H),Image.Resampling.LANCZOS),(x,y))
        d.text((x,y+H+3),view,font=label,fill="#dedacc")
sheet.save(ROOT/"review.jpg",quality=90,subsampling=0)
