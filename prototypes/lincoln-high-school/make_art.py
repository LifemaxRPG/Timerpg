"""Original two-texel school art, with retained Timerpg swatch provenance."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageColor
import json
from pixel_font import FONT
R=Path(__file__).resolve().parent;REPO=R.parents[1];REG={}
def save(im,name):im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(R/'textures'/name)
def text(d,x,y,s,fill):
 for ch in s:
  if ch==' ':x+=3;continue
  for j,row in enumerate(FONT[ch]):
   for i,c in enumerate(row):
    if c=='1':d.point((x+i,y+j),fill=fill)
  x+=6
 return x
atlas=Image.open(REPO/'kotlin-street/tools/swatches/atlas/atlas.png').convert('RGBA');j=json.loads((REPO/'kotlin-street/tools/swatches/atlas/atlas.json').read_text());src=[]
for key,name in [('brick_red','brick'),('wall_stucco_red','plaster'),('roof_membrane_grey','roof')]:
 e=j[key];im=atlas.crop((e['x'],e['y'],e['x']+e['w'],e['y']+e['h']));im=im.resize((im.width//2,im.height//2),Image.Resampling.NEAREST)
 if name=='plaster':
  values=sorted(set(im.getdata()),key=lambda c:(sum(c[:3]),c));pal=['#bcb9a6','#ccc9b2','#d6d2ba','#e2ddc5','#e8e0ca'];lut={v:ImageColor.getcolor(pal[round(i*4/max(1,len(values)-1))],'RGBA') for i,v in enumerate(values)};out=Image.new('RGBA',im.size);out.putdata([lut[v] for v in im.getdata()]);im=out
 save(im,name+'.png');src.append({'id':key,'atlas_rect':[e['x'],e['y'],e['w'],e['h']],'source':e['entry'],'tint':'warm cream' if name=='plaster' else None})
(R/'source-materials/provenance.json').write_text(json.dumps({'licence':'Timerpg-only shared LimeZu swatches; retain original credit. All geometry and supplemental art original.','swatches':src},indent=2)+'\n')
for name,pal in [('stone',['#8f9387','#cccbb3','#e9dfc5','#b0b39e']),('metal',['#293e43','#657972','#a0b09b','#435c5a']),('wood',['#403f36','#8c7857','#b39c72','#6c6149']),('grass',['#5c6b4b','#798665','#909a74','#667959']),('sport',['#a6aaa1','#d4d4c4','#e6e1d0','#bfc4b4'])]:
 im=Image.new('RGBA',(64,64),pal[1]);d=ImageDraw.Draw(im)
 for y in range(64):
  for x in range(64):
   if name=='stone' and (y%8==0 or (x+(y//8%2)*16)%32==0):d.point((x,y),fill=pal[0])
   elif name=='wood' and y%8==0:d.point((x,y),fill=pal[0])
   elif (x*19+y*31)%103==0:d.point((x,y),fill=pal[3])
   elif (x*13+y*7)%197==0:d.point((x,y),fill=pal[2])
 save(im,name+'.png')
# All window mullions and net mesh are texture pixels, not geometry.
im=Image.new('RGBA',(512,256),(100,136,134,42));mask=Image.new('RGBA',im.size,'black');d=ImageDraw.Draw(im);md=ImageDraw.Draw(mask)
for key,rect in [('class',[0,0,40,38]),('small',[48,0,32,22]),('ribbon',[88,0,96,24]),('gym',[192,0,48,50]),('case',[248,0,48,24]),('security_side',[304,0,30,56]),('security_front',[344,0,28,42]),('security_transom',[384,0,96,14]),('press',[0,64,80,20]),('net',[88,64,128,80]),('portable',[224,64,28,18]),('tower',[264,64,32,50]),('ticket_window',[304,64,28,18]),('class_pair',[368,64,18,38]),('small_pair',[344,64,14,22])]:
 REG[key]={'rect_art':rect};x,y,w,h=rect
 if key=='net':
  d.rectangle((x,y,x+w-1,y+h-1),fill=(0,0,0,0))
  for xx in range(x,x+w,8):d.line((xx,y,xx,y+h-1),fill='#56675b')
  for yy in range(y,y+h,8):d.line((x,yy,x+w-1,yy),fill='#56675b')
  continue
 d.rectangle((x,y,x+w-1,y+h-1),outline='#344a48');d.line((x+1,y+1,x+w-2,y+1),fill='#b7c4ad')
 if key!='case':md.rectangle((x+2,y+2,x+w-3,y+h-3),fill='#c7c9aa')
 spacing=16 if key in ['ribbon','press'] else max(8,w//2)
 for xx in range(x+spacing,x+w-1,spacing):d.line((xx,y+1,xx,y+h-2),fill='#344a48');md.line((xx,y+1,xx,y+h-2),fill='black')
 yy=y+(h*2//3 if key in ['class','tower'] else h//2);d.line((x+1,yy,x+w-2,yy),fill='#344a48');md.line((x+1,yy,x+w-2,yy),fill='black')
save(im,'glass.png');save(mask,'glass-emission.png')
im=Image.new('RGBA',(512,128),'#8d9b88');d=ImageDraw.Draw(im)
for key,rect in [('lockers',[0,0,12,30]),('scoreboard',[24,0,96,32]),('date',[128,0,28,10]),('vent',[168,0,24,16]),('backboard',[200,0,24,14]),('ticket_label',[240,0,48,10]),('clock',[304,0,32,32])]:
 REG[key]={'rect_art':rect};x,y,w,h=rect;d.rectangle((x,y,x+w-1,y+h-1),fill='#87947f',outline='#3d514b');d.line((x+1,y+1,x+w-2,y+1),fill='#bec7ad')
 if key=='lockers':
  d.line((x+6,y,x+6,y+h-1),fill='#3d514b')
  for xx in [x+1,x+7]:
   for yy in [y+4,y+6,y+8]:d.line((xx,yy,xx+3,yy),fill='#3d514b')
   d.line((xx+3,y+16,xx+3,y+19),fill='#d1cbb0')
 if key=='date':d.rectangle((x,y,x+w-1,y+h-1),fill='#cccbb3');text(d,x+3,y+2,'1925','#49534a')
 if key=='scoreboard':
  d.rectangle((x+1,y+1,x+w-2,y+h-2),fill='#354d46');text(d,x+27,y+3,'LINCOLN','#d4cfac');text(d,x+8,y+18,'00     00','#d4cfac')
 if key=='ticket_label':
  d.rectangle((x,y,x+w-1,y+h-1),fill='#d2cbb2',outline='#46594f');text(d,x+3,y+1,'TICKETS','#46594f')
 if key=='clock':
  d.ellipse((x+1,y+1,x+30,y+30),fill='#d8d3b9',outline='#46594f')
  for xx,yy in [(16,3),(29,16),(16,29),(3,16)]:d.rectangle((x+xx-1,y+yy-1,x+xx+1,y+yy+1),fill='#46594f')
  d.line((x+16,y+7,x+16,y+16,x+25,y+18),fill='#46594f',width=2)
 if key=='backboard':
  d.rectangle((x,y,x+w-1,y+h-1),fill='#e3ddc4',outline='#53645b');d.rectangle((x+7,y+6,x+16,y+12),outline='#946957')
 if key=='vent':
  for yy in range(y+3,y+h-2,3):d.line((x+2,yy,x+w-3,yy),fill='#3d514b')
save(im,'panels.png')
im=Image.new('RGBA',(256,64),'#cccbb3');d=ImageDraw.Draw(im)
for key,rect,label in [('historic_name',[0,0,128,12],'LINCOLN HIGH SCHOOL'),('modern_name',[0,24,144,18],'LINCOLN CONSOLIDATED')]:
 REG[key]={'rect_art':rect};x,y,w,h=rect;d.rectangle((x,y,x+w-1,y+h-1),outline='#697367');width=sum(3 if c==' ' else 6 for c in label)-1;assert width<w-4
 text(d,x+(w-width)//2,y+(h-7)//2,label,'#414f46')
save(im,'signs.png')
im=Image.new('RGBA',(32,32),'#e8dbb4');d=ImageDraw.Draw(im);d.rectangle((0,0,31,31),outline='#59645a');d.line((1,1,30,1),fill='#f3e7c9');save(im,'glow.png')
(R/'art-regions.json').write_text(json.dumps(REG,indent=2)+'\n')
print('School art generated; 13 PNG images, exact 2x2 texel art blocks')
