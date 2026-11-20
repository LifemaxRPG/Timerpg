"""One lean review sheet and HTML gallery; no ZIP or duplicate delivery frames."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json,html
R=Path(__file__).resolve().parent
STATES=['1925','1955','1975','2000'];NAMES=['1925: original school','1955: post-war growth','1975: Lincoln Consolidated','2000: renovated campus']
EXTRAS=[('1925-entrance','Original entrance tower and datestone'),('1925-corridor','Locker corridor and open classroom portals'),('1955-gym','Bow-roof gym, court and fixed basketball hoops'),('1975-bleachers','Field viewed from the steel grandstand'),('1975-portables','Three raised portable classrooms and ramps'),('2000-security','New glass entrance and accessible side ramp')]
infos=[json.loads((R/('state-'+s+'.json')).read_text()) for s in STATES]
info={'name':'Lincoln High School campus through time','default_state':'1925','state_selection':'Choose one complete state from actual town history; no scheduled construction/renaming/occupants are created.','states':infos,'far':json.loads((R/'far-info.json').read_text()),'render_provenance':'Actual Blender desktop models, 1024x768 Cycles renders, standard OIDN sampling-noise cleanup. Not Filament/S25 captures.'}
(R/'model-info.json').write_text(json.dumps(info,indent=2)+'\n')
shots=[(s+'-'+v,n+' / '+v) for s,n in zip(STATES,NAMES) for v in ['day','night','rear','above']]+EXTRAS
for n,_ in shots:
 p=R/'renders'/(n+'.png');raw=Path('/tmp/lincoln-school-raw')/(n+'.png');assert Image.open(p).size==(1024,768),n
 if raw.exists():assert p.stat().st_mtime>=raw.stat().st_mtime,('Stale delivery frame',n)
font='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf';h=ImageFont.truetype(font,38);label=ImageFont.truetype(font,18);small=ImageFont.truetype(font,16)
canvas=Image.new('RGB',(2120,2800),'#2c383d');d=ImageDraw.Draw(canvas);d.text((24,18),'LINCOLN — A CAMPUS THROUGH A CENTURY',font=h,fill='#eee4ca');d.text((24,70),'Four complete era alternatives. Actual Blender desktop views; game integration pending.',font=small,fill='#bdcdc3')
for row,(s,name) in enumerate(zip(STATES,NAMES)):
 y=112+row*440;d.text((24,y),name,font=label,fill='#eee4ca')
 for col,v in enumerate(['day','night','rear','above']):
  x=20+col*525;im=Image.open(R/'renders'/(s+'-'+v+'.png')).convert('RGB');canvas.paste(im.resize((510,382),Image.Resampling.LANCZOS),(x,y+28));d.text((x,y+412),v.upper(),font=small,fill='#bdcdc3')
for i,(n,t) in enumerate(EXTRAS):
 x=20+i%4*525;y=1900+i//4*440;im=Image.open(R/'renders'/(n+'.png')).convert('RGB');canvas.paste(im.resize((510,382),Image.Resampling.LANCZOS),(x,y));d.text((x,y+390),t,font=small,fill='#eee4ca')
d.text((24,2780),'Original geometry and supplemental pixel art. Shared LimeZu swatches retained for Timerpg only. Not Filament/S25 captures.',font=small,fill='#bdcdc3');canvas.save(R/'school-review.jpg',quality=94)
links=''.join(f'<a href="lincoln-high-school-{s}.glb" download>{s} GLB</a><a href="lincoln-high-school-{s}.blend" download>{s} packed source</a>' for s in STATES)
cards=''.join(f'<figure><a href="renders/{n}.png"><img src="renders/{n}.png" loading="lazy" alt="{html.escape(t)}"></a><figcaption>{html.escape(t)}</figcaption></figure>' for n,t in shots)
(R/'preview.html').write_text('''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Lincoln High School through time</title><style>body{margin:0;background:#2c383d;color:#eee4ca;font:16px/1.6 system-ui,sans-serif}main{max-width:1600px;padding:30px;margin:auto}h1{font-size:clamp(30px,5vw,52px);margin:0}p{max-width:1100px;color:#bdcdc3}nav{display:flex;gap:18px;flex-wrap:wrap;margin:25px 0}a{color:#efb39d}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(350px,1fr));gap:24px}figure{margin:0}img{display:block;width:100%}figcaption{padding:8px 0}</style><main><h1>Lincoln: a campus through a century</h1><p>The original three-storey brick school and baseball diamond grow into a campus with a ribbon-window wing, bow-roof gym and football grounds. Consolidation brings a new name, portables, student parking and steel bleachers; renovation adds the glass entrance, turf and track. Pick one complete visual state according to town facts. The 1955 far silhouette is a separate limited fallback. These are actual Blender desktop views, not Filament/S25 captures. State selection, collision parsing, navigation, lighting, pupils and security remain game integration work.</p><nav>'''+links+'<a href="school-far.glb">1955 far silhouette</a><a href="school-review.jpg">Full review sheet</a><a href="README.md">Rebuild and handoff</a><a href="validation.json">Validation</a></nav><div class="grid">'+cards+'</div></main></html>\n')
print(json.dumps({'states':4,'views':len(shots),'review':'school-review.jpg','triangles':{i['state']:i['triangles_visible'] for i in infos}},indent=2))
