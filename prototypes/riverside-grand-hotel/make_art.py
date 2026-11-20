"""Original grand-hotel pixel art and Timerpg-only shared swatch provenance."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageColor
import json,math
from arch_profile import arch
from pixel_font import FONT
R=Path(__file__).resolve().parent;REPO=R.parents[1];REG={};PROV=[]
a=Image.open(REPO/'kotlin-street/tools/swatches/atlas/atlas.png').convert('RGBA');j=json.loads((REPO/'kotlin-street/tools/swatches/atlas/atlas.json').read_text())
def save(im,n):im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(R/'textures'/n)
for key,n in [('brick_red','brick'),('roof_slate','slate'),('plank_horizontal','wood'),('pavement','paving')]:
 e=j[key];im=a.crop((e['x'],e['y'],e['x']+e['w'],e['y']+e['h']));save(im.resize((im.width//2,im.height//2),Image.Resampling.NEAREST),n+'.png');PROV.append({'id':key,'rect':[e['x'],e['y'],e['w'],e['h']],'entry':e['entry']})
for n,pal in [('stone',['#878e82','#c2bca2','#dfd4b8','#eee2c5']),('plaster',['#a8ab98','#d0cbb1','#e1dbc0','#eee5ce']),('copper',['#3e5c54','#638679','#91a491','#b8c1a3']),('metal',['#263b3c','#405955','#6e8578','#9eac94']),('foliage',['#3d5942','#657d53','#899766','#a1ae78'])]:
 im=Image.new('RGBA',(64,64),pal[1]);d=ImageDraw.Draw(im)
 for y in range(64):
  for x in range(64):
   if n=='stone' and (y%12==0 or (x+(y//12%2)*16)%32==0):d.point((x,y),fill=pal[0])
   elif n in ['stone','copper'] and y%(12 if n=='stone' else 16)==1:d.point((x,y),fill=pal[2])
   elif (x*17+y*13)%157==0:d.point((x,y),fill=pal[2])
   elif (x*11+y*29)%233==0:d.point((x,y),fill=pal[0])
 save(im,n+'.png')
# Large atlas: exact-size facade windows plus a dedicated physical-density roof-glass field.
im=Image.new('RGBA',(512,512),(0,0,0,0));em=Image.new('RGBA',im.size,'black');d=ImageDraw.Draw(im);ed=ImageDraw.Draw(em)
for key,rect,round_head in [('guest',[0,0,24,28],False),('ground',[32,0,24,32],True),('lobby',[64,0,40,80],True),('garden',[112,0,24,48],False),('tower',[144,0,20,38],True),('service',[176,0,20,24],False),('garden_fan',[208,0,48,18],False),('garden_centre',[264,0,48,48],False)]:
 x,y,w,h=rect;REG[key]={'rect_art':rect}
 if round_head:
  poly,_,_=arch(w,h,0,False);poly=[(x+w/2+xx,y+h-z) for xx,z in poly];d.polygon(poly,fill=(112,139,128,92));ed.polygon(poly,fill='#aaa384');d.line(poly+[poly[0]],fill='#344b46');ed.line(poly+[poly[0]],fill='black')
 else:d.rectangle((x,y,x+w-1,y+h-1),fill=(112,139,128,80),outline='#344b46');ed.rectangle((x+1,y+1,x+w-2,y+h-2),fill='#aaa384')
 for yy in range(y,y+h):
  for xx in range(x,x+w):
   if im.getpixel((xx,yy))[3]==0:continue
   if xx in [x,x+w-1,x+w//2] or yy in [y+h//2,y+h-1] or (key=='lobby' and (xx-x)%10==0):d.point((xx,yy),fill='#344b46');ed.point((xx,yy),fill='black')
# Pane-only grid for the winter garden roof; unused atlas remains black/non-emissive.
rr=[0,192,256,128];REG['roof_glass']={'rect_art':rr}
for y in range(192,320):
 for x in range(256):
  frame=x%24 in [0,1] or (y-192)%24 in [0,1]
  d.point((x,y),fill='#3f5d54' if frame else (119,153,139,90));ed.point((x,y),fill='black' if frame else '#8c9880')
save(im,'glass.png');save(em,'glass-emission.png')
def text(dr,label,x,y,c,scale=1):
 for ch in label:
  if ch==' ':x+=4*scale;continue
  for yy,row in enumerate(FONT[ch]):
   for xx,v in enumerate(row):
    if v=='1':dr.rectangle((x+xx*scale,y+yy*scale,x+(xx+1)*scale-1,y+(yy+1)*scale-1),fill=c)
  x+=6*scale
im=Image.new('RGBA',(256,128),'#bfbda6');d=ImageDraw.Draw(im);REG['name']={'rect_art':[0,0,120,20]};d.rectangle((0,0,119,19),fill='#36524c',outline='#263d37');d.line((1,1,118,1),fill='#a3b09a');text(d,'RIVERSIDE',33,3,'#eee0bb');text(d,'GRAND HOTEL',29,11,'#ddc68f')
REG['clock']={'rect_art':[0,32,48,48]};d.rectangle((0,32,47,79),fill='#53665a');d.ellipse((1,33,46,78),fill='#dcd7bd',outline='#30463d');d.ellipse((3,35,44,76),outline='#9a9d84')
for i in range(12):
 an=i*math.tau/12;cx=24+18*math.sin(an);cy=56-18*math.cos(an);d.rectangle((int(cx)-1,int(cy)-1,int(cx)+1,int(cy)+1),fill='#3c5145')
d.line((24,56,24,41),fill='#3c5145',width=2);d.line((24,56,34,62),fill='#3c5145',width=2);d.ellipse((22,54,26,58),fill='#6b765e');save(im,'signs.png')
im=Image.new('RGBA',(32,32),'#ecd9ad');d=ImageDraw.Draw(im);d.rectangle((0,0,31,31),outline='#667260');d.line((1,1,30,1),fill='#fff0cc');save(im,'glow.png')
(R/'art-regions.json').write_text(json.dumps(REG,indent=2)+'\n');(R/'source-materials/provenance.json').write_text(json.dumps({'licence':'LimeZu swatches retained for Timerpg only; preserve original credit. Original geometry, clock and supplemental pixel art.','swatches':PROV},indent=2)+'\n');print('Hotel art saved: 13 PNGs, all exact 2x2 art blocks')
