#!/usr/bin/env python3
"""Top-down debug picture of a street.json v2 town (tools/export-town.ts): roads, water, plaza, lots, building footprints,
props and the yards (tools/town-yards.ts: every item by kind, the back lanes and verges). For checking the yards by eye.
Usage: python3 yards-debug.py street.json out.png [scale px/unit, default 0.25] [x0 z0 x1 z1: a world window to zoom on]"""
import json, sys
from PIL import Image, ImageDraw

COL = {
    'lawn': (96, 168, 84), 'meadow': (128, 170, 90), 'gravel': (190, 182, 160), 'frontPath': (214, 196, 160),
    'sidePath': (206, 190, 156), 'driveway': (120, 120, 126), 'kerbCut': (150, 150, 156), 'parkingPad': (136, 136, 142),
    'garage': (150, 92, 70), 'carport': (170, 120, 90), 'frontFence': (245, 245, 240), 'hedge': (40, 110, 50),
    'lowWall': (170, 150, 130), 'postRail': (150, 110, 70), 'gate': (255, 220, 0), 'sideGate': (255, 200, 0),
    'backGate': (255, 160, 0), 'sideFence': (120, 90, 60), 'backFence': (110, 80, 50), 'patio': (196, 170, 150),
    'deck': (176, 130, 86), 'shed': (130, 80, 50), 'greenhouse': (190, 230, 230), 'vegBed': (110, 80, 40),
    'flowerBed': (230, 110, 170), 'washingLine': (255, 255, 255), 'bins': (40, 60, 40), 'grill': (30, 30, 30),
    'table': (220, 220, 220), 'chair': (240, 240, 240), 'bench': (160, 120, 80), 'playSet': (240, 70, 60),
    'trampoline': (40, 40, 140), 'sandpit': (240, 220, 150), 'pool': (70, 170, 230), 'poolDeck': (230, 225, 210),
    'tree': (30, 90, 40), 'fruitTree': (90, 140, 40), 'bush': (60, 130, 60), 'compost': (90, 60, 30),
    'firePit': (200, 80, 30), 'logPile': (120, 70, 40), 'birdbath': (180, 200, 220), 'doghouse': (170, 90, 40),
    'bikeShed': (110, 110, 160), 'pallets': (170, 140, 90), 'hammock': (230, 200, 120), 'swing': (200, 60, 120),
    'gazebo': (240, 240, 230), 'pond': (60, 120, 200), 'planter': (180, 120, 90), 'mailbox': (200, 40, 40),
    'lane': (98, 98, 104), 'verge': (120, 160, 90), 'clothesline': (255, 255, 255),
}
ORDER = ['lawn', 'meadow', 'gravel', 'patio', 'deck', 'poolDeck', 'driveway', 'parkingPad', 'kerbCut', 'frontPath', 'sidePath']


def bodies(g):
    """Every building's own footprint (front wall to back), as tools/town-graph.ts places it on its row."""
    out = []
    for r in g['rows']:
        pts = r['pts']; cum = [0.0]
        for a, b in zip(pts, pts[1:]): cum.append(cum[-1] + ((b[0] - a[0]) ** 2 + (b[1] - a[1]) ** 2) ** .5)
        for b in r['buildings']:
            s = min(max((b['x0'] + b['x1']) / 2, 0), cum[-1]); i = 0
            while i + 2 < len(pts) and s > cum[i + 1]: i += 1
            a, c = pts[i], pts[i + 1]; L = cum[i + 1] - cum[i]
            ux, uz = (c[0] - a[0]) / L, (c[1] - a[1]) / L; nx, nz = uz, -ux
            px, pz = a[0] + ux * (s - cum[i]), a[1] + uz * (s - cum[i])
            cs = [(px + ux * (u - s) + nx * d, pz + uz * (u - s) + nz * d) for u in (b['x0'], b['x1']) for d in (b['setback'], b['setback'] + b['depth'])]
            out.append([min(c[0] for c in cs), min(c[1] for c in cs), max(c[0] for c in cs), max(c[1] for c in cs)])
    return out


def main():
    src, out = sys.argv[1], sys.argv[2]
    sc = float(sys.argv[3]) if len(sys.argv) > 3 else 0.25
    g = json.load(open(src))['graph']
    xs = [p[0] for r in g['roads'] for p in r['pts']]; zs = [p[1] for r in g['roads'] for p in r['pts']]
    win = [float(v) for v in sys.argv[4:8]] if len(sys.argv) > 7 else [min(xs) - 600, min(zs) - 600, max(xs) + 600, max(zs) + 600]
    W, H = int((win[2] - win[0]) * sc), int((win[3] - win[1]) * sc)
    im = Image.new('RGB', (W, H), (70, 96, 60)); d = ImageDraw.Draw(im)
    P = lambda x, z: ((x - win[0]) * sc, (z - win[1]) * sc)
    def rect(r, fill=None, outline=None, width=1):
        a, b = P(r[0], r[1]), P(r[2], r[3])
        if b[0] - a[0] < 1 and b[1] - a[1] < 1 and fill: d.point(a, fill=fill); return
        d.rectangle([a, (max(a[0], b[0] - 1), max(a[1], b[1] - 1))], fill=fill, outline=outline, width=width)
    for p in (g['water'] or {}).get('polys', []):
        rect([p[0][0], p[0][1], p[2][0], p[2][1]], fill=(60, 110, 190))
    for r in g['roads']:
        for a, b in zip(r['pts'], r['pts'][1:]):
            h = r['width'] / 2
            rect([min(a[0], b[0]) - h, min(a[1], b[1]) - h, max(a[0], b[0]) + h, max(a[1], b[1]) + h], fill=(178, 176, 168))
        for a, b in zip(r['pts'], r['pts'][1:]):
            h = r['width'] / 2 - 38
            rect([min(a[0], b[0]) - h, min(a[1], b[1]) - h, max(a[0], b[0]) + h, max(a[1], b[1]) + h], fill=(64, 64, 70))
    for b in g['bridges']: rect(b['rect'], fill=(140, 120, 100))
    rect(g['plaza']['rect'], fill=(200, 190, 170))
    for ln in g.get('lanes', []): rect(ln['rect'], fill=COL.get(ln['kind'], (255, 0, 255)))
    for p in g['props']:
        x, z = P(p['x'], p['z']); rr = max(1, 9 * sc)
        d.ellipse([x - rr, z - rr, x + rr, z + rr], fill=(36, 70, 36) if p['kind'] == 'tree' else (90, 140, 70))
    yards = g.get('yards', [])
    for y in yards:
        rect(y['parcel'], fill=(84, 120, 70))
    for y in yards:
        items = sorted(y['items'], key=lambda i: (ORDER.index(i['kind']) if i['kind'] in ORDER else 99))
        for it in items:
            c = COL.get(it['kind'], (255, 0, 255))
            if it['kind'] in ('tree', 'fruitTree', 'bush'):
                r = it['rect']; d.ellipse([P(r[0], r[1]), P(r[2], r[3])], fill=c, outline=(20, 50, 20))
            else: rect(it['rect'], fill=c)
            if 'face' in it and it['kind'] in ('shed', 'garage', 'bench', 'greenhouse', 'doghouse', 'gazebo', 'carport', 'bikeShed'):
                r = it['rect']; cx, cz = (r[0] + r[2]) / 2, (r[1] + r[3]) / 2
                d.line([P(cx, cz), P(cx + it['face'][0] * 12, cz + it['face'][1] * 12)], fill=(255, 255, 255))
    for l in g['lots']: rect(l['rect'], outline=(255, 255, 255) if sc >= 0.5 else (200, 200, 200))
    for y in yards: rect(y['parcel'], outline=(255, 120, 255))
    for f in bodies(g): rect(f, fill=(60, 50, 60), outline=(0, 0, 0))
    for l in g['landmarks']: rect(l['rect'], outline=(255, 80, 80), width=2)
    im.save(out)
    print('wrote', out, im.size)


if __name__ == '__main__':
    main()
