"""Build Briarford's police station, its town jail and later additions."""
import json
from math import cos, pi, sin
from pathlib import Path
import sys

import bpy

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
from revision_common import batch, emitter, export_glb, save_source
from sculpture_tools import beam, box, group, material, mesh, oval, plaque

T = .0388
P = 32 * T
FLOOR = .045
REGIONS = json.loads((ROOT / "textures/sign-regions.json").read_text())
ERAS = {"1900": [1900, 1939], "1940": [1940, 1979], "1980": [1980, None]}


def gltf(x, y, z):
    return [round(v, 4) for v in (x, z, -y)]


def build(era, lod):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    near, far = lod == "near", lod == "far"
    old, modern = era == "1900", era == "1980"
    root = group("Briarford Police Station")
    shell, interior, lot, cold = [group(n, root) for n in ("shell", "interior", "lot", "collision")]
    brick = material("wall_brick", "brick")
    limestone = material("wall_limestone", "stone")
    block = material("wall_dressed_block", "block")
    wood = material("detail_joinery", "wood")
    metal = material("detail_ironwork", "metal")
    roof = material("roof_bitumen", "roof")
    tile = material("floor_interior", "floor")
    asphalt = material("ground_paving", "asphalt")
    sign = material("sign_station", "signs")
    glow = material("glow_entrance", "globe", .46)
    glass = material("glass_station", "sash")
    emitter(glass, "sash-mask", .26)
    restricted = material("glass_restricted", "vestibule" if modern else "cell")
    emitter(restricted, "vestibule-mask" if modern else "cell-mask", .10)
    if modern:
        tree = restricted.node_tree
        sampler = next(n for n in tree.nodes if n.type == "TEX_IMAGE" and n.image.name.startswith("vestibule.png"))
        tree.links.new(sampler.outputs["Alpha"], tree.nodes["Principled BSDF"].inputs["Alpha"])
    hidden = bpy.data.materials.new("collision_invisible")
    hidden.use_nodes = True
    hidden.surface_render_method = "BLENDED"
    hidden.node_tree.nodes["Principled BSDF"].inputs["Alpha"].default_value = 0
    collision, door_info, anchors, cells, features = [], {}, {}, [], {}

    def slab(label, parent, b, mat, *, keep=False):
        a, c, d, e, f, h = b
        ob = box(label, parent, ((a+c)/2, (d+e)/2, (f+h)/2), (c-a, e-d, h-f), mat)
        if keep:
            ob["keep"] = True
        return ob

    def collider(label, b, parent=shell, attachment=None):
        a, c, d, e, f, h = b
        ob = slab(f"collision_{len(collision):03d}", cold, b, metal)
        ob.data.materials.clear()
        ob.data.materials.append(hidden)
        ob["collision_only"] = True
        collision.append({"name": label, "center_gltf_m": gltf((a+c)/2, (d+e)/2, (f+h)/2),
                          "size_gltf_m": [round(v, 4) for v in (c-a, h-f, e-d)],
                          "group": "lot" if parent == lot else "building", "attachment": attachment or ""})

    def solid(label, parent, b, mat, *, blocks=False, keep=False):
        ob = slab(label, parent, b, mat, keep=keep)
        if blocks:
            collider(label, b, parent)
        return ob

    def marker(name, p, parent=root, yaw=0):
        ob = group(name, parent)
        ob.location = p
        ob.rotation_euler.z = yaw
        return ob

    def door(name, p, external=True, yaw=0):
        marker(name, p, yaw=yaw)
        door_info[name] = {"position": gltf(*p), "external": external,
                           "front_gltf": [round(sin(yaw), 4), 0, round(cos(yaw), 4)]}

    def point(name, p):
        marker(name, p)
        anchors[name] = gltf(*p)

    def quad(a, b, v, low, high, axis, reverse=False):
        pts = [(a, v, low), (b, v, low), (b, v, high), (a, v, high)]
        if axis == "y":
            pts = [(y, x, z) for x, y, z in pts]
        return [pts[i] for i in (1, 0, 3, 2)] if reverse else pts

    def panel(name, key, u, v, z, axis="x", reverse=False, parent=shell):
        rect = REGIONS[key]
        w, h = rect[2]*T, rect[3]*T
        ob = plaque(name, parent, quad(u-w/2, u+w/2, v, z, z+h, axis, reverse),
                    sign, rect, (512, 256))
        ob["keep"] = name.startswith(("sign_name", "wanted_paper_"))
        return ob

    def wall(label, parent, edge, depth, top, mat, openings=(), axis="x",
             *, bottom=FLOOR, sill=False, outside=0, collides=True):
        """Skin wall around real apertures and collide the uninterrupted walking spans."""
        start, end = edge
        d0, d1 = depth
        vertices, faces = [], []

        def xyz(u, v, z):
            return (u, v, z) if axis == "x" else (v, u, z)

        def face(points):
            i = len(vertices)
            vertices.extend(xyz(*p) for p in points)
            faces.append([i+j for j in (range(4) if axis == "x" else reversed(range(4)))])

        def skins(a, b, low, high):
            if b-a <= 1e-6 or high-low <= 1e-6:
                return
            face(((a,d0,low),(b,d0,low),(b,d0,high),(a,d0,high)))
            face(((b,d1,low),(a,d1,low),(a,d1,high),(b,d1,high)))

        def reveal(u, low, high, left):
            points = ((u,d0,low),(u,d1,low),(u,d1,high),(u,d0,high))
            face(points if left else points[::-1])

        def horizontal(a, b, z, up):
            points = ((a,d0,z),(b,d0,z),(b,d1,z),(a,d1,z))
            face(points if up else points[::-1])

        def bounds(a, b, low, high):
            return (a,b,d0,d1,low,high) if axis == "x" else (d0,d1,a,b,low,high)

        prev = walk = start
        top_only = bottom > .2
        for a, b, low, high, kind in sorted(openings):
            assert prev <= a < b <= end and bottom <= low < high <= top, (label, a, b, low, high)
            skins(prev, a, bottom, top)
            skins(a,b,bottom,low)
            skins(a,b,high,top)
            reveal(a,low,high,True)
            reveal(b,low,high,False)
            horizontal(a,b,high,False)
            if kind == "door":
                if walk < a and not top_only and collides:
                    collider(label, bounds(walk,a,bottom if parent == interior else 0,top), parent)
                walk = b
            else:
                horizontal(a,b,low,True)
                material_ = restricted if kind == "frost" or kind == "clear" else glass
                v = (d0+d1)/2
                plaque(label + " sash", parent, quad(a,b,v,low,high,axis), material_,
                       (0,0,(b-a)/T,(high-low)/T),(32,32))
                if sill and not far:
                    out = (d0 if outside == 0 else d1)
                    bds = (a-.09,b+.09,out-.095,out+.095,low-.085,low)
                    if axis == "y":
                        bds = (bds[2],bds[3],bds[0],bds[1],bds[4],bds[5])
                    slab(label + " sill", parent, bds, limestone)
            prev = b
        skins(prev,end,bottom,top)
        horizontal(start,end,top,True)
        reveal(start,bottom,top,False)
        reveal(end,bottom,top,True)
        if walk < end and not top_only and collides:
            collider(label,bounds(walk,end,bottom if parent == interior else 0,top),parent)
        return mesh(label,parent,vertices,faces,mat)

    def sash(x, z, width=P, height=P, kind="sash"):
        return (x-width/2,x+width/2,z,z+height,kind)

    def opening(a,b,high=2.36):
        return (a,b,FLOOR,high,"door")

    # One site datum for all three releases. The public door always faces the street.
    slab("Paved lot",lot,(-11.7,15,-4.5,25.5,0,.029),asphalt)
    slab("Front walk",lot,(-9,11,-3.7,-.05,.03,.042),limestone)
    slab("West verge",lot,(-11.5,-9,-3.6,22,.03,.042),block)
    slab("Public wing floor",interior,(-7.45,7.45,0,10,0,FLOOR),tile)
    slab("Town jail passage floor",interior,(-5.6,5.6,10,13.41,0,FLOOR),tile)
    slab("Upper station floor",shell,(-7.35,7.35,.16,9.8,3.57,3.73),tile)
    slab("Plinth front",shell,(-7.48,-1.22,-.075,.10,.039,.39),limestone)
    slab("Plinth front",shell,(1.22,7.48,-.075,.10,.039,.39),limestone)
    for x0,x1 in ((-7.48,-7.40),(7.40,7.48)):
        slab("Returns of plinth",shell,(x0,x1,0,10,.039,.42),limestone)
    # Ground and upper openings use their own strip so all apertures are real.
    g = [sash(x,1.02,height=1.86) for x in (-5.72,-3.52,3.52,5.72)]
    g.append(opening(-1.12,1.12,2.55))
    wall("Public facade",shell,(-7.45,7.45),(0,.31),3.66,brick,g,sill=True)
    wall("Upper public facade",shell,(-7.45,7.45),(0,.31),6.75,brick,
         [sash(x,4.35,height=1.66) for x in (-5.72,-3.52,3.52,5.72)] +
         [sash(x,4.87,height=1.30) for x in (-.83,.83)],
         bottom=3.66,sill=True)
    for third, x in enumerate((-7.45,7.15)):
        wall(f"Station flank {third}",shell,(.31,9.72),(x,x+.30),3.66,brick,
             [sash(u,1.08,height=1.55) for u in (2.6,5.6)] +
             ([opening(8.18,9.38)] if third and not old else [sash(8.5,1.08)]),
             axis="y",sill=True,outside=third)
        wall(f"Upper station flank {third}",shell,(.31,9.72),(x,x+.30),6.75,brick,
             [sash(u,4.38,height=1.55) for u in (2.6,5.6,8.5)],
             axis="y",bottom=3.66,sill=True,outside=third)
    wall("Return behind office",shell,(-7.45,7.45),(9.72,10),3.66,brick,
         [sash(-6.44,1.28,width=.86),opening(-1.10,1.10),sash(6.44,1.28,width=.86)],sill=True,outside=1)
    wall("Upper station rear",shell,(-7.45,7.45),(9.72,10),6.75,brick,
         [sash(x,4.38,height=1.55) for x in (-5.7,-3.45,3.45,5.7)],bottom=3.66,sill=True,outside=1)
    slab("Station roof",shell,(-7.46,7.46,0,10,6.60,6.77),roof)
    for x in (-3.27,3.27):
        slab("Roof drainage strip",shell,(x-.13,x+.13,.24,9.74,6.77,6.785),metal)
    if not old:
        for x in (-3.84,-2.58):
            slab("Roof ventilation pedestal",shell,(x-.36,x+.36,7.23,8.05,6.77,7.06),metal)
            slab("Roof ventilation hood",shell,(x-.48,x+.48,7.17,8.11,7.06,7.20),limestone)
    for z0,z1,over in ((3.58,3.72,.12),(6.56,6.75,.16),(6.79,7.01,.24)):
        for a,b in ((-over,.24),(9.81,10+over)):
            slab("Limestone stringcourse",shell,(-7.45-over,7.45+over,a,b,z0,z1),limestone)
        for a,b in ((-7.45-over,-7.23),(7.23,7.45+over)):
            slab("Limestone stringcourse return",shell,(a,b,.24,9.81,z0,z1),limestone)
    for x in (-7.23,7.23):
        for z in (0.55,1.14,1.73,2.32,2.91,4.05,4.64,5.23,5.82):
            slab("Stone quoin",shell,(x-.27,x+.27,-.032,.022,z,z+.20),limestone)
    # A raised central parapet, carved arch surround and broad stone name tablet.
    for w,z0,z1,mat in ((5.78,6.77,7.38,brick),(4.85,7.38,7.60,brick),
                        (4.0,7.60,7.80,limestone),(4.16,7.80,7.93,limestone)):
        slab("Civic parapet",shell,(-w/2,w/2,-.09,.52,z0,z1),mat)
    slab("Name tablet stone bed",shell,(-3.8,3.8,-.38,-.035,3.47,4.62),limestone)
    panel("sign_name", "name", 0,-.386,3.53)
    for i in range(9):
        angle0,angle1 = pi*i/9+.009, pi*(i+1)/9-.009
        coordinates = [(r*cos(a),1.99+r*sin(a)) for r,a in
                       ((1.10,angle0),(1.43,angle0),(1.43,angle1),(1.10,angle1))]
        points = [(x,y,z) for y in (-.23,-.005) for x,z in coordinates]
        if not modern:
            mesh("Limestone arch voussoir",shell,points,
                 [(0,1,2,3),(7,6,5,4),(4,5,1,0),(5,6,2,1),(6,7,3,2),(7,4,0,3)],limestone)
    if not modern:
        for x in (-1.275,1.275):
            slab("Portal jamb",shell,(x-.155,x+.155,-.23,-.002,.042,1.99),limestone)
    door("door_main",(0,-2.67 if modern else -.19,0))
    door("door_back",(0,19.16,FLOOR),yaw=pi)
    if not old:
        door("door_staff",(7.61,8.78,FLOOR),yaw=pi/2)
    if modern:
        # Projected glass enclosure built in front of the original masonry opening.
        for a,b in ((-2.75,-1.2),(1.2,2.75)):
            slab("Vestibule curb",shell,(a,b,-2.50,-2.36,.039,.22),limestone)
        wall("Vestibule front",shell,(-2.75,2.75),(-2.49,-2.34),2.72,metal,
             (sash(-1.97,.215,1.36,2.24,"clear"),opening(-1.12,1.12,2.46),
              sash(1.97,.215,1.36,2.24,"clear")))
        for i,x in enumerate((-2.75,2.60)):
            wall("Vestibule glass return",shell,(-2.34,0),(x,x+.15),2.72,metal,
                 (sash(-1.17,.215,2.14,2.24,"clear"),),axis="y")
        slab("Canopy broad cap",shell,(-2.97,2.97,-2.72,.1,2.69,3.12),limestone)
        slab("Vestibule walking floor",interior,(-2.75,2.75,-2.49,0,0,FLOOR),tile)
    panel("Police station lettering","police",0,-.104,7.03)
    if era == "1940":
        slab("Radio-era enamel entry hood",shell,(-1.64,1.64,-.93,-.08,2.71,2.92),metal)
        slab("Entry hood edge",shell,(-1.67,1.67,-.985,-.93,2.72,2.96),limestone)
    y_globe = -2.86 if modern else -1.00 if era == "1940" else -.57
    z_globe = 2.58 if modern else 2.59 if era == "1940" else 2.83
    beam("Globe iron arm",shell,(0,y_globe+.45,z_globe+.16),(0,y_globe,z_globe+.16),
         (.045,.045),(.045,.045),metal,6)
    oval("Police entrance globe",shell,[(0,y_globe,z_globe+k,.17*s,.16*s) for k,s in
         ((-.15,.45),(-.09,.9),(.075,1),(.13,.52))],glow,10)
    if old:
        features["wanted_posters"] = [{"node": f"wanted_paper_{i}", "texture": "signs.png",
                                       "rect": REGIONS[f"paper_{i}"]} for i in (1,2)]
        slab("Wanted notice cabinet",shell,(2.03,4.31,-.2,-.05,.79,2.73),wood)
        panel("Wanted notice heading","wanted",3.17,-.211,1.83)
        for i in range(2):
            panel(f"wanted_paper_{i+1}",f"paper_{i+1}",2.64+i,-.212,.91)
        for x in (-5.55,-3.01):
            slab("Hitching post",lot,(x-.09,x+.09,-2.83,-2.65,.04,.96),metal)
        slab("Hitching rail",lot,(-5.65,-2.9,-2.80,-2.68,.84,.96),metal)
        collider("Hitching rail",(-5.65,-2.9,-2.83,-2.65,.038,.96),lot)
    else:
        slab("Foundation date stone",shell,(2.62,3.42,-.09,-.005,.79,1.08),limestone)
        # Roof radio aerial on a braced tower, separate from game simulation.
        mast = marker("radio_mast",(5.05,7.95,6.78),shell)
        for dx,dy in ((-.38,-.34),(.38,-.34),(0,.44)):
            beam("Mast leg",mast,(dx,dy,0),(dx*.28,dy*.28,4.55),(.045,.045),(.025,.025),metal,4)
        for z in (.0,1.05,2.10,3.15):
            s=1-z/6.25
            r=1-(z+1.05)/6.25
            triangle=[(-.38,-.34),(.38,-.34),(0,.44)]
            for (x,y),(a,b) in zip(triangle,triangle[1:]+triangle[:1]):
                beam("Mast brace",mast,(x*s,y*s,z),(a*r,b*r,z+1.05),(.038,.038),(.038,.038),metal,4)
        beam("Aerial",mast,(0,0,3.9),(0,0,6.22),(.034,.034),(.025,.025),metal,5)
        beam("Aerial dipole",mast,(-.8,0,5.23),(.8,0,5.23),(.029,.029),(.027,.027),metal,4)
    for x,y in ((-6.2,8.3),(5.8,8.8)):
        slab("Vent stack",shell,(x-.37,x+.37,y-.32,y+.32,6.67,7.54),brick)
        slab("Vent stack cap",shell,(x-.43,x+.43,y-.37,y+.37,7.52,7.67),limestone)

    # Interior: public lobby, counter, chief's office and two rooms on the opposite side.
    for x in (-2.17,2.17):
        wall("Lobby dividing wall",interior,(3.04,9.72),(x-.085,x+.085),3.56,block,
             (opening(4.23,5.37),opening(7.4,8.58)),axis="y")
    wall("Chief room division",interior,(-7.15,-2.255),(6.30,6.46),3.56,block)
    wall("East room division",interior,(2.255,7.15),(6.30,6.46),3.56,block,
         (sash(4.7,1.15,1.24,1.24,"frost"),) if not old else ())
    slab("Front desk body",interior,(-1.83,1.78,3.73,4.57,FLOOR,1.10),wood)
    slab("Front desk raised top",interior,(-1.96,1.90,3.65,4.64,1.10,1.24),limestone)
    collider("Sergeant counter",(-1.96,1.90,3.65,4.64,FLOOR,1.24),interior)
    panel("Fixed front desk label","desk",0,3.714,.16,parent=interior)
    point("front_desk_officer",(0,5.18,FLOOR))
    panel("Chief office label","chief",-4.70,6.287,2.54,parent=interior)
    if near:
        solid("Chief fixed writing shelf",interior,(-7.14,-4.47,5.58,6.27,FLOOR,.79),wood,blocks=True)
    if not old:
        panel("Booking label","booking",4.7,6.484,2.61,reverse=True,parent=interior)
        # Scale marks and date board are independent of actor height.
        panel("Booking height chart","height",4.47,9.703,.10,parent=interior)
        solid("Fingerprint counter",interior,(5.62,7.12,6.7,7.48,FLOOR,1.03),wood,blocks=True)
        point("booking_spot",(4.47,9.18,FLOOR))
        point("fingerprint_spot",(6.43,7.82,FLOOR))
        stand = marker("booking_camera_stand",(4.55,7.56,FLOOR),interior)
        if near:
            for x,y in ((-.28,-.25),(.28,-.25),(0,.30)):
                beam("Camera tripod leg",stand,(x,y,0),(0,0,1.17),(.032,.032),(.032,.032),metal,4)
            slab("Booking camera",stand,(-.18,.18,-.11,.17,1.14,1.40),metal)
            collider("Booking camera stand",(4.25,4.85,7.28,7.87,FLOOR,1.44),interior)
        panel("Interview room label","interview",4.72,6.279,2.65,parent=interior)
        slab("Interview table",interior,(4.05,5.35,3.46,4.22,.69,.81),wood)
        slab("Interview table pedestal",interior,(4.54,4.86,3.66,4.02,FLOOR,.69),metal)
        collider("Interview table",(4.05,5.35,3.46,4.22,FLOOR,.81),interior)
        point("interview_chair",(4.70,4.76,FLOOR))
        features["one_way_window"] = {"position": gltf(4.7,6.38,1.77),
                                       "note":"Read as reflective fixed glass; one-way visibility is a runtime role."}
        if modern:
            panel("Records room label","records",-4.77,6.475,2.51,reverse=True,parent=interior)
            for x in (-6.67,-3.01):
                solid("Records fixed shelving",interior,(x-.28,x+.28,7.0,9.58,FLOOR,2.25),wood,blocks=True)
            point("records_room",(-4.78,8.5,FLOOR))
    # Behind the station: a continuous jail block. The door to the block swings on its own node.
    jail_h=3.60
    for i,x in enumerate((-5.6,5.33)):
        wall("Jail flank",shell,(10,18.78),(x,x+.27),jail_h,brick,
             (opening(10.73,11.99),) if modern and i else (sash(11.64,1.94,1.05,.85,"frost"),),
             axis="y",outside=i,sill=True)
    wall("Jail rear wall",shell,(-5.6,5.6),(18.78,19.03),jail_h,brick,
         [sash(x,2.38,.80,.65,"frost") for x in (-4.14,-1.54,1.54,4.14)]+[opening(-.62,.62)],sill=True,outside=1)
    slab("Jail roof",shell,(-5.68,5.68,9.99,19.12,3.44,3.65),roof)
    slab("Jail roof cap",shell,(-5.72,5.72,18.97,19.16,3.61,3.80),limestone)
    slab("Secure transfer hall floor",interior,(-.89,.89,13.41,19.03,0,FLOOR),tile)
    slab("Rear jail sign tablet",shell,(-2.31,2.31,18.95,19.16,3.19,4.15),limestone)
    panel("Town jail rear sign","holding" if modern else "jail",0,19.170,3.25,reverse=True)
    door("door_jail",(0,9.56,FLOOR),False,yaw=pi)
    jail_door = marker("jail_iron_door",(-1.10,9.87,FLOOR),interior)
    if not far:
        slab("Heavy iron transfer door",jail_door,(0,2.18,-.065,.065,0,2.31),metal)
        slab("Reinforced iron plate",jail_door,(.2,1.98,-.092,-.065,.12,1.15),metal)
        features["jail_iron_door"]={"node": jail_door.name, "closed_box_gltf_m":
                                    {"center": gltf(-.01,9.87,1.2), "size":[2.18,2.31,.13]}}
    for label,x in (("left",-1.24),("right",1.24)):
        slab(f"Iron portal casing {label}",interior,(x-.14,x+.14,9.57,10.12,FLOOR,2.44),limestone)
    slab("Iron portal head",interior,(-1.38,1.38,9.58,10.08,2.36,2.52),limestone)
    if near:
        # The sallyport and the original access corridor meet at the cell block.
        for x in (-5.32,5.32):
            slab("Cell escort partition",interior,(x-.06,x+.06,12.18,12.25,FLOOR,3.40),block)
    if not far:
        slab("Roof hatch curb",shell,(-.59,.59,15.45,16.63,3.65,3.78),limestone)
    hatch=marker("roof_hatch",(-.51,15.54,3.78),shell)
    if not far:
        slab("Roof hatch leaf",hatch,(0,1.02,0,1.02,0,.11),metal)
    features["roof_hatch"]={"node":"roof_hatch", "position":gltf(-.51,15.54,3.78)}
    if not far:
        for x in (-4.72,-1.47,1.47,4.72):
            vent=marker(f"rear_vent_{len([n for n in features if n.startswith('rear_vent')])+1}",
                        (x,19.046,2.10),shell)
            slab("Vent housing",vent,(-.32,.32,0,.13,0,.21),metal)
            features[vent.name]={"node":vent.name,"position":gltf(x,19.046,2.10)}
    for idx,(a,b) in enumerate(((-5.33,-2.84),(-2.69,-.89),(.89,2.69),(2.84,5.33)),1):
        center=(a+b)/2
        width=min(1.03,(b-a)-.44)
        hinge=center-width/2
        end=hinge+width
        floor_name=f"cell_floor_{idx}"
        slab(floor_name,interior,(a,b,13.41,18.78,0,FLOOR),tile,keep=True)
        if idx in (1,3):
            x=b+.075
            solid("Cell masonry partition",interior,(x-.075,x+.075,13.35,18.78,FLOOR,3.42),block,blocks=True)
        if idx in (2,3):
            x=b+.075 if idx==2 else a-.075
            solid("Secure passage side",interior,(x-.075,x+.075,13.38,18.78,FLOOR,3.42),block,blocks=True)
        face_mat=block if modern else metal
        for p,q in ((a,hinge),(end,b)):
            if modern:
                solid("Modern cell front",interior,(p,q,13.32,13.5,FLOOR,3.42),face_mat,blocks=True)
            else:
                collider("Fixed cell bars",(p,q,13.34,13.51,FLOOR,2.61),interior)
                for u in (p+.05,(p+q)/2,q-.05):
                    slab("Fixed iron bar",interior,(u-.03,u+.03,13.38,13.46,FLOOR,2.61),metal)
                slab("Fixed iron cross brace",interior,(p,q,13.36,13.48,1.03,1.12),metal)
        solid("Cell header",interior,(hinge,end,13.33,13.50,2.30,3.42),block)
        if not modern:
            for p,q in ((a,hinge),(end,b)):
                for z in (.13,2.53):
                    slab("Fixed cage framing",interior,(p,q,13.34,13.51,z,z+.09),metal)
        gate_name=f"cell_door_{idx}"
        gate=marker(gate_name,(hinge,13.39,FLOOR),interior)
        if modern:
            wall("Secure steel leaf",gate,(0,width),(-.057,.058),2.22,metal,
                 (sash(width/2,1.54,.36,.29,"sash"),),bottom=0,collides=False)
            slab("Cell door handle",gate,(width-.24,width-.08,-.10,-.057,.98,1.055),limestone)
        else:
            for u in [width*i/6 for i in range(1,6)]:
                slab("Door upright",gate,(u-.03,u+.03,-.038,.038,0,2.23),metal)
            for z in (.09,1.07,2.14):
                slab("Swing grille rail",gate,(0,width,-.048,.048,z,z+.08),metal)
        w_x=(-4.14,-1.54,1.54,4.14)[idx-1]
        bars=marker(f"cell_window_bars_{idx}",(w_x,18.986,2.38),shell)
        if not far:
            for dx in (-.33,-.17,0,.17,.33):
                slab("Barred jail window",bars,(dx-.024,dx+.024,-.02,.02,0,.65),metal)
        bunk_x = a+.37 if idx in (1,3) else b-.37
        point(f"cell_bunk_{idx}",(bunk_x,17.13,.57))
        if near:
            slab("Cell bunk iron frame",interior,(bunk_x-.32,bunk_x+.32,16.10,18.16,.40,.51),metal)
            slab("Cell bunk mattress",interior,(bunk_x-.28,bunk_x+.28,16.16,18.10,.51,.58),block)
            for y in (16.18,18.10):
                slab("Bunk support",interior,(bunk_x-.27,bunk_x+.27,y-.054,y+.054,FLOOR,.43),metal)
            collider("Cell bunk",(bunk_x-.32,bunk_x+.32,16.1,18.16,FLOOR,.58),interior)
            if old:
                oval("Cell bucket",interior,[(center,15.03,z,r,r) for z,r in
                     ((FLOOR,.13),(.29,.17),(.30,.155),(.09,.12))],metal,8)
                collider("Cell bucket",(center-.17,center+.17,14.86,15.20,FLOOR,.30),interior)
        cells.append({"id":idx,"floor_node":floor_name,"door_node":gate_name,"bunk_anchor":f"cell_bunk_{idx}",
                      "window_bars_node":bars.name,"room_min_gltf_m":gltf(a,18.78,FLOOR),
                      "room_max_gltf_m":gltf(b,13.50,3.42),
                      "closed_box_gltf_m":{"center":gltf(center,13.39,1.16),"size":[width,2.23,.12]}})
    if not old:
        # The radio-era rear squad garage becomes an extended secure transport bay.
        x0=7.45 if modern else 7.9
        wall("Garage west",shell,(9.72,18.78),(x0,x0+.26),3.64,block,
             (opening(10.74,12.0),) if modern else (),axis="y")
        wall("Garage east",shell,(9.72,18.78),(13.02,13.28),3.64,block,
             (sash(12.4,2.21,2.48,.83),sash(16.2,2.21,2.48,.83)),axis="y",sill=True,outside=1)
        wall("Garage head",shell,(x0,13.28),(9.46,9.72),3.64,block,
             (opening(8.5,9.7),))
        wall("Garage rear",shell,(x0,13.28),(18.78,19.03),3.64,block,
             (opening(8.42,12.55,3.05),))
        slab("Garage roof",shell,(x0-.11,13.42,9.35,19.18,3.45,3.75),roof)
        slab("Garage fascia",shell,(x0-.10,13.42,19.04,19.21,3.30,4.24),metal)
        slab("Garage floor",interior,(x0,13.28,9.46,19.03,0,FLOOR),tile)
        panel("Rear bay enamel lettering","sally" if modern else "garage",10.74,19.228,3.34,reverse=True)
        door("door_garage",(10.43,19.34,FLOOR),yaw=pi)
        if modern:
            # A short secure link joins the sally port to the jail's transfer hall.
            # Its active entry is on the outside of the jail's flank.
            slab("Sally link floor",interior,(5.6,7.45,10.48,12.18,0,FLOOR),tile)
            slab("Sally link roof",shell,(5.6,7.45,10.48,12.18,2.67,2.84),roof)
            solid("Sally link sides",shell,(5.6,7.45,10.48,10.65,FLOOR,2.69),block,blocks=True)
            solid("Sally link sides",shell,(5.6,7.45,12.01,12.18,FLOOR,2.69),block,blocks=True)
            door("door_sally_inner",(5.53,11.39,FLOOR),False,yaw=pi/2)
        for n,x in enumerate((8.78,11.72),1):
            point(f"car_spot_{n}",(x,22.15,.029))
        for x in (7.31,10.25,13.16):
            slab("Squad parking stripe",lot,(x-.0388,x+.0388,20.12,24.64,.029,.038),limestone)
        point("garage_car_spot",(10.55,15.1,FLOOR))
    if modern:
        for x,y,z,dx,dy in ((-2.42,-2.58,2.59,.14,-.15),(12.39,19.12,3.17,-.18,.19),
                             (1.95,3.06,2.91,-.19,.16),(5.21,13.77,2.97,-.19,-.18)):
            n=f"cctv_{len([id_ for id_ in features if id_.startswith('cctv')])+1}"
            camera=marker(n,(x,y,z),root)
            if not far:
                beam("CCTV bracket",camera,(0,0,0),(dx,dy,-.14),(.037,.037),(.037,.037),metal,4)
                slab("CCTV enclosure",camera,(dx-.14,dx+.14,dy-.09,dy+.09,-.17,-.04),limestone)
                slab("CCTV lens",camera,(dx-.062,dx+.062,dy-.119,dy-.084,-.143,-.069),metal)
            features[n]={"node":n,"position":gltf(x,y,z)}
    # Dogleg service yard and a powered gate in the current era.
    fence=marker("yard_fence",(0,0,0),lot)
    if modern:
        def fence_run(a,b):
            ax,ay=a
            bx,by=b
            dx,dy=bx-ax,by-ay
            count=round((abs(dx)+abs(dy))/1.16)
            for j in range(count+1):
                x,y=ax+dx*j/count,ay+dy*j/count
                slab("Fence post",fence,(x-.047,x+.047,y-.047,y+.047,.03,2.11),metal)
                if j and near:
                    px,py=ax+dx*(j-1)/count,ay+dy*(j-1)/count
                    for z1,z2 in ((.20,1.96),(1.96,.20)):
                        beam("Security mesh braces",fence,(px,py,z1),(x,y,z2),
                             (.015,.015),(.015,.015),metal,4)
            for z in (.2,1.96):
                beam("Fence rails",fence,(ax,ay,z),(bx,by,z),(.04,.04),(.04,.04),metal,4)
            collider("Security fence",(min(ax,bx)-.06,max(ax,bx)+.06,min(ay,by)-.06,max(ay,by)+.06,.029,2.11),
                     lot,"yard_fence")
        fence_run((14.52,7.0),(14.52,24.95))
        fence_run((14.52,24.95),(12.82,24.95))
        fence_run((8.38,24.95),(-8.69,24.95))
        fence_run((-8.69,24.95),(-8.69,13))
        gate=marker("yard_gate",(8.38,24.95,.029),lot)
        for z in (.21,1.95):
            slab("Gate rails",gate,(0,4.44,-.04,.04,z,z+.075),metal)
        for i in range(14):
            x=4.44*i/13
            slab("Gate upright",gate,(x-.023,x+.023,-.032,.032,.23,2.01),metal)
        features["yard_gate"]={"node":gate.name,"slide_gltf_m":[-4.44,0,0],"closed_box_gltf_m":
                               {"center":gltf(10.6,24.95,1.05),"size":[4.44,2.10,.13]}}
    else:
        for b in ((-8.65,-8.51,14,23.9,.03,1.49),(-8.65,5.60,23.76,23.9,.03,1.49)):
            solid("Low masonry yard wall",fence,b,brick)
            collider("Low masonry yard wall",b,lot,"yard_fence")
    features["yard_fence"]={"node":"yard_fence"}
    for x,y in ((-6.63,19.9),(-7.09,19.9)):
        solid("Service refuse bin",lot,(x-.20,x+.20,y-.23,y+.23,.03,.78),metal,blocks=True)
        slab("Service refuse lid",lot,(x-.23,x+.23,y-.25,y+.25,.77,.81),metal)
    for x in (-5.38,5.39):
        slab("Rear downpipe",shell,(x-.046,x+.046,19.04,19.13,.12,3.67),metal)
    slab("Rear service apron",lot,(-1.35,1.35,19.15,21.85,.03,.046),limestone)
    # Keep the authoring scene unbatched. Near and mid share runtime anchors, and far
    # drops room shells, collision triangles and fine objects while retaining the main forms.
    info = {
        "era": ERAS[era], "front_gltf": "+Z", "footprint_m": [14.9 if old else 20.73,19.03+(2.49 if modern else 0)],
        "lot_bounds_gltf_m": [[-11.7,0,-25.5],[15,.042,4.5]], "height_m":7.93 if old else 13.0,
        "doors":door_info,"anchors":anchors,"cells":cells,"features":features,
        "collision_boxes":collision,
        "signs":[{"id":"sign_name","node":"sign_name","texture":"signs.png","rect":REGIONS["name"],
                  "lines":1,"maxChars":14,"face":"civic-block","ink":"#e2cfa2",
                  "paper":"#343e49","glow":False}],
    }
    return root,info


def export(era):
    root, info=build(era,"near")
    stem=f"briarford-{era}"
    save_source(ROOT/f"{stem}.blend")
    for lod in ("near","mid","far"):
        if lod != "near":
            root,_=build(era,lod)
        # Far is a non-interactive building silhouette. Metadata describes near.
        if lod != "near":
            for o in list(bpy.data.objects):
                if o.type == "MESH" and o.parent and (o.parent.name == "collision" or
                        lod == "far" and o.parent.name == "interior"):
                    bpy.data.objects.remove(o,do_unlink=True)
        if lod == "far":
            for o in list(bpy.data.objects):
                if o.type == "MESH" and (any(k in o.name for k in
                        ("sill","Window","Vent stack","quoin","Mast brace","Gate upright",
                         "Stringcourse return","Service refuse","cross brace","sash.0"))
                        or "interior" in [p.name for p in parent_chain(o)]):
                    bpy.data.objects.remove(o,do_unlink=True)
        if lod == "mid":
            for o in list(bpy.data.objects):
                if o.type == "MESH" and (any(k in o.name for k in ("quoin","Gate upright","Fixed iron bar",
                        "Mast brace","Cell door handle","Rear downpipe","Hitching post","Hitching rail"))
                        or "interior" in [p.name for p in parent_chain(o)]):
                    bpy.data.objects.remove(o,do_unlink=True)
        for parent in [ob for ob in bpy.data.objects if ob.type == "EMPTY" and ob.name != "collision"]:
            batch(parent)
        name=stem+("" if lod=="near" else f"-{lod}")+".glb"
        export_glb(ROOT/name)
        objects=[o for o in bpy.data.objects if o.type=="MESH" and not o.get("collision_only")]
        info.setdefault("models",{})[lod]={
            "file":name,"triangles":sum(sum(len(p.vertices)-2 for p in o.data.polygons) for o in objects),
            "materials":sorted({mat.name for ob in objects for mat in ob.data.materials})}
    return info


def parent_chain(ob):
    while ob.parent:
        ob=ob.parent
        yield ob


if __name__ == "__main__":
    info={"name":"Briarford Police Station & Town Jail","naming":"owner","naming_role":"town",
          "meters_per_texel":T,"texels_per_art_pixel":2,
          "shared_swatches":{"wall_brick":"brick_red","ground_paving":"asphalt_worn","roof_bitumen":"roof_membrane_grey (palette source)"},
          "states":{era:export(era) for era in ERAS}}
    (ROOT/"model-info.json").write_text(json.dumps(info,indent=2)+"\n")
