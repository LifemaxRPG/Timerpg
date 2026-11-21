"""Build the Port Meridian passenger terminal, quay, and Copperwake boarding link."""
from pathlib import Path
from math import cos, sin, pi
import json
import sys

import bpy
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from sculpture_tools import group, material, mesh, loft, oval, box, beam, plaque
from revision_common import batch, export_glb, save_source

bpy.ops.wm.read_factory_settings(use_empty=True)
root=group("Port Meridian")
quay=group("berth_01_quay",root)
terminal=group("passenger_terminal",root)
roof=group("terminal_roof",terminal)
hall=group("departure_hall",terminal)
land=group("street_and_forecourt",root)
staircase=group("terminal_stairs",terminal)
link=group("fixed_passenger_link",root)
leaf=group("deployable_gangway_leaf",root)
service=group("service_apron",root)
lot=group("lot",root)
stone=material("wall_quay_masonry","stone")
road=material("ground_port_asphalt","road")
brick=material("wall_terminal_brick","brick")
cream=material("wall_terminal_stone","cream")
copper=material("roof_patinated_copper","copper")
steel=material("detail_port_steel","steel")
glass=material("glass_terminal","glass")
light=material("glow_terminal","amber",.55)
ochre=material("detail_safety_ochre","ochre")
rubber=material("detail_fenders","rubber")
timber=material("ground_boarding_deck","timber")
signmat=material("sign_port","sign")

water=-2.40
ship_position=[22.4,-11.75,0]
ship_anchor=[-17.6,21.26,29]
target=tuple(round(s+a,4) for s,a in zip(ship_position,ship_anchor))
door_nodes={}
boxes=[]
stairs=[]

def slab(name,parent,x0,x1,y0,y1,z0,z1,mat):
    return box(name,parent,((x0+x1)/2,(y0+y1)/2,(z0+z1)/2),(x1-x0,y1-y0,z1-z0),mat)

def pole(name,parent,x,y,z0,z1,r,mat,n=8):
    return oval(name,parent,[(x,y,z0,r,r),(x,y,z1,r,r)],mat,n=n)

def along(name,parent,x0,x1,y0,y1,z0,z1,thickness,mat):
    return loft(name,parent,[[(x0,y0,z0-d),(x1,y0,z1-d),(x1,y1,z1-d),(x0,y1,z0-d)]
                            for d in (thickness,0)],mat)

def across(name,parent,x0,x1,y0,y1,z0,z1,thickness,mat):
    return loft(name,parent,[[(x0,y0,z0-d),(x1,y0,z0-d),(x1,y1,z1-d),(x0,y1,z1-d)]
                            for d in (thickness,0)],mat)

def marker(name,x,y,z,yaw=0,**properties):
    ob=group(name,root)
    ob.location=(x,y,z)
    ob.rotation_euler.z=yaw
    door_nodes[name]={"xyz_gltf_m":[x,z,-y],**properties}
    return ob

def collision(name,x0,x1,y0,y1,z0,z1,part):
    boxes.append({"name":name,"center_gltf_m":[(x0+x1)/2,(z0+z1)/2,-(y0+y1)/2],
                  "size_gltf_m":[x1-x0,z1-z0,y1-y0],"group":part})

def wall(name,parent,x0,x1,y0,y1,z0,z1,mat):
    slab(name,parent,x0,x1,y0,y1,z0,z1,mat)
    collision(name,x0,x1,y0,y1,z0,z1,parent.name)

def sign(name,parent,x,y,z,row,width,height,side="west"):
    w,h=.0776*width,.0776*height
    if side=="west":
        points=[(x,y+w/2,z),(x,y-w/2,z),(x,y-w/2,z+h),(x,y+w/2,z+h)]
    elif side=="east":
        points=[(x,y-w/2,z),(x,y+w/2,z),(x,y+w/2,z+h),(x,y-w/2,z+h)]
    else:
        points=[(x-w/2,y,z),(x+w/2,y,z),(x+w/2,y,z+h),(x-w/2,y,z+h)]
    ob=plaque(name,parent,points,signmat,(0,2*row,2*width,2*height),(512,256))
    ob["keep"]=True

# Single straight deep-water berth. No water, terrain, or dredged seabed is baked into the GLB.
slab("Quay foundation",quay,-16,.0,-193,193,-6.6,-.36,stone)
slab("Continuous coping",quay,-16.3,.12,-193.4,193.4,-.36,.035,cream)
slab("Port service road",land,-121,-16.3,-193,193,-.6,.005,road)
slab("Terminal plaza",land,-106,-16.5,-78,69,.006,.035,stone)
slab("Terminal raised walk",land,-96,-80.5,-74,63,.035,.11,cream)
slab("Ship edge stripe",quay,-.71,-.50,-191.5,191.5,.037,.051,ochre)
for y in range(-178,180,8):
    if -73<y<65:
        continue
    slab("Port road center dash",land,-25.08,-24.93,y,y+3,.007,.019,cream)
for y in (-167,-145,-118,-88,-61,-26,9,46,83,120,150,170):
    pole("Cast cruise mooring bollard",quay,-1.85,y,.035,.81,.24,steel)
    pole("Cast bollard head",quay,-1.85,y,.81,1.01,.34,steel,n=6)
    collision("Mooring bollard",-2.19,-1.51,y-.34,y+.34,.035,1.01,quay.name)
    slab("Fender mounting plate",quay,.03,.31,y-1.55,y+1.55,-4.38,-.18,steel)
    slab("Industrial rubber fender",quay,.23,1.40,y-1.70,y+1.70,-3.74,-.69,rubber)
    slab("Fender face pad",quay,1.39,1.56,y-1.32,y+1.32,-3.59,-.86,cream)
for y in (-143,-80,-41,47,101,150):
    for y_offset in (-.30,.30):
        pole("Quay escape ladder rail",quay,.34,y+y_offset,-3.53,.45,.049,ochre,6)
    for i in range(11):
        slab("Quay escape ladder rung",quay,.282,.40,y-.29,y+.29,-3.41+i*.345,-3.36+i*.345,steel)
for y in (-185,-125,-69,-12,43,99,154,185):
    pole("Apron light standard",quay,-5.4,y,.035,8.6,.123,steel)
    slab("Apron light arm",quay,-6.7,-5.26,y-.12,y+.12,8.36,8.57,steel)
    slab("Apron luminaire",quay,-6.98,-6.56,y-.24,y+.24,8.25,8.44,light)
for y in (-192,192):
    pole("Quay endpoint beacon",quay,-.54,y,.035,3.27,.21,steel)
    pole("Quay endpoint lens",quay,-.54,y,3.27,3.72,.31,light)
    pole("Beacon hood",quay,-.54,y,3.72,3.83,.35,copper)

# Street-level shell and walk-through forecourt with two clear entrances.
slab("Check-in floor",terminal,-81,-27,-70,58,.035,.112,cream)
for y0,y1 in [(-70,-46),(-40,9),(15,58)]:
    wall("City side brick wall",terminal,-81,-80.61,y0,y1,.112,4.68,brick)
for y0,y1 in [(-70,-55),(-49,58)]:
    wall("Quay side brick wall",terminal,-27.38,-27,y0,y1,.112,5.6,brick)
for y in (-70,58):
    wall("Terminal end masonry shell",terminal,-80.61,-27.38,y-.16,y+.16,.11,6.6,brick)
for x0,x1,y0,y1 in [(-81.35,-79.8,-70.34,58.34),(-28.4,-26.67,-70.34,58.34),
                     (-79.8,-28.4,-70.34,-69.55),(-79.8,-28.4,57.55,58.34)]:
    slab("Horizontal stone belt",terminal,x0,x1,y0,y1,6.6,7.17,cream)
for y0,y1 in [(-69.7,-46),(-40,9),(15,57.7)]:
    wall("City clerestory",terminal,-81,-80.78,y0,y1,4.68,6.59,glass)
for y0,y1 in [(-46,-40),(9,15)]:
    slab("Recessed portal header",terminal,-81.29,-80.5,y0,y1,4.2,6.6,cream)
    for y in (y0,y1):
        slab("Portal cast-stone jamb",terminal,-81.33,-80.46,y-.29,y+.29,.112,4.35,cream)
slab("Baggage portal header",terminal,-27.4,-26.85,-55,-49,4.7,6.58,cream)
slab("Quay clerestory ribbon",terminal,-27.27,-26.99,-70,58,5.6,6.62,glass)
for y0,y1 in [(-67,-25),(-24,22),(23,55)]:
    slab("Check-in canopy",land,-94,-80.35,y0,y1,4.82,5.21,copper)
    slab("Canopy stone fascia",land,-94.18,-93.85,y0,y1,4.84,5.39,cream)
for y in (-66,-51,-33,-14,4,24,42,54):
    pole("City canopy column",land,-91.2,y,.11,4.88,.22,steel,n=6)
    slab("Canopy linear fixture",land,-88,-84.5,y-.093,y+.093,4.74,4.85,light)
for y in (-54,-50,-18,-14,21,25,50,54):
    slab("Bus curb stripe",land,-101.4,-97.8,y,y+.17,.037,.053,cream)
slab("Drop-off curb",land,-97.7,-96.95,-63,56,.036,.156,cream)
for y in (-57,-32,-4,29,52):
    slab("Bus shelter low plinth",lot,-95.83,-94.0,y-.75,y+.75,.036,.17,stone)
    slab("Bus shelter seat",lot,-95.54,-94.55,y-.65,y+.65,.44,.55,timber)
    for yy in (y-.55,y+.55):
        slab("Seat foundation",lot,-95.48,-94.59,yy-.09,yy+.09,.17,.45,steel)
sign("sign_name_terminal",terminal,-81.46,-4,7.24,0,168,22)
slab("Port name backing",terminal,-81.43,-80.83,-11.05,3.05,7.12,9.14,copper)
sign("sign_departures",land,-94.23,-43,5.0,23,133,15)
slab("Departures fascia backer",land,-94.21,-93.86,-48.3,-37.7,4.9,6.26,copper)
sign("sign_arrivals",land,-94.23,12,5.0,39,116,15)
slab("Arrivals fascia backer",land,-94.21,-93.86,7.3,16.7,4.9,6.26,copper)
marker("door_main",-81.55,-43,.112,-pi/2,clear_width_m=5.4)
marker("door_arrivals",-81.55,12,.112,-pi/2,clear_width_m=5.4)
marker("door_baggage",-26.6,-52,.112,pi/2,clear_width_m=6.0)
for y in (-55,-37,6,20):
    slab("Fixed check-in counter",terminal,-74.0,-72.7,y-2.4,y+2.4,.112,1.23,cream)
    slab("Counter stone edge",terminal,-74.15,-72.55,y-2.45,y+2.45,1.22,1.35,stone)
for y in (-61,-29,-4,30,49):
    for x in (-72,-36):
        slab("Concrete pier capital",terminal,x-.26,x+.26,y-.27,y+.27,.112,8.20,cream)

# Departure floor with real openings above the two switchback stairs.
upper=8.20
stair_origins=(-55,28)
for y0,y1 in [(-70,-55),(-43,28),(40,58)]:
    slab("Upper departure floor",hall,-80.88,-27.0,y0,y1,7.86,upper,timber)
for y in stair_origins:
    slab("Mezzanine west floor edge",hall,-80.88,-68.55,y,y+12,7.86,upper,timber)
    slab("Mezzanine east floor edge",hall,-57.95,-27.0,y,y+12,7.86,upper,timber)

def rail(name,parent,x0,x1,y0,y1,z,n):
    beam(name,parent,(x0,y0,z+1.10),(x1,y1,z+1.10),(.055,.055),(.055,.055),steel,4)
    for i in range(n+1):
        t=i/n
        pole("Rail stanchion",parent,x0+(x1-x0)*t,y0+(y1-y0)*t,z,z+1.1,.043,steel,n=4)

for i,y in enumerate(stair_origins,1):
    # Both runs use .1685 m risers and .36 m going with room to turn at the mid-landing.
    base=.112
    mid=(upper+base)/2
    run=8.64
    treads=24
    for x0,x1,start,direction,lo,hi in [
        (-68.3,-63.8,y+.20,1,base,mid),
        (-63.3,-58.2,y+8.84,-1,mid,upper)]:
        for n in range(treads):
            a,b=(start+direction*p*run/treads for p in (n,n+1))
            t=lo+(hi-lo)*(n+1)/treads
            slab("Stair tread",staircase,x0,x1,min(a,b),max(a,b),t-.1685,t,cream)
        for x in (x0+.24,x1-.24):
            beam("Steel stair stringer",staircase,(x,start,lo-.12),
                 (x,start+direction*run,hi-.12),(.125,.22),(.125,.22),steel,4)
        for x in (x0+.045,x1-.045):
            beam("Sloping stair rail",staircase,(x,start,lo+1.08),(x,start+direction*run,hi+1.08),
                 (.055,.055),(.055,.055),steel,4)
            for n in range(0,25,4):
                f=n/treads
                pole("Stair handrail post",staircase,x,start+direction*run*f,lo+(hi-lo)*f,
                     lo+(hi-lo)*f+1.08,.036,steel,4)
        name=f"stair_{i}_{'lower' if direction==1 else 'upper'}"
        x=(x0+x1)/2
        marker(name+"_foot",x,start,lo)
        marker(name+"_landing",x,start+direction*run,hi)
        stairs.append({"name":name,"x_center":x,"start_blender_y":start,"direction":direction,
                       "run_m":run,"from_y_m":lo,"to_y_m":hi,"treads":treads})
    slab("Mid-level stair landing",staircase,-68.42,-58.06,y+8.84,y+12,mid-.22,mid,cream)
    for x in (-68.19,-58.31):
        slab("Stair landing support",staircase,x-.15,x+.15,y+11.1,y+11.4,.112,mid-.19,steel)
    slab("Upper landing threshold",staircase,-63.35,-58.1,y-.18,y+.205,upper-.24,upper,cream)
    for x in (-68.52,-58.0):
        rail("Mezzanine well guard",staircase,x,x,y+.04,y+12,upper,5)
    rail("Mezzanine well end guard",staircase,-68.52,-58,y+11.97,y+11.97,upper,4)
    rail("Mezzanine guard at stair descending end",staircase,-68.52,-63.42,y+.03,y+.03,upper,2)

# Upper hall cladding, three copper-clad roofs and three broad gabled end windows.
for x0,x1 in [(-81.2,-80.66),(-27.37,-26.83)]:
    slab("Upper cornice",hall,x0,x1,-70.2,58.2,7.15,8.2,cream)
for x,y0,y1 in [(-80.97,-70,58),(-27.08,-70,-32.0),(-27.08,-26.0,58)]:
    wall("Upper hall glazing",hall,x-.15,x+.15,y0,y1,8.2,17.62,glass)
    for index in range(int((y1-y0)/3.7)+1):
        y=y0+.08+index*3.7
        slab("Glazing vertical seam",hall,x-.19,x+.19,y-.058,y+.058,8.2,17.64,steel)
for y in (-70,58):
    wall("Terminal end upper glazing",hall,-80.69,-27.32,y-.15,y+.15,8.2,17.62,glass)
    for j in range(11):
        x=-80.07+j*5.14
        slab("End facade mullion",hall,x-.058,x+.058,y-.185,y+.185,8.2,17.66,steel)
for y0,y1 in [(-70,-28),(-28,15),(15,58)]:
    middle=(y0+y1)/2
    peak=25.85 if y0==-28 else 23.75
    eave=17.78
    across("Watershed copper panel",roof,-82.2,-25.82,y0-1.07,middle,eave,peak,.39,copper)
    across("Watershed copper panel",roof,-82.2,-25.82,middle,y1+1.07,peak,eave,.39,copper)
    # In the shared valley, adjoining panels terminate on the same flat gutter.
    for x,flip in [(-81.02,True),(-26.87,False)]:
        vertices=[(x,y0,17.56),(x,y1,17.56),(x,middle,peak-.34)]
        mesh("Gabled clerestory glazing",roof,vertices,[(2,1,0) if flip else (0,1,2)],glass)
        for left_edge,right_edge,z_left,z_right in [(y0-1.04,middle,eave,peak),(middle,y1+1.04,peak,eave)]:
            beam("Structural raked stone trim",roof,(x,left_edge,z_left-.095),(x,right_edge,z_right-.095),
                 (.17,.20),(.17,.20),cream,4)
        for j in range(1,8):
            y=y0+(y1-y0)*j/8
            z=eave+(peak-eave)*(1-abs(y-middle)/((y1-y0)/2))
            slab("Clerestory slim mullion",roof,x-.064,x+.064,y-.052,y+.052,17.58,z-.32,steel)
    slab("Raised ridge seam",roof,-82.32,-25.7,middle-.13,middle+.13,peak-.11,peak+.14,cream)
    for xx in (-74,-54,-34):
        beam("Interior roof arch tie",roof,(xx,y0+.2,17.3),(xx,y1-.2,17.3),(.135,.133),(.135,.133),steel,4)
        for yy in (y0+.2,y1-.2):
            beam("Interior diagonal truss",roof,(xx,yy,17.37),(xx,middle,peak-.45),(.142,.134),(.142,.134),steel,4)
        slab("Suspended long luminaire",hall,xx-1.25,xx+1.25,middle-.14,middle+.14,16.97,17.15,light)
for y in (-70,-28,15,58):
    slab("Roof rain gutter",roof,-82.45,-25.58,y-.13,y+.13,17.51,17.77,steel)
    for x in (-81.21,-26.70):
        yy=-25.5 if x==-26.70 and y==-28 else y
        slab("Copper downspout",roof,x-.067,x+.067,yy-.073,yy+.073,7.19,17.74,copper)
for x,y0,y1 in [(-81.11,-70.3,58.28),(-26.83,-70.3,-32.0),(-26.83,-26,58.28)]:
    slab("Upper glazing sill",roof,x-.18,x+.18,y0,y1,8.11,8.44,cream)
sign("sign_gate_01",hall,-27.32,-29,12.58,73,101,14)
slab("Gate sign backer",hall,-27.31,-27.06,-33,-25,12.5,13.76,copper)

# Elevated passenger bridge. Ship-side leaf can be hidden as a separate node when unoccupied.
bridge_start=(-27.10,8.20,-29)
hinge=(-4.20,8.90,-29)
# A life-ring post sits directly in front of the authored ship anchor. Offset the
# tapered ramp tip through the northern half of the actual railing opening.
landing_z=30.04
leaf_end=(target[0],target[1],-landing_z)
nose_x=2.36
nose_height=hinge[1]+(target[1]-hinge[1])*(nose_x-hinge[0])/(target[0]-hinge[0])
nose=(nose_x,nose_height,-landing_z)

def tapered(name,parent,x0,x1,y0,y1,z0,z1,w0,w1,thickness,mat):
    return loft(name,parent,[[(x0,y0-w0/2,z0-d),(x1,y1-w1/2,z1-d),
                             (x1,y1+w1/2,z1-d),(x0,y0+w0/2,z0-d)]
                            for d in (thickness,0)],mat)

for part,start,end,w0,w1,covered in [(link,bridge_start,hinge,4.24,4.24,True),
                                    (leaf,hinge,nose,4.24,1.24,True),
                                    (leaf,nose,leaf_end,1.24,1.24,False)]:
    x0,z0,y0=start
    x1,z1,y1=end
    tapered("Clear boarding deck",part,x0,x1,y0,y1,z0,z1,w0,w1,.22,timber)
    if covered:
        tapered("Boarding bridge roof",part,x0,x1,y0,y1,z0+3.32,z1+3.32,w0+.63,w1+.58,.21,copper)
    for side in (-1,1):
        sy=y0+(w0/2-.046)*side
        ey=y1+(w1/2-.046)*side
        if covered:
            tapered("Bridge lower side panel",part,x0,x1,sy,ey,z0+.67,z1+.67,.092,.092,.67,cream)
            tapered("Bridge enclosed side glazing",part,x0,x1,sy,ey,z0+3.13,z1+3.13,.09,.09,2.47,glass)
            for k in (1,2,3,4):
                f=k/5
                x,z,yy=x0+(x1-x0)*f,z0+(z1-z0)*f,sy+(ey-sy)*f
                slab("Dark glass framing",part,x-.072,x+.072,yy-.066,yy+.066,z+.61,z+3.19,steel)
        else:
            for h,r in ((.53,.036),(1.09,.046)):
                beam("Landing guardrail",part,(x0,sy,z0+h),(x1-.1,ey,z1+h),(.04,r),(.04,r),steel,4)
            for f in (0,.52,.94):
                x,z,yy=x0+(x1-x0)*f,z0+(z1-z0)*f,sy+(ey-sy)*f
                pole("Landing guard stanchion",part,x,yy,z,z+1.1,.031,steel,4)
    if covered:
        for f in (.19,.47,.78):
            x,y,z=x0+(x1-x0)*f,y0+(y1-y0)*f,z0+(z1-z0)*f
            slab("Gangway ceiling fixture",part,x-.60,x+.60,y-.10,y+.10,z+3.02,z+3.13,light)
for x in (-22,-9.9):
    for y in (-31.25,-26.75):
        slab("Fixed link pier footing",link,x-.93,x+.93,y-.67,y+.67,.035,.47,stone)
        slab("Fixed link structural pier",link,x-.31,x+.31,y-.29,y+.29,.45,8.48,steel)
        collision("Link bridge pier",x-.94,x+.94,y-.68,y+.68,.035,8.48,link.name)
slab("Gangway hinge housing",link,-4.38,-3.98,-31.68,-26.32,8.37,8.80,steel)
marker("door_gate_01",-27.28,-29,8.20,pi/2,clear_width_m=4.24)
marker("gangway_deck_hinge",-4.20,-29,8.90,pi/2)
marker("gangway_ship_end",leaf_end[0],leaf_end[2],leaf_end[1],pi/2)
marker("ship_port_transfer",target[0],-target[2],target[1],pi/2,ship_node="gangway_port")
marker("berth_01_mv_copperwake",ship_position[0],0,ship_position[1],
       heading_deg=0,waterline_y_m=water,required_depth_m=12.5)
marker("approach_aft",30,400,water,lane_width_m=95)
marker("approach_forward",30,-400,water,lane_width_m=95)
marker("mooring_aft",-1.85,150,.035)
marker("mooring_forward",-1.85,-150,.035)
for name,x,y,panel_y,w,h in [("quay_sign_berth",-12,-53,55,117,17),("quay_sign_service",-36,101,104,94,13)]:
    for dx in (-2.9,2.9):
        pole("Quayside directional sign post",lot,x+dx,y,.035,3.88,.08,steel,4)
    slab("Quayside sign backer",lot,x-w*.0388-.045,x+w*.0388+.045,y-.04,y+.14,2.62,2.62+h*.0776+.05,copper)
    sign(name,lot,x,y-.06,2.65,panel_y,w,h,side="south")

# Harbor clock and lift core form a visible landmark from the street and water.
wall("Lift shaft masonry core",terminal,-78.5,-73.15,-8.75,-2.0,.112,26.9,brick)
slab("Clock drum base belt",roof,-78.79,-72.86,-9.05,-1.7,24.6,25.18,cream)
slab("Clock faces block",roof,-78.61,-73.05,-8.92,-1.83,26.75,30.5,cream)
slab("Clock tower crown",roof,-79.02,-72.61,-9.36,-1.37,30.41,30.80,copper)
slab("Clock tower roof cornice",roof,-79.12,-72.51,-9.43,-1.28,30.8,31.0,cream)
across("Clock cap",roof,-79.1,-72.54,-9.39,-5.35,31,32.15,.16,copper)
across("Clock cap",roof,-79.1,-72.54,-5.35,-1.31,32.15,31,.16,copper)
for x,flip in [(-78.64,False),(-73.02,True)]:
    axis_y=-5.36
    pos_z=28.45
    ring=[(x,axis_y+1.43*cos(i*pi/12),pos_z+1.43*sin(i*pi/12)) for i in range(24)]
    mesh("Clock glass disk",roof,ring,[tuple(range(24)) if flip else tuple(reversed(range(24)))],light)
    outside=x+(.017 if flip else -.017)
    for i in range(12):
        a=i*pi/6
        r1=1.04 if i%3==0 else 1.18
        beam("Clock hour index",roof,(outside,axis_y+r1*cos(a),pos_z+r1*sin(a)),
             (outside,axis_y+1.32*cos(a),pos_z+1.32*sin(a)),(.039,.039),(.039,.039),steel,4)
    for dy,dz,thick in ((-.58,.48,.065),(.59,.83,.049)):
        beam("Clock hands",roof,(outside,axis_y,pos_z),(outside,axis_y+dy,pos_z+dz),
             (thick,thick),(thick*.72,thick*.72),steel,4)
for z in (.112,8.20):
    slab("Lift door face",terminal,-73.15,-73.094,-7.09,-4.20,z,z+2.72,steel)
    slab("Lift control surround",terminal,-73.146,-73.05,-3.88,-3.58,z+1.05,z+1.43,cream)
marker("lift_ground",-72.82,-5.62,.112,pi/2)
marker("lift_departure",-72.82,-5.62,8.20,pi/2)

# Service end, coaches, and lighting make the long quay readable at walking height.
for x,y in [(-37,-115),(-37,-132),(-37,-149),(-49,-118),(-49,-136),(-64,-116),(-64,-133),
            (-39,105),(-39,122),(-39,139),(-52,110),(-52,127)]:
    slab("Painted baggage load box",service,x-2.3,x+2.3,y-6.1,y+6.1,.013,.028,ochre)
slab("Port service house floor",service,-84,-57,93,122,.005,.081,cream)
for x0,x1,y0,y1 in [(-84,-83.7,93,122),(-83.7,-57,121.7,122),(-83.7,-57,93,93.3),
                     (-57.3,-57,93.3,99),(-57.3,-57,111,121.7)]:
    wall("Service house sided wall",service,x0,x1,y0,y1,.081,5.53,brick)
slab("Service door header beam",service,-57.44,-56.89,98.95,111.05,4.45,5.76,cream)
along("Service shed roof",service,-85,-56,92,123,6.85,5.77,.23,copper)
for x in (-83.7,-57.15):
    slab("Service shed roof packing",service,x-.11,x+.11,93,122,5.45,6.24 if x<-80 else 5.68,steel)
for y in (93.12,121.85):
    along("Service sloped end infill",service,-83.88,-57.02,y-.12,y+.12,6.77,5.72,.97,brick)
marker("door_service",-56.55,105,.081,pi/2,clear_width_m=12)
sign("sign_service_house",service,-56.84,105,4.67,104,94,13,side="east")
for x,y,mat in [(-39,89,brick),(-45,89,copper),(-52,89,steel),(-47,150,cream),(-54,150,brick),(-60,147,copper)]:
    slab("Baggage container",lot,x-1.55,x+1.55,y-5.8,y+5.8,.017,2.9,mat)
    for yy in (y-5.83,y+5.83):
        slab("Container handling rail",lot,x-1.60,x+1.60,yy-.04,yy+.04,.035,.18,steel)
    collision("Baggage container",x-1.55,x+1.55,y-5.8,y+5.8,.014,2.9,lot.name)
for x,y in [(-70,-88),(-70,72),(-97,-58),(-97,55),(-39,75),(-43,-79),(-39,-159),(-37,163)]:
    pole("Ground light pole",lot,x,y,.035,6.43,.092,steel,6)
    slab("Ground light lamp",lot,x-.41,x+.32,y-.18,y+.18,6.2,6.4,light)
for x in (-74,-52,-33):
    for y in (-74,62):
        slab("Passenger wayfinding stripe",land,x-3,x+3,y-.11,y+.11,.036,.049,ochre)

# Static coach props give the passenger curb a readable scale.
coaches=group("optional_shuttle_coaches",lot)
for y,paint in [(-42,copper),(13,cream)]:
    x=-100.25
    slab("Coach paint shell",coaches,x-1.28,x+1.28,y-6.48,y+6.48,.38,1.94,paint)
    slab("Coach wraparound glazing",coaches,x-1.26,x+1.26,y-6.44,y+6.43,1.94,3.04,glass)
    slab("Coach roof",coaches,x-1.22,x+1.22,y-6.38,y+6.37,3.04,3.31,cream)
    slab("Coach nose bumper",coaches,x-1.26,x+1.26,y-6.56,y-6.44,.39,.62,steel)
    for xx in (x-.88,x+.88):
        slab("Coach marker lamp",coaches,xx-.18,xx+.18,y-6.55,y-6.47,.74,.93,light)
    for xx in (x-1.18,x+1.18):
        for yy in (y-4.24,y+4.05):
            beam("Coach tire",coaches,(xx-.13,yy,.488),(xx+.13,yy,.488),
                 (.488,.488),(.488,.488),rubber,n=6)
    collision("Parked shuttle coach",x-1.28,x+1.28,y-6.56,y+6.48,.01,3.31,coaches.name)

# Descriptive bounds use the longitudinal quay as the main footprint.
for node in (quay,terminal,roof,hall,land,staircase,link,leaf,service,lot,coaches):
    batch(node)
bpy.context.view_layer.update()
meshes=[ob for ob in bpy.context.scene.objects if ob.type=="MESH"]
for ob in meshes:
    ob.data.calc_loop_triangles()
count=sum(len(ob.data.loop_triangles) for ob in meshes)
info={
    "name":"Port Meridian",
    "era":"1996 onward passenger terminal",
    "footprint_m":{"quay_length":386,"landward_extent":121,"clock_roof_above_grade":32.15},
    "waterline_gltf_y":water,
    "coordinate_system":"glTF metres, +Y up, +X seaward and +Z along the bow approach. Origin at grade at the quay edge.",
    "triangles":count,
    "materials":sorted({material.name for ob in meshes for material in ob.data.materials}),
    "swatches":["plank_horizontal"],
    "berth":{"id":"berth_01","vessel":"MV Copperwake","vessel_length_m":309,"vessel_beam_m":39.26,
             "vessel_origin_gltf_m":ship_position,"vessel_heading_deg":0,
             "vessel_waterline_above_keel_m":9.35,"required_design_depth_m":12.5,
             "quay_limits_gltf_z_m":[-193,193],
             "ship_port_anchor_local_gltf_m":ship_anchor,"ship_port_anchor_world_gltf_m":target},
    "boarding_path_gltf_m":[bridge_start,hinge,nose,leaf_end],
    "onboard_transfer_gltf_m":[[leaf_end[0],leaf_end[1],-leaf_end[2]],target],
    "landing_deck_width_m":1.24,
    "boarding_leaf_node":leaf.name,
    "stair_runs":stairs,
    "anchors":door_nodes,
    "wall_collision_boxes":boxes,
    "optional_nodes":[leaf.name,lot.name,coaches.name],
    "runtime":"Use authored floors and steps for walking surfaces. The leaf is shown deployed at mean water. Hide or replace it when no ship is present. No runtime gangway movement, tides or ship transit are implemented by this asset.",
}
# Convert the boarding path from temporary (Blender X, height, Blender Y) tuples.
info["boarding_path_gltf_m"]=[[x,z,-y] for x,z,y in info["boarding_path_gltf_m"]]
(ROOT/"model-info.json").write_text(json.dumps(info,indent=2)+"\n")
export_glb(ROOT/"port-meridian.glb")
save_source(ROOT/"port-meridian.blend")
print(f"{count} triangles, {len(info['materials'])} materials")
