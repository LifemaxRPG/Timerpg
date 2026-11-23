#!/usr/bin/env python3
"""Lays the panels of kotlin-street/tools/world-map.ts on one sheet (at most 1600 px wide).

  python3 kotlin-street/tools/world-sheet.py <panel dir> <out.jpg>
"""
import json, sys
from PIL import Image, ImageDraw, ImageFont

def font(size, bold=False):
    try: return ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans%s.ttf' % ('-Bold' if bold else ''), size)
    except Exception: return ImageFont.load_default()

CODE = {'capital': 'C', 'port': 'P', 'river city': 'R', 'mountain town': 'Mt', 'mill town': 'Mi', 'farm town': 'F'}

def main():
    d, out = sys.argv[1], sys.argv[2]
    meta = json.load(open(f'{d}/labels.json'))
    SW = 1600
    rows = []
    f, fb, fs = font(11), font(12, True), font(10, True)
    for s in meta['seeds']:
        seed = s['seed']
        planet = Image.open(f'{d}/{seed}-planet.png').convert('RGB'); zoom = Image.open(f'{d}/{seed}-zoom.png').convert('RGB')
        k, jan, jul = (Image.open(f'{d}/{seed}-{n}.png').convert('RGB') for n in ('koppen', 'jan', 'jul'))
        top = max(planet.height, zoom.height)
        row = Image.new('RGB', (SW, 22 + top + 18 + k.height + 6), (245, 243, 238)); dr = ImageDraw.Draw(row)
        cr = s['crossings']
        dr.text((8, 4), f"seed '{seed}': {s['continents']} continents, land {s['landPct']}% of the planet (30% outside the ice caps), "
                f"{s['hubs']} hubs, {s['towns']} towns, {s['hamlets']} hamlets; capital's continent {s['start']}; "
                f"ocean crossings {cr[0] if cr else '-'}-{cr[1] if cr else '-'} h by sailboat; generated in {s['genMs']/1000:.0f} s",
                fill=(0, 0, 0), font=fb)
        row.paste(planet, (8, 22)); zx = planet.width + 16; row.paste(zoom, (zx, 22))
        for l in s['planetLabels']:
            t = CODE[l['type']]; dr.text((8 + l['x'] + 5, 22 + l['y'] - 7), t, fill=(0, 0, 0), font=fs, stroke_width=2, stroke_fill=(255, 255, 255))
        zw = s['zoomSize'][0]
        for l in s['zoomLabels']:
            x, y = zx + l['x'], 22 + l['y']
            tw = dr.textlength(l['text'], font=fs)
            tx = x + 9 if x + 9 + tw < zx + zw else x - 9 - tw
            dr.text((tx, y - 7), l['text'], fill=(0, 0, 0), font=fs, stroke_width=2, stroke_fill=(255, 255, 255))
        L = s['zoomScaleKm']; zy = 22 + s['zoomSize'][1]
        dr.rectangle([zx + 10, zy - 20, zx + 10 + L, zy - 14], fill=(255, 255, 255), outline=(0, 0, 0))
        dr.text((zx + 14 + L, zy - 25), '100 km', fill=(0, 0, 0), font=f, stroke_width=2, stroke_fill=(255, 255, 255))
        y2 = 22 + top + 16
        for i, (im, cap) in enumerate([(k, 'Koppen climate classes'), (jan, 'January mean temperature'), (jul, 'July mean temperature')]):
            x = 8 + i * (im.width + 8)
            row.paste(im, (x, y2)); dr.text((x + 4, y2 - 14), cap, fill=(0, 0, 0), font=fb)
        rows.append(row)
    # legend
    leg = Image.new('RGB', (SW, 84), (245, 243, 238)); dr = ImageDraw.Draw(leg)
    dr.text((8, 4), 'World generator (src/world/gen, kotlin-street/tools/world-map.ts): planet 1,996 x 998 km, x wraps; desktop renders of the '
            'skeleton, rolled so the capital\'s continent is in the middle; right: the capital\'s continent', fill=(0, 0, 0), font=fb)
    x, y = 8, 24
    for kname, c in meta['hubs'].items():
        dr.rectangle([x, y, x + 10, y + 10], fill=tuple(c), outline=(0, 0, 0)); t = f'{CODE[kname]} {kname}'; dr.text((x + 14, y - 2), t, fill=(0, 0, 0), font=f); x += 14 + dr.textlength(t, font=f) + 12
    dr.ellipse([x, y - 2, x + 14, y + 12], outline=(255, 215, 0), width=2); dr.text((x + 18, y - 2), 'launch hub', fill=(0, 0, 0), font=f); x += 96
    for name, c, wd in [('main line', (15, 15, 15), 3), ('branch', (80, 80, 80), 1), ('highway', (240, 140, 40), 2), ('expressway', (230, 60, 30), 2),
                        ('county road', (120, 100, 80), 1), ('sea route', (255, 255, 255), 2), ('ferry', (200, 230, 255), 2), ('river', (46, 98, 205), 2)]:
        dr.line([(x, y + 5), (x + 20, y + 5)], fill=c, width=wd); dr.text((x + 24, y - 2), name, fill=(0, 0, 0), font=f); x += 30 + dr.textlength(name, font=f) + 8
    x, y = 8, 44
    for name, c in meta['biomes'].items():
        if name in ('ocean',): continue
        dr.rectangle([x, y, x + 10, y + 10], fill=tuple(c), outline=(80, 80, 80)); dr.text((x + 13, y - 2), name, fill=(0, 0, 0), font=f); x += 13 + dr.textlength(name, font=f) + 8
    x, y = 8, 64
    dr.text((x, y - 2), 'Koppen:', fill=(0, 0, 0), font=fb); x += 60
    for e in meta['koppen']:
        dr.rectangle([x, y, x + 10, y + 10], fill=tuple(e['c']), outline=(80, 80, 80)); dr.text((x + 13, y - 2), e['k'], fill=(0, 0, 0), font=f); x += 13 + dr.textlength(e['k'], font=f) + 8
    dr.text((x + 10, y - 2), 'temperature: blue -30 C, white 0, green 10, yellow 20, red 35', fill=(0, 0, 0), font=f)
    sheet = Image.new('RGB', (SW, leg.height + sum(r.height for r in rows)), (245, 243, 238))
    sheet.paste(leg, (0, 0)); y = leg.height
    for r in rows: sheet.paste(r, (0, y)); y += r.height
    sheet.save(out, quality=88)
    print(out, sheet.size)

if __name__ == '__main__':
    main()
