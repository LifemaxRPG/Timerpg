"""Combine all three era reviews into a single labelled image."""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
WIDTH, HEIGHT, GAP = 314, 226, 12
VIEWS = (
    ("day", "Front / day"), ("night", "Front / night"),
    ("rear", "Service yard"), ("above", "Site plan"),
    ("entrance", "Street height"), ("desk", "Sergeant's desk"),
    ("cell", "Inside a cell"), ("booking", "Booking room"),
)
ERAS = {
    "1900": "1900–1939   /   Brick station and town jail",
    "1940": "1940–1979   /   Dispatch, booking and rear squad garage",
    "1980": "1980 onward   /   Glass entry and secure transport bay",
}
heading = ImageFont.truetype("DejaVuSans-Bold.ttf", 26)
label = ImageFont.truetype("DejaVuSans.ttf", 15)
small = ImageFont.truetype("DejaVuSans.ttf", 12)
width = WIDTH * 4 + GAP * 5
stride = (HEIGHT + 23) * 2 + 45
canvas = Image.new("RGB", (width,76+len(ERAS)*stride), "#233139")
pen = ImageDraw.Draw(canvas)
pen.text((GAP,12), "Briarford Police Station & Town Jail", font=heading, fill="#f3e8ce")
pen.text((GAP,47), "TIMERPG   /   C009   /   BLENDER DESKTOP RENDERS", font=small, fill="#c3ceb9")
for i,(era,title) in enumerate(ERAS.items()):
    y = 76+i*stride
    pen.text((GAP,y+4), title, font=label, fill="#f3e8ce")
    for j,(key,caption) in enumerate(VIEWS):
        xx = GAP+(j%4)*(WIDTH+GAP)
        yy = y+29+(j//4)*(HEIGHT+23)
        path = ROOT/"renders"/f"briarford-{era}-{key}.png"
        if key=="booking" and era=="1900":
            pen.rectangle((xx,yy,xx+WIDTH,yy+HEIGHT), fill="#2d3d44")
            pen.text((xx+14,yy+32), "Booking room added in 1940",font=label,fill="#d3caaf")
        else:
            with Image.open(path) as shot:
                canvas.paste(shot.convert("RGB").resize((WIDTH,HEIGHT),Image.Resampling.LANCZOS),(xx,yy))
            pen.text((xx,yy+HEIGHT+2), caption, font=small, fill="#e2dbc7")
canvas.save(ROOT/"review.jpg",quality=90,subsampling=0)
