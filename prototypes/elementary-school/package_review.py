"""Place all campus and rural states on the same review sheet."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT=Path(__file__).resolve().parent
states=(("mill-branch-active","Mill Branch School · In use"),("mill-branch-abandoned","Mill Branch School · Abandoned"),
        ("westmeadow-1955","Westmeadow Elementary · 1955–1989"),("westmeadow-1990","Westmeadow Elementary · 1990 onward"))
views=(("day","Day"),("night","Night"),("rear","Rear"),("above","Above"),("eye","Entrance"),
       ("yard","Schoolyard"),("interior","Interior"))
w,h=360,275
sheet=Image.new("RGB",(len(views)*w+28,len(states)*h+70),"#293b39")
draw=ImageDraw.Draw(sheet)
title=ImageFont.truetype("DejaVuSans-Bold.ttf",37)
small=ImageFont.truetype("DejaVuSans.ttf",20)
draw.text((18,13),"Mill Branch and Westmeadow Schools · Blender Cycles",font=title,fill="#f1e4c8")
for row,(state,name) in enumerate(states):
    y=65+row*h
    draw.text((17,y),name,font=small,fill="#f1e4c8")
    for col,(view,caption) in enumerate(views):
        x=14+col*w
        with Image.open(ROOT/"renders"/f"{state}-{view}.png") as render:
            photo=ImageOps.contain(render.convert("RGB"),(w-10,216))
        sheet.paste(photo,(x+(w-photo.width)//2,y+25))
        draw.text((x+6,y+245),caption,font=small,fill="#f1e4c8")
sheet.save(ROOT/"review.jpg",quality=94)
