"""Package two era rows of actual model renders on one contact sheet."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT=Path(__file__).resolve().parent
STATES=(("1960","1960–1989 · Cedar Relay"),("1990","1990 onward · Relay Travel Center"))
VIEWS=(("day","Day"),("night","Night"),("rear","Rear lot"),("above","Above"),
       ("eye","Entrance"),("diesel","Diesel lanes"),("pole","Road pylon"),("interior","Diner and booths"),
       ("service","Era service bay"),("yard_night","Yard at night"))
w,h=376,292
columns=5
rows_per_era=(len(VIEWS)+columns-1)//columns
era_height=34+rows_per_era*h
sheet=Image.new("RGB",(columns*w+28,len(STATES)*era_height+80),"#293b39")
draw=ImageDraw.Draw(sheet)
title=ImageFont.truetype("DejaVuSans-Bold.ttf",38)
caption=ImageFont.truetype("DejaVuSans.ttf",20)
draw.text((18,13),"Cedar Relay Truck Stop · Blender Cycles",font=title,fill="#f1e4c8")
for row,(year,label) in enumerate(STATES):
    y=67+row*era_height
    draw.text((17,y),label,font=caption,fill="#f1e4c8")
    for index,(view,name) in enumerate(VIEWS):
        x=14+(index%columns)*w
        tile_y=y+(index//columns)*h
        with Image.open(ROOT/"renders"/f"{year}-{view}.png") as source:
            photo=ImageOps.contain(source.convert("RGB"),(w-10,235))
        sheet.paste(photo,(x+(w-photo.width)//2,tile_y+25))
        draw.text((x+6,tile_y+262),name,font=caption,fill="#f1e4c8")
sheet.save(ROOT/"review.jpg",quality=94)
