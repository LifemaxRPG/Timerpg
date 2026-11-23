"""Package eight actual render views per motel era."""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parent
W,H,GAP=298,209,12
ERAS=(
    ("1935","1935–54   /   Timber motor court, drive-in carports and enamel sign"),
    ("1955","1955–84   /   Neon arrow, butterfly-roof office and fenced kidney pool"),
    ("1985","1985 onward   /   Backlit sign, outside stair and upper corridor"),
)
VIEWS=(
    ("day","Front / day"),("night","Front / night"),("back","Rear"),("above","From above"),
    ("road","Road approach at dusk"),("entrance","At the office entrance"),
    ("office-door","Office from the door"),("office-counter","Behind the counter"),
)
head=ImageFont.truetype("DejaVuSans-Bold.ttf",27)
label=ImageFont.truetype("DejaVuSans.ttf",16)
small=ImageFont.truetype("DejaVuSans.ttf",13)
stride=(H+21)*2+45
sheet=Image.new("RGB",(4*W+5*GAP,71+len(ERAS)*stride),"#233038")
d=ImageDraw.Draw(sheet)
d.text((GAP,10),"Birchbend Motor Lodge",font=head,fill="#f2dfbd")
d.text((GAP,47),"C014   /   TIMERPG   /   BLENDER DESKTOP RENDERS",font=small,fill="#b0c0ba")
for i,(era,title) in enumerate(ERAS):
    y=71+i*stride
    d.text((GAP,y),title,font=label,fill="#f2dfbd")
    for j,(key,caption) in enumerate(VIEWS):
        x=GAP+(j%4)*(W+GAP)
        yy=y+25+(j//4)*(H+21)
        with Image.open(ROOT/"renders"/f"birchbend-{era}-{key}.png") as im:
            sheet.paste(im.convert("RGB").resize((W,H),Image.Resampling.LANCZOS),(x,yy))
        d.text((x,yy+H+3),caption,font=small,fill="#dedacf")
sheet.save(ROOT/"review.jpg",quality=91,subsampling=0)
