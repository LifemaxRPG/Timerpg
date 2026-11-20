"""Make a contact sheet from the actual renders, preserving each camera frame."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

root = Path(__file__).resolve().parent
views = [
    ("day", "South plaza"), ("night", "Dusk"), ("walking", "Pedestrian approach"),
    ("rear", "Back of the work coat"), ("above", "Plan of the square"),
    ("lantern", "Lantern entrance"), ("square", "In the streetscape"),
    ("scale-study", "Early study beside the Gazette"), ("filament", "Exported GLB in Filament")
]
sheet = Image.new("RGB", (1104, 951), "#242f31")
draw = ImageDraw.Draw(sheet)
font = ImageFont.truetype("DejaVuSans.ttf", 16)
for i, (name, label) in enumerate(views):
    x = 9 + (i % 3) * 366
    y = 9 + (i // 3) * 315
    with Image.open(root / "renders" / (name + ".png")) as src:
        image = ImageOps.contain(src.convert("RGB"), (354, 280), Image.Resampling.LANCZOS)
    sheet.paste(image, (x + (354 - image.width) // 2, y + (280 - image.height) // 2))
    draw.text((x + 4, y + 285), label, fill="#e9dfc6", font=font)
sheet.save(root / "review.jpg", quality=92)
