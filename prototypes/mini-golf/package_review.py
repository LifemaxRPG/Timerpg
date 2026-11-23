"""Combine all course states in a single review sheet."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT=Path(__file__).resolve().parent
STATES=(("1962","1962–1989 · Open"),("1990","1990–2005 · Faded"),("2006","2006 onward · Closed and overgrown"))
VIEWS=(("day","Day"),("night","Night"),("rear","Rear"),("above","Above"),
       ("eye","Entrance and statue pad"),("course","Among the holes"),("interior","Ticket hut"))
w,h=374,290
sheet=Image.new("RGB",(len(VIEWS)*w+28,len(STATES)*h+68),"#293b39")
draw=ImageDraw.Draw(sheet)
title=ImageFont.truetype("DejaVuSans-Bold.ttf",38)
caption=ImageFont.truetype("DejaVuSans.ttf",20)
draw.text((18,13),"Thimble Trails Mini Golf · Blender Cycles",font=title,fill="#f1e4c8")
for row,(year,label) in enumerate(STATES):
    y=67+row*h
    draw.text((17,y),label,font=caption,fill="#f1e4c8")
    for col,(view,name) in enumerate(VIEWS):
        x=14+col*w
        with Image.open(ROOT/"renders"/f"{year}-{view}.png") as source:
            photo=ImageOps.contain(source.convert("RGB"),(w-10,233))
        sheet.paste(photo,(x+(w-photo.width)//2,y+25))
        draw.text((x+6,y+260),name,font=caption,fill="#f1e4c8")
sheet.save(ROOT/"review.jpg",quality=94)
