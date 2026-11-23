#!/usr/bin/env python3
"""The intake job's data step (docs/LANDMARKS_GLB.md "The intake models"): from the contributors' model-info files (copied with
`git show`, in the landmark folders of app/src/main/assets/landmarks/) it writes one flat model-info per state in the shape the
loader reads (top-level `doors` with `external`, `collision.boxes_gltf_y_up`), and prints the catalogue entries and the
generator's specs for the models. Run from kotlin-street/:  python3 tools/landmarks/intake.py [--write]
Lots are the .glb's bounds (everything drawn, x and z); a chain of states that differ in size shares the biggest plot (`plot`)
and the first state's middle (`centre`), so the building does not move between states."""
import json, os, sys, copy
sys.path.insert(0, os.path.dirname(__file__))
import glbinfo

A = 'app/src/main/assets/landmarks'
R = lambda p: json.load(open(os.path.join(A, p)))

def boxes_min_max(c):
    out = []
    for b in c['boxes_gltf_y_up']:
        lo, hi = b['min_gltf_m'], b['max_gltf_m']
        out.append({'source': b.get('label', 'box'), 'group': 'lot' if b.get('label', '').startswith(('flag', 'hydrant', 'fence', 'rear', 'bin')) and False else 'structure',
                    'attachment': 'structure', 'center': [(lo[i] + hi[i]) / 2 for i in range(3)], 'size': [hi[i] - lo[i] for i in range(3)]})
    return out

def norm_collision(c):
    c = copy.deepcopy(c)
    bx = c.get('boxes_gltf_y_up')
    if bx and 'min_gltf_m' in bx[0]:
        c['boxes_gltf_y_up'] = boxes_min_max(c)
    return c

def doors_list(d, internal=(), external=()):
    if isinstance(d, dict):
        d = [{'node': k, 'position': v.get('translation_gltf') if isinstance(v, dict) else v, 'external': True} for k, v in d.items()]
    elif d is None:
        d = []
    d = copy.deepcopy(d)
    for x in d:
        if x['node'] in internal: x['external'] = False
        if x['node'] in external: x['external'] = True
    return d

# one row per state: kind, slug, glb, where its info comes from, the years it stands from (era.from..to; built the sample year), the business
S = []
def add(kind, slug, glb, info, name, role, era, room='hall', **kw): S.append(dict(kind=kind, slug=slug, glb=glb, info=info, name=name, role=role, era=era, room=room, **kw))

# info: (file, key path) -- 'state:<k>' picks states[k] of a list by its `state` field, 'file' = the file itself
add('landmark_glb_public_library_1912', 'public-library', 'public-library-1912.glb', ('model-info.json', 'state', '1912'), 'Public Library', 'library', (1905, 1920, 1912))
add('landmark_glb_public_library_1970s', 'public-library', 'public-library-1970s.glb', ('model-info.json', 'state', '1970s'), 'Public Library', 'library', (1965, 2040, 1975))
for st, (a, b, c) in {'family': (1920, 1980, 1950), 'retail': (1974, 2010, 1990), 'closed': (2004, 2040, 2012), 'cafe': (2012, 2040, 2016)}.items():
    add(f'landmark_glb_corner_drugstore_{st}', 'corner-drugstore', f'corner-drugstore-{st}.glb', ('model-info.json', 'state', st), 'Corner Drugstore', 'drugstore', (a, b, c), room='shelves')
add('landmark_glb_small_town_bank_1920s', 'small-town-bank', 'small-town-bank-1920s.glb', ('model-info.json', 'state', '1920s'), 'Town State Bank', 'bank', (1900, 1930, 1925))
add('landmark_glb_small_town_bank_drive_through', 'small-town-bank', 'small-town-bank-drive-through.glb', ('model-info.json', 'state', 'drive-through'), 'Town State Bank', 'bank', (1965, 1985, 1975))
add('landmark_glb_brick_church', 'brick-church', 'brick-church.glb', ('model-info-brick.json', 'file'), 'Town Church', 'church', (1880, 2040, 1905))
add('landmark_glb_country_church', 'brick-church', 'country-church.glb', ('model-info-country.json', 'file'), 'Country Church', 'church', (1870, 2040, 1900))
for y, (a, b) in {'1925': (1925, 1958), '1955': (1952, 1978), '1975': (1972, 2003), '2000': (1998, 2040)}.items():
    add(f'landmark_glb_lincoln_high_school_{y}', 'lincoln-high-school', f'lincoln-high-school-{y}.glb', ('model-info.json', 'state', y), 'Lincoln High School', 'school', (a, b, a), far='lincoln-high-school/school-far.glb')
for st, (a, b, c, nm) in {'original': (1952, 1962, 1954, "Milo's Drive-In"), 'closed': (1976, 2012, 1995, "Milo's Drive-In"), 'restored': (2000, 2040, 2005, "Milo's Diner")}.items():
    add(f'landmark_glb_carhop_drive_in_{st}', 'carhop-drive-in', f'carhop-{st}.glb', (f'model-info-{st}.json', 'file'), nm, 'carhop', (a, b, c))
add('landmark_glb_fire_station_1924', 'fire-station', 'fire-station-1924.glb', ('model-info.json', 'id', '1924'), 'Fire Station', 'fire_station', (1924, 1924, 1924))
add('landmark_glb_fire_station_1988', 'fire-station', 'fire-station-1988.glb', ('model-info.json', 'id', '1988'), 'Fire Station', 'fire_station', (1984, 1990, 1985))
add('landmark_glb_post_office_1938', 'post-office', 'post-office-1938.glb', ('model-info.json', 'id', '1938'), 'Post Office', 'post_office', (1938, 1938, 1938))
add('landmark_glb_post_office_1988', 'post-office', 'post-office-1988.glb', ('model-info.json', 'id', '1988'), 'Post Office', 'post_office', (1976, 2040, 1988))
add('landmark_glb_highway_rest_area', 'highway-rest-area', 'highway-rest-area.glb', ('model-info.json', 'file'), 'Pine Bend Rest Area', 'rest_area', (1975, 2040, 1978))
add('landmark_glb_lantern_pine_lodge', 'lantern-pine-lodge', 'lantern-pine-lodge.glb', ('model-info.json', 'file'), 'Lantern Pine Lodge', 'lodge', (1930, 2040, 1935))
add('landmark_glb_the_eyrie', 'the-eyrie', 'the-eyrie.glb', ('model-info.json', 'file'), 'The Eyrie', 'monument', (1938, 2040, 1940))
add('landmark_glb_union_arch', 'union-arch', 'union-arch.glb', ('model-info.json', 'file'), 'Union Arch', 'monument', (1929, 2040, 1930))
add('landmark_glb_night_keeper', 'night-keeper', 'night-keeper.glb', ('model-info.json', 'file'), 'The Night Keeper', 'roadside_attraction', (1930, 2040, 1935))
add('landmark_glb_beach_blvd_rex', 'beach-blvd-rex', 'beach-blvd-rex.glb', ('model-info.json', 'file'), 'Beach Blvd Rex', 'roadside_attraction', (1964, 2040, 1964))
for y, (a, b) in {'1926': (1926, 1940), '1941': (1941, 1975), '1976': (1976, 1999), '2000': (2000, 2040)}.items():
    add(f'landmark_glb_great_meridian_{y}', 'great-meridian-monument', f'great-meridian-{y}.glb', ('model-info.json', 'gm', y), 'The Great Meridian', 'monument', (a, b, a), far='great-meridian-monument/great-meridian-far.glb')

INTERNAL = {  # doors the info marks (or the glb names) as joining rooms: no entrance from the street
    'carhop': {'door_prep_inside'},
    'monument': {'door_hall_west', 'door_hall_east', 'door_observatory_lift', 'door_cornice_gallery', 'door_sky_deck', 'door_lantern_chamber', 'door_rotunda_south', 'door_north', 'door_east', 'door_west'},
}
EXTERNAL = {'landmark_glb_lincoln_high_school_2000': {'door_main'}}  # its info marks door_main internal (a security vestibule); the street still enters by it

def state_info(s):
    f, how = s['info'][0], s['info'][1]
    j = R(f"{s['slug']}/{f}")
    if how == 'file': return j
    key = s['info'][2]
    if how == 'state': return next(x for x in j['states'] if x['state'] == key)
    if how == 'id': return next(x for x in j['states'] if x['id'] == key)
    if how == 'gm':
        st = j['states'][key]
        base = j['collision']['boxes_gltf_y_up']
        extra = j['collision'].get('per_state_extras', {}).get(key, [])
        bx = [{'source': b['source'], 'group': 'lot' if b.get('category') == 'lot' else 'structure', 'attachment': 'structure', 'center': b['center'], 'size': b['size']} for b in base + extra]
        return {'name': j['name'], 'doors': [{'node': k, 'position': v, 'external': True} for k, v in st['doors'].items()], 'collision': {'boxes_gltf_y_up': bx, 'use_the_walls': True}, 'building': j['footprint_m']}

def building(s, j):
    for k in ('main_footprint_m', 'footprint_m', 'building_footprint_m'):
        v = j.get(k)
        if isinstance(v, list): return [round(v[0], 2), round(v[1], 2)]
        if isinstance(v, dict):
            if 'building_width' in v: return [round(v['building_width'], 2), round(v['building_depth'], 2)]
    if 'nave_footprint_m' in j: return [round(j['nave_footprint_m'][0], 2), round(j['total_building_depth_m'], 2)]
    v = j.get('footprint_m') or j.get('building')
    if isinstance(v, dict) and 'building_width' in v: return [round(v['building_width'], 2), round(v['building_depth'], 2)]
    if isinstance(v, dict) and 'width_x' in v: return [round(v['width_x'], 2), round(v['depth_z'], 2)]
    return None

def main(write):
    entries = []; ts = []
    chains = {}
    for s in S:
        path = os.path.join(A, s['slug'], s['glb'])
        r = glbinfo.report(path)
        (lx, ly, lz), (hx, hy, hz) = r['bounds']
        j = state_info(s)
        s['lot'] = [round(hx - lx, 2), round(hz - lz, 2)]
        s['xmid'] = round((lx + hx) / 2, 2)
        s['tris'] = r['tris']; s['doors_glb'] = r['doors']; s['mats'] = len(r['mats']); s['height'] = hy
        s['building'] = building(s, j) or [round(s['lot'][0] * .6, 2), round(s['lot'][1] * .6, 2)]
        chains.setdefault((s['slug'], s['role'] if s['slug'] != 'brick-church' else s['glb']), []).append(s)
    # a chain of states: the biggest plot, the first state's middle
    for key, ch in chains.items():
        if len(ch) > 1:
            pw = max(c['lot'][0] for c in ch); pd = max(c['lot'][1] for c in ch)
            big = max(ch, key=lambda c: c['lot'][0] * c['lot'][1])
            for c in ch:
                c['plot'] = [pw, pd]; c['centre'] = big['xmid']
    for s in S:
        j = state_info(s)
        coll = norm_collision(j.get('collision', {})) if j.get('collision') else {}
        internal = set(INTERNAL.get(s['role'], set())) if s['role'] != 'monument' or s['slug'] == 'great-meridian-monument' else set()
        doors = doors_list(j.get('doors'), internal, EXTERNAL.get(s['kind'], set()))
        listed = {d['node'] for d in doors}
        missing = [d for d in s['doors_glb'] if d not in listed and not d.startswith('door_room')]
        for d in missing: doors.append({'node': d, 'external': d not in INTERNAL.get(s['role'], set())})
        if s['slug'] == 'lincoln-high-school':
            for d in doors:
                if d['node'].startswith('door_room'): d['external'] = False
        flat = {'name': s['name'], 'state': s['kind'], 'file': s['glb'], 'doors': doors, 'collision': coll,
                'lot_node': j.get('lot_node'), 'triangles_visible': s['tris'], 'source': f"codex/{s['slug']} {s['info'][0]}, flattened per state by tools/landmarks/intake.py"}
        out = f"{A}/{s['slug']}/model-info-{s['kind'].split('landmark_glb_')[1]}.json"
        if write: json.dump(flat, open(out, 'w'), indent=1)
        e = {'kind': s['kind'], 'name': s['name'], 'role': s['role'], 'room': s['room'], 'file': f"{s['slug']}/{s['glb']}", 'info': f"{s['slug']}/model-info-{s['kind'].split('landmark_glb_')[1]}.json",
             'era': {'from': s['era'][0], 'to': s['era'][1], 'built': s['era'][2]}, 'footprint': {'lot': s['lot'], 'building': s['building']}}
        if s.get('far'): e['far'] = s['far']
        if s.get('centre') is not None: e['centre'] = s['centre']; e['footprint']['plot'] = s['plot']
        entries.append(e)
        ts.append(f"  {s['kind']}: {{ name: {json.dumps(s['name'])}, role: '{s['role']}', era: {{ from: {s['era'][0]}, to: {s['era'][1]}, built: {s['era'][2]} }}, lot: {s['lot']}, building: {s['building']}{', plot: ' + json.dumps(s['plot']) if s.get('plot') else ''} }},")
        print(f"{s['kind']:48s} lot {s['lot']} bld {s['building']} tris {s['tris']} h {s['height']:.1f} mats {s['mats']} doors {[(d['node'], d.get('external', True)) for d in doors if d.get('external', True)]}  missing {missing}")
    if write:
        # the catalogue keeps its hand-written layout: the old entries as they are, ours appended one key a line
        txt = open(f'{A}/landmarks.json').read()
        cat = json.loads(txt)
        mine = {e['kind'] for e in entries}
        if any(l['kind'] in mine for l in cat['landmarks']):
            raise SystemExit('entries already in the catalogue: restore landmarks.json first')
        body = ',\n'.join('    {\n' + ',\n'.join(f'      {json.dumps(k)}: {json.dumps(v)}' for k, v in e.items()) + '\n    }' for e in entries)
        end = txt.rindex('\n  ]')
        open(f'{A}/landmarks.json', 'w').write(txt[:end] + ',\n' + body + txt[end:])
        open(os.environ.get('TS_OUT', '/tmp/intake_ts.txt'), 'w').write('\n'.join(ts) + '\n')

if __name__ == '__main__':
    main('--write' in sys.argv)
