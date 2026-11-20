"""Assemble state metadata, actual Blender view sheets, gallery and a local archive."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json,zipfile
R=Path(__file__).resolve().parent
base=json.loads((R/'state-1912.json').read_text());later=json.loads((R/'state-1970s.json').read_text());info=dict(base);info['states']=[base,later];info['default_state']='1912';info['state_selection']='Choose one complete GLB. Addition activation follows actual construction/land facts, not a forced date.';(R/'model-info.json').write_text(json.dumps(info,indent=2)+'\n')
SHOTS=[('01-front-day','1912 library: columns, stone base and slate hip'),('02-blue-hour','Lanterns and pane-only reading-room glow'),('03-rear','Original rear service entrance and arched windows'),('04-above','Complete hipped roof and portico'),('05-person-height','Raised entrance from the public approach'),('06-reading-hall','Central desk hall and reading-room passages'),('07-addition','Later state: 1970s reading wing and glazed link'),('08-basement','Basement room and actual two-flight stair')]
f='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf';title=ImageFont.truetype(f,34);label=ImageFont.truetype(f,18);small=ImageFont.truetype(f,16)
s=Image.new('RGB',(1304,2180),'#2c383d');d=ImageDraw.Draw(s);d.text((24,20),'PUBLIC LIBRARY — TWO HISTORICAL STATES',font=title,fill='#eee4ca');d.text((24,67),'1912 brick and stone library  /  Separate 1970s addition state',font=small,fill='#bdcdc3')
for i,(n,caption) in enumerate(SHOTS):
 x=24+i%2*640;y=112+i//2*500;im=Image.open(R/'renders'/(n+'-clean.png')).convert('RGB');assert im.size==(1280,960);s.paste(im.resize((616,462),Image.Resampling.LANCZOS),(x,y));d.text((x,y+468),caption,font=label,fill='#eee4ca')
d.text((24,2120),'Actual Blender desktop previews. Clean shells; books, shelving and furnishings come from the game.',font=small,fill='#bdcdc3');d.text((24,2145),'Not Filament/S25 captures. Basement, stairs, terrain cutout and glTF collision parsing need integration.',font=small,fill='#bdcdc3');s.save(R/'library-review.jpg',quality=95)
c=Image.new('RGB',(1600,730),'#2c383d');dd=ImageDraw.Draw(c);dd.text((24,18),'PUBLIC LIBRARY — 1912 & 1970s ADDITION',font=title,fill='#eee4ca')
for i,(n,t) in enumerate([('01-front-day','1912 original'),('07-addition','Later state: linked 1970s wing')]):
 im=Image.open(R/'renders'/(n+'-clean.png')).convert('RGB');c.paste(im.resize((776,582),Image.Resampling.LANCZOS),(12+i*800,80));dd.text((24+i*800,672),t,font=label,fill='#eee4ca')
dd.text((24,705),'Actual Blender desktop previews; game integration pending.',font=small,fill='#bdcdc3');c.save(R/'library-states.jpg',quality=95)
cards=''.join(f'<figure><a href="renders/{n}-clean.png"><img src="renders/{n}-clean.png" alt="{t}"></a><figcaption>{t}</figcaption></figure>' for n,t in SHOTS)
(R/'preview.html').write_text('''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Public Library: 1912 and later addition</title><style>body{margin:0;background:#2c383d;color:#eee4ca;font:16px/1.6 system-ui,sans-serif}main{max-width:1500px;padding:32px;margin:auto}h1{font-size:clamp(32px,5vw,56px);margin:0}p{max-width:1000px;color:#bdcdc3}a{color:#e0bd9d}nav{display:flex;gap:20px;flex-wrap:wrap;margin:24px 0}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(360px,1fr));gap:24px}figure{margin:0}img{width:100%;display:block}figcaption{padding:8px 0}</style><main><h1>Public Library</h1><p>A 1912 brick-and-stone library with two reading rooms, central desk and a basement room reached by real stairs. A separate later-state GLB adds a low 1970s brick/glass wing through a short glazed link. Exterior thresholds stay open. These are actual Blender desktop views; basement navigation, terrain cutout, state selection and glTF collision parsing require game integration.</p><nav><a href="public-library-1912.glb" download>1912 GLB</a><a href="public-library-1970s.glb" download>Later-state GLB</a><a href="public-library-1912.blend" download>1912 source</a><a href="public-library-1970s.blend" download>Later source</a><a href="README.md">Rebuild/handoff</a><a href="validation.json">Validation</a></nav><div class="grid">'''+cards+'</div></main></html>\n')
files=[p for p in R.iterdir() if p.is_file() and p.suffix in ['.py','.json','.md','.txt','.glb','.blend','.jpg','.html']]
for folder in ['textures','renders','source-materials']:files.extend(p for p in (R/folder).glob('*') if p.is_file())
zpath=R/'public-library-model-and-previews.zip'
with zipfile.ZipFile(zpath,'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(files):z.write(p,'public-library/'+str(p.relative_to(R)))
with zipfile.ZipFile(zpath) as z:assert z.testzip() is None
print(json.dumps({'archive':str(zpath),'files':len(files),'views':len(SHOTS),'bytes':zpath.stat().st_size},indent=2))
