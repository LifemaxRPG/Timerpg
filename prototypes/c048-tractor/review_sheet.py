"""Assemble the Blender renders into a labeled two-column review sheet."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parent
vehicles = [('wren-rowcrop-1952', 'Wren Row-Crop / 1952'), ('wren-utility-1978', 'Wren Utility / 1978')]
views = [('front', 'Front three-quarter'), ('rear', 'Rear three-quarter'),
         ('side', 'Side'), ('top', 'Top'), ('night', 'Night'),
         ('access', 'Driver access'), ('scale', 'Scale')]
width, height = 420, 300
sheet = Image.new('RGB', (width * 2 + 30, 45 + (height + 28) * len(views)), '#171b1e')
draw = ImageDraw.Draw(sheet)
font = ImageFont.truetype('DejaVuSans.ttf', 16)
for column, (vehicle, title) in enumerate(vehicles):
    x = 10 + column * (width + 10)
    draw.text((x, 12), title, font=font, fill='#eee4d4')
    for index, (view, label) in enumerate(views):
        y = 45 + index * (height + 28)
        with Image.open(root / f'{vehicle}-{view}.png') as render:
            sheet.paste(render.convert('RGB'), (x, y))
        draw.text((x, y + height + 4), label, font=font, fill='#eee4d4')
sheet.save(root / 'review-sheet.jpg', quality=90)
