"""Lay out the four era states and six required views on one sheet."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT=Path(__file__).resolve().parent
states=[
    ("1925","1925–1969 · Marlow Dime"),
    ("1970","1970–1999 · Marlow Variety"),
    ("2000","2000 onward · Dollar Cove"),
    ("closed","Vacant · For Lease"),
]
views=[("day","Day"),("night","Night"),("rear","Back"),("above","Above"),("eye","Entrance"),("room","Interior")]
sw,sh=386,285
sheet=Image.new("RGB",(6*sw+32,1270),"#283942")
draw=ImageDraw.Draw(sheet)
headline=ImageFont.truetype("DejaVuSans-Bold.ttf",40)
cap=ImageFont.truetype("DejaVuSans-Bold.ttf",20)
small=ImageFont.truetype("DejaVuSans.ttf",18)
draw.text((20,13),"Marlow Five and Dime",font=headline,fill="#efe2c4")
for i,(state,label) in enumerate(states):
    y=69+i*293
    draw.text((19,y),label,font=cap,fill="#efe2c4")
    for j,(view,title) in enumerate(views):
        x=16+j*sw
        with Image.open(ROOT/"renders"/f"{state}-{view}.png") as image:
            photo=ImageOps.contain(image.convert("RGB"),(sw-10,233))
        sheet.paste(photo,(x+(sw-photo.width)//2,y+28))
        draw.text((x+7,y+265),title,font=small,fill="#eadfc7")
sheet.save(ROOT/"review.jpg",quality=94)
