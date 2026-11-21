"""Original Pine Bend highway rest area. Coordinates are in metres."""
import json
import sys
from pathlib import Path
from math import atan2, hypot, pi

import bpy
from mathutils import Vector
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from revision_common import batch, export_glb, save_source
from sculpture_tools import T, Y0, beam, box, group, loft, material, mesh, oval, plaque

bpy.ops.wm.read_factory_settings(use_empty=True)
site=group("pine_bend")
building=group("pavilion",site)
grounds=group("parking_and_paths",site)
lot=group("lot",site)
interior=group("restroom_shells",building)
grass=material("ground_grass","grass")
road=material("ground_asphalt","asphalt_worn")
paving=material("ground_paving","pavement")
brick=material("wall_brick","brick_courses")
wood=material("wall_timber","plank_horizontal")
roof=material("roof_tin_blue","roof_tin_blue")
trim=material("detail_concrete","trim")
teal=material("detail_teal","teal")
glass=material("glass_clerestory","glass",.25)
lamp=material("glow_lamps","lamp",.65)
sign=material("sign_name","signs")
collision=group("collision",site)
collision["collision_only"]=True
boxes=[]


def slab(name,parent,x,y,z,w,d,h,mat,solid=False,category="structure"):
    ob=box(name,parent,(x,y,z+h/2),(w,d,h),mat)
    if solid:
        add_box(name,category,(x,y,z+h/2),(w,d,h),parent.name)
    return ob


def add_box(name,category,center,size,attachment):
    x,y,z=center
    boxes.append({"name":name,"group":category,"attachment":attachment,
                  "center":[x,y+Y0,z],"size":list(size)})


def mark(name,position,heading=0):
    x,y,z=position
    ob=group(name,site)
    ob.location=(x,y+Y0,z)
    ob.rotation_euler.z=heading
    return {"node":name,"position_gltf_m":[x,z,-(y+Y0)],"heading_deg":round(heading*180/pi)}


def label(name,parent,x,y,z,rect):
    u,v,w,h=rect
    w,h=w*T,h*T
    return plaque(name,parent,[(x-w/2,y,z-h/2),(x+w/2,y,z-h/2),
        (x+w/2,y,z+h/2),(x-w/2,y,z+h/2)],sign,rect,(512,256))


slab("Green highway parcel",grounds,0,0,0,82,66,.11,grass)
slab("Access drive",grounds,0,-27,.11,81.8,10.4,.038,road)
slab("Car lot pavement",grounds,-15.65,-15.3,.11,49.1,16,.04,road)
slab("Passenger lot connection",grounds,-37.3,-23.9,.11,6.9,7,.039,road)
slab("Truck apron",grounds,25.3,1.0,.11,28.3,49.9,.041,road)
slab("Truck exit throat",grounds,32.5,25.3,.11,13.4,15.2,.039,road)
slab("Accessible restroom walk",grounds,-9.0,-5.9,.15,36.5,2.55,.074,paving)
slab("Pedestrian spine",grounds,8.9,7.2,.11,2.7,36.8,.109,paving)
slab("Truck driver path",grounds,12.65,7.9,.11,4.44,2.3,.113,paving)
slab("Picnic path",grounds,-6.6,20.9,.11,31.3,2.34,.101,paving)
slab("Park loop link",grounds,-27.9,10.0,.11,2.35,23.5,.105,paving)
slab("Park approach",grounds,-24.3,-2.3,.11,7.6,2.0,.101,paving)

# Parking markings are above the asphalt. Passenger cars park in two opposing rows.
for row in (-10.1,-20.35):
    for i in range(13):
        x=-33.1+i*2.81
        slab("Passenger parking stripe",grounds,x,row,.152,.10,4.88,.012,trim)
for x in (-.7,2.13,4.96):
    slab("Accessible stall edge",grounds,x,-10.08,.152,.12,4.88,.012,roof)
for y in (-12.35,-11.58,-10.81,-10.04,-9.27,-8.50,-7.73):
    slab("Marked access aisle",grounds,5.82,y,.152,1.55,.1,.012,trim)
for i in range(5):
    x=18.95+i*4.25
    slab("Truck bay stripe",grounds,x,7.15,.153,.135,25,.012,trim)
for j in range(6):
    y=-6.7+j*.60
    slab("Truck footpath crossing",grounds,14.65,y,.153,3.0,.245,.012,trim)
for j in range(9):
    x=-33+j*7.55
    slab("Access drive dash",grounds,x,-26.7,.151,3.12,.13,.012,trim)
for x in (-24,-12,0,12):
    slab("Passenger lane dash",grounds,x,-15.05,.152,2.22,.10,.011,trim)
# Mountable walk edges and planted curb breaks.
for x,w in [(-19.65,16),(-5.3,8.1),(3.8,5.0)]:
    slab("Walk curb",grounds,x,-7.10,.149,w,.21,.11,trim)
for y in (-2,3,12.3,17.6):
    slab("Truck path curb",grounds,10.44,y,.15,.20,3.48,.15,trim)

# Restrooms flank an open roofed breezeway, which contains vending and a wayfinding board.
slab("Entry apron",grounds,-7.1,-2.68,.11,14.7,4.28,.1128,paving)
slab("Pavilion slab",building,-7.1,6.55,.11,23.4,14.8,.113,paving)
for center in (-14.0,-.20):
    room=group("restroom_west" if center<-5 else "restroom_east",interior)
    span=8.06
    slab("Room floor",room,center,7.7,.223,span,10.85,.028,paving)
    for x in (center-span/2+.135,center+span/2-.135):
        slab("Side brick wall",room,x,7.68,.224,.27,11.09,3.25,brick,True)
    slab("Rear brick wall",room,center,13.09,.224,span-.55,.275,3.25,brick,True)
    door_x=center+(.68 if center<-5 else -.68)
    left,right=center-span/2+.27,center+span/2-.27
    parts=[(left,door_x-.78),(door_x+.78,right)]
    for a,b in parts:
        slab("Front brick jamb wall",room,(a+b)/2,2.23,.224,b-a,.27,3.25,brick,True)
    slab("Entry lintel",room,door_x,2.23,2.56,1.56,.27,.914,brick,True)
    for side in (-1,1):
        slab("Teal door frame",room,door_x+side*.807,2.067,.222,.14,.11,2.41,teal)
    slab("Teal door head",room,door_x,2.066,2.563,1.75,.11,.105,teal)
    slab("Privacy baffle",room,center+(-.71 if center<-5 else .71),4.0,.25,4.53,.15,2.42,teal,True)
    slab("Room ceiling",room,center,7.7,3.46,span,10.98,.14,trim)
    # Fixed plumbing blocks remain removable as one kit per room.
    kit=group("fixtures_west" if center<-5 else "fixtures_east",lot)
    for i in range(3):
        x=center+(i-1)*1.73
        slab("Stall divider",kit,x+.73,10.94,.25,.075,2.55,1.92,teal,True,"fixture")
        slab("Toilet pedestal",kit,x,11.98,.25,.39,.59,.45,trim,True,"fixture")
        oval("Ceramic bowl",kit,[(x,11.91,.60,.26,.35),(x,11.91,.741,.23,.34)],trim,n=8)
        slab("Rear cistern",kit,x,12.54,.25,.47,.21,.88,trim)
    sink_x=center+(-3.1 if center<-5 else 3.1)
    slab("Vanity base",kit,sink_x,7.24,.25,.57,3.1,.72,trim,True,"fixture")
    slab("Vanity counter",kit,sink_x,7.24,.97,.69,3.14,.10,teal)
    for y in (6.32,7.22,8.14):
        oval("Sink bowl inset",kit,[(sink_x,y,1.07,.24,.27),(sink_x,y,1.112,.25,.28)],trim,n=8)
    slab("Vanity mirror strip",kit,sink_x+(-.30 if center<-5 else .30),7.29,1.25,.055,2.93,.75,glass)
    slab("Restroom ceiling light",room,center,8.2,3.41,4.6,.26,.058,lamp)
    label("Restroom entrance sign",room,door_x,2.058,2.88,(0,32,128,32))

# Low brick utility wall closes the rear of the breezeway. It has a timber service door face.
slab("Breezeway rear wall",building,-7.1,13.15,.22,5.78,.29,3.26,brick,True)
slab("Utility door",building,-7.15,13.321,.22,1.22,.096,2.24,teal)
slab("Utility door kick plate",building,-7.15,13.378,.26,1.12,.022,.29,trim)

# Layered gable roof with deep eaves, raised entry gable and translucent soffit strips.
def gable(name,x,y,w,d,eave,ridge,along_y=False):
    a=w/2
    b=d/2
    for side in (-1,1):
        pts=[(side*a,-b,eave),(side*a,b,eave),(0,b,ridge),(0,-b,ridge)]
        if along_y:
            pts=[(v,u,z) for u,v,z in pts]
        pts=[(x+u,y+v,z) for u,v,z in pts]
        f=(0,1,2,3)
        normal=(Vector(pts[1])-Vector(pts[0])).cross(Vector(pts[2])-Vector(pts[0]))
        if normal.z<0:
            f=tuple(reversed(f))
        mesh(name,building,pts,[f],roof)
        for edge in ((0,1),(1,2),(2,3),(3,0)):
            beam("Deep timber fascia",building,pts[edge[0]],pts[edge[1]],(.087,.126),(.087,.126),teal,n=4)
    for side in (-1,1):
        pts=[(-a,side*b,eave),(a,side*b,eave),(0,side*b,ridge)]
        if along_y:
            pts=[(v,u,z) for u,v,z in pts]
        p=[(x+u,y+v,z) for u,v,z in pts]
        mesh("Timber gable end",building,p,[(0,1,2)],wood)


# The main roof ridge runs across the building. Width is the roof's slope span.
gable("Long pavilion gable",-7.10,7.05,13.25,24.9,3.63,5.56,True)
gable("Entry canopy gable",-7.10,.14,6.64,7.0,3.56,5.05)
for x in (-10.35,-3.85):
    for y in (-2.76,2.34):
        slab("Entry canopy pier",building,x,y,.222,.54,.54,2.98,brick,True)
        slab("Dark canopy post cap",building,x,y,3.20,.35,.35,.47,teal)
for x in (-9.0,-5.2):
    slab("Warm canopy fixture",building,x,-.94,3.47,1.25,.39,.10,lamp)
slab("Main sign fascia",building,-7.1,-3.39,3.67,10.11,.13,1.23,teal)
label("sign_name",building,-7.10,-3.465,4.28,(0,0,256,32))
slab("Rear intake awning",building,-7.15,13.58,2.58,3.2,1.2,.16,roof)
for x in (-15.7,1.7):
    slab("Rear ventilation louver",building,x,13.26,2.65,1.46,.12,.54,teal)
    for yy in (-.2,-.1,0,.1,.2):
        slab("Vent louver blade",building,x,13.343,2.91+yy,1.36,.06,.047,trim)
    for j in range(3):
        slab("Clerestory low glass",building,x+1.41*j,2.073,3.05,1.29,.097,.34,glass)

for i in (0,1,2):
    x=-8.79+i*1.44
    machine=group(f"vending_{i+1}",lot)
    slab("Vending cabinet",machine,x,11.98,.223,1.25,.82,2.11,teal,True,"fixture")
    label("Vending pixels",machine,x,11.554,1.275,(276+40*i,6,32,54))
    slab("Vending hood light",machine,x,11.58,2.278,1.18,.10,.06,lamp)
label("Vending direction",building,-7.1,12.997,2.95,(128,32,128,32))
slab("Regional map cabinet",building,-7.12,6.7,.224,1.50,.28,2.0,teal,True)
label("Picnic wayfinding on cabinet",building,-7.12,6.545,1.47,(0,96,128,32))

# Covered tables and seating each stay removable.
def table(x,y,num):
    g=group(f"picnic_table_{num}",lot)
    yard=.213 if num in (1,2,3) else .11
    for dx in (-.18,0,.18):
        slab("Table plank",g,x+dx,y,yard+.75,.169,1.93,.078,wood)
    for side in (-1,1):
        xx=x+side*.58
        for dy in (-.075,.075):
            slab("Bench plank",g,xx+dy,y,yard+.43,.138,1.92,.079,wood)
    for dy in (-.67,.67):
        for side in (-1,1):
            beam("Picnic table A-frame",g,(x+side*.42,y+dy,yard),
                 (x+side*.17,y+dy,yard+.76),(.075,.076),(.075,.076),teal,n=4)
        beam("Bench crosspiece",g,(x-.68,y+dy,yard+.418),(x+.68,y+dy,yard+.418),
             (.075,.077),(.075,.077),teal,n=4)
    add_box("Picnic table","lot",(x,y,yard+.414),(1.45,1.93,.828),g.name)


for i,(x,y) in enumerate(((-30.2,18.0),(-24.1,18.0),(-15.4,22.2),
                           (-22.4,12.2),(-34.1,23.9),(-8.7,23.3)),1):
    slab("Picnic hardstand",grounds,x,y,.11,3.1,3.36,.094,paving)
    table(x,y,i)
for k,(x,y) in enumerate(((-27.2,18.05),(-14.3,22.22))):
    w,d=(9.7,5.82) if k==0 else (5.58,5.05)
    gable("Picnic shelter roof",x,y,w,d,2.59,3.61)
    for dx in (-w/2+.39,w/2-.39):
        for dy in (-d/2+.31,d/2-.31):
            slab("Shelter masonry footing",building,x+dx,y+dy,.11,.38,.37,.4,brick,True)
            slab("Shelter timber post",building,x+dx,y+dy,.51,.21,.20,2.10,teal,True)
    slab("Shelter ceiling lamp",building,x,y,2.61,.82,.23,.10,lamp)

for i,(x,y) in enumerate(((-20.0,-5.75),(4.12,-5.75),(10.61,5.85),(-34,17.0))):
    g=group(f"waste_bin_{i+1}",lot)
    oval("Bin body",g,[(x,y,.215,.33,.34),(x,y,1.07,.38,.37)],teal,n=8)
    oval("Bin hood cap",g,[(x,y,1.05,.44,.44),(x,y,1.23,.34,.34)],trim,n=8)
    add_box("Waste bin","lot",(x,y,.727),(.86,.86,1.02),g.name)
for i,(x,y) in enumerate(((-19.9,-1.31),(10.80,8.97),(10.82,14.0))):
    g=group(f"bench_{i+1}",lot)
    slab("Seat timber",g,x,y,.66,1.98,.37,.084,wood)
    slab("Seat back",g,x,y+.17,.84,1.98,.10,.31,wood)
    for dx in (-.73,.73):
        slab("Bench leg",g,x+dx,y,.217,.14,.39,.45,teal)
    add_box("Bench","lot",(x,y,.69),(1.98,.5,.94),g.name)

for name,x,y,rect in (("Truck directions",15.7,-21.17,(0,64,128,32)),
                       ("Car directions",-21.2,-23.3,(128,64,128,32)),
                       ("Picnic directions",-25.7,10.1,(0,96,128,32))):
    g=group(name.lower().replace(" ","_"),lot)
    for dx in (-1.62,1.62):
        slab("Wayfinding posts",g,x+dx,y,.11,.16,.16,2.21,teal,True,"lot")
    slab("Wayfinding panel",g,x,y,1.45,4.99,.11,1.26,teal)
    label(name,g,x,y-.061,2.071,rect)

for i,(x,y,h) in enumerate([(-37,-8,7),(-38,6,9),(-33,8,7.5),
    (-36,28,9.4),(-28,28,7.6),(-21,28,9),(-12,28,8.2),(1.6,25,8.5),(6.6,27,7.1),
    (15.5,24,8),(15.5,18,7.8),(39.0,17,8.2),(-5.0,-31,6.6),(-8.3,-31,5.5)]):
    g=group(f"tree_{i+1:02d}",lot)
    oval("Pine trunk",g,[(x,y,.11,.15,.15),(x,y,h*.75,.11,.11)],wood,n=6)
    for j in range(3):
        f=j*.195
        oval("Layered pine boughs",g,[(x,y,h*(.23+f),1.3-h*.017-j*.10,1.4-j*.21),
             (x+.097,y-.07,h*(.31+f),1.64-j*.26,1.58-j*.25),
             (x-.17,y+.10,h*(.65+f),.14,.12)],grass,n=8)
    add_box("Tree trunk","lot",(x,y,h*.375),(.32,.31,h*.75),g.name)

for i,(x,y) in enumerate(((-29,-15.2),(-10,-15.1),(6.7,-15.2),(15.0,-14.8),
                           (15.4,14.0),(38.5,7.2),(-22.3,24.9))):
    g=group(f"lot_light_{i+1:02d}",lot)
    slab("Lamp footing",g,x,y,.155,.54,.54,.31,trim,True,"lot")
    beam("Parking lamp pole",g,(x,y,.46),(x,y,5.8),(.10,.10),(.074,.074),teal,n=6)
    beam("Parking lamp arm",g,(x,y,5.73),(x,y-.53,5.73),(.07,.07),(.07,.07),teal,n=4)
    slab("Parking lamp head",g,x,y-.57,5.57,.59,.31,.12,lamp)
    add_box("Lamp pole","lot",(x,y,3),(.20,.20,5.6),g.name)

for i,(x,y) in enumerate(((-17.8,-4.66),(-10.6,-4.66),(-3.6,-4.66),(3.1,-4.66),(10.9,18.8))):
    g=group(f"bollard_{i+1}",lot)
    oval("Safety bollard",g,[(x,y,.22,.11,.11),(x,y,1.17,.105,.105),(x,y,1.23,.066,.066)],trim,n=7)
    add_box("Bollard","lot",(x,y,.725),(.22,.22,1.01),g.name)

doors=[mark("door_main",(-13.32,2.00,.251)),mark("door_east",(-.88,2.00,.251))]
anchors=[mark("car_entry",(-36.5,-31.7,.15)),mark("truck_entry",(37.0,-31.7,.15)),
         mark("truck_exit",(32.5,32.82,.15),pi),mark("driver_walk",(12.62,7.9,.223)),
         mark("vending_breezeway",(-7.1,10.4,.223)),mark("picnic_entrance",(-25.7,11.4,.215))]
for i,d in enumerate(boxes):
    cx,cy,cz=d["center"]
    ob=box(f"collider_{i+1:03d}",collision,(cx,cy-Y0,cz),d["size"],trim)
    ob["collision_only"]=True
    ob.display_type="WIRE"
    ob.hide_render=True

parents=[building,interior,*interior.children,grounds,*lot.children]
for parent in parents:
    batch(parent)
visible=[o for o in site.children_recursive if o.type=="MESH" and not o.get("collision_only")]
tris=sum(len(p.vertices)-2 for o in visible for p in o.data.polygons)
mats=sorted({m.name for o in visible for m in o.data.materials})
assert tris<=12000 and len(mats)<=12,(tris,mats)
info={"name":"Pine Bend Rest Area","era":"Late 1970s public highway rest area, suitable onward",
      "footprint_m":[82,66],"height_m":round(max(v.co.z for o in visible for v in o.data.vertices),4),
      "triangles_visible":tris,"materials":mats,"doors":doors,"anchors":anchors,
      "site_plan":{"passenger_rows":2,"truck_bays":4,"truck_bay_pitch_m":4.25,"truck_bay_length_m":25},
      "collision":{"boxes_blender_z_up":boxes},
      "units":"Metres, glTF Y-up, entrance +Z, origin centered on south parcel edge",
      "texel_size_m":T,"texels_per_art_pixel":2,
      "swatches_used":["pavement","asphalt_worn","brick_courses","roof_tin_blue","plank_horizontal"]}
(ROOT/"model-info.json").write_text(json.dumps(info,indent=2)+"\n")
export_glb(ROOT/"highway-rest-area.glb",[site,*site.children_recursive])
save_source(ROOT/"highway-rest-area.blend")
