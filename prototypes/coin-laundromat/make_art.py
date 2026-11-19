"""Original mid-century laundry artwork, with shared stucco/roof/paving swatches."""
from pathlib import Path
from PIL import Image,ImageDraw
from pixel_font import FONT
import json
R=Path(__file__).resolve().parent;REPO=R.parents[1];REG={};INK='#34464c';TEAL='#527c77';CREAM='#e8dfc8';CORAL='#b97567'
def save(im,n):im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(R/'textures'/n)
def text(d,xy,s,c=INK):
 x,y=xy
 for ch in s:
  if ch==' ':x+=3;continue
  for j,row in enumerate(FONT[ch]):
   for i,b in enumerate(row):
    if b=='1':d.point((x+i,y+j),fill=c)
  x+=6
j=json.loads((REPO/'kotlin-street/tools/swatches/atlas/atlas.json').read_text());atlas=Image.open(REPO/'kotlin-street/tools/swatches/atlas/atlas.png').convert('RGBA');sources=[]
for key,name in [('wall_stucco_red','stucco'),('roof_membrane_grey','roof'),('pavement','paving')]:
 e=j[key];im=atlas.crop((e['x'],e['y'],e['x']+e['w'],e['y']+e['h']));im.save(R/'source-materials'/(key+'.png'));im=im.resize((im.width//2,im.height//2),Image.Resampling.NEAREST)
 if name=='stucco':
  vals=sorted({im.getpixel((x,y)) for y in range(im.height) for x in range(im.width)},key=lambda c:sum(c[:3]));pal=['#b8b09b','#c8c0aa','#d8cfb7','#e8dfc8','#f1e7d0'];lookup={v:pal[round(i*4/max(1,len(vals)-1))] for i,v in enumerate(vals)};out=Image.new('RGBA',im.size);d=ImageDraw.Draw(out)
  for y in range(im.height):
   for x in range(im.width):d.point((x,y),fill=lookup[im.getpixel((x,y))])
  im=out
 save(im,name+'.png');sources.append({'id':key,'atlas_rect':[e['x'],e['y'],e['w'],e['h']],'source':e['entry'],'tint':'warm cream' if name=='stucco' else None})
(R/'source-materials/provenance.json').write_text(json.dumps({'licence':'LimeZu swatches: Timerpg game use only, original credit required. Other art/geometry original.','swatches':sources},indent=2)+'\n')
for name,colors in [('coral',['#855e58',CORAL,'#d69b83','#a76d60']),('steel',['#30494e',TEAL,'#88a298','#b5ad92']),('enamel',['#a8ac9e','#e5e4d3','#f7edda','#c3c6b7']),('drum',['#263940','#3c555b','#73918c','#526e72'])]:
 im=Image.new('RGBA',(64,64),colors[1]);d=ImageDraw.Draw(im)
 for y in range(0,64,16):d.line((0,y,63,y),fill=colors[0]);d.line((0,y+1,63,y+1),fill=colors[2])
 for y in range(64):
  for x in range(64):
   if (x*23+y*17)%173==0:d.point((x,y),fill=colors[3])
 save(im,name+'.png')
im=Image.new('RGBA',(64,64),CREAM);d=ImageDraw.Draw(im)
for y in range(64):
 for x in range(64):
  col='#a1b4a8' if (x//4+y//4)%2 else '#e3dbc5';d.point((x,y),fill=col)
  if (x*19+y*31)%113==0:d.point((x,y),fill='#b8b5a1')
save(im,'floor.png')
# Utility and optional fixture face atlas. None of these marks set a price or machine state.
im=Image.new('RGBA',(256,128),CREAM);d=ImageDraw.Draw(im)
for key,rect in [('service_door',[0,0,14,30]),('washer_face',[20,0,12,14]),('dryer_face',[40,0,16,24]),('change_face',[64,0,12,24]),('louver',[88,0,12,20])]:
 x,y,w,h=rect;REG[key]={'rect_art':rect};d.rectangle((x,y,x+w-1,y+h-1),fill='#e5e4d3',outline=INK);d.line((x+1,y+1,x+w-2,y+1),fill='#faf0d8')
 if key=='service_door':d.rectangle((x+3,y+3,x+w-4,y+19),outline=TEAL);d.point((x+w-3,y+22),fill=INK)
 if key in ['washer_face','dryer_face']:
  d.rectangle((x+1,y+1,x+w-2,y+3),fill=TEAL);d.point((x+3,y+2),fill=CREAM);d.line((x+w-4,y+1,x+w-4,y+3),fill=INK)
  if key=='dryer_face':
   for yy in [y+h-4,y+h-2]:d.line((x+2,yy,x+w-3,yy),fill='#89988f')
 if key=='change_face':
  d.rectangle((x+3,y+4,x+8,y+10),fill=TEAL);d.line((x+5,y+5,x+5,y+8),fill=CREAM);d.rectangle((x+3,y+15,x+8,y+19),fill=INK)
 if key=='louver':
  for yy in range(y+3,y+h-2,3):d.line((x+2,yy,x+w-3,yy),fill=INK);d.line((x+2,yy+1,x+w-3,yy+1),fill='#b3c1b3')
save(im,'panels.png')
# Real alpha panes: clear centres, opaque original pixel frames/decals.
im=Image.new('RGBA',(256,128),(102,153,149,36));d=ImageDraw.Draw(im)
for key,rect in [('store_window',[0,0,60,28]),('side_window',[64,0,20,18]),('front_door',[88,0,18,32]),('narrow_window',[112,0,20,28])]:
 x,y,w,h=rect;REG[key]={'rect_art':rect};d.rectangle((x,y,x+w-1,y+h-1),outline=INK);d.line((x+1,y+1,x+w-2,y+1),fill='#b2c9b9')
 if key=='store_window':
  for xx in [x+20,x+40]:d.line((xx,y,xx,y+h-1),fill=INK)
  for xx,yy,rr in [(x+7,y+6,3),(x+13,y+4,2),(x+51,y+6,3)]:d.ellipse((xx-rr,yy-rr,xx+rr,yy+rr),outline=(236,228,196,255))
 if key=='front_door':d.rectangle((x+2,y+20,x+w-3,y+h-2),fill=TEAL);d.line((x+1,y+19,x+w-2,y+19),fill=INK)
save(im,'glass.png')
im=Image.new('RGBA',(128,128),TEAL);d=ImageDraw.Draw(im)
for key,rect in [('nameplate',[0,0,112,18]),('pylon',[0,24,40,36])]:
 x,y,w,h=rect;REG[key]={'rect_art':rect};d.rectangle((x,y,x+w-1,y+h-1),fill=CREAM,outline=INK);d.line((x+1,y+1,x+w-2,y+1),fill='#fff0cc')
 if key=='nameplate':text(d,(x+26,y+2),'SUDS & SPIN');text(d,(x+21,y+10),'COIN LAUNDRY')
 else:text(d,(x+9,y+4),'SUDS');text(d,(x+3,y+15),'& SPIN');text(d,(x+9,y+26),'COIN')
save(im,'signs.png')
im=Image.new('RGBA',(32,8),'#708a82');d=ImageDraw.Draw(im);d.rectangle((0,0,31,7),outline=INK);d.rectangle((2,2,29,5),fill='#fff0bd');save(im,'glow.png')
(R/'art-regions.json').write_text(json.dumps(REG,indent=2)+'\n')
