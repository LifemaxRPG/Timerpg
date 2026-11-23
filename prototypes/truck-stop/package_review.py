"""Package two era rows of actual model renders on one contact sheet."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT=Path(__file__).resolve().parent
STATES=(("1960","1960–1989 · Cedar Relay"),("1990","1990 onward · Relay Travel Center"))
VIEWS=(("day","Day"),("night","Night"),("rear","Rear lot"),("above","Above"),
       ("eye","Entrance"),("diesel","Diesel lanes"),("pole","Road pylon"),("interior","Diner and booths"))
w,h=376,292
sheet=Image.new("RGB",(len(VIEWS)*w+28,len(STATES)*h+68),"#293b39")
draw=ImageDraw.Draw(sheet)
title=ImageFont.truetype("DejaVuSans-Bold.ttf",38)
caption=ImageFont.truetype("DejaVuSans.ttf",20)
draw.text((18,13),"Cedar Relay Truck Stop · Blender Cycles",font=title,fill="#f1e4c8")
for row,(year,label) in enumerate(STATES):
    y=67+row*h
    draw.text((17,y),label,font=caption,fill="#f1e4c8")
    for col,(view,name) in enumerate(VIEWS):
        x=14+col*w
        with Image.open(ROOT/"renders"/f"{year}-{view}.png") as source:
            photo=ImageOps.contain(source.convert("RGB"),(w-10,235))
        sheet.paste(photo,(x+(w-photo.width)//2,y+25))
        draw.text((x+6,y+262),name,font=caption,fill="#f1e4c8")
sheet.save(ROOT/"review.jpg",quality=94)
