"""Six-view contact sheet and local review bundle from actual Blender renders."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import zipfile,json
R=Path(__file__).resolve().parent
SHOTS=[('01-street-day','Street entrance — day'),('02-platform-night','Covered platform — night'),('03-platform-day','Rail-facing elevation — day'),('04-above','Roof, platform and access'),('05-person-height','Street view at 1.7 m'),('06-waiting-hall','Waiting hall and ticket counter')]
font='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf';title=ImageFont.truetype(font,34);label=ImageFont.truetype(font,20);small=ImageFont.truetype(font,16)
sheet=Image.new('RGB',(1304,1680),'#292c3b');d=ImageDraw.Draw(sheet);d.text((24,20),'UNION DEPOT',font=title,fill='#eee9df');d.text((25,67),'Premier station  /  Brick, slate, timber and a four-face clock',font=small,fill='#c9c1b6')
for i,(n,caption) in enumerate(SHOTS):
 x,y=24+(i%2)*640,112+(i//2)*500;im=Image.open(R/'renders'/(n+'-clean.png')).convert('RGB');assert im.size==(1280,960);sheet.paste(im.resize((616,462),Image.Resampling.LANCZOS),(x,y));d.text((x,y+468),caption,font=label,fill='#eee9df')
d.text((24,1620),'Blender desktop previews. Reference track only; railway integration and S25 testing remain.',font=small,fill='#c9c1b6');d.text((24,1645),'Platform and station anchors are modular; no operating route or boarding behavior is assumed.',font=small,fill='#c9c1b6');sheet.save(R/'depot-review.jpg',quality=95)
cards=''.join(f'<figure><a href="renders/{n}-clean.png"><img src="renders/{n}-clean.png" alt="{caption}"></a><figcaption>{caption}</figcaption></figure>' for n,caption in SHOTS)
(R/'preview.html').write_text('''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Union Depot</title><style>body{margin:0;background:#292c3b;color:#eee9df;font:16px/1.6 system-ui,sans-serif}main{max-width:1500px;padding:32px;margin:auto}h1{font-size:clamp(32px,5vw,60px);margin:0}p{color:#c9c1b6;max-width:960px}a{color:#e8b79c}nav{display:flex;gap:24px;flex-wrap:wrap;margin:24px 0}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(360px,1fr));gap:24px}figure{margin:0}img{width:100%;display:block}figcaption{padding:10px 0}</style><main><h1>Union Depot</h1><p>An original premier railroad depot for Timerpg, with a clock tower, waiting hall, ticket counter, baggage wing and covered platform. These are Blender desktop previews. The track is presentation scenery and is absent from the GLB; final railway alignment and game integration remain pending.</p><nav><a href="premier-railroad-depot.glb" download>3D model</a><a href="premier-railroad-depot.blend" download>Editable scene</a><a href="README.md">Rebuild and integration notes</a><a href="validation.json">Validation</a></nav><div class="grid">'''+cards+'</div></main></html>\n')
files=[p for p in R.iterdir() if p.is_file() and p.suffix in ['.py','.json','.md','.glb','.blend','.jpg','.html','.txt']]
for folder in ['textures','renders','source-materials']:files+=list((R/folder).glob('*'))
archive=R/'union-depot-model-and-previews.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(files):
  if p.is_file():z.write(p,'premier-railroad-depot/'+str(p.relative_to(R)))
with zipfile.ZipFile(archive) as z:assert z.testzip() is None
print(json.dumps({'archive':str(archive),'bytes':archive.stat().st_size,'files':len(files),'views':len(SHOTS)},indent=2))
