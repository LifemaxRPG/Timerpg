"""Small stepped colour clusters and a four-panel, original night-town mosaic."""
from pathlib import Path
from PIL import Image,ImageDraw
from pixel_font import FONT
import random,json
R=Path(__file__).resolve().parent
atlas_dir=R.parents[1]/'kotlin-street/tools/swatches/atlas'
atlas=Image.open(atlas_dir/'atlas.png').convert('RGB')
lib=json.loads((atlas_dir/'atlas.json').read_text())
def save(im,name):
    im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(R/'textures'/(name+'.png'))
for key in ['pavement','brick_red','plank_horizontal']:
    r=lib[key];x,y,w,h=[r[k] for k in ['x','y','w','h']]
    save(atlas.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST),key)
palettes={
    'clay':['#a35e48','#ad6950','#814b3e','#c18360'],
    'indigo':['#46586a','#506272','#394958','#708087'],
    'iron':['#353f41','#40494a','#2b3436','#586361'],
    'ochre':['#ba934d','#c49d58','#a58147','#d3af6f'],
    'sandstone':['#d0b48d','#d8bf96','#bca384','#e4cba5'],
    'moss':['#607456','#718260','#53694e','#83916b']
}
for idx,(name,pal) in enumerate(palettes.items()):
    rng=random.Random(1918+idx);im=Image.new('RGB',(64,64),pal[0]);d=ImageDraw.Draw(im)
    for j in range(68):
        x,y=rng.randrange(60),rng.randrange(62);w=rng.randint(3,9)
        d.line((x,y,x+w,y),fill=pal[1 if j%3 else 2])
        if j%3==0:d.line((x+1,y+1,x+w-1,y+1),fill=pal[1])
    for x,y in [(8,19),(37,48),(51,5)]:
        d.line((x,y,x+4,y),fill=pal[2]);d.point((x+4,y-1),fill=pal[3])
    save(im,name)
# Each panel has a different silhouette. One art pixel spans 7.76 cm on the built glass.
im=Image.new('RGB',(256,128),'#374548');d=ImageDraw.Draw(im)
for k in range(4):
    x=k*60;w,h=56,66
    for yy,c in [(0,'#b99766'),(20,'#cda866'),(36,'#dfbb78'),(52,'#e5c990')]:
        d.rectangle((x,yy,x+w-1,h-1),fill=c)
    cx=x+[18,33,26,35][k];cy=13+k%2*3
    d.polygon([(cx,cy-6),(cx+2,cy-2),(cx+6,cy),(cx+2,cy+2),(cx,cy+6),(cx-2,cy+2),(cx-6,cy),(cx-2,cy-2)],fill='#fae2ab')
    for xx,yy in [(5,8),(42,12),(13,24),(32,7),(47,26)]:
        d.point((x+xx,yy),fill='#fff0c6')
    for b in range(4):
        xx=x+1+b*15;top=42-((b*5+k*8)%14);col=['#836951','#8c7457','#9c7956'][b%3]
        d.rectangle((xx,top,xx+13,65),fill=col)
        d.polygon([(xx-1,top),(xx+6,top-6),(xx+14,top)],fill='#695e50')
        d.rectangle((xx+3,top+6,xx+5,top+10),fill='#f3d697')
    d.rectangle((x,0,x+w-1,h-1),outline='#4b5047',width=2)
    d.line((x+2,2,x+w-3,2),fill='#f0d89f')
    for y in (24,49):d.line((x+1,y,x+w-2,y),fill='#776e56')
    for xx in (14,28,42):d.line((x+xx,2,x+xx,h-3),fill='#827b60')
save(im,'night-glass')
im=Image.new('RGB',(128,16),'#303e41');d=ImageDraw.Draw(im);d.rectangle((0,0,127,15),outline='#ae8b59')
msg='THE NIGHT KEEPER';x=(128-len(msg)*6)//2
for ch in msg:
    for y,row in enumerate(FONT.get(ch,[])):
        for i,b in enumerate(row):
            if b=='1':d.point((x+i,y+4),fill='#e5cfa6')
    x+=6
save(im,'name')
