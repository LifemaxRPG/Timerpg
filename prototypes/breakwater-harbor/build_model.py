"""Build Breakwater Harbor in Blender XY, with the harbor entrance facing -Y."""
from pathlib import Path
from random import Random
from math import pi, cos, sin
import json
import sys

import bpy
ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
from sculpture_tools import group, material, mesh, loft, oval, box, beam, plaque
from revision_common import batch, export_glb, save_source

bpy.ops.wm.read_factory_settings(use_empty=True)
harbor = group("Breakwater Harbor")
shore = group("shore_and_seawall", harbor)
breakwaters = group("armored_breakwaters", harbor)
docks = group("floating_slips", harbor)
quay = group("visitor_quay", harbor)
yard = group("launch_and_service", harbor)
warehouse = group("dry_stack_warehouse", harbor)
shed_roof = group("warehouse_roof", warehouse)
racks = group("dry_stack_racks", warehouse)
office = group("harbor_office", harbor)
lot = group("lot", harbor)
stock = group("optional_rack_boats", lot)
forklift = group("optional_marina_forklift", lot)

concrete = material("wall_concrete", "concrete")
asphalt = material("ground_asphalt", "asphalt")
steel = material("detail_steel", "steel")
teal = material("wall_teal", "teal")
cream = material("wall_cream", "cream")
stone = material("detail_riprap", "stone")
orange = material("detail_safety_ochre", "orange")
glass = material("glass_marina", "glass")
warm = material("glow_marina", "warm", .6)
red = material("detail_navigation_red", "red", .35)
timber = material("ground_dock_timber", "dock")
lettering = material("sign_marina", "signs")
waterline = -.85
anchors = {}
colliders = []

def slab(name, parent, x0, x1, y0, y1, z0, z1, mat):
    return box(name, parent, ((x0+x1)/2, (y0+y1)/2, (z0+z1)/2),
               (x1-x0, y1-y0, z1-z0), mat)

def post(name, parent, x, y, z0, z1, radius, mat, n=8):
    return oval(name, parent, [(x,y,z0,radius,radius),(x,y,z1,radius,radius)], mat, n=n)

def pitched(name, parent, x0, x1, y0, y1, top0, top1, thickness, mat):
    rings = [[(x0,y0,top0-d),(x1,y0,top0-d),(x1,y1,top1-d),(x0,y1,top1-d)] for d in (thickness,0)]
    return loft(name,parent,rings,mat)

def marker(name, xyz, yaw=0, **properties):
    node = group(name, harbor)
    node.location = xyz
    node.rotation_euler.z = yaw
    anchors[name] = {"gltf_m": [xyz[0], xyz[2], -xyz[1]], **properties}

def collision(name, bounds):
    x0,x1,y0,y1,z0,z1 = bounds
    colliders.append({"name":name, "center_gltf_m":[(x0+x1)/2,(z0+z1)/2,-(y0+y1)/2],
                      "size_gltf_m":[x1-x0,z1-z0,y1-y0]})

def barrier(name, parent, x0, x1, y0, y1, z0, z1, mat):
    slab(name,parent,x0,x1,y0,y1,z0,z1,mat)
    collision(name,(x0,x1,y0,y1,z0,z1))

def sign(name, parent, x, y, z, pixel_y, w, h):
    width, height = w*.0776, h*.0776
    obj = plaque(name,parent,[(x-width/2,y,z),(x+width/2,y,z),
                 (x+width/2,y,z+height),(x-width/2,y,z+height)],lettering,
                 (0,pixel_y*2,w*2,h*2),(256,128))
    obj["keep"] = True

# The apron is split around a publicly accessible ramp. Land grade is zero.
for a,b in [(-48,21),(28,47)]:
    slab("Bulkhead core",shore,a,b,0,8,-2.25,-.12,concrete)
    slab("Bulkhead coping",shore,a,b,-.24,7.9,-.12,.02,concrete)
slab("Continuous backlot",shore,-48,47,8,43,-.8,0,asphalt)
slab("Warehouse apron",shore,-45,-4,8,15,.006,.026,concrete)
slab("Office footway",shore,-4,14,2,17,.004,.026,concrete)
slab("Launch service apron",yard,18,36,8,24,.004,.024,concrete)
slab("Parking aisle",shore,-4,46,32,36,.005,.026,concrete)
for x in range(-1,18,3):
    slab("Visitor parking stripe",shore,x,x+.095,21,26,.003,.023,cream)
for x in range(30,46,3):
    slab("Trailer stall stripe",yard,x,x+.11,25,32,.003,.023,cream)
for x in (-35,-26,-17):
    slab("Forklift clearway marking",yard,x,x+.14,6,12,.031,.041,orange)
for y in (2.3,3.35,4.4,5.45):
    slab("Pedestrian crossing",shore,-5.5,-2.5,y,y+.38,.024,.035,cream)

# Riprap arms frame an open central fairway.
rng = Random(803)
def arm(name,points):
    segments = []
    for (ax,ay),(bx,by) in zip(points,points[1:]):
        dx,dy=bx-ax,by-ay
        length=(dx*dx+dy*dy)**.5
        segments.append((length,dx/length,dy/length))

    def edge(side,width,z,end):
        coords=[]
        for i,(x,y) in enumerate(points):
            before=segments[max(0,i-1)]
            after=segments[min(i,len(segments)-1)]
            _,ux,uy=before
            _,wx,wy=after
            divisor=1+ux*wx+uy*wy
            offset=side*width/divisor
            along=-end if i==0 else end if i==len(points)-1 else 0
            coords.append((x-offset*(uy+wy)+along*ux,y+offset*(ux+wx)+along*uy,z))
        return coords

    rings=[edge(-1,width,z,end)+edge(1,width,z,end)[::-1]
           for width,z,end in ((3.0,-2.0,1.5),(2.0,-.15,.75),(1.47,.11,.48))]
    loft(name,breakwaters,rings,stone)
    for (ax,ay),(length,ux,uy) in zip(points,segments):
        vx,vy=-uy,ux
        for s in range(1,int(length),3):
            for side in (-1,1):
                rx=rng.uniform(.63,1.16); ry=rng.uniform(.65,1.1)
                x,y=ax+s*ux+side*2.12*vx,ay+s*uy+side*2.12*vy
                z=rng.uniform(-.65,-.47)
                oval("Angular armor stone",breakwaters,[(x,y,z-.42,rx,ry),(x,y,z+.14,rx,ry),
                     (x+.21,y-.17,z+.48,rx*.59,ry*.65)],stone,n=5)
arm("West armor", [(-45,1),(-45,-67),(-18,-82)])
arm("East armor", [(43,1),(43,-73),(37,-82)])
for x,mat in [(-18,teal),(37,red)]:
    post("Marker footing",breakwaters,x,-82,-.2,.51,.66,concrete)
    post("Daymark column",breakwaters,x,-82,.51,2.95,.26,mat)
    post("Marker lens",breakwaters,x,-82,2.95,3.24,.33,mat)
    post("Marker top",breakwaters,x,-82,3.24,3.4,.39,steel)

# Floating dock tops at -.48 m, 37 cm freeboard at the design waterline.
def pontoon(name, x0, x1, y0, y1):
    slab(name+" float",docks,x0,x1,y0,y1,-1.06,-.63,steel)
    slab(name+" boards",docks,x0,x1,y0,y1,-.63,-.48,timber)
def cleat(parent,x,y,z):
    slab("Mooring cleat base",parent,x-.19,x+.19,y-.12,y+.12,z,z+.10,steel)
    slab("Cast cleat horn",parent,x-.27,x+.27,y-.08,y+.08,z+.10,z+.19,steel)

pontoon("Spine",-36,20,-20,-17.25)
pontoon("Landing stem",-9.1,-6.5,-17.25,-7.7)
pitched("Hinged aluminum gangway",docks,-9,-6.6,-7.72,.1,-.48,.027,.14,concrete)
for x in (-9.04,-6.56):
    beam("Gangway handrail",docks,(x,-7.72,.55),(x,.1,1.057),(.045,.044),(.045,.044),steel,n=4)
    for t in (0,.33,.67,1):
        y=-7.72+7.82*t
        post("Gangway stanchion",docks,x,y,-.48+.507*t,.55+.507*t,.043,steel,4)
for x in (-35.4,-26.4,-17.4,-8.4,.6,9.6,18.6):
    pontoon("Finger",x-.58,x+.58,-34,-20)
    for y in (-33.28,-24.1,-20.9):
        cleat(docks,x,y,-.48)
    post("Floating dock guide pile",docks,x,-33.7,-2.4,.48,.145,timber)
    post("Pile cap",docks,x,-33.7,.48,.56,.16,cream)
for x in (-35.5,-22,-12,.5,12,19.4):
    post("Primary dock guide pile",docks,x,-18.6,-2.4,.54,.18,timber)
for i,x in enumerate((-30.9,-21.9,-12.9,-3.9,5.1,14.1),1):
    marker(f"berth_sail_{i:02}",(x,-26.7,waterline),pi,
           length_m=12,beam_m=4.4,required_depth_m=2.5)

# Fixed deep-water face for a 42-m yacht. The water-facing wall is continuous.
slab("Visitor pier footing",quay,31.5,38,-54,.08,-2.6,-.12,concrete)
slab("Visitor quay pedestrian deck",quay,31.2,38.28,-54.3,.12,-.12,.031,concrete)
slab("Yacht berth edge stripe",quay,31.24,31.4,-53.65,-.2,.032,.05,orange)
for y in (-50,-42,-34,-26,-18,-10,-2):
    post("Quay bollard",quay,31.87,y,.031,.68,.16,steel)
    post("Bollard cap",quay,31.87,y,.68,.8,.235,steel)
    slab("Vertical timber rubbing strake",quay,31.02,31.51,y-.18,y+.18,-1.6,.13,timber)
for y in (-36,-15):
    for x in (30.87,31.12):
        post("Quay rescue ladder rail",quay,x,y,-1.78,.35,.048,orange)
    for i in range(6):
        slab("Stainless ladder tread",quay,30.84,31.15,y-.046,y+.046,-1.6+i*.29,-1.54+i*.29,steel)
marker("berth_yacht",(26.6,-29.5,waterline),pi,length_m=44,beam_m=8.3,required_depth_m=4.0)
marker("harbor_entrance",(5,-86,waterline),pi,nominal_channel_width_m=48)

marker("yacht_approach",(26.6,-77,waterline),pi,required_depth_m=4.0)
marker("sailboat_turning_basin",(-8,-59,waterline),pi,nominal_diameter_m=40,required_depth_m=2.5)

# Concrete ramp falls into the harbor. No render-only water is exported.
pitched("Grooved launch ramp",yard,21,28,-7,8,-1.77,.012,.29,concrete)
for i in range(19):
    y=-6.5+i*.78
    z=-1.77+(y+7)*1.782/15
    pitched("Ramp traction groove",yard,21.24,27.76,y,y+.085,z+.008,z+.018,.016,steel)
for x in (20.7,28.3):
    pitched("Ramp guide curb",yard,x-.28,x+.28,-7,8,-1.49,.27,.31,concrete)
marker("launch_ramp_top",(24.5,7.7,.012),width_m=7)
marker("launch_ramp_water",(24.5,-3,waterline),width_m=7)

# Dry-stack enclosure: a real clear-span warehouse with a lift aisle and a tall, open roll-up bay.
slab("Warehouse continuous floor",warehouse,-42,-8,11.7,40.2,0,.045,concrete)
barrier("West warehouse wall",warehouse,-42,-41.7,11.7,40.2,.04,12.2,teal)
barrier("East warehouse wall",warehouse,-8.3,-8,11.7,40.2,.04,12.2,teal)
barrier("Rear warehouse wall",warehouse,-41.7,-8.3,39.85,40.2,.04,12.2,teal)
for a,b in [(-42,-30.9),(-19.1,-8)]:
    barrier("Front bay jamb",warehouse,a,b,11.7,12.03,.04,10.05,teal)
    slab("Lower masonry plinth",warehouse,a,b,11.55,11.72,.06,2.35,cream)
    slab("Glazed upper bay",warehouse,a+.8,b-.8,11.50,11.71,6.5,8.06,glass)
    for x in (a+.75,(a+b)/2,b-.75):
        slab("Clerestory mullion",warehouse,x-.075,x+.075,11.43,11.53,6.44,8.12,steel)
    for z in (6.44,8.06):
        slab("Clerestory frame",warehouse,a+.75,b-.75,11.43,11.53,z,z+.07,cream)
slab("Clear opening lintel",warehouse,-42,-8,11.61,12.035,10.05,12.2,cream)
slab("Raised shutter roll",warehouse,-30.94,-19.06,11.31,11.68,9.68,10.13,steel)
for x in (-31.1,-18.9):
    slab("Roll door track",warehouse,x-.105,x+.105,11.52,11.73,.045,10,steel)
    post("Bay safety bollard",warehouse,x,10.84,.026,1.02,.125,orange)
for a,b,y0,y1,t0,t1 in [(-42.65,-7.35,11.04,25.9,12.08,15.2),
                          (-42.65,-7.35,25.9,40.77,15.2,12.08)]:
    pitched("Standing seam roof",shed_roof,a,b,y0,y1,t0,t1,.24,steel)
for x, normal in ((-42,-1),(-8,1)):
    v=[(x,11.7,12.15),(x,40.2,12.15),(x,25.9,15.0)]
    mesh("Gable siding",shed_roof,v,[(0,1,2) if normal>0 else (2,1,0)],teal)
for y in (11.05,40.76):
    slab("Fascia and gutter",shed_roof,-42.69,-7.31,y-.1,y+.08,11.89,12.22,cream)
slab("Ridge cap",shed_roof,-42.72,-7.28,25.82,25.98,15.17,15.31,cream)
for x in (-38,-11,-8.1,-41.9):
    slab("Warehouse downpipe",warehouse,x-.066,x+.066,40.22,40.42,.08,12.02,steel)
for y in (15,23,31,38):
    for x in (-41.35,-8.65):
        slab("Clear-span frame column",warehouse,x-.18,x+.18,y-.19,y+.19,.045,11.8,steel)
    beam("Roof truss bottom chord",shed_roof,(-41.5,y,11.56),(-8.5,y,11.56),(.11,.1),(.11,.1),steel,n=4)
    beam("Roof truss rising chord",shed_roof,(-41.5,y,11.56),(-25,y,13.0),(.09,.09),(.09,.09),steel,n=4)
    beam("Roof truss falling chord",shed_roof,(-25,y,13.0),(-8.5,y,11.56),(.09,.09),(.09,.09),steel,n=4)
    slab("Aisle fixture",warehouse,-25.42,-24.58,y-.15,y+.15,11.13,11.24,warm)
for y in (18,28,36):
    for x in (-42.1,-7.9):
        slab("Side vent housing",warehouse,x-.128,x+.128,y-1.6,y+1.6,8.55,9.67,steel)
        for z in (8.68,8.91,9.14,9.37):
            slab("Side vent blade",warehouse,x-.156,x+.156,y-1.5,y+1.5,z,z+.09,cream)
sign("sign_name_dry_stack",warehouse,-25,11.59,10.45,17,73,11)
marker("door_dry_stack",(-25,11.15,.045),clear_width_m=11.8,clear_height_m=9.63)

# Two rows of heavy cantilever frames, three bays deep and three tiers high.
def runabout(parent,x,y,z,length,shell):
    # V-bottom, flared sheer, squared transom and a narrow foredeck.
    profile=[(-.11,-.49),(.11,-.49),(.96,-.30),(1,-.04),
             (.85,.43),(-.85,.43),(-1,-.04),(-.96,-.30)]
    def ring(scale,h):
        return [(x+a*1.23*scale,y+b*length,z+h) for a,b in profile]
    loft("Runabout hull",parent,[ring(.45,.08),ring(.97,.38),ring(1,.74)],shell)
    slab("Runabout cockpit",parent,x-.8,x+.8,y-.75,y+1.24,z+.73,z+.88,steel)
    pitched("Runabout windshield",parent,x-.91,x+.91,y-.86,y-.47,z+1.27,z+.86,.065,glass)
    slab("Runabout transom pad",parent,x-.76,x+.76,y+1.45,y+2.18,z+.72,z+.84,cream)

for x in (-35.1,-14.9):
    west=x< -25
    wall_side=x-2.12 if west else x+2.12
    open_side=x+2.18 if west else x-2.18
    collision("Dry stack rack footprint",(x-2.2,x+2.2,12.75,39.6,.045,10.65))
    for y in (12.88,21.82,30.74,39.49):
        slab("Rack upright",racks,wall_side-.15,wall_side+.15,y-.16,y+.16,.045,11.05,steel)
        slab("Rack anchor shoe",racks,wall_side-.33,wall_side+.33,y-.3,y+.3,.047,.14,orange)
        for z in (.49,3.61,6.73):
            slab("Cantilever support",racks,min(open_side,wall_side),max(open_side,wall_side),y-.16,y+.16,z-.2,z,orange)
            beam("Rack knee brace",racks,(wall_side,y,z-.39),(open_side*.6+wall_side*.4,y,z-.07),(.075,.075),(.075,.075),steel,4)
    for z in (.49,3.61,6.73):
        for runner in (x-.79,x+.79):
            slab("Padded bunk runner",racks,runner-.095,runner+.095,12.71,39.63,z,z+.38,timber)
    for bay,y in enumerate((17.42,26.2,35.1)):
        for tier,z in enumerate((.62,3.74,6.86)):
            if (west and (bay,tier)==(1,0)) or (not west and (bay,tier) in ((0,0),(2,1))):
                continue
            runabout(stock,x,y,z-.11,6.6+(bay%2)*.7,(cream,teal,cream,red)[(tier+bay+int(west))%4])
marker("dry_stack_staging",(-24.9,15,.045),pi,clear_aisle_width_m=15.8)

# Harbormaster and check-in pavilion. The open center is a real doorway into the shell.
slab("Office concrete floor",office,-1.3,12.5,5.2,14.0,.024,.055,concrete)
for a,b in [(-1.3,4.7),(6.55,12.5)]:
    barrier("Office front low wall",office,a,b,5.23,5.51,.055,.95,cream)
    slab("Office front windows",office,a,b,5.29,5.49,.95,2.92,glass)
    collision("Office glazing",(a,b,5.29,5.49,.95,2.92))
    for i in range(4):
        x=a+.13+(b-a-.26)*i/3
        slab("Office mullion",office,x-.055,x+.055,5.2,5.31,.91,2.96,steel)
for a,b,y0,y1 in [(-1.3,-1.04,5.51,7.16),(-1.3,-1.04,12.04,14),(12.22,12.5,5.51,14),(-1.04,12.22,13.73,14)]:
    barrier("Office perimeter wall",office,a,b,y0,y1,.055,3.35,teal)
for a,b in [(-1.36,3.69),(7.38,12.56)]:
    slab("Pavilion stone band",office,a,b,5.13,5.24,.055,.72,stone)
slab("Office lintel and fascia",office,-1.35,12.58,5.14,5.56,2.92,3.79,cream)
slab("Office deck overhang",office,-2.05,13.20,3.15,14.35,3.34,3.56,teal)
slab("Office parapet trim",office,-1.84,13,5.05,14.2,3.55,3.99,cream)
slab("Office roof field",office,-1.68,12.84,5.18,14.02,3.991,4.08,steel)
for x in (-1.64,12.8):
    post("Porch post",office,x,3.58,.025,3.39,.09,steel,4)
for x in (0,11):
    slab("Canopy fixture",office,x-.17,x+.17,3.97,4.37,3.28,3.36,warm)
slab("Check-in counter",office,8.15,11.9,10.6,11.45,.055,1.10,cream)
barrier("Office west sill",office,-1.3,-1.04,7.16,12.04,.055,1.27,teal)
barrier("Office west header",office,-1.3,-1.04,7.16,12.04,2.70,3.35,teal)
collision("Office west glazing",(-1.39,-1.37,7.16,12.04,1.27,2.70))
for y in (7.10,9.60,12.04):
    slab("West window mullion",office,-1.42,-1.28,y,y+.075,1.20,2.77,cream)
for z in (1.20,2.70):
    slab("West window frame",office,-1.42,-1.28,7.10,12.12,z,z+.075,cream)
slab("Aft side glazing",office,-1.39,-1.37,7.16,12.04,1.27,2.70,glass)
sign("sign_name_marina",office,5.6,5.10,2.95,0,118,16)
# Lift the full sign into the storefront fascia with its original physical pixel ratio.
sign_panel=bpy.data.objects["sign_name_marina"]
sign_panel.location.z=.55
slab("Marina sign backing",office,1.0,10.2,5.14,5.24,3.49,4.75,teal)
marker("door_main",(5.63,4.99,.055),clear_width_m=1.85,clear_height_m=2.85)
marker("sign_harbor_pedestrian",(5.61,2.18,.026),pi)
sign("sign_launch",yard,28.56,1.95,1.10,41,49,11)
for x in (26.75,30.37):
    post("Maple launch sign post",yard,x,2.02,.023,2.12,.068,steel,4)
for x in (17.95,19.25,29.9,31.12):
    post("Ramp approach bollard",yard,x,6.8,.025,.91,.123,orange)

# Fender groups, shore power, beacon fixtures and life rings keep their services legible.
for x,y in [(-35,-18.5),(-17.7,-18.5),(2.55,-18.5),(17.6,-18.5),(36.9,-37),(36.9,-17)]:
    z=-.48 if x<20 else .032
    slab("Shore power plinth",lot,x-.26,x+.26,y-.2,y+.2,z,z+.11,steel)
    slab("Power pedestal",lot,x-.18,x+.18,y-.14,y+.14,z+.10,z+.79,cream)
    slab("Pedestal marker lamp",lot,x-.19,x+.19,y-.15,y+.15,z+.77,z+.86,warm)
for x,y,z in [(-14,2.0,.026),(15,4,.026),(37.6,-48,.03),(37.6,-24,.03),(37.6,-8,.03),(-43.8,11,.026),(-6.2,16,.025)]:
    post("Pier light base",lot,x,y,z,z+.25,.19,concrete)
    post("Pier light pole",lot,x,y,z+.24,z+4.2,.057,steel)
    slab("Pier light arm",lot,x-.62,x+.066,y-.055,y+.055,z+4.13,z+4.25,steel)
    slab("Pier luminaire",lot,x-.78,x-.44,y-.13,y+.13,z+4.08,z+4.25,warm)
for x,y,z in [(-11,-18.1,-.48),(20,-18.1,-.48),(35.5,-29,.03),(-10,2,.026)]:
    post("Rescue equipment stand",lot,x,y,z,z+1.52,.058,steel,4)
    verts=[(x+r*cos(a*2*pi/12),y-.063,z+1.08+r*sin(a*2*pi/12)) for r in (.31,.20) for a in range(12)]
    mesh("Life ring",lot,verts,[(i,(i+1)%12,12+(i+1)%12,12+i) for i in range(12)],orange)
for x0,x1 in [(-47,-10),(13,46)]:
    for y in (42.3,):
        slab("Rear yard guardrail",shore,x0,x1,y-.066,y+.066,1.04,1.13,steel)
        for i in range(10):
            x=x0+(x1-x0)*i/9
            post("Rear yard guardrail post",shore,x,y,.002,1.14,.071,steel,4)
for x,y in [(-12,7),(-12,8),(16,33),(17,33)]:
    slab("Utility cabinet footing",yard,x-.43,x+.43,y-.37,y+.37,.003,.13,concrete)
    slab("Utility cabinet",yard,x-.32,x+.32,y-.29,y+.29,.13,1.32,teal)
    slab("Utility cabinet vent",yard,x-.24,x+.24,y-.32,y-.294,.96,1.22,steel)

# Parked high-reach marina forklift with a twin-stage upright and long forks.
fx,fy=-38,7
slab("Lift counterweight",forklift,fx-1.42,fx+1.42,fy+.9,fy+2.03,.42,1.58,orange)
slab("Lift chassis",forklift,fx-1.23,fx+1.23,fy-1.62,fy+1.76,.45,1.21,orange)
for x in (fx-1.25,fx+1.25):
    for y in (fy-.97,fy+1.26):
        beam("Forklift tire",forklift,(x-.28,y,.66),(x+.28,y,.66),(.63,.63),(.63,.63),steel,n=10)
        beam("Forklift hub",forklift,(x-.30,y,.66),(x+.30,y,.66),(.28,.28),(.28,.28),orange,n=10)
for x in (fx-.69,fx+.69):
    for y in (fy-.59,fy+.74):
        post("Overhead guard pillar",forklift,x,y,1.17,2.89,.081,steel,4)
    slab("Lift mast channel",forklift,x-.16,x+.16,fy-1.8,fy-1.53,.36,4.72,steel)
    slab("Lift mast rail",forklift,x-.068,x+.068,fy-1.91,fy-1.77,.5,4.5,orange)
    slab("Fork heel",forklift,x-.15,x+.15,fy-2.0,fy-1.80,.19,.95,steel)
    slab("Padded marine fork",forklift,x-.16,x+.16,fy-5.7,fy-1.82,.19,.34,steel)
slab("Forklift overhead guard",forklift,fx-.84,fx+.84,fy-.7,fy+.86,2.84,3.03,orange)
slab("Operator seat",forklift,fx-.36,fx+.36,fy+.07,fy+.64,1.18,1.61,steel)
slab("Mast tie bar",forklift,fx-.91,fx+.91,fy-1.84,fy-1.52,4.47,4.72,steel)

for name, x,y,z in [("dock_gangway_land",-7.8,.16,.027),("dock_gangway_float",-7.8,-7.65,-.48),
                     ("yacht_quay_access",35,.3,.02),("dry_stack_transfer",-25,5.1,.02)]:
    marker(name,(x,y,z),pi)
# Floors and dock boards remain visual walk surfaces. Only solid barriers are listed.
for x in (-31.1,-18.9):
    collision("Dry stack bay bollard",(x-.13,x+.13,10.71,10.97,.027,1.02))
collision("Parked marine lift",(-39.55,-36.45,1.25,9.05,.02,3.04))

groups=[shore,breakwaters,docks,quay,yard,warehouse,shed_roof,racks,office,lot,stock,forklift]
for node in groups:
    batch(node)
bpy.context.view_layer.update()
all_meshes=[ob for ob in bpy.context.scene.objects if ob.type=="MESH"]
for ob in all_meshes:
    ob.data.calc_loop_triangles()
count=sum(len(ob.data.loop_triangles) for ob in all_meshes)
info={
    "name":"Breakwater Harbor", "era":"1990s onward",
    "dimensions_m":{"width":95,"length":129,"height_above_land":15.31},
    "coordinate_system":"glTF metres, +Y up, +Z toward seaward approach. Origin at the bulkhead and land grade.",
    "waterline_y_m":waterline,
    "triangles":count, "materials": sorted({m.name for ob in all_meshes for m in ob.data.materials}),
    "swatches":["plank_horizontal"],
    "berths_and_anchors":anchors, "collision_boxes":colliders,
    "rack_capacity":{"sides":2,"bays_per_side":3,"tiers":3,"slots":18,"displayed_boats":15},
    "optional_nodes":["lot","optional_rack_boats","optional_marina_forklift"],
    "placement":"Supply terrain at land grade behind the apron and water at the design waterline. Dredge to the annotated depths in the world. Authored dock walks, rack aisle, and approaches are geometry only.",
}
(ROOT/"model-info.json").write_text(json.dumps(info,indent=2)+"\n")
export_glb(ROOT/"breakwater-harbor.glb")
save_source(ROOT/"breakwater-harbor.blend")
print(f"{count} triangles; {len(info['materials'])} materials")
