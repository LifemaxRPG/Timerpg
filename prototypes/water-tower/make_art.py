"""Original steel details in Timerpg's palette; shared pavement swatch for the footings."""
from pathlib import Path
from PIL import Image,ImageDraw
import json,random
R=Path(__file__).resolve().parent;repo=R.parents[1]
def save(im,n):im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(R/'textures'/n)
def metal(n,colors,course=16):
 im=Image.new('RGBA',(64,64),colors[1]);d=ImageDraw.Draw(im)
 for y in range(0,64,course):
  d.line((0,y,63,y),fill=colors[0]);d.line((0,y+1,63,y+1),fill=colors[2])
  for x in range(4,64,12):d.point((x,y+3),fill=colors[0]);d.point((x,y+2),fill=colors[2])
 for y in range(64):
  for x in range(64):
   if (x*17+y*31)%211==0:d.point((x,y),fill=colors[3])
 save(im,n)
metal('tank-steel.png',['#616e7b','#a7b7ba','#d2d7cf','#889b9e'],24)
metal('frame-steel.png',['#323d50','#566978','#85999c','#b7ab93'],16)
metal('roof-steel.png',['#69444a','#a45b56','#c48471','#805653'],16)
metal('walk-steel.png',['#3a4655','#788587','#a8b4ac','#5e6d75'],8)
# Exact blank 64 x 20 art-pixel sign region, with a thin outline and top highlight.
im=Image.new('RGBA',(64,32),'#e5dfc9');d=ImageDraw.Draw(im);d.rectangle((0,0,63,19),outline='#3a4655');d.line((1,1,62,1),fill='#fff0d5');d.line((1,18,62,18),fill='#b8b49f')
for x,y in [(3,3),(60,3),(3,16),(60,16)]:d.point((x,y),fill='#788587')
save(im,'sign-name.png')
im=Image.new('RGBA',(32,32),'#74756d');d=ImageDraw.Draw(im)
for y in range(32):
 for x in range(32):
  v=(x*23+y*47+x*y)%29
  if v<4:d.point((x,y),fill=['#656961','#96988b','#b0ae9c','#555e59'][v])
save(im,'gravel.png')
im=Image.new('RGBA',(16,16),'#bcb6a5');d=ImageDraw.Draw(im);d.rectangle((0,0,15,15),outline='#72766f');d.line((1,1,14,1),fill='#d4cbb5');d.line((2,12,6,11,8,12),fill='#929789');save(im,'equipment.png')
im=Image.new('RGBA',(8,8),'#dc8070');d=ImageDraw.Draw(im);d.rectangle((0,0,7,7),outline='#57404b');d.rectangle((2,1,5,4),fill='#ffd6aa');save(im,'beacon.png')
j=json.loads((repo/'kotlin-street/tools/swatches/atlas/atlas.json').read_text());a=Image.open(repo/'kotlin-street/tools/swatches/atlas/atlas.png').convert('RGBA');e=j['pavement'];im=a.crop((e['x'],e['y'],e['x']+e['w'],e['y']+e['h']));im.save(R/'source-materials/pavement.png');im.resize((im.width//2,im.height//2),Image.Resampling.NEAREST).resize(im.size,Image.Resampling.NEAREST).save(R/'textures/concrete.png')
(R/'source-materials/provenance.json').write_text(json.dumps({'licence':'LimeZu: Timerpg game use only; retain original credit. Original geometry and supplemental steel/sign/gravel artwork.','swatches':[e]},indent=2)+'\n')
