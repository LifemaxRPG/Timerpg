"""Union Depot: shared Timerpg swatches plus original architectural pixel art."""
from pathlib import Path
from PIL import Image,ImageDraw
import json,math
from pixel_font import FONT
R=Path(__file__).resolve().parent;REPO=R.parents[1];REG={}
INK='#3a3a50';CREAM='#eee9df';TEAL='#46756a';BRASS='#b79a60'
def save(im,name):im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(R/'textures'/name)
def text(d,xy,s,c):
 x,y=xy
 for ch in s:
  if ch==' ':x+=3;continue
  for j,row in enumerate(FONT.get(ch,['00000']*7)):
   for i,b in enumerate(row):
    if b=='1':d.point((x+i,y+j),fill=c)
  x+=6
atlas=Image.open(REPO/'kotlin-street/tools/swatches/atlas/atlas.png').convert('RGBA');data=json.loads((REPO/'kotlin-street/tools/swatches/atlas/atlas.json').read_text());sources=[]
for name in ['brick_red','block_courses','roof_slate','plank_vertical','pavement','asphalt_worn']:
 e=data[name];im=atlas.crop((e['x'],e['y'],e['x']+e['w'],e['y']+e['h']));im.save(R/'source-materials'/(name+'.png'));w,h=im.size;art=im.resize((w//2,h//2),Image.Resampling.NEAREST)
 if name=='block_courses':
  vals=sorted({art.getpixel((x,y)) for y in range(art.height) for x in range(art.width)},key=lambda c:sum(c[:3]));pal=['#77747c','#a6a29d','#c7c0b4','#dad4cb','#eee9df']
  lookup={c:pal[round(i*4/max(1,len(vals)-1))] for i,c in enumerate(vals)};out=Image.new('RGBA',art.size);d=ImageDraw.Draw(out)
  for y in range(art.height):
   for x in range(art.width):d.point((x,y),fill=lookup[art.getpixel((x,y))])
  art=out
 save(art,name+'.png');sources.append({'id':name,'atlas_rect':[e['x'],e['y'],e['w'],e['h']],'source':e['entry'],'tint':'warm stone palette' if name=='block_courses' else None})
(R/'source-materials/provenance.json').write_text(json.dumps({'licence':'LimeZu: Timerpg game use only; credit required','swatches':sources},indent=2)+'\n')
im=Image.new('RGBA',(64,64),TEAL);d=ImageDraw.Draw(im);d.rectangle((0,0,63,63),outline=INK);d.line((1,1,62,1),fill='#76998a')
for y in (30,62):d.line((0,y,63,y),fill='#3d6f63')
for x,y in [(5,12),(44,27),(29,50)]:d.point((x,y),fill='#a6a29d')
save(im,'teal.png')
im=Image.new('RGBA',(256,128),INK);d=ImageDraw.Draw(im)
for i,(key,color) in enumerate([('metal','#7d7f99'),('rubber',INK),('brass',BRASS),('foliage','#3d6f63')]):
 x=(i%2)*64;y=(i//2)*64;d.rectangle((x,y,x+63,y+63),fill=color);REG[key]={'rect_art':[x,y,64,64]}
save(im,'utility.png')
im=Image.new('RGBA',(32,32),CREAM);d=ImageDraw.Draw(im)
for y in range(32):
 for x in range(32):
  color='#d0c6b4' if (x//4+y//4)%2 else '#eee9df'
  if (x*19+y*7)%67==0:color='#aaa29b'
  d.point((x,y),fill=color)
save(im,'terrazzo.png')
im=Image.new('RGBA',(256,128),(0,0,0,0));d=ImageDraw.Draw(im)
def pane(key,rect,door=False):
 x,y,w,h=rect;REG[key]={'rect_art':rect};d.rectangle((x,y,x+w-1,y+h-1),fill=(75,104,115,95),outline=INK);d.line((x+1,y+1,x+w-2,y+1),fill=CREAM)
 for yy in range(y+10,y+h-1,10):d.line((x,yy,x+w-1,yy),fill='#716a60')
 for xx in range(x+8,x+w-1,8):d.line((xx,y,xx,y+h-1),fill='#716a60')
 if door:
  d.rectangle((x,y+h-12,x+w-1,y+h-1),fill=TEAL,outline=INK);d.line((x+w//2,y,x+w//2,y+h-1),fill=INK)
  for dx in (-2,2):d.point((x+w//2+dx,y+h//2+3),fill=BRASS)
pane('window32',[0,0,32,32]);pane('window40',[34,0,32,40]);pane('main_door',[68,0,32,40],True);pane('platform_door',[102,0,24,40],True);pane('staff_door',[128,0,16,36],True);pane('ticket_window',[146,0,48,24]);pane('bay_side',[196,0,16,28]);pane('bay_front',[214,0,40,28]);pane('arch',[0,44,40,16]);pane('window24',[42,44,32,24])
save(im,'glass.png')
# Freight doors: standalone repeating timber supports the large blank faces; trim is drawn in this inset image.
im=Image.new('RGBA',(64,64),TEAL);d=ImageDraw.Draw(im);REG['freight_door']={'rect_art':[0,0,48,44]};d.rectangle((0,0,47,43),outline=INK)
for x in range(4,48,4):d.line((x,1,x,42),fill='#235d68')
d.line((24,0,24,43),fill=INK);d.line((2,2,22,40),fill=BRASS);d.line((26,40,45,2),fill=BRASS);d.line((20,20,20,25),fill=CREAM);d.line((28,20,28,25),fill=CREAM)
# Pack this door into the glass atlas as an opaque architectural panel (no extra material).
utility=Image.open(R/'textures/utility.png').resize((256,128),Image.Resampling.NEAREST);utility.paste(im.crop((0,0,48,44)),(128,0));REG['freight_door']['rect_art']=[128,0,48,44];save(utility,'utility.png')
im=Image.new('RGBA',(128,128),TEAL);d=ImageDraw.Draw(im)
def label(key,rect,s):
 x,y,w,h=rect;REG[key]={'rect_art':rect};d.rectangle((x,y,x+w-1,y+h-1),fill=TEAL,outline=INK);text(d,(x+2,y+2),s,CREAM)
label('tickets',[0,0,48,12],'TICKETS');label('baggage',[50,0,48,12],'BAGGAGE');label('platform',[0,14,64,12],'PLATFORM 1')
REG['lamp']={'rect_art':[0,28,16,4]};d.rectangle((0,28,15,31),fill='#ffe6ab',outline=INK)
REG['clock']={'rect_art':[68,14,32,32]};d.ellipse((68,14,99,45),fill=INK);d.ellipse((69,15,98,44),fill=BRASS);d.ellipse((71,17,96,42),fill=CREAM)
for j in range(12):
 a=math.tau*j/12;x=round(83.5+11*math.sin(a));y=round(29.5-11*math.cos(a));d.point((x,y),fill=INK)
# Original small 3x5 digits for cardinal numerals, to keep the dial on the physical art grid.
small={'1':['010','110','010','010','111'],'2':['110','001','010','100','111'],'3':['110','001','010','001','110'],'6':['011','100','110','101','010'],'9':['010','101','011','001','110']}
def numeral(s,x,y):
 for c in s:
  for yy,row in enumerate(small[c]):
   for xx,b in enumerate(row):
    if b=='1':d.point((x+xx,y+yy),fill=INK)
  x+=4
numeral('12',80,18);numeral('3',91,27);numeral('6',82,36);numeral('9',73,27)
REG['timetable']={'rect_art':[0,48,32,24]};d.rectangle((0,48,31,71),fill='#dad4cb',outline=INK);text(d,(1,50),'TRAINS',INK)
for y in (59,64,69):d.line((2,y,29,y),fill='#a6a29d')
save(im,'signs.png')
im=Image.new('RGBA',(64,16),TEAL);d=ImageDraw.Draw(im);d.rectangle((0,0,63,11),outline=INK);d.line((1,1,62,1),fill='#76998a');text(d,(1,3),'UNION DEPOT',CREAM);REG['station_name']={'rect_art':[0,0,64,12]};save(im,'station-name.png')
(R/'art-regions.json').write_text(json.dumps(REG,indent=2)+'\n')
