"""Show the actual model from the approach, rear, rooms and truck apron."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps
root=Path(__file__).resolve().parent
views=[
    ("day","Pine Bend by day"),("night","Parking and pavilion at dusk"),("walking","Passenger approach"),
    ("rear","Picnic shelters and rear elevation"),("above","Car and truck circulation"),("vending","Covered vending"),
    ("truck","Truck pull-through bays"),("restroom","Restroom shell and fixed fixtures"),
    ("filament","Export in desktop Filament")
]
views=[(n,t) for n,t in views if (root/"renders"/f"{n}.png").exists()]
assert len(views)>=8
sheet=Image.new("RGB",(1218,984),"#273335")
draw=ImageDraw.Draw(sheet)
font=ImageFont.truetype("DejaVuSans.ttf",17)
for i,(name,title) in enumerate(views):
    x,y=9+i%3*406,8+i//3*328
    with Image.open(root/"renders"/f"{name}.png") as im:
        thumbnail=ImageOps.contain(im.convert("RGB"),(392,292))
    sheet.paste(thumbnail,(x+(392-thumbnail.width)//2,y+(292-thumbnail.height)//2))
    draw.text((x+4,y+296),title,font=font,fill="#ede2c8")
sheet.save(root/"review.jpg",quality=94)
