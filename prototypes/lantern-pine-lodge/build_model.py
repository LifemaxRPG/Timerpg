"""Generate the hand-hewn Lantern Pine Lodge and its small woodland clearing."""
import json
import sys
from pathlib import Path
from math import cos,pi,sin

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from revision_common import batch,emitter,export_glb,save_source
from sculpture_tools import T,Y0,beam,box,group,loft,material,mesh,oval,plaque

bpy.ops.wm.read_factory_settings(use_empty=True)
site=group("lantern_pine_lodge")
grounds=group("clearing",site)
lodge=group("lodge_shell",site)
overhead=group("roof_and_rafters",lodge)
entry=group("porch",lodge)
inside=group("hearth_and_fixed_millwork",lodge)
lot=group("lot",site)
collision=group("collision",site)
collision["collision_only"]=True
ground=material("ground_needles","needle_ground")
leaf=material("detail_evergreen","pine")
path=material("ground_gravel","path")
log=material("wall_hewn_logs","hewn_log")
wood=material("wall_plank","plank_horizontal")
roof=material("roof_cedar","roof_slate")
stone=material("wall_fieldstone","fieldstone")
trim=material("detail_trim","trim")
glass=material("glass_lodge","glass")
emitter(glass,"lamp",.18)
lamp=material("glow_lantern","lamp",.65)
sign=material("sign_name","name")
iron=material("detail_iron","iron")
boxes=[]


def blocker(name,parent,center,size,category="structure"):
    x,y,z=center
    boxes.append({"name":name,"attachment":parent.name,"group":category,
                  "center":[x,y+Y0,z],"size":[*size]})


def block(name,parent,x,y,z,w,d,h,mat,solid=False,category="structure"):
    ob=box(name,parent,(x,y,z+h/2),(w,d,h),mat)
    if solid:
        blocker(name,parent,(x,y,z+h/2),(w,d,h),category)
    return ob


def surf(name,parent,points,mat):
    coords=[Vector(p) for p in points]
    f=tuple(range(len(points)))
    return mesh(name,parent,coords,[f],mat)


block("Forest parcel",grounds,0,0,0,34,29,.10,ground)
path_edges=[((-1.8,-14.4),(1.3,-14.4)),((-1.1,-11.4),(1.7,-11.7)),
            ((-2.25,-8.68),(.96,-8.68))]
surf("Bent gravel approach",grounds,[(*p,.113) for p in
     [edges[0] for edges in path_edges]+[edges[1] for edges in reversed(path_edges)]],path)
surf("Side path",grounds,[(3,-8.68,.113),(4.65,-8.68,.113),(8.3,-6.55,.113),
     (10.4,-3.2,.113),(8.75,-2.7,.113),(7.15,-5.2,.113),(4.28,-7.1,.113)],path)

block("Raised fieldstone plinth",lodge,0,1,.10,11.64,9.37,.46,stone)
block("Main room floor",lodge,0,1,.56,11.64,9.36,.13,wood)
block("Porch fieldstone platform",entry,0,-5.45,.10,12.35,3.63,.44,stone)
block("Porch floorboards",entry,0,-5.45,.54,12.31,3.61,.15,wood)
for i in range(3):
    top=.10+(i+1)*.193
    block("Full width porch stair",entry,-.63,-8.17+i*.349,.10,3.66,.35,top-.10,stone)

wall_z=.69
pitch=.337
courses=10
wall_top=wall_z+courses*pitch
windows=[(1.65,4.83,wall_z+pitch*3,wall_z+pitch*8)]
front_open=[(-2.26,-.74,wall_z,wall_z+pitch*7)]+windows
back_open=[(-4.32,-2.05,wall_z+pitch*3,wall_z+pitch*8),
           (2.26,3.78,wall_z,wall_z+pitch*7)]
west_windows=[(-.18,2.06,wall_z+pitch*3,wall_z+pitch*8)]
east_windows=[(-1.55,.41,wall_z+pitch*3,wall_z+pitch*8),
              (2.0,3.81,wall_z+pitch*3,wall_z+pitch*8)]


def logwall(name,axis,fixed,left,right,openings):
    for i in range(courses):
        z=wall_z+pitch*(i+.5)
        reach=.31 if i%2==axis else .19
        edge0,edge1=left-reach,right+reach
        gaps=sorted((a,b) for a,b,lo,hi in openings if lo<z<hi)
        bounds=[edge0]+[v for gap in gaps for v in gap]+[edge1]
        for a,b in zip(bounds[::2],bounds[1::2]):
            if axis==0:
                p,q=(a,fixed,z),(b,fixed,z)
            else:
                p,q=(fixed,a,z),(fixed,b,z)
            beam(name,lodge,p,q,(.223,.214),(.223,.214),log,n=8)
    # Colliders follow openings for the full coarse wall, with separate over-window headers.
    jambs=[left]+[v for a,b,_,_ in sorted(openings) for v in (a,b)]+[right]
    for a,b in zip(jambs[::2],jambs[1::2]):
        center=((a+b)/2,fixed,(wall_z+wall_top)/2) if axis==0 else (fixed,(a+b)/2,(wall_z+wall_top)/2)
        blocker(name,lodge,center,(b-a,.40,wall_top-wall_z) if axis==0 else (.40,b-a,wall_top-wall_z))
    for a,b,lo,hi in openings:
        for z0,z1 in ((wall_z,lo),(hi,wall_top)):
            if z1-z0>.001:
                ce=((a+b)/2,fixed,(z0+z1)/2) if axis==0 else (fixed,(a+b)/2,(z0+z1)/2)
                blocker("Window sill or door header",lodge,ce,(b-a,.4,z1-z0) if axis==0 else (.4,b-a,z1-z0))
        if lo>wall_z+.1:
            ce=((a+b)/2,fixed,(lo+hi)/2) if axis==0 else (fixed,(a+b)/2,(lo+hi)/2)
            blocker("Glazing",lodge,ce,(b-a,.13,hi-lo) if axis==0 else (.13,b-a,hi-lo))


logwall("Front hewn logs",0,-3.60,-5.8,5.8,front_open)
logwall("Rear hewn logs",0,5.6,-5.8,5.8,back_open)
logwall("West hewn logs",1,-5.8,-3.6,5.6,west_windows)
logwall("East hewn logs",1,5.8,-3.6,5.6,east_windows)

# Deep sash frames sit in actual cuts in the log shell.
def glazing(axis,fixed,opening):
    a,b,lo,hi=opening
    center=(a+b)/2
    span=b-a
    def piece(name,q,z,w,h,mat,d=.14):
        if axis==0:
            return block(name,lodge,q,fixed,z,w,d,h,mat)
        return block(name,lodge,fixed,q,z,d,w,h,mat)
    piece("Recessed pane",center,lo+.065,span-.14,hi-lo-.13,glass,.062)
    for p in (a+.037,b-.037):
        piece("Window casing",p,lo-.047,.114,hi-lo+.094,trim)
    for z in (lo-.05,hi-.075):
        piece("Deep window sill",center,z,span+.15,.122,trim,.51)
    for ratio in (.33,.66):
        piece("Window mullion",a+span*ratio,lo,.079,hi-lo,iron)
    piece("Middle sash rail",center,(lo+hi)/2,span-.06,.087,iron)


for axis,fixed,items in ((0,-3.6,windows),(0,5.6,[back_open[0]]),
                          (1,-5.8,west_windows),(1,5.8,east_windows)):
    for opening in items:
        glazing(axis,fixed,opening)
for y,a,b in ((-3.6,-2.26,-.74),(5.6,2.26,3.78)):
    for x in (a-.04,b+.04):
        block("Rebated doorway casing",lodge,x,y,wall_z,.136,.48,pitch*7+.085,trim)
    block("Door head trim",lodge,(a+b)/2,y,wall_z+pitch*7,b-a+.25,.48,.124,trim)
    block("Stone door threshold",lodge,(a+b)/2,y,wall_z-.036,b-a+.08,.40,.039,trim)

block("Raised rear stoop",lodge,3.02,6.33,.10,3.17,1.41,.58,stone)
for i in range(2):
    block("Back stoop stair",lodge,3.02,7.61-i*.36,.10,2.18,.365,.184*(i+1),stone)

# Thick, low-sided roof slabs cover a real timber interior from ridge down to both eaves.
def roof_plate(name,parent,points,thickness,material):
    pts=[Vector(p) for p in points]
    normal=(pts[1]-pts[0]).cross(pts[2]-pts[0])
    if normal.z<0:
        pts.reverse()
    verts=[p-Vector((0,0,thickness)) for p in pts]+pts
    ob=mesh(name,parent,verts,[(3,2,1,0),(4,5,6,7),(0,1,5,4),(1,2,6,5),
                               (2,3,7,6),(3,0,4,7)],material)
    ob["keep"]=True
    return ob


for s in (-1,1):
    x=s*6.45
    roof_plate("roof_main_west" if s<0 else "roof_main_east",overhead,[(0,-4.45,7.23),(x,-4.45,4.18),
           (x,6.38,4.18),(0,6.38,7.23)],.19,roof)
    for y in (-4.49,6.41):
        beam("Gable bargeboard",overhead,(0,y,7.225),(x,y,4.19),(.106,.096),(.106,.096),trim,n=4)
    for y in (-3.34,-1.42,.50,2.38,4.42,5.63):
        beam("Exposed rafter",overhead,(0,y,6.96),(s*6.16,y,4.06),(.096,.116),(.096,.116),wood,n=4)
    beam("Long eave flashing",overhead,(x,-4.46,4.15),(x,6.42,4.15),(.089,.073),(.089,.073),iron,n=4)
beam("Cedar ridge cap",overhead,(0,-4.48,7.24),(0,6.42,7.24),(.127,.09),(.127,.09),trim,n=6)
for y,face in ((-3.60,-1),(5.6,1)):
    vv=[(-5.8,y,wall_top),(5.8,y,wall_top),(0,y,6.99)]
    surf("Planked gable end",overhead,vv if face<0 else list(reversed(vv)),wood)
    # The heavy truss remains visible below the gable.
    beam("Tie beam",lodge,(-5.59,y+.19,4.07),(5.59,y+.19,4.07),(.179,.167),(.179,.167),log,n=4)
    beam("King post",overhead,(0,y-.033,4.07),(0,y-.033,6.92),(.104,.12),(.104,.12),trim,n=4)
for y in (-1.44,1.25,3.97):
    beam("Great-room collar tie",lodge,(-3.51,y,5.10),(3.51,y,5.10),(.158,.154),(.158,.154),wood,n=4)

# The shed roof terminates at the front wall below the underside of the gable eaves.
porch_front=-7.62
porch_back=-3.60
front_height=3.29
back_height=3.95
roof_plate("roof_porch",overhead,[(-6.46,porch_front,front_height),
           (6.46,porch_front,front_height),(6.46,porch_back,back_height),
           (-6.46,porch_back,back_height)],.18,roof)
beam("Porch front fascia",overhead,(-6.46,-7.64,3.232),(6.46,-7.64,3.232),
     (.087,.103),(.087,.103),trim,n=4)
for x in (-6.446,6.446):
    beam("Porch rake fascia",overhead,(x,porch_front,3.22),(x,porch_back,3.88),
         (.074,.078),(.074,.078),trim,n=4)
block("Folded wall flashing",overhead,0,-3.735,3.905,11.58,.20,.034,iron)
block("Upstand of wall flashing",overhead,0,-3.64,3.925,11.58,.027,.166,iron)

for x in (-5.72,-2.80,2.54,5.71):
    y=-6.98
    block("Porch footing",entry,x,y,.54,.58,.58,.36,stone,True)
    block("Square porch post",entry,x,y,.88,.268,.268,2.355,log,True)
    for s in (-1,1):
        beam("Porch knee brace",entry,(x,y,2.71),(x+s*.60,y,3.205),
             (.089,.079),(.089,.079),trim,n=4)

# The sign sits on the fascia face beyond the stair opening.
x,y,z=3.15,-7.787,3.02
w,h=96*T,36*T
block("Fascia-mounted timber sign",entry,x,y,z-h/2-.014,w+.07,.104,h+.029,iron)
for sx in (-1.55,1.55):
    block("Fascia sign iron bracket",entry,x+sx,-7.712,3.14,.077,.125,.124,iron)
pts=[(x-w/2,y-.055,z-h/2),(x+w/2,y-.055,z-h/2),
     (x+w/2,y-.055,z+h/2),(x-w/2,y-.055,z+h/2)]
plaque("sign_name",entry,pts,sign,(0,0,96,36),(96,36))

# The full-height chimney shares its lower masonry with the room's hearth.
def chimney_course(name,x,y,tiers):
    rings=[]
    for z,w,d in tiers:
        a,b=w/2,d/2
        bevel=.19
        rings.append([(x+u,y+v,z) for u,v in
             [(-a+bevel,-b),(a-bevel,-b),(a,-b+bevel),(a,b-bevel),
              (a-bevel,b),(-a+bevel,b),(-a,b-bevel),(-a,-b+bevel)]])
    return loft(name,lodge,rings,stone)

chimney_course("Tapered fieldstone chimney",-6.03,3.52,[
    (.1,1.87,2.95),(2.64,1.87,2.95),(3.70,1.49,1.88),
    (5.45,1.18,1.62),(8.14,1.10,1.54)])
block("Chimney stone crown",lodge,-6.03,3.52,8.12,1.39,1.82,.23,stone)
block("Rain cap",lodge,-6.03,3.52,8.49,1.03,1.44,.105,iron)
for dx in (-.33,.33):
    for dy in (-.47,.47):
        block("Rain cap riser",lodge,-6.03+dx,3.52+dy,8.35,.095,.10,.15,iron)
blocker("Exterior chimney",lodge,(-6.34,3.52,4.10),(1.24,2.95,8.0))
block("Hearth apron",inside,-4.99,3.50,.68,2.2,3.34,.19,stone,True)
for y in (2.36,4.64):
    block("Fireplace cheeks",inside,-4.92,y,.86,1.31,.51,1.45,stone,True)
block("Fireback",inside,-5.68,3.5,.855,.28,2.31,1.45,iron,True)
block("Hearth lintel",inside,-4.94,3.50,2.14,1.37,2.86,.39,stone,True)
block("Hand-hewn mantel",inside,-4.84,3.5,2.48,1.59,3.05,.232,log)
for i in range(4):
    y=2.92+i*.37
    beam("Logs in iron hearth",inside,(-5.49,y,.918),(-4.81,y+.17,1.13),
         (.12,.10),(.115,.114),log,n=6)
for dy,h in ((-.38,.32),(.02,.52),(.31,.36)):
    verts=[(-5.01,3.5+dy-.16,1.01),(-5.00,3.5+dy+.15,1.04),
           (-5.06,3.5+dy+.11,1.01+h*.73),(-5.07,3.5+dy-.015,1.01+h)]
    surf("Banked ember flame",inside,verts,lamp)
for y in (2.10,4.95):
    block("Hearth andirons",inside,-4.31,y,.88,.38,.065,.72,iron)

# Cabin joinery is fixed. Small furnished touches belong to named optional lot groups.
for x in (2.4,4.04):
    block("Fixed window seat base",inside,x,-2.94,.69,1.58,.56,.41,wood,True)
block("Window seat top",inside,3.24,-2.92,1.09,3.29,.66,.102,trim)
block("Entrance peg rail",inside,-3.90,-3.345,2.17,2.37,.093,.25,trim)
for x in (-4.81,-4.39,-3.96,-3.52,-3.08):
    beam("Coat hook",inside,(x,-3.28,2.30),(x,-3.11,2.37),(.039,.042),(.025,.030),iron,n=5)

# Amber lanterns have dark frames and individual removable emitter faces.
def lantern(name,parent,x,y,z):
    g=group(name,parent)
    block("Iron lantern top",g,x,y,z+.284,.30,.30,.11,iron)
    block("Amber glass lamp",g,x,y,z,.245,.245,.30,lamp)
    block("Iron lantern base",g,x,y,z-.065,.305,.305,.083,iron)
    for dx in (-.115,.115):
        for dy in (-.115,.115):
            block("Lantern cage bar",g,x+dx,y+dy,z,.036,.036,.298,iron)
    return g

for i,(x,y,z) in enumerate(((-2.55,-3.84,2.43),(1.15,-3.87,2.44),(-5.64,-6.95,2.40),
         (5.65,-6.94,2.42),(4.04,5.88,2.52),(0,1.28,4.45),(0,-1.39,4.45),(0,3.94,4.45))):
    lantern(f"lantern_{i+1:02d}",lodge,x,y,z)
    if y< -6 or abs(y)<3:
        beam("Suspended lantern rod",lodge,(x,y,z+.38),(x,y,z+.69),(.032,.032),(.032,.032),iron,n=5)

# A little rear lean-to keeps firewood out of the rain.
store=group("woodshed",lodge)
roof_plate("Woodshed lean-to",overhead,[(5.78,2.01,3.02),(8.82,2.01,2.38),
              (8.82,6.22,2.38),(5.78,6.22,3.02)],.15,roof)
block("Woodshed stone skid",store,7.22,4.03,.1,2.66,3.65,.13,stone)
for y in (2.29,5.99):
    block("Shed post",store,8.59,y,.227,.17,.17,2.06,log,True)
block("Woodshed rear siding",store,7.21,6.04,.23,2.9,.17,2.13,wood,True)
stack=group("woodpile",lot)
for j in range(5):
    for i in range(6-j//2):
        x=6.27+i*.352+(j%2)*.18
        z=.35+j*.248
        beam("Split firewood",stack,(x,4.39+(i%2)*.08,z),(x,5.82-(j%2)*.12,z+.028),
             (.151,.149),(.149,.13),log,n=6)
blocker("Woodpile",stack,(7.30,5.12,.89),(2.17,1.52,1.32),"lot")

# Porch bench, an axe-round stool, and a small outdoor table are optional dressing.
bench=group("porch_bench",lot)
block("Porch seat",bench,-4.29,-5.15,1.10,2.24,.63,.103,trim)
block("Porch bench back",bench,-4.29,-4.92,1.28,2.24,.09,.45,wood)
for dx in (-.86,.86):
    block("Porch bench legs",bench,-4.29+dx,-5.15,.69,.15,.54,.45,log)
blocker("Porch bench",bench,(-4.29,-5.15,1.2),(2.24,.65,1.02),"lot")
stool=group("stump_stool",lot)
oval("Hewn stump",stool,[(4.53,-5.53,.69,.29,.31),(4.53,-5.53,1.16,.31,.32)],log,n=9)
blocker("Stool",stool,(4.53,-5.53,.925),(.63,.65,.47),"lot")
picnic=group("woodland_table",lot)
x,y=10.6,-.60
for dx in (-.20,0,.20):
    block("Table plank",picnic,x+dx,y,.85,.187,1.80,.085,wood)
for side in (-1,1):
    block("Bench plank",picnic,x+side*.61,y,.545,.29,1.84,.090,wood)
    for yy in (-.6,.6):
        beam("Table A-frame",picnic,(x+side*.42,y+yy,.10),
             (x+side*.16,y+yy,.86),(.08,.071),(.08,.071),log,n=4)
for yy in (-.6,.6):
    beam("Bench cross member",picnic,(x-.75,y+yy,.52),(x+.75,y+yy,.52),(.065,.071),(.065,.071),log,n=4)
blocker("Woodland table",picnic,(x,y,.518),(1.54,1.88,.835),"lot")

for i,(x,y,h) in enumerate([(-14.9,-12.0,8.1),(-11.1,-9.8,8.5),(-14.2,-5.9,10.8),
    (-14.6,1.9,9.2),(-12.0,7.1,9.4),(-13.2,11.4,10.8),(-8.23,10.75,10.3),
    (-3.3,11.8,9.9),(1.95,12.2,10.3),(6.85,11.47,11.1),(12.22,9.8,9.7),
    (15.10,4.98,9.0),(14.6,-3.8,10.8),(12.3,-9.8,9.3),(15.05,-12.4,7.94),
    (-9.2,-11.7,4.65),(9.37,8.0,4.75)]):
    tree=group(f"tree_{i+1:02d}",lot)
    oval("Tapering pine trunk",tree,[(x,y,.1,.205,.19),(x,y,h*.8,.089,.091)],log,n=7)
    for j in range(4):
        z=(.22+j*.146)*h
        w=h*(.173-j*.0295)
        oval("Layered pine crown",tree,[(x,y,z,w*.77,w*.77),
            (x+.08,y+.09,z+h*.073,w,w*.96),(x-.13,y-.11,z+h*.34,w*.12,w*.13)],leaf,n=8)
    blocker("Tree trunk",tree,(x,y,h*.30),(.49,.48,h*.60),"lot")
for i,(x,y,w) in enumerate([(-12.34,-7.32,.83),(-8.2,6.66,.77),(11.87,-6.73,.92),
                          (12.05,4.1,1.2),(-15.05,7.5,1.2),(.97,-11.8,.54)]):
    g=group(f"rock_{i}",lot)
    oval("Glacial erratic",g,[(x,y,.097,w*.83,w*.61),
          (x+.12,y+.02,.10+w*.49,w*.63,w*.53),
          (x+.23,y-.11,.10+w*.71,w*.21,w*.31)],stone,n=7)
    blocker("Boulder",g,(x,y,.10+w*.36),(w*1.66,w*1.23,w*.72),"lot")

def marker(name,x,y,z,heading=0):
    ob=group(name,site)
    ob.location=(x,y+Y0,z)
    ob.rotation_euler.z=heading
    return {"node":ob.name,"position_gltf_m":[x,z,-(y+Y0)],"heading_deg":round(heading*180/pi)}

doors=[marker("door_main",-1.50,-3.96,.69),marker("door_back",3.02,5.89,.69,pi)]
anchors=[marker("footpath_entrance",-.2,-14.34,.113),
         marker("hearth", -3.71,3.50,.69),marker("woodshed_approach",8.06,1.82,.10)]
for i,data in enumerate(boxes):
    x,y,z=data["center"]
    ob=box(f"collider_{i+1:03d}",collision,(x,y-Y0,z),data["size"],iron)
    ob["collision_only"]=True
    ob.display_type="WIRE"
    ob.hide_render=True

for parent in [lodge,grounds,entry,inside,overhead,store,*lot.children]:
    batch(parent)
visible=[o for o in site.children_recursive if o.type=="MESH" and not o.get("collision_only")]
tris=sum(len(p.vertices)-2 for o in visible for p in o.data.polygons)
mats=sorted({m.name for o in visible for m in o.data.materials})
assert tris<=12000 and len(mats)<=12
info={"name":"Lantern Pine Lodge","era":"1930s woodland lodge, suitable onward",
      "footprint_m":[34,29],"height_m":round(max(v.co.z for o in visible for v in o.data.vertices),4),
      "building_height_m":8.595,"triangles_visible":tris,"materials":mats,"doors":doors,"anchors":anchors,
      "collision":{"boxes_blender_z_up":boxes},"units":"Metres, glTF Y up, door faces +Z, origin on south parcel edge",
      "texel_size_m":T,"texels_per_art_pixel":2,"swatches_used":["plank_horizontal","roof_slate"]}
(ROOT/"model-info.json").write_text(json.dumps(info,indent=2)+"\n")
export_glb(ROOT/"lantern-pine-lodge.glb",[site,*site.children_recursive])
save_source(ROOT/"lantern-pine-lodge.blend")
