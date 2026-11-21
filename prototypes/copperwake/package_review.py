"""Compose the ship's exterior, deck and room views for visual review."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

root=Path(__file__).resolve().parent
views=[
    ("day","Underway"),("night","After dark"),("stern","Stern and tiered balconies"),("above","Deck plan"),
    ("promenade","Walking the promenade"),("pool","Main pool deck"),("lounge","Inside the observation lounge"),
]
if (root/"renders"/"filament.png").exists():
    views.append(("filament","Exported GLB in desktop Filament"))
sheet=Image.new("RGB",(1456,552),"#263137")
draw=ImageDraw.Draw(sheet)
font=ImageFont.truetype("DejaVuSans.ttf",16)
for i,(name,title) in enumerate(views):
    x,y=7+i%4*364,7+i//4*276
    with Image.open(root/"renders"/f"{name}.png") as opened:
        img=ImageOps.contain(opened.convert("RGB"),(350,235))
    sheet.paste(img,(x+(350-img.width)//2,y+(235-img.height)//2))
    draw.text((x+4,y+244),title,fill="#ecdfca",font=font)
sheet.save(root/"review.jpg",quality=94)
