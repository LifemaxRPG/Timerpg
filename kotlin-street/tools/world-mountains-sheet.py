#!/usr/bin/env python3
"""Lays world-mountains-map.ts's maps and a preview shot on one captioned contact sheet.

Usage: python3 kotlin-street/tools/world-mountains-sheet.py <mapdir> <preview.png> <out.jpg> [preview caption]
"""
import json
import sys
import textwrap
from PIL import Image, ImageDraw, ImageFont

mapdir, shot, out = sys.argv[1], sys.argv[2], sys.argv[3]
shot_caption = sys.argv[4] if len(sys.argv) > 4 else 'world preview'
L = json.load(open(f'{mapdir}/labels.json'))


def font(size):
    for f in ('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', '/usr/share/fonts/dejavu/DejaVuSans.ttf'):
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            pass
    return ImageFont.load_default()


F, FS, FT = font(17), font(13), font(22)
PW, PH = 928, 464  # a planet panel (half the 2-cell maps)
sheet = Image.new('RGB', (PW * 2 + 30, 40 + (PH + 75) + (600 + 30)), (250, 250, 248))
d = ImageDraw.Draw(sheet)
d.text((10, 8), f"World generator, seed {L['seed']}: grand mountains, natural wonders, winding roads (desktop renders and maps, not S25 captures)", fill=(0, 0, 0), font=FT)

# 1. the planet's height with the great ranges
ph = Image.open(f'{mapdir}/planet_height.png').resize((PW, PH))
x0, y0 = 10, 40
sheet.paste(ph, (x0, y0))
s = PW / (ph.width if ph.width else PW)
k = PW / Image.open(f'{mapdir}/planet_height.png').width
for p in L['planet']['peaks']:
    if p['cls'] == 'great':
        d.text((min(x0 + p['x'] * k + 8, x0 + PW - 70), y0 + p['y'] * k - 8), f"{p['peakM']:,} m", fill=(255, 255, 255), font=F, stroke_width=2, stroke_fill=(120, 0, 0))
c = L['planet']['capital']
d.text((x0 + c['x'] * k + 8, y0 + c['y'] * k - 8), 'capital', fill=(0, 0, 0), font=F, stroke_width=2, stroke_fill=(255, 220, 0))
d.text((x0, y0 + PH + 4), 'Planet height (2,000 x 1,000 km, wraps east-west). Red outline: the great ranges (their own term over 1,500 m); red dots their highest peaks,',
       fill=(0, 0, 0), font=FS)
d.text((x0, y0 + PH + 20), 'purple dots the middle ranges\' peaks; white: above the snow line. The capital (yellow) keeps 150 km or more from any great range.', fill=(0, 0, 0), font=FS)

# 2. the wonders
wm = Image.open(f'{mapdir}/wonders.png').resize((PW, PH))
x1 = 20 + PW
sheet.paste(wm, (x1, y0))
k2 = PW / Image.open(f'{mapdir}/wonders.png').width
for q in L['wonders']['legendary']:
    d.text((x1 + q['x'] * k2 + 12, y0 + q['y'] * k2 - 9), q['name'], fill=(0, 0, 0), font=F, stroke_width=2, stroke_fill=(255, 215, 0))
cols, counts = L['wonders']['colours'], L['wonders']['counts']
lx, ly = x1, y0 + PH + 4
for i, (kind, col) in enumerate(cols.items()):
    cx, cy = lx + (i % 7) * 132, ly + (i // 7) * 16
    n = sum(counts.get(kind, {}).values())
    d.rectangle((cx, cy + 3, cx + 9, cy + 12), fill=tuple(col), outline=(0, 0, 0))
    d.text((cx + 12, cy), f'{kind} {n}', fill=(0, 0, 0), font=font(11))
tiers = {}
for kc in counts.values():
    for t, n in kc.items():
        tiers[t] = tiers.get(t, 0) + n
d.text((x1, ly + 50), 'Wonders by kind and tier: dot common, bigger dot uncommon, ringed rare, gold legendary: ' +
       ', '.join(f'{t} {tiers.get(t, 0)}' for t in ('common', 'uncommon', 'rare', 'legendary')), fill=(0, 0, 0), font=FS)

# 3. the preview shot
y1 = y0 + PH + 75
pv = Image.open(shot)
pv = pv.resize((PW, int(pv.height * PW / pv.width)))
pv = pv.crop((0, 0, PW, min(pv.height, 600)))
sheet.paste(pv, (x0, y1))
for i, t in enumerate(textwrap.wrap(shot_caption, 120)):
    d.text((x0, y1 + pv.height + 4 + i * 17), t, fill=(0, 0, 0), font=FS)

# 4. the close-up
cu = Image.open(f'{mapdir}/road_closeup.png')
sheet.paste(cu, (x1, y1))
C = L['closeup']
for p in C['places']:
    d.text((x1 + p['x'] + 9, y1 + p['y'] - 8), p['name'], fill=(255, 255, 255), font=F, stroke_width=2, stroke_fill=(0, 0, 0))
for q in C['wonders']:
    d.text((x1 + q['x'] + 9, y1 + q['y'] - 8), f"{q['name']} ({q['kind']})", fill=(255, 255, 255), font=FS, stroke_width=2, stroke_fill=(120, 0, 80))
tx = x1 + 610
lines = [
    'Close-up, %d km across:' % C['widthKm'],
    f"{C['village']} ({C['setting']}, {C['villageM']:,} m)",
    f"its county road {C['road']}: {C['km']} km,",
    f"climbs {C['climbM']:,} m, {C['switchbacks']} hairpins,",
    f"steepest grade {C['maxGrade'] * 100:.1f}% (limit 10%).",
    '',
    'White: county roads (thick: this one),',
    'dark: highways, blue: rivers;',
    'shaded relief of the near terrain',
    '(near.ts) with 100 m contours.',
    'Roads are routed on 134 m nodes',
    'on the calm ground they are laid on;',
    'the knolls cost, so they wind round.',
]
for i, t in enumerate(lines):
    d.text((tx, y1 + i * 20), t, fill=(0, 0, 0), font=FS)
sheet.save(out, quality=88)
print(out, sheet.size)
