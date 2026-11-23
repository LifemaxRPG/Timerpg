"""Put all four states on one labeled model-review sheet."""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parent
STATES = (("1935","1935–1949 · Wood park"),("1950","1950–1959 · First lights"),
          ("1960","1960–1999 · Steel bleachers"),("2000","2000 onward · Renovation"))
VIEWS = (("day","Day"),("night","Night"),("rear","Rear"),("above","Above"),
         ("eye","Gate at eye level"),("field","Backstop and grandstand"),("interior","Concessions interior"))
width,height=369,292
sheet=Image.new("RGB",(len(VIEWS)*width+28,len(STATES)*height+65),"#293b39")
draw=ImageDraw.Draw(sheet)
title=ImageFont.truetype("DejaVuSans-Bold.ttf",37)
caption=ImageFont.truetype("DejaVuSans.ttf",20)
draw.text((19,14),"Alder Bend Ballpark · Blender Cycles",font=title,fill="#f0e0bc")
for row,(year,label) in enumerate(STATES):
    y=64+height*row
    draw.text((17,y),label,font=caption,fill="#f0e0bc")
    for col,(shot,name) in enumerate(VIEWS):
        x=14+col*width
        with Image.open(ROOT/"renders"/f"{year}-{shot}.png") as preview:
            image=ImageOps.contain(preview.convert("RGB"),(width-11,233))
        sheet.paste(image,(x+(width-image.width)//2,y+28))
        draw.text((x+7,y+265),name,font=caption,fill="#f0e0bc")
sheet.save(ROOT/"review.jpg",quality=94)
