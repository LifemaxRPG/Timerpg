"""Package two bank states and nine actual Blender desktop views."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json,zipfile
R=Path(__file__).resolve().parent
states=[json.loads((R/('state-'+s+'.json')).read_text()) for s in ['1920s','drive-through']]
info=dict(states[0]);info['states']=states;info['default_state']='1920s';info['state_selection']='Choose one complete state from actual town history and available side land. The drive-through is eligible from 1965, never a forced event.'
(R/'model-info.json').write_text(json.dumps(info,indent=2)+'\n')
SHOTS=[('01-front-day','Stone portico, arched transom and repaintable frieze'),('02-blue-hour','Warm hall, bronze surrounds and lit lanterns'),('03-rear','Brick service side, open staff entry and finished roof'),('04-above','Complete shell, parapets and supported roof details'),('05-person-height','Open public entrance from pedestrian height'),('06-banking-hall','Four fixed teller wickets, offices and vault beyond')]
EXTRA=[('07-vault','Fixed vault: circular leaf, locking bars and handwheel'),('08-drive-through','1965+ addition: two lanes and pneumatic conduit'),('09-drive-lanes','Driver eye: separate terminals and clear canopy')]
for n,_ in SHOTS+EXTRA:
 raw=R/'renders'/(n+'.png');clean=R/'renders'/(n+'-clean.png')
 assert Image.open(raw).size==Image.open(clean).size==(1280,960),n
 assert clean.stat().st_mtime>=raw.stat().st_mtime,n
f='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf';title=ImageFont.truetype(f,34);label=ImageFont.truetype(f,18);small=ImageFont.truetype(f,16)
s=Image.new('RGB',(1304,1680),'#2c383d');d=ImageDraw.Draw(s);d.text((24,20),'TOWN STATE BANK',font=title,fill='#eee4ca');d.text((24,67),'1920s classical bank  /  Separate 1965+ drive-through state',font=small,fill='#bdcdc3')
for i,(n,t) in enumerate(SHOTS):
 x=24+i%2*640;y=112+i//2*500;im=Image.open(R/'renders'/(n+'-clean.png')).convert('RGB');s.paste(im.resize((616,462),Image.Resampling.LANCZOS),(x,y));d.text((x,y+468),t,font=label,fill='#eee4ca')
d.text((24,1620),'Actual Blender desktop previews. Original geometry and supplemental pixel art; fixed interior fixtures.',font=small,fill='#bdcdc3');d.text((24,1645),'Not Filament/S25 captures. Navigation, collision parsing, lighting and banking interactions need integration.',font=small,fill='#bdcdc3');s.save(R/'bank-review.jpg',quality=95)
c=Image.new('RGB',(1600,1380),'#2c383d');dd=ImageDraw.Draw(c);dd.text((24,18),'TOWN STATE BANK — TWO ERAS',font=title,fill='#eee4ca')
comparisons=[('01-front-day','1920s: stone front, columns and arched transom'),('08-drive-through','1965+: two-lane drive-through addition'),('07-vault','Fixed vault door with locking bars and handwheel'),('09-drive-lanes','Driver eye beneath the later canopy')]
for i,(n,t) in enumerate(comparisons):
 x=12+i%2*800;y=80+i//2*630;im=Image.open(R/'renders'/(n+'-clean.png')).convert('RGB');c.paste(im.resize((776,582),Image.Resampling.LANCZOS),(x,y));dd.text((x+12,y+592),t,font=label,fill='#eee4ca')
dd.text((24,1350),'Actual Blender views. Select one state from town facts; bank name is repainted per town.',font=small,fill='#bdcdc3');c.save(R/'bank-states.jpg',quality=95)
cards=''.join(f'<figure><a href="renders/{n}-clean.png"><img src="renders/{n}-clean.png" alt="{t}"></a><figcaption>{t}</figcaption></figure>' for n,t in SHOTS+EXTRA)
links=''.join(f'<a href="small-town-bank-{s}.glb" download>{s} GLB</a><a href="small-town-bank-{s}.blend" download>{s} source</a>' for s in ['1920s','drive-through'])
(R/'preview.html').write_text('''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Town State Bank: two eras</title><style>body{margin:0;background:#2c383d;color:#eee4ca;font:16px/1.6 system-ui,sans-serif}main{max-width:1500px;padding:32px;margin:auto}h1{font-size:clamp(32px,5vw,56px);margin:0}p{max-width:1000px;color:#bdcdc3}a{color:#efb39d}nav{display:flex;gap:20px;flex-wrap:wrap;margin:24px 0}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(360px,1fr));gap:24px}figure{margin:0}img{width:100%;display:block}figcaption{padding:8px 0}</style><main><h1>Town State Bank</h1><p>A 1920s stone portico and high banking hall, with a fixed vault and four teller wickets. A separate 1965+ version adds two drive-through lanes, pneumatic conduit and a night deposit fixture. The town name is repainted on the carved frieze. Select one state from actual town history. All views are Blender desktop renders, not Filament/S25 captures; game navigation, lighting, collision parsing and banking interactions remain integration work.</p><nav>'''+links+'<a href="README.md">Rebuild/handoff</a><a href="validation.json">Validation</a></nav><div class="grid">'+cards+'</div></main></html>\n')
files=[p for p in R.iterdir() if p.is_file() and p.suffix in ['.py','.json','.md','.txt','.glb','.blend','.jpg','.html']]
for folder in ['textures','renders','source-materials']:files.extend(p for p in (R/folder).glob('*') if p.is_file())
zpath=R/'small-town-bank-model-and-previews.zip'
with zipfile.ZipFile(zpath,'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(files):z.write(p,'small-town-bank/'+str(p.relative_to(R)))
with zipfile.ZipFile(zpath) as z:assert z.testzip() is None
print(json.dumps({'archive':str(zpath),'files':len(files),'views':9,'bytes':zpath.stat().st_size},indent=2))
