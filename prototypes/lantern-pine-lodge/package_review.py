"""Compose day, night and close-up previews of the actual lodge."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

root=Path(__file__).resolve().parent
views=[
    ("dusk","Lanterns in the pines"),("day","Daylight and approach"),("porch","At the foot of the porch"),
    ("rear","Rear stoop and woodshed"),("hearth","Fieldstone hearth"),("above","Clearing and roof plan"),
    ("roof-detail","Porch junction and wall flashing"),("filament","Export in desktop Filament")
]
views=[(name,label) for name,label in views if (root/"renders"/f"{name}.png").exists()]
assert len(views)>=6
sheet=Image.new("RGB",(1332,((len(views)+2)//3)*330),"#293536")
draw=ImageDraw.Draw(sheet)
font=ImageFont.truetype("DejaVuSans.ttf",17)
for i,(name,caption) in enumerate(views):
    x,y=9+i%3*444,7+i//3*330
    with Image.open(root/"renders"/f"{name}.png") as original:
        im=ImageOps.contain(original.convert("RGB"),(426,294))
    sheet.paste(im,(x+(426-im.width)//2,y+(294-im.height)//2))
    draw.text((x+5,y+298),caption,font=font,fill="#eee3cd")
sheet.save(root/"review.jpg",quality=94)
