"""Package actual Blender views with source, model and integration notes."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import zipfile,json
R=Path(__file__).resolve().parent
SHOTS=[('01-front-day','Four legs and blank name panel — day'),('02-blue-hour','Steel silhouette — blue hour'),('03-rear','Rear ladder and cage'),('04-above','Conical roof, hatch and catwalk'),('05-person-height','View from person height'),('06-tank-detail','Riveted tank and separate sign_name panel')]
f='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf';title=ImageFont.truetype(f,32);label=ImageFont.truetype(f,18);small=ImageFont.truetype(f,16)
s=Image.new('RGB',(1256,2050),'#292e3a');d=ImageDraw.Draw(s);d.text((24,20),'CLASSIC STEEL WATER TOWER',font=title,fill='#eee9df');d.text((24,65),'Four legs  /  Riveted tank, rear ladder, catwalk and blank name panel',font=small,fill='#bdc7c5')
for i,(n,caption) in enumerate(SHOTS):
 x=24+i%2*616;y=110+i//2*630;im=Image.open(R/'renders'/(n+'-clean.png')).convert('RGB');assert im.size==(1200,1200);s.paste(im.resize((592,592),Image.Resampling.LANCZOS),(x,y));d.text((x,y+598),caption,font=label,fill='#eee9df')
d.text((24,2005),'Blender desktop previews, not Filament or S25 captures. Town naming and utility/climbing behavior remain pending.',font=small,fill='#bdc7c5');s.save(R/'tower-review.jpg',quality=95)
cards=''.join(f'<figure><a href="renders/{n}-clean.png"><img src="renders/{n}-clean.png" alt="{caption}"></a><figcaption>{caption}</figcaption></figure>' for n,caption in SHOTS)
(R/'preview.html').write_text('''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Classic Steel Water Tower</title><style>body{margin:0;background:#292e3a;color:#eee9df;font:16px/1.6 system-ui,sans-serif}main{max-width:1400px;padding:32px;margin:auto}h1{font-size:clamp(32px,5vw,56px);margin:0}p{max-width:950px;color:#bdc7c5}a{color:#edb79f}nav{display:flex;gap:24px;flex-wrap:wrap;margin:24px 0}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(340px,1fr));gap:24px}figure{margin:0}img{width:100%;display:block}figcaption{padding:8px 0}</style><main><h1>Classic Steel Water Tower</h1><p>An original four-legged riveted steel tank with a conical roof, bracing, ladder, catwalk and separate blank sign_name panel. Actual Blender desktop previews, not Filament or S25 captures. The maintenance compound stays closed; town naming, utility behavior and climbing remain runtime work.</p><nav><a href="water-tower.glb" download>3D model</a><a href="water-tower.blend" download>Editable source</a><a href="README.md">Integration and rebuild</a><a href="validation.json">Validation</a></nav><div class="grid">'''+cards+'</div></main></html>\n')
files=[p for p in R.iterdir() if p.is_file() and p.suffix in ['.py','.json','.md','.txt','.blend','.glb','.jpg','.html']]
for folder in ['textures','renders','source-materials']:files += [p for p in (R/folder).glob('*') if p.is_file()]
zpath=R/'water-tower-model-and-previews.zip'
with zipfile.ZipFile(zpath,'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(files):z.write(p,'water-tower/'+str(p.relative_to(R)))
with zipfile.ZipFile(zpath) as z:assert z.testzip() is None
print(json.dumps({'archive':str(zpath),'files':len(files),'views':6,'bytes':zpath.stat().st_size},indent=2))
