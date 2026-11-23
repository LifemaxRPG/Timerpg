"""Build three generations of the Vale Lantern filling station."""
from math import cos, pi, sin
from pathlib import Path
import json
import sys

import bpy

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from sculpture_tools import beam, box, group, material, mesh, oval, plaque
from revision_common import batch, emitter, export_glb, save_source

T=.0388
F=.048
ART=json.loads((ROOT/"textures/regions.json").read_text())
AGES={"1925":[1925,1944],"1945":[1945,1974],"1975":[1975,None]}


def coord(p):
    x,y,z=p
    return [round(x,5),round(z,5),round(-y,5)]


def build(era,lod):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    old,middle=era=="1925",era=="1945"
    modern=era=="1975"
    near,far=lod=="near",lod=="far"
    root=group("Vale Lantern Fuel")
    shell,interior,lot,cold=[group(n,root) for n in ("shell","interior","lot","collision")]
    paint=material("wall_finish",{"1925":"cottage","1945":"enamel","1975":"stucco"}[era])
    roof=material("roof_finish","shingle" if old else "membrane")
    floor=material("floor_concrete","concrete")
    asphalt=material("ground_forecourt","asphalt")
    cream=material("detail_enamel","enamel")
    iron=material("detail_petrol_blue","teal")
    orange=material("detail_burnt_amber","orange")
    metal=material("detail_hardware","metal")
    glass=material("glass_shop","glass")
    emitter(glass,"glass-mask",.18)
    sign=material("sign_station","signs")
    emitter(sign,"sign-mask",.68)
    price=material("glow_price","price-"+era)
    emitter(price,"price-"+era+"-mask",.81 if modern else .32)
    if old:
        globe=material("glass_gravity_pump","globe")
        tree=globe.node_tree
        texture=next(n for n in tree.nodes if n.type=="TEX_IMAGE")
        tree.links.new(texture.outputs["Alpha"],tree.nodes["Principled BSDF"].inputs["Alpha"])
        globe.surface_render_method="BLENDED"
    hidden=bpy.data.materials.new("collision_invisible")
    hidden.use_nodes=True
    hidden.surface_render_method="BLENDED"
    hidden.node_tree.nodes["Principled BSDF"].inputs["Alpha"].default_value=0
    boxes,doors,anchors=[],{},{}
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

    slab("Through forecourt",lot,(-16.0,15.45,-17.0,11.2,0,.034),asphalt)
    slab("Office apron",lot,(-5.15,8.24,-1.48,-.025,.035,.048),floor)
    x0= -7.65 if modern else -4.25
    x1= 7.65 if modern else 4.25
    height=3.27 if modern else 3.10 if middle else 2.82
    slab("Shop slab",interior,(x0,x1,0,7.5,0,F),floor)
    front=[(-3.55,-2.00,.94,2.31,"window"),(-.85,.85,F,2.38,"door"),
           (2.0,3.55,.94,2.31,"window")]
    if modern:
        front.extend(((-6.73,-4.77,.74,2.39,"window"),(4.77,6.73,.74,2.39,"window")))
    wall("Main fuel office frontage",ident,x0,x1,0,.23,F,height,front)
    wall("Rear wall and service opening",ident,x0,x1,7.23,7.5,F,height,
         ((-3.33,-2.32,1.23,2.31,"window"),(2.20,3.40,F,2.28,"door")))
    for x in (x0,x1-.23):
        def flank(u,v,z,x=x):
            return x+v,7.23-u,z
        wall("Complete return wall",flank,0,7.0,0,.23,F,height,((2.68,3.87,.98,2.16,"window"),))
    door("door_main",(0,-.14,0))
    door("door_back",(2.80,7.68,0),pi)
    if old:
        gable("Cottage gable",ident,x0,x1,7.5,height,4.36)
        slab("Flat ceiling below attic",shell,(x0+.20,x1-.20,.24,7.21,2.67,2.77),cream)
        slab("Tall chimney shaft",shell,(2.81,3.46,4.82,5.47,3.49,5.30),metal)
        slab("Chimney cap",shell,(2.75,3.52,4.76,5.53,5.25,5.42),cream)
        slab("Doorway header blade",shell,(-1.74,1.74,-.24,-.004,2.42,3.33),iron)
        graphic("Cottage office placard","office",0,-.252,2.45)
        ledge("Inset cottage entry roof",ident,-2.20,2.20,-1.49,-.16,2.21,2.58)
    elif middle:
        slab("Flat office and shop membrane",shell,(x0-.14,x1+.14,-.08,7.62,3.02,3.25),roof)
        slab("Continuous enamel transom",shell,(x0-.11,x1+.11,-.20,0,2.59,3.33),iron)
        slab("Orange enamel stripe",shell,(x0-.13,x1+.13,-.22,-.19,2.66,2.76),orange)
        graphic("Enamel office sign","office",0,-.227,2.52)
    else:
        slab("Market roof membrane",shell,(x0-.18,x1+.18,-.08,7.69,3.17,3.41),roof)
        slab("Blue panel transom",shell,(x0-.13,x1+.13,-.27,-.01,2.64,3.48),iron)
        slab("Contrast line above glazing",shell,(x0-.12,x1+.12,-.295,-.269,2.67,2.74),orange)
        graphic("Market signs","market",0,-.301,2.64)
        graphic("Lantern storefront logo","emblem",-5.68,-.318,2.61)
        slab("Roofline lantern badge mounting",shell,(-6.43,-4.94,-.310,-.21,2.565,4.045),iron)
        slab("Market roof cooler base",shell,(-5.39,-2.92,5.16,6.89,3.38,3.59),cream)
        slab("Market rooftop cooler",shell,(-5.24,-3.04,5.28,6.77,3.58,4.13),metal)
        for z in (3.71,3.87,4.03) if near else (3.87,):
            slab("Cooling louver",shell,(-5.23,-3.05,6.768,6.815,z,z+.042),iron)

    counter=group("cashier_counter",interior)
    slab("Fitted counter",counter,(-2.99,.78,3.50,4.31,F,1.07),paint)
    slab("Counter rim",counter,(-3.10,.88,3.43,4.38,1.07,1.19),cream)
    collider("Cashier counter",(-3.10,.88,3.43,4.38,F,1.19),attachment="cashier_counter")
    anchor("cashier_clerk",(-1.09,4.85,F))
    anchor("cashier_guest",(-1.07,2.91,F))
    features["cashier"]={"node":"cashier_counter","clerk":"cashier_clerk","guest":"cashier_guest"}
    if not far:
        slab("Counter register casing",counter,(-1.88,-1.28,3.65,4.15,1.19,1.43),metal)
        slab("Register face highlight",counter,(-1.83,-1.32,3.632,3.652,1.27,1.37),cream)
        if old:
            slab("Wide service shelf",interior,(-3.52,.59,6.78,7.215,1.05,1.13),cream)
        if modern:
            slab("Built-in refrigerator shell",interior,(-7.41,-5.07,4.78,7.12,F,2.17),iron)
            collider("Cooler built-in",(-7.41,-5.07,4.78,7.12,F,2.17))
            for x in (-7.2,-6.56,-5.92):
                slab("Cooler painted rail",interior,(x,x+.03,4.71,4.79,1.31,2.11),cream)
        for y in (1.6,5.37):
            slab("Interior light shell",interior,(-.45,.45,y-.20,y+.20,height-.21,height-.15),cream)
    if middle:
        # The garage wing has two physical apertures, with overhead leaves shown rolled up.
        bay_low,bay_high=4.25,12.60
        back=7.5
        slab("Service shop floor",interior,(bay_low,bay_high,0,7.5,0,F),floor)
        wall("Open service bay front",ident,bay_low,bay_high,0,.23,F,3.96,
             ((4.76,8.11,F,3.13,"door"),(8.73,12.10,F,3.13,"door")),cream)
        wall("Garage rear wall",ident,bay_low,bay_high,7.25,back,F,3.96,
             ((5.18,6.42,1.70,2.86,"window"),(10.12,11.34,1.70,2.86,"window")),cream)
        slab("Garage end wall",shell,(12.36,12.60,.23,7.25,F,3.96),cream)
        collider("Garage end wall",(12.36,12.60,.23,7.25,0,3.96))
        slab("Roof closes both bays",shell,(4.21,12.79,-.16,7.63,3.88,4.10),roof)
        slab("Service shop glazed fascia",shell,(4.19,12.69,-.21,-.007,3.26,4.09),iron)
        graphic("Shop service lettering","service",8.42,-.223,3.245)
        for i,u in enumerate((6.435,10.41),1):
            name=f"door_service_{i}"
            door(name,(u,-.14,0))
            ob=marker(f"service_bay_{i}",(u,.225,3.095),shell)
            ob["open_with_door"]=name
            slab("Overhead door leaf in open position",ob,(-1.59,1.59,0,1.62,0,.069),metal)
            for x in (u-1.65,u+1.65):
                slab("Guide rail",shell,(x-.036,x+.036,.198,1.91,3.08,3.137),cream)
            anchor(f"service_bay_{i}_work",(u,4.54,F))
            features[f"service_bay_{i}"]={"door":name,"leaf_node":ob.name,"work_anchor":f"service_bay_{i}_work",
                                          "opening_width_m":3.35 if i==1 else 3.37,"opening_height_m":3.13-F}
            lift=marker(f"lift_{i}",(u,3.2,F),interior)
            if not far:
                for x in (-.61,.61):
                    slab("Hydraulic lift tracks",lift,(x-.14,x+.14,0,2.79,0,.15),metal)
                slab("Hydraulic lift rear support",lift,(-.28,.28,1.81,2.23,.095,.346),iron)
                if near:
                    for x in (-1.07,1.07):
                        slab("Lift riser",lift,(x-.085,x+.085,.73,.96,0,2.17),iron)
                        collider("Lift riser",(u+x-.085,u+x+.085,3.93,4.16,F,2.218),attachment=lift.name)
        if near:
            for j in range(3):
                oval("Stacked service tyre",interior,[(11.88,6.59,.07+j*.19,.377,.377),
                      (11.88,6.59,.23+j*.19,.377,.377)],metal,10)
            collider("Tyre stack",(11.50,12.26,6.20,6.98,F,.61),attachment="service_bay_2")
        phone=group("pay_phone",shell)
        slab("Pay phone box",phone,(-4.82,-4.29,1.99,2.47,1.08,1.86),iron)
        slab("Coin slot face",phone,(-4.843,-4.82,2.08,2.30,1.50,1.76),cream)
        if near:
            beam("Pay phone receiver",phone,(-4.856,2.35,1.25),(-4.856,2.35,1.69),(.048,.049),(.048,.049),metal,6)
        anchor("pay_phone_use",(-5.30,2.23,F))

    # One forecourt standard per era. Posts are out of the pump and service lanes.
    if old:
        spec=(-4.00,4.00,-5.78,.20,2.76)
        posts=[(-3.69,-5.13),(3.69,-5.13)]
        islands=[(-1.78,-3.51),(1.78,-3.51)]
    elif middle:
        spec=(-8.40,3.80,-10.80,-2.10,3.78)
        posts=[(-7.67,-9.88),(3.07,-9.88),(-7.67,-3.08),(3.07,-3.08)]
        islands=[(-5.42,-6.47),(-.14,-6.47)]
    else:
        spec=(-10.37,10.43,-13.58,-3.40,4.45)
        posts=[(-8.96,-12.10),(8.96,-12.10),(-8.96,-4.51),(8.96,-4.51)]
        islands=[(x,y) for x in (-6.08,.07,6.18) for y in (-10.10,-6.69)]
    a,b,c,d,e=spec
    canopy=group("fuel_canopy",shell)
    if old:
        def turn(u,v,z):
            return a+v,d-u,z
        gable("Gabled cottage pump canopy",turn,0,d-c,b-a,e,e+.82)
        slab("Clear canopy soffit",canopy,(a,b,c,d,e-.06,e),cream)
    else:
        lift=.06 if middle else .36
        top=[(a,c,e+.38),(b,c,e+.38+lift),(b,d,e+.38+lift),(a,d,e+.38)]
        mesh("Folded canopy membrane",canopy,top+[(x,y,z-.28) for x,y,z in top],
             [(0,1,2,3),(7,6,5,4),(0,4,5,1),(1,5,6,2),(2,6,7,3),(3,7,4,0)],roof)
        front_face=[(a,c-.038,e-.08),(b,c-.038,e-.08),(b,c-.038,e+.38+lift),(a,c-.038,e+.38)]
        mesh("Exposed petrol blue canopy fascia",canopy,
             front_face+[(x,y+.17,z) for x,y,z in front_face],
             [(0,1,2,3),(7,6,5,4),(0,4,5,1),(1,5,6,2),(2,6,7,3),(3,7,4,0)],iron)
        slab("Burnt amber stripe",canopy,(a,b,c-.065,c-.039,e+.016,e+.101),orange)
        for x,tilt in ((a+.032,0),(b-.032,lift)):
            slab("Canopy side closure",canopy,(x-.035,x+.035,c,d,e-.057,e+.365+tilt),iron)
        for x in ((-6,1.65) if middle else (-7.21,0,7.35)):
            slab("Canopy luminaire housing",canopy,(x-.50,x+.50,(c+d)/2-.38,(c+d)/2+.38,e+.044,e+.13),cream)
    for x,y in posts:
        w=.30 if old else .39 if middle else .48
        height_=e+.07 if old else e+.162+lift*(x-a)/(b-a)
        slab("Canopy load-bearing post",canopy,(x-w/2,x+w/2,y-w/2,y+w/2,.030,height_),cream if old else iron)
        slab("Canopy post base",canopy,(x-w/2-.058,x+w/2+.058,y-w/2-.058,y+w/2+.058,.031,.34),floor)
        collider("Canopy post",(x-w/2-.058,x+w/2+.058,y-w/2-.058,y+w/2+.058,.03,height_),
                 "building","fuel_canopy")
    if not old:
        # A fixed brand-neutral sign lives on the canopy. Owner branding lives on the pole.
        axis=(a+b)/2
        slab("Raised GAS fascia panel housing",canopy,(axis-1.075,axis+1.075,c-.061,c+.129,e-.05,e+.89),iron)
        graphic("Canopy front GAS label","gas",axis,c-.072,e-.015,parent=canopy)

    # The low concrete skirts mark the approach lanes without raising their entry thresholds.
    if old:
        slab("Concrete apron below the carport",lot,(-4.03,4.03,-6.31,-.019,.0341,.041),floor)
    else:
        for x in ((-6.34,-4.54,-1.05,.77) if middle else (-7.11,-5.05,-.96,1.10,5.14,7.21)):
            slab("Painted queue lane",lot,(x-.044,x+.044,-14.48,-11.83,.0342,.041),cream)
        if middle:
            for x in (4.59,8.47,12.23):
                slab("Service bay approach line",lot,(x-.041,x+.041,-4.87,-.70,.034,.039),cream)
    pumps=group("fuel_pumps",lot)
    if modern:
        for x in (-6.08,.07,6.18):
            slab("Long concrete self-serve island",pumps,(x-.73,x+.73,-11.34,-5.51,.036,.151),floor)
    for i,(x,y) in enumerate(islands,1):
        ob=marker(f"pump_{i}",(x,y,0),pumps)
        xx=.57 if old else .63 if middle else .65
        if not modern:
            slab("Independent pump island",ob,(-xx,xx,-.69,.69,.036,.141),floor)
        if old:
            oval("Gravity pump column",ob,[(0,0,z,r,r) for z,r in ((.139,.288),(.27,.249),
                 (1.25,.216),(1.35,.279))],orange,10 if near else 7)
            oval("Clear glass petrol reservoir",ob,[(0,0,z,r,r) for z,r in
                 ((1.32,.209),(1.40,.234),(1.89,.234),(1.98,.206))],globe,12 if near else 7)
            oval("Visible fuel line",ob,[(0,0,z,.193,.193) for z in (1.393,1.60)],orange,10 if near else 6)
            oval("Reservoir enamel cap",ob,[(0,0,z,r,r) for z,r in
                 ((1.962,.263),(2.047,.242),(2.145,.131))],iron,10 if near else 7)
            graphic("Gravity pump scale face","meter-small",0,-.226,.84,parent=ob)
            high=2.147
            tube=(.245,.045)
            nozzle=(x+.385,y-.18,1.07)
        else:
            hw=.477 if modern else .46
            slab("Dispenser base",ob,(-hw,hw,-.325,.325,.146,1.62 if modern else 1.48),iron if modern else cream)
            slab("Petrol amber facing",ob,(-hw+.028,hw-.028,-.369,-.324,.17,1.43),orange)
            slab("Upper dispenser band",ob,(-hw-.015,hw+.015,-.362,.349,1.39,1.66),cream if modern else iron)
            slab("Window for price and total",ob,(-.393,.393,-.394,-.36,.78,1.28),metal)
            graphic("Pump volume meter panel","meter-small",0,-.401,.842,parent=ob)
            graphic("Pump ordinal badge","pump-"+str(i),0,-.379,1.659,parent=ob)
            slab("Pump number topper",ob,(-.307,.307,-.37,-.17,1.65,2.365),iron)
            high=2.36
            tube=(hw,.075)
            nozzle=(x+hw+.115,y-.240,1.19)
        nx,ny,nz=(nozzle[0]-x,nozzle[1]-y,nozzle[2])
        if near:
            points=[(tube[0],tube[1],1.20),(tube[0]+.134,tube[1]+.037,.78),
                     (tube[0]+.145,-.039,.491),(tube[0]+.137,-.196,.55),(nx,ny,nz-.055)]
            for j in range(len(points)-1):
                beam("Black refuelling hose",ob,points[j],points[j+1],(.026,.026),(.026,.026),metal,5)
        if not far:
            beam("Nozzle handle",ob,(nx,ny,nz-.082),(nx,ny,nz+.057),(.047,.050),(.052,.050),metal,5)
            beam("Metal spout",ob,(nx,ny,nz+.053),(nx-.128,ny,nz+.18),(.025,.025),(.019,.019),cream,5)
        collider(f"Pump {i} full body",(x-xx+.105,x+xx-.035,y-.38,y+.345,.035,high),"lot",ob.name)
        anchor(f"pump_{i}_nozzle",nozzle)
        anchor(f"pump_{i}_use",(nozzle[0]+.36,nozzle[1],.035))
        features[f"pump_{i}"]={"node":ob.name,"nozzle":f"pump_{i}_nozzle","use":f"pump_{i}_use",
                              "style":"gravity_globe" if old else "enamel_box" if middle else "self_serve"}
        if modern and i in (1,2,3,4,5,6):
            yy=y-.85 if i%2 else y+.85
            for xx in ((x-.54,x+.54) if near else (x-.54,)):
                oval("Protective bollard",pumps,[(xx,yy,.143,.078,.078),(xx,yy,.87,.078,.078)],orange,8 if near else 5)
                collider("Raised island bollard",(xx-.078,xx+.078,yy-.078,yy+.078,.035,.87),"lot","fuel_pumps")

    utils=group("service_fixtures",lot)
    if old:
        # An open oil tray, small can silhouettes, and a freestanding compressed-air post.
        x,y=3.42,-.67
        slab("Oil rack lower pan",utils,(x-.63,x+.63,y-.29,y+.29,.042,.23),iron)
        slab("Oil rack face",utils,(x-.63,x+.63,y+.21,y+.29,.23,1.17),iron)
        slab("Oil rack shelf",utils,(x-.63,x+.63,y-.25,y+.235,.70,.77),cream)
        if near:
            for u in (-.41,-.11,.23,.47):
                for z in (.23,.77):
                    oval("Original oil can",utils,[(x+u,y-.081,z,.103,.105),
                         (x+u,y-.081,z+.221,.103,.105)],orange if u<0 else cream,8)
        collider("Oil rack",(x-.63,x+.63,y-.29,y+.29,.035,1.17),"lot","service_fixtures")
        ax,ay=-5.26,-2.36
        oval("Air post",utils,[(ax,ay,.032,.146,.146),(ax,ay,1.36,.103,.103)],iron,10)
        oval("Air pressure dial",utils,[(ax,ay,1.333,.194,.173),(ax,ay,1.52,.187,.164)],cream,10)
        collider("Air post",(ax-.19,ax+.19,ay-.18,ay+.18,.034,1.52),"lot","service_fixtures")
        anchor("air_post_use",(ax+.45,ay,.034))
    if modern:
        x,y=6.10,-.94
        chest=marker("ice_chest",(x,y,0),utils)
        slab("Insulated ice chest",chest,(-1.00,1.00,-.41,.41,.038,1.18),cream)
        slab("Blue freezer lid",chest,(-1.03,1.03,-.447,.44,1.12,1.24),iron)
        graphic("Ice label","ice",0,-.462,.257,parent=chest)
        collider("Ice chest",(x-1.03,x+1.03,y-.447,y+.44,.034,1.24),"lot",chest.name)
        ax,ay=11.71,-3.45
        am=marker("air_machine",(ax,ay,0),utils)
        slab("Air machine plinth",am,(-.71,.71,-.43,.43,.034,.17),floor)
        slab("Digital inflation cabinet",am,(-.47,.47,-.29,.29,.16,1.72),iron)
        slab("Pressure readout",am,(-.23,.23,-.31,-.29,1.31,1.54),cream)
        collider("Air machine",(ax-.47,ax+.47,ay-.29,ay+.29,.034,1.72),"lot",am.name)
        anchor("air_machine_use",(ax,ay-.55,.034))
    for x,y in ((-3.72,8.29),(1.22,8.22)):
        slab("Rear steel bin",lot,(x-.28,x+.28,y-.245,y+.245,.035,.82),iron)
        slab("Rear bin lid",lot,(x-.32,x+.32,y-.28,y+.28,.801,.88),cream)
        collider("Rear refuse bin",(x-.32,x+.32,y-.28,y+.28,.034,.88),"lot","lot")
    for x in (x0+.33,x1-.33):
        slab("Rear downpipe",shell,(x-.048,x+.048,7.502,7.597,.14,height-.06),metal)
    # Every face points out to the correct lane. Both faces share an owner plate and a separate price plate.
    pole=group("roadside_sign",lot)
    sx,sy=-11.92,-11.95
    h=5.08 if old else 8.20 if middle else 7.48
    om=h-1.32
    pm=om-1.58
    for dx in ((-2.13,2.13) if old else (0,)):
        w=.213 if old else .31
        slab("Road sign pole",pole,(sx+dx-w/2,sx+dx+w/2,sy-.17,sy+.17,.034,h),iron)
        collider("Road sign full support",(sx+dx-w/2,sx+dx+w/2,sy-.17,sy+.17,.034,h),"lot",pole.name)
    slab("Owner plate housing",pole,(sx-3.25,sx+3.25,sy-.229,sy+.239,om-.051,h+.042),iron if not old else cream)
    slab("Price cabinet",pole,(sx-2.095,sx+2.095,sy-.246,sy+.257,pm-.066,om-.132),metal)
    sale=group("price_panel",pole)
    _,_,pw,ph=ART["rects"]["price-"+era]
    pwidth,pheight=pw*T,ph*T
    for y,flip,suffix in ((sy-.269,False,"street"),(sy+.279,True,"exit")):
        graphic("sign_name_"+suffix,"name-"+era,sx,y,om,parent=pole,flip=flip,keep=True)
        price_face=plaque("price_face_"+suffix,sale,face(sx-pwidth/2,sx+pwidth/2,y,pm,pm+pheight,ident,flip),
                          price,(0,0,pw,ph),(pw,ph))
        price_face["keep"]=True
    if middle:
        oval("Tall pole sign lantern topper",pole,[(sx,sy,z,r,r) for z,r in
             ((h-.02,.22),(h+.22,.465),(h+.75,.388),(h+.99,.198))],orange,8 if not far else 6)
    elif modern:
        slab("LED frame inset accent",pole,(sx-2.15,sx+2.15,sy-.261,sy-.234,pm-.106,pm-.063),orange)
    features["price"]={"node":"price_panel","material":"glow_price","texture":"price-"+era+".png",
                       "rect":[0,0,pw,ph],"front_and_back":True,
                       "digits_rect":[0,0,pw,ph],
                       "style":"led" if modern else "painted_board",
                       "unit":"currency_per_gallon" if modern else "cents_per_gallon"}
    flush_colliders()
    scheme=ART["styles"][era]
    desc={"id":"sign_name","nodes":["sign_name_street","sign_name_exit"],"texture":"signs.png",
          "rect":ART["rects"]["name-"+era],"lines":1,"maxChars":12,**scheme,"glow":True}
    return {"era":AGES[era],"height_m":h+(.99 if middle else .042),
            "footprint_m":[15.3 if modern else 16.85 if middle else 8.5,7.5],
            "lot_bounds_gltf_m":[[-16,0,-11.2],[15.45,.048,17]],
            "doors":doors,"anchors":anchors,"features":features,
            "collision_boxes":boxes,"signs":[desc],"price_panels":[features["price"]],
            "price":{"node":"price_face_street","art":[pw//2,ph//2],"grades":["regular"],"rows":[5],
                     "left":14,"font":"5x7","ink":"#e37b49" if modern else "#efdfad","paper":"#393f3b"}}


def export(era):
    info=None
    for lod in ("near","mid","far"):
        current=build(era,lod)
        stem=f"vale-lantern-{era}"
        if lod=="near":
            info=current
            save_source(ROOT/(stem+".blend"))
        for parent in [o for o in bpy.data.objects if o.type=="EMPTY" and o.name!="collision"]:
            batch(parent)
        filename=stem+("" if lod=="near" else "-"+lod)+".glb"
        export_glb(ROOT/filename)
        visible=[o for o in bpy.data.objects if o.type=="MESH" and not o.get("collision_only")]
        info.setdefault("models",{})[lod]={"file":filename,
            "triangles":sum(len(p.vertices)-2 for o in visible for p in o.data.polygons),
            "materials":sorted({m.name for ob in visible for m in ob.data.materials})}
    return info


if __name__=="__main__":
    states={era:export(era) for era in AGES}
    info={"task":"C015","name":"Vale Lantern Fuel","naming":"owner","meters_per_texel":T,
          "texels_per_art_pixel":2,"front_gltf":"+Z","origin":"Ground at the midpoint of the store street facade",
          "swatches":{"roof_shingle":"roof_slate","forecourt":"asphalt_worn","concrete":"pavement"},
          "signs":[{**s,"id":s["id"]+"_"+era,"state":era}
                   for era,state in states.items() for s in state["signs"]],"states":states}
    (ROOT/"model-info.json").write_text(json.dumps(info,indent=2)+"\n")
