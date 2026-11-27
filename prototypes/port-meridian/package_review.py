"""Compose the final contact sheet from views of the exported port."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

root=Path(__file__).resolve().parent
views=[
    ("day","Port Meridian and MV Copperwake"),("dusk","Quay and terminal after dusk"),
    ("above","Berth and service layout"),("front","Arrival and departure frontages"),
    ("street","Departures at walking height"),("hall","Upstairs departure hall"),
    ("boarding","Passenger bridge from the quay"),("transfer","Boarding handoff to Copperwake"),
    ("service","Baggage yard and service shed"),("filament","Port export in desktop Filament"),
]
sheet=Image.new("RGB",(1536,1700),"#26373a")
draw=ImageDraw.Draw(sheet)
font=ImageFont.truetype("DejaVuSans.ttf",21)
for i,(view,label) in enumerate(views):
    x,y=i%2*768,i//2*340
    with Image.open(root/"renders"/f"{view}.png") as image:
        image=ImageOps.contain(image.convert("RGB"),(736,298))
    sheet.paste(image,(x+(768-image.width)//2,y+8+(298-image.height)//2))
    draw.text((x+24,y+309),label,font=font,fill="#e9dfc5")
sheet.save(root/"review.jpg",quality=93)
