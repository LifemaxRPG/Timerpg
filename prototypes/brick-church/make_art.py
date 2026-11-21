"""Original church pixel art and shared Timerpg swatch provenance, no duplicate cuts."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageColor
import json,math
from arch_profile import arch
R=Path(__file__).resolve().parent;REPO=R.parents[1];REG={}
def save(im,n):im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(R/'textures'/n)
j=json.loads((REPO/'kotlin-street/tools/swatches/atlas/atlas.json').read_text());a=Image.open(REPO/'kotlin-street/tools/swatches/atlas/atlas.png').convert('RGBA');src=[]
for key,n in [('brick_red','brick'),('roof_slate','roof'),('wall_stucco_red','interior'),('asphalt_worn','paving')]:
 e=j[key];im=a.crop((e['x'],e['y'],e['x']+e['w'],e['y']+e['h']));im=im.resize((im.width//2,im.height//2),Image.Resampling.NEAREST)
 if n in ['interior','paving']:
  colors=sorted(set(im.getdata()),key=lambda c:(sum(c[:3]),c));pal=['#b7b7a5','#c8c7b1','#d2cfb9','#dfd9c0','#e8e0ca'] if n=='interior' else ['#afb4a8','#c2c5b7','#d1d2c3','#dddccc','#e9e4d2'];lut={v:ImageColor.getcolor(pal[round(i*4/max(1,len(colors)-1))],'RGBA') for i,v in enumerate(colors)};new=Image.new('RGBA',im.size);new.putdata([lut[v] for v in im.getdata()]);im=new
 save(im,n+'.png');src.append({'id':key,'atlas_rect':[e['x'],e['y'],e['w'],e['h']],'source':e['entry'],'tint':('warm cream' if n=='interior' else 'light paving') if n in ['interior','paving'] else None})
(R/'source-materials/provenance.json').write_text(json.dumps({'licence':'Shared LimeZu swatches are Timerpg-only; retain original credit. Supplemental art and all geometry original.','swatches':src},indent=2)+'\n')
for n,pal in [('siding',['#b7bdaf','#e2e1cc','#f0e8d2','#cad0bd']),('stone',['#969d8f','#d0cbb4','#e9dfc5','#b7bba5']),('metal',['#2e4247','#5c736e','#91a491','#435b58']),('bronze',['#4f5845','#9d8961','#c9b68c','#7c795b']),('wood',['#3d463d','#75654d','#ad9670','#5f5d46']),('grass',['#596b4c','#7d8866','#949e77','#6b7d58'])]:
 im=Image.new('RGBA',(64,64),pal[1]);d=ImageDraw.Draw(im)
 for y in range(64):
  for x in range(64):
   if n=='siding' and y%8==0:d.point((x,y),fill=pal[0])
   elif n=='siding' and y%8==1:d.point((x,y),fill=pal[2])
   elif n=='stone' and (y%10==0 or (x+(y//10%2)*16)%32==0):d.point((x,y),fill=pal[0])
   elif n=='wood' and y%4==0:d.point((x,y),fill=pal[0])
   elif (x*19+y*13)%173==0:d.point((x,y),fill=pal[3])
   elif (x*11+y*17)%223==0:d.point((x,y),fill=pal[2])
 save(im,n+'.png')
im=Image.new('RGBA',(256,128),(0,0,0,0));em=Image.new('RGBA',im.size,'black');d=ImageDraw.Draw(im);md=ImageDraw.Draw(em)
for key,rect,kind in [('lancet',[0,0,32,68],'point'),('front_lancet',[40,0,16,28],'point'),('hall',[64,0,28,28],'rect'),('country',[100,0,22,42],'round'),('country_front',[132,0,20,40],'round'),('vestry',[164,0,20,28],'rect')]:
 x,y,w,h=rect;REG[key]={'rect_art':rect,'profile':kind};poly=None
 if kind!='rect':
  p,_,_=arch(w,h,0,kind=='point');poly=[(x+w/2+xx,y+h-zz) for xx,zz in p]
  d.polygon(poly,fill=(117,141,132,88));md.polygon(poly,fill='#b7b79b')
 else:d.rectangle((x,y,x+w-1,y+h-1),fill=(117,141,132,76));md.rectangle((x,y,x+w-1,y+h-1),fill='#b7b79b')
 for yy in range(y,y+h):
  for xx in range(x,x+w):
   if im.getpixel((xx,yy))[3]==0:continue
   if kind=='point' and ((xx-x)%8==0 or (yy-y)%14==0):d.point((xx,yy),fill='#3a514d');md.point((xx,yy),fill='black')
   elif kind=='point':
    c=['#84998b','#a18d68','#7c8f9b','#9f8175'][((xx-x)//4+(yy-y)//7)%4];rgb=ImageColor.getrgb(c);d.point((xx,yy),fill=(*rgb,108));md.point((xx,yy),fill=tuple(int(v*.8) for v in rgb))
   elif (xx-x)==w//2 or (yy-y) in [h//3,2*h//3]:d.point((xx,yy),fill='#475b54');md.point((xx,yy),fill='black')
 if poly:d.line(poly+[poly[0]],fill='#3a514d',width=1);md.line(poly+[poly[0]],fill='black',width=1)
 else:d.rectangle((x,y,x+w-1,y+h-1),outline='#3a514d');md.rectangle((x,y,x+w-1,y+h-1),outline='black')
save(im,'glass.png');save(em,'glass-emission.png')
# Hand-drawn 3x5 lettering keeps the ordinary roadside sign at a modest physical size.
MINI={'T':['111','010','010','010','010'],'O':['111','101','101','101','111'],'W':['101','101','101','111','101'],'N':['101','111','111','111','101'],'C':['111','100','100','100','111'],'H':['101','101','111','101','101'],'U':['101','101','101','101','111'],'R':['110','101','110','101','101']}
im=Image.new('RGBA',(64,32),'#d5d0b7');d=ImageDraw.Draw(im);d.rectangle((0,0,31,19),outline='#3e524a');d.line((1,1,30,1),fill='#ebe2c8')
for label,yy in [('TOWN',4),('CHURCH',11)]:
 x=(32-(len(label)*4-1))//2
 for ch in label:
  for y,row in enumerate(MINI[ch]):
   for xx,v in enumerate(row):
    if v=='1':d.point((x+xx,yy+y),fill='#3e524a')
  x+=4
REG['name']={'rect_art':[0,0,32,20]};save(im,'signs.png')
im=Image.new('RGBA',(32,32),'#e6d8b1');d=ImageDraw.Draw(im);d.rectangle((0,0,31,31),outline='#5f6656');d.line((1,1,30,1),fill='#f5e7c4');save(im,'glow.png');(R/'art-regions.json').write_text(json.dumps(REG,indent=2)+'\n')
print('Church art: 14 shared PNGs, 13 used/packed per variant')
