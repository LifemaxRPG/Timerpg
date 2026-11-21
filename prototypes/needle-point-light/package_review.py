"""Assemble lighting comparisons from the exported-model render views."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

root=Path(__file__).resolve().parent
body=ImageFont.truetype("DejaVuSans.ttf",26)
bold=ImageFont.truetype("DejaVuSans-Bold.ttf",41)
states=[("historic","Historic · 1892–2007"),("modern","Modern · 2008 onward")]
for view,output,subtitle in (("day","review.jpg","Needle Point Light"),
                             ("night","review-night.jpg","Needle Point after dusk")):
    sheet=Image.new("RGB",(1600,768),"#293a3e")
    draw=ImageDraw.Draw(sheet)
    draw.text((26,15),subtitle,font=bold,fill="#efe3c7")
    for i,(state,label) in enumerate(states):
        x=i*800
        with Image.open(root/"renders"/f"{state}-{view}.png") as image:
            photo=ImageOps.contain(image.convert("RGB"),(784,640))
        sheet.paste(photo,(x+(800-photo.width)//2,64))
        draw.text((x+18,718),label,font=body,fill="#e8ddc6")
    sheet.save(root/output,quality=94)
