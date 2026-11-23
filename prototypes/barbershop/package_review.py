"""Combine front, night, rear, above, street-height and interior model views."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT=Path(__file__).resolve().parent
states=(("barber-1940","1940–1979 · Mercer Barber"),("barber-1980","1980–2009 · Mercer Hair Co."),
        ("barber-2010","2010 onward · Mercer Barber Co."),("salon-1940","1940–1979 · Lilac Beauty"),
        ("salon-1980","1980–2009 · Lilac Styling"),("salon-2010","2010 onward · Lilac Nail Room"))
views=(("day","Day"),("night","Night"),("rear","Rear"),("above","Above"),("eye","Entrance"),("interior","Interior"))
w,h=369,271
sheet=Image.new("RGB",(len(views)*w+28,len(states)*h+66),"#293b39")
draw=ImageDraw.Draw(sheet)
title=ImageFont.truetype("DejaVuSans-Bold.ttf",37)
caption=ImageFont.truetype("DejaVuSans.ttf",20)
draw.text((18,14),"Mercer and Lilac · Blender Cycles",font=title,fill="#f1e4c8")
for row,(state,name) in enumerate(states):
    y=65+h*row
    draw.text((17,y),name,font=caption,fill="#f1e4c8")
    for col,(view,label) in enumerate(views):
        x=14+w*col
        with Image.open(ROOT/"renders"/f"{state}-{view}.png") as source:
            photo=ImageOps.contain(source.convert("RGB"),(w-10,214))
        sheet.paste(photo,(x+(w-photo.width)//2,y+25))
        draw.text((x+6,y+241),label,font=caption,fill="#f1e4c8")
sheet.save(ROOT/"review.jpg",quality=94)
