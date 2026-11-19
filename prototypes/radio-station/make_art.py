"""Deterministic original radio-station artwork with retained shared swatch provenance."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageColor
from pixel_font import FONT
import json
R=Path(__file__).resolve().parent;REPO=R.parents[1];REG={};INK='#354b4c';CREAM='#e5dfc9';TEAL='#5d7b73'
def save(im,n):im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(R/'textures'/n)
def text(d,x,y,s,c=CREAM):
 for ch in s:
  if ch==' ':x+=3;continue
  for j,row in enumerate(FONT[ch]):
   for i,b in enumerate(row):
    if b=='1':d.point((x+i,y+j),fill=c)
  x+=6
j=json.loads((REPO/'kotlin-street/tools/swatches/atlas/atlas.json').read_text());atlas=Image.open(REPO/'kotlin-street/tools/swatches/atlas/atlas.png').convert('RGBA');sources=[]
for key,name in [('brick_red','brick'),('wall_stucco_red','interior'),('roof_membrane_grey','roof'),('pavement','paving'),('asphalt_worn','asphalt')]:
 e=j[key];im=atlas.crop((e['x'],e['y'],e['x']+e['w'],e['y']+e['h']));im.save(R/'source-materials'/(key+'.png'));im=im.resize((im.width//2,im.height//2),Image.Resampling.NEAREST)
 if name=='interior':
  vals=sorted(set(im.getdata()),key=lambda c:(sum(c[:3]),c));pal=['#c2c8b9','#c9cfc0','#d3d7c7','#dee0cd','#e6e5d1'];lut={v:ImageColor.getcolor(pal[round(i*4/max(1,len(vals)-1))],'RGBA') for i,v in enumerate(vals)};out=Image.new('RGBA',im.size);out.putdata([lut[v] for v in im.getdata()]);im=out
 save(im,name+'.png');sources.append({'id':key,'atlas_rect':[e['x'],e['y'],e['w'],e['h']],'source':e['entry'],'tint':'sage cream' if name=='interior' else None})
(R/'source-materials/provenance.json').write_text(json.dumps({'licence':'LimeZu shared swatches are Timerpg-only; retain original credit. All geometry and supplemental art original.','swatches':sources},indent=2)+'\n')
for name,cols in [('trim',['#aaa998',CREAM,'#f3ebd6','#c8c8b2']),('steel',['#2f4547',TEAL,'#8b9b8c','#4e6d66']),('floor',['#555e5c','#788079','#9a9c8b','#646f69'])]:
 im=Image.new('RGBA',(64,64),cols[1]);d=ImageDraw.Draw(im)
 for y in range(64):
  for x in range(64):
   if name=='floor' and (x%12==0 or y%12==0):d.point((x,y),fill=cols[0])
   elif (x*19+y*23)%157==0:d.point((x,y),fill=cols[3])
   elif (x*17+y*13)%211==0:d.point((x,y),fill=cols[2])
 save(im,name+'.png')
# Real alpha glass: clear control-room pane; exterior panes retain original pixel frames.
im=Image.new('RGBA',(256,128),(115,153,145,65));mask=Image.new('RGBA',im.size,(0,0,0,255));d=ImageDraw.Draw(im);md=ImageDraw.Draw(mask)
for key,rect in [('front_window',[0,0,12,26]),('side_window',[16,0,20,26]),('high_window',[40,0,24,12]),('control_window',[68,0,32,22]),('studio_window',[104,0,16,28])]:
 x,y,w,h=rect;REG[key]={'rect_art':rect}
 if key=='control_window':d.rectangle((x,y,x+w-1,y+h-1),fill=(115,153,145,28))
 d.rectangle((x,y,x+w-1,y+h-1),outline=INK);d.line((x+1,y+1,x+w-2,y+1),fill=CREAM);md.rectangle((x+2,y+2,x+w-3,y+h-3),fill='black' if key=='control_window' else '#d8d3b5')
 if key!='control_window':d.line((x+w//2,y+1,x+w//2,y+h-2),fill=INK);md.line((x+w//2,y+1,x+w//2,y+h-2),fill='black')
# 64x64 seamless non-atlas pane region for angled lobby glass; no drawn mullions to stretch.
REG['angled_glass']={'rect_art':[128,0,64,64]};d.rectangle((128,0,191,63),fill=(116,154,146,70));md.rectangle((128,0,191,63),fill='#cbd0b4')
for y in range(64):
 for x in range(128,192):
  if (x*13+y*19)%181==0:d.point((x,y),fill=(140,170,157,80))
save(im,'glass.png');save(mask,'glass-emission.png')
im=Image.new('RGBA',(256,128),TEAL);d=ImageDraw.Draw(im)
for key,rect in [('main_door',[0,0,16,32]),('service_door',[20,0,16,32]),('room_door',[40,0,16,32]),('louver',[60,0,24,16])]:
 x,y,w,h=rect;REG[key]={'rect_art':rect};d.rectangle((x,y,x+w-1,y+h-1),fill=TEAL,outline=INK);d.line((x+1,y+1,x+w-2,y+1),fill=CREAM)
 if key=='louver':
  for yy in range(y+3,y+h-2,3):d.line((x+2,yy,x+w-3,yy),fill=INK);d.line((x+2,yy+1,x+w-3,yy+1),fill='#b4c1aa')
 else:
  d.rectangle((x+3,y+4,x+w-4,y+19),outline='#b7c3aa');d.line((x+2,y+23,x+w-3,y+23),fill=INK);d.point((x+w-3,y+23),fill='#cfb68a')
REG['tower_red']={'rect_art':[128,0,64,64]};d.rectangle((128,0,191,63),fill='#ac7065')
for y in range(64):
 for x in range(128,192):
  if (x*17+y*19)%163==0:d.point((x,y),fill='#8b605a')
  elif (x+y*11)%229==0:d.point((x,y),fill='#c38b77')
save(im,'panels.png')
im=Image.new('RGBA',(256,128),INK);d=ImageDraw.Draw(im)
for key,rect in [('nameplate',[0,0,94,18]),('blade',[0,24,20,48]),('on_air',[104,0,40,12]),('emblem',[24,24,24,24])]:
 x,y,w,h=rect;REG[key]={'rect_art':rect};d.rectangle((x,y,x+w-1,y+h-1),fill=INK,outline=CREAM)
 if key=='nameplate':text(d,x+12,y+5,'VALLEY RADIO')
 if key=='blade':
  for i,c in enumerate('RADIO'):text(d,x+7,y+3+i*9,c)
 if key=='on_air':text(d,x+3,y+3,'ON AIR','#d9ab87')
 if key=='emblem':
  d.rectangle((x+10,y+5,x+13,y+14),fill=CREAM);d.arc((x+7,y+7,x+16,y+17),0,180,fill=CREAM);d.line((x+11,y+17,x+11,y+20),fill=CREAM);d.line((x+8,y+20,x+15,y+20),fill=CREAM)
  for xx in [4,19]:d.line((x+xx,y+7,x+xx,y+14),fill='#b6bfa5')
save(im,'signs.png')
im=Image.new('RGBA',(16,16),'#e9d6a5');d=ImageDraw.Draw(im);d.rectangle((0,0,15,15),outline=INK);d.line((1,1,14,1),fill='#fff0c7');d.line((1,14,14,14),fill='#b7a384');save(im,'glow.png')
(R/'art-regions.json').write_text(json.dumps(REG,indent=2)+'\n')
