"""Package six actual Blender views of Valley Radio."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json,zipfile
R=Path(__file__).resolve().parent
SHOTS=[('01-front-day','Broadcast house, glazed entrance and lattice mast — day'),('02-blue-hour','Lit nameplate, blade and entrance — night'),('03-rear','Service awning, roof equipment and finished rear'),('04-above','Complete roof, parking and mast compound'),('05-person-height','Public frontage from street level'),('06-studio-control','Studio/control-room shells with real partition glazing')]
f='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf';title=ImageFont.truetype(f,34);label=ImageFont.truetype(f,18);small=ImageFont.truetype(f,16)
s=Image.new('RGB',(1304,1680),'#2c383d');d=ImageDraw.Draw(s);d.text((24,20),'VALLEY RADIO',font=title,fill='#eee4ca');d.text((24,67),'1950s–60s  /  Local broadcast house and freestanding lattice mast',font=small,fill='#bdcdc3')
for i,(n,caption) in enumerate(SHOTS):
 x=24+i%2*640;y=112+i//2*500;im=Image.open(R/'renders'/(n+'-clean.png')).convert('RGB');assert im.size==(1280,960);s.paste(im.resize((616,462),Image.Resampling.LANCZOS),(x,y));d.text((x,y+468),caption,font=label,fill='#eee4ca')
d.text((24,1620),'Actual Blender desktop previews. Clean studio/control shells; fixed wall lining and observation ledge.',font=small,fill='#bdcdc3');d.text((24,1645),'Not Filament or S25 captures. Doors, lighting, navigation and broadcast state require game integration.',font=small,fill='#bdcdc3');s.save(R/'radio-station-review.jpg',quality=95)
shots=SHOTS
cards=''.join(f'<figure><a href="renders/{n}-clean.png"><img src="renders/{n}-clean.png" alt="{caption}"></a><figcaption>{caption}</figcaption></figure>' for n,caption in shots)
(R/'preview.html').write_text('''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Valley Radio</title><style>body{margin:0;background:#2c383d;color:#eee4ca;font:16px/1.6 system-ui,sans-serif}main{max-width:1500px;padding:32px;margin:auto}h1{font-size:clamp(32px,5vw,56px);margin:0}p{max-width:980px;color:#bdcdc3}a{color:#efb2a0}nav{display:flex;gap:20px;flex-wrap:wrap;margin:24px 0}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(360px,1fr));gap:24px}figure{margin:0}img{width:100%;display:block}figcaption{padding:8px 0}</style><main><h1>Valley Radio</h1><p>An original mid-century broadcast house with an angular glazed entrance, brick studio wing, service side and self-supporting lattice mast. Clean studio/control-room shells with fixed wall lining and observation ledge. Actual Blender desktop previews; Filament/S25 integration and measurements are pending. Broadcasts, doors and antenna behavior are not implemented by this model.</p><nav><a href="radio-station.glb" download>Clean building model</a><a href="radio-station.blend" download>Editable source</a><a href="README.md">Rebuild and integration</a><a href="validation.json">Model validation</a></nav><div class="grid">'''+cards+'</div></main></html>\n')
files=[p for p in R.iterdir() if p.is_file() and p.suffix in ['.py','.json','.md','.txt','.glb','.blend','.jpg','.html']]
for folder in ['textures','renders','source-materials']:files += [p for p in (R/folder).glob('*') if p.is_file()]
zpath=R/'radio-station-model-and-previews.zip'
with zipfile.ZipFile(zpath,'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(files):z.write(p,'radio-station/'+str(p.relative_to(R)))
with zipfile.ZipFile(zpath) as z:assert z.testzip() is None
print(json.dumps({'archive':str(zpath),'files':len(files),'views':len(shots),'bytes':zpath.stat().st_size},indent=2))
