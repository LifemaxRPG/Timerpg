"""Shared LimeZu materials and original whole-art-pixel motel details."""
from pathlib import Path
from PIL import Image,ImageDraw
import json
from sign_art import text,write_name,write_vacancy
R=Path(__file__).resolve().parent;REPO=R.parents[1];REG={};INK='#3a3a50';CREAM='#eee9df';TEAL='#46756a';CORAL='#b75d56'
def save(im,n):im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(R/'textures'/n)
a=Image.open(REPO/'kotlin-street/tools/swatches/atlas/atlas.png').convert('RGBA');j=json.loads((REPO/'kotlin-street/tools/swatches/atlas/atlas.json').read_text());sources=[]
for key,name in [('wall_stucco_red','stucco'),('brick_red','brick'),('roof_membrane_grey','roof'),('pavement','paving'),('asphalt_worn','asphalt')]:
 e=j[key];im=a.crop((e['x'],e['y'],e['x']+e['w'],e['y']+e['h']));im.save(R/'source-materials'/(key+'.png'));im=im.resize((im.width//2,im.height//2),Image.Resampling.NEAREST)
 if name=='stucco':
  values=sorted({im.getpixel((x,y)) for y in range(im.height) for x in range(im.width)},key=lambda c:sum(c[:3]));pal=['#c6bca8','#d8ceb9','#e5dbc6','#eee5d3','#f6eddd'];out=Image.new('RGBA',im.size);d=ImageDraw.Draw(out);lookup={c:pal[round(i*4/max(1,len(values)-1))] for i,c in enumerate(values)}
  for y in range(im.height):
   for x in range(im.width):d.point((x,y),fill=lookup[im.getpixel((x,y))])
  im=out
 save(im,name+'.png');sources.append({'id':key,'atlas_rect':[e['x'],e['y'],e['w'],e['h']],'source':e['entry'],'tint':'warm cream' if name=='stucco' else None})
(R/'source-materials/provenance.json').write_text(json.dumps({'licence':'LimeZu: Timerpg game use only, credit required','swatches':sources},indent=2)+'\n')
im=Image.new('RGBA',(64,64),TEAL);d=ImageDraw.Draw(im);d.rectangle((0,0,63,63),outline=INK);d.line((1,1,62,1),fill='#76998a');d.line((1,2,1,62),fill='#76998a')
for x,y in [(12,14),(44,29),(19,52)]:d.point((x,y),fill='#c7bba7')
save(im,'teal.png')
im=Image.new('RGBA',(256,128),INK);d=ImageDraw.Draw(im)
for key,rect,color in [('metal',[0,0,64,64],'#7d7f99'),('rubber',[64,0,64,64],INK),('coral',[0,64,64,64],CORAL),('cream',[64,64,64,64],CREAM),('pool_tile',[128,0,64,64],'#9bc7cd'),('carpet',[192,64,64,64],'#867660')]:
 x,y,w,h=rect;REG[key]={'rect_art':rect};d.rectangle((x,y,x+w-1,y+h-1),fill=color)
 if key=='pool_tile':
  for xx in range(x,x+w,4):d.line((xx,y,xx,y+h-1),fill='#d7e3d9')
  for yy in range(y,y+h,4):d.line((x,yy,x+w-1,yy),fill='#d7e3d9')
 if key=='carpet':
  for yy in range(y,y+h):
   for xx in range(x,x+w):
    if (xx*13+yy*7)%23==0:d.point((xx,yy),fill='#aa987b')
# Opaque doors, AC grilles and ice-machine artwork stay out of the glass material.
REG['guest_door']={'rect_art':[128,64,12,28]};d.rectangle((128,64,139,91),fill=TEAL,outline=INK);d.rectangle((130,66,137,76),outline='#235d68');d.rectangle((130,79,137,88),outline='#235d68');d.point((137,78),fill='#d8c08c')
REG['ice_machine']={'rect_art':[144,64,20,26]};d.rectangle((144,64,163,89),fill=CREAM,outline=INK);text(d,(145,66),'ICE','#b75d56');d.rectangle((150,75,158,81),fill=INK);d.rectangle((151,76,157,79),fill='#7d7f99')
for y in (84,86,88):d.line((146,y,161,y),fill='#7d7f99')
REG['ac_grille']={'rect_art':[144,96,16,6]};d.rectangle((144,96,159,101),fill='#7d7f99',outline=INK)
for y in (98,100):d.line((146,y,157,y),fill=INK)
save(im,'utility.png')
im=Image.new('RGBA',(256,128),(0,0,0,0));d=ImageDraw.Draw(im)
def pane(key,rect,curtain=False):
 x,y,w,h=rect;REG[key]={'rect_art':rect};d.rectangle((x,y,x+w-1,y+h-1),fill=(75,105,118,100),outline=INK);d.line((x+1,y+1,x+w-2,y+1),fill=CREAM)
 if curtain:
  for xx in range(x+2,x+w-2):
   if xx<x+w//2-2 or xx>x+w//2+2:d.line((xx,y+2,xx,y+h-3),fill='#b7a082' if xx%3 else '#e2d0ad')
 d.line((x+w//2,y,x+w//2,y+h-1),fill=INK)
pane('room_window',[0,0,22,16],True);pane('bath_window',[24,0,12,8]);pane('office_window',[38,0,28,24]);pane('office_side',[68,0,24,24]);pane('office_door',[94,0,14,30]);save(im,'glass.png')
im=Image.new('RGBA',(128,128),TEAL);d=ImageDraw.Draw(im)
for i in range(12):
 x=(i%6)*16;y=(i//6)*12;REG['number_%02d'%(i+1)]={'rect_art':[x,y,14,10]};d.rectangle((x,y,x+13,y+9),fill=CORAL,outline=INK);text(d,(x+1,y+2),'%02d'%(i+1),CREAM)
for key,rect,label in [('office',[0,28,38,10],'OFFICE'),('pool',[40,28,26,10],'POOL'),('linen',[68,28,32,10],'LINEN')]:
 x,y,w,h=rect;REG[key]={'rect_art':rect};d.rectangle((x,y,x+w-1,y+h-1),fill=TEAL,outline=INK);text(d,(x+1,y+1),label,CREAM)
REG['lamp']={'rect_art':[0,42,12,4]};d.rectangle((0,42,11,45),fill='#ffe2a1',outline=INK)
REG['neon']={'rect_art':[0,64,64,64]};d.rectangle((0,64,63,127),fill='#ffe2a1');save(im,'glow.png')
im=Image.new('RGBA',(32,32),(70,178,188,190));d=ImageDraw.Draw(im)
for y in range(32):
 for x in range(32):
  if (x//2+y//2)%7==0:d.point((x,y),fill=(125,211,211,190))
  elif (x*11+y*7)%37==0:d.point((x,y),fill=(45,148,169,190))
save(im,'water.png')
write_name(R/'textures/sign-name.png');write_vacancy(R/'textures/vacancy.png',False);write_vacancy(R/'textures/no-vacancy.png',True)
REG['name']={'rect_art':[0,0,80,28]};REG['vacancy']={'rect_art':[0,0,64,12]}
(R/'art-regions.json').write_text(json.dumps(REG,indent=2)+'\n')
