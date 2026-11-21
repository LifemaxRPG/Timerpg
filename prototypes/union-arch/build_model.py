"""Build a 1929 civic arch with three open passages and a public square."""
import json
import sys
from math import cos, sin, pi
from pathlib import Path

import bpy
sys.path.insert(0, str(Path(__file__).resolve().parent))
from sculpture_tools import T, beam, box, group, material, mesh, oval, plaque

ROOT = Path(__file__).resolve().parent
bpy.ops.wm.read_factory_settings(use_empty=True)
body = group("union_arch")
structure = group("stone_arch", body)
lot = group("lot", body)

stone = material("wall_ashlar", "ashlar")
trim = material("wall_limestone", "carving")
base = material("wall_granite", "granite")
bronze = material("detail_bronze", "bronze")
cap = material("roof_copper", "copper")
paving = material("ground_paving", "pavement")
grass = material("ground_grass", "grass")
leaf = material("detail_foliage", "leaf")
timber = material("detail_timber", "plank_horizontal")
sign = material("sign_name", "name")
glow = material("glow_lantern", "lamp", .32)


def block(name, x, y, bottom, width, depth, height, mat=stone, parent=structure):
    return box(name, parent, (x, y, bottom + height/2), (width, depth, height), mat)


def prism(name, cross_section, front, rear, mat, parent=structure):
    # Face the XY cross-section toward the south before closing its depth.
    points = [(x, front, z) for x, z in cross_section] + [(x, rear, z) for x, z in cross_section]
    n = len(cross_section)
    faces = [tuple(range(n)), tuple(reversed(range(n, 2*n)))]
    faces.extend((i, i+n, (i+1)%n+n, (i+1)%n) for i in range(n))
    return mesh(name, parent, points, faces, mat)


block("Plaza", 0, 0, 0, 43, 36, .12, paving, body)
block("Processional pavement", 0, 0, .12, 12.6, 35.6, .035, base, body)
block("Processional paving inset", 0, 0, .155, 11.9, 35.3, .014, paving, body)
for side in (-1, 1):
    for y in (-10.8, 10.8):
        block("Granite garden curb", side*15.8, y, .12, 9.4, 10.4, .23, base, body)
        block("Garden bed", side*15.8, y, .35, 8.9, 9.9, .02, grass, body)

# Piers are bounded by the actual jambs. The center bay rises above both wings.
for x, width in [(-11.7, 2.8), (-6.325, 3.05), (6.325, 3.05), (11.7, 2.8)]:
    height = 14.7 if abs(x) < 10 else 9.25
    block("Ashlar pier", x, 0, .12, width, 5.8, height-.12)
    for z, depth, h, mat in [(.17, 6.5, .42, base), (.59, 6.18, .36, base), (.95, 6, .20, trim)]:
        block("Plinth molding", x, 0, z, width+.10-(z-.17)*.075, depth, h, mat)

for x, radius, spring, top, count in [
    (-9.075, 1.225, 4.45, 9.25, 10),
    (0, 4.8, 7.6, 14.7, 18),
    (9.075, 1.225, 4.45, 9.25, 10)
]:
    for i in range(count):
        a, b = pi-(pi*i/count), pi-(pi*(i+1)/count)
        def point(angle, r):
            return x+r*cos(angle), spring+r*sin(angle)
        left, right = point(a, radius), point(b, radius)
        prism("Vault and spandrel", [left, right, (right[0], top), (left[0], top)], -2.9, 2.9, stone)
        width = .73 if x == 0 else .38
        aa, bb = a-.004, b+.004
        face = [point(aa, radius-.002), point(bb, radius-.002),
                point(bb, radius+width), point(aa, radius+width)]
        for f, r in [(-3.14, -2.88), (2.88, 3.14)]:
            prism("Cut voussoir", face, f, r, trim if i % 3 else stone)
    k = .35 if x == 0 else .19
    for y in (-3.12, 3.12):
        prism("Raised keystone", [(x-k,spring+radius-.06), (x+k,spring+radius-.06),
              (x+k*1.46,spring+radius+1), (x-k*1.46,spring+radius+1)],
              y-.22, y+.22, trim)

for side in (-1, 1):
    for y in (-3.02, 3.02):
        for x, h, w in [(side*6.31, 13.68, 1.28), (side*11.72, 8.57, .96)]:
            block("Pilaster shaft", x, y, 1.14, w, .58, h-1.14, trim)
            for offset in (-.32, -.16, 0, .16, .32):
                block("Pilaster fluting", x+offset*w, y*1.09, 1.64, .052, .11, h-2, stone)
            block("Pilaster capital", x, y*1.05, h-.23, w*1.39, .82, .21, trim)
            block("Pilaster abacus", x, y*1.06, h-.02, w*1.48, .88, .27, trim)
    for z, w, d, h, mat in [
        (9.25, 8.4, 6.3, .33, trim), (9.58, 8.6, 6.63, .39, trim),
        (9.97, 7.8, 5.85, 1.02, stone), (10.99, 7.98, 6.05, .20, trim)
    ]:
        block("Wing cornice and parapet", side*9.05, 0, z, w, d, h, mat)

for z, w, d, h, mat in [
    (13.98, 16.1, 6.38, .28, trim), (14.26, 16.38, 6.69, .44, trim),
    (14.70, 16, 6, .38, stone), (15.08, 15.7, 5.84, 2.82, stone),
    (17.9, 16.1, 6.3, .21, trim), (18.11, 16.36, 6.52, .28, trim),
    (18.39, 16.74, 6.92, .42, trim), (18.81, 15.85, 6.08, .31, stone),
    (19.12, 14.4, 5.32, .65, stone), (19.77, 13.22, 4.60, .38, cap)
]:
    block("Attic and stepped crown", 0, 0, z, w, d, h, mat)

# The center tablet and civic reliefs wrap onto the reverse elevation.
for face in (-1, 1):
    y = face*2.98
    block("Tablet stone frame", 0, y, 15.68, 10.34, .35, 1.64, trim)
    w, h = 256*T, 32*T
    coords = [(-w/2,y+face*.183,16.5-h/2),(w/2,y+face*.183,16.5-h/2),
              (w/2,y+face*.183,16.5+h/2),(-w/2,y+face*.183,16.5+h/2)]
    plaque("sign_name" if face < 0 else "sign_name_reverse", structure,
           coords if face < 0 else list(reversed(coords[2:]+coords[:2])),
           sign, (0,0,256,32), (256,32))
    for side in (-1, 1):
        x = side*6.28
        p = (x, face*2.91, 16.51)
        q = (x, face*3.17, 16.51)
        beam("Bronze river rosette", structure, p, q, (.80,.80), (.69,.69), bronze, 12)
        for dx in (-.28, 0, .28):
            vertices = [(x+dx-.09,16.14),(x+dx+.08,16.14),(x+dx+.22,16.44),
                        (x+dx+.07,16.79),(x+dx-.08,16.79),(x+dx+.05,16.44)]
            prism("River relief reed", vertices, min(q[1],q[1]+face*.08),
                  max(q[1],q[1]+face*.08), trim)

for x in (-6.15, 6.15, -11.8, 11.8):
    for face in (-1, 1):
        y = face*3.7
        block("Bronze lantern bracket", x, face*3.43, 2.72, .16, .6, .16, bronze)
        oval("Lantern body", structure, [(x,y,2.75,.23,.20),(x,y,3.22,.29,.25)], glow, n=4)
        oval("Lantern cap", structure, [(x,y,3.20,.42,.35),(x,y,3.45,.12,.11)], bronze, n=4)
        block("Lantern foot", x, y, 2.69, .42, .38, .10, bronze)

for side in (-1, 1):
    for y in (-13.8, -7.9, 8.1, 13.7):
        x = side*(16.1 if abs(y)>10 else 18)
        tree = group(f"tree_{side}_{y}", lot)
        oval("Tree trunk", tree, [(x,y,.37,.13,.13),(x,y,3.2,.1,.1)], timber, n=7)
        oval("Faceted street tree", tree, [(x,y,2.21,.55,.57),(x,y,3.25,1.65,1.43),
             (x-.22,y+.18,4.9,1.36,1.24),(x-.3,y+.24,5.6,.45,.51)], leaf, n=9)
    for y in (-9.3, 9.3):
        bench = group(f"bench_{side}_{y}", lot)
        for dx in (-.83,.83):
            block("Bench foot", side*10+dx, y, .12, .16, .63, .47, bronze, bench)
        for j in range(3):
            block("Seat slat", side*10, y+.16*(j-1), .54, 2.1, .14, .08, timber, bench)
        for j in range(2):
            block("Bench back", side*10, y+.27, .81+j*.15, 2.1, .085, .12, timber, bench)

for parent in [body, structure, *lot.children]:
    bins = {}
    for ob in parent.children:
        if ob.type == "MESH" and not ob.name.startswith("sign_name"):
            bins.setdefault(ob.data.materials[0].name, []).append(ob)
    for name, objects in bins.items():
        bpy.ops.object.select_all(action="DESELECT")
        for ob in objects:
            ob.select_set(True)
        bpy.context.view_layer.objects.active = objects[0]
        if len(objects) > 1:
            bpy.ops.object.join()
        objects[0].name = f"{parent.name}__{name}"

objects = [ob for ob in body.children_recursive if ob.type == "MESH"]
triangles = sum(len(p.vertices)-2 for ob in objects for p in ob.data.polygons)
materials = sorted({m.name for ob in objects for m in ob.data.materials})
assert triangles < 12000 and len(materials) <= 12
info = {
    "name": "Union Arch", "era": "1929 onward", "footprint_m": [43,36],
    "height_m": 20.15, "triangles_visible": triangles, "materials": materials,
    "units": "Metres, glTF Y up, front faces +Z, origin at front lot edge",
    "doors": [],
    "passages": {"center_width_m": 9.5, "side_width_m": 2.35},
    "collision": {"use_the_walls": True, "notes": "Use stone piers and garden curbs. Three public openings are through-passages."},
    "texel_size_m": T, "texels_per_art_pixel": 2,
    "swatches_used": ["pavement", "plank_horizontal"]
}
(ROOT/"model-info.json").write_text(json.dumps(info, indent=2)+"\n")
bpy.ops.export_scene.gltf(filepath=str(ROOT/"union-arch.glb"), export_format="GLB", export_extras=True)
bpy.ops.file.pack_all()
bpy.context.preferences.filepaths.save_version = 0
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/"union-arch.blend"))
