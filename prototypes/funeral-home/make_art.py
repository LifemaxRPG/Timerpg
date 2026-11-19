"""Deterministic original Willow House pixel artwork; retained shared swatch cuts."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageColor
from pixel_font import FONT
import json
R=Path(__file__).resolve().parent;REPO=R.parents[1];REG={};CREAM='#e3dcc5';INK='#3d4f4b'
def save(im,n):im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(R/'textures'/n)
def text(d,x,y,s):
 for ch in s:
  if ch==' ':x+=3;continue
  for j,row in enumerate(FONT[ch]):
   for i,b in enumerate(row):
    if b=='1':d.point((x+i,y+j),fill=CREAM)
  x+=6
j=json.loads((REPO/'kotlin-street/tools/swatches/atlas/atlas.json').read_text());atlas=Image.open(REPO/'kotlin-street/tools/swatches/atlas/atlas.png').convert('RGBA');sources=[]
for key,name in [('wall_stucco_red','interior'),('brick_red','brick'),('roof_slate','slate'),('pavement','paving')]:
 e=j[key];im=atlas.crop((e['x'],e['y'],e['x']+e['w'],e['y']+e['h']));im.save(R/'source-materials'/(key+'.png'));im=im.resize((im.width//2,im.height//2),Image.Resampling.NEAREST)
 if name=='interior':
  vals=sorted(set(im.getdata()),key=lambda c:sum(c[:3]));pal=['#d0c8b2','#d7cfb9','#ded6c0','#e5ddc7','#ece4ce'];lut={v:pal[round(i*4/max(1,len(vals)-1))] for i,v in enumerate(vals)};out=Image.new('RGBA',im.size);out.putdata([ImageColor.getcolor(lut[v],'RGBA') for v in im.getdata()]);im=out
 save(im,name+'.png');sources.append({'id':key,'atlas_rect':[e['x'],e['y'],e['w'],e['h']],'source':e['entry'],'tint':'cream' if name=='interior' else None})
(R/'source-materials/provenance.json').write_text(json.dumps({'licence':'LimeZu shared swatches: Timerpg game use only; retain original credit. Supplemental art and geometry original.','swatches':sources},indent=2)+'\n')
for name,cols in [('siding',['#a9afa1','#d7d8c4','#eee8d2','#c1c5b4']),('trim',['#b6b29f',CREAM,'#f4ecd6','#d1cbb4']),('wood',['#4c4c42','#776452','#998166','#665a4c']),('steel',['#283d3a','#425b53','#708375','#526c60']),('lawn',['#61705a','#7c896c','#97a07b','#707f61'])]:
 im=Image.new('RGBA',(64,64),cols[1]);d=ImageDraw.Draw(im)
 for y in range(64):
  if name in ['siding','wood'] and y%4==0:d.line((0,y,63,y),fill=cols[0]);d.line((0,y+1,63,y+1),fill=cols[2])
  for x in range(64):
   if (x*23+y*17)%179==0:d.point((x,y),fill=cols[3])
   if name=='lawn' and (x*19+y*13)%73==0:d.point((x,y),fill=cols[2])
 save(im,name+'.png')
# Opaque privacy glass and door-face atlas. Separate emission image lights panes only.
im=Image.new('RGBA',(256,256),INK);mask=Image.new('RGBA',im.size,(0,0,0,255));d=ImageDraw.Draw(im);md=ImageDraw.Draw(mask)
entries=[('home_window',0,0,24,30),('side_window',28,0,20,28),('small_window',52,0,12,28),('high_window',68,0,20,12),('chapel_window',92,0,20,32),('chapel_front',116,0,28,28),('attic',148,0,24,16),('front_leaf',0,40,12,34),('side_door',16,40,18,32),('staff_door',38,40,16,32),('chapel_leaf',58,40,12,32),('garage_leaf',74,40,24,38),('louver',102,40,24,16),('garage_window',130,40,20,18)]
for key,x,y,w,h in entries:
 REG[key]={'rect_art':[x,y,w,h]};d.rectangle((x,y,x+w-1,y+h-1),fill='#556c5e',outline=INK);d.line((x+1,y+1,x+w-2,y+1),fill='#e7dfc6')
 if 'window' in key or key in ['chapel_front','attic']:
  d.rectangle((x+2,y+2,x+w-3,y+h-3),fill='#8eaaa0');md.rectangle((x+2,y+2,x+w-3,y+h-3),fill='#decda3')
  for yy in [y+h//2]:d.line((x+1,yy,x+w-2,yy),fill=CREAM);md.line((x+1,yy,x+w-2,yy),fill='black')
  d.line((x+w//2,y+1,x+w//2,y+h-2),fill=CREAM);md.line((x+w//2,y+1,x+w//2,y+h-2),fill='black')
  if key.startswith('chapel'):
   for yy in range(y+5,y+h-3,8):d.line((x+3,yy,x+w-4,yy+4),fill='#647d70');md.line((x+3,yy,x+w-4,yy+4),fill='#867e65')
 elif key=='louver':
  for yy in range(y+3,y+h-2,3):d.line((x+2,yy,x+w-3,yy),fill=INK);d.line((x+2,yy+1,x+w-3,yy+1),fill='#86927b')
 else:
  for yy in ([4,19] if key!='garage_leaf' else [4,15,26]):d.rectangle((x+2,y+yy,x+w-3,min(y+yy+9,y+h-3)),outline='#b6bd9f')
  d.point((x+w-3,y+h-11),fill='#d6b77e')
save(im,'glass-panels.png');save(mask,'pane-emission.png')
im=Image.new('RGBA',(64,32),INK);d=ImageDraw.Draw(im);REG['nameplate']={'rect_art':[0,0,48,24]};d.rectangle((0,0,47,23),outline=CREAM);text(d,6,2,'WILLOW');text(d,9,10,'HOUSE')
tiny={'F':['111','100','110','100','100'],'U':['101','101','101','101','111'],'N':['101','111','111','111','101'],'E':['111','100','110','100','111'],'R':['110','101','110','101','101'],'A':['010','101','111','101','101'],'L':['100','100','100','100','111'],'H':['101','101','111','101','101'],'O':['111','101','101','101','111'],'M':['101','111','111','101','101']};x=1
for c in 'FUNERAL HOME':
 if c==' ':x+=2;continue
 for yy,row in enumerate(tiny[c]):
  for xx,b in enumerate(row):
   if b=='1':d.point((x+xx,18+yy),fill=CREAM)
 x+=4
save(im,'signs.png')
im=Image.new('RGBA',(16,16),'#f6ddb0');d=ImageDraw.Draw(im);d.rectangle((0,0,15,15),outline=INK);d.line((3,1,3,14),fill='#b7a67e');d.line((12,1,12,14),fill='#b7a67e');save(im,'glow.png')
(R/'art-regions.json').write_text(json.dumps(REG,indent=2)+'\n')
