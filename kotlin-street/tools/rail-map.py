#!/usr/bin/env python3
"""Draws the railroad network round the town from its rail.json (tools/export-rail.ts) for the given years, side by
side: towns (a filled dot: passenger trains stop; a ring: freight only; grey: no railroad or the rails gone), lines by
their state that year (main lines thick, branches thin; with passenger trains in colour, freight-only dark, the bed of a
lifted line dotted, a rail trail green). docs/RAIL.md.

Usage: python3 rail-map.py rail.json out.png [year ...]   (default 1955 2025)
"""
import json, sys
from PIL import Image, ImageDraw, ImageFont

def main():
    src, out = sys.argv[1], sys.argv[2]
    years = [int(y) for y in sys.argv[3:]] or [1955, 2025]
    d = json.load(open(src))
    R = 5  # cells round the town shown
    d['towns'] = [t for t in d['towns'] if abs(t['cx']) <= R and abs(t['cy']) <= R]
    towns = {t['id']: t for t in d['towns']}
    d['links'] = [l for l in d['links'] if l['a'] in towns and l['b'] in towns]
    xs = [t['center'][0] for t in d['towns']]; zs = [t['center'][1] for t in d['towns']]
    x0, x1, z0, z1 = min(xs), max(xs), min(zs), max(zs)
    W = 1000; pad = 40
    s = (W - 2 * pad) / (x1 - x0)
    H = int((z1 - z0) * s + 2 * pad + 40)
    img = Image.new('RGB', (W * len(years), H), (236, 230, 214))
    dr = ImageDraw.Draw(img)
    try: font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 11); big = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 18)
    except OSError: font = big = ImageFont.load_default()
    frm = d['from']
    for k, year in enumerate(years):
        ox = k * W
        P = lambda x, z: (ox + pad + (x - x0) * s, pad + 30 + (z - z0) * s)
        dr.rectangle([ox, 0, ox + W - 1, H - 1], outline=(120, 110, 90))
        nat = year >= d['service']['from']
        dr.text((ox + pad, 8), f"{d['name']} and round it, {year}" + (f"  ({d['service']['full']})" if nat else "  (the railroads' own trains)"), fill=(40, 40, 50), font=big)
        for l in d['links']:
            if l['built'] > year: continue
            gone = l.get('abandoned') is not None and year >= l['abandoned']
            trail = l.get('trail') is not None and year >= l['trail']
            pax = not gone and (l.get('passengerEnd') is None or year <= l['passengerEnd']) and (not nat or l['kind'] == 'main')
            pts = [P(x, z) for x, z in l['pts']]
            wd = 4 if l['kind'] == 'main' else 2
            if gone:
                col = (90, 160, 80) if trail else (170, 160, 140)
                for i in range(0, len(pts) - 1, 2): dr.line([pts[i], pts[i + 1]], fill=col, width=2)
            else:
                dr.line(pts, fill=(200, 60, 50) if pax else (70, 70, 80), width=wd)
        for t in d['towns']:
            st = t['status'][max(0, min(len(t['status']) - 1, year - frm))]
            x, y = P(*t['center'])
            r = 6 if t['rank'] == 'major' else 4.5 if t['rank'] == 'town' else 3.5
            if st == 'p': dr.ellipse([x - r, y - r, x + r, y + r], fill=(200, 60, 50), outline=(40, 30, 30))
            elif st == 'f': dr.ellipse([x - r, y - r, x + r, y + r], fill=(236, 230, 214), outline=(40, 40, 50), width=2)
            else: dr.ellipse([x - r + 1, y - r + 1, x + r - 1, y + r - 1], fill=(170, 165, 150))
            home = t['id'] == '0,0'
            if home: dr.rectangle([x - 9, y - 9, x + 9, y + 9], outline=(30, 60, 160), width=2)
            if abs(t['cx']) <= 3 and abs(t['cy']) <= 3 or home:
                dr.text((x + r + 2, y - 6), t['name'], fill=(30, 30, 40) if st == 'p' else (110, 105, 95), font=font)
        n = sum(1 for t in d['towns'] if t['status'][year - frm] == 'p')
        dr.text((ox + pad, H - 22), f"{n} of {len(d['towns'])} towns with passenger trains   red: passenger line / station   dark: freight only   ring: depot shut   dotted: rails lifted (green: rail trail)", fill=(60, 60, 70), font=font)
    img.save(out)

main()
