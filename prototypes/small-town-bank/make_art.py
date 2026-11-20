"""Deterministic original bank art, pane-only emission and retained shared swatches."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageColor
from pixel_font import FONT
import math,json
R=Path(__file__).resolve().parent;REPO=R.parents[1];REG={};INK='#3c4d4b';STONE='#d7d1bc';BRONZE='#9d8a60'
def save(im,n):im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(R/'textures'/n)
def text(d,x,y,s,c=INK):
 for ch in s:
  if ch==' ':x+=3;continue
  for j,row in enumerate(FONT[ch]):
   for i,b in enumerate(row):
    if b=='1':d.point((x+i,y+j),fill=c)
  x+=6
j=json.loads((REPO/'kotlin-street/tools/swatches/atlas/atlas.json').read_text());atlas=Image.open(REPO/'kotlin-street/tools/swatches/atlas/atlas.png').convert('RGBA');sources=[]
for key,name in [('brick_red','brick'),('wall_stucco_red','interior'),('roof_membrane_grey','roof'),('pavement','paving')]:
 e=j[key];im=atlas.crop((e['x'],e['y'],e['x']+e['w'],e['y']+e['h']));im.save(R/'source-materials'/(key+'.png'));im=im.resize((im.width//2,im.height//2),Image.Resampling.NEAREST)
 if name=='interior':
  vals=sorted(set(im.getdata()),key=lambda c:(sum(c[:3]),c));pal=['#c9c5af','#d2ceb8','#ddd7c0','#e7dfc8','#eee6cf'];lut={v:ImageColor.getcolor(pal[round(i*4/max(1,len(vals)-1))],'RGBA') for i,v in enumerate(vals)};o=Image.new('RGBA',im.size);o.putdata([lut[v] for v in im.getdata()]);im=o
 save(im,name+'.png');sources.append({'id':key,'atlas_rect':[e['x'],e['y'],e['w'],e['h']],'source':e['entry'],'tint':'warm cream' if name=='interior' else None})
(R/'source-materials/provenance.json').write_text(json.dumps({'licence':'Shared LimeZu swatches: Timerpg-only; retain original credit. Supplemental art and all geometry original.','swatches':sources},indent=2)+'\n')
for name,cols in [('stone',['#aaa998',STONE,'#eee5ce','#c3c1ac']),('bronze',['#5e6657',BRONZE,'#c9b88c','#7e7b5e']),('steel',['#2d4447','#5d756e','#98a89a','#47615c']),('floor',['#959f90','#d0ceba','#e5ddc6','#b6bdab'])]:
 im=Image.new('RGBA',(64,64),cols[1]);d=ImageDraw.Draw(im)
 for y in range(64):
  for x in range(64):
   if name=='stone' and y%8==0:d.point((x,y),fill=cols[0])
   elif name=='stone' and y%8==1:d.point((x,y),fill=cols[2])
   elif name=='stone' and (x+(y//8%2)*16)%32==0:d.point((x,y),fill=cols[0])
   elif name=='floor' and (x%24==0 or y%24==0):d.point((x,y),fill=cols[0])
   elif (x*19+y*13)%151==0:d.point((x,y),fill=cols[3])
   elif (x*17+y*23)%223==0:d.point((x,y),fill=cols[2])
 save(im,name+'.png')
im=Image.new('RGBA',(256,128),(114,149,141,40));em=Image.new('RGBA',im.size,(0,0,0,255));d=ImageDraw.Draw(im);md=ImageDraw.Draw(em)
for key,rect in [('arch',[0,0,44,36]),('tall',[48,0,20,52]),('side',[72,0,24,44]),('rear',[100,0,20,36]),('sidelight',[124,0,8,32]),('teller',[136,0,30,22])]:
 x,y,w,h=rect;REG[key]={'rect_art':rect}
 if key=='teller':d.rectangle((x,y,x+w-1,y+h-1),fill=(114,149,141,24))
 d.rectangle((x,y,x+w-1,y+h-1),outline=INK);d.line((x+1,y+1,x+w-2,y+1),fill='#c9b88c')
 if key!='teller':md.rectangle((x+2,y+2,x+w-3,y+h-3),fill='#cec9a5')
 if key=='arch':
  d.arc((0,0,43,43),180,360,fill=INK)
  for a in [-150,-120,-90,-60,-30]:
   p=(round(22+21*math.cos(math.radians(a))),round(22+21*math.sin(math.radians(a))));d.line((22,22,*p),fill=BRONZE);md.line((22,22,*p),fill='black')
  d.line((1,22,42,22),fill=INK);md.line((1,22,42,22),fill='black');d.line((22,22,22,35),fill=INK);md.line((22,22,22,35),fill='black')
 elif key not in ['sidelight','teller']:
  d.line((x+w//2,y+1,x+w//2,y+h-2),fill=INK);md.line((x+w//2,y+1,x+w//2,y+h-2),fill='black');d.line((x+1,y+h//2,x+w-2,y+h//2),fill=INK);md.line((x+1,y+h//2,x+w-2,y+h//2),fill='black')
save(im,'glass.png');save(em,'pane-emission.png')
im=Image.new('RGBA',(256,128),'#73614c');d=ImageDraw.Draw(im)
for key,rect in [('wood',[0,0,128,128]),('terminal',[136,0,10,18]),('night_drop',[152,0,10,16]),('tray',[168,0,8,6]),('louver',[184,0,24,16])]:
 x,y,w,h=rect;REG[key]={'rect_art':rect}
 if key=='wood':
  d.rectangle((x,y,x+w-1,y+h-1),fill='#78674f')
  for yy in range(y,y+h,16):
   for xx in range(x,x+w,16):d.rectangle((xx+1,yy+1,xx+14,yy+14),outline='#454c43');d.line((xx+2,yy+2,xx+13,yy+2),fill='#aa9673')
 else:
  d.rectangle((x,y,x+w-1,y+h-1),fill='#b9c2ac',outline=INK);d.line((x+1,y+1,x+w-2,y+1),fill='#e6e4c9')
  if key in ['terminal','night_drop']:
   d.rectangle((x+2,y+4,x+7,y+8),outline=INK);d.line((x+2,y+4,x+5,y+7,x+7,y+4),fill=INK);d.line((x+1,y+11,x+w-2,y+11),fill=INK)
   if key=='terminal':
    for yy in [y+13,y+15]:d.line((x+3,yy,x+6,yy),fill='#6d8277')
  if key=='tray':d.rectangle((x+1,y+1,x+w-2,y+h-2),fill='#677e73')
  if key=='louver':
   for yy in range(y+3,y+h-2,3):d.line((x+2,yy,x+w-3,yy),fill=INK);d.line((x+2,yy+1,x+w-3,yy+1),fill='#e1dfc3')
for key,x,rows in [('lane_1',212,['010','110','010','010','111']),('lane_2',220,['110','001','010','100','111'])]:
 REG[key]={'rect_art':[x,0,4,6]};d.rectangle((x,0,x+3,5),fill=INK)
 for yy,row in enumerate(rows):
  for xx,b in enumerate(row):
   if b=='1':d.point((x+xx,yy),fill='#e6e1c8')
save(im,'panels.png')
im=Image.new('RGBA',(128,64),STONE);d=ImageDraw.Draw(im);REG['nameplate']={'rect_art':[0,0,128,18]};d.rectangle((0,0,127,17),outline=INK);d.line((1,1,126,1),fill='#f1e8d0');text(d,22,6,'TOWN STATE BANK');save(im,'signs.png')
im=Image.new('RGBA',(32,32),'#eedcb2');d=ImageDraw.Draw(im);d.rectangle((0,0,31,31),outline=INK);d.line((1,1,30,1),fill='#fff1ca');save(im,'glow.png');(R/'art-regions.json').write_text(json.dumps(REG,indent=2)+'\n')
