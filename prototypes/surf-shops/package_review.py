"""Lay out paired views from the exported storefront geometry."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT=Path(__file__).resolve().parent
titles=[("driftline","Driftline Surf & Repair"),("tideglass","Tideglass Board Co.")]
body=ImageFont.truetype("DejaVuSans.ttf",26)
bold=ImageFont.truetype("DejaVuSans-Bold.ttf",43)
for view,output,heading in (("day","review.jpg","Surf shops"),
                            ("street","review-windows.jpg","At the storefront"),
                            ("night","review-night.jpg","After dusk")):
    canvas=Image.new("RGB",(1600,716),"#273a42")
    draw=ImageDraw.Draw(canvas)
    draw.text((26,11),heading,font=bold,fill="#efe6cf")
    for i,(shop,name) in enumerate(titles):
        left=i*800
        with Image.open(ROOT/"renders"/f"{shop}-{view}.png") as im:
            thumb=ImageOps.contain(im.convert("RGB"),(784,582))
        canvas.paste(thumb,(left+(800-thumb.width)//2,62))
        draw.text((left+24,666),name,font=body,fill="#e9dfc8")
    canvas.save(ROOT/output,quality=94)
