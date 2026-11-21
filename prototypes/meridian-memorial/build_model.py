"""Construct a national memorial as a complete, metre-scale Timerpg asset."""
import json
import sys
from math import cos, pi, sin
from pathlib import Path

import bpy

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
from sculpture_tools import T, beam, box, group, loft, material, mesh, oval, plaque

bpy.ops.wm.read_factory_settings(use_empty=True)
root = group("meridian_memorial")
towers = group("monument", root)
court = group("national_square", root)
lot = group("lot", root)
stone = material("wall_ashlar", "ashlar")
limestone = material("wall_limestone", "carving")
granite = material("wall_granite", "granite")
bronze = material("detail_bronze", "bronze")
patina = material("detail_patina", "copper")
paving = material("ground_paving", "pavement")
water = material("detail_water", "water")
grass = material("ground_grass", "grass")
leaf = material("detail_foliage", "leaf")
wood = material("detail_timber", "plank_horizontal")
glow = material("glow_amber", "lamp", .4)
sign = material("sign_name", "name")


def block(name, parent, x, y, z, w, d, h, mat):
    return box(name, parent, (x,y,z+h/2), (w,d,h), mat)


def course(name, parent, levels, mat, corner=2):
    rings = []
    for x,y,z,w,d in levels:
        u,v = w/2,d/2
        k = min(corner,u*.35,v*.35)
        outline = [(-u+k,-v),(u-k,-v),(u,-v+k),(u,v-k),
                   (u-k,v),(-u+k,v),(-u,v-k),(-u,-v+k)]
        rings.append([(x+dx,y+dy,z) for dx,dy in outline])
    return loft(name,parent,rings,mat)


block("National square",root,0,0,0,176,236,.16,paving)
block("Axial granite inlay",court,0,0,.16,31,235.7,.03,granite)
# Water sits within low granite frames, with a real walkway down either bank.
for sign_y in (-1,1):
    y = sign_y*65
    block("Granite pool basin",court,0,y,.19,26.2,69.2,.19,granite)
    block("Still water",court,0,y,.38,24.6,67.6,.045,water)
    for x in (-12.75,12.75):
        block("Pool coping",court,x,y,.38,.68,69,.21,limestone)
    for end in (-1,1):
        block("End coping",court,0,y+end*34.30,.38,24.82,.64,.21,limestone)
    for sx in (-1,1):
        block("East-west lawn terrace",court,sx*56,y,.16,47,78,.24,granite)
        block("Lawn",court,sx*56,y,.4,45.6,76.6,.04,grass)
        for dy in (-27,-9,9,27):
            x = sx*55
            tree = group(f"tree_{sx}_{y+dy}",lot)
            oval("Trunk",tree,[(x,y+dy,.44,.29,.29),(x,y+dy,7,.16,.16)],wood,n=8)
            oval("Canopy",tree,[(x,y+dy,3.9,1.9,1.65),(x,y+dy,6,3.75,3.3),
                 (x-.39,y+dy+.3,10.7,2.75,2.45),(x-.65,y+dy+.55,12.45,.73,.78)],leaf,n=10)
            x = sx*30
            ob = group(f"lamp_{sx}_{y+dy}",lot)
            oval("Lamp base",ob,[(x,y+dy,.16,.49,.49),(x,y+dy,.7,.32,.32),
                 (x,y+dy,4.35,.12,.12)],bronze,n=8)
            oval("Amber glass",ob,[(x,y+dy,4.32,.27,.27),(x,y+dy,4.88,.37,.37)],glow,n=6)
            oval("Lamp hood",ob,[(x,y+dy,4.87,.45,.45),(x,y+dy,5.28,.14,.14)],bronze,n=6)

# Each pylon has a broad granite footing, a continuous taper, and a set-back upper lantern.
for side in (-1,1):
    x = side*42
    course("Granite footing",towers,[(x,0,.16,39,44),(x,0,5.2,39,44),
                                    (x,0,8.7,34,39)],granite,3)
    course("Stone shaft",towers,[(x,0,8.66,33.8,38.8),(x,0,76,29,33),
         (x+side*1.5,0,121,27,29),(x+side*2.6,0,173,23.1,23.6),
         (x+side*2.6,0,212,19,18)],stone,3)
    top = x+side*2.6
    course("Tower crown",towers,[(top,0,211.8,19.15,18.2),
           (top,0,216,19.15,18.2),(top,0,220,14,14),
           (top,0,226,9.1,9.1),(top,0,227.6,9.1,9.1)],limestone,1.6)
    course("Bronze cap",towers,[(top,0,227.58,8.9,8.9),(top,0,230,5.6,5.6),
           (top,0,232,2.8,2.8)],patina,1)
    for front in (-1,1):
        for off in (-9,-5,5,9):
            # Buttress feet taper into tall engaged ribs. Bronze channels flank each pier's spine.
            levels = [(x+off,front*22, .17,3.65,10.1),
                      (x+off,front*22, 12,3.65,10.1),
                      (x+off*.94,front*18,40,2.55,6.6),
                      (x+side*.3+off*.79,front*14.7,94,1.58,4.35),
                      (x+side*1.45+off*.65,front*12.9,133,1.06,3.7),
                      (top+off*.61,front*10.3,178,.72,3.15),
                      (top+off*.55,front*9.12,201,.54,1.5)]
            course("Engaged limestone buttress",towers,levels,limestone,.40)
        strip = [(x,front*19.55,8.5,4.1,.95),(x,front*16.73,75,3.4,1.25),
                 (x+side*1.5,front*14.72,121,3.15,1.15),(top,front*12.02,173,2.65,.95),
                 (top,front*9.68,202,2.25,1.05)]
        course("Recessed bronze spine",towers,strip,patina,.17)
        for z, y, width in [(37,18.2,3.5),(74,16.7,3.1),(112,15.1,2.9),(149,13.35,2.65),(187,10.8,2.25)]:
            block("Bronze spine transom",towers,x+side*max(0,min(2.6,(z-76)*.031)),
                  front*y,z,width,1.55,.43,bronze)
        for off in (-11,11):
            block("Ground-level dedication pier",court,x+off,front*27,.17,3.2,3.2,5.6,granite)
            block("Pedestal crown",court,x+off,front*27,5.72,3.7,3.75,.33,limestone)
            oval("Bronze lantern",court,[(x+off,front*27,6.04,.77,.77),
                 (x+off,front*27,7.09,.80,.80)],glow,n=8)
            oval("Lantern lid",court,[(x+off,front*27,7.08,1,1),
                 (x+off,front*27,7.48,.52,.52)],bronze,n=8)

# A copper-clad steel ring bears on a deep gallery and two lateral saddle anchors.
ring_z, radius, band, half_depth = 159, 31, 2.45, 3.25
for i in range(36):
    a,b = i*2*pi/36,(i+1)*2*pi/36
    def profile(theta):
        return [(r*cos(theta),y,ring_z+r*sin(theta))
                for r,y in [(radius-band,-half_depth),(radius+band,-half_depth),
                            (radius+band,half_depth),(radius-band,half_depth)]]
    loft("Copper ring segment",towers,[profile(a),profile(b)],patina if i%3 else bronze,ends=False)
    if i%3==0:
        for y in (-3.35,3.35):
            p = (radius*cos(a),y,ring_z+radius*sin(a))
            beam("Ring rib",towers,p,(p[0],y+(.31 if y>0 else -.31),p[2]),(1.25,1.25),(1.04,1.04),bronze,n=8)
block("Gallery limestone girder",towers,0,0,117,66,15.4,7.5,granite)
block("Gallery copper parapet",towers,0,0,124.48,66,15.83,1.68,patina)
block("Ring bearing",towers,0,0,125.9,12,10.6,2.0,bronze)
for s in (-1,1):
    block("Stone ring saddle",towers,s*32.5,0,153.7,4.8,12.4,11.1,granite)
    for y in (-6.28,6.28):
        block("Saddle face",towers,s*32.5,y,155,4.5,.62,8,bronze)
    for y in (-6.8,6.8):
        beam("Gallery diagonal strut",towers,(s*30,y,107),(s*19,y,117.35),(1.78,1.78),(1.38,1.38),bronze,n=8)
for y in (-7.8,7.8):
    block("Gallery upper band",towers,0,y,121.5,57,.58,.4,limestone)
    for x in range(-24,25,4):
        block("Gallery slit",towers,x,y,119,1.80,.54,1.83,glow)

# Stone terraces lead to an open memorial floor. Side ramps reach the same platform.
block("Memorial floor",court,0,0,.16,47,39,1.29,granite)
block("Limestone floor cap",court,0,0,1.45,47,39,.10,limestone)
for s in (-1,1):
    for i in range(8):
        h = (i+1)*.17375
        y = s*(23.1-i*.48)
        block("Ceremonial stair",court,0,y,.16,40,.48,h,limestone)
    for xs in (-1,1):
        x = xs*22
        z0,z1 = .16,1.55
        pts = [(x+dx,s*y,z) for dx in (-1.35,1.35)
               for y,z in [(29,z0),(19.49,z0),(19.49,z1)]]
        fs = [(2,1,0),(3,4,5),(0,1,4,3),(1,2,5,4),(2,0,3,5)]
        if s > 0:
            fs = [tuple(reversed(f)) for f in fs]
        mesh("Level access ramp",court,pts,fs,granite)

# Low dedicatory walls are kept outside the central sightline.
for side in (-1,1):
    for face in (-1,1):
        x,y = side*16.6,face*12
        block("Inscription wall",court,x,y,1.55,12,2,3.5,granite)
        block("Inscription molding",court,x,y,5.02,12.35,2.23,.26,limestone)
        w,h = 256*T,32*T
        p = [(x-w/2,y+face*1.015,3.48-h/2),(x+w/2,y+face*1.015,3.48-h/2),
             (x+w/2,y+face*1.015,3.48+h/2),(x-w/2,y+face*1.015,3.48+h/2)]
        plaque("sign_name" if (side,face)==(-1,-1) else f"sign_name_{side}_{face}",court,p if face<0 else [p[i] for i in (1,0,3,2)],sign,(0,0,256,32),(256,32))
# Radial seams on the walkable inlaid compass define its points at person height.
oval("Compass inlay",court,[(0,0,1.548,8.5,8.5),(0,0,1.565,8.5,8.5)],granite,n=16)
for i in range(8):
    a=i*pi/4
    reach=7.6 if i%2==0 else 5.15
    shape=[(1.52*cos(t),1.52*sin(t),1.581) for t in (a-pi/8,a+pi/8)]
    mesh("Compass needle",court,[shape[0],(reach*cos(a),reach*sin(a),1.581),shape[1]],
         [(0,1,2)],limestone if i%2==0 else patina)

for parent in (root,towers,court,*lot.children):
    groups = {}
    for ob in parent.children:
        if ob.type=="MESH" and not ob.name.startswith("sign_name"):
            groups.setdefault(ob.data.materials[0].name,[]).append(ob)
    for name, objects in groups.items():
        bpy.ops.object.select_all(action="DESELECT")
        for ob in objects:
            ob.select_set(True)
        bpy.context.view_layer.objects.active=objects[0]
        if len(objects)>1:
            bpy.ops.object.join()
        objects[0].name=f"{parent.name}__{name}"

objects=[ob for ob in root.children_recursive if ob.type=="MESH"]
triangles=sum(len(p.vertices)-2 for ob in objects for p in ob.data.polygons)
materials=sorted({mat.name for ob in objects for mat in ob.data.materials})
assert triangles<=12000 and len(materials)<=12
info={"name":"The Meridian Memorial","era":"1941 design, suitable from the 1940s onward",
      "height_m":232,"footprint_m":[176,236],"ring_outer_diameter_m":66.9,"triangles_visible":triangles,
      "materials":materials,"units":"glTF metres, Y up, +Z south, origin at south plaza edge",
      "doors":[],"collision":{"use_the_walls":True,"notes":"Public ground-level terraces and ramps. Upper bearing gallery is sculptural. Treat reflecting basins as obstructions."},
      "texel_size_m":T,"texels_per_art_pixel":2,"swatches_used":["pavement","plank_horizontal"]}
(ROOT/"model-info.json").write_text(json.dumps(info,indent=2)+"\n")
bpy.ops.export_scene.gltf(filepath=str(ROOT/"meridian-memorial.glb"),export_format="GLB",export_extras=True)
bpy.ops.file.pack_all()
bpy.context.preferences.filepaths.save_version=0
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/"meridian-memorial.blend"))
