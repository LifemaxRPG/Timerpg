#!/usr/bin/env python3
"""Top-down map of a baked world town (tools/towns-bake.ts --keep): the whole ground (the town and the farm ring), its
streets, lots, landmarks, water, the farms' roads and fields, and the world's own rivers, roads and rail lines laid over
it through the town's frame (tools/towns-site.ts), with the road entries marked: for checking by eye that the world and
the town meet. Usage: python3 towns-map.py <bake out> <place file id> out.png [px per tile, default 0.6]"""
import json, math, sys
from PIL import Image, ImageDraw

TILE_M = 2.1


def to_plan(f, x, z):
    dx, dz = x - f['world'][0], z - f['world'][1]
    t = math.radians(f['deg']); c, s = math.cos(t), math.sin(t)
    return f['plan'][0] + (dx * c + dz * s) / TILE_M, f['plan'][1] + (-dx * s + dz * c) / TILE_M


def main():
    out_dir, fid, png = sys.argv[1], sys.argv[2], sys.argv[3]
    px = float(sys.argv[4]) if len(sys.argv) > 4 else 0.6
    row = json.load(open(f'{out_dir}/rows/{fid}.json'))
    site = json.load(open(f'{out_dir}/sites/{fid}.json'))
    g = json.load(open(f'{out_dir}/work/{fid}/street.json'))['graph']
    sc = g['scale']  # street units a tile
    gr = row['ground']
    W, H = int((gr['x1'] - gr['x0']) * px), int((gr['y1'] - gr['y0']) * px)
    im = Image.new('RGB', (W, H), (78, 104, 64)); d = ImageDraw.Draw(im)
    T = lambda x, y: ((x - gr['x0']) * px, (y - gr['y0']) * px)          # plan tiles
    U = lambda x, z: T(x / sc, z / sc)                                     # street units

    def box(r, **kw):
        a, b = U(r[0], r[1]), U(r[2], r[3])
        d.rectangle([a, (max(a[0], b[0]), max(a[1], b[1]))], **kw)
    farms = g.get('farms') or {}
    crop = {'corn': (196, 180, 70), 'wheat': (214, 190, 110), 'hay': (150, 170, 80)}
    for f in farms.get('fields', []):
        if f.get('sold') is not None and f['sold'] <= 2025: continue
        box(f['rect'], fill=crop.get(f['crop'], (120, 150, 70)))
    for f in farms.get('farms', []):
        box(f['rect'], fill=(150, 130, 100))
    for r in farms.get('roads', []):
        d.line([U(*p) for p in r['pts']], fill=(150, 150, 150), width=max(1, int(r['width'] / sc * px)))
    for p in (g.get('water') or {}).get('polys', []):
        d.polygon([U(*q) for q in p], fill=(60, 110, 190))
    for r in g['roads']:
        d.line([U(*p) for p in r['pts']], fill=(225, 225, 225) if r['main'] else (175, 175, 175), width=max(1, int(r['width'] / sc * px)))
    for l in g['lots']:
        box(l['rect'], fill=(110, 170, 95), outline=(60, 90, 50))
    for l in g.get('landmarks', []):
        box(l['rect'], fill=(200, 120, 90))
    if g.get('plaza'): box(g['plaza']['rect'], fill=(210, 200, 170))
    # the world's vectors through the town's frame
    col = {'river': (40, 220, 255), 'highway': (255, 60, 60), 'county': (255, 150, 60), 'rail': (20, 20, 20)}
    for p in site['polys']:
        pts = [T(*to_plan(row['frame'], x, z)) for x, z in p['pts']]
        d.line(pts, fill=col[p['kind']], width=2)
    for e in row.get('entries', []):
        s = e['side']
        for at, c in ((e['worldAt'], (255, 0, 255)), (e['at'], (255, 255, 0))):
            x, y = (at, gr['y0']) if s == 'N' else (at, gr['y1']) if s == 'S' else (gr['x0'], at) if s == 'W' else (gr['x1'], at)
            q = T(x, y); d.ellipse([q[0] - 5, q[1] - 5, q[0] + 5, q[1] + 5], outline=c, width=2)
    # the built town's outline
    d.rectangle([T(0, 0), T(gr['x1'] - 100, gr['y1'] - 100)], outline=(255, 255, 255))
    d.text((6, 4), f"{row['name']} ({row['id']}) {row['rank']} {row.get('setting') or row['type']}, {row['sizeM'][0]} x {row['sizeM'][1]} m, {row['frame']['deg']} deg, "
           f"{row['height']['m']} m, relief {row['height']['relief']} m", fill=(255, 255, 255))
    im.save(png)


main()
