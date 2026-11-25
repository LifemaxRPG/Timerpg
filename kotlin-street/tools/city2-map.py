#!/usr/bin/env python3
"""The second city's top-down map (kotlin-street/docs/CITY2.md) from tools/city2-dump.ts's output:
the ground (hypsometric tint, hillshade, 20 m contours), the sea, the river and rail, the districts, every road by class
with its name, structures (bridges, viaducts, tunnels, the elevated Bayway), the loop highlighted, the landmarks
numbered, a legend and a scale bar.

    python3 kotlin-street/tools/city2-map.py <dump dir> <out.png> [title]
"""
import json
import math
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

src, dst = sys.argv[1], sys.argv[2]
title = sys.argv[3] if len(sys.argv) > 3 else 'City 2 - road network draft'
d = json.load(open(f'{src}/city2.json'))
fr = d['frame']
nx, nz, G = fr['nx'], fr['nz'], fr['grid']
h = np.fromfile(f'{src}/ground.f32', dtype=np.float32).reshape(nz, nx)

M = 2.2  # metres a pixel
W = int((fr['x1'] - fr['x0']) / M)
H = int((fr['z1'] - fr['z0']) / M)
PANEL = 560


def px(x, z):
    return ((x - fr['x0']) / M, (z - fr['z0']) / M)


# ---- the ground
hi = np.array(Image.fromarray(h).resize((W, H), Image.BILINEAR))
gz, gx = np.gradient(hi, M)
sun = np.array([-1.0, -1.0, 1.4])
sun /= np.linalg.norm(sun)
nrm = np.dstack([-gx, -gz, np.ones_like(hi)])
nrm /= np.linalg.norm(nrm, axis=2, keepdims=True)
shade = np.clip((nrm @ sun), 0, 1)
stops = [(-60, (40, 78, 120)), (-0.01, (92, 150, 190)), (0, (226, 214, 170)), (6, (176, 196, 138)), (40, (150, 182, 116)),
         (100, (170, 170, 110)), (180, (160, 140, 104)), (260, (150, 128, 110)), (400, (210, 205, 200))]
rgb = np.zeros((H, W, 3))
for c in range(3):
    rgb[..., c] = np.interp(hi, [s[0] for s in stops], [s[1][c] for s in stops])
land = hi > 0
rgb[land] *= (0.62 + 0.5 * shade[land])[:, None]
# contours every 20 m
cont = (np.floor(hi / 20) != np.floor(np.roll(hi, 1, 0) / 20)) | (np.floor(hi / 20) != np.floor(np.roll(hi, 1, 1) / 20))
rgb[cont & land] *= 0.82
img = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8)).convert('RGBA')

try:
    F = lambda s, b=False: ImageFont.truetype(f'/usr/share/fonts/truetype/dejavu/DejaVuSans{"-Bold" if b else ""}.ttf', s)
    f12, f14, f16, f20, f28, f40 = F(12), F(14), F(16, True), F(20, True), F(28, True), F(40, True)
except Exception:
    f12 = f14 = f16 = f20 = f28 = f40 = ImageFont.load_default()

# ---- districts
over = Image.new('RGBA', img.size, (0, 0, 0, 0))
od = ImageDraw.Draw(over)
DCOL = {'harbour': (60, 90, 160), 'oldtown': (170, 80, 60), 'mills': (120, 90, 70), 'newtown': (200, 150, 60), 'strand': (230, 120, 160),
        'fellside': (110, 160, 90), 'crown': (150, 90, 170), 'ridge': (70, 140, 120), 'eastcove': (90, 110, 130), 'head': (90, 130, 70), 'uplands': (150, 150, 90)}
for di in d['districts']:
    c = DCOL.get(di['id'], (128, 128, 128))
    pts = [px(*p) for p in di['poly']]
    od.polygon(pts, fill=c + (46,), outline=c + (200,))
img = Image.alpha_composite(img, over)
dr = ImageDraw.Draw(img)

# ---- river and rail
dr.line([px(*p['at']) for p in d['river']], fill=(70, 130, 190), width=9, joint='curve')
rail = [px(*p) for p in d['rail']]
dr.line(rail, fill=(60, 60, 60), width=5, joint='curve')
for i in range(len(rail) - 1):
    (x0, y0), (x1, y1) = rail[i], rail[i + 1]
    L = math.hypot(x1 - x0, y1 - y0)
    for t in np.arange(0, L, 14):
        a = t / L
        cx, cy = x0 + (x1 - x0) * a, y0 + (y1 - y0) * a
        ux, uy = (y1 - y0) / L * 5, -(x1 - x0) / L * 5
        dr.line([(cx - ux, cy - uy), (cx + ux, cy + uy)], fill=(60, 60, 60), width=2)

# ---- streets (New Town grid)
for s in d['streets']:
    dr.line([px(*s['a']), px(*s['b'])], fill=(250, 250, 245), width=4)

# ---- roads
CLS = {'boulevard': ((255, 255, 255), 11), 'arterial': ((255, 226, 120), 9), 'expressway': ((255, 170, 80), 11), 'scenic': ((255, 240, 200), 7),
       'hill': ((255, 240, 200), 6), 'street': ((250, 250, 245), 6), 'lane': ((240, 230, 215), 4), 'quay': ((250, 250, 245), 8), 'ramp': ((255, 190, 110), 5)}
roads = d['roads']
# the loop's glow first
for r in roads:
    if r['loop'] is not None:
        dr.line([px(p[0], p[1]) for p in r['pts']], fill=(230, 40, 40, 255), width=CLS[r['cls']][1] + 12, joint='curve')


def span_of(r, i):
    s = i * 4
    for sp in r['spans']:
        if sp['from'] <= s <= sp['to']:
            return sp['kind']
    return None


for r in sorted(roads, key=lambda r: r['cls'] == 'expressway'):
    col, w = CLS[r['cls']]
    pts = [px(p[0], p[1]) for p in r['pts']]
    dr.line(pts, fill=(70, 60, 50), width=w + 4, joint='curve')
    dr.line(pts, fill=col, width=w, joint='curve')
    # structures
    for i in range(len(pts) - 1):
        k = span_of(r, i)
        if k == 'tunnel':
            if (i // 4) % 2 == 0:
                dr.line([pts[i], pts[i + 1]], fill=(120, 110, 100), width=w + 4)
            else:
                dr.line([pts[i], pts[i + 1]], fill=(235, 230, 220), width=w)
        elif k in ('bridge', 'viaduct', 'elevated'):
            (x0, y0), (x1, y1) = pts[i], pts[i + 1]
            L = math.hypot(x1 - x0, y1 - y0) or 1
            nx_, ny_ = -(y1 - y0) / L * (w / 2 + 3), (x1 - x0) / L * (w / 2 + 3)
            for sgn in (1, -1):
                dr.line([(x0 + nx_ * sgn, y0 + ny_ * sgn), (x1 + nx_ * sgn, y1 + ny_ * sgn)], fill=(20, 20, 20), width=2)
    if r['gone']:
        for i in range(0, len(pts) - 1, 6):
            dr.line([pts[i], pts[min(i + 2, len(pts) - 1)]], fill=(170, 90, 40), width=3)

# junctions
for k, j in d['junctions'].items():
    x, y = px(*j['at'])
    if j['kind'] == 'roundabout':
        rr = j['r'] / M + 4
        dr.ellipse([x - rr, y - rr, x + rr, y + rr], fill=(250, 250, 245), outline=(70, 60, 50), width=3)
        dr.ellipse([x - rr / 2, y - rr / 2, x + rr / 2, y + rr / 2], fill=(130, 180, 110))
    elif j['kind'] == 'interchange':
        dr.ellipse([x - 16, y - 16, x + 16, y + 16], outline=(200, 110, 40), width=4)


# ---- labels: road names along the road
def label_along(text, pts, font, at=0.5, fill=(20, 20, 20), halo=(255, 255, 255)):
    n = len(pts)
    i = max(1, min(n - 2, int(n * at)))
    (x0, y0), (x1, y1) = pts[i - 1], pts[min(n - 1, i + 3)]
    ang = math.degrees(math.atan2(-(y1 - y0), x1 - x0))
    if ang > 90: ang -= 180
    if ang < -90: ang += 180
    tw, th = dr.textbbox((0, 0), text, font=font)[2:]
    t = Image.new('RGBA', (tw + 8, th + 8), (0, 0, 0, 0))
    td = ImageDraw.Draw(t)
    td.text((4, 4), text, font=font, fill=fill, stroke_width=3, stroke_fill=halo)
    t = t.rotate(ang, expand=True, resample=Image.BICUBIC)
    cx, cy = pts[i]
    img.alpha_composite(t, (int(cx - t.width / 2), int(cy - t.height / 2)))


AT = {'esplanade': .55, 'fellside': .45, 'crown': .2, 'ridge': .4, 'neckhill': .35, 'harbour': .3, 'head': .42, 'tunnel': .5, 'bayway': .3, 'pass': .6, 'coastwest': .6, 'steep': .5, 'crownlane': .5}
for r in roads:
    pts = [px(p[0], p[1]) for p in r['pts']]
    nm = r['name'] + (f" ({r['built']}-{r['gone']})" if r['gone'] else '')
    label_along(nm, pts, f16 if r['cls'] not in ('lane',) else f14, AT.get(r['id'], .5))
seen = set()
for s in d['streets']:
    if s['name'] in seen: continue
    seen.add(s['name'])
    label_along(s['name'], [px(*s['a']), px(*s['b'])], f12, .5, fill=(70, 60, 50))

# district names
for di in d['districts']:
    xs = [p[0] for p in di['poly']]; zs = [p[1] for p in di['poly']]
    x, y = px(sum(xs) / len(xs), sum(zs) / len(zs))
    c = DCOL.get(di['id'], (90, 90, 90))
    txt = di['name'].upper()
    tw = dr.textbbox((0, 0), txt, font=f20)[2]
    dr.text((x - tw / 2, y - 12), txt, font=f20, fill=tuple(int(v * .6) for v in c), stroke_width=3, stroke_fill=(255, 255, 255))
    sub = f"from {di['from']}"
    tw2 = dr.textbbox((0, 0), sub, font=f12)[2]
    dr.text((x - tw2 / 2, y + 12), sub, font=f12, fill=(60, 60, 60), stroke_width=2, stroke_fill=(255, 255, 255))

# junction names
for k, j in d['junctions'].items():
    if j['kind'] == 'end':
        continue
    x, y = px(*j['at'])
    dr.text((x + 14, y - 22), j['name'], font=f12, fill=(40, 30, 30), stroke_width=2, stroke_fill=(255, 255, 230))

# water names
for name, (x, z) in {'THE BELL (the bay)': (-300, 1700), 'Long Strand': (-2150, 1800), 'Sallow': (-1270, -2100), 'Eastcove': (2550, 650), 'Lighthouse Point': (1380, 3000)}.items():
    X, Y = px(x, z)
    dr.text((X, Y), name, font=f20 if 'BELL' in name else f14, fill=(230, 240, 255) if 'BELL' in name else (30, 60, 110), stroke_width=2, stroke_fill=(40, 80, 130) if 'BELL' in name else (230, 240, 255))

# landmarks
for n, l in enumerate(d['landmarks'], 1):
    x, y = px(*l['at'])
    dr.ellipse([x - 13, y - 13, x + 13, y + 13], fill=(30, 30, 40), outline=(255, 220, 90), width=3)
    t = str(n)
    tw, th = dr.textbbox((0, 0), t, font=f14)[2:]
    dr.text((x - tw / 2, y - th / 2 - 2), t, font=f14, fill=(255, 220, 90))

# ---- the panel: title, legend, scale, the loop's numbers
canvas = Image.new('RGBA', (W + PANEL, H), (246, 242, 232, 255))
canvas.alpha_composite(img, (0, 0))
cd = ImageDraw.Draw(canvas)
X = W + 24
y = 24
cd.text((X, y), title, font=f28, fill=(30, 30, 30)); y += 44
cd.text((X, y), 'Top-down design map (prototype data, not a render).', font=f14, fill=(80, 80, 80)); y += 22
cd.text((X, y), 'North up; 1 px = %.1f m. Heights from the site sketch.' % M, font=f14, fill=(80, 80, 80)); y += 34

cd.text((X, y), 'The loop (red glow)', font=f20, fill=(180, 30, 30)); y += 30
total = 0
for r in sorted([r for r in roads if r['loop'] is not None], key=lambda r: r['loop']):
    total += r['length']
    slow = min(c['kmh'] for c in r['corners']) if r['corners'] else 0
    cd.text((X, y), f"{r['loop']}. {r['name']}", font=f16, fill=(30, 30, 30))
    cd.text((X + 250, y + 2), f"{r['length'] / 1000:.2f} km  max {r['maxGrade'] * 100:.0f}%  slowest {slow} km/h", font=f12, fill=(60, 60, 60))
    y += 24
cd.text((X, y), f'Loop length {total / 1000:.1f} km', font=f16, fill=(30, 30, 30)); y += 22
cd.text((X, y), '(corner speeds: race Torrel estimate from its grip)', font=f12, fill=(90, 90, 90)); y += 34

cd.text((X, y), 'Roads', font=f20, fill=(30, 30, 30)); y += 30
for lab, key in [('Boulevard (Esplanade)', 'boulevard'), ('Arterial', 'arterial'), ('Expressway / tunnel', 'expressway'), ('Scenic / hill road', 'scenic'), ('Street, quay', 'street'), ('Old town lane', 'lane')]:
    col, w = CLS[key]
    cd.line([(X, y + 9), (X + 60, y + 9)], fill=(70, 60, 50), width=w + 4)
    cd.line([(X, y + 9), (X + 60, y + 9)], fill=col, width=w)
    cd.text((X + 74, y), lab, font=f14, fill=(30, 30, 30)); y += 26
cd.line([(X, y + 9), (X + 60, y + 9)], fill=(250, 250, 245), width=4); cd.text((X + 74, y), 'New Town grid (generated from the seed)', font=f14, fill=(30, 30, 30)); y += 26
cd.line([(X, y + 3), (X + 60, y + 3)], fill=(20, 20, 20), width=2); cd.line([(X, y + 15), (X + 60, y + 15)], fill=(20, 20, 20), width=2)
cd.text((X + 74, y), 'Bridge, viaduct, elevated', font=f14, fill=(30, 30, 30)); y += 26
for i in range(4):
    cd.line([(X + i * 15, y + 9), (X + i * 15 + 7, y + 9)], fill=(120, 110, 100), width=10)
cd.text((X + 74, y), 'Tunnel', font=f14, fill=(30, 30, 30)); y += 26
cd.line([(X, y + 9), (X + 60, y + 9)], fill=(60, 60, 60), width=5); cd.text((X + 74, y), 'Railway', font=f14, fill=(30, 30, 30)); y += 26
cd.line([(X, y + 9), (X + 60, y + 9)], fill=(70, 130, 190), width=9); cd.text((X + 74, y), 'River Sallow', font=f14, fill=(30, 30, 30)); y += 36

cd.text((X, y), 'Landmarks', font=f20, fill=(30, 30, 30)); y += 30
for n, l in enumerate(d['landmarks'], 1):
    cd.ellipse([X, y, X + 22, y + 22], fill=(30, 30, 40), outline=(255, 220, 90), width=2)
    t = str(n); tw = cd.textbbox((0, 0), t, font=f12)[2]
    cd.text((X + 11 - tw / 2, y + 4), t, font=f12, fill=(255, 220, 90))
    span = f"{l['from']}" + (f"-{l['to']}" if l.get('to') else '')
    cd.text((X + 32, y + 3), f"{l['name']} ({span})", font=f14, fill=(30, 30, 30)); y += 27

# scale bar and north arrow (on the map, bottom left)
sx, sy = 40, H - 70
km = 1000 / M
cd.rectangle([sx - 10, sy - 34, sx + 2 * km + 40, sy + 30], fill=(255, 255, 255, 210))
for i in range(2):
    cd.rectangle([sx + i * km, sy, sx + (i + 1) * km, sy + 12], fill=(30, 30, 30) if i % 2 == 0 else (255, 255, 255), outline=(30, 30, 30))
for i, t in enumerate(['0', '1 km', '2 km']):
    cd.text((sx + i * km - 8, sy - 24), t, font=f14, fill=(30, 30, 30))
ax, ay = 70, 90
cd.polygon([(ax, ay - 40), (ax - 16, ay + 10), (ax, ay), (ax + 16, ay + 10)], fill=(30, 30, 30))
cd.text((ax - 8, ay + 14), 'N', font=f20, fill=(30, 30, 30))

canvas.convert('RGB').save(dst, optimize=True)
print(f'{dst}: {canvas.width}x{canvas.height}')
