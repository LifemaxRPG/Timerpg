"""Original carhop pixel art, deterministic wear and shared Timerpg swatch provenance."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageColor
import json,random
from pixel_font import FONT
FONT["'"]=['00100','00100','00000','00000','00000','00000','00000']
R=Path(__file__).resolve().parent;REPO=R.parents[1];REG={};PROV=[]
A=Image.open(REPO/'kotlin-street/tools/swatches/atlas/atlas.png').convert('RGBA');J=json.loads((REPO/'kotlin-street/tools/swatches/atlas/atlas.json').read_text())
def save(im,n): im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(R/'textures'/n)
def cut(key):
 e=J[key];im=A.crop((e['x'],e['y'],e['x']+e['w'],e['y']+e['h']));PROV.append({'id':key,'rect':[e['x'],e['y'],e['w'],e['h']],'entry':e['entry']});return im.resize((im.width//2,im.height//2),Image.Resampling.NEAREST)
def recolour(im,pal):
 pixels=list(im.get_flattened_data() if hasattr(im,'get_flattened_data') else im.getdata());colours=sorted(set(pixels),key=lambda c:(sum(c[:3]),c));lut={v:ImageColor.getcolor(pal[round(i*(len(pal)-1)/max(1,len(colours)-1))],'RGBA') for i,v in enumerate(colours)};out=Image.new('RGBA',im.size);out.putdata([lut[v] for v in pixels]);return out
bases={n:cut(k) for n,k in [('wall','block_courses'),('roof','roof_membrane_grey'),('asphalt','asphalt_worn'),('paving','pavement'),('wood','plank_horizontal')]}
for n in ['paving','wood']:save(bases[n],n+'.png')
# Cream, dark metal and floor are intentionally low contrast but never flat fills.
for name,pal in [('cream',['#888e7b','#c3c3a7','#dad5b5','#eee3c2']),('metal',['#293f40','#4d6864','#7b9285','#aab4a0']),]:
 im=Image.new('RGBA',(64,64),pal[1]);d=ImageDraw.Draw(im)
 for y in range(64):
  for x in range(64):
   if name=='floor' and (x%16==0 or y%16==0):d.point((x,y),fill=pal[0])
   elif name=='cream' and y%16==0:d.point((x,y),fill=pal[0])
   elif name=='cream' and y%16==1:d.point((x,y),fill=pal[3])
   elif (x*19+y*37)%179==0:d.point((x,y),fill=pal[2])
   elif (x*13+y*17)%251==0:d.point((x,y),fill=pal[0])
 save(im,name+'.png')
def text(d,label,xy,fill,scale=1):
 x,y=xy
 for ch in label:
  if ch==' ':x+=4*scale;continue
  glyph=FONT.get(ch, FONT['-'])
  for yy,row in enumerate(glyph):
   for xx,v in enumerate(row):
    if v=='1':d.rectangle((x+xx*scale,y+yy*scale,x+(xx+1)*scale-1,y+(yy+1)*scale-1),fill=fill)
  x+=6*scale
for state in ['original','closed','restored']:
 closed=state=='closed';restored=state=='restored'
 palettes={'wall':['#7c7d64','#b0b19a','#c3c3ad','#d1ceb2'] if closed else ['#7b806c','#c0bda0','#d8d2b4','#e9dec0'], 'roof':['#666d60','#8d9484','#a4aa96','#babda5'], 'asphalt':['#424b45','#626a5f','#7a8070','#8c917f'] if closed else ['#323f3d','#4b5751','#606e62','#788273']}
 if restored:
  palettes['wall']=['#89958e','#c4ccc1','#d9ded0','#eee9d5']
  palettes['roof']=['#68797c','#91a1a0','#b7c2b9','#d0d5c5']
 for n in ['wall','roof','asphalt']:
  im=recolour(bases[n],palettes[n]);d=ImageDraw.Draw(im)
  if closed and n in ['roof','asphalt']:
   tile=im;im=Image.new('RGBA',(64,64))
   for yy in range(0,64,tile.height):
    for xx in range(0,64,tile.width):im.paste(tile,(xx,yy))
   d=ImageDraw.Draw(im);rng=random.Random(48)
   for i in range(5):
    x=rng.randrange(48);y=rng.randrange(48)
    if n=='roof':d.line((x,y,x,y+8),fill='#929985');d.line((x+1,y,x+1,y+5),fill='#a0a58f')
    else:d.line([(x,y),(x+2,y+4),(x+1,y+8),(x+4,y+11)],fill='#4f5a50')
  save(im,n+'-'+state+'.png')
 im=Image.new('RGBA',(64,64),'#769386' if closed else '#397c77');d=ImageDraw.Draw(im)
 for y in range(64):
  for x in range(64):
   if y%16==0:d.point((x,y),fill='#3b5750')
   elif y%16==1:d.point((x,y),fill='#a7b6a1' if closed else '#80a899')
   elif (x*13+y*31)%131==0:d.point((x,y),fill='#c5bfa1' if closed else '#416c62')
 if restored:im=recolour(im,['#283d4b','#405c70','#6a8492','#9baeb0'])
 save(im,'teal-'+state+'.png')
 im=Image.new('RGBA',(64,64),'#a58f78' if closed else '#b56b52');d=ImageDraw.Draw(im)
 for y in range(64):
  for x in range(64):
   if y%8==0:d.point((x,y),fill='#765b4a')
   elif y%8==1:d.point((x,y),fill='#ccb493' if closed else '#d79972')
   elif (x*19+y*7)%101==0:d.point((x,y),fill='#bdb197' if closed else '#946149')
 if restored:im=recolour(im,['#715b39','#a98246','#c5a565','#e1c488'])
 save(im,'coral-'+state+'.png')
 # All fixed panels use actual size on art grid; pane mask black on frames.
 im=Image.new('RGBA',(256,128),(0,0,0,0));em=Image.new('RGBA',im.size,'black');d=ImageDraw.Draw(im);e=ImageDraw.Draw(em)
 for key,rect in [('front_window',[0,0,44,22]),('side_window',[48,0,40,22]),('rear_window',[96,0,28,18]),('transom',[0,30,44,6])]:
  x,y,w,h=rect;REG[key]={'rect_art':rect}
  d.rectangle((x,y,x+w-1,y+h-1),fill=(96,133,124,90),outline='#344e49');e.rectangle((x+1,y+1,x+w-2,y+h-2),fill='#aca883')
  for yy in [y+1,y+h-2]:d.line((x+1,yy,x+w-2,yy),fill='#9cae99');e.line((x+1,yy,x+w-2,yy),fill='black')
  d.line((x+w//2,y,x+w//2,y+h-1),fill='#344e49');e.line((x+w//2,y,x+w//2,y+h-1),fill='black')
 save(im,'glass-'+state+'.png');save(em,'glass-emission-'+state+'.png')
 im=Image.new('RGBA',(128,128),'#c3c4a9');d=ImageDraw.Draw(im)
 for key,rect in [('name',[0,0,80,32]),('menu',[0,40,12,20]),('status',[0,70,32,12])]:
  x,y,w,h=rect;REG[key]={'rect_art':rect};d.rectangle((x,y,x+w-1,y+h-1),fill='#748a7b' if closed else '#326d67',outline='#2e4843');d.line((x+1,y+1,x+w-2,y+1),fill='#b9c2a3')
 if restored:
  im=recolour(im,['#263d4b','#3f5a6b','#8a9f9b','#e0c896']);d=ImageDraw.Draw(im)
 text(d,"MILO'S", (22,4),'#d0c2a2' if closed else '#f0dfb7');text(d,'DINER' if restored else 'DRIVE-IN',(26 if restored else 17,16),'#d0c2a2' if closed else '#f0dfb7')
 # The surname-free made-up ordinary name is repaintable. One restrained name panel.
 d.rectangle((2,42,9,48),outline='#eee0b5');d.rectangle((3,44,8,45),fill='#ac7254')
 for y in [52,55]:d.line((2,y,9,y),fill='#d7c9a7')
 text(d,'SHUT' if closed else 'ORDER',(1,73),'#d4c5a5' if closed else '#efdcaf')
 if closed:
  rng=random.Random(491)
  for i in range(220):x=rng.randrange(80);y=rng.randrange(32);d.point((x,y),fill='#82917e')
 save(im,'sign-'+state+'.png')
 # Narrow lit strips and lamps, not an emissive fascia or whole canopy underside.
 im=Image.new('RGBA',(32,32),'#e9d9ad' if not closed else '#8c907b');d=ImageDraw.Draw(im);d.rectangle((0,0,31,31),outline='#5e6859');d.line((1,1,30,1),fill='#f5e7bf');save(im,'glow-'+state+'.png')
(R/'art-regions.json').write_text(json.dumps(REG,indent=2)+'\n');(R/'source-materials/provenance.json').write_text(json.dumps({'licence':'Shared LimeZu swatches retained for Timerpg only. Original geometry and supplemental pixel art.','swatches':PROV},indent=2)+'\n')
print('Carhop: deterministic 2x2 art and per-state weathering saved')

# Store identical artwork once, even when two repaired states share the same paint.
import hashlib
seen={};aliases={}
for p in sorted((R/'textures').glob('*.png'),key=lambda p:(0 if '-original' in p.name else 1 if '-closed' in p.name else 2,p.name)):
 digest=hashlib.sha256(p.read_bytes()).hexdigest()
 if digest in seen:aliases[p.name]=seen[digest];p.unlink()
 else:seen[digest]=p.name;aliases[p.name]=p.name
(R/'texture-map.json').write_text(json.dumps(aliases,indent=2)+'\n')
