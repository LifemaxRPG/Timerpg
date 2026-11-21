"""Compose a review sheet from the rendered model views."""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps

root = Path(__file__).resolve().parent
views = [
    ("day", "South square"), ("night", "Blue hour"), ("walking", "Pedestrian approach"),
    ("rear", "North elevation"), ("above", "Plaza plan"), ("passage", "Central passage")
]
sheet = Image.new("RGB", (1128, 612), "#263135")
text = ImageDraw.Draw(sheet)
font = ImageFont.truetype("DejaVuSans.ttf", 16)
for i, (name, caption) in enumerate(views):
    x, y = 8 + i%3*376, 8 + i//3*306
    with Image.open(root/"renders"/f"{name}.png") as image:
        thumbnail = ImageOps.contain(image.convert("RGB"), (360, 270))
    sheet.paste(thumbnail, (x+(360-thumbnail.width)//2, y))
    text.text((x+4,y+276), caption, font=font, fill="#eee5d5")
sheet.save(root/"review.jpg", quality=94)
