"""Shared Timerpg swatches with original timber, galvanized sheet and mill signage."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageEnhance
from pixel_font import FONT
import json
R=Path(__file__).resolve().parent;REPO=R.parents[1];REG={};INK='#383b4a';CREAM='#e3d7b7';RED='#a56454'
def save(im,n):im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(R/'textures'/n)
def txt(d,xy,s,c=INK):
 x,y=xy
 for ch in s:
  if ch==' ':x+=3;continue
  for j,row in enumerate(FONT[ch]):
   for i,v in enumerate(row):
    if v=='1':d.point((x+i,y+j),fill=c)
  x+=6
j=json.loads((REPO/'kotlin-street/tools/swatches/atlas/atlas.json').read_text());a=Image.open(REPO/'kotlin-street/tools/swatches/atlas/atlas.png').convert('RGBA');sources=[]
for key,name in [('brick_red','brick'),('roof_tin_blue','roof'),('pavement','paving')]:
 e=j[key];im=a.crop((e['x'],e['y'],e['x']+e['w'],e['y']+e['h']));im.save(R/'source-materials'/(key+'.png'));im=im.resize((im.width//2,im.height//2),Image.Resampling.NEAREST)
 if name=='roof':im=ImageEnhance.Brightness(ImageEnhance.Color(im).enhance(.6)).enhance(.8)
 save(im,name+'.png');sources.append({'id':key,'atlas_rect':[e['x'],e['y'],e['w'],e['h']],'source':e['entry'],'tint':'Weathered slate blue: saturation 0.6, brightness 0.8' if name=='roof' else None})
(R/'source-materials/provenance.json').write_text(json.dumps({'licence':'LimeZu: Timerpg game use only, retain original credit. Original geometry and supplemental mill artwork.','swatches':sources},indent=2)+'\n')
for name,pal in [('timber-red',['#754d48','#a56454','#c78e70','#8d5a4e']),('timber-oak',['#7a6456','#b09a76','#d0ba8e','#97805f']),('dark',['#29323e','#414958','#666b6e','#39414d'])]:
 im=Image.new('RGBA',(64,64),pal[1]);d=ImageDraw.Draw(im)
 for x in range(0,64,8):
  d.line((x,0,x,63),fill=pal[0]);d.line((x+1,0,x+1,63),fill=pal[2]);d.point((x+4,6+(x*3)%40),fill=pal[0])
 for y in range(64):
  for x in range(64):
   if (x*13+y*19)%157==0:d.point((x,y),fill=pal[3])
 save(im,name+'.png')
im=Image.new('RGBA',(64,64),'#a9b4b1');d=ImageDraw.Draw(im)
for y in range(0,64,4):d.line((0,y,63,y),fill='#6b7a7f');d.line((0,y+1,63,y+1),fill='#d2d9cd')
for x in range(4,64,16):
 for y in range(4,64,16):d.point((x,y),fill='#596974')
save(im,'steel.png')
im=Image.new('RGBA',(64,64),'#8d8a75');d=ImageDraw.Draw(im)
for y in range(64):
 for x in range(64):
  k=(x*17+y*29+x*y)%37
  if k<5:d.point((x,y),fill=['#6f7469','#b5ae92','#787e73','#a29d85','#5c695f'][k])
save(im,'gravel.png')
# Utility atlas, whole art pixels; each panel has exactly its physical dimensions.
im=Image.new('RGBA',(256,128),INK);d=ImageDraw.Draw(im)
def region(key,rect,fill):
 x,y,w,h=rect;REG[key]={'rect_art':rect};d.rectangle((x,y,x+w-1,y+h-1),fill=fill);return x,y,w,h
for key,rect in [('metal',[0,0,64,64]),('sack',[64,0,16,16]),('public_door',[80,0,14,30]),('load_front',[96,0,30,48]),('load_rear',[128,0,28,48]),('service_door',[160,0,14,30]),('louver',[176,0,12,20]),('lamp',[192,0,8,8])]:
 x,y,w,h=region(key,rect,'#868a83' if key=='metal' else '#b5a17d')
 d.rectangle((x,y,x+w-1,y+h-1),outline=INK);d.line((x+1,y+1,x+w-2,y+1),fill='#d7c49b')
 if key.startswith('load_'):
  for xx in range(x+4,x+w-1,5):d.line((xx,y+2,xx,y+h-3),fill='#8b765d')
  d.line((x+2,y+3,x+w-3,y+h-4),fill='#6b6358',width=2);d.rectangle((x+3,y+h//2,x+5,y+h//2+4),fill=INK)
 if key=='public_door':d.rectangle((x+3,y+3,x+10,y+17),fill='#637e7f');d.line((x+3,y+11,x+10,y+11),fill=INK);d.point((x+11,y+21),fill=INK)
 if key=='service_door':
  for yy in range(y+4,y+22,4):d.line((x+3,yy,x+w-4,yy),fill=INK)
  d.point((x+11,y+23),fill=INK)
 if key=='sack':d.line((x+8,y+4,x+8,y+12),fill='#6d7459');d.line((x+5,y+5,x+8,y+8,x+11,y+5),fill='#6d7459');d.line((x+4,y+9,x+8,y+11,x+12,y+9),fill='#6d7459')
 if key=='louver':
  for yy in range(y+3,y+h-2,3):d.line((x+2,yy,x+w-3,yy),fill=INK);d.line((x+2,yy+1,x+w-3,yy+1),fill='#d7c49b')
save(im,'utility.png')
im=Image.new('RGBA',(128,64),'#547477');d=ImageDraw.Draw(im)
for key,rect in [('office_window',[0,0,24,24]),('narrow_window',[28,0,12,24]),('tower_window',[44,0,24,16]),('side_window',[72,0,16,20])]:
 x,y,w,h=rect;REG[key]={'rect_art':rect};d.rectangle((x,y,x+w-1,y+h-1),fill='#c0b894',outline=INK);d.line((x+1,y+1,x+w-2,y+1),fill='#e4d4a8')
 for xx in range(x+2,x+w-2,8):d.line((xx,y+2,xx,y+h-3),fill='#8c9e91')
 d.line((x+1,y+h//2,x+w-2,y+h//2),fill=INK);d.line((x+w//2,y+1,x+w//2,y+h-2),fill=INK)
save(im,'glass.png')
im=Image.new('RGBA',(128,128),INK);d=ImageDraw.Draw(im)
REG['mill_name']={'rect_art':[0,0,96,18]};d.rectangle((0,0,95,17),fill=CREAM,outline=INK);d.line((1,1,94,1),fill='#fae6bd');txt(d,(19,2),'HEARTHSIDE');txt(d,(14,10),'FEED & GRAIN')
REG['tower_sign']={'rect_art':[0,32,12,40]};d.rectangle((0,32,11,71),fill=CREAM,outline=INK);d.line((1,33,10,33),fill='#fae6bd')
for i,c in enumerate('FEED'):txt(d,(3,35+i*9),c)
REG['dock_label']={'rect_art':[16,32,48,12]};d.rectangle((16,32,63,43),fill=CREAM,outline=INK);txt(d,(20,35),'LOADING')
save(im,'signs.png')
im=Image.new('RGBA',(16,16),'#6b6559');d=ImageDraw.Draw(im);d.rectangle((0,0,15,15),outline=INK);d.rectangle((2,2,13,12),fill='#ffe4ad');d.line((3,3,12,3),fill='#fff0c9');save(im,'glow.png')
(R/'art-regions.json').write_text(json.dumps(REG,indent=2)+'\n')
