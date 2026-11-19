"""Isolated name and vacancy texture writers; no occupancy or business facts invented."""
from pathlib import Path
from PIL import Image,ImageDraw
from pixel_font import FONT
INK='#3a3a50';TEAL='#235d68';NEON='#ffe2a1'
def text(d,xy,s,color,scale=1):
 x,y=xy
 for ch in s:
  if ch==' ':x+=3*scale;continue
  if ch not in FONT:raise ValueError('Unsupported sign character: '+ch)
  for j,row in enumerate(FONT[ch]):
   for i,b in enumerate(row):
    if b=='1':d.rectangle((x+i*scale,y+j*scale,x+(i+1)*scale-1,y+(j+1)*scale-1),fill=color)
  x+=6*scale
def width(s,scale=1):return sum((3 if c==' ' else 6)*scale for c in s)-scale

def write_name(path,name='SUNDOWN'):
 name=name.upper().strip()
 if name.endswith(' MOTEL'):name=name[:-6].rstrip()
 if not name or width(name)>76:raise ValueError('Use a shorter, explicit sign name; do not stretch or truncate it.')
 im=Image.new('RGBA',(128,32),TEAL);d=ImageDraw.Draw(im);d.rectangle((0,0,79,27),outline=INK);d.rectangle((1,1,78,26),outline='#ff8b91');d.line((3,3,76,3),fill='#46756a')
 text(d,((80-width(name))//2,4),name,'#ffe2a1');text(d,((80-width('MOTEL',2))//2,12),'MOTEL','#80e0da',2)
 im.resize((256,64),Image.Resampling.NEAREST).save(path)

def write_vacancy(path,occupied=False):
 im=Image.new('RGBA',(64,16),TEAL);d=ImageDraw.Draw(im);d.rectangle((0,0,63,11),outline=INK);d.line((1,1,62,1),fill='#46756a');text(d,(3,3),'NO','#ff8b91' if occupied else '#58424b');text(d,(20,3),'VACANCY',NEON)
 im.resize((128,32),Image.Resampling.NEAREST).save(path)
if __name__=='__main__':
 import argparse
 p=argparse.ArgumentParser();p.add_argument('--name',default='SUNDOWN');p.add_argument('--occupied',action='store_true');p.add_argument('--output',type=Path,default=Path(__file__).parent/'textures');a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True);write_name(a.output/'sign-name.png',a.name);write_vacancy(a.output/('no-vacancy.png' if a.occupied else 'vacancy.png'),a.occupied)
