"""Compose the published contact sheet from the actual exported model renders."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

root=Path(__file__).resolve().parent
views=[
    ("day","The harbor basin"), ("dusk","Quay lights at dusk"), ("above","Berths and approach"),
    ("warehouse","Dry-stack portal"), ("rack-layout","Three-tier rack cutaway"), ("dockside","Floating slips at eye level"),
    ("quay","Vesper at the visitor quay"), ("rear","Service side and trailer lot"), ("filament","Export in desktop Filament"),
]
sheet=Image.new("RGB",(1440,1110),"#26373a")
draw=ImageDraw.Draw(sheet)
font=ImageFont.truetype("DejaVuSans.ttf",19)
for index,(view,label) in enumerate(views):
    x,y=(index%3)*480,(index//3)*370
    with Image.open(root/"renders"/f"{view}.png") as image:
        image=ImageOps.contain(image.convert("RGB"),(460,332))
    sheet.paste(image,(x+(480-image.width)//2,y+9+(332-image.height)//2))
    draw.text((x+16,y+344),label,font=font,fill="#e9dfc5")
sheet.save(root/"review.jpg",quality=93)
