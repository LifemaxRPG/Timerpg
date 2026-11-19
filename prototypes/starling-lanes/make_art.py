"""Original pixel artwork; paint at art resolution, enlarge exactly 2x."""
from PIL import Image,ImageDraw
from pathlib import Path
import json
from pixel_font import FONT
R=Path(__file__).resolve().parent;REG={}
ink='#34384c';cream='#eee9df';teal='#46756a';red='#a14f44'
def text(d,xy,s,c):
 x,y=xy
 for ch in s:
  for yy,row in enumerate(FONT.get(ch,['00000']*7)):
   for xx,v in enumerate(row):
    if v=='1':d.point((x+xx,y+yy),fill=c)
  x+=6
def save(im,n):im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(R/'textures'/n)
for n,c,hi in [('cream',cream,'#fff6e7'),('teal',teal,'#76998a')]:
 im=Image.new('RGBA',(64,64),c);d=ImageDraw.Draw(im);d.rectangle((0,0,63,63),outline=ink);d.line((1,1,62,1),fill=hi);d.line((1,2,1,62),fill=hi);save(im,n+'.png')
im=Image.new('RGBA',(128,128),ink);d=ImageDraw.Draw(im)
for i,(name,c) in enumerate([('metal','#7d7f99'),('rubber','#34384c'),('pin_red',red),('paint',cream)]):
 x=(i%2)*64;y=(i//2)*64;d.rectangle((x,y,x+63,y+63),fill=c);d.line((x,y,x+63,y),fill=cream);REG[name]={'rect_art':[x,y,64,64]}
save(im,'utility.png')
im=Image.new('RGBA',(256,128),(0,0,0,0));d=ImageDraw.Draw(im)
def glass(key,rect,label=None):
 x,y,w,h=rect;REG[key]={'rect_art':rect};d.rectangle((x,y,x+w-1,y+h-1),fill=(83,127,139,90),outline=ink);d.line((x+1,y+1,x+w-2,y+1),fill=cream)
 for xx in range(x+20,x+w-1,20):d.line((xx,y,xx,y+h-1),fill=ink)
 if label:
  ww=len(label)*6+4;d.rectangle((x+4,y+h-14,x+4+ww,y+h-3),fill=cream);text(d,(x+7,y+h-12),label,red)
glass('front',[0,0,120,42],'BOWL');glass('entry',[122,0,32,36]);glass('side',[156,0,48,40],'8 LANES');glass('high_side',[0,80,96,12]);glass('transom',[0,44,32,12]);glass('clerestory',[0,60,192,12]);save(im,'glass.png')
im=Image.new('RGBA',(128,64),teal);d=ImageDraw.Draw(im)
REG['title']={'rect_art':[0,0,96,12]};d.rectangle((0,0,95,11),outline=ink);text(d,(7,3),'STARLING LANES',cream)
REG['lamp']={'rect_art':[0,16,16,4]};d.rectangle((0,16,15,19),fill='#ffe6ab',outline=ink)
for i in range(8):
 x=i*8;REG['lane'+str(i+1)]={'rect_art':[x,24,8,10]};d.rectangle((x,24,x+7,33),fill=red,outline=ink);text(d,(x+2,26),str(i+1),cream)
REG['bowl']={'rect_art':[0,40,48,12]};d.rectangle((0,40,47,51),outline=ink);text(d,(12,43),'BOWL',cream)
save(im,'signs.png')
im=Image.new('RGBA',(32,32),teal);d=ImageDraw.Draw(im)
for y in range(0,32,8):
 for x in range(0,32,8):d.polygon([(x+4,y),(x+7,y+4),(x+4,y+7),(x,y+4)],fill=cream if (x+y)%16 else red,outline=ink)
save(im,'mosaic.png')
# Fixed flooring: preserve the licensed wood art and its scale.
src=Image.open(R/'textures/plank_vertical.png').resize((32,32),Image.Resampling.NEAREST)
im=Image.new('RGBA',(128,128),ink)
for x in range(0,64,32):
 for y in range(0,64,32):im.paste(src,(x,y))
REG['wood']={'rect_art':[0,0,64,64]}
for y in range(32):
 for x in range(14):im.putpixel((67+x,y),src.getpixel((x,y)))
REG['lane']={'rect_art':[64,0,20,32]};save(im,'lane-wood.png')
(R/'art-regions.json').write_text(json.dumps(REG,indent=2)+'\n')
