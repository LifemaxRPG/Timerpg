"""Package one actual-model review sheet and local HTML gallery, no duplicate frames."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json,html
R=Path(__file__).resolve().parent;states=['original','closed','restored'];views=['day','night','rear','above','person','interior'];labels={'original':'1950s ORIGINAL CARHOP','closed':'FADED / BOARDED UP','restored':'RESTORED WALK-UP DINER'}
infos=[json.loads((R/('model-info-'+s+'.json')).read_text()) for s in states]
(R/'model-info.json').write_text(json.dumps({'name':"Milo's Drive-In",'states':infos,'state_selection':'Match actual town business facts. Closed and restored are alternative outcomes, not unconditional date changes.','shared_shell_and_doors':True,'review':'carhop-review.jpg','runtime':'Game integration pending; Blender desktop previews only.'},indent=2)+'\n')
W,H=1536,2140;out=Image.new('RGB',(W,H),'#2c383d');d=ImageDraw.Draw(out);font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',21);small=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',16)
d.text((18,12),"MILO'S DRIVE-IN — THREE LIVES OF ONE ROADSIDE STAND",fill='#e7dec2',font=font)
d.text((18,43),'Original geometry and pixel art / same footprint and entrance anchors / eight menu-speaker bays',fill='#bfc9c1',font=small)
for i,s in enumerate(states):
 d.text((i*512+12,80),labels[s],fill='#eadfc1',font=font)
 for j,v in enumerate(views):
  p=R/'renders'/(s+'-'+v+'.png');im=Image.open(p).convert('RGB');assert im.size==(1024,640),(p,im.size)
  raw=Path('/tmp/carhop-raw')/p.name
  if raw.exists():assert p.stat().st_mtime>=raw.stat().st_mtime,'Stale processed preview'
  im=im.resize((500,312),Image.Resampling.LANCZOS);y=116+j*330;out.paste(im,(i*512+6,y));d.text((i*512+12,y+312),(v.upper()+' / INSPECTION LIGHT' if s=='closed' and v=='interior' else v.upper()),fill='#c4cec6',font=small)
d.text((18,2105),'Actual Blender desktop renders, not Filament/S25 captures. Engine integration and furnishing remain pending.',fill='#c4cec6',font=small)
out.save(R/'carhop-review.jpg',quality=93)
# Compact three-state comparison for the owner's chat; derived only from the actual final day renders.
im=Image.new('RGB',(1536,742),'#2c383d');d=ImageDraw.Draw(im)
for i,s in enumerate(states):
 d.text((i*512+12,7),labels[s],font=font,fill='#e7dec2');pic=Image.open(R/'renders'/(s+'-day.png')).convert('RGB').resize((500,312),Image.Resampling.LANCZOS);im.paste(pic,(i*512+6,40))
for i,s in enumerate(states):
 d.text((i*512+12,358),'AT PERSON HEIGHT',font=small,fill='#c4cec6');pic=Image.open(R/'renders'/(s+'-person.png')).convert('RGB').resize((500,312),Image.Resampling.LANCZOS);im.paste(pic,(i*512+6,382))
d.text((15,712),'Actual Blender desktop renders — game integration pending',font=small,fill='#c4cec6')
im.save('/workspace/work/carhop-state-comparison.jpg',quality=94)
gallery=['<!doctype html><html lang="en"><meta charset="utf-8"><title>Milo’s Drive-In — model review</title><style>body{background:#2c383d;color:#e7dec2;font:16px system-ui;margin:2rem}section{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}img{width:100%}figure{margin:0}a{color:#b6d8ce}</style><h1>Milo’s Drive-In</h1><p>Three original models. Actual Blender previews, not Filament/S25 captures. Game integration pending.</p><p>Original carhop / faded and boarded / restored with walk-up hatches and four removable picnic tables. The engine selects states from town facts.</p>']
for v in views:
 gallery.append('<h2>'+v.title()+'</h2><section>')
 for s in states:gallery.append('<figure><img src="renders/'+s+'-'+v+'.png" alt="'+html.escape(s+' '+v)+'"><figcaption>'+labels[s]+'</figcaption></figure>')
 gallery.append('</section>')
(R/'preview.html').write_text('\n'.join(gallery)+'\n');print('Packaged 3 states / 18 actual views / one review sheet')
