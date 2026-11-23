"""Arrange all era states on one review sheet."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

root = Path(__file__).resolve().parent
states = (("1925","1925–1949 · Timber elevator"),("1950","1950–1989 · Concrete expansion"),("1990","1990 onward · Lark Bend Co-op"))
views = (("day","Day"),("night","Night"),("rear","Rail side"),("above","Above"),("eye","At the scale office"),("interior","Office interior"),("paint","Painted name"))
w,h = 378,305
sheet = Image.new("RGB", (len(views)*w+24,len(states)*h+72), "#26353a")
draw = ImageDraw.Draw(sheet)
title = ImageFont.truetype("DejaVuSans-Bold.ttf",36)
caption = ImageFont.truetype("DejaVuSans.ttf",19)
draw.text((16,14),"Lark Bend Grain Elevator · Blender Cycles",font=title,fill="#ebe2c9")
for i,(state,row) in enumerate(states):
    y = 66+i*h
    draw.text((16,y),row,font=caption,fill="#ebe2c9")
    for j,(view,label) in enumerate(views):
        x = 12+j*w
        with Image.open(root/"renders"/f"{state}-{view}.png") as image:
            photo = ImageOps.contain(image.convert("RGB"),(w-10,244))
        sheet.paste(photo,(x+(w-photo.width)//2,y+26))
        draw.text((x+6,y+273),label,font=caption,fill="#ebe2c9")
sheet.save(root/"review.jpg",quality=94)
