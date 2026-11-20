"""Original corner-drugstore pixel art and state-specific sign/mask pairs."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageColor
from pixel_font import FONT
import json
R=Path(__file__).resolve().parent;REPO=R.parents[1];REG={};INK='#344b4a';CREAM='#e4ddc5';TEAL='#587a70';CORAL='#a66f63'
def save(im,n):im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(R/'textures'/n)
def text(d,x,y,s,c):
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
  vals=sorted(set(im.getdata()),key=lambda c:(sum(c[:3]),c));pal=['#c4c7b4','#cdd0bd','#d7d8c4','#e2dfc9','#eae4cd'];lut={v:ImageColor.getcolor(pal[round(i*4/max(1,len(vals)-1))],'RGBA') for i,v in enumerate(vals)};o=Image.new('RGBA',im.size);o.putdata([lut[v] for v in im.getdata()]);im=o
 save(im,name+'.png');sources.append({'id':key,'atlas_rect':[e['x'],e['y'],e['w'],e['h']],'source':e['entry'],'tint':'cream sage' if name=='interior' else None})
(R/'source-materials/provenance.json').write_text(json.dumps({'licence':'LimeZu shared swatches: Timerpg-only; retain original credit. Supplemental art and all geometry original.','swatches':sources},indent=2)+'\n')
for name,cols in [('stone',['#a9ab9c',CREAM,'#f2ead3','#c7c6b0']),('steel',['#293f42','#647d73','#a3af98','#455f59']),('enamel',['#aeb8a5','#e6dfca','#f5edd6','#c9cfb7']),('floor',['#5c776f','#d8d2bd','#e9e1ca','#9caa96'])]:
 im=Image.new('RGBA',(64,64),cols[1]);d=ImageDraw.Draw(im)
 for y in range(64):
  for x in range(64):
   if name=='floor':d.point((x,y),fill=cols[0] if (x//8+y//8)%2 else cols[1])
   if name=='enamel' and (x%8==0 or y%8==0):d.point((x,y),fill=cols[3])
   elif (x*17+y*23)%181==0:d.point((x,y),fill=cols[3])
   elif (x*19+y*13)%227==0:d.point((x,y),fill=cols[2])
 save(im,name+'.png')
im=Image.new('RGBA',(256,128),(111,151,143,40));mask=Image.new('RGBA',im.size,(0,0,0,255));d=ImageDraw.Draw(im);md=ImageDraw.Draw(mask)
for key,rect in [('front_wide',[0,0,52,32]),('front_small',[56,0,36,32]),('side_display',[0,40,64,32]),('upper',[96,0,20,28]),('service',[120,0,24,20]),('corner_sidelight',[148,0,9,28]),('side_small',[164,0,24,28])]:
 x,y,w,h=rect;REG[key]={'rect_art':rect};d.rectangle((x,y,x+w-1,y+h-1),outline=INK);d.line((x+1,y+1,x+w-2,y+1),fill=CREAM);md.rectangle((x+2,y+2,x+w-3,y+h-3),fill='#cbd1b4')
 if key!='corner_sidelight':
  for xx in range(x+w//2,x+w-1,w//2):d.line((xx,y+1,xx,y+h-2),fill=INK);md.line((xx,y+1,xx,y+h-2),fill='black')
 if key=='upper':d.line((x+1,y+12,x+w-2,y+12),fill=INK);md.line((x+1,y+12,x+w-2,y+12),fill='black')
save(im,'glass.png');save(mask,'pane-emission.png')
im=Image.new('RGBA',(256,256),TEAL);d=ImageDraw.Draw(im)
for key,rect in [('cabinet',[0,0,128,128]),('board',[128,0,64,64]),('seat',[128,64,32,32]),('basin',[164,64,6,8]),('louver',[176,64,24,16])]:
 x,y,w,h=rect;REG[key]={'rect_art':rect}
 if key=='cabinet':
  d.rectangle((x,y,x+w-1,y+h-1),fill=TEAL)
  for yy in range(y,y+h,16):
   for xx in range(x,x+w,16):d.rectangle((xx+1,yy+1,xx+14,yy+14),outline=INK);d.line((xx+2,yy+2,xx+13,yy+2),fill='#a3b49c');d.line((xx+6,yy+5,xx+9,yy+5),fill=CREAM)
 if key=='board':
  d.rectangle((x,y,x+w-1,y+h-1),fill='#968268')
  for yy in range(y,y+h,4):d.line((x,yy,x+w-1,yy),fill='#635f50');d.line((x,yy+1,x+w-1,yy+1),fill='#b09c7b')
 if key=='seat':
  d.rectangle((x,y,x+w-1,y+h-1),fill=CORAL)
  for yy in range(y,y+h):
   for xx in range(x,x+w):
    if (xx*13+yy*19)%109==0:d.point((xx,yy),fill='#c28d75')
 if key=='basin':d.rectangle((x,y,x+w-1,y+h-1),fill='#93aaa0',outline=INK);d.rectangle((x+1,y+1,x+w-2,y+h-2),fill='#506f69')
 if key=='louver':
  d.rectangle((x,y,x+w-1,y+h-1),fill=TEAL,outline=INK)
  for yy in range(y+3,y+h-2,3):d.line((x+2,yy,x+w-3,yy),fill=INK);d.line((x+2,yy+1,x+w-3,yy+1),fill='#b4c1a9')
save(im,'panels.png')
for state in ['family','retail','closed','cafe']:
 im=Image.new('RGBA',(128,128),TEAL);em=Image.new('RGBA',im.size,(0,0,0,255));d=ImageDraw.Draw(im);md=ImageDraw.Draw(em);REG['nameplate']={'rect_art':[0,0,100,16]};REG['blade']={'rect_art':[0,24,18,50]};d.rectangle((0,0,99,15),fill=TEAL,outline=INK);d.line((1,1,98,1),fill=CREAM)
 name='CORNER CAFE' if state=='cafe' else 'CORNER DRUGSTORE';xx=19 if state=='cafe' else 4;text(d,xx,5,name,CREAM)
 if state!='closed':text(md,xx,5,name,'#ded3ab')
 d.rectangle((0,24,17,73),fill=CREAM,outline=INK);word='CAFE' if state=='cafe' else 'DRUGS';start=31 if state=='cafe' else 27
 for i,c in enumerate(word):
  text(d,6,start+i*9,c,TEAL if state=='cafe' else CORAL)
  if state!='closed':text(md,6,start+i*9,c,'#d7b68a')
 save(im,'signs-'+state+'.png');save(em,'sign-emission-'+state+'.png')
im=Image.new('RGBA',(32,32),'#efddb0');d=ImageDraw.Draw(im);d.rectangle((0,0,31,31),outline=INK);d.line((1,1,30,1),fill='#fff1c9');save(im,'glow.png');(R/'art-regions.json').write_text(json.dumps(REG,indent=2)+'\n')
