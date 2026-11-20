"""Original library pixel artwork; shared LimeZu crops retained for Timerpg-only use."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageColor
from pixel_font import FONT
import json
R=Path(__file__).resolve().parent;REPO=R.parents[1];REG={};INK='#414d4c';STONE='#d8d2bd'
def save(im,n):im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(R/'textures'/n)
def text(d,x,y,s,c=INK):
 for ch in s:
  if ch==' ':x+=3;continue
  for j,row in enumerate(FONT[ch]):
   for i,b in enumerate(row):
    if b=='1':d.point((x+i,y+j),fill=c)
  x+=6
j=json.loads((REPO/'kotlin-street/tools/swatches/atlas/atlas.json').read_text());atlas=Image.open(REPO/'kotlin-street/tools/swatches/atlas/atlas.png').convert('RGBA');sources=[]
for key,name in [('brick_red','brick'),('wall_stucco_red','interior'),('roof_slate','slate'),('roof_membrane_grey','membrane'),('pavement','paving')]:
 e=j[key];im=atlas.crop((e['x'],e['y'],e['x']+e['w'],e['y']+e['h']));im.save(R/'source-materials'/(key+'.png'));im=im.resize((im.width//2,im.height//2),Image.Resampling.NEAREST)
 if name=='interior':
  vals=sorted(set(im.getdata()),key=lambda c:(sum(c[:3]),c));pal=['#ccc5b0','#d5cdb8','#ddd5bf','#e6ddc7','#eee5ce'];lut={v:ImageColor.getcolor(pal[round(i*4/max(1,len(vals)-1))],'RGBA') for i,v in enumerate(vals)};o=Image.new('RGBA',im.size);o.putdata([lut[v] for v in im.getdata()]);im=o
 save(im,name+'.png');sources.append({'id':key,'atlas_rect':[e['x'],e['y'],e['w'],e['h']],'source':e['entry'],'tint':'warm cream' if name=='interior' else None})
(R/'source-materials/provenance.json').write_text(json.dumps({'licence':'Shared LimeZu swatches are Timerpg-only; retain original credit. Supplemental art and geometry original.','swatches':sources},indent=2)+'\n')
for name,cols in [('stone',['#a9ab9c',STONE,'#f0e7d1','#c4c2ad']),('wood',['#554f44','#8c765b','#ab9370','#74654f']),('steel',['#2d4143','#586e65','#8b9a87','#465d58'])]:
 im=Image.new('RGBA',(64,64),cols[1]);d=ImageDraw.Draw(im)
 for y in range(64):
  for x in range(64):
   if name=='wood' and y%6==0:d.point((x,y),fill=cols[0])
   elif (x*17+y*23)%173==0:d.point((x,y),fill=cols[3])
   elif (x*13+y*19)%239==0:d.point((x,y),fill=cols[2])
 save(im,name+'.png')
im=Image.new('RGBA',(256,128),(105,147,140,35));em=Image.new('RGBA',im.size,(0,0,0,255));d=ImageDraw.Draw(im);md=ImageDraw.Draw(em)
for key,rect in [('arch',[0,0,28,40]),('basement',[32,0,20,12]),('modern',[56,0,28,24]),('ribbon',[88,0,48,24]),('link',[140,0,16,24])]:
 x,y,w,h=rect;REG[key]={'rect_art':rect};d.rectangle((x,y,x+w-1,y+h-1),outline=INK);d.line((x+1,y+1,x+w-2,y+1),fill=STONE);md.rectangle((x+2,y+2,x+w-3,y+h-3),fill='#c9c8a9')
 for xx in range(x+w//2,x+w-1,w//2):d.line((xx,y+1,xx,y+h-2),fill=INK);md.line((xx,y+1,xx,y+h-2),fill='black')
 if key=='arch':
  # True geometry is arched; the arch and lead bars are also painted on the pixel grid.
  d.arc((x,y,x+w-1,y+w-1),180,360,fill=INK,width=1);md.arc((x,y,x+w-1,y+w-1),180,360,fill='black',width=1)
  for yy in [y+14,y+28]:d.line((x+1,yy,x+w-2,yy),fill=INK);md.line((x+1,yy,x+w-2,yy),fill='black')
save(im,'glass.png');save(em,'glass-emission.png')
im=Image.new('RGBA',(128,64),STONE);d=ImageDraw.Draw(im);REG['nameplate']={'rect_art':[0,0,100,16]};d.rectangle((0,0,99,15),outline=INK);d.line((1,1,98,1),fill='#f5ecd5');text(d,10,5,'PUBLIC LIBRARY');save(im,'signs.png')
im=Image.new('RGBA',(32,32),'#efdcb0');d=ImageDraw.Draw(im);d.rectangle((0,0,31,31),outline=INK);d.line((2,2,29,2),fill='#fff1c9');save(im,'glow.png')
im=Image.new('RGBA',(64,64),'#716e5b');d=ImageDraw.Draw(im);REG['louver']={'rect_art':[0,0,24,16]}
for y in range(2,15,3):d.line((1,y,22,y),fill=INK);d.line((1,y+1,22,y+1),fill='#bbbba4')
save(im,'panels.png');(R/'art-regions.json').write_text(json.dumps(REG,indent=2)+'\n')
