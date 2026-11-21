"""Review Vesper's hull, boat decks and salon from rendered model views."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont,ImageOps

ROOT=Path(__file__).resolve().parent
views=[("day","Vesper underway"),("stern","Aft terraces and stair routes"),("dusk","At dusk"),
       ("beach","Swim platform at eye level"),("flybridge","Under the hardtop"),("salon","Main salon"),
       ("above","Deck layout"),("underbody","Twin shafts and rudders"),("filament","Export in desktop Filament")]
views=[(name,title) for name,title in views if (ROOT/"renders"/f"{name}.png").exists()]
assert len(views)>=8
sheet=Image.new("RGB",(1320,960),"#293638")
draw=ImageDraw.Draw(sheet)
font=ImageFont.truetype("DejaVuSans.ttf",18)
for i,(name,label) in enumerate(views):
    x,y=i%3*440+8,i//3*320+7
    with Image.open(ROOT/"renders"/f"{name}.png") as im:
        photo=ImageOps.contain(im.convert("RGB"),(424,278))
    sheet.paste(photo,(x+(424-photo.width)//2,y+(278-photo.height)//2))
    draw.text((x+5,y+286),label,font=font,fill="#eee1c7")
sheet.save(ROOT/"review.jpg",quality=94)
