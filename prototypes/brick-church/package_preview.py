"""One combined church review and fourteen actual Blender desktop views."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json,html
R=Path(__file__).resolve().parent
infos=[json.loads((R/('model-info-'+s+'.json')).read_text()) for s in ['brick','country']]
info={'name':'Town churches','variant_selection':'Alternative designs chosen from town facts: brick Gothic church with rear parish hall, or white clapboard farm-town church with rear vestry. Not a forced replacement chain.','variants':infos,'render_provenance':'Actual Blender desktop models, Cycles 1024x768, 16 samples and standard OIDN sampling-noise cleanup. Not Filament/S25 captures.'};(R/'model-info.json').write_text(json.dumps(info,indent=2)+'\n')
views=[(v+'-'+n,('Brick Gothic' if v=='brick' else 'White country')+' / '+n) for v in ['brick','country'] for n in ['day','night','rear','above']]
close=[('brick-person','Brick entrance from person height'),('brick-nave','Pointed windows, nave ribs and raised chancel'),('brick-hall','Rear parish hall and fixed serving counter'),('country-person','Farm-town porch from person height'),('country-nave','Round windows, timber ribs and clear nave'),('country-belfry','Open belfry with fixed bronze bell')]
for n,_ in views+close:
 p=R/'renders'/(n+'.png');raw=Path('/tmp/church-raw')/(n+'.png');assert Image.open(p).size==(1024,768),n
 if raw.exists():assert p.stat().st_mtime>=raw.stat().st_mtime,('Stale delivery frame',n)
f='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf';title=ImageFont.truetype(f,32);label=ImageFont.truetype(f,16);small=ImageFont.truetype(f,14)
s=Image.new('RGB',(1640,1570),'#2c383d');d=ImageDraw.Draw(s);d.text((20,15),'TOWN CHURCHES — TWO ORIGINAL DESIGNS',font=title,fill='#eee4ca');d.text((20,58),'Brick Gothic with parish hall  /  White clapboard church for farm towns',font=label,fill='#bdcdc3')
for row,v in enumerate(['brick','country']):
 y=90+row*350;d.text((20,y),'BRICK GOTHIC' if v=='brick' else 'WHITE COUNTRY CHURCH',font=label,fill='#eee4ca')
 for col,n in enumerate(['day','night','rear','above']):
  x=15+col*407;im=Image.open(R/'renders'/(v+'-'+n+'.png')).convert('RGB');s.paste(im.resize((395,296),Image.Resampling.LANCZOS),(x,y+24));d.text((x,y+324),n.upper(),font=small,fill='#bdcdc3')
for i,(n,t) in enumerate(close):
 x=15+i%4*407;y=810+i//4*350;im=Image.open(R/'renders'/(n+'.png')).convert('RGB');s.paste(im.resize((395,296),Image.Resampling.LANCZOS),(x,y));d.text((x,y+304),t,font=small,fill='#eee4ca')
d.text((20,1525),'Actual Blender desktop renders, not Filament/S25 captures. The game supplies pews and loose furnishings.',font=small,fill='#bdcdc3');d.text((20,1548),'Original geometry and supplemental pixel art; shared LimeZu swatches retained for Timerpg only.',font=small,fill='#bdcdc3');s.save(R/'church-review.jpg',quality=95)
links='<a href="brick-church.glb" download>Brick GLB</a><a href="brick-church.blend" download>Brick packed source</a><a href="country-church.glb" download>Country GLB</a><a href="country-church.blend" download>Country packed source</a>'
cards=''.join(f'<figure><a href="renders/{n}.png"><img src="renders/{n}.png" loading="lazy" alt="{html.escape(t)}"></a><figcaption>{html.escape(t)}</figcaption></figure>' for n,t in views+close)
(R/'preview.html').write_text('''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Town churches: two designs</title><style>body{margin:0;background:#2c383d;color:#eee4ca;font:16px/1.6 system-ui,sans-serif}main{max-width:1600px;margin:auto;padding:30px}h1{font-size:clamp(30px,5vw,52px);margin:0}p{max-width:1100px;color:#bdcdc3}a{color:#efb39d}nav{display:flex;flex-wrap:wrap;gap:20px;margin:24px 0}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(350px,1fr));gap:24px}figure{margin:0}img{width:100%;display:block}figcaption{padding:8px 0}</style><main><h1>Town churches</h1><p>Two original alternatives: a brick Gothic church with a thirty-metre corner spire and rear parish hall, or a compact white clapboard church with an open belfry and rear vestry. Both have actual curved window openings, clean naves and raised chancels. The game supplies pews and loose furnishings. Select the design from town facts; no congregation history, services or graves are invented. All views are Blender desktop renders, not Filament/S25 captures. Collision parsing, navigation, lighting, per-town names and interactions remain game integration work.</p><nav>'''+links+'<a href="church-review.jpg">Review sheet</a><a href="README.md">Rebuild and handoff</a><a href="validation.json">Validation</a></nav><div class="grid">'+cards+'</div></main></html>\n')
print(json.dumps({'variants':2,'views':len(views+close),'triangles':{i['variant']:i['triangles_visible'] for i in infos},'review':'church-review.jpg'},indent=2))
