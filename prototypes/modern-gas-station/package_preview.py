"""Package the real model and its six checked desktop renders for local review."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json,zipfile
R=Path(__file__).resolve().parent
SHOTS=[('01-day-front-clean.png','Daylight'),('02-blue-hour-clean.png','Blue hour'),('03-back-clean.png','Rear service yard'),('04-above-clean.png','Roof and lot'),('05-person-height-clean.png','Player height: 1.7 m'),('06-island-equipment-clean.png','Trash + windshield-wash station')]
font='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
title=ImageFont.truetype(font,34);label=ImageFont.truetype(font,20);small=ImageFont.truetype(font,17)
sheet=Image.new('RGB',(1304,1654),'#292c3b');d=ImageDraw.Draw(sheet)
d.text((24,20),'CROSSFIELD',font=title,fill='#eee9df');d.text((25,67),'FUEL + MARKET  /  Second pass: canopy, storefront and forecourt',font=small,fill='#c9c1b6')
for i,(name,caption) in enumerate(SHOTS):
    x,y=24+(i%2)*640,112+(i//2)*500
    im=Image.open(R/'renders'/name).convert('RGB');assert im.size==(1280,960)
    sheet.paste(im.resize((616,462),Image.Resampling.LANCZOS),(x,y));d.text((x,y+468),caption,font=label,fill='#eee9df')
d.text((24,1623),'Actual Blender desktop renders. Prototype review; Filament integration and S25 testing remain.',font=small,fill='#c9c1b6')
sheet.save(R/'crossfield-review.jpg',quality=95)
cards=''.join(f'<figure><a href="renders/{name}"><img src="renders/{name}" alt="Crossfield: {caption}"></a><figcaption>{caption}</figcaption></figure>' for name,caption in SHOTS)
(R/'preview.html').write_text('''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Crossfield Fuel & Market</title><style>body{margin:0;background:#292c3b;color:#eee9df;font:16px/1.6 system-ui,sans-serif}main{max-width:1500px;padding:32px;margin:auto}h1{font-size:clamp(32px,5vw,60px);margin:0}p{color:#c9c1b6;max-width:900px}a{color:#e8b79c}nav{display:flex;gap:24px;flex-wrap:wrap;margin:24px 0}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(360px,1fr));gap:24px}figure{margin:0}img{width:100%;display:block}figcaption{padding:10px 0;font-weight:600}</style><main><h1>Crossfield Fuel & Market</h1><p>An original modern station for Timerpg, built using the project's material swatches and Claude's model guide. These are actual Blender desktop renders, not game or S25 captures. Prototype asset handoff; runtime integration remains separate.</p><nav><a href="modern-gas-station.glb" download>3D model</a><a href="modern-gas-station.blend" download>Editable scene</a><a href="README.md">Rebuild and integration notes</a><a href="validation.json">Asset validation</a></nav><div class="grid">'''+cards+'</div></main></html>\n')
files=[p for p in R.iterdir() if p.is_file() and (p.suffix in ['.py','.json','.md','.glb','.blend','.jpg','.html'])]
for directory in ['textures','renders','source-materials']:files+=list((R/directory).glob('*'))
archive=R/'crossfield-model-and-previews.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
    for p in sorted(files):
        if p.is_file():z.write(p,'modern-gas-station/'+str(p.relative_to(R)))
with zipfile.ZipFile(archive) as z:assert z.testzip() is None
print(json.dumps({'archive':str(archive),'bytes':archive.stat().st_size,'files':len(files),'views':len(SHOTS)},indent=2))
