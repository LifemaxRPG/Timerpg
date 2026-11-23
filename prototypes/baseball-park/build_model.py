"""Build Alder Bend Ballpark in four dated states and three LODs."""
import json
from math import cos, pi, sin, sqrt
from pathlib import Path
import sys

import bpy

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
from sculpture_tools import beam, box, group, loft, material, mesh, plaque
from revision_common import batch, emitter, export_glb, save_source

REGIONS = json.loads((ROOT/"textures/sign-regions.json").read_text())
METRES_PER_TEXEL = .0388
BASE_RUN = 27.432
D = BASE_RUN / sqrt(2)
FOUL = [
    (-72,99),(-72,117),(-60,117),(-60,129),(-43,129),(-43,137),(-23,137),(-23,145),
    (23,145),(23,137),(43,137),(43,129),(60,129),(60,117),(72,117),(72,99),
]
STATES = {
    "1935": ("Original wood park",1935,1949),
    "1950": ("Wood park with lights",1950,1959),
    "1960": ("Steel bleachers",1960,1999),
    "2000": ("Aluminium renovation",2000,None),
}


def slab(parent, name, bounds, mat):
    a,b,c,d,e,f = bounds
    return box(name,parent,((a+b)/2,(c+d)/2,(e+f)/2),(b-a,d-c,f-e),mat)


def ground(parent,name,outline,z,mat):
    signed = sum(x*ny-y*nx for (x,y),(nx,ny) in zip(outline,outline[1:]+outline[:1]))
    verts = [(x,y,z) for x,y in (outline if signed>0 else outline[::-1])]
    return mesh(name,parent,verts,[tuple(range(len(verts)))],mat)


def gable(parent,name,x0,x1,y0,y1,low,high,mat):
    middle = (y0+y1)/2
    verts = [(x,y,z) for x in (x0,x1) for y,z in ((y0,low),(y1,low),(middle,high))]
    return mesh(name,parent,verts,[(2,1,0),(3,4,5),(0,1,4,3),(3,5,2,0),(1,2,5,4)],mat)


def canopy(parent,name,x0,x1,y0,y1,front,rear,mat):
    top = [(x0,y0,rear),(x1,y0,rear),(x1,y1,front),(x0,y1,front)]
    return mesh(name,parent,top+[(x,y,z-.20) for x,y,z in top],
                [(0,1,2,3),(7,6,5,4),(0,4,5,1),(1,5,6,2),(2,6,7,3),(3,7,4,0)],mat)


def build(year,lod):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    near, far = lod == "near", lod == "far"
    lit = year != "1935"
    modern = year == "2000"
    wood_bleachers = year in ("1935","1950")
    root = group("Alder Bend Ballpark")
    park, stand, structures, side, fence, light, forecourt, coll = [
        group(name,root) for name in ("diamond","grandstand","park_buildings","line_bleachers",
                                     "outfield_fence","light_towers","lot","collision")]
    grass = material("ground_grass","grass_mown" if modern else "grass")
    dirt = material("ground_infield","dirt")
    gravel = material("ground_gravel","gravel")
    white = material("markings_chalk","chalk")
    wood = material("wall_park_wood","wood")
    brick = material("wall_brick","brick")
    roof = material("roof_green_sheet","roof")
    metal = material("trim_aluminium" if modern else "trim_painted_steel","aluminium" if modern else "steel")
    signmat = material("sign_paint","signs")
    glass = material("glass_booth","glass")
    emitter(glass,"pane-mask",.27)
    glow = material("glow_floodlights","chalk",.6) if lit else None
    hidden = bpy.data.materials.new("collision_invisible")
    hidden.use_nodes = True
    hidden.surface_render_method = "BLENDED"
    hidden.node_tree.nodes.get("Principled BSDF").inputs["Alpha"].default_value = 0
    doors, collision, panels = {}, [], {}

    def collider(name,bounds,optional=False,attachment=""):
        a,b,c,d,e,f = bounds
        shape = slab(coll,f"collider_{len(collision):02}",bounds,white)
        shape.data.materials.clear()
        shape.data.materials.append(hidden)
        shape["collision_only"] = True
        collision.append({"name":name,"center_gltf_m":[(a+b)/2,(e+f)/2,-(c+d)/2],
                          "size_gltf_m":[b-a,f-e,d-c],"group":"lot" if optional else "building",
                          "attachment":attachment})

    def block(parent,name,bounds,mat,blocking=False):
        slab(parent,name,bounds,mat)
        if blocking:
            collider(name,bounds)

    def anchor(name,x,y,z=.09,yaw=0):
        node = group(name,root)
        node.location = (x,y,z)
        node.rotation_euler.z = yaw
        doors[name] = [x,z,-y]

    def inked(parent,name,key,x,y,z,reverse=False):
        rect = REGIONS[key]
        w,h = rect[2]*METRES_PER_TEXEL,rect[3]*METRES_PER_TEXEL
        a,b = (x+w/2,x-w/2) if reverse else (x-w/2,x+w/2)
        ob = plaque(name,parent,[(a,y,z),(b,y,z),(b,y,z+h),(a,y,z+h)],signmat,rect,(512,512))
        ob["keep"] = True
        panels[name] = {"texture":"signs.png","atlas_size_texels":[512,512],"rect_texels":rect,
                        "bottom_left_gltf_m":[a,z,-y],"width_m":w,"height_m":h}

    def pane(name,x,y,z):
        w = 32*METRES_PER_TEXEL
        plaque(name,structures,[(x-w/2,y,z),(x+w/2,y,z),(x+w/2,y,z+w),(x-w/2,y,z+w)],
               glass,(0,0,32,32),(32,32))

    # Ground: original survey geometry, dirt cutouts, 90-foot bases and foul lines.
    ground(park,"Maintained grass outfield",[(-25,17),(25,17)]+list(reversed(FOUL)),.028,grass)
    ground(park,"Rounded infield apron",[(0,20.7)]+[(r*sin(a*pi/180),27+r*cos(a*pi/180))
           for a in range(60,-61,-10) for r in (48,)],.041,dirt)
    ground(park,"Inner diamond",[(0,33.5),(D-3.4,27+D),(0,27+2*D-4.1),(-D+3.4,27+D)],.052,grass)
    ground(park,"Home-plate dirt",[(4.6*cos(i*pi/8),27+4.6*sin(i*pi/8)) for i in range(16)],.051,dirt)
    for i, (x,y) in enumerate(((-D,27+D),(0,27+2*D),(D,27+D))):
        outline = [(x,y+.27),(x+.27,y),(x,y-.27),(x-.27,y)]
        ground(park,f"Base {3-i}",outline,.076,white)
    ground(park,"Home plate",[(-.22,27.19),(.22,27.19),(.22,26.97),(0,26.75),(-.22,26.97)],.076,white)
    mound_y = 27+18.44
    loft("Pitching mound",park,[[(r*cos(i*pi/8),mound_y+r*sin(i*pi/8),z) for i in range(16)]
         for r,z in ((2.74,.055),(1.40,.29))],dirt)
    slab(park,"Pitching rubber",(-.30,.30,mound_y-.09,mound_y+.08,.29,.315),white)
    for sign in (-1,1):
        a,b = .88,72
        ground(park,"Foul chalk",[(sign*a,27+a),(sign*b,27+b),(sign*b,27+b+.102),(sign*a,27+a+.102)],.071,white)
        for y in (25.59,28.03):
            x0,x1 = (.52,1.82) if sign==1 else (-1.82,-.52)
            slab(park,"Batter box transverse chalk",(x0,x1,y,y+.0776,.075,.079),white)
        for x in (.52,1.82):
            slab(park,"Batter box long chalk",(sign*x-.0388,sign*x+.0388,25.59,28.1076,.075,.079),white)
    for x in (-72,72):
        slab(fence,"Foul pole",(x-.085,x+.085,99-.085,99+.085,.028,7.1),white)
        collider("Foul pole",(x-.085,x+.085,99-.085,99+.085,.028,7.1))

    # Ticket frontage, gravel parking, clear central entry and shared routes.
    slab(forecourt,"Gravel parking lot",(-36,36,-10,18.8,0,.025),gravel)
    slab(forecourt,"Park approach apron",(-17.8,17.8,-.8,18.8,.026,.078),dirt)
    for x in (-33,-28,-23,-18,18,23,28,33):
        slab(forecourt,"Parking bay line",(x-.04,x+.04,-9.4,-5.1,.026,.029),white)
    for a,b in ((-18,-2.3),(2.3,18)):
        block(structures,"Entry brick boundary",(a,b,-.04,.26,.025,1.78),brick,True)
        slab(structures,"Entry fence cap",(a-.05,b+.05,-.08,.29,1.74,1.89),white)
    for x in (-2.45,2.45):
        block(structures,"Gate post",(x-.16,x+.16,-.16,.28,.03,3.75),brick,True)
        slab(structures,"Gate post cap",(x-.21,x+.21,-.18,.33,3.68,3.78),white)
    slab(structures,"Park gate timber marquee",(-9.45,9.45,-.13,.22,3.22,5.26),wood)
    for x in (-8.92,8.92):
        block(structures,"Marquee outer pillar",(x-.23,x+.23,-.11,.36,.09,3.28),brick,True)
    inked(structures,"sign_name","name",0,-.143,3.30)
    anchor("door_main",0,-.34)

    def booth(label, x, y, w, length, opening, sign_key):
        x0,x1 = x-w/2,x+w/2
        y0,y1 = y,y+length
        block(structures,label+" slab",(x0,x1,y0,y1,.03,.09),white)
        door_x = x+.27*w
        a,b = door_x-.66,door_x+.66
        window_x = x-.21*w
        wa,wb = window_x-.6208,window_x+.6208
        for left,right in ((x0,wa),(wb,a),(b,x1)):
            block(structures,label+" front wall",(left,right,y0,y0+.18,.09,3.15),wood)
        block(structures,label+" glazed opening base",(wa,wb,y0,y0+.18,.09,1.07),wood)
        for left,right in ((x0,a),(b,x1)):
            collider(label+" front with fixed glazing",(left,right,y0,y0+.18,.09,3.15))
        block(structures,label+" glazed opening head",(wa,wb,y0,y0+.18,2.3116,3.15),wood)
        block(structures,label+" door lintel",(a,b,y0,y0+.18,2.55,3.15),wood)
        for left,right in ((x0,x0+.18),(x1-.18,x1)):
            block(structures,label+" side wall",(left,right,y0+.18,y1-.18,.09,3.15),wood,True)
        block(structures,label+" back wall",(x0,x1,y1-.18,y1,.09,3.15),brick,True)
        for xx in (a,b):
            slab(structures,label+" doorway casing",(xx-.033,xx+.033,y0-.048,y0+.18,.09,2.55),metal)
        gable(structures,label+" gable",x0-.23,x1+.23,y0-.39,y1+.40,3.11,3.99,roof)
        slab(structures,label+" ceiling",(x0+.04,x1-.04,y0+.04,y1-.04,3.05,3.16),wood)
        wx = window_x
        pane(label+" service glazing",wx,y0+.078,1.07)
        slab(structures,label+" service ledge",(wx-.8,wx+.8,y0-.23,y0+.053,.93,1.04),metal)
        slab(structures,label+" fixed counter",(x0+.25,x0+1.31,y0+1.20,y1-.24,.09,1.03),wood)
        collider(label+" counter",(x0+.25,x0+1.31,y0+1.20,y1-.24,.09,1.03))
        inked(structures,"sign_"+opening,sign_key,x,y0-.031,2.55)
        anchor("door_"+opening,door_x,y0-.31)
    booth("Ticket booth",-11.8,2.4,5.3,4.7,"tickets","tickets")
    booth("Concession stand",12,2.4,8.1,6.0,"concessions","concession")

    # 600 nominal places in three 20-seat bays. Busy detail resides in strips and row geometry.
    block(stand,"Grandstand undercroft floor",(-18,18,8.6,19.4,.026,.09),dirt)
    for a,b in ((-18,-2.6),(2.6,18)):
        block(stand,"Grandstand split rear wall",(a,b,8.67,8.92,.09,3.58),brick,True)
    for x in (-17.85,-11.0,11.0,17.85):
        block(stand,"Grandstand side support",(x-.14,x+.14,8.9,9.35,.09,8.3),wood,True)
    for x in (-17.35,-5.64,5.64,17.35):
        block(stand,"Field-side canopy column",(x-.14,x+.14,18.98,19.32,.09,8.0),wood,True)
        slab(stand,"Timber truss bottom chord",(x-.105,x+.105,9.04,19.27,7.43,7.60),wood)
        if not far:
            for y in (11.5,15.6):
                beam("Canopy truss diagonals",stand,(x,y-1.8,7.5),(x,y+1.0,8.23),
                     (.08,.105),(.08,.105),wood,4)
    canopy(stand,"Covered grandstand roof",-18.73,18.73,8.05,20.19,8.26,9.38,roof)
    slab(stand,"Grandstand front fascia",(-18.74,18.74,20.08,20.21,7.89,8.20),wood)
    for bay,(a,b) in enumerate(((-16.93,-7.33),(-4.8,4.8),(7.33,16.93))):
        # Per-bay collision reserves the spectator banks. The concourse remains open below them.
        for r in range(10):
            row_y = 18.36-r*.76
            z = .43+r*.31
            deck = (.10 if r==0 else z-.31)
            slab(stand,"Grandstand terrace",(a,b,row_y-.67,row_y+.09,deck,z),wood)
            if not far or r%2==0:
                slab(stand,"Grandstand seating bench",(a,b,row_y-.36,row_y+.06,z+.35,z+.44),
                     metal if modern else wood)
                if near:
                    for x in (a+.56,(a+b)/2,b-.56):
                        slab(stand,"Grandstand bench riser",(x-.055,x+.055,row_y-.30,row_y-.19,z,z+.35),metal)
        collider(f"Seating bank {bay+1}",(a,b,11.05,18.46,.09,3.71))
    for middle in (-6.065,6.065):
        for r in range(10):
            y = 18.48-r*.76
            z = .40+r*.31
            slab(stand,"Stand stepped aisle",(middle-.48,middle+.48,y-.75,y,.09,z),wood)
        for x in (middle-.49,middle+.49):
            beam("Raked aisle rail",stand,(x,18.46,1.04),(x,11.70,3.99),(.045,.045),(.045,.045),metal,4)
    for x in (-16.0,-8.65,-3.25,3.25,8.65,16.0):
        beam("Raked wooden stringer",stand,(x,18.49,.21),(x,11.15,3.12),(.105,.135),(.105,.135),wood,4)
        for y,z in ((12.33,2.74),(15.47,1.49),(17.94,.48)):
            slab(stand,"Grandstand trestle post",(x-.092,x+.092,y-.095,y+.095,.09,z),wood)
        if not far:
            beam("Trestle knee brace",stand,(x,12.33,.81),(x,13.56,2.28),(.061,.080),(.061,.080),wood,4)
    slab(stand,"Protected front bench rail",(-17,17,18.72,18.82,1.33,1.46),wood)

    # Open mesh backstop drawn with thin strips; no opaque panel behind the batter.
    for x in (-7.65,-3.825,0,3.825,7.65):
        block(park,"Backstop mast",(x-.085,x+.085,20.5,20.69,.028,6.2),metal)
    for z in (1.15,3.65,6.09):
        slab(park,"Backstop horizontal cable",(-7.72,7.72,20.55,20.61,z,z+.043),metal)
    if not far:
        for n in range(-30,31,1 if near else 3):
            x=n*.245
            slab(park,"Backstop wire strand",(x-.012,x+.012,20.569,20.593,.7,6.10),metal)
    collider("Backstop screen",(-7.74,7.74,20.50,20.69,.028,6.20))

    # Side bleachers and the two roofed dugouts leave generous spectator paths.
    for s in (-1,1):
        center = s*26.8
        stand_mat = wood if wood_bleachers else metal
        for r in range(5):
            x = center+s*.85*r
            z = .48+r*.39
            slab(side,"Foul line footboards",(x-.36,x+.36,25.2,41.4,z-.15,z),stand_mat)
            if not far or r%2==0:
                slab(side,"Secondary bleacher bench",(x-.28,x+.28,25.20,41.4,z+.34,z+.45),stand_mat)
            for y in ((25.85,30.1,36.55,40.75) if near else (25.85,40.75)):
                slab(side,"Bleacher upright",(x-.086,x+.086,y-.08,y+.08,.026,z),metal if not wood_bleachers else wood)
        a,b = sorted((center-s*.5,center+s*3.90))
        collider("Secondary bleachers",(a,b,25.2,41.40,.026,2.49))
        x = s*18.90
        block(structures,"Dugout floor",(x-4.58,x+4.58,21.52,25.54,.026,.09),dirt)
        block(structures,"Dugout rear wall",(x-4.58,x+4.58,21.52,21.79,.09,2.48),brick,True)
        for l,r in ((x-4.58,x-4.35),(x+4.35,x+4.58)):
            block(structures,"Dugout return",(l,r,21.79,25.55,.09,2.48),wood,True)
        slab(structures,"Dugout bench",(x-4.12,x+4.12,22.06,22.62,.48,.58),wood)
        collider("Dugout bench",(x-4.12,x+4.12,22.06,22.62,.09,.58))
        canopy(structures,"Dugout sheet canopy",x-4.69,x+4.69,21.33,25.74,2.55,2.94,roof)
        for y in (25.11,25.45):
            slab(structures,"Dugout open rail",(x-3.22,x+3.22,y,y+.09,.94,1.03),metal)

    # Wood board outfield fence, fictional painted ads, and switch from hand board to LED-style board.
    track = [(x,y) for x,y in FOUL] + [(x*.958,27+(y-27)*.971) for x,y in reversed(FOUL)]
    ground(park,"Outfield warning track",track,.041,dirt)
    for i,((x,y),(nx,ny)) in enumerate(zip(FOUL,FOUL[1:])):
        a,b = sorted((x,nx))
        c,d = sorted((y,ny))
        bounds = (a-.115,b+.115,c-.115,d+.115,.028,2.61)
        block(fence,"Stepped outfield fence",bounds,wood,True)
        slab(fence,"Outfield cap",(a-.16,b+.16,c-.16,d+.16,2.55,2.69),roof)
        if near:
            for k in range(1, int(max(b-a,d-c)/3.8)+1):
                t = k/(int(max(b-a,d-c)/3.8)+1)
                px,py = x+t*(nx-x),y+t*(ny-y)
                slab(fence,"Rear fence pilaster",(px-.15,px+.15,py-.15,py+.15,.028,2.64),wood)
    for i,(x,y,key) in enumerate(((-64.7,117,"ad_coal"),(-32.4,137,"ad_feed"),
                                  (0,145,"ad_tires"),(36.5,137,"ad_soda"))):
        inked(fence,f"sign_ad_{i+1}",key,x,y-.132,.43)
    board_x,board_y = (15.4,143.85)
    board_key = "board_new" if modern else "board_old"
    board_w = REGIONS[board_key][2]*METRES_PER_TEXEL
    board_h = REGIONS[board_key][3]*METRES_PER_TEXEL
    z = 3.16
    for x in (board_x-board_w*.37,board_x+board_w*.37):
        block(fence,"Scoreboard upright",(x-.16,x+.16,board_y,board_y+.40,.028,z+board_h+.46),
              metal if modern else wood,True)
    slab(fence,"Scoreboard casing",(board_x-board_w/2-.22,board_x+board_w/2+.22,
        board_y-.12,board_y+.32,z-.20,z+board_h+.22),metal if modern else wood)
    inked(fence,"sign_scoreboard",board_key,board_x,board_y-.136,z)

    # One bank of lenses per mast, separately emissive. Real flood lighting is a runtime handoff.
    if lit:
        for x,y in ((-43,50),(43,50),(-62,110),(62,110)):
            block(light,"Flood mast footing",(x-.46,x+.46,y-.46,y+.46,0,.75),brick)
            collider("Flood mast",(x-.46,x+.46,y-.46,y+.46,0,17.4))
            beam("Tapered flood mast",light,(x,y,.71),(x,y,16.36),(.25,.25),(.12,.12),metal,8)
            slab(light,"Flood headstock",(x-1.13,x+1.13,y-.14,y+.15,16.23,17.37),metal)
            toward = 1 if y<80 else -1
            for xx in (x-.69,x,x+.69):
                for zz in (16.53,17.04):
                    bulb = y+toward*.17
                    slab(light,"Hooded flood lamp",(xx-.24,xx+.24,bulb-.055,bulb+.055,zz-.17,zz+.17),glow)
    for parent in (park,stand,structures,side,fence,light,forecourt):
        batch(parent)
    file = f"alder-bend-{year}" + ("" if near else f"-{lod}")
    visible = [ob for ob in bpy.data.objects if ob.type=="MESH" and not ob.get("collision_only")]
    tris = sum(len(p.vertices)-2 for ob in visible for p in ob.data.polygons)
    export_glb(ROOT/f"{file}.glb")
    if near:
        save_source(ROOT/f"{file}.blend")
    return {"file":f"{file}.glb","lod":lod,"visible_triangles":tris,
            "door_nodes_gltf_m":doors,"collision_boxes_gltf_m":collision,"sign_panels":panels}


HEADING = "Y up, metres. +Z street-facing, origin at grade at midpoint of the main gate."
metadata = {
    "task":"C003","name":"Alder Bend Ballpark","axes":HEADING,
    "footprint_m":[144.92,155.52],"height_m":17.37,
    "lot_bounds_gltf_m":{"x":[-72.5,72.5],"y":[0,17.37],"z":[-145.5,10]},
    "ballfield":{"basepath_m":BASE_RUN,"nominal_grandstand_places":600,
                 "seat_pitch_m":.48,"seat_rows":10,"seat_bays":3,
                 "home_plate_gltf_m":[0,.076,-27],
                 "first_base_gltf_m":[D,.076,-27-D],"second_base_gltf_m":[0,.076,-27-2*D],
                 "third_base_gltf_m":[-D,.076,-27-D],
                 "mound_gltf_m":[0,.315,-45.44],"center_field_m":118},
    "swatches":["plank_horizontal","brick_red","ballast","roof_membrane_green","roof_membrane_tan","roof_membrane_grey"],
    "lod_distances_suggested_m":{"mid":90,"far":175},
    "states":[],
}
for y,(label,start,end) in STATES.items():
    models = [build(y,lod) for lod in ("near","mid","far")]
    print(y,[(m["lod"],m["visible_triangles"]) for m in models])
    metadata["states"].append({"id":y,"title":label,"era":[start,end],"models":models})
(ROOT/"model-info.json").write_text(json.dumps(metadata,indent=2)+"\n")
