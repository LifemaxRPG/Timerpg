#!/usr/bin/env python3
"""The Highways v2 contact sheet (WORLD_GEN.md "Highways that evolve"): draws the network of world-highways-map.ts's
hwy.json on its relief pictures, by year, with route numbers, interchanges and towns, plus an optional render.
Usage: python3 kotlin-street/tools/world-highways-sheet.py <mapdir> <out.jpg> [render.png] [caption]"""
import json
import sys

from PIL import Image, ImageDraw, ImageFont

src, dst = sys.argv[1], sys.argv[2]
render = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] else None
caption = sys.argv[4] if len(sys.argv) > 4 else ''
D = json.load(open(f'{src}/hwy.json'))


def font(size, bold=False):
    for f in (['DejaVuSans-Bold.ttf', 'DejaVuSans.ttf'] if bold else ['DejaVuSans.ttf']):
        for d in ['/usr/share/fonts/truetype/dejavu/', '']:
            try:
                return ImageFont.truetype(d + f, size)
            except OSError:
                pass
    return ImageFont.load_default()


TEAL, CREAM, ORANGE = (31, 111, 107), (243, 233, 200), (224, 122, 46)
STYLE = {  # class -> (colour, width at the panel's scale 1)
    'track': ((150, 110, 70), 1), 'county': ((120, 104, 90), 1), 'local': ((150, 150, 150), 1), 'frontage': ((110, 110, 110), 1),
    'highway': ((178, 40, 30), 2), 'expressway': (TEAL, 3),
}


def draw_view(v, year, scale=1.0, labels=True):
    img = Image.open(f"{src}/{v['name']}.png").convert('RGB')
    W, H = img.size
    k = W / (2 * v['hx'])
    px = lambda x, z: ((x - (v['cx'] - v['hx'])) * k, (z - (v['cz'] - v['hz'])) * k)
    over = Image.new('RGBA', img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(over)
    detail = v['hx'] < 40
    order = ['abandoned', 'track', 'county', 'local', 'frontage', 'highway', 'expressway']
    label_at = {}
    for cls in order:
        for s in v['segs']:
            st = s['at'].get(str(year))
            if not st or st['cls'] != cls:
                continue
            pts = [px(x, z) for x, z in s['pts']]
            if len(pts) < 2:
                continue
            if cls == 'abandoned':
                a = int(200 * (1 - st['fade']))
                d.line(pts, fill=(120, 100, 80, a), width=1 if not detail else 2)
                continue
            col, wd = STYLE[cls]
            if cls == 'county' and st['surface'] in ('dirt', 'track'):
                col = (170, 140, 100)
            if cls == 'highway':
                wd = 2 if st['lanes'] <= 2 else 3
                if st['surface'] == 'gravel':
                    col = (200, 120, 80)
            if cls == 'expressway':
                wd = {2: 2, 4: 3, 6: 4}.get(st['lanes'], 3)
            if cls == 'track':
                for i in range(len(pts) - 1):
                    if i % 2 == 0:
                        d.line([pts[i], pts[i + 1]], fill=col + (200,), width=1)
                continue
            w = max(1, round(wd * scale * (2.2 if detail else 1)))
            if cls == 'expressway' and detail:
                d.line(pts, fill=(20, 60, 58, 255), width=w + 2)
            d.line(pts, fill=col + (255,), width=w)
            if st['number'] and labels:
                n = st['number']
                L = sum(((pts[i + 1][0] - pts[i][0]) ** 2 + (pts[i + 1][1] - pts[i][1]) ** 2) ** .5 for i in range(len(pts) - 1))
                if n not in label_at or label_at[n][0] < L:
                    label_at[n] = (L, pts[len(pts) // 2])
    img = Image.alpha_composite(img.convert('RGBA'), over)
    d = ImageDraw.Draw(img)
    f = font(int(11 * scale) + (3 if detail else 0), True)
    fs = font(int(10 * scale) + (3 if detail else 0))
    if detail:
        for ic in v['ics']:
            if ic['year'] > year:
                continue
            x, y = px(ic['x'], ic['z'])
            r = 6 if ic['kind'] == 'system' else 4
            d.ellipse([x - r, y - r, x + r, y + r], outline=(0, 0, 0), fill=(255, 255, 255) if ic['kind'] != 'junction' else (200, 200, 200), width=2)
            ex = ', '.join(f"{k} exit {e}" for k, e in sorted(ic['exits'].items()) if k.startswith('P'))
            if ex and ic['kind'] == 'service' and v['name'] != 'beltway':
                d.text((x + 8, y - 16), ex, fill=(0, 0, 0), font=fs)
        for s in v['sites']:
            if s['from'] > year or (s.get('to') and s['to'] <= year):
                continue
            x, y = px(s['x'], s['z'])
            d.rectangle([x - 3, y + 6, x + 3, y + 12], fill=(140, 60, 160))
    for p in v['places']:
        x, y = px(p['x'], p['z'])
        if p['kind'] == 'hub' or detail or p['rank'] == 'major':
            r = 5 if p['kind'] == 'hub' else 3
            d.ellipse([x - r, y - r, x + r, y + r], fill=(30, 30, 30))
            if p['kind'] == 'hub' or detail:
                d.text((x + 6, y + 2), p['name'], fill=(20, 20, 20), font=fs)
    for n, (L, (x, y)) in sorted(label_at.items()):
        if L < (60 if not detail else 30):
            continue
        tw = d.textlength(n, font=f)
        if n.startswith('P'):
            # the Pennant shield: a banner with a swallowtail notch, teal with a cream number
            w2, h2 = tw / 2 + 5, 8 * scale + 3
            d.polygon([(x - w2, y - h2), (x + w2, y - h2), (x + w2, y + h2 + 4), (x, y + h2 - 1), (x - w2, y + h2 + 4)], fill=TEAL, outline=CREAM)
            d.rectangle([x - w2, y - h2, x + w2, y - h2 + 2], fill=ORANGE)
            d.text((x - tw / 2, y - h2 + 2), n[1:], fill=CREAM, font=f)
        elif n.startswith('N'):
            # the national route's milestone: white, arched top, black border
            w2, h2 = tw / 2 + 4, 7 * scale + 3
            d.rounded_rectangle([x - w2, y - h2, x + w2, y + h2], radius=int(w2), fill=(255, 255, 255), outline=(0, 0, 0))
            d.text((x - tw / 2 + 4, y - h2 + 3), n[1:], fill=(0, 0, 0), font=f)
    d.rectangle([0, 0, img.size[0], 22 * scale + 6], fill=(255, 255, 255))
    d.text((6, 3), f"{v['title']}  {year}" if v['name'] == 'continent' else f"{v['title']}  ({year})", fill=(0, 0, 0), font=font(int(15 * scale), True))
    return img.convert('RGB')


views = {v['name']: v for v in D['views']}
cont = views['continent']
row1 = [draw_view(cont, y) for y in cont['years']]
pw = 640
row1 = [im.resize((pw, round(im.size[1] * pw / im.size[0])), Image.LANCZOS) for im in row1]
row2 = []
if 'bypass' in views:
    b = views['bypass']
    row2 += [draw_view(b, y) for y in b['years']]
row2.append(draw_view(views['beltway'], 1990))
if render:
    row2.append(Image.open(render).convert('RGB'))
h2 = 420
row2 = [im.resize((round(im.size[0] * h2 / im.size[1]), h2), Image.LANCZOS) for im in row2]
Wt = max(pw * len(row1), sum(im.size[0] for im in row2) + 10 * len(row2))
legend_h = 40
Ht = 46 + row1[0].size[1] + 10 + h2 + legend_h + 20
sheet = Image.new('RGB', (Wt, Ht), (250, 250, 248))
d = ImageDraw.Draw(sheet)
d.text((10, 10), caption, fill=(0, 0, 0), font=font(20, True))
x = 0
for im in row1:
    sheet.paste(im, (x, 46)); x += pw
x, y = 0, 46 + row1[0].size[1] + 10
for im in row2:
    sheet.paste(im, (x, y)); x += im.size[0] + 10
y += h2 + 8
lx = 10
fl = font(14)
for name, col, wd in [('expressway (Pennant)', TEAL, 5), ('highway (national)', (178, 40, 30), 3), ('gravel highway', (200, 120, 80), 3), ('county road', (120, 104, 90), 2), ('dirt road / track', (170, 140, 100), 2), ('old local road', (150, 150, 150), 2), ('abandoned, fading', (150, 130, 110), 1)]:
    d.line([(lx, y + 14), (lx + 40, y + 14)], fill=col, width=wd)
    d.text((lx + 46, y + 5), name, fill=(0, 0, 0), font=fl)
    lx += 60 + d.textlength(name, font=fl)
d.text((lx, y + 5), 'circles: interchanges (exit numbers = km); purple: roadside sites', fill=(0, 0, 0), font=fl)
if sheet.size[0] > 3000:
    sheet = sheet.resize((3000, round(sheet.size[1] * 3000 / sheet.size[0])), Image.LANCZOS)
sheet.save(dst, quality=88)
print(dst, sheet.size)
