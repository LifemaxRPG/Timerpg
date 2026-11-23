"""Build three Birchbend motel eras, at one world origin."""
from math import cos, pi, sin
from pathlib import Path
import json
import sys

import bpy

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from sculpture_tools import beam, box, group, material, mesh, plaque
from revision_common import batch, emitter, export_glb, save_source

T=.0388
P=32*T
F=.048
STOREY=3.05
ART=json.loads((ROOT/"textures/regions.json").read_text())
AGES={"1935":[1935,1954],"1955":[1955,1984],"1985":[1985,None]}


def coord(p):
    x,y,z=p
    return [round(x,5),round(z,5),round(-y,5)]


def build(era,lod):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    old,middle=era=="1935",era=="1955"
    modern=era=="1985"
    near,far=lod=="near",lod=="far"
    root=group("Birchbend Motor Lodge")
    shell,interior,lot,cold=[group(n,root) for n in ("shell","interior","lot","collision")]
    paint=material("wall_siding",{"1935":"sage","1955":"coral","1985":"beige"}[era])
    roof=material("roof_finish","shingles" if old else "awning")
    floor=material("floor_concourse","wood" if old else "paving")
    asphalt=material("ground_forecourt","gravel" if old else "asphalt")
    cream=material("detail_trim","cream")
    iron=material("detail_fittings","dark_sage" if old else "teal")
    glass=material("glass_motel","glass")
    emitter(glass,"glass-mask",.21)
    sign=material("sign_motel","signs")
    emitter(sign,"sign-mask",.62)
    if middle:
        water=material("detail_water","water")
        vacancy=material("glow_vacancy","signs")
        no=material("glow_no","signs")
        emitter(vacancy,"sign-mask",.78)
        emitter(no,"sign-mask",.001)
        arrow=material("glow_arrow","arrow",.84)
    hidden=bpy.data.materials.new("collision_invisible")
    hidden.use_nodes=True
    hidden.surface_render_method="BLENDED"
    hidden.node_tree.nodes["Principled BSDF"].inputs["Alpha"].default_value=0
    boxes,doors,anchors,rooms=[],{},{},[]
    features={}
    pending_colliders={}

    def slab(label,parent,b,mat):
        x0,x1,y0,y1,z0,z1=b
        return box(label,parent,((x0+x1)/2,(y0+y1)/2,(z0+z1)/2),(x1-x0,y1-y0,z1-z0),mat)

    def collider(label,b,group_name="building",attachment=""):
        if not near:
            return
        key=(label,group_name,attachment,tuple(round(v,5) for v in b[:4]))
        pending_colliders.setdefault(key,[]).append((b[4],b[5]))

    def flush_colliders():
        for (name,group_name,attachment,footprint),levels in pending_colliders.items():
            segments=[]
            for low,high in sorted(levels):
                # A solid spandrel or the corridor structure joins like walls vertically.
                if segments and low-segments[-1][1]<=.36:
                    segments[-1]=(segments[-1][0],max(segments[-1][1],high))
                else:
                    segments.append((low,high))
            x0,x1,y0,y1=footprint
            for z0,z1 in segments:
                ob=slab(f"collision_{len(boxes):03d}",cold,(*footprint,z0,z1),iron)
                ob.data.materials.clear()
                ob.data.materials.append(hidden)
                ob["collision_only"]=True
                boxes.append({"name":name,"group":group_name,"attachment":attachment,
                              "center_gltf_m":coord(((x0+x1)/2,(y0+y1)/2,(z0+z1)/2)),
                              "size_gltf_m":[round(v,5) for v in (x1-x0,z1-z0,y1-y0)]})

    def marker(name,at,parent=root,yaw=0):
        ob=group(name,parent)
        ob.location=at
        ob.rotation_euler.z=yaw
        return ob

    def door(name,at,yaw=0):
        marker(name,at,yaw=yaw)
        doors[name]={"position_gltf_m":coord(at),
                     "facing_gltf":[round(sin(yaw),5),0,round(cos(yaw),5)],"external":True}

    def anchor(name,at):
        marker(name,at)
        anchors[name]=coord(at)

    def ident(u,v,z):
        return u,v,z

    def main(u,v,z):
        return u-11.7,17+v,z

    def wing(u,v,z):
        return 11.7+v,17-u,z

    def bounds(transform,b):
        a,c,d,e,f,h=b
        left=transform(a,d,f)
        right=transform(c,e,h)
        return tuple(l for x,y in zip(left,right) for l in (min(x,y),max(x,y)))

    def solid(label,parent,b,mat,t=ident,collision=False,class_="building",attachment=""):
        mapped=bounds(t,b)
        if collision:
            collider(label,mapped,class_,attachment)
        return slab(label,parent,mapped,mat)

    def face(u0,u1,v,z0,z1,t,flip=False):
        vs=[t(u0,v,z0),t(u1,v,z0),t(u1,v,z1),t(u0,v,z1)]
        return [vs[i] for i in (1,0,3,2)] if flip else vs

    def graphic(label,key,u,v,z,t=ident,parent=shell,mat=None,flip=False,keep=False):
        r=ART["rects"][key]
        w,h=r[2]*T,r[3]*T
        ob=plaque(label,parent,face(u-w/2,u+w/2,v,z,z+h,t,flip),mat or sign,r,(512,256))
        if keep:
            ob["keep"]=True
        return ob

    def wall(label,t,u0,u1,v0,v1,low,high,holes=(),material_=None):
        """Build the two wall skins and depth around each actual aperture."""
        verts,polys=[],[]
        assert u1>u0 and v1>v0 and high>low
        def quad(points):
            i=len(verts)
            verts.extend(t(*p) for p in points)
            polys.append(tuple(range(i,i+4)))

        def sides(a,b,f,h):
            if b-a<.00001 or h-f<.00001:
                return
            quad(((a,v0,f),(b,v0,f),(b,v0,h),(a,v0,h)))
            quad(((b,v1,f),(a,v1,f),(a,v1,h),(b,v1,h)))

        def across(a,b,z,up):
            pts=((a,v0,z),(b,v0,z),(b,v1,z),(a,v1,z))
            quad(pts if up else pts[::-1])

        def edge(a,p,q,left):
            pts=((a,v0,p),(a,v1,p),(a,v1,q),(a,v0,q))
            quad(pts if left else pts[::-1])

        def fixed(a,b):
            if b>a:
                collider(label,bounds(t,(a,b,v0,v1,0 if low<=F else low,high)))

        prev=walk=u0
        for a,b,bottom,top,kind in sorted(holes):
            assert prev<=a<b<=u1 and low<=bottom<top<=high,(label,a,b)
            sides(prev,a,low,high)
            sides(a,b,low,bottom)
            sides(a,b,top,high)
            edge(a,bottom,top,True)
            edge(b,bottom,top,False)
            across(a,b,top,False)
            if kind=="door":
                fixed(walk,a)
                walk=b
            else:
                across(a,b,bottom,True)
                plaque(label+" sash",shell,face(a,b,(v0+v1)/2,bottom,top,t),glass,
                       (0,0,(b-a)/T,(top-bottom)/T),(32,32))
            prev=b
        sides(prev,u1,low,high)
        across(u0,u1,high,True)
        edge(u0,low,high,False)
        edge(u1,low,high,True)
        fixed(walk,u1)
        mesh(label,shell,verts,polys,material_ or paint)

    def ledge(label,t,u0,u1,y0,y1,a,b,parent=shell):
        top=[t(u0,y0,a),t(u1,y0,a),t(u1,y1,b),t(u0,y1,b)]
        bottom=[(x,y,z-.135) for x,y,z in top]
        mesh(label,parent,top+bottom,
             [(0,1,2,3),(7,6,5,4),(0,4,5,1),(1,5,6,2),(2,6,7,3),(3,7,4,0)],roof)

    def gable(label,t,start,end,depth,edge,ridge):
        pts=[t(u,v,z) for u in (start,end) for v,z in ((0,edge),(depth,edge),(depth/2,ridge))]
        mesh(label,shell,pts,[(2,1,0),(3,4,5),(0,1,4,3),(1,2,5,4),(2,0,3,5)],paint)
        ledge("Near shingled pitch",t,start-.16,end+.15,-.16,depth/2,edge+.025,ridge+.16)
        ledge("Rear shingled pitch",t,start-.16,end+.15,depth/2,depth+.16,ridge+.16,edge+.025)

    slab("Forecourt",lot,(-16.8,19,-4.8,26.6,0,.026),asphalt)
    slab("Road-facing departure apron",lot,(-7.2,8.2,-4.8,-.03,.027,.039),floor)
    slab("Office floor",interior,(-4.28,4.28,0,6.0,0,F),floor)
    office_front=[(-3.55,-2.00,.82,2.245,"window"),(-.79,.79,F,2.32,"door"),
                  (2.00,3.55,.82,2.245,"window")]
    wall("Roadside office facade",ident,-4.28,4.28,0,.25,F,2.96,office_front)
    wall("Office back",ident,-4.28,4.28,5.76,6.0,F,2.96,((2.24,3.42,F,2.21,"door"),))
    for x in (-4.28,4.04):
        def flank(u,v,z,x=x):
            return x+v,5.76-u,z
        wall("Office return",flank,0,5.51,0,.24,F,2.96)
    if old:
        gable("Gabled motor court office",ident,-4.28,4.28,6.0,2.96,4.22)
        ledge("Entry timber marquee",ident,-2.28,2.28,-1.47,.08,2.54,2.79)
    elif middle:
        ledge("Butterfly office left roof",lambda u,v,z:(v,6-u,z),0,6.0,-4.51,0,4.08,3.47)
        ledge("Butterfly office right roof",lambda u,v,z:(v,6-u,z),0,6.0,0,4.51,3.47,4.08)
        slab("Solid office ceiling",shell,(-4.27,4.27,0,6,2.93,3.06),roof)
        # Infill the roof bearings and the two seams above the original wall plate.
        for y in (.009,5.991):
            for a,b in ((-4.28,0),(0,4.28)):
                pts=[(a,y,2.96),(b,y,2.96),(b,y,3.335+.61*abs(b)/4.51),
                     (a,y,3.335+.61*abs(a)/4.51)]
                mesh("Butterfly roof spandrel",shell,pts,[(0,1,2,3) if y<3 else (3,2,1,0)],paint)
        for x in (-4.28,4.04):
            slab("Butterfly roof wall plate",shell,(x,x+.24,.01,5.99,2.954,3.927),paint)
        ledge("Flat office approach canopy",ident,-2.98,2.98,-1.96,.10,2.69,2.96)
    else:
        slab("Office ceiling",shell,(-4.43,4.43,-.12,6.12,2.87,3.11),roof)
        slab("Thick plastic entrance brow",shell,(-3.57,3.57,-1.50,.11,2.63,3.19),iron)
    if old:
        slab("Closed attic ceiling",shell,(-4.27,4.27,0,6,2.95,3.04),floor)
    slab("Office entry walk",lot,(-4.8,4.9,-2.21,0,.033,F),floor)
    sign_y=-1.57 if modern else -1.52 if old else -2.01
    sign_z=2.60 if modern else 2.626 if old else 2.748
    slab("Office sign blade seat",shell,(-1.66,1.66,sign_y+.008,sign_y+.24,sign_z-.04,sign_z+.89),iron)
    graphic("Printed office sign","office",0,sign_y,sign_z)
    door("door_main",(0,-.15,0))
    door("door_back",(2.83,6.13,0),pi)
    if not far:
        count=group("counter",interior)
        slab("Reception counter front",count,(-2.58,1.36,3.22,4.01,F,1.08),paint)
        slab("Counter top",count,(-2.68,1.46,3.13,4.10,1.08,1.20),cream)
        collider("Reception counter",(-2.68,1.46,3.13,4.10,F,1.20),attachment="counter")
        slab("Registration window sill",interior,(-2.35,1.16,5.57,5.75,1.14,1.27),cream)
        slab("Key pigeonholes case",interior,(-2.35,.52,5.56,5.75,1.41,2.25),iron)
        graphic("Key rack label","key",-.91,5.554,1.42,parent=interior)
        if near:
            for x in (-2.03,-1.66,-1.29,-.92,-.55,-.18,.19):
                slab("Key rack slot separator",interior,(x-.017,x+.017,5.50,5.56,1.62,2.23),cream)
            slab("Desktop ledger",interior,(-1.46,-.71,3.51,3.94,1.20,1.238),floor)
        door_lamp=group("office_lamp",shell)
        slab("Office bulkhead",door_lamp,(-.14,.14,-.16,.036,2.42,2.58),iron)
    anchor("counter_clerk",(-.75,4.65,F))
    anchor("counter_guest",(-.74,2.56,F))

    room_count=7
    for idx,(t,width,n,yaw) in enumerate(((main,23.4,5,0),(wing,9.60,2,-pi/2))):
        step=width/n
        height=2.84
        depth=5.85
        if old:
            gable("Long timber row gable",t,0,width,depth,height,4.0)
        else:
            top=height+(STOREY if modern else 0)
            solid("Wide room roof membrane",shell,(-.18,width+.20,-.14,depth+.17,top-.105,top+.148),roof,t)
            for y in (-.12,depth-.03):
                solid("Room-wing fascia",shell,(-.19,width+.20,y-.04,y+.048,top-.118,top+.198),cream,t)
        if modern:
            for a,c,v0,v1 in ((0,width,0,.23),(0,width,depth-.21,depth),
                              (0,.21,.23,depth-.21),(width-.21,width,.23,depth-.21)):
                solid("Between-storey perimeter band",shell,(a,c,v0,v1,2.836,STOREY+F),cream,t)
        for level in range(2 if modern else 1):
            z=STOREY*level
            solid("Floor and foundation",interior,(0,width,0,depth,z,z+F),floor,t)
            top=z+height
            rear_holes=tuple((j*step+2.98,j*step+3.63,z+1.66,z+2.35,"window")
                             for j in range(n) if near or j%2==0)
            wall("Room-wing rear wall",t,0,width,depth-.21,depth,z+F,top,rear_holes)
            if near:
                for a,b,low,high,kind in rear_holes:
                    solid("Rear bathroom window sill",shell,(a-.065,b+.065,depth-.016,depth+.10,
                           low-.097,low-.019),cream,t)
            for edge in (0,width-.21):
                solid("End wall",shell,(edge,edge+.21,.23,depth-.21,z,z+height),paint,t,True)
            if near:
                for u in range(1,n):
                    p=u*step
                    solid("Shared room partition",interior,(p-.078,p+.078,.24,depth-.21,z+F,top-.07),paint,t,True)
            holes=[]
            for j in range(n):
                base=j*step
                x=base+1.01
                ww=base+3.46
                holes.extend(((x-.48,x+.48,z+F,z+2.16,"door"),
                              (ww-P/2,ww+P/2,z+.93,z+.93+P,"window")))
                num=(j+1 if idx==0 else j+6)+level*room_count
                rid=f"room_{num}"
                at=t(x,-.117,z)
                door(rid,at,yaw)
                anchor(rid+"_door",at)
                anchor(rid+"_bed",t(base+3.23,3.77,z+F))
                rooms.append({"node":rid,"level":level+1,"door_anchor":rid+"_door",
                              "bed_anchor":rid+"_bed","entry_clear_width_m":.96})
                if not far:
                    # Returns sit outside the clear opening and underline the doorway.
                    for q in (x-.52,x+.52):
                        solid("Guest entry door jamb",shell,(q-.035,q+.035,-.035,.057,z+F,z+2.16),cream,t)
                    solid("Stone window sill",shell,(ww-P/2-.082,ww+P/2+.082,-.11,.10,
                           z+.856,z+.937),cream,t)
                    graphic("Room number plaque","room-"+str(num),x+1.09,-.059,z+1.47,t)
            if far:
                holes=[h for h in holes if h[-1]=="window"]
            wall("Parking-facing rooms",t,0,width,0,.23,z+F,top,holes)
            if old:
                solid("Flat ceiling below attic",shell,(0,width,.20,depth-.12,top-.09,top),floor,t)
            walk=1.67 if not old else 1.38
            solid("Exterior walking corridor",shell,(0,width,-walk,0,z+.025,z+F),floor,t)
            if level==1:
                solid("Suspended deck soffit",shell,(-.03,width+.06,-walk-.01,.02,z-.156,z+.026),cream,t)
                for a in range(n):
                    c=a*step
                    solid("Upper guard rail",shell,(c+.015,c+step-.014,-walk-.029,-walk+.032,z+.99,z+1.071),iron,t)
                    collider("Upper balcony guard",bounds(t,(c+.015,c+step-.014,-walk-.035,-walk+.045,
                                                             z+F,z+1.071)),attachment="upper_corridor")
                    if not far:
                        for k in (range(1,6) if near else (2,4)):
                            u=c+step*k/6
                            solid("Balcony baluster",shell,(u-.025,u+.025,-walk-.021,-walk+.019,z+F,z+.99),iron,t)
            for i in range(n+1):
                u=.118 if i==0 else width-.118 if i==n else i*step
                if old:
                    # The deep timber canopy and bay posts mark the first era as a car court.
                    solid("Carport timber column",shell,(u-.079,u+.079,-3.64,-3.48,z+.027,z+2.55),cream,t,True)
                else:
                    if near or i in (0,n):
                        solid("Slim exterior-corridor post",shell,(u-.051,u+.051,-walk+.006,-walk+.103,z+.018,z+2.72),iron,t,True)
            end= -3.86 if old else -walk-.13
            ledge("Covered carport" if old else "Roof over exterior corridor",t,-.17,width+.15,end,.094,
                  z+2.53 if old else z+2.743,z+2.81 if old else z+2.79)

        for j in range(n):
            x=j*step+.20
            if old:
                solid("Carport parking stop",lot,(x+.85,x+2.53,-1.50,-1.32,.027,.14),cream,t)
            else:
                solid("Parking stripe",lot,(x,x+.041,-5.23,-1.91,.027,.034),cream,t)
                if near:
                    solid("Parking curb stop",lot,(x+1.0,x+2.83,-2.19,-1.99,.027,.123),cream,t)
        if not old and not far:
            for u in ((.35,width-.35) if near else (width-.35,)):
                solid("Gutter and downspout",shell,(u-.039,u+.039,depth,depth+.077,.14,
                      height+(STOREY if modern else 0)),iron,t)
    # Second floor corners share a safe walking surface; the exterior stair stays a separate model group.
    if modern:
        stairs=group("exterior_stair",shell)
        if near:
            for i in range(18):
                z=STOREY*(i+1)/18+F
                slab("Stair tread",stairs,(-14.06,-12.40,9.67+i*.318,9.67+(i+1)*.318,
                     .028 if i==0 else z-.18,z),cream)
        else:
            ledge("Distance stair silhouette",ident,-14.06,-12.40,9.67,15.394,.17,STOREY+F,stairs)
        slab("Upper stair landing",stairs,(-14.06,-11.69,15.394,17.02,STOREY-.14,STOREY+F),floor)
        for x in (-14.04,-12.43):
            beam("Stair stringer",stairs,(x,9.67,.091),(x,15.44,STOREY-.076),(.064,.063),(.064,.063),iron,4)
            beam("Hand rail",stairs,(x,9.67,.90),(x,15.44,STOREY+.93),(.035,.038),(.035,.038),iron,4)
            for i in (0,4,8,12,17):
                yy=9.72+i*.318
                zz=i*STOREY/18+F
                slab("Stair rail post",stairs,(x-.028,x+.028,yy-.027,yy+.027,zz,zz+.94),iron)
        for b in ((-14.06,-14.00,15.394,17.02,STOREY+F,4.08),
                  (-14.06,-11.69,16.96,17.02,STOREY+F,4.08)):
            slab("Landing rail",stairs,(*b[:4],b[5]-.074,b[5]),iron)
            collider("Landing guard",b,attachment="exterior_stair")
        slab("Landing roof support",stairs,(-13.91,-13.77,16.57,16.72,.026,STOREY-.15),iron)
        collider("Landing roof support",(-13.91,-13.77,16.57,16.72,.026,STOREY-.15),attachment="exterior_stair")
        features["stair"]={"node":"exterior_stair","rise_m":STOREY,"treads":18,"run_m":5.724,
                           "base_anchor_gltf":coord((-13.23,9.59,0)),
                           "landing_anchor_gltf":coord((-11.82,16.14,STOREY+F))}
    if middle or modern:
        x,y=8.22,8.02
        slab("Ice and vending alcove base",lot,(6.51,10.12,6.65,9.27,.027,F),floor)
        slab("Alcove rear screen",shell,(6.58,10.04,8.98,9.19,F,2.53),paint)
        collider("Alcove rear screen",(6.58,10.04,8.98,9.19,.029,2.53))
        ledge("Covered service alcove",ident,6.41,10.20,6.56,9.25,2.48,2.60)
        for q in (6.62,10.02):
            slab("Alcove post",shell,(q-.049,q+.049,6.73,6.82,F,2.48),iron)
            collider("Alcove post",(q-.049,q+.049,6.73,6.82,.029,2.48))
        fixtures=[("ice_machine","ice",7.51,1.37)]
        if modern:
            fixtures.append(("vending_machine","soda",9.04,1.95))
        for name,key,q,h in fixtures:
            ob=group(name,shell)
            slab("Cold machine cabinet",ob,(q-.545,q+.545,y-.36,y+.312,F,h),cream if key=="ice" else iron)
            collider(name,(q-.545,q+.545,y-.36,y+.312,F,h),attachment=name)
            graphic("Machine printed label",key,q,y-.368,.61 if key=="ice" else 1.00,parent=ob)
            if not far:
                slab("Machine access hatch",ob,(q-.37,q+.37,y-.377,y-.358,.27,.54),iron)
            anchor(name+"_use",(q,y-.79,F))
    if middle:
        pool=group("pool",lot)
        x,y=-10.10,10.64
        # A shallow kidney silhouette, with a full rim and a water surface below coping.
        ring=[]
        for i in range(24 if near else 14 if not far else 10):
            theta=i*2*pi/(24 if near else 14 if not far else 10)
            ring.append((2.94*cos(theta),1.91*sin(theta)+.79*cos(2*theta)))
        n=len(ring)
        verts=[(x+u*scale,y+v*scale,z) for scale,z in ((1.094,.127),(1,.126),(1,.073)) for u,v in ring]
        faces=[(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)]
        faces.extend((i+n,(i+1)%n+n,(i+1)%n+2*n,i+2*n) for i in range(n))
        mesh("Kidney coping and tile lip",pool,verts,faces,cream)
        mesh("Pool water",pool,[(x+u,y+v,.075) for u,v in ring],[tuple(range(n))],water)
        slab("Pool deck",pool,(-14.01,-6.27,7.43,14.02,.027,.058),floor)
        # Fence has an explicit break at the sidewalk entry.
        segments=[((-14,7.46),(-11.12,7.46)),((-9.72,7.46),(-6.29,7.46)),
                  ((-6.29,7.46),(-6.29,14.01)),((-6.29,14.01),(-14,14.01)),((-14,14.01),(-14,7.46))]
        for a,b in segments:
            dx,dy=b[0]-a[0],b[1]-a[1]
            m=round((abs(dx)+abs(dy))/.78)
            for z in (.31,1.14):
                beam("Pool fence rail",pool,(*a,z),(*b,z),(.032,.032),(.032,.032),iron,4)
            for i in range(m+1):
                xx,yy=a[0]+dx*i/m,a[1]+dy*i/m
                slab("Fence upright",pool,(xx-.025,xx+.025,yy-.023,yy+.023,.060,1.20),iron)
                if near and i and m>3:
                    tx,ty=xx-dx/m,yy-dy/m
                    beam("Fence crossed mesh",pool,(tx,ty,.29),(xx,yy,1.16),(.017,.017),(.017,.017),iron,4)
            collider("Pool fence",(min(a[0],b[0])-.032,max(a[0],b[0])+.032,
                                  min(a[1],b[1])-.032,max(a[1],b[1])+.032,.026,1.20),"lot","pool")
        graphic("Pool rules plaque","pool",-12.45,7.386,1.11,parent=pool)
        anchor("pool_gate",(-10.42,7.44,.058))
        features["pool"]={"node":"pool","entry_anchor":"pool_gate","waterline_gltf_y":.075}

    # The sign faces the road and can also be read on the way out.
    monument=group("roadside_sign",lot)
    sx,sy=-10.47,-1.10
    pole_top=5.77 if old else 7.63 if middle else 6.09
    poles=(-1.73,1.73) if old else (0,)
    for dx in poles:
        slab("Tall sign support",monument,(sx+dx-.11,sx+dx+.11,sy-.18,sy+.18,.027,pole_top),iron)
        collider("Full sign support",(sx+dx-.11,sx+dx+.11,sy-.18,sy+.18,.027,pole_top),"lot","roadside_sign")
    name_z=pole_top-1.28
    slab("Tall roadside face housing",monument,(sx-3.02,sx+3.02,sy-.25,sy+.26,name_z-.087,pole_top+.056),
         iron if middle else cream)
    for v,flip,suffix in ((sy-.258,False,"street"),(sy+.268,True,"exit")):
        graphic(f"sign_name_{suffix}","name-"+era,sx,v,name_z,parent=monument,flip=flip,keep=True)
        subtype="motor" if old else "motel" if middle else "rooms"
        graphic("Fixed sign subtitle",subtype,sx,v,name_z-1.10,parent=monument,flip=flip)
    slab("Subtitle support",monument,(sx-2.76,sx+2.76,sy-.25,sy+.26,name_z-1.16,name_z-.18),iron)
    if middle:
        # The vacancy word stays lit in both booking states. NO is its own subgroup and material.
        vz=3.69
        slab("Vacancy cabinet",monument,(sx-2.67,sx+2.67,sy-.265,sy+.267,vz-.07,vz+.95),cream)
        for n,k,dx,mat in (("sign_no","no",-1.89,no),("sign_vacancy","vacancy",.72,vacancy)):
            by_state=group(n,monument)
            graphic(n+"_street",k,sx+dx,sy-.276,vz,parent=by_state,mat=mat,keep=True)
            graphic(n+"_exit",k,sx-dx,sy+.278,vz,parent=by_state,mat=mat,flip=True,keep=True)
        pts=[(sx+3.73,sy-.26,6.33),(sx+2.83,sy-.26,7.01),(sx+2.83,sy-.26,6.65),
             (sx+1.97,sy-.26,6.65),(sx+1.97,sy-.26,6.02),(sx+2.83,sy-.26,6.02),(sx+2.83,sy-.26,5.66)]
        points=pts+[(x,sy+.27,z) for x,y,z in pts]
        mesh("Reversed sheet-metal arrow",monument,points,
             [tuple(range(6,-1,-1)),tuple(range(7,14))]+
             [(i,(i+1)%7,(i+1)%7+7,i+7) for i in range(7)],cream)
        for a,b in zip(pts,pts[1:]+pts[:1]):
            beam("Neon arrow tube backing",monument,(a[0],a[1]-.024,a[2]),(b[0],b[1]-.024,b[2]),
                 (.047,.047),(.047,.047),arrow,6)
        features["vacancy"]={"word_node":"sign_vacancy","no_node":"sign_no",
                             "word_material":"glow_vacancy","no_material":"glow_no",
                             "vacant":{"word":True,"no":False},"full":{"word":True,"no":True}}
    for x,y in ((-4.01,6.55),(4.82,7.20),(17.9,14.7)):
        slab("Rear steel refuse bin",lot,(x-.27,x+.27,y-.27,y+.27,.026,.84),iron)
        slab("Ribbed bin lid",lot,(x-.30,x+.30,y-.30,y+.30,.83,.89),cream)
        collider("Refuse bin",(x-.30,x+.30,y-.30,y+.30,.026,.89),"lot","lot")

    flush_colliders()
    scheme=ART["schemes"][era]
    info={"era":AGES[era],"height_m":pole_top+.056,"lot_bounds_gltf_m":[[-16.8,0,-26.6],[19,.049,4.8]],
          "rooms":sorted(rooms,key=lambda r:(r["level"],int(r["node"].split("_")[1]))),
          "doors":doors,"anchors":anchors,"collision_boxes":boxes,"features":features,
          "built_footprints":[[-4.28,4.28,-6.0,0],[-11.7,11.7,-22.85,-17],[11.7,17.55,-17,-7.4]],
          "signs":[{"id":"sign_name","nodes":["sign_name_street","sign_name_exit"],"texture":"signs.png",
                    "rect":ART["rects"]["name-"+era],"lines":1,"maxChars":11,"face":scheme["face"],
                    "ink":scheme["ink"],"paper":scheme["paper"],"glow":True,"glowColor":scheme["glow"]}]}
    return info


def export(era):
    info=None
    for lod in ("near","mid","far"):
        current=build(era,lod)
        stem=f"birchbend-{era}"
        if lod=="near":
            info=current
            save_source(ROOT/(stem+".blend"))
        for parent in [o for o in bpy.data.objects if o.type=="EMPTY" and o.name!="collision"]:
            batch(parent)
        path=stem+("" if lod=="near" else "-"+lod)+".glb"
        export_glb(ROOT/path)
        visible=[o for o in bpy.data.objects if o.type=="MESH" and not o.get("collision_only")]
        info.setdefault("models",{})[lod]={"file":path,
            "triangles":sum(len(p.vertices)-2 for o in visible for p in o.data.polygons),
            "materials":sorted({m.name for o in visible for m in o.data.materials})}
    return info


if __name__=="__main__":
    states={era:export(era) for era in AGES}
    info={"task":"C014","name":"Birchbend Motor Lodge","naming":"owner","meters_per_texel":T,
          "texels_per_art_pixel":2,"front_gltf":"+Z","origin":"Ground at the midpoint of the office street facade",
          "shared_swatches":{"asphalt":"asphalt_worn","gravel":"ballast","paving":"pavement","old_roof":"roof_slate",
                              "old_floor":"plank_horizontal"},
          "signs":[{**s,"id":s["id"]+"_"+era,"state":era}
                   for era,state in states.items() for s in state["signs"]],"states":states}
    (ROOT/"model-info.json").write_text(json.dumps(info,indent=2)+"\n")
