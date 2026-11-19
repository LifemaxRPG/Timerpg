"""Assemble a local review page and contact sheet from the actual model renders."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import json, zipfile

R = Path(__file__).resolve().parent
SHOTS = [('01-day-front-clean.png', 'Daylight'),
         ('02-blue-hour-clean.png', 'Blue hour'),
         ('03-rear-service-clean.png', 'Rear service yard'),
         ('05-counter-detail-clean.png', 'Inside the dining room')]
font_path = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
title_font = ImageFont.truetype(font_path, 36)
label_font = ImageFont.truetype(font_path, 21)
small_font = ImageFont.truetype(font_path, 17)
sheet = Image.new('RGB', (1648, 1456), '#14292d')
d = ImageDraw.Draw(sheet)
d.text((32, 22), 'JUNCTION DINER', font=title_font, fill='#f0dbad')
d.text((32, 74), 'Original geometry + pixel textures  /  Blender desktop prototype', font=small_font, fill='#a7c3bd')
for i, (name, label) in enumerate(SHOTS):
    x, y = 24 + (i % 2)*812, 120 + (i // 2)*640
    shot = Image.open(R / 'renders' / name).convert('RGB')
    assert shot.size == (1200, 900), (name, shot.size)
    sheet.paste(shot.resize((788, 591), Image.Resampling.LANCZOS), (x, y))
    d.text((x, y+600), label, font=label_font, fill='#e5e0cb')
d.text((24, 1422), 'Timerpg prototype handoff. Blender desktop renders; Filament integration and S25 validation remain.', font=small_font, fill='#a7c3bd')
sheet.save(R / 'junction-diner-review.jpg', quality=94)
cards = ''.join(f'<figure><a href="renders/{name}"><img src="renders/{name}" alt="Junction Diner: {label}"></a><figcaption>{label}</figcaption></figure>' for name, label in SHOTS)
(R / 'preview.html').write_text('''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Junction Diner — local study</title><style>body{margin:0;background:#14292d;color:#f0dbad;font:16px/1.5 system-ui,sans-serif}main{max-width:1400px;margin:auto;padding:32px}h1{font-size:clamp(32px,5vw,64px);margin:0}p{color:#b4ccc6;max-width:900px}a{color:#f0dbad}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(340px,1fr));gap:24px}figure{margin:0}img{width:100%;display:block;border-radius:4px}figcaption{padding:10px 0;font-weight:600}nav{margin:24px 0;display:flex;gap:24px;flex-wrap:wrap}</style><main><h1>Junction Diner</h1><p>Original geometry, pixel textures and lettering. A rounded enamel dining room, red booths, a furnished counter, kitchen, roof equipment and service yard.</p><p>Actual Blender desktop prototype renders. Not an in-game or S25 capture. Prototype handoff for review; runtime integration is separate.</p><nav><a href="export/junction-diner.glb" download>Portable 3D model</a><a href="junction-diner.blend" download>Editable Blender scene</a><a href="README.md">Source and rebuild notes</a></nav><div class="grid">''' + cards + '</div></main></html>')
included = ['README.md', 'make_textures.py', 'build_diner.py', 'export_model.py',
            'finish_renders.py', 'package_preview.py', 'preview.html',
            'junction-diner.blend', 'junction-diner-review.jpg']
included += [str(p.relative_to(R)) for directory in ['textures', 'export', 'renders']
             for p in sorted((R / directory).glob('*')) if p.is_file()]
archive = R / 'junction-diner-source-and-previews.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
    for relative in included:
        z.write(R / relative, 'junction-diner/' + relative)
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
print(json.dumps({'archive':str(archive), 'bytes':archive.stat().st_size,
                  'files':len(included), 'renders':4}, indent=2))
