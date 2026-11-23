"""Build one review sheet with seven views per era."""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parent
W,H,GAP=303,218,12
VIEWS=(("day","Day"),("night","Night"),("rear","Rear alley"),("above","Site plan"),
       ("street","Street height"),("from-door","From the entrance"),
       ("behind-counter","Behind the clerk's counter"))
ERAS=(
    ("1920","1920–59   /   Brass pawnbroker balls and valve radios"),
    ("1960","1960–99   /   Neon signs and tagged stock"),
    ("2000","2000 onward   /   LED lettering, security glass and game consoles"),
)
head=ImageFont.truetype("DejaVuSans-Bold.ttf",26)
label=ImageFont.truetype("DejaVuSans.ttf",15)
small=ImageFont.truetype("DejaVuSans.ttf",13)
stride=(H+22)*2+49
canvas=Image.new("RGB",(4*W+5*GAP,72+len(ERAS)*stride),"#232832")
pen=ImageDraw.Draw(canvas)
pen.text((GAP,10),"Latch & Lyre Pawn Shop",font=head,fill="#f7e5c5")
pen.text((GAP,46),"C010  /  TIMERPG  /  BLENDER DESKTOP RENDERS",font=small,fill="#bfc8bf")
for i,(era,title) in enumerate(ERAS):
    y=72+i*stride
    pen.text((GAP,y),title,font=label,fill="#f7e5c5")
    for j,(view,caption) in enumerate(VIEWS):
        x=GAP+(j%4)*(W+GAP)
        yy=y+24+(j//4)*(H+22)
        with Image.open(ROOT/"renders"/f"pawn-{era}-{view}.png") as shot:
            canvas.paste(shot.convert("RGB").resize((W,H),Image.Resampling.LANCZOS),(x,yy))
        pen.text((x,yy+H+2),caption,font=small,fill="#dedacf")
canvas.save(ROOT/"review.jpg",quality=91,subsampling=0)
