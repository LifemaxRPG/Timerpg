"""Review silhouette, leaf structure and distance simplification together."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT=Path(__file__).resolve().parent
views=[("day","Coastal Palm"),("street","At walking height"),("crown","Folded leaflets and new growth"),
       ("rear","Trunk and rear crown"),("above","Crown from above"),("mid","168-triangle distance mesh"),
       ("filament","Export in desktop Filament")]
views=[(n,t) for n,t in views if (ROOT/"renders"/f"{n}.png").exists()]
assert len(views)>=6
sheet=Image.new("RGB",(1248,((len(views)+2)//3)*356),"#263836")
draw=ImageDraw.Draw(sheet)
font=ImageFont.truetype("DejaVuSans.ttf",17)
for i,(name,label) in enumerate(views):
    x,y=8+i%3*416,8+i//3*356
    with Image.open(ROOT/"renders"/f"{name}.png") as f:
        img=ImageOps.contain(f.convert("RGB"),(398,316))
    sheet.paste(img,(x+(398-img.width)//2,y+(316-img.height)//2))
    draw.text((x+5,y+321),label,font=font,fill="#e9dec7")
sheet.save(ROOT/"review.jpg",quality=94)
