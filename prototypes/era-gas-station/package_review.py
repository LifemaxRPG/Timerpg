"""Assemble the seven requested Blender views for each station era."""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parent
W,H,GAP=298,209,12
ERAS=(
    ("1925","1925–44   /   Shingled cottage, two gravity pumps and a hand-painted price board"),
    ("1945","1945–74   /   Enamel station, two islands and a two-bay service shop"),
    ("1975","1975 onward   /   Six self-serve pumps, folded canopy and LED prices"),
)
VIEWS=(("day","Forecourt / day"),("night","Forecourt / night"),("back","Rear"),("above","From above"),
       ("entrance","At the forecourt entrance"),("office-door","Shop from the door"),
       ("office-counter","Behind the counter"))
head=ImageFont.truetype("DejaVuSans-Bold.ttf",28)
label=ImageFont.truetype("DejaVuSans.ttf",16)
small=ImageFont.truetype("DejaVuSans.ttf",13)
stride=(H+21)*2+45
sheet=Image.new("RGB",(4*W+5*GAP,71+len(ERAS)*stride),"#243037")
d=ImageDraw.Draw(sheet)
d.text((GAP,10),"Vale Lantern Fuel",font=head,fill="#f2dfbd")
d.text((GAP,47),"C015   /   TIMERPG   /   BLENDER DESKTOP RENDERS",font=small,fill="#b0c0ba")
for i,(era,title) in enumerate(ERAS):
    y=71+i*stride
    d.text((GAP,y),title,font=label,fill="#f2dfbd")
    for j,(key,caption) in enumerate(VIEWS):
        x=GAP+(j%4)*(W+GAP)
        yy=y+25+(j//4)*(H+21)
        with Image.open(ROOT/"renders"/f"vale-lantern-{era}-{key}.png") as im:
            sheet.paste(im.convert("RGB").resize((W,H),Image.Resampling.LANCZOS),(x,yy))
        d.text((x,yy+H+3),caption,font=small,fill="#dedacf")
sheet.save(ROOT/"review.jpg",quality=91,subsampling=0)
