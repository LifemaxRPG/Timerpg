"""Build Alder, an 8.6 metre pocket cruiser with a masthead sloop rig."""
import json
import sys
from math import pi,sin,hypot
from pathlib import Path

import bpy
from mathutils import Vector as V

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from revision_common import batch,export_glb,save_source
from sculpture_tools import T,beam,box,group,loft,material,mesh,oval,plaque

bpy.ops.wm.read_factory_settings(use_empty=True)
boat=group("alder")
hull=group("hull",boat)
deck=group("deck_and_cockpit",boat)
cabin=group("cabin_trunk",boat)
rig=group("mast_and_standing_rig",boat)
main=group("sail_main",boat)
jib=group("sail_jib",boat)
deck_rig=group("running_rig",boat)
navy=material("wall_hull","navy")
boot=material("detail_antifouling","boot")
ivory=material("wall_deck","ivory")
wood=material("ground_teak","teak")
steel=material("detail_metal","steel")
cord=material("detail_rope","cord")
cloth=material("wall_sailcloth","cloth")
glass=material("glass_ports","glass")
cushion=material("detail_cushion","cushion")
lamp=material("glow_masthead","lamp",.42)
name_mat=material("sign_name","name")
waterline=1.43


def block(name,parent,x,y,z,w,d,h,mat):
    return box(name,parent,(x,y,z+h/2),(w,d,h),mat)


# Starboard half section from stem to transom. Each station carries its bow rise.
stations=[(.028,-4.52,.22),(.38,-4.06,.15),(.88,-3.37,.08),
          (1.23,-2.43,.02),(1.41,-1.28,0),(1.46,-.03,-.001),
          (1.44,1.31,-.026),(1.33,2.53,-.058),(1.17,3.56,-.089),
          (1.00,4.08,-.105)]


def outline(z,width,front_shift,rear_shift,sheer):
    points=[]
    for x,y,rise in stations:
        length_fraction=(y+4.52)/8.60
        yy=y+front_shift*(1-length_fraction)**4-rear_shift*length_fraction**4
        points.append((x*width,yy,z+rise*sheer))
    return points+[(-x,y,h) for x,y,h in reversed(points)]


skins=[(.92,.17,2.9,2.65,.0),(1.08,.47,1.43,1.68,.12),
       (1.27,.75,.83,1.03,.23),(1.43,.87,.51,.64,.08),
       (1.515,.91,.43,.49,.08),(1.566,.93,.36,.44,.08),
       (2.19,1,0,0,1),(2.234,1.008,0,0,1)]
perimeters=[outline(*spec) for spec in skins]
loft("Continuous round-bilge hull",hull,perimeters,
     lambda i,j:(boot,boot,boot,boot,ivory,navy,ivory)[i],ends=False)
last=perimeters[0]
mesh("Underbody closure",hull,last,[tuple(reversed(range(len(last))))],boot)

# Narrow fin with an attached bulb and a separate rudder and tiller.
def foil(parent,name,levels,skin):
    rings=[]
    for y0,y1,z,half in levels:
        rings.append([(0,y0,z),(half,y0+(y1-y0)*.28,z),(half*.64,y1-.05,z),
                    (0,y1,z),(-half*.64,y1-.05,z),(-half,y0+(y1-y0)*.28,z)])
    return loft(name,parent,rings,skin)


foil(hull,"Encapsulated ballast keel",[
     (.37,1.20,.045,.088),(.17,1.30,.18,.12),(-.30,1.27,1.02,.20)],boot)
oval("Ballast shoe",hull,[(0,.77,0,.11,.52),(0,.79,.075,.155,.60),
    (0,.77,.179,.10,.53)],boot,n=12)
rudder=group("rudder_pivot",boat)
foil(rudder,"Rudder blade",[(3.18,3.61,.49,.054),(3.15,3.66,.64,.073),
     (3.025,3.57,1.635,.091)],navy)
beam("Rudder stock",rudder,(0,3.20,1.44),(0,3.20,2.35),(.034,.034),(.034,.034),steel,n=8)
beam("Shaped tiller",rudder,(0,3.2,2.34),(0,2.69,2.49),(.052,.051),(.056,.044),wood,n=8)
beam("Tiller grip",rudder,(0,2.69,2.49),(0,2.12,2.51),(.056,.047),(.043,.039),wood,n=8)

# The deck has a true open cockpit well, cut into a full perimeter.
cockpit_start=1.31
cockpit_end=3.56
cockpit_half=.785
outer=perimeters[-1][:10]
for i in range(len(outer)-1):
    x0,y0,z0=outer[i]
    x1,y1,z1=outer[i+1]
    hole=y0>=cockpit_start-1e-5 and y1<=cockpit_end+1e-5
    w=cockpit_half if hole else 0
    for s in (-1,1):
        points=[(s*w,y0,z0),(s*x0,y0,z0),(s*x1,y1,z1),(s*w,y1,z1)]
        mesh("Weather deck",deck,points,[(0,1,2,3) if s>0 else (3,2,1,0)],ivory)
for s in (-1,1):
    mesh("Cockpit side wall",deck,[(s*cockpit_half,1.31,1.85),(s*cockpit_half,3.56,1.85),
        (s*cockpit_half,3.56,2.145),(s*cockpit_half,1.31,2.208)],
        [(0,1,2,3) if s>0 else (3,2,1,0)],ivory)
block("Drained cockpit sole",deck,0,2.435,1.825,1.57,2.25,.053,wood)
block("Cockpit forward bulkhead",deck,0,1.329,1.878,1.57,.038,.329,ivory)
block("Cockpit stern bulkhead",deck,0,3.544,1.878,1.57,.033,.265,ivory)
for s in (-1,1):
    block("Cockpit locker",deck,s*.635,2.42,1.878,.28,2.10,.24,ivory)
    block("Teak seat slats",deck,s*.633,2.42,2.118,.315,2.10,.037,wood)
    block("Removable seat cushion",deck,s*.642,2.21,2.157,.269,1.30,.062,cushion)
    beam("Cockpit coaming cap",deck,(s*.83,1.34,2.24),(s*.83,3.53,2.185),
         (.043,.037),(.046,.036),wood,n=4)

# Cabin side portlights stay separate from the closed companionway.
bottom=[(-.47,-2.66,2.21),(.47,-2.66,2.21),(.87,-2.08,2.22),(.86,1.18,2.198),
        (-.86,1.18,2.198),(-.87,-2.08,2.22)]
top=[(-.39,-2.55,2.58),(.39,-2.55,2.58),(.72,-1.93,2.765),(.74,1.16,2.753),
     (-.74,1.16,2.753),(-.72,-1.93,2.765)]
loft("Low cabin trunk",cabin,[bottom,top],ivory)
for s in (-1,1):
    for y in (-1.25,-.65):
        points=[(s*.831,y-.21,2.374),(s*.831,y+.21,2.374),
                (s*.774,y+.21,2.588),(s*.774,y-.21,2.588)]
        mesh("Raked cabin port",cabin,points,[(0,1,2,3) if s>0 else (3,2,1,0)],glass)
    beam("Cabin handrail",cabin,(s*.59,-1.83,2.832),(s*.59,.77,2.822),
         (.03,.035),(.03,.035),wood,n=5)
    for y in (-1.72,-.92,-.1,.71):
        block("Handrail shoe",cabin,s*.59,y,2.764,.065,.087,.065,steel)
block("Companionway board",cabin,0,1.192,2.227,.62,.043,.46,wood)
block("Sliding companion hatch",cabin,0,.98,2.746,.71,.56,.047,wood)
for x in (-.35,.35):
    block("Companion hatch rail",cabin,x,.97,2.753,.041,.65,.043,steel)
block("Forehatch frame",cabin,0,-1.98,2.74,.52,.57,.05,steel)
block("Tinted forehatch pane",cabin,0,-1.98,2.789,.43,.48,.023,glass)

# Pulpits, pushpit and paired lifelines. The aft sections have a boarding opening.
def guard(name,points):
    for x,y,z in points:
        beam(name,deck,(x,y,z),(x,y,z+.53),(.026,.026),(.022,.022),steel,n=5)
    for a,b in zip(points,points[1:]):
        for height in (.30,.53):
            aa=(a[0],a[1],a[2]+height)
            bb=(b[0],b[1],b[2]+height)
            beam("Lifeline run",deck,aa,bb,(.011,.011),(.011,.011),steel,n=5)


for s in (-1,1):
    guard("Deck stanchion",[(s*.34,-4.07,2.38),(s*.80,-3.40,2.30),(s*1.11,-2.48,2.244),
                          (s*1.29,-1.28,2.234),(s*1.33,-.03,2.233)])
    guard("Aft stanchion",[(s*1.31,1.31,2.208),(s*1.20,2.53,2.176),
                         (s*1.03,3.56,2.145),(s*.88,4.0,2.132),(s*.50,4.03,2.132)])
    for y,x,z in ((-3.11,s*.82,2.28),(3.83,s*.83,2.138)):
        block("Cleat base",deck,x,y,z,.113,.15,.026,steel)
        beam("Mooring cleat",deck,(x,y-.114,z+.052),(x,y+.114,z+.052),
             (.023,.023),(.023,.023),steel,n=6)
    for y in (1.61,3.21):
        oval("Cockpit sheet winch",deck,[(s*1.10,y,2.233,.108,.108),
             (s*1.10,y,2.312,.080,.080),(s*1.10,y,2.359,.094,.094)],steel,n=9)
    for y in (1.97,2.17,2.39):
        oval("Coiled sheet",deck,[(s*.92,y,2.235,.088,.125),(s*.92,y,2.254,.088,.125)],cord,n=10)
block("Bow roller bracket",deck,0,-4.22,2.429,.16,.36,.054,steel)
beam("Anchor shank",deck,(0,-4.43,2.481),(0,-3.98,2.485),(.027,.027),(.025,.025),steel,n=6)
for s in (-1,1):
    beam("Bow roller anchor fluke",deck,(0,-4.37,2.468),(s*.141,-4.48,2.415),
         (.025,.068),(.015,.049),steel,n=5)
# Timber boarding ladder on the open transom.
for x in (-.23,.23):
    beam("Swim ladder leg",deck,(x,4.095,2.124),(x,4.137,1.39),(.021,.021),(.021,.021),steel,n=5)
for z in (1.44,1.67,1.89):
    beam("Swim ladder tread",deck,(-.232,4.126,z),(.232,4.126,z),(.024,.024),(.024,.024),wood,n=5)

# Masthead, boom and standing rig. The headsail's luff follows the actual forestay.
mast_heel=V((0,-.51,2.75))
mast_head=V((0,-.26,10.85))
beam("Anodized mast",rig,mast_heel,mast_head,(.083,.086),(.041,.049),steel,n=10)
block("Mast step",rig,0,-.51,2.718,.27,.245,.082,steel)
beam("Boom",rig,(.06,-.49,3.27),(.48,2.72,3.35),(.055,.066),(.040,.05),steel,n=6)
beam("Boom vang",deck_rig,(.01,-.47,2.93),(.18,.53,3.294),
     (.023,.023),(.019,.019),steel,n=5)
forestay_tack=V((0,-4.18,2.53))
forestay_head=V((0,-.27,10.74))
beam("Forestay",rig,forestay_tack,forestay_head,(.015,.015),(.015,.015),steel,n=5)
for s in (-1,1):
    spread=(s*.68,-.22,6.41)
    beam("Spreaders",rig,(0,-.405,6.43),spread,(.040,.038),(.027,.027),steel,n=6)
    chain=(s*1.25,.18,2.27)
    beam("Lower shroud",rig,chain,(0,-.38,6.70),(.013,.013),(.013,.013),steel,n=5)
    beam("Cap shroud lower",rig,chain,spread,(.014,.014),(.014,.014),steel,n=5)
    beam("Cap shroud upper",rig,spread,mast_head,(.012,.012),(.012,.012),steel,n=5)
    block("Chain plate",rig,chain[0],chain[1],2.23,.087,.14,.119,steel)
    beam("Split backstay leg",rig,(s*.71,3.91,2.15),(0,3.40,5.0),
         (.013,.013),(.013,.013),steel,n=5)
beam("Main backstay",rig,(0,3.40,5.0),mast_head,(.013,.013),(.013,.013),steel,n=5)
oval("Anchor light head",rig,[(0,-.257,10.85,.057,.057),(0,-.257,10.955,.052,.052)],lamp,n=8)
beam("Wind indicator wand",rig,(0,-.253,10.94),(0,-.253,11.21),(.011,.011),(.011,.011),steel,n=5)
mesh("Masthead wind vane",rig,[(0,-.31,11.20),(.15,-.34,11.20),(.038,-.39,11.20)],
     [(0,1,2)],steel)


def sail(label,parent,head,tack,clew,camber,rows=9,cols=6,roach=.08):
    """Billow each horizontal cross-section. Keep the luff on the spar or stay."""
    h,t,c=map(V,(head,tack,clew))
    verts=[]
    for i in range(rows):
        v=i/rows
        luff=t.lerp(h,v)
        leech=c.lerp(h,v)
        leech.y+=roach*sin(pi*v)
        for j in range(cols+1):
            u=j/cols
            p=luff.lerp(leech,u)
            p.x+=camber*sin(pi*u)*(1-v)*(.65+.35*sin(pi*v))
            verts.append(tuple(p))
    verts.append(tuple(h))
    faces=[]
    for i in range(rows-1):
        for j in range(cols):
            k=i*(cols+1)+j
            faces.append((k,k+1,k+cols+2,k+cols+1))
    k=(rows-1)*(cols+1)
    faces.extend((k+j,k+j+1,len(verts)-1) for j in range(cols))
    ob=mesh(label,parent,verts,faces,cloth)
    ob["keep"]=True
    for border in ([i*(cols+1) for i in range(rows)]+[len(verts)-1],
                   [i*(cols+1)+cols for i in range(rows)]+[len(verts)-1],
                   list(range(cols+1))):
        for a,b in zip(border,border[1:]):
            beam("Sewn sail edge",parent,verts[a],verts[b],(.010,.010),(.010,.010),ivory,n=4)
    return verts


main_clew=(.48,2.72,3.428)
jib_clew=(.36,-.68,3.53)
sail("Cambered mainsail",main,(.068,-.217,10.48),(.088,-.44,3.39),main_clew,.36,roach=.23)
jib_head=forestay_tack.lerp(forestay_head,.947)
jib_tack=forestay_tack.lerp(forestay_head,.035)
sail("Working jib",jib,jib_head,jib_tack,jib_clew,.29,rows=8,cols=6,roach=.0)
for a,b in [((.48,2.72,3.35),(.29,2.34,2.35)),((.29,2.34,2.35),(.67,1.64,2.33)),
            (jib_clew,(1.11,1.61,2.36)),((1.11,1.61,2.36),(.91,2.1,2.25))]:
    beam("Running sheet",deck_rig,a,b,(.015,.015),(.015,.015),cord,n=5)
# Match the name panel to the open stern's transom rake and to physical texels.
_,low_y,low_z=perimeters[5][9]
_,high_y,high_z=perimeters[6][9]
rake=(high_y-low_y)/(high_z-low_z)
w=44*T
dz=6*T/hypot(1,rake)
z_center=1.87
p=[(x,low_y+(z-low_z)*rake+.003,z)
   for x,z in ((w/2,z_center-dz),(-w/2,z_center-dz),(-w/2,z_center+dz),(w/2,z_center+dz))]
plaque("sign_name",hull,p,name_mat,(0,0,44,12),(44,12))

def anchor(name,xyz,forward):
    obj=group(name,boat)
    obj.location=xyz
    x,y,z=xyz
    return {"node":name,"position_gltf_m":[x,z,-y],"forward_gltf":forward}


anchors=[anchor("helm",(.43,2.66,1.878),[0,0,1]),
         anchor("deck_board_port",(-1.34,.59,2.22),[-1,0,0]),
         anchor("deck_board_starboard",(1.34,.59,2.22),[1,0,0]),
         anchor("bow_mooring",(0,-4.17,2.43),[0,0,1]),
         anchor("stern_mooring",(0,3.96,2.13),[0,0,-1]),
         anchor("waterline",(0,0,waterline),[0,0,1])]
for parent in (hull,deck,cabin,rig,main,jib,deck_rig,rudder):
    batch(parent)
rudder.location=(0,3.20,1.44)
for child in rudder.children:
    child.location-=rudder.location
bpy.context.view_layer.update()
visible=[ob for ob in boat.children_recursive if ob.type=="MESH"]
world_points=[ob.matrix_world@v.co for ob in visible for v in ob.data.vertices]
dims=[round(max(v[i] for v in world_points)-min(v[i] for v in world_points),4) for i in range(3)]
triangles=sum(len(f.vertices)-2 for ob in visible for f in ob.data.polygons)
mats=sorted({m.name for ob in visible for m in ob.data.materials})
assert triangles<=8500 and len(mats)<=12,(triangles,mats)
info={"name":"Alder","type":"8.6 m masthead cruising sloop","era":"Late 1970s onward",
      "length_overall_m":8.6,"beam_m":round(2*1.46*1.008,4),"draft_m":waterline,
      "bounding_size_gltf_m":[dims[0],dims[2],dims[1]],"waterline_above_keel_m":waterline,
      "triangles_visible":triangles,"materials":mats,"anchors":anchors,
      "axes":"glTF metres, Y up; bow +Z; origin at keel elevation, amidships",
      "rig_state":"Both sails deployed in one fixed trim. Individual nodes for mainsail, jib, mast and rudder assembly.",
      "collision":"Use hull and deck geometry to derive watercraft and standing collision. No vehicle runtime authored.",
      "texel_size_m":T,"texels_per_art_pixel":2}
(ROOT/"model-info.json").write_text(json.dumps(info,indent=2)+"\n")
export_glb(ROOT/"alder-sloop.glb")
save_source(ROOT/"alder-sloop.blend")
