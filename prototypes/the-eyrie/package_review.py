"""Compile the final review sheet from the model's rendered views."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

root = Path(__file__).resolve().parent
views = [
    ("day", "South elevation"), ("night", "Night lighting"),
    ("walking", "From the promenade"), ("rear", "North elevation"),
    ("above", "Granite terraces"), ("court", "At the dedication stone"),
    ("city", "With Timerpg buildings at native scale"), ("filament", "GLB in desktop Filament")
]
views = [(name, title) for name, title in views if (root/"renders"/f"{name}.png").exists()]
assert len(views)>=6
sheet = Image.new("RGB", (1376,568), "#263237")
draw = ImageDraw.Draw(sheet)
font = ImageFont.truetype("DejaVuSans.ttf", 15)
for i, (name, title) in enumerate(views):
    x, y = 6+i%4*344, 6+i//4*284
    with Image.open(root/"renders"/f"{name}.png") as opened:
        image = ImageOps.contain(opened.convert("RGB"), (332,249))
    sheet.paste(image, (x+(332-image.width)//2,y+(249-image.height)//2))
    draw.text((x+4,y+253),title,fill="#eee5d5",font=font)
sheet.save(root/"review.jpg",quality=93)
