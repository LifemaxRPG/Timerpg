"""Build two Rillhaven Torrel generations with physical UVs and moving parts."""
from bisect import bisect_right
from math import cos, pi, radians, sin, sqrt
from pathlib import Path
import json
import sys

import bpy

sys.path.insert(0,str(Path(__file__).resolve().parent))
from mesh_tools import bl, box, loft, material, node, rod, surface
from revision_common import batch, emitter, export_glb, save_source

ROOT=Path(__file__).resolve().parent
T=.0388
BODY=(
    (-2.01,.743,.642,.692),(-1.88,.814,.701,.750),(-1.55,.840,.754,.795),
    (-1.16,.846,.769,.803),(-.915,.811,.757,.783),(-.56,.807,.731,.758),
    (.08,.798,.699,.730),(.59,.790,.680,.714),(.75,.798,.681,.711),
    (1.15,.805,.665,.691),(1.55,.778,.625,.657),(1.86,.742,.576,.615),
    (2.00,.682,.551,.575),
)
DESIGNS={
    "1968":{"name":"Rillhaven Torrel","years":[1968,1976],"wheelbase":2.31,
            "track":[1.388,1.430],"radii":[.303,.303],"tyres":[.165,.183],
            "mass":976,"bias":.43,"speed":188,"sprint":8.9,"gear":3.91,"first":"#ce5e31"},
    "1988":{"name":"Rillhaven Torrel RS","years":[1988,1996],"wheelbase":2.36,
            "track":[1.478,1.530],"radii":[.311,.323],"tyres":[.205,.235],
            "mass":1088,"bias":.42,"speed":224,"sprint":6.4,"gear":3.82,"first":"#d5a843"},
}
PALETTES={"red":"#b84b47","yellow":"#d5a843","racing_green":"#417458",
          "white":"#e3dbbc","orange":"#ce5e31","slate_metallic":"#627a87"}


def build(year,lod):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    d=DESIGNS[year]
    early=year=="1968"
    near,far=lod=="near",lod=="far"
    slug=f"torrel-{year}"
    root=node(slug)
    shell=node("body",root)
    paint=material("paint_primary",slug+"-primary")
    accent=material("paint_secondary",slug+"-secondary")
    trim=material("hardware_and_trim",slug+"-finish")
    glass=material("glass_vehicle",slug+"-glass",True)
    lights=material("glow_lamps",slug+"-lamps")
    emitter(lights,slug+"-emission",.42)
    front_track,rear_track=d["track"]
    front_r,rear_r=d["radii"]
    front_w,rear_w=d["tyres"]
    axle=d["wheelbase"]/2
    top=1.133 if early else 1.157
    hood_pt=(0,.714,.684)
    deck_pt=(0,.783,-.936)
    roof_pt=(0,top-.046,-.228)
    pivots={}
    poses={}
    lamps={}

    def part(name,parent,p,axis=None):
        ob=node(name,parent,p)
        actual=bl(p)
        # All authored pivots have an identity rotation. Sum the local offsets.
        ancestor=parent
        while ancestor:
            actual+=ancestor.location
            ancestor=ancestor.parent
        pivots[name]={"parent":parent.name,
                      "center_gltf_m":[round(v,6) for v in (actual.x,actual.z,-actual.y)]}
        if axis:
            pivots[name]["axis_local_gltf"]=list(axis)
            ob["axis_local_gltf"]=list(axis)
        return ob

    def local(pt,p):
        return tuple(x-y for x,y in zip(pt,p))

    def panel(name,parent,coords,mat,zone=None):
        return surface(name,parent,list(coords),[tuple(range(len(coords)))],mat,zone)

    door_z=(.573,-.609)
    hood=part("hood",root,hood_pt,(1,0,0))
    deck=part("trunk",root,deck_pt,(1,0,0))
    roof=part("roof_panel",root,roof_pt)
    roof["removable"]=True
    poses.update({"hood":-43,"trunk":46})
    for side,suffix in ((1,"l"),(-1,"r")):
        for fore,track,rad,width,z in (("f",front_track,front_r,front_w,axle),
                                       ("r",rear_track,rear_r,rear_w,-axle)):
            center=(side*track/2,rad,z)
            parent=root
            if fore=="f":
                parent=part("steer_f"+suffix,root,center,(0,1,0))
            wh=part("wheel_"+fore+suffix,parent,(0,0,0) if fore=="f" else center,(1,0,0))
            wh["tyre_width_m"]=width
            wh["tyre_radius_m"]=rad
            n=12 if near else 8 if not far else 6
            half=width/2
            if near:
                profile=[(-half,.84,"rubber"),(-half*.75,1,"rubber"),(half*.72,1,"rubber"),
                         (half,.86,"rubber"),(half+.002,.682,"metal"),
                         (half-(.030 if early else .062),.565,"grille"),
                         (half-(.031 if early else .065),.255,"wheel")]
            elif not far:
                profile=[(-half,1,"rubber"),(half*.87,1,"rubber"),(half+.003,.65,"wheel")]
            else:
                profile=[(-half,1,"rubber"),(half,1,"rubber")]
            rings=[[(side*x,rad*s*cos(2*pi*i/n+pi),rad*s*sin(2*pi*i/n+pi)) for i in range(n)]
                   for x,s,zone in profile]
            faces,zones=[],[]
            for j in range(len(rings)-1):
                for i in range(n):
                    ni=(i+1)%n
                    faces.append((j*n+i,j*n+ni,(j+1)*n+ni,(j+1)*n+i))
                    zones.append(profile[j][2])
            faces.extend((tuple(reversed(range(n))),tuple(range((len(rings)-1)*n,len(rings)*n))))
            zones.extend(("rubber", "wheel" if not far else "dark"))
            if side<0:
                faces=[f[::-1] for f in faces]
            surface("Tyre and dished rim",wh,sum(rings,[]),faces,trim,zones)
            if near:
                dish=half-(.026 if early else .056)
                for i in range(4 if early else 5):
                    angle=2*pi*i/(4 if early else 5)+pi/8
                    yy,zz=cos(angle),sin(angle)
                    pts=[(side*dish,rad*yy*s+rad*zz*w,rad*zz*s-rad*yy*w)
                         for s,w in ((.146,-.062),(.525,-.091),(.578,.014),(.224,.062))]
                    panel("Forged fan spoke",wh,pts if side>0 else pts[::-1],trim,"bright")
                rod("Wheel hub",wh,(side*(dish-.008),0,0),(side*(dish+.018),0,0),
                    .050 if early else .061,trim,"metal",8)

    for side,suffix in ((1,"l"),(-1,"r")):
        p=(side*.720,.533,door_z[0])
        door=part("door_"+suffix,root,p,(0,1,0))
        poses[door.name]=-62*side
    steering=part("steering_wheel",root,(.334,.797,.391),(0,.34,.940425))
    part("seat_driver",root,(.347,.351,-.235))
    part("seat_pillion",root,(-.347,.351,-.235))
    # seat_passenger is a descriptive alias for tools that distinguish car seats.
    part("seat_passenger",root,(-.347,.351,-.235))
    part("exit_left",root,(1.13,0,.03))
    part("exit_right",root,(-1.13,0,.03))
    emblem=part("badge_plate",root,(0,.600,1.878))

    def lens(role,side,z,y,x,w,h,forward,which=""):
        suffix="l" if side>0 else "r"
        name="light_"+role+which+"_"+suffix
        p=(side*x,y,z)
        ob=part(name,root,p)
        lamps[name]={"role":role,"direction_gltf":[0,0,forward],"zone":role}
        if not far:
            q=[(-w/2,-h/2,0),(w/2,-h/2,0),(w/2,h/2,0),(-w/2,h/2,0)]
            panel("Recessed rectangular lens",ob,q if forward>0 else q[::-1],lights,role)

    for s in (-1,1):
        lens("head",s,2.006,.515 if early else .534,.504 if early else .534,.287 if early else .31,.121,1)
        lens("turn",s,2.008,.423 if early else .443,.57 if early else .606,.138,.057,1,"_front")
        lens("tail",s,-2.015,.634,.601 if early else .656,.193,.081,-1)
        lens("brake",s,-2.017,.634,.422 if early else .468,.141,.081,-1)
        lens("turn",s,-2.016,.634,.257 if early else .287,.138,.081,-1,"_rear")
        lens("reverse",s,-2.016,.552,.376 if early else .410,.126,.064,-1)

    def body(z):
        i=min(len(BODY)-2,max(0,bisect_right([row[0] for row in BODY],z)-1))
        a,b=BODY[i:i+2]
        u=(z-a[0])/(b[0]-a[0])
        w,y,h=(a[j]+(b[j]-a[j])*u for j in (1,2,3))
        if not early:
            f=max(0,1-abs(z-axle)/.91)
            r=max(0,1-abs(z+axle)/.96)
            w+=.066+.034*f+.048*r
            y+=.019
            h+=.022
        return w,y,h

    def wing(parent):
        y=.954
        edges=[(-.778,y,-1.915),(.778,y,-1.915),(.713,y+.035,-1.712),(-.713,y+.035,-1.712)]
        if far:
            panel("Wing blade",parent,edges,accent)
        else:
            surface("Wing blade",parent,edges+[(x,k-.032,z) for x,k,z in edges],
                    [(0,1,2,3),(7,6,5,4),(1,0,4,5),(2,1,5,6),(3,2,6,7),(0,3,7,4)],accent)
            for x in (-.481,.481):
                box("Wing pedestal",parent,(x,.858,-1.798),(.065,.164,.119),trim,"dark")
                if near:
                    box("Cutoff wing endplate",parent,(x/.481*.76,.946,-1.822),(.042,.118,.225),accent)

    if far:
        loft("Distance wedge",shell,
             [[(-w*.67,.219,z),(w*.67,.219,z),(w,y,z),(-w,y,z)]
              for z in (-2.01,-1.31,1.27,2.0) for w,y,h in (body(z),)],paint)
        loft("Distance cabin",shell,
             [[(-w,y,z),(w,y,z),(w*.84,k,z),(-w*.84,k,z)]
              for z,w,y,k in ((-.958,.571,.764,1.091),(.219,.575,.705,1.091),(.66,.660,.7,.745))],glass)
        box("Distance targa",roof,(0,.015,0),(1.085,.044,.874),accent)
        if not early:
            wing(shell)
    else:
        # Center spar and tray are narrower than the tyres' inner shoulders.
        loft("Flat chassis and centre tub",shell,
             [[(-w,y,z),(w,y,z),(w,y+.13,z),(-w,y+.13,z)]
              for z,w,y in ((-1.943,.52,.184),(-1.08,.50,.154),(.98,.50,.154),(1.944,.47,.205))],
             trim,"dark")
        # Side ribbons step around true wheel apertures; doors occupy their own gap.
        holes=[(-axle,rear_r,rear_r+.051 if near else rear_r+.074),
               (axle,front_r,front_r+.054 if near else front_r+.073)]

        def arch(z):
            low=.225 if early else .215
            for center,r,cut in holes:
                r2=cut*cut-(z-center)**2
                if r2>0:
                    low=max(low,r+sqrt(r2))
            return low

        stations={row[0] for row in BODY}|set(door_z)|{.684,-.936,-axle,axle}
        for pos,r,cut in holes:
            # Include both ends of the arch, plus small cheeks below the springline.
            stations.update(pos+cut*cos(i*pi/(10 if near else 4))
                            for i in range((10 if near else 4)+1))
            stations.update((pos-cut-.025,pos+cut+.025))
        stations=sorted(stations)
        if not near:
            stations=[z for z in stations if not any(abs(z-a)<.015 for a in door_z) or z in door_z]

        def thickness_mesh(parent,side,chain,zp=None):
            result=[]
            for z,w,lo,hi in chain:
                x0=w-.039 if near else w-.024
                result.extend(((side*x0,lo,z),(side*w,lo+.026,z),(side*(w-.008),hi,z),
                               (side*(w-.041),hi+.021,z)))
            if zp:
                result=[local(p,zp) for p in result]
            fs=[]
            for i in range(len(chain)-1):
                for j in ((0,1,2) if near else (1,2)):
                    k=4*i+j
                    fs.append((k,k+1,k+5,k+4))
            if side<0:
                fs=[f[::-1] for f in fs]
            return result,fs

        for side,suffix in ((1,"l"),(-1,"r")):
            door=bpy.data.objects["door_"+suffix]
            p=tuple(pivots[door.name]["center_gltf_m"])
            for part_start,part_end in ((-2.01,door_z[1]),(door_z[0],2.0)):
                chain=[]
                for z in stations:
                    if part_start<=z<=part_end:
                        w,y,h=body(z)
                        chain.append((z,w,min(arch(z),y-.056),y))
                vs,fs=thickness_mesh(shell,side,chain)
                surface("Shaped outer fender and rolled lip",shell,vs,fs,paint)
            door_sections=[z for z in stations if door_z[1]<z<door_z[0]]
            chain=[]
            for z in [door_z[1]+.009]+door_sections+[door_z[0]-.010]:
                w,y,h=body(z)
                chain.append((z,w-.005,.312,y-.011))
            vs,fs=thickness_mesh(door,side,chain,p)
            surface("Door outer wedge",door,vs,fs,paint)
            if near:
                box("Door inner card",door,local((side*(body(.0)[0]-.077),.482,-.019),p),
                    (.075,.303,1.142),trim,"panel")
                box("Door belt strip",door,local((side*(body(-.04)[0]+.001),.649 if early else .673,-.016),p),
                    (.015,.035,1.126),trim,"metal" if early else "dark")
                box("Flush door release",door,local((side*(body(-.424)[0]+.004),.686 if early else .710,-.438),p),
                    (.026,.032,.135),trim,"bright" if early else "metal")
            x=side
            pane=[(.725*x,.717 if early else .740,.553),(.532*x,top-.065,.185),
                  (.559*x,top-.065,-.599),(.749*x,.741 if early else .764,-.597)]
            panel("Side door glazing",door,[local(q,p) for q in (pane if side>0 else pane[::-1])],glass)
            if near:
                for a,b in ((pane[0],pane[1]),(pane[-1],pane[0])):
                    rod("Door sash brightwork",door,local(a,p),local(b,p),.012,trim,"metal" if early else "dark",4)
                stem=(side*.808,.803,.353)
                rod("Mirror stalk",door,local((side*.739,.748,.362),p),local(stem,p),.024,trim,"dark",5)
                box("Rear view mirror",door,local((side*(.873 if early else .918),.815,.352),p),
                    (.148,.071,.069),trim,"metal" if early else "dark")
                for z in (-.402,.380):
                    rod("Roof seal along sill",shell,(side*.746,.705,z-.10),(side*.740,.702,z+.10),
                        .017,trim,"rubber",4)

        for side in (-1,1):
            # A fixed sill stays in place when the door hinges forward.
            side_x=side*(.734 if early else .804)
            box("Load-bearing sill",shell,(side_x,.257,-.024),(.117,.118,1.28),accent)
            if near:
                box("Thin rocker brightwork",shell,(side_x+side*.069,.266,-.028),
                    (.014,.026,1.266),trim,"bright" if early else "edge")
        # Corner bands are continuous from the wingtips to the moving deck seams.
        for side in (-1,1):
            for start,end in ((-2.01,-.936),(.684,2.0)):
                sts=[z for z in stations if start<=z<=end]
                quad=[]
                for a,b in zip(sts,sts[1:]):
                    p,q=body(a),body(b)
                    faces=[(side*(p[0]-.041),p[1]+.021,a),(side*(q[0]-.041),q[1]+.021,b),
                           (side*q[0]*.703,q[2],b),(side*p[0]*.703,p[2],a)]
                    quad.extend(faces if side>0 else faces[::-1])
                surface("Fixed shoulder flange",shell,quad,
                        [tuple(range(i,i+4)) for i in range(0,len(quad),4)],paint)

        def lid(owner,p,start,end):
            chain=[start]+[z for z,w,y,k in BODY if start+.045<z<end-.045][::1 if near else 2]+[end]
            rings=[]
            for z in chain:
                w,y,k=body(z)
                x=w*.703-.007
                rings.append([(q,j,z) for q,j in ((-x,k-.036),(x,k-.036),(x,k),(x*.72,k+.023),
                                                        (-x*.72,k+.023),(-x,k))])
            loft("Lift-off bonnet" if owner==hood else "Mid-engine deck lid",owner,
                 [[local(q,p) for q in ring] for ring in rings],paint)

        lid(hood,hood_pt,.691,1.986)
        lid(deck,deck_pt,-2.006,-.943)
        for z,front_sign in ((2.001,1),(-2.010,-1)):
            w,y,h=body(2.0 if front_sign>0 else -2.01)
            ring=[(-w*.917,.275,z), (w*.917,.275,z),(w,y-.014,z),(w*.703,h,z),
                  (-w*.703,h,z),(-w,y-.014,z)]
            panel("Nose cut-off" if front_sign>0 else "Kamm tail cut-off",shell,
                  ring if front_sign>0 else ring[::-1],paint)
            finish="bright" if early else "dark"
            box("Chrome blade bumper" if early else "Inset impact bumper",shell,
                (0,.364 if early else .352,z+front_sign*.038),
                (2*w*.95,.067 if early else .115,.099),trim,finish)
            if near:
                for x in (-.773*w,.773*w):
                    box("Bumper end return",shell,(x,.366 if early else .361,z-front_sign*.013),
                        (.176,.072 if early else .114,.156),trim,finish)
        box("Cooling slot",shell,(0,.478,2.015),(.643 if early else .820,.087,.018),trim,"grille")
        box("Recessed rear plate",shell,(0,.530,-2.020),(.327,.101,.023),trim,"dark")
        if near:
            box("Number plate blank",shell,(0,.529,-2.036),(.219,.065,.014),trim,"bright")
            for x in (-.585,.585) if early else (-.595,.595):
                rod("Twin exhaust",shell,(x,.279,-1.917),(x,.279,-2.114),
                    .039 if early else .052,trim,"metal",8)
            for x in (-.28,.28):
                # Slots follow the liftgate, so the open cover exposes the bay.
                for z in (-1.28,-1.412,-1.544):
                    xx=x*.76
                    zz=body(z)[2]+.027
                    box("Engine cover louvre",deck,local((xx,zz,z),deck_pt),
                        (.297,.019,.077),trim,"grille")
            for side in (-1,1):
                # Recessed looking, raked mid-engine intake behind each door.
                coords=[(.492,-.843),(.458,-.677),(.624,-.657),(.691,-.875)]
                paired=[(side*(body(z)[0]+.012),max(y,arch(z)+.042),z) for y,z in coords]
                panel("Raked side intake",shell,paired if side>0 else paired[::-1],trim,"mesh")
                for a,b in ((paired[1],paired[2]),(paired[2],paired[3])):
                    rod("Air inlet rim",shell,a,b,.015,trim,"metal" if early else "dark",4)
        if not early:
            wing(shell)
        panel("Torrel badge insert",emblem,
              [(-.043,-.011,.001),(.043,-.011,.001),(.043,.011,-.042),(-.043,.011,-.042)],trim,"badge")

        # The windshield stays with the shell. An exposed targa bow frames the rear glass.
        screen=[(-.704,.714,.661),(.704,.714,.661),(.556,top-.064,.191),(-.556,top-.064,.191)]
        panel("Raked windshield",shell,screen,glass)
        bars=[(screen[i],screen[(i+1)%4]) for i in range(4)]
        for a,b in bars:
            rod("Windscreen surround",shell,a,b,.025,trim,"bright" if early else "dark",4)
        rear_y=.777 if early else .798
        rear=[(-.60,rear_y,-.913),(-.546,top-.078,-.692),(.546,top-.078,-.692),(.60,rear_y,-.913)]
        panel("Upright rear light",shell,rear[::-1],glass)
        for side in (-1,1):
            points=[(side*x,y,z) for x,y,z in
                     ((.542,top-.041,-.641),(.615,top-.083,-.737),(.734,rear_y,-.928),
                      (.741,rear_y,-.641),(.661,top-.090,-.640),(.547,top-.055,-.743))]
            surface("Pinned targa buttress",shell,points,
                    [(0,1,2,3),(5,4,3,2),(0,4,5,1),(0,3,4),(1,5,2)] if side>0 else
                    [(3,2,1,0),(2,3,4,5),(1,5,4,0),(4,3,0),(2,5,1)],accent)
        box("Fixed transverse targa beam",shell,(0,top-.069,-.682),(1.163,.068,.153),accent)
        loft("Removable targa shell",roof,
             [[local((x,y,z),roof_pt) for x,y in
               ((-.552,top-.057),(.552,top-.057),(.542,top-.017),(.431,top),
                (-.431,top),(-.542,top-.017))]
              for z in (-.621,.193)],accent)
        if near:
            for x in (-.44,.44):
                box("Under-roof latch",roof,(x,-.021,.362),(.081,.027,.076),trim,"metal")

        # Distinct cabin, bonnet compartment and mid-engine bay.
        box("Cabin footwell",shell,(0,.315,.052),(1.344,.078,1.624),trim,"dark")
        box("Sealed firewall ahead of engine",shell,(0,.515,-.832),(1.386,.392,.083),trim,"panel")
        box("Front luggage bucket",shell,(0,.414,1.114),(.918,.186,.927),trim,"fabric")
        box("Mid-engine bay tray",shell,(0,.327,-1.405),(1.257,.126,.957),trim,"dark")
        box("Engine block behind driver",shell,(0,.542,-1.303),(.752,.274,.597),trim,"metal")
        if near:
            for x in (-.235,.235):
                box("Engine cam cover",shell,(x,.68,-1.338),(.214,.093,.502),trim,"dark")
                for z in (-1.14,-1.31,-1.48):
                    rod("Plug lead",shell,(x,.731,z),(x*.23,.688,z-.051),.021,trim,"loom",5)
            for x in (-.37,.37):
                box("Strut dome",shell,(x,.582,1.267),(.225,.179,.236),trim,"metal")
        for x in (-.348,.348):
            loft("Separate low bucket seat",shell,
                 [[(x-w,y,z),(x+w,y,z),(x+w,y+.098,z),(x-w,y+.098,z)]
                  for z,w,y in ((-.558,.218,.354),(-.19,.204,.357),(.049,.177,.396))],trim,"fabric")
            loft("Raked seat back",shell,
                 [[(x-w,y-.029,z),(x+w,y-.029,z),(x+w,y+.057,z),(x-w,y+.057,z)]
                  for z,w,y in ((-.671,.146,.803+.025*(not early)),(-.590,.205,.647),(-.489,.211,.414))],
                 trim,"fabric")
        box("Centre tunnel",shell,(0,.434,-.003),(.161,.174,.928),trim,"panel")
        box("Glare-shield dashboard",shell,(0,.691,.513),(1.373,.144,.208),trim,"panel")
        panel("Driver-centred instrument strip",shell,
              [(0.105,.649,.401),(.571,.649,.401),(.571,.785,.401),(.105,.785,.401)],trim,"dash")
        rod("Gear selector",shell,(0,.516,.137),(0,.682,.106),.023,trim,"metal",5)
        if near:
            box("Gear knob",shell,(0,.684,.104),(.061,.054,.058),trim,"wood" if early else "dark")
            box("Pedal box",shell,(.330,.414,.548),(.228,.132,.069),trim,"grille")
            for x in (.271,.383):
                box("Pedal pad",shell,(x,.437,.491),(.049,.093,.029),trim,"metal")
        if near:
            # Ring geometry is pitched to the documented steering-wheel axis.
            spokes=12
            rad=.148 if early else .143
            points=[]
            for a in range(spokes):
                t=2*pi*a/spokes
                for delta,depth in ((-.013,-.017),(.013,-.010),(.013,.012),(-.013,.012)):
                    y=cos(t)*(rad+delta)
                    points.append((sin(t)*(rad+delta),.940425*y+.34*depth,-.34*y+.940425*depth))
            fs=[(4*i+j,4*((i+1)%spokes)+j,4*((i+1)%spokes)+(j+1)%4,4*i+(j+1)%4)
                for i in range(spokes) for j in range(4)]
            surface("Wood rim" if early else "Padded steering rim",steering,points,fs,trim,"wood" if early else "dark")
            for x in (-.106,.106):
                rod("Steering spoke",steering,(0,0,0),(x,-.039,.018),.021,trim,"metal",4)
            box("Steering hub pad",steering,(0,0,-.006),(.124,.080,.044),trim,"panel")

    for parent in [o for o in bpy.data.objects if o.type=="EMPTY"]:
        if near and parent in (hood,roof,deck):
            continue
        batch(parent)
    bpy.context.view_layer.update()
    geom=[o for o in bpy.data.objects if o.type=="MESH"]
    bounds=[[],[],[]]
    for obj in geom:
        for v in obj.data.vertices:
            world=obj.matrix_world@v.co
            for dest,value in zip(bounds,(world.x,world.z,-world.y)):
                dest.append(value)
    file=slug+("" if near else "-"+lod)
    export_glb(ROOT/(file+".glb"))
    if near:
        save_source(ROOT/(file+".blend"))
    return {"file":file+".glb","lod":lod,"triangles":sum(len(p.vertices)-2 for o in geom for p in o.data.polygons),
            "bounds_gltf_m":[[round(min(v),7),round(max(v),7)] for v in bounds],
            "materials":sorted({mat.name for o in geom for mat in o.data.materials}),
            "nodes":pivots,"door_preview_degrees":{k:v for k,v in poses.items() if k.startswith("door")},
            "cargo_preview_degrees":{k:v for k,v in poses.items() if k in ("hood","trunk")},
            "lamps":lamps}


def metadata(era,d,assets):
    chips=list(PALETTES.values())
    chips.remove(d["first"])
    wheel={axle:{"track_m":d["track"][i],"radius_m":d["radii"][i],"width_m":d["tyres"][i]}
           for i,axle in enumerate(("front","rear"))}
    wheel_positions=assets[0]["nodes"]
    return {
        "key":f"torrel-{era}","name":d["name"],"years":d["years"],
        "estimated_mass_kg":d["mass"],"estimated_top_speed_kmh":d["speed"],
        "wheelbase_m":d["wheelbase"],"track_m":d["track"][0],"wheel_radius_m":d["radii"][0],
        "mid_engine":True,"driven_axle":"rear",
        "weight_distribution":{"front":d["bias"],"rear":round(1-d["bias"],2)},
        "wheel_axles":wheel,"estimated_0_100_kmh_s":d["sprint"],"suggested_final_drive":d["gear"],
        "suggested_top_gear_ratio":.87 if era=="1968" else .80,
        "suggested_power_kw":92 if era=="1968" else 145,
        "suggested_lod_m":{"mid":38,"far":95},
        "roof_panel":{"node":"roof_panel","operation":"remove","mount_gltf_m":wheel_positions["roof_panel"]["center_gltf_m"]},
        "collision_boxes":[{"center_gltf_m":[0,.465,0],
                            "size_gltf_m":[1.41 if era=="1968" else 1.58,.60,3.88]},
                           {"center_gltf_m":[0,.902,-.225],"size_gltf_m":[1.09,.453,1.239]}],
        "paint":{"preset_body_colours":[d["first"],*chips],"named_presets":PALETTES,
                 "stock_secondary":"#353b3a" if era=="1968" else "#323c45",
                 "masks":{k:f"textures/torrel-{era}-{k}-mask.png" for k in ("primary","secondary")}},
        "lods":assets,
    }


if __name__=="__main__":
    vehicles=[]
    for era,d in DESIGNS.items():
        assets=[build(era,lod) for lod in ("near","mid","far")]
        vehicles.append(metadata(era,d,assets))
        print(era,[m["triangles"] for m in assets],flush=True)
    meta={"task":"C017","format":"glTF metres, +Y up, +Z forward, +X vehicle left",
          "origin":"Ground below the axle midpoint","texel_m":T,"vehicles":vehicles}
    (ROOT/"model-info.json").write_text(json.dumps(meta,indent=2)+"\n")
