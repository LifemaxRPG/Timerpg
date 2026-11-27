"""Compose actual Alder model views for deck, rig and hull review."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT=Path(__file__).resolve().parent
views=[("day","Alder under sail"),("profile","Rig and hull profile"),("stern","Aft quarter"),
       ("cockpit","Tiller and recessed cockpit"),("deck","Foredeck and cabin trunk"),
       ("keel","Rudder and ballast fin"),("above","Deck plan"),("filament","Export in desktop Filament")]
views=[(name,title) for name,title in views if (ROOT/"renders"/f"{name}.png").exists()]
assert len(views)>=7
sheet=Image.new("RGB",(1344,600),"#293939")
draw=ImageDraw.Draw(sheet)
font=ImageFont.truetype("DejaVuSans.ttf",17)
for i,(name,title) in enumerate(views):
    x,y=i%4*336+8,i//4*300+7
    with Image.open(ROOT/"renders"/f"{name}.png") as im:
        resize=ImageOps.contain(im.convert("RGB"),(320,265))
    sheet.paste(resize,(x+(320-resize.width)//2,y+(265-resize.height)//2))
    draw.text((x+4,y+268),title,font=font,fill="#ede0c7")
sheet.save(ROOT/"review.jpg",quality=94)
