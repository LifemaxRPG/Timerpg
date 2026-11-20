"""Produce one contact sheet of the eight real camera views."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
r=Path(__file__).resolve().parent
labels=[('day','South promenade'),('night','After dark'),('rear','North bank'),('above','Foundry Court'),('person','At walking height'),('portrait','The bronze head'),('shore','West approach'),('underneath','Under the hull')]
sheet=Image.new('RGB',(1440,582),'#142a2d')
draw=ImageDraw.Draw(sheet)
for i,(view,label) in enumerate(labels):
    x=i%4*360;y=i//4*291
    image=Image.open(r/'renders'/(view+'.png')).convert('RGB')
    image.thumbnail((352,247),Image.Resampling.LANCZOS)
    sheet.paste(image,(x+4,y+4))
    draw.text((x+10,y+258),label,fill='#eadcb9',font=ImageFont.load_default(size=17))
sheet.save(r/'review.jpg',quality=94)
