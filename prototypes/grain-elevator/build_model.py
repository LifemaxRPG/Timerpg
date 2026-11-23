"""Build three era states and their LODs in Blender, in real-world metres."""
import json
from math import cos, pi, sin
from pathlib import Path
import sys

import bpy

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
from sculpture_tools import beam, box, group, loft, material, mesh, oval, plaque
from revision_common import batch, emitter, export_glb, save_source

PIXEL = .0776
ATLAS = (256, 256)
REGIONS = json.loads((ROOT / "textures/sign-regions.json").read_text())
STATES = {
    "1925": ("Timber Elevator", 1925, 1949),
    "1950": ("Timber and Concrete Expansion", 1950, 1989),
    "1990": ("Lark Bend Co-op", 1990, None),
}
SIDING_Y = 28.5
SILO_Y = 16.0
SILO_RADIUS = 20 * PIXEL / (2*sin(pi/16))
SILO_CENTRES = [1.4 + i*7.96 for i in range(6)]


def slab(parent, name, bounds, mat):
    a,b,c,d,e,f = bounds
    return box(name, parent, ((a+b)/2, (c+d)/2, (e+f)/2), (b-a, d-c, f-e), mat)


def gable(parent, name, x, y, width, depth, eaves, ridge, mat):
    a, b = x-width/2, x+width/2
    profile = [(a,eaves),(b,eaves),(x,ridge)]
    vertices = [(px, py, z) for py in (y-depth/2, y+depth/2) for px,z in profile]
    return mesh(name, parent, vertices, [(0,1,2),(5,4,3),(3,4,1,0),(1,4,5,2),(3,0,2,5)], mat)


def roof_cover(parent, name, x, y, width, depth, eaves, ridge, mat):
    for a, b in (((x-width/2,eaves),(x,ridge)), ((x,ridge),(x+width/2,eaves))):
        y0, y1 = y-depth/2, y+depth/2
        verts = [(a[0],y0,a[1]),(b[0],y0,b[1]),(b[0],y1,b[1]),(a[0],y1,a[1])]
        verts += [(px,py,z-.29) for px,py,z in verts]
        mesh(name,parent,verts,[(0,1,2,3),(7,6,5,4),(0,4,5,1),(1,5,6,2),(2,6,7,3),(3,7,4,0)],mat)


def build(state, lod):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    timber, concrete = state != "1990", state != "1925"
    near, far = lod == "near", lod == "far"
    root = group("Lark Bend Grain Elevator")
    office, tower, silos, yard, col = [group(n, root) for n in
        ("scale_office", "timber_elevator", "concrete_elevator", "lot", "collision")]
    wood = material("wall_clapboard", "wood")
    cement = material("wall_concrete", "concrete")
    steel = material("trim_oxidized_steel", "steel")
    roof = material("roof_membrane", "roof")
    brick = material("wall_office_brick", "brick")
    paving = material("ground_drive", "yard")
    ballast = material("ground_gravel", "gravel")
    glass = material("glass_panes", "glass")
    glass.use_backface_culling = False
    emitter(glass, "pane-mask", .24)
    sign = material("sign_paint", "signs")
    sign.use_backface_culling = True
    sn = sign.node_tree.nodes
    sn.get("Principled BSDF").inputs["Alpha"].default_value = 1
    sign.node_tree.links.new(next(n for n in sn if n.type=="TEX_IMAGE").outputs["Alpha"],
                             sn.get("Principled BSDF").inputs["Alpha"])
    hidden = bpy.data.materials.new("collision_invisible")
    hidden.use_nodes = True
    hidden.surface_render_method = "BLENDED"
    hidden.node_tree.nodes.get("Principled BSDF").inputs["Alpha"].default_value = 0
    colliders, signs, doors = [], {}, {}

    def collide(name, bounds, optional=False, attachment=None):
        a,b,c,d,e,f = bounds
        obj = slab(col, f"collider_{len(colliders):02d}", bounds, cement)
        obj.data.materials.clear()
        obj.data.materials.append(hidden)
        obj["collision_only"] = True
        colliders.append({"name":name, "center_gltf_m":[(a+b)/2,(e+f)/2,-(c+d)/2],
                          "size_gltf_m":[b-a,f-e,d-c], "group":"lot" if optional else "building",
                          "attachment": attachment or ""})

    def wall(parent, name, bounds, mat, blocking=False):
        slab(parent, name, bounds, mat)
        if blocking:
            collide(name, bounds)

    def door(name, x, y, yaw=0):
        node = group(name, root)
        node.location = (x,y,.14)
        node.rotation_euler.z = yaw
        doors[name] = [x,.14,-y]

    def quad(parent, label, coords, mat, rect, size):
        ob = plaque(label,parent,coords,mat,rect,size)
        ob["keep"] = True
        return ob

    def face_coords(x, y, z, w, h, reverse=False):
        a,b = (x+w/2, x-w/2) if reverse else (x-w/2, x+w/2)
        return [(a,y,z),(b,y,z),(b,y,z+h),(a,y,z+h)]

    def painted(parent, node, region, x, y, z):
        rect = REGIONS[region]
        width, height = rect[2]*.0388, rect[3]*.0388
        quad(parent,node,face_coords(x,y,z,width,height),sign,rect,ATLAS)
        signs[node] = {"rect_texels":rect,"atlas_size_texels":ATLAS,"texture":"signs.png"}

    def panes(parent, name, x, y, z, columns, rows, reverse=False):
        # Each opening contains physically scaled gloss-free panes and an unlit art-pixel frame.
        pane_size = 16*PIXEL
        for i in range(columns):
            for j in range(rows):
                px = x+(i-(columns-1)/2)*pane_size
                ob = quad(parent,name,face_coords(px,y,z+j*pane_size,pane_size,pane_size,reverse),
                     glass,(0,0,32,32),(32,32))
                del ob["keep"]

    # Shared scale office, true open grade-level street and yard doors.
    wall(office, "Office foundation and floor", (-3.9,3.9,0,6.1,.025,.14), cement)
    def office_facade(y, street):
        door_span = (-.78, .78) if street else (1.10, 2.62)
        openings = [(*door_span, .14, 2.55)]
        windows = [(-2.34, 1.23), (2.34, 1.23)] if street else [(-1.63, 1.25)]
        for x, z in windows:
            openings.append((x-16*PIXEL, x+16*PIXEL, z, z+16*PIXEL))
            panes(office, "Scale office sashes", x, y+.12, z, 2, 1, not street)
        previous = -3.9
        for a,b,z0,z1 in sorted(openings):
            wall(office, "Full-height office pier", (previous,a,y,y+.24,.14,3.53), brick, True)
            if z0 > .14:
                wall(office, "Window plinth", (a,b,y,y+.24,.14,z0), brick, True)
                collide("Fixed sash", (a,b,y+.09,y+.16,z0,z1))
            wall(office, "Office lintel", (a,b,y,y+.24,z1,3.53), brick)
            previous = b
        wall(office, "End office pier", (previous,3.9,y,y+.24,.14,3.53), brick, True)
    office_facade(.02, True)
    office_facade(5.87, False)
    for a,b in ((-3.9,-3.66),(3.66,3.9)):
        wall(office,"Office side wall",(a,b,.26,5.87,.14,3.53),wood,True)
    for x in (-.78,.78):
        slab(office,"Main door casing",(x-.08,x+.025,-.025,.26,.14,2.55),steel)
    for x in (1.10,2.62):
        slab(office,"Yard door casing",(x-.065,x+.035,5.86,6.14,.14,2.55),steel)
    slab(office,"Office gabled roof tie",(-3.88,3.88,.04,6.06,3.36,3.53),wood)
    gable(office,"Office finished gable",0,3.05,7.8,6.1,3.53,4.75,wood)
    roof_cover(office,"Office sheet roof",0,3.05,8.66,6.81,3.55,4.95,roof)
    slab(office,"Fixed records counter",(-3.54,-2.47,2.8,5.7,.14,1.04),wood)
    collide("Office records counter",(-3.54,-2.47,2.8,5.7,.14,1.04))
    slab(office,"Counter cap",(-3.59,-2.39,2.76,5.73,1.02,1.095),steel)
    painted(office,"sign_office","office",0,-.012,2.58)
    door("door_main",0,-.32)
    door("door_back",1.86,6.40,pi)

    # The yard stays open for truck circulation. It stops short of the game-owned rail siding.
    x0, x1 = (-23.8,24.2) if not concrete else (-25,46)
    slab(yard,"Compacted approach",(x0,x1,-4,24.45,0,.035),paving)
    slab(yard,"Service strip",(x0,x1,23.9,25.1,.035,.055),ballast)
    slab(yard,"Walk at office",(-4.4,4.3,-1.6,6.61,.037,.11),cement)
    slab(yard,"Truck scale frame",(5.8,20.3,1.31,4.79,.035,.235),steel)
    slab(yard,"Truck scale deck",(6.13,19.96,1.47,4.63,.235,.29),cement)
    for x, rise in ((5.8,1),(20.3,-1)):
        a, b = x-2.15 if rise==1 else x+2.15, x
        corners = [(a,1.31,.035),(b,1.31,.25),(b,4.79,.25),(a,4.79,.035),
                   (a,1.31,.034),(b,1.31,.035),(b,4.79,.035),(a,4.79,.034)]
        polys = [(0,1,2,3),(7,6,5,4),(1,5,6,2),(0,4,5,1),(2,6,7,3),(3,7,4,0)]
        mesh("Scale approach ramp",yard,corners,[p if rise==1 else tuple(reversed(p)) for p in polys],cement)
    for x in (6.25,19.85):
        for y in (1.14,4.97):
            slab(yard,"Scale bollard",(x-.11,x+.11,y-.12,y+.12,.035,.96),steel)
            collide("Scale bollard",(x-.11,x+.11,y-.12,y+.12,.035,.96),True,"lot")
    slab(yard,"Scale sign post",(4.65,4.84,5.9,6.10,.035,2.7),steel)
    collide("Scale sign post",(4.65,4.84,5.9,6.10,.035,2.7),True,"lot")
    painted(yard,"sign_scale","scale",4.75,5.89,1.8)

    if timber:
        # Tall timber working house, changing pitch at the cap, with attached unloading shed.
        wall(tower,"Raised concrete boot pit",(-21,-11,10.8,21.15,.035,.83),cement)
        wall(tower,"Timber bin house",(-20.85,-11.15,10.94,20.98,.83,22.3),wood)
        collide("Timber elevator",(-21,-11,10.8,21.15,.035,22.3))
        for z in (1.12,7.3,14.2,21.98):
            slab(tower,"Timber belt",(-20.98,-11.02,10.81,21.09,z,z+.23),steel)
        for x in (-20.79,-11.21):
            slab(tower,"Painted corner casing",(x-.13,x+.13,10.79,21.14,.83,22.27),steel)
        gable(tower,"Timber shoulder gables",-16,15.96,9.7,10.04,22.3,25.55,wood)
        roof_cover(tower,"Working house roof",-16,15.96,10.4,10.85,22.41,25.99,roof)
        wall(tower,"Monitor headhouse",(-18.85,-13.15,12.1,19.8,24.25,30.2),wood)
        gable(tower,"Monitor end gable",-16,15.95,5.7,7.7,30.2,32.02,wood)
        roof_cover(tower,"Monitor cap",-16,15.95,6.25,8.3,30.32,32.35,roof)
        painted(tower,"sign_wood","wood",-16,10.916,17.85)
        for y,reverse in ((12.089,False),(19.811,True)):
            panes(tower,"Monitor windows",-16,y,27.54,3 if not far else 1,1,reverse)
        panes(tower,"Timber stair window",-18.65,10.922,11.05,1,1)
        panes(tower,"Timber rear stair window",-13.44,20.999,11.05,1,1,True)
        for x in (-20.23,-11.78):
            slab(tower,"Receiving shed front post",(x-.19,x+.19,6.84,7.24,.035,5.0),wood)
            collide("Receiving shed front post",(x-.19,x+.19,6.84,7.24,.035,5.0))
        slab(tower,"Receiving deck",(-20.97,-11.03,6.62,10.89,.035,.3),cement)
        slab(tower,"Receiving shed fascia",(-21.2,-10.8,6.5,6.91,4.53,4.92),steel)
        verts = [(-21.24,6.45,4.94),(-10.76,6.45,4.94),(-10.76,10.95,6.72),(-21.24,10.95,6.72)]
        mesh("Lean-to canopy roof",tower,verts,[(0,1,2,3)],roof)
        slab(tower,"Receiving timber hopper curb",(-18.35,-13.65,8.62,9.71,.3,.45),steel)
        beam("Old enclosed rail chute",tower,(-16,20.87,15.21),(-16,SIDING_Y,6.22),(.39,.43),(.19,.19),steel,8)
        beam("Old loadout sleeve",tower,(-16,SIDING_Y,6.25),(-16,SIDING_Y,5.13),(.20,.20),(.17,.17),steel,8)
        if concrete:
            beam("Transfer leg between elevators",tower,(-13.25,16.0,26.6),(1.4,16,35.15),
                 (.35,.64),(.35,.64),steel,4)
    if concrete:
        for i, x in enumerate(SILO_CENTRES):
            radius = SILO_RADIUS
            profile = ((.035,radius),(35.72,radius)) if far else (
                (.035,radius),(34.84,radius),(35.28,radius+.09),(35.72,radius+.09))
            oval("Slip-form concrete silo",silos,[(x,SILO_Y,z,r,r) for z,r in profile],cement,16)
            inside = radius*cos(pi/16)
            collide(f"Silo {i+1} square core",(x-inside*.74,x+inside*.74,SILO_Y-inside,SILO_Y+inside,.035,35.72))
            collide(f"Silo {i+1} east/west shoulders",
                    (x-inside,x+inside,SILO_Y-inside*.61,SILO_Y+inside*.61,.035,35.72))
            if not far:
                slab(silos,"Concrete clean-out frame",(x-.72,x+.72,SILO_Y-inside-.26,SILO_Y-inside+.08,.15,2.51),cement)
                slab(silos,"Sealed bin door",(x-.51,x+.51,SILO_Y-inside-.29,SILO_Y-inside-.27,.29,2.25),steel)
            if near:
                slab(silos,"Hatch latch",(x+.03,x+.45,SILO_Y-inside-.33,SILO_Y-inside-.285,1.13,1.24),steel)
        slab(silos,"Conveyor gallery floor",(-1.2,43.1,14.55,17.45,35.6,36.0),cement)
        slab(silos,"Tin gallery enclosure",(-.95,42.9,14.72,17.28,36.0,38.57),steel)
        # The shallow gable runs along the gallery. Wrap its vertical ends in the same cladding.
        gallery_gable = [(x,y,z) for x in (-1.35,43.3) for y,z in
                         ((14.48,38.50),(17.52,38.50),(16,39.10))]
        mesh("Long gallery cap",silos,gallery_gable,
             [(2,1,0),(3,4,5),(0,1,4,3),(3,5,2,0),(1,2,5,4)],roof)
        for x in (2.1,19.4,26.8,34.9,40.4):
            panes(silos,"Gallery light",x,14.709,36.56,1,1)
            panes(silos,"Gallery rear light",x,17.292,36.56,1,1,True)
        slab(silos,"Headhouse concrete walls",(5.4,16.15,12.45,19.55,35.65,43.52),cement)
        slab(silos,"Headhouse roof surround",(5.15,16.4,12.13,19.82,43.3,43.81),cement)
        slab(silos,"Headhouse flat membrane",(5.33,16.22,12.28,19.64,43.8,43.92),roof)
        slab(silos,"Vent housing",(12.28,13.72,16.53,17.71,43.92,44.71),steel)
        for reverse,y in ((False,12.438),(True,19.562)):
            panes(silos,"Headhouse sashes",10.76,y,40.21,4 if not far else 2,1,reverse)
        # Rear inspection ladder and service platform, below the gallery sill.
        ladder_x = 25.0
        ladder_y = 20.08
        for rail in (ladder_x, ladder_x+.74):
            slab(silos,"Inspection ladder rail",(rail-.041,rail+.041,ladder_y-.045,ladder_y+.045,3.3,36.25),steel)
        if near:
            for i in range(80):
                z = 3.51+i*.407
                slab(silos,"Ladder rung",(ladder_x,ladder_x+.74,ladder_y-.038,ladder_y+.038,z,z+.0776),steel)
        if not far:
            for z in (5.2,13.2,21.2,29.2,35.1):
                slab(silos,"Ladder wall bracket",(ladder_x-.06,ladder_x+.81,19.58,ladder_y+.05,z,z+.16),steel)
        slab(silos,"Inspection platform",(23.79,27.04,17.17,20.55,35.75,35.92),steel)
        for post in (23.91,26.93):
            slab(silos,"Inspection platform rail post",(post-.06,post+.06,20.36,20.48,35.91,36.92),steel)
        slab(silos,"Inspection platform back rail",(23.85,26.99,20.35,20.49,36.80,36.94),steel)
        # Painted decals span three exact-width facets. The atlas is split at the facet edges.
        for node, region, x in (("sign_name","name",SILO_CENTRES[2]),("sign_trade","coop" if state=="1990" else "grain",SILO_CENTRES[3])):
            atlas_x, atlas_y, width, height = REGIONS[region]
            z = 23.08
            points = [(x+SILO_RADIUS*cos((i+.5)*pi/8),SILO_Y+SILO_RADIUS*sin((i+.5)*pi/8))
                      for i in (10,11,12,13)]
            # Radial offsets separate each decal from its surface without rescaling the artwork.
            coords = [(px,py-.014,z) for px,py in points]
            uv = []
            for row in (1,0):
                uv.extend([( (atlas_x+i*40)/ATLAS[0],1-(atlas_y+row*height)/ATLAS[1]) for i in range(4)])
            ob = mesh(node,silos,coords+[(px,py,pz+height*.0388) for px,py,pz in coords],
                      [(i,i+1,i+5,i+4) for i in range(3)],sign)
            for loop in ob.data.loops:
                ob.data.uv_layers.active.data[loop.index].uv = uv[loop.vertex_index]
            ob["keep"] = True
            signs[node] = {"texture":"signs.png","rect_texels":[atlas_x,atlas_y,width,height],
                           "atlas_size_texels":ATLAS,"bottom_edge_gltf_m":[[px,pz,-py] for px,py,pz in coords]}
        # New loadout stands beside the line, with a spout centred over the same siding.
        bx = 35.0
        slab(silos,"Rail transfer landing",(bx-1.66,bx+1.66,22.6,24.21,6.43,6.69),steel)
        for xx in (bx-1.38,bx+1.38):
            slab(silos,"Rail loadout structural leg",(xx-.18,xx+.18,23.66,24.05,.035,6.46),steel)
            collide("Rail loadout leg",(xx-.18,xx+.18,23.66,24.05,.035,6.46))
            beam("Landing cross brace",silos,(xx,23.87,3.6),(bx,23.76,6.45),(.12,.17),(.12,.17),steel,4)
        beam("Concrete elevator rail delivery chute",silos,(bx,17.35,37.75),(bx,SIDING_Y,6.49),
             (.47,.39),(.21,.21),steel,8)
        beam("Rail loading bell",silos,(bx,SIDING_Y,6.50),(bx,SIDING_Y,5.18),(.21,.21),(.32,.32),steel,8)
        if state == "1990":
            slab(yard,"Low reclaimed annex foundation",(-21,-11,10.8,21.15,.035,.155),cement)
    if near:
        for i in range(7):
            x = -6.75 - 2.4*i
            slab(yard,"Yard fence post",(x-.095,x+.095,23.65,23.83,.032,1.45),steel)
        for z in (.51,1.15):
            slab(yard,"Rear safety rail",(-21.2,-6.65,23.68,23.80,z,z+.10),steel)
        collide("Rear safety rail",(-21.25,-6.65,23.65,23.83,.032,1.45),True,"lot")
        slab(yard,"Office waste cabinet",(-4.91,-4.10,4.35,5.30,.035,1.25),steel)
        collide("Office waste cabinet",(-4.91,-4.10,4.35,5.30,.035,1.25),True,"lot")
    for parent in (office,tower,silos,yard):
        batch(parent)
    name = f"lark-bend-{state}" + ("" if near else f"-{lod}")
    visible = [obj for obj in bpy.data.objects if obj.type=="MESH" and not obj.get("collision_only")]
    triangles = sum(len(p.vertices)-2 for ob in visible for p in ob.data.polygons)
    export_glb(ROOT / f"{name}.glb")
    if near:
        save_source(ROOT / f"{name}.blend")
    return {"file":f"{name}.glb","lod":lod,"visible_triangles":triangles,"doors":doors,
            "colliders":colliders,"sign_panels":signs,"footprint_x_m":[x0,x1],
            "height_m":44.71 if concrete else 32.35}


manifest = {
    "task":"C002","name":"Lark Bend Grain Elevator",
    "axes":"glTF metres, +Y up, street +Z, origin centred on the front edge of the scale office at grade.",
    "bounds_gltf_m":{"x":[-25,46],"y":[0,44.71],"z":[-28.83,4]},
    "swatches":{"wood":"plank_vertical","concrete_palette":"roof_membrane_tan","steel_palette":"roof_tin_brown",
                "roof_palette":"roof_membrane_grey","brick":"brick_red","yard":"asphalt_worn","gravel":"ballast"},
    "lod_distances_suggested_m":{"mid":80,"far":190},
    "rail_siding":{"centerline_gltf_m":[[-27,0,-SIDING_Y],[47,0,-SIDING_Y]],
                   "loading_spout_timber_gltf_m":[-16,5.13,-SIDING_Y],
                   "loading_spout_concrete_gltf_m":[35,5.18,-SIDING_Y],
                   "note":"The game places the track. Spouts are static. Timber spout available through 1989, concrete spout from 1950."},
    "states":[],
}
for key, (title, first, last) in STATES.items():
    models = [build(key,lod) for lod in ("near","mid","far")]
    print(key,[(m["lod"],m["visible_triangles"]) for m in models])
    manifest["states"].append({"id":key,"name":title,"era":[first,last],"models":models})
(ROOT / "model-info.json").write_text(json.dumps(manifest,indent=2)+"\n")
