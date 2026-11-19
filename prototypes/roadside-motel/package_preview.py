"""Actual Blender images, review gallery and local model bundle."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import zipfile,json
R=Path(__file__).resolve().parent;SHOTS=[('01-court-day','L-shaped motor court — day'),('02-court-night','Neon, covered galleries and pool — night'),('03-rear','Rear service side'),('04-above','12 rooms and parking layout'),('05-pool-and-office','Pool, ice alcove and office at 1.7 m'),('06-room-shell','Guest-room shell for game furnishing')]
f='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf';title=ImageFont.truetype(f,34);label=ImageFont.truetype(f,19);small=ImageFont.truetype(f,16)
s=Image.new('RGB',(1304,1680),'#292c3b');d=ImageDraw.Draw(s);d.text((24,20),'SUNDOWN MOTEL',font=title,fill='#eee9df');d.text((25,67),'1950s–60s  /  Twelve rooms, a neon office, ice machine and pool',font=small,fill='#c9c1b6')
for i,(n,caption) in enumerate(SHOTS):
 x=24+i%2*640;y=112+i//2*500;im=Image.open(R/'renders'/(n+'-clean.png')).convert('RGB');assert im.size==(1280,960);s.paste(im.resize((616,462),Image.Resampling.LANCZOS),(x,y));d.text((x,y+468),caption,font=label,fill='#eee9df')
d.text((24,1620),'Blender desktop previews. Name/vacancy artwork is isolated; simulation bindings remain pending.',font=small,fill='#c9c1b6');d.text((24,1645),'Pool needs a runtime terrain cutout and water/traversal integration. Not Filament or S25 captures.',font=small,fill='#c9c1b6');s.save(R/'motel-review.jpg',quality=95)
cards=''.join(f'<figure><a href="renders/{n}-clean.png"><img src="renders/{n}-clean.png" alt="{caption}"></a><figcaption>{caption}</figcaption></figure>' for n,caption in SHOTS)
(R/'preview.html').write_text('''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Sundown Motel</title><style>body{margin:0;background:#292c3b;color:#eee9df;font:16px/1.6 system-ui,sans-serif}main{max-width:1500px;padding:32px;margin:auto}h1{font-size:clamp(32px,5vw,60px);margin:0}p{max-width:960px;color:#c9c1b6}a{color:#e8b79c}nav{display:flex;gap:24px;flex-wrap:wrap;margin:24px 0}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(360px,1fr));gap:24px}figure{margin:0}img{width:100%;display:block}figcaption{padding:10px 0}</style><main><h1>Sundown Motel</h1><p>A 1950s–60s twelve-room L-shaped motel with a neon office, isolated name/vacancy panels, an ice machine and a fenced pool. Actual Blender previews; game bindings, terrain cutting and water/traversal integration remain pending. Rooms are clean shells for the game to furnish.</p><nav><a href="roadside-motel.glb" download>3D model</a><a href="roadside-motel.blend" download>Editable source</a><a href="README.md">Rebuild and integration notes</a><a href="validation.json">Validation</a></nav><div class="grid">'''+cards+'</div></main></html>\n')
files=[p for p in R.iterdir() if p.is_file() and p.suffix in ['.py','.json','.md','.glb','.blend','.jpg','.html','.txt']]
for folder in ['textures','renders','source-materials']:files+=list((R/folder).glob('*'))
zpath=R/'sundown-motel-model-and-previews.zip'
with zipfile.ZipFile(zpath,'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(files):
  if p.is_file():z.write(p,'roadside-motel/'+str(p.relative_to(R)))
with zipfile.ZipFile(zpath) as z:assert z.testzip() is None
print(json.dumps({'archive':str(zpath),'bytes':zpath.stat().st_size,'files':len(files),'views':6},indent=2))
