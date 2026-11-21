"""Build Vesper, an original 42 m raised-pilothouse yacht."""
import json
import sys
from math import hypot,pi
from pathlib import Path

import bpy
from mathutils import Vector
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from revision_common import batch,export_glb,save_source
from sculpture_tools import T,beam,box,group,loft,material,mesh,oval,plaque

bpy.ops.wm.read_factory_settings(use_empty=True)
boat=group("vesper")
hull=group("hull_and_underbody",boat)
main=group("main_deck",boat)
upper=group("pilothouse_deck",boat)
fly=group("flybridge",boat)
hardware=group("marine_hardware",boat)
furniture=group("lot",boat)
navy=material("wall_hull","hull")
ivory=material("wall_superstructure","ivory")
boot=material("detail_antifouling","boot")
steel=material("detail_marine_trim","trim")
brass=material("detail_champagne","champagne")
teak=material("ground_teak","teak")
olive=material("detail_olive","olive")
linen=material("detail_linen","linen")
glass=material("glass_tinted","glass",.10)
amber=material("glow_deck_lamps","amber",.50)
sign=material("sign_name","name")
waterline=2.10
main_floor=4.46
upper_floor=7.15
fly_floor=9.81


def block(name,parent,x,y,z,w,d,h,mat):
    return box(name,parent,(x,y,z+h/2),(w,d,h),mat)


# Long flared bow, a broad midship chine, and a stern that steps down toward the water.
stations=[(.055,-21.50,.44),(.55,-20.05,.36),(1.48,-17.55,.23),
          (2.65,-13.15,.102),(3.52,-8.43,.023),(3.94,-3.6,0),
          (3.96,3.1,0),(3.88,9.18,0),(3.62,14.38,-.018),
          (3.30,16.44,-.38),(3.03,18.71,-1.68),(.055,18.98,-1.70)]


def perimeter(z,width,bow_shift=0,aft_shift=0,sheer=0):
    result=[]
    for x,y,dz in stations:
        u=(y+21.5)/40.48
        result.append((x*width,y+bow_shift*(1-u)**4-aft_shift*u**4,z+dz*sheer))
    return result+[(-x,y,z) for x,y,z in reversed(result)]


sections=[(.0,.32,8.2,4.65,0),(.42,.56,6.15,3.55,0),(1.17,.77,3.9,2.22,.035),
          (2.1,.91,2.66,1.45,.05),(2.21,.925,2.45,1.36,.07),
          (3.68,.98,.70,.45,.54),(4.28,1,0,0,1),(4.42,1,0,0,1)]
rings=[perimeter(*section) for section in sections]
loft("Continuous hull shell",hull,rings,lambda i,_:(boot,boot,boot,brass,navy,navy,ivory)[i],ends=False)
mesh("Keel closure",hull,rings[0],[tuple(reversed(range(len(rings[0]))))],boot)
# Terminate the weather deck at the transom break. The exposed beach level
# has its own flat floor, so it never inherits the hull's sloped sheer.
deck_limit=15.53
for a,b in zip(rings[-1][:11],rings[-1][1:12]):
    if a[1]>=deck_limit:
        continue
    if b[1]>deck_limit:
        fraction=(deck_limit-a[1])/(b[1]-a[1])
        b=tuple(u+(v-u)*fraction for u,v in zip(a,b))
    for side in (-1,1):
        points=[(0,a[1],a[2]),(side*a[0],a[1],a[2]),
                (side*b[0],b[1],b[2]),(0,b[1],b[2])]
        mesh("Sheer deck skin",main,points,[(0,1,2,3) if side>0 else (3,2,1,0)],teak)
for side in (-1,1):
    x=side*1.19
    beam("Propulsion shaft",hardware,(x,14.4,.26),(x,16.64,.46),
         (.083,.083),(.083,.083),steel,n=8)
    beam("Shaft strut",hardware,(x,15.43,.782),(x,15.65,.382),
         (.097,.102),(.076,.079),steel,n=6)
    beam("Propeller hub",hardware,(x,16.60,.46),(x,17.02,.46),
         (.16,.16),(.11,.11),brass,n=9)
    for dx,dz in ((.36,.24),(-.36,-.24),(-.24,.36),(.24,-.36)):
        beam("Propeller blade",hardware,(x,16.83,.46),(x+dx,16.91,.46+dz),
             (.08,.072),(.046,.053),brass,n=6)
    block("Spade rudder",hardware,x,17.41,.19,.145,.49,1.91,navy)

for side in (-1,1):
    for x,y,z,w in [(3.45,-8.5,3.08,2.13),(3.71,-4.55,3.03,2.03),
                    (3.86,-.58,3.03,1.95),(3.86,3.12,3.02,2.23),(3.74,7.3,3.06,2.55)]:
        # Sheer windows are recessed into their dark frames.
        block("Hull window surround",hull,side*x,y,z,.14,w,.52,steel)
        block("Hull window glass",hull,side*(x+.073),y,z+.065,.035,w-.15,.385,glass)

# Defined main walk areas. Bow deck stays raked and the circulation terrace stays level.
block("Main salon and aft terrace teak",main,0,3.60,4.417,6.62,23.59,.043,teak)
block("Transom beach platform",main,0,18.79,2.625,6.23,3.22,.095,teak)
block("Lower beach club floor",main,0,16.409,2.635,6.24,1.538,.085,teak)
block("Transom beach platform trim",main,0,20.41,2.594,6.14,.065,.107,ivory)
for side in (-1,1):
    beam("Stern grab rail",hardware,(side*2.87,19.33,2.71),(side*2.87,19.33,3.48),
         (.044,.044),(.044,.044),steel,n=6)
    beam("Stern grab rail return",hardware,(side*2.87,19.33,3.48),(side*2.87,18.46,3.48),
         (.044,.044),(.044,.044),steel,n=6)

# Stainless boarding ladder and discreet outboard platform braces.
for x in (-.37,.37):
    beam("Swim ladder side",hardware,(x,20.28,2.69),(x,20.39,1.78),
         (.025,.025),(.025,.025),steel,n=6)
for z in (1.83,2.09,2.35,2.61):
    beam("Swim ladder teak step",hardware,(-.37,20.39,z),(.37,20.39,z),
         (.030,.039),(.030,.039),teak,n=5)
for x in (-1.9,1.9):
    beam("Transom platform brace",hardware,(x,18.20,2.11),(x,19.95,2.63),
         (.081,.087),(.067,.078),steel,n=6)

stairs_info=[]
def stair(name,g,x,y0,y1,low,high,width):
    count=round((high-low)/.18)
    stride=(y1-y0)/count
    for i in range(count):
        z=low+(i+1)*(high-low)/count
        block("Teak stair tread",g,x,y0+(i+.5)*stride,z-.115,width,abs(stride)+.006,.115,teak)
        block("Ivory stair riser",g,x,y0+i*stride,z-.18,width,.057,.171,ivory)
    for side in (-1,1):
        xx=x+side*width*.5
        beam("Stair handrail",g,(xx,y0,low+.94),(xx,y1,high+.94),
             (.025,.025),(.025,.025),steel,n=6)
        for fraction in (0,.34,.67,1):
            yy=y0+(y1-y0)*fraction
            zz=low+(high-low)*fraction
            block("Stair rail support",g,xx,yy,zz,.055,.055,.95,steel)
    stairs_info.append({"name":name,"foot_gltf_m":[x,low,-y0],"landing_gltf_m":[x,high,-y1],"treads":count})


for side in (-1,1):
    stair(f"beach stair {side}",main,side*2.43,18.45,15.38,2.72,main_floor,1.07)
stair("main to pilothouse deck",main,2.31,12.79,7.88,main_floor,upper_floor,1.12)
stair("pilothouse to flybridge",upper,-1.99,6.83,2.91,upper_floor,fly_floor,1.04)

# Two actual room shells. Transom entries are apertures, with no panel spanning either door.
def raised_room(g,front,back,w,base,top,opening):
    side_y0=front+1.72
    side_y1=back-.17
    lower=base+.69
    header=top-.41
    for s in (-1,1):
        x=s*(w-.12)
        block("Deckhouse base sill",g,x,(side_y0+side_y1)/2,base,.24,side_y1-side_y0,.69,ivory)
        block("Continuous side window",g,x,(side_y0+side_y1)/2,lower,.17,side_y1-side_y0,header-lower,glass)
        block("Structural window header",g,x,(side_y0+side_y1)/2,header,.26,side_y1-side_y0,.41,ivory)
        for y in range(round(side_y0)+1,round(side_y1),3):
            block("Raked deckhouse mullion",g,x,y,lower,.249,.10,header-lower,steel)
        # Chiselled bow shoulder takes the continuous glass into the front fascia.
        face=[(s*w,side_y0,lower),(s*(w-.74),front,lower),
              (s*(w-.74),front+.44,header),(s*w,side_y0,header)]
        mesh("Raked panoramic corner pane",g,face,[(0,1,2,3) if s<0 else (3,2,1,0)],glass)
        wing_lo=[(s*w,side_y0,base),(s*(w-.74),front,base),
                 (s*(w-.74),front,lower),(s*w,side_y0,lower)]
        mesh("Forward structural haunch",g,wing_lo,[(0,1,2,3) if s<0 else (3,2,1,0)],ivory)
        span=(2*w-opening)/2
        block("Aft corner wall",g,s*(opening/2+span/2),back,base,span,.25,top-base,ivory)
        block("Aft doorway frame",g,s*opening/2,back+.115,base,.072,.087,top-base,steel)
    points=[(-(w-.74),front,lower),((w-.74),front,lower),
            ((w-.74),front+.44,header),(-(w-.74),front+.44,header)]
    mesh("Sloping forward window",g,points,[(0,1,2,3)],glass)
    block("Forward sill panel",g,0,front+.10,base,2*(w-.74),.26,.69,ivory)
    block("Aft opening beam",g,0,back,top-.42,opening,.25,.42,ivory)
    block("Ceiling deck plate",g,0,(front+back)/2,top,2*w+.22,back-front+.25,.115,ivory)


salon=group("main_salon",main)
raised_room(salon,-8.8,6.14,2.98,main_floor,7.0,2.48)
block("Pilothouse terrace teak",upper,0,-.42,7.10,5.72,16.6,.05,teak)
# Downward inclined wing accents give the side elevation its own silhouette.
for side in (-1,1):
    x=side*3.104
    p=[(x,1.78,4.47),(x,2.33,4.47),(x,5.61,7.16),(x,4.77,7.16)]
    mesh("Swept buttress",main,p,[(0,1,2,3) if side>0 else (3,2,1,0)],ivory)
    beam("Aft terrace roof brace",main,(side*2.91,6.16,4.45),(side*2.91,7.26,7.09),
         (.118,.10),(.10,.09),ivory,n=4)
block("Aft terrace overhead trailing beam",main,0,6.9,6.97,6.31,2.12,.17,ivory)

pilothouse=group("wheelhouse",upper)
raised_room(pilothouse,-7.68,1.82,2.44,upper_floor,9.66,1.70)
block("Flybridge terrace",fly,0,-2.54,9.75,5.12,10.9,.06,teak)
block("Flybridge aft visor lip",fly,0,2.88,9.64,5.12,.16,.16,ivory)
block("Raked flybridge glazing sill",fly,0,-7.76,fly_floor,3.9,.22,.44,ivory)
mesh("Tinted flybridge screen",fly,[(-1.95,-7.80,10.24),(1.95,-7.80,10.24),
        (1.95,-7.49,10.77),(-1.95,-7.49,10.77)],[(0,1,2,3)],glass)

# Cap rails follow the exposed decks and leave real gaps for stairs.
def rail(g,points,z):
    for a,b in zip(points,points[1:]):
        x,y=a
        u,v=b
        count=max(1,round(hypot(u-x,v-y)/1.15))
        for i in range(count):
            q=i/count
            block("Rail upright",g,x+(u-x)*q,y+(v-y)*q,z,.047,.047,.94,steel)
        for height in (.42,.91):
            beam("Rail run",g,(x,y,z+height),(u,v,z+height),(.018,.018),(.018,.018),steel,n=4)
    x,y=points[-1]
    block("Rail terminal",g,x,y,z,.047,.047,.94,steel)


for side in (-1,1):
    deck_edge=[(side*3.52,-7.5),(side*3.80,-3.5),(side*3.79,8.8)]
    if side>0:
        rail(hardware,deck_edge+[(3.64,11.15)],4.44)
        rail(hardware,[(3.56,12.45),(3.48,13.8),(3.10,15.3)],4.44)
    else:
        rail(hardware,deck_edge+[(side*3.48,13.8),(side*3.10,15.3)],4.44)
    rail(hardware,[(side*.41,-20.06),(side*1.35,-17.5),(side*2.52,-13.2),(side*3.39,-8.45)],4.57)
    rail(hardware,[(side*2.72,-7.42),(side*2.76,1.8),(side*2.77,7.78)],upper_floor)
    rail(hardware,[(side*2.42,-7.50),(side*2.42,-4.16),(side*2.42,2.84)],fly_floor)
rail(hardware,[(-2.74,7.78),(1.62,7.78)],upper_floor)
rail(hardware,[(-1.40,2.82),(2.41,2.82)],fly_floor)
rail(hardware,[(-1.82,15.26),(1.82,15.26)],main_floor)

# Cushions and furniture are detachable groups. The built-in salon counter stays with the shell.
def settee(name,parent,x,y,z,w,d,back_side):
    g=group(name,parent)
    block("Settee pedestal",g,x,y,z+.022,w,d,.31,teak)
    block("Seat cushion",g,x,y,z+.333,w+.026,d+.03,.16,linen)
    if back_side:
        block("Settee back",g,x+back_side*(w*.5-.071),y,z+.47,.15,d+.028,.44,olive)
    else:
        block("Settee back",g,x,y+d*.5-.08,z+.47,w+.014,.16,.44,olive)
    return g


def table(name,g,x,y,z,w,d):
    block("Fixed table foot",g,x,y,z,.38,.34,.64,steel)
    block(name,g,x,y,z+.64,w,d,.101,teak)


settee("salon_port_lounge",furniture,-2.37,.62,main_floor,.88,4.34,-1)
settee("salon_starboard_lounge",furniture,2.37,.62,main_floor,.88,4.34,1)
table("Salon cocktail table",salon,0,.77,main_floor,1.04,1.77)
block("Salon bar cabinet",salon,-1.85,-4.70,main_floor,1.99,1.23,1.02,teak)
block("Salon pale counter",salon,-1.85,-4.70,main_floor+1.02,2.08,1.35,.125,linen)
settee("aft_port_seating",furniture,-2.93,10.05,main_floor,.73,3.02,-1)
table("Alfresco dining table",main,-.96,10.02,main_floor,1.42,2.35)
settee("aft_dining_bench",furniture,-.97,11.81,main_floor,2.5,.59,0)
for x in (-1.18,1.18):
    seat=group(f"foredeck_sunpad_{x}",furniture)
    block("Recessed sunpad base",seat,x,-12.18,4.48,1.68,2.77,.156,ivory)
    block("Foredeck sunpad",seat,x,-12.18,4.637,1.62,2.64,.115,linen)
    beam("Raised sunpad back",seat,(x,-13.38,4.75),(x,-13.87,5.11),
         (.72,.07),(.72,.07),olive,n=4)
for x in (-.81,.85):
    settee(f"fly_lounge_{x}",furniture,x,.88,fly_floor,1.21,1.22,0)
table("Flybridge low table",fly,.68,-.35,fly_floor,.83,1.11)

# Raked hardtop posts and roof taper. Water- and engine-facing elements remain attached.
for side in (-1,1):
    beam("Hardtop forward strut",fly,(side*2.13,-6.25,fly_floor),(side*1.84,-5.94,12.0),
         (.139,.133),(.112,.115),ivory,n=6)
    beam("Swept aft hardtop strut",fly,(side*2.16,.83,fly_floor),(side*1.90,-.18,12.0),
         (.155,.133),(.12,.118),ivory,n=6)
outline=[(-1.24,-6.78,11.92),(1.24,-6.78,11.92),(2.31,-5.88,11.92),(2.31,.29,11.92),
         (1.76,.94,11.92),(-1.76,.94,11.92),(-2.31,.29,11.92),(-2.31,-5.88,11.92)]
top=[(x*.965,y-.06,12.19+(0.03 if y<0 else 0)) for x,y,_ in outline]
loft("Swept composite hardtop",fly,[outline,top],ivory)
for x,y in ((-.84,-4.11),(.84,-4.08)):
    oval("Satellite radome",hardware,[(x,y,12.18,.26,.25),(x,y,12.42,.38,.36),
         (x,y,12.76,.31,.30),(x,y,12.88,.10,.11)],ivory,n=9)
beam("Raked navigation mast",hardware,(0,-2.66,12.19),(0,-2.04,13.77),
     (.143,.133),(.07,.071),steel,n=7)
beam("Radar array",hardware,(-.89,-2.15,13.53),(.89,-2.15,13.53),
     (.091,.078),(.091,.078),ivory,n=4)
block("White anchor lamp",hardware,0,-2.02,13.75,.14,.144,.13,amber)

for parent,z,y in ((upper,upper_floor,-6.37),(fly,fly_floor,-6.67)):
    block("Helm console base",parent,0,y,z,2.55,.58,.84,ivory)
    block("Dark chart display surround",parent,0,y,z+.83,2.61,.63,.107,steel)
    for x in (-.76,.54):
        block("Helm display",parent,x,y,z+.946,.46,.38,.028,glass)
    beam("Helm shaft",parent,(.55,y+.27,z+.72),(.55,y+.40,z+1.11),
         (.05,.05),(.05,.05),steel,n=6)
    oval("Helm rim hub",parent,[(.55,y+.39,z+.925,.14,.14),
         (.55,y+.39,z+1.045,.18,.18)],steel,n=8)

for x,y in ((-2.4,8.45),(2.4,8.45),(-1.86,5.01),(1.86,5.01),
            (-1.9,-4.22),(1.9,-4.22),(0,-.45)):
    z=6.94 if y<7 else 6.91
    block("Recessed overhead lamp",main,x,y,z,.23,.18,.037,amber)
for x,y in ((-2.35,-14.65),(2.35,-14.65),(-3.01,14.16),(3.01,14.16)):
    block("Mooring cleat base",hardware,x,y,4.47,.22,.33,.073,steel)
    beam("Docking cleat",hardware,(x,y-.23,4.59),(x,y+.23,4.59),
         (.062,.062),(.062,.062),steel,n=7)
for x in (-.58,.58):
    oval("Forepeak capstan",hardware,[(x,-19.31,4.73,.24,.25),
         (x,-19.31,4.82,.18,.183),(x,-19.31,4.98,.21,.21)],steel,n=9)
beam("Stainless anchor roller",hardware,(0,-21.43,4.87),(0,-20.23,4.83),
     (.096,.083),(.079,.083),steel,n=6)

w,h=84*T,22*T
block("Aft garage bulkhead",main,0,15.60,2.70,3.72,.19,1.66,navy)
block("Transom name panel",main,0,15.698,3.045,w+.08,.027,h+.073,navy)
y=15.715
z=3.51
plaque("sign_name",main,[(w/2,y,z-h/2),(-w/2,y,z-h/2),(-w/2,y,z+h/2),(w/2,y,z+h/2)],
       sign,(0,0,84,22),(84,22))


def marker(name,x,y,z,heading=0):
    ob=group(name,boat)
    ob.location=(x,y,z)
    ob.rotation_euler.z=heading
    return {"node":ob.name,"position_gltf_m":[x,z,-y],"heading_deg":round(heading*180/pi)}


anchors=[marker("board_stern",0,19.97,2.72,pi),marker("board_starboard",3.65,11.76,4.46,pi/2),
         marker("salon_entry",0,6.50,4.46,pi),marker("wheelhouse_entry",0,2.05,7.15,pi),
         marker("fly_helm",.54,-5.61,9.81),marker("captain_spawn",.55,-5.15,7.15),
         marker("mooring_bow",0,-20.48,4.76),marker("mooring_stern",2.91,14.16,4.47)]
for part in (hull,main,upper,fly,hardware,salon,pilothouse,*furniture.children):
    batch(part)
meshes=[ob for ob in boat.children_recursive if ob.type=="MESH"]
coordinates=[v.co for ob in meshes for v in ob.data.vertices]
lo=[min(v[i] for v in coordinates) for i in range(3)]
hi=[max(v[i] for v in coordinates) for i in range(3)]
tris=sum(len(f.vertices)-2 for ob in meshes for f in ob.data.polygons)
materials=sorted({m.name for ob in meshes for m in ob.data.materials})
assert tris<=12000 and len(materials)<=12
info={"name":"Vesper","type":"Raised-pilothouse luxury motor yacht","era":"2010s onward",
      "length_overall_m":round(hi[1]-lo[1],3),"beam_m":round(hi[0]-lo[0],3),
      "waterline_above_keel_m":waterline,"height_m":round(hi[2],3),"triangles_visible":tris,
      "materials":materials,"anchors":anchors,"stairs":stairs_info,
      "axes":"glTF metres Y up, bow +Z, origin amidships at the keel",
      "texel_size_m":T,"texels_per_art_pixel":2,"swatches_used":["plank_horizontal"],
      "collision":"Derive vehicle collision from hull and deck shapes. The two rooms have open aft portals. Game navigation to be integrated."}
(ROOT/"model-info.json").write_text(json.dumps(info,indent=2)+"\n")
export_glb(ROOT/"vesper-yacht.glb")
save_source(ROOT/"vesper-yacht.blend")
