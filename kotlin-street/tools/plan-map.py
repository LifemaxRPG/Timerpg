#!/usr/bin/env python3
"""Top-down 2D map pictures of town plans (kotlin-street/docs/TOWN_PLAN.md): coloured districts, streets, lots,
labelled buildings, rail, river, farmland. Reads the JSON of tools/plan-sketch.ts (the planned town), or of a dump of
today's generator (a JSON with `candidates`, see the doc), and draws them side by side on one sheet.

Usage: python3 kotlin-street/tools/plan-map.py out.jpg a.json [b.json ...] [--cols 2] [--width 1600]
"""
import json
import sys
from PIL import Image, ImageDraw, ImageFont

ZONE = {'core': '#ecc8bc', 'square': '#ddd3ee', 'civic': '#e4dcf2', 'old': '#f4e7c6', 'worker': '#ecdcc8',
        'rail': '#d9d9d9', 'strip': '#f8d9b0', 'postwar': '#dcecc8', 'late': '#cde7dc', 'heights': '#c5dfc0',
        'park': '#c2e0ae', 'river': '#d7e8ee'}
LOT = {'shop': '#b04a34', 'small': None, 'medium': None, 'house': None, 'villa': '#3f7b49', 'estate': '#2f6439',
       'apartment': '#86679c', 'mobile': '#a99a86', 'model': '#1d4fa8', 'store': '#c2185b', 'civic': '#6b4fa6',
       'civicbig': '#4a2d88', 'church': '#6b4fa6', 'parking': '#a7a7a7', 'tower': '#5b4136', 'warehouse': '#7a7a7a',
       'industry': '#505050', 'stripmall': '#e0783a', 'stripshop': '#eaa05c', 'park': '#97c97a', 'cemetery': '#a9c49a',
       'square': '#d8cfb2'}
HOME = {'old': '#c9963e', 'worker': '#a8865f', 'postwar': '#78ad55', 'late': '#4a9a82', 'heights': '#3f7b49',
        'core': '#c9963e', 'strip': '#86679c', 'civic': '#c9963e'}
ROAD = {'main': ('#3b3b3b', 2.6), 'highway': ('#3b3b3b', 2.6), 'street': ('#8a8a8a', 2.0), 'lane': ('#c4bba8', 1.0),
        'country': ('#a58c66', 2.0)}


def font(sz):
    for p in ('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', '/usr/share/fonts/dejavu/DejaVuSans.ttf'):
        try:
            return ImageFont.truetype(p, sz)
        except OSError:
            pass
    return ImageFont.load_default()


def from_current(d):
    """Today's generator (a dump of plan, layout, glb sites, rail, farms: see TOWN_PLAN.md) in the sketch's shape."""
    m = 60
    ox, oy = m, m
    mv = lambda r: {'x': r['x'] + ox, 'y': r['y'] + oy, 'w': r['w'], 'h': r['h']}
    built = {(r['x'], r['y'], r['w'], r['h']) for r in d['roads']}
    roads = [{'rect': mv(r), 'cls': 'main' if r['main'] else 'street',
              'from': 0 if (r['x'], r['y'], r['w'], r['h']) in built else 9999} for r in d['allRoads']]
    lots = [{'rect': mv(l), 'kind': ('shop' if l['kind'] == 'shop' else l['tier']), 'd': 'old', 'from': 0} for l in d['lots']]
    lots.append({'rect': mv(d['plaza']), 'kind': 'square', 'd': 'square', 'from': 0, 'label': 'Plaza'})
    if d.get('hall'):
        lots.append({'rect': mv(d['hall']), 'kind': 'civicbig', 'd': 'square', 'from': 0, 'label': 'Town hall'})
    if d.get('chapel'):
        lots.append({'rect': mv(d['chapel']), 'kind': 'church', 'd': 'civic', 'from': 0, 'label': 'Chapel'})
    names = {'post': 'Post', 'school': 'School', 'fire': 'Fire', 'police': 'Police', 'hotel': 'Hotel', 'clocktower': 'Clock',
             'cinema': 'Cinema', 'generalstore': 'General store', 'market': 'Market', 'supermarket': 'Supermarket',
             'superstore': 'Superstore', 'hospital': 'Hospital'}
    for l in d['landmarks']:
        k = 'store' if l['id'] in ('cinema', 'generalstore', 'market', 'supermarket', 'superstore') else 'civic'
        lots.append({'rect': mv(l), 'kind': k, 'd': 'civic', 'from': 0, 'label': names.get(l['id'], l['id']), 'model': k == 'store'})
    short = {'funeral_home': 'Funeral home', 'foundry_nightclub': 'The Foundry', 'gazette_office': 'Gazette',
             'water_tower': 'Water tower', 'modern_gas_station': 'Gas (2000s)', 'coin_laundromat': 'Laundry'}
    for g in d['glb']:
        lots.append({'rect': mv(g['rect']), 'kind': 'model', 'd': 'civic', 'from': 0, 'model': True,
                     'label': short.get(g['kind'][13:], g['name'])})
    if d['rail']['depot']:
        lots.append({'rect': mv(d['rail']['depot']), 'kind': 'model', 'd': 'rail', 'from': 0, 'model': True, 'label': 'Union Depot'})
    if d['rail']['elevator']:
        lots.append({'rect': mv(d['rail']['elevator']), 'kind': 'industry', 'd': 'rail', 'from': 0, 'label': '(mill site, empty)'})
    water = []
    cells = sorted(map(tuple, d['water']), key=lambda c: (c[1], c[0]))
    i = 0
    while i < len(cells):  # merge runs of a row
        x, y = cells[i]; j = i
        while j + 1 < len(cells) and cells[j + 1] == (cells[j][0] + 1, y):
            j += 1
        water.append({'x': x + ox, 'y': y + oy, 'w': j - i + 1, 'h': 1}); i = j + 1
    W, H = d['width'] + 2 * m, d['height'] + 2 * m
    inside = lambda r: r['x'] + r['w'] > 0 and r['y'] + r['h'] > 0 and r['x'] < W and r['y'] < H
    fields = [f for f in map(mv, d['farms']['fields']) if inside(f)]
    farms = [f for f in map(mv, [f for f in d['farms']['farms'] if f]) if inside(f)]
    tracks = [[[p['x'] + ox, p['y'] + oy] for p in t] for t in d['rail']['tracks']]
    for r in d['farms']['roads']:
        (ax, ay), (bx, by) = r['a'], r['b']
        rect = {'x': min(ax, bx) - 1 + ox, 'y': min(ay, by) - 1 + oy, 'w': abs(bx - ax) + 2, 'h': abs(by - ay) + 2}
        roads.append({'rect': rect, 'cls': 'country', 'from': 0})
    return {'title': f"{d['seed']} {d['year']}: today's generator", 'sub': f"{d['type']}; {d['people']} people. Models get leftover ground; the feed mill has no plot.",
            'year': d['year'], 'width': W, 'height': H, 'zones': [], 'roads': roads, 'lots': lots, 'water': water,
            'fields': fields, 'farms': farms, 'rail': {'tracks': tracks, 'from': 0}, 'labels': []}


def draw(s, pw, ph):
    """One map panel, [pw] x [ph] pixels."""
    img = Image.new('RGB', (pw, ph), '#f7f4ec')
    g = ImageDraw.Draw(img)
    head = 34
    sc = min((pw - 8) / s['width'], (ph - head - 4) / s['height'])
    ox = (pw - s['width'] * sc) / 2
    oy = head
    P = lambda x, y: (ox + x * sc, oy + y * sc)
    year = s['year']

    def box(r, fill=None, outline=None, width=1):
        x0, y0 = P(r['x'], r['y']); x1, y1 = P(r['x'] + r['w'], r['y'] + r['h'])
        g.rectangle([x0, y0, max(x0 + 1, x1 - 1), max(y0 + 1, y1 - 1)], fill=fill, outline=outline, width=width)

    def dashed(r, col):
        x0, y0 = P(r['x'], r['y']); x1, y1 = P(r['x'] + r['w'], r['y'] + r['h'])
        for (a, b, c, e) in ((x0, y0, x1, y0), (x1, y0, x1, y1), (x0, y1, x1, y1), (x0, y0, x0, y1)):
            n = max(1, int(max(abs(c - a), abs(e - b)) / 4))
            for k in range(0, n, 2):
                t0, t1 = k / n, min(1, (k + 1) / n)
                g.line([a + (c - a) * t0, b + (e - b) * t0, a + (c - a) * t1, b + (e - b) * t1], fill=col, width=1)

    # the woods (background), farmland, farmsteads
    g.rectangle([ox, oy, ox + s['width'] * sc, oy + s['height'] * sc], fill='#cfdcc0')
    for f in s['fields']:
        box(f, fill='#efe2ae', outline='#d9c98f')
    for f in s['farms']:
        box(f, fill='#d5bf8a', outline='#a88f5c')
    # districts: a district with nothing built yet is still farmland
    builtd = {l['d'] for l in s['lots'] if l['from'] <= year and l['kind'] not in ('park', 'cemetery')}
    for z in s['zones']:
        if z['d'] == 'river':
            continue
        if z['d'] in builtd or z['d'] in ('park',):
            box(z['rect'], fill=ZONE[z['d']])
        else:
            box(z['rect'], fill='#efe2ae', outline='#d9c98f')
    for w in s['water']:
        box(w, fill='#78b2d6')
    # streets (future ones dotted)
    for r in s['roads']:
        col, wid = ROAD[r['cls']]
        if r['from'] <= year:
            box(r['rect'], fill=col)
        elif r['cls'] != 'lane':
            dashed(r['rect'], '#b0a890')
    # the rail line
    rail = s.get('rail')
    if rail and rail.get('from', 0) <= year:
        lines = rail.get('tracks') or [rail['track']] + ([rail['siding']] if rail.get('siding') else [])
        for t in lines:
            pts = [P(x, y) for x, y in t]
            g.line(pts, fill='#2a2a2a', width=2)
            for (a, b), (c, e) in zip(pts, pts[1:]):
                L = max(1.0, ((c - a) ** 2 + (e - b) ** 2) ** 0.5)
                nx, ny = -(e - b) / L * 3, (c - a) / L * 3
                for k in range(0, int(L), 6):
                    x, y = a + (c - a) * k / L, b + (e - b) * k / L
                    g.line([x - nx, y - ny, x + nx, y + ny], fill='#2a2a2a', width=1)
    # lots: built ones filled, planned ones (reserved) dashed
    lab = []
    for l in sorted(s['lots'], key=lambda l: l['kind'] in ('park', 'cemetery', 'square', 'parking') and 0 or 1):
        k = l['kind']
        col = LOT.get(k) or HOME.get(l['d'], '#c9963e')
        standing = l['from'] <= year and (l.get('to') is None or year < l['to'])
        if standing:
            box(l['rect'], fill=col, outline='#ffffff' if k not in ('park', 'cemetery', 'square', 'parking') else None)
            if l.get('label'):
                lab.append((l, l['label'], '#111111'))
        elif l.get('to') is not None and year >= l['to']:
            if l.get('then'):  # the plot's next use once the model is gone
                box(l['rect'], fill=LOT['stripshop'], outline='#ffffff')
                lab.append((l, f"{l['then']} (was {l['label'].lower()})", '#111111'))
            continue
        elif l.get('model') or k in ('store', 'civic', 'civicbig', 'stripmall'):
            box(l['rect'], fill='#efe2ae' if l.get('before') in ('field', None, 'reserved') else '#e5ddcc')
            dashed(l['rect'], col)
            if l.get('label') and l['from'] < 9999:
                lab.append((l, f"{l['label']} ({l['from']})", '#555555'))
    f = font(9)
    taken = []
    for l, t, c in sorted(lab, key=lambda q: (q[2] != '#111111', not q[0].get('model'))):
        r = l['rect']
        cx, cy = P(r['x'] + r['w'] / 2, r['y'] + r['h'] / 2)
        tw = g.textlength(t, font=f)
        for dx, dy in ((0, 0), (0, -11), (0, 11), (0, -22), (0, 22), (tw / 2 + 8, 0), (-tw / 2 - 8, 0)):
            x, y = cx + dx, cy + dy
            bb = (x - tw / 2 - 1, y - 5, x + tw / 2 + 1, y + 6)
            if not any(bb[0] < q[2] and q[0] < bb[2] and bb[1] < q[3] and q[1] < bb[3] for q in taken):
                break
        else:
            continue
        taken.append(bb)
        g.rectangle(bb, fill='#ffffff' if c == '#111111' else '#fffbe8', outline='#999999' if (dx or dy) else None)
        g.text((x - tw / 2, y - 5), t, fill=c, font=f)
    g.text((6, 2), s['title'], fill='#111', font=font(14))
    g.text((6, 19), s['sub'], fill='#444', font=font(10))
    # a 100-tile (210 m) scale bar
    x0, y0 = pw - 10 - 100 * sc, ph - 10
    g.line([x0, y0, pw - 10, y0], fill='#222', width=2)
    g.text((x0, y0 - 13), '100 tiles = 210 m', fill='#222', font=font(9))
    return img


def legend(pw, ph):
    img = Image.new('RGB', (pw, ph), '#f7f4ec')
    g = ImageDraw.Draw(img)
    f = font(12)
    g.text((10, 6), 'Legend', fill='#111', font=font(15))
    items = [('District: downtown core', ZONE['core']), ('Civic square / plaza block', ZONE['square']),
             ('Old neighbourhood (pre-1940)', ZONE['old']), ('Across the tracks (worker houses)', ZONE['worker']),
             ('Rail side (depot, mill, works)', ZONE['rail']), ('Highway strip (post-war)', ZONE['strip']),
             ('Post-war subdivision (1950-70)', ZONE['postwar']), ('Later subdivision (1975-2020)', ZONE['late']),
             ('Heights (villas, estates)', ZONE['heights']), ('Park, river bank, cemetery', ZONE['park']),
             ('Farmland / not yet platted', '#efe2ae'), ('Woods', '#cfdcc0')]
    items2 = [('Main Street shop (street wall)', LOT['shop']), ('Home (colour = its district)', HOME['old']),
              ('Apartments', LOT['apartment']), ('Hand-made model (labelled)', LOT['model']),
              ('Store / cinema', LOT['store']), ('Civic building', LOT['civic']), ('Strip centre / strip shop', LOT['stripmall']),
              ('Car park (renewal, strip)', LOT['parking']), ('Warehouse / elevator', LOT['warehouse']),
              ('Tower', LOT['tower'])]
    y = 30
    for t, c in items:
        g.rectangle([10, y, 30, y + 13], fill=c, outline='#999'); g.text((38, y), t, fill='#222', font=f); y += 19
    y2 = 30
    for t, c in items2:
        g.rectangle([pw // 2, y2, pw // 2 + 20, y2 + 13], fill=c, outline='#fff'); g.text((pw // 2 + 28, y2), t, fill='#222', font=f); y2 += 19
    y = max(y, y2) + 6
    for t in ['Dashed outline + (year): a plot reserved in the plan for a later building;',
              '  before then it is held as a field, an empty lot or an older use.',
              'Dotted street: platted later (not built in this year).',
              'Black line with ticks: the railroad; Main Street is the dark street.',
              'All are desktop renders of plan data, not game screenshots.']:
        g.text((10, y), t, fill='#333', font=font(11)); y += 16
    return img


def main():
    args = sys.argv[1:]
    cols = 2
    width = 1600
    if '--cols' in args:
        i = args.index('--cols'); cols = int(args[i + 1]); del args[i:i + 2]
    if '--width' in args:
        i = args.index('--width'); width = int(args[i + 1]); del args[i:i + 2]
    out, files = args[0], args[1:]
    sketches = []
    for p in files:
        d = json.load(open(p))
        sketches.append(from_current(d) if 'candidates' in d else d)
    pw = width // cols
    ph = int(pw * 0.68)
    panels = [draw(s, pw, ph) for s in sketches]
    if len(panels) % cols:
        panels.append(legend(pw, ph))
    rows = (len(panels) + cols - 1) // cols
    sheet = Image.new('RGB', (pw * cols, ph * rows), '#ffffff')
    for i, p in enumerate(panels):
        sheet.paste(p, ((i % cols) * pw, (i // cols) * ph))
        ImageDraw.Draw(sheet).rectangle([(i % cols) * pw, (i // cols) * ph, (i % cols + 1) * pw - 1, (i // cols + 1) * ph - 1], outline='#bbbbbb')
    sheet.save(out, quality=88)
    print(out, sheet.size)


if __name__ == '__main__':
    main()
