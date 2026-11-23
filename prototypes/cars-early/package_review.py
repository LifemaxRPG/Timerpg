"""Lay out a compact sheet of the actual exported vehicle renders."""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps
from designs import DESIGNS

ROOT=Path(__file__).resolve().parent
VIEWS=(("front","Front three-quarter"),("rear","Rear three-quarter"),("side","Side profile"),("top","From above"),
       ("night","Headlamps at night"),("open","Doors open"),("interior","At the driver's door"),("scale","1.75 m reference"))
W,H=382,277
cols=4
row_h=H*2+33
sheet=Image.new("RGB",(cols*W+24,len(DESIGNS)*row_h+74),"#263536")
draw=ImageDraw.Draw(sheet)
title=ImageFont.truetype("DejaVuSans-Bold.ttf",32)
label=ImageFont.truetype("DejaVuSans.ttf",20)
draw.text((14,13),"Timerpg Early Cars  ·  Blender Cycles",font=title,fill="#eee3cd")
for index,car in enumerate(DESIGNS):
    y=67+index*row_h
    draw.text((14,y),f"{car.name}  ·  {car.years[0]}–{car.years[-1]}",font=label,fill="#eee3cd")
    for v,(key,caption) in enumerate(VIEWS):
        x=12+(v%cols)*W
        row_y=y+(v//cols)*H+29
        with Image.open(ROOT/"renders"/f"{car.slug}-{key}.png") as source:
            thumb=ImageOps.contain(source.convert("RGB"),(W-10,H-33))
        sheet.paste(thumb,(x+(W-thumb.width)//2,row_y))
        draw.text((x+6,row_y+H-31),caption,font=label,fill="#eee3cd")
sheet.save(ROOT/"review.jpg",quality=94)
