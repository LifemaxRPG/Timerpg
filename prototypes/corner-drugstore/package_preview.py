"""Package four complete state alternatives and eleven actual Blender views."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json,zipfile
R=Path(__file__).resolve().parent
states=[json.loads((R/('state-'+s+'.json')).read_text()) for s in ['family','retail','closed','cafe']]
info=dict(states[0]);info['states']=states;info['default_state']='family';info['state_selection']='Choose one complete visual state from actual history. The cafe is conditional reuse, not a forced restoration event.';(R/'model-info.json').write_text(json.dumps(info,indent=2)+'\n')
SHOTS=[('01-front-day','Chamfered corner, display fronts and projecting sign'),('02-blue-hour','Pane-only glass and sign lighting'),('03-rear','Service side and outside upper-office staircase'),('04-above','Complete roof, parapet and rear stair structure'),('05-person-height','Diagonal open entrance from street level'),('06-fountain','Family fountain: counter, fixed stools and dispensers')]
EXTRA=[('07-retail','1976–2005: fountain removed and ceiling lowered'),('08-closed','2006 onward: closed visual alternative'),('09-cafe','2006 onward: cafe reusing the original counter'),('10-upper-offices','Unfurnished upper office shells'),('11-cafe-exterior','Cafe storefront: reused shell and repainted signs')]
f='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf';title=ImageFont.truetype(f,34);label=ImageFont.truetype(f,18);small=ImageFont.truetype(f,16)
s=Image.new('RGB',(1304,1680),'#2c383d');d=ImageDraw.Draw(s);d.text((24,20),'CORNER DRUGSTORE & SODA FOUNTAIN',font=title,fill='#eee4ca');d.text((24,67),'Four separate period states  /  Common 1920s–30s brick corner shell',font=small,fill='#bdcdc3')
for i,(n,t) in enumerate(SHOTS):
 x=24+i%2*640;y=112+i//2*500;im=Image.open(R/'renders'/(n+'-clean.png')).convert('RGB');assert im.size==(1280,960);s.paste(im.resize((616,462),Image.Resampling.LANCZOS),(x,y));d.text((x,y+468),t,font=label,fill='#eee4ca')
d.text((24,1620),'Actual Blender desktop previews. Fixed fountain fixtures; the game supplies loose stock and furnishings.',font=small,fill='#bdcdc3');d.text((24,1645),'Not Filament/S25 captures. State choice, navigation, glTF collision parsing and lighting need integration.',font=small,fill='#bdcdc3');s.save(R/'drugstore-review.jpg',quality=95)
c=Image.new('RGB',(1600,1380),'#2c383d');dd=ImageDraw.Draw(c);dd.text((24,18),'CORNER DRUGSTORE — FOUR VISUAL STATES',font=title,fill='#eee4ca')
comparisons=[('06-fountain','1935–1975: family store with fountain'),('07-retail','1976–2005: fountain removed, dropped ceiling'),('08-closed','2006 onward: closed alternative'),('11-cafe-exterior','2006 onward: cafe reuse alternative')]
for i,(n,t) in enumerate(comparisons):
 x=12+i%2*800;y=80+i//2*630;im=Image.open(R/'renders'/(n+'-clean.png')).convert('RGB');c.paste(im.resize((776,582),Image.Resampling.LANCZOS),(x,y));dd.text((x+12,y+592),t,font=label,fill='#eee4ca')
dd.text((24,1350),'Actual Blender views. Alternative outcomes; game facts control closure, counter recovery and reuse.',font=small,fill='#bdcdc3');c.save(R/'drugstore-states.jpg',quality=95)
cards=''.join(f'<figure><a href="renders/{n}-clean.png"><img src="renders/{n}-clean.png" alt="{t}"></a><figcaption>{t}</figcaption></figure>' for n,t in SHOTS+EXTRA)
links=''.join(f'<a href="corner-drugstore-{s}.glb" download>{s.title()} GLB</a><a href="corner-drugstore-{s}.blend" download>{s.title()} source</a>' for s in ['family','retail','closed','cafe'])
(R/'preview.html').write_text('''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Corner Drugstore: four states</title><style>body{margin:0;background:#2c383d;color:#eee4ca;font:16px/1.6 system-ui,sans-serif}main{max-width:1500px;padding:32px;margin:auto}h1{font-size:clamp(32px,5vw,56px);margin:0}p{max-width:1000px;color:#bdcdc3}a{color:#efb39d}nav{display:flex;gap:20px;flex-wrap:wrap;margin:24px 0}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(360px,1fr));gap:24px}figure{margin:0}img{width:100%;display:block}figcaption{padding:8px 0}</style><main><h1>Corner Drugstore &amp; Soda Fountain</h1><p>A complete brick corner shell with diagonal entrance, projecting trade sign, upper offices and a rear exterior stair. Four GLBs provide the family fountain, later retail conversion, closed shop and cafe reuse alternatives. State choice and recovery of the old counter must follow actual simulation facts. These are Blender desktop previews; game navigation, lighting, collision parsing and furnishings remain integration work.</p><nav>'''+links+'<a href="README.md">Rebuild/handoff</a><a href="validation.json">Validation</a></nav><div class="grid">'+cards+'</div></main></html>\n')
files=[p for p in R.iterdir() if p.is_file() and p.suffix in ['.py','.json','.md','.txt','.glb','.blend','.jpg','.html']]
for folder in ['textures','renders','source-materials']:files.extend(p for p in (R/folder).glob('*') if p.is_file())
zpath=R/'corner-drugstore-model-and-previews.zip'
with zipfile.ZipFile(zpath,'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(files):z.write(p,'corner-drugstore/'+str(p.relative_to(R)))
with zipfile.ZipFile(zpath) as z:assert z.testzip() is None
print(json.dumps({'archive':str(zpath),'files':len(files),'views':11,'bytes':zpath.stat().st_size},indent=2))
