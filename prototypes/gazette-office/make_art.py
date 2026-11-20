"""Original Gazette panels in Timerpg's palette, with shared masonry/roof swatches."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageEnhance
from pixel_font import FONT
import json
R=Path(__file__).resolve().parent;REPO=R.parents[1];REG={};INK='#303e47';CREAM='#e4d7b8';TEAL='#466c6a'
def save(im,n):im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(R/'textures'/n)
def text(d,xy,s,color=INK):
 x,y=xy
 for ch in s:
  if ch==' ':x+=3;continue
  for j,row in enumerate(FONT[ch]):
   for i,v in enumerate(row):
    if v=='1':d.point((x+i,y+j),fill=color)
  x+=6
j=json.loads((REPO/'kotlin-street/tools/swatches/atlas/atlas.json').read_text());a=Image.open(REPO/'kotlin-street/tools/swatches/atlas/atlas.png').convert('RGBA');sources=[]
for key,name in [('brick_red','brick'),('roof_membrane_grey','membrane'),('roof_tin_blue','metal-roof'),('pavement','paving'),('asphalt_worn','asphalt')]:
 e=j[key];im=a.crop((e['x'],e['y'],e['x']+e['w'],e['y']+e['h']));im.save(R/'source-materials'/(key+'.png'));im=im.resize((im.width//2,im.height//2),Image.Resampling.NEAREST)
 if name=='metal-roof':im=ImageEnhance.Brightness(ImageEnhance.Color(im).enhance(.45)).enhance(.8)
 save(im,name+'.png');sources.append({'id':key,'atlas_rect':[e['x'],e['y'],e['w'],e['h']],'source':e['entry'],'tint':'weathered slate blue' if name=='metal-roof' else None})
(R/'source-materials/provenance.json').write_text(json.dumps({'licence':'LimeZu swatches: Timerpg game use only; original credit required. Other geometry/pixel art original.','swatches':sources},indent=2)+'\n')
for name,pal in [('cream',['#a69880',CREAM,'#f4e8cc','#c6b99c']),('interior',['#b7a889','#d4c6a7','#f0dfbf','#c5b596']),('steel',['#2f414c',TEAL,'#78958a','#b5ab8e'])]:
 im=Image.new('RGBA',(64,64),pal[1]);d=ImageDraw.Draw(im)
 for y in range(0,64,16):d.line((0,y,63,y),fill=pal[0]);d.line((0,y+1,63,y+1),fill=pal[2])
 for y in range(64):
  for x in range(64):
   if (x*17+y*23)%211==0:d.point((x,y),fill=pal[3])
 save(im,name+'.png')
# All utility rectangles use exact physical art-pixel dimensions.
im=Image.new('RGBA',(256,128),INK);d=ImageDraw.Draw(im)
for key,rect in [('main_door',[0,0,16,32]),('side_door',[20,0,14,30]),('loading_leaf',[40,0,24,36]),('paper',[72,0,32,32]),('vent',[112,0,16,12])]:
 x,y,w,h=rect;REG[key]={'rect_art':rect};d.rectangle((x,y,x+w-1,y+h-1),fill=TEAL if 'door' in key or key=='loading_leaf' else CREAM,outline=INK);d.line((x+1,y+1,x+w-2,y+1),fill='#91aaa0')
 if 'door' in key:
  d.rectangle((x+3,y+3,x+w-4,y+17),fill='#aaa989',outline=INK);d.rectangle((x+3,y+21,x+w-4,y+h-4),outline=INK);d.point((x+w-3,y+21),fill=CREAM)
 if key=='loading_leaf':
  for yy in range(y+5,y+h-2,5):d.line((x+2,yy,x+w-3,yy),fill=INK)
  d.rectangle((x+4,y+17,x+6,y+21),fill=INK)
 if key=='paper':
  for yy in range(y+2,y+h,8):d.line((x,yy,x+w-1,yy),fill='#c0b498');d.line((x+5,yy+2,x+24,yy+2),fill='#9e9988')
 if key=='vent':
  for yy in range(y+3,y+h-2,3):d.line((x+2,yy,x+w-3,yy),fill=INK)
save(im,'panels.png')
im=Image.new('RGBA',(256,128),'#778e83');d=ImageDraw.Draw(im)
for key,rect in [('arch_window',[0,0,24,30]),('small_window',[28,0,20,26]),('display_window',[52,0,48,26]),('side_window',[104,0,20,24]),('clerestory',[0,40,32,14])]:
 x,y,w,h=rect;REG[key]={'rect_art':rect};d.rectangle((x,y,x+w-1,y+h-1),fill='#c3b790',outline=INK);d.line((x+1,y+1,x+w-2,y+1),fill='#e7d5a7');d.line((x+w//2,y+1,x+w//2,y+h-2),fill=INK);d.line((x+1,y+h//2,x+w-2,y+h//2),fill=INK)
 if key=='arch_window':
  # Brick-coloured corners make a pixel arch without modelling each masonry unit.
  for dx,dy in [(0,0),(1,0),(2,0),(3,0),(0,1),(1,1),(0,2),(23,0),(22,0),(21,0),(20,0),(23,1),(22,1),(23,2)]:d.point((x+dx,y+dy),fill='#8c594d')
  d.arc((x+1,y+1,x+w-2,y+17),180,360,fill=INK,width=1);d.line((x+1,y+12,x+w-2,y+12),fill=INK)
 if key=='display_window':
  for xx in [x+4,x+19,x+34]:
   d.rectangle((xx,y+13,xx+10,y+23),fill=CREAM,outline=INK);d.line((xx+2,y+15,xx+8,y+15),fill=INK)
   for yy in [18,20,22]:d.line((xx+2,y+yy,xx+8,y+yy),fill='#9a9380')
save(im,'glass.png')
im=Image.new('RGBA',(128,128),TEAL);d=ImageDraw.Draw(im)
for key,rect in [('masthead',[0,0,100,14]),('blade',[0,24,32,28]),('emblem',[104,0,16,16]),('dispatch',[40,24,54,12])]:
 x,y,w,h=rect;REG[key]={'rect_art':rect};d.rectangle((x,y,x+w-1,y+h-1),fill=CREAM,outline=INK);d.line((x+1,y+1,x+w-2,y+1),fill='#f9ebc9')
 if key=='masthead':text(d,(x+19,y+4),'THE GAZETTE');d.line((x+3,y+11,x+w-4,y+11),fill=TEAL)
 if key=='blade':text(d,(x+7,y+4),'THE');text(d,(x+2,y+14),'PRESS');d.line((x+4,y+24,x+w-5,y+24),fill=TEAL)
 if key=='dispatch':text(d,(x+5,y+3),'DISPATCH')
 if key=='emblem':d.rectangle((x+3,y+5,x+11,y+12),outline=TEAL);d.line((x+4,y+10,x+12,y+2),fill=INK,width=2);d.line((x+9,y+4,x+12,y+4),fill=TEAL)
save(im,'signs.png')
im=Image.new('RGBA',(16,16),'#536b6a');d=ImageDraw.Draw(im);d.rectangle((0,0,15,15),outline=INK);d.rectangle((2,2,13,12),fill='#ffe1a3');d.line((3,3,12,3),fill='#fff0c8');save(im,'glow.png')
(R/'art-regions.json').write_text(json.dumps(REG,indent=2)+'\n')
