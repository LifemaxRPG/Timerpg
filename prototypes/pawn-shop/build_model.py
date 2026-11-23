"""Build and export three eras of the Latch & Lyre pawn shop."""
import json
from math import pi
from pathlib import Path
import sys

import bpy

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from revision_common import batch, emitter, export_glb, save_source
from sculpture_tools import beam, box, group, mesh, material, oval, plaque

T=.0388
P=32*T
FLOOR=.044
REGIONS=json.loads((ROOT/"textures/regions.json").read_text())
ERAS={"1920":[1920,1959],"1960":[1960,1999],"2000":[2000,None]}


def cast(x,y,z):
    return [round(i,5) for i in (x,z,-y)]


def build(era,lod):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    near,far=lod=="near",lod=="far"
    old,modern=era=="1920",era=="2000"
    root=group("Latch & Lyre")
    shell,interior,lot,stock,collision_root=[group(n,root) for n in
            ("shell","fixed_interior","lot","display_stock","collision")]
    brick=material("wall_brick","brick")
    stone=material("wall_cast_stone","stone")
    roof=material("roof_membrane","roof")
    wood=material("floor_plank","plank")
    paving=material("ground_paving","paving")
    paint=material("detail_storefront",{"1920":"teal","1960":"ochre","2000":"graphite"}[era])
    metal=material("detail_ironwork","metal")
    brass=material("detail_antique_brass","brass")
    front_glass=material("glass_display","loans" if old else "window")
    clear_glass=material("glass_inner","plain")
    artwork=material("sign_paint","signs")
    lamp=material("glow_lamp","lamp",.85)
    emitter(artwork,"sign-mask",.73)
    emitter(front_glass,"loans-mask" if old else "window-mask",.13)
    for mat in (front_glass,clear_glass):
        nodes=mat.node_tree.nodes
        texture=next(n for n in nodes if n.type=="TEX_IMAGE")
        mat.node_tree.links.new(texture.outputs["Alpha"],nodes["Principled BSDF"].inputs["Alpha"])
    invisible=bpy.data.materials.new("collision_invisible")
    invisible.use_nodes=True
    invisible.surface_render_method="BLENDED"
    invisible.node_tree.nodes["Principled BSDF"].inputs["Alpha"].default_value=0
    collision=[]
    anchors={}
    doors={}
    features={}

    def slab(label,parent,b,mat,*,block=False,attachment=""):
        a,c,d,e,f,h=b
        ob=box(label,parent,((a+c)/2,(d+e)/2,(f+h)/2),(c-a,e-d,h-f),mat)
        if block:
            collider(label,b,parent==lot,attachment)
        return ob

    def collider(label,b,lot_role=False,attachment=""):
        a,c,d,e,f,h=b
        if near:
            ob=slab(f"collision_{len(collision):02d}",collision_root,b,metal)
            ob.data.materials.clear()
            ob.data.materials.append(invisible)
            ob["collision_only"]=True
            collision.append({"name":label,"center_gltf_m":cast((a+c)/2,(d+e)/2,(f+h)/2),
                              "size_gltf_m":[round(dimension,5) for dimension in (c-a,h-f,e-d)],
                              "group":"lot" if lot_role else "building","attachment":attachment})

    def marker(name,pt,parent=root):
        node=group(name,parent)
        node.location=pt
        return node

    def door(name,pt,exterior=True,yaw=0):
        node=marker(name,pt)
        node.rotation_euler.z=yaw
        doors[name]={"position_gltf_m":cast(*pt),"external":exterior,
                     "facing":"+Z" if yaw==0 else "-Z"}

    def anchor(name,pt):
        marker(name,pt)
        anchors[name]=cast(*pt)

    def pane(label,parent,pts,mat):
        width=sum((a-b)**2 for a,b in zip(pts[0],pts[1]))**.5
        height=sum((a-b)**2 for a,b in zip(pts[0],pts[3]))**.5
        return plaque(label,parent,pts,mat,(0,64-height/T,width/T,height/T),(64,64))

    def graphic(label,key,x,y,z,parent=shell,reverse=False):
        r=REGIONS[key]
        w,h=r[2]*T,r[3]*T
        pts=[(x-w/2,y,z),(x+w/2,y,z),(x+w/2,y,z+h),(x-w/2,y,z+h)]
        if reverse:
            pts=[pts[i] for i in (1,0,3,2)]
        ob=plaque(label,parent,pts,artwork,r,(512,512))
        ob["keep"]=label=="sign_name"
        return ob

    def wall(label,parent,a,b,d,e,h,mat,holes=(),axis="x",floor=FLOOR):
        """Extrude a wall around holes ordered by lateral coordinate."""
        def xyz(u,v,z):
            return (u,v,z) if axis=="x" else (v,u,z)

        def world_bounds(p,q,low,high):
            return (p,q,d,e,low,high) if axis=="x" else (d,e,p,q,low,high)

        verts=[]
        polys=[]

        def quad(points):
            idx=len(verts)
            verts.extend(xyz(*p) for p in points)
            polys.append([idx+j for j in (range(4) if axis=="x" else (3,2,1,0))])

        def skins(p,q,k,l):
            if q-p>1e-6 and l-k>1e-6:
                quad(((p,d,k),(q,d,k),(q,d,l),(p,d,l)))
                quad(((q,e,k),(p,e,k),(p,e,l),(q,e,l)))

        def reveal(p,k,l,left):
            points=((p,d,k),(p,e,k),(p,e,l),(p,d,l))
            quad(points if left else points[::-1])

        def cap(p,q,z,upper):
            points=((p,d,z),(q,d,z),(q,e,z),(p,e,z))
            quad(points if upper else points[::-1])

        drawn=walk=a
        for p,q,low,high,kind in sorted(holes):
            assert drawn<=p<q<=b and floor<=low<high<=h,(label,p,q)
            skins(drawn,p,floor,h)
            skins(p,q,floor,low)
            skins(p,q,high,h)
            cap(p,q,high,False)
            reveal(p,low,high,True)
            reveal(q,low,high,False)
            if kind=="door":
                if walk < p:
                    collider(label,world_bounds(walk,p,floor,h))
                walk=q
            else:
                cap(p,q,low,True)
                mid=(d+e)*.5
                points=[xyz(p,mid,low),xyz(q,mid,low),xyz(q,mid,high),xyz(p,mid,high)]
                pane(label+" pane",parent,points,clear_glass if kind=="clear" else front_glass)
            drawn=q
        skins(drawn,b,floor,h)
        reveal(a,floor,h,False)
        reveal(b,floor,h,True)
        cap(a,b,h,True)
        mesh(label,parent,verts,polys,mat)
        if walk<b:
            collider(label,world_bounds(walk,b,0,h))

    def opening(a,b,high=2.29):
        return a,b,FLOOR,high,"door"

    # The ground-floor datum, party walls and storefront piers survive every renovation.
    slab("Lot foundation",lot,(-5.25,5.25,-2.9,16.65,0,.025),paving)
    slab("Shop floor",interior,(-3.89,3.89,0,14,0,FLOOR),wood)
    slab("Street curb",lot,(-4.9,4.9,-2.9,-2.68,.025,.052),stone)
    slab("Threshold bridge",shell,(-.80,.80,.10,.70,.01,FLOOR),stone)
    h=4.17
    wall("West party wall",shell,(.0),13.77,-3.90,-3.63,h,brick,axis="y")
    wall("East party wall",shell,(.0),13.77,3.63,3.90,h,brick,
         ((10.7,11.62,2.20,3.15,"glass"),),axis="y")
    wall("Rear brick wall",shell,-3.90,3.90,13.77,14,h,brick,
         ((-2.90,-1.65,1.65,2.70,"glass"),opening(1.63,2.84)),)
    slab("Roof deck",shell,(-3.91,3.91,-.015,14.05,4.00,4.21),roof)
    slab("Front parapet",shell,(-3.9,3.9,-.075,.21,4.14,4.77),brick)
    for a,b in ((-3.92,-3.67),(3.67,3.92)):
        slab("Party wall parapet",shell,(a,b,.21,14,4.18,4.46),brick)
        slab("Side coping",shell,(a-.035,b+.035,.19,14.10,4.46,4.55),stone)
    slab("Rear coping",shell,(-3.94,3.94,13.95,14.10,4.21,4.34),stone)
    slab("Stone front coping",shell,(-4.01,4.01,-.19,.26,4.77,4.92),stone)
    for a,b in ((-3.89,-3.30),(3.30,3.89)):
        slab("Full brick shopfront pier",shell,(a,b,0,.28,0,4.18),brick,block=True)
        slab("Stone plinth at jamb",shell,(a-.03,b+.03,-.04,.32,0,.34),stone)
        slab("Piers' cast cap",shell,(a-.045,b+.045,-.09,.32,3.13,3.34),stone)
    slab("Historic stone sign bed",shell,(-3.44,3.44,-.145,-.012,3.47,4.68),stone)
    graphic("sign_name","name-"+era,0,-.151,3.52)
    # Align the bricks in the old painted advertisement with the party wall.
    side_rect=REGIONS["wall-"+era]
    y=2.4832
    z=P
    w=side_rect[2]*T
    ht=side_rect[3]*T
    plaque("Fading musical instruments wall advertisement",shell,
          [(3.905,y,z),(3.905,y+w,z),(3.905,y+w,z+ht),(3.905,y,z+ht)],
          artwork,side_rect,(512,512))
    if not far:
        for x in (-3.49,3.49):
            slab("Cornice corbel",shell,(x-.13,x+.13,-.181,-.030,4.61,4.77),stone)
    slab("Facade belt course",shell,(-3.95,3.95,-.137,.08,3.31,3.47),stone)

    # Wide displays and a recessed entrance. The shopper's aperture remains open.
    for a,b in ((-3.30,-.82),(.82,3.30)):
        face=pane("Street display glazing",shell,
             ((a,.155,.63),(b,.155,.63),(b,.155,.63+2*P),(a,.155,.63+2*P)),front_glass)
        # Solid bulkhead below fixed glass, trim rails and recessed display plinth.
        slab("Painted shopfront bulkhead",shell,(a,b,.02,.34,FLOOR,.63),paint)
        side="west" if a<0 else "east"
        sheet=marker(f"display_window_{side}",(0,0,0),shell)
        # Link this pane and the steel grille to distinct removable barriers.
        face.parent=sheet
        collider("Fixed display bulkhead",(a,b,.035,.225,0,.63))
        collider("Display glazing",(a,b,.064,.222,.63,3.15),attachment=sheet.name)
        features[sheet.name]={"node":sheet.name,"position_gltf_m":[0,0,0]}
        for z0,z1 in ((.58,.66),(3.08,3.32)):
            slab("Structural sash rail",shell,(a,b,.035,.235,z0,z1),paint)
        for x in (a,b):
            slab("Glazing stile",shell,(x-.045,x+.045,.081,.204,.60,3.19),paint)
        if near:
            x=(a+b)*.5
            slab("Fixed display plinth",interior,(x-1.05,x+1.05,.32,1.07,FLOOR,.46),wood,block=True)
    for x in (-.82,.82):
        slab("Recess return",shell,(x-.049,x+.049,.14,.68,FLOOR,3.18),paint,block=True)
    wall("Recessed doorway head",shell,-.82,.82,.64,.79,3.32,paint,
         (opening(-.75,.75,2.49),))
    door("door_main",(0,.44,0))
    door("door_back",(2.24,14.16,0),yaw=pi)
    for x in (-3.1,3.12):
        slab("Storefront pilaster",shell,(x-.058,x+.058,-.075,.05,.56,3.31),paint)

    if old:
        for x in (-2.23,2.23):
            beam("Enamel lamp arm",shell,(x,.05,4.64),(x,-.40,4.72),(.03,.03),(.03,.03),metal,4)
            oval("Sign downlight shade",shell,[(x,-.40,4.64,.135,.135),(x,-.40,4.73,.067,.067)],metal,8)
            oval("Sign downlight lens",shell,[(x,-.40,4.629,.107,.107),(x,-.40,4.648,.108,.108)],lamp,8)
        # Three brass balls hang from an iron triangle instead of from the window.
        beam("Pawn emblem arm",shell,(0,.09,3.60),(0,-.67,3.60),
             (.036,.036),(.036,.036),metal,4)
        beam("Emblem hanger",shell,(0,-.66,3.58),(0,-.66,3.14),
             (.033,.033),(.027,.027),metal,4)
        beam("Crossbar",shell,(-.29,-.66,3.28),(.29,-.66,3.28),
             (.027,.027),(.027,.027),metal,4)
        for x,z in ((-.27,3.09),(.27,3.09),(0,2.79)):
            stem_top=3.28 if x else 3.18
            beam("Brass ball stem",shell,(x,-.66,stem_top),(x,-.66,z+.12),(.018,.018),(.018,.018),metal,4)
            oval("Golden pawn ball",shell,[(x,-.66,z+dz,r,r) for dz,r in
                 ((-.15,.056),(-.083,.128),(.055,.137),(.15,.052))],brass,8 if not far else 5)
    else:
        slab("Awning deck",shell,(-3.32,3.32,-.63,.08,3.24,3.43),paint)
        slab("Awning lip",shell,(-3.37,3.37,-.69,-.6,3.19,3.45),metal)
        for x in (-2.06,2.06):
            slab("Awning display downlight",shell,(x-.19,x+.19,-.50,-.28,3.222,3.242),lamp)
        for x in (-1.97,1.97):
            bars=marker("window_bars_west" if x<0 else "window_bars_east",(x,-.03,.60),shell)
            for dx in ((-.99,-.66,-.33,0,.33,.66,.99) if near else (-.92,0,.92)):
                slab("Vertical burglar bar",bars,(dx-.018,dx+.018,-.023,.023,0,2.54),metal)
            for z in (.10,1.27,2.48):
                slab("Burglar bar rail",bars,(-1.18,1.18,-.037,.037,z,z+.052),metal)
            features[bars.name]={"node":bars.name,"position_gltf_m":cast(x,-.03,.60)}
            collider("Fixed window bars",(x-1.18,x+1.18,-.067,.007,.60,3.14),
                     attachment=bars.name)
        if modern:
            slab("LED cabinet",shell,(-2.88,2.88,-.811,-.66,2.46,3.32),metal)
            graphic("LED We Buy Gold","buygold",0,-.818,2.47)
        else:
            slab("Box for neon price sign",shell,(-3.99,3.99,-.80,-.62,2.44,3.32),paint)
            graphic("Amber Guns Gold TV","goldtv",0,-.810,2.45)
            slab("Hanging Pawn plaque backing",shell,(2.25,4.76,-.15,.02,4.11,5.07),metal)
            graphic("Neon PAWN","pawn",3.50,-.156,4.17)
        buzzer=marker("entry_buzzer",(.81,.567,1.37),shell)
        slab("Enamel buzzer",buzzer,(0,.09,-.045,.03,0,.22),metal)
        features["entry_buzzer"]={"node":buzzer.name,"position_gltf_m":cast(.81,.567,1.37)}
    if not far:
        for x in (-3.49,3.46):
            slab("Downspout",shell,(x-.044,x+.044,14.01,14.10,.20,4.28),metal)
        slab("Roof ventilation curb",shell,(-2.30,-.95,11.16,12.25,4.21,4.40),stone)
        slab("Pitched vent hood",shell,(-2.42,-.83,11.05,12.34,4.40,4.55),metal)
        slab("Alley lamp housing",shell,(1.93,2.50,14.005,14.21,2.53,2.67),metal)
        slab("Alley lamp lens",shell,(1.97,2.46,14.03,14.19,2.51,2.54),lamp)
        if modern:
            slab("Rooftop HVAC plinth",shell,(.73,2.81,8.23,10.26,4.21,4.38),stone)
            slab("Rooftop HVAC unit",shell,(.87,2.65,8.40,10.09,4.38,5.22),metal)
            for z in (4.54,4.70,4.86,5.02):
                slab("Air-conditioner louvers",shell,(.868,2.66,10.09,10.13,z,z+.058),stone)
            slab("HVAC return duct",shell,(.19,.9,8.76,9.68,4.22,4.65),paint)
    slab("Service walkway",lot,(1.0,3.30,14.03,16.00,.026,.047),stone)
    for x in (-2.58,-1.8):
        slab("Alley bin",lot,(x-.24,x+.24,14.30,14.88,.026,.82),metal)
        collider("Alley bin",(x-.27,x+.27,14.27,14.91,.026,.86),True,"lot")
        slab("Alley bin lid",lot,(x-.27,x+.27,14.27,14.91,.79,.86),paint)
    graphic("Alley receiving marker","records",.22,14.022,2.74,reverse=True)

    # The counter and its aperture are separate from all wall and shelf fixtures.
    counter=marker("counter",(0,7.51,FLOOR),interior)
    anchor("counter_clerk",(0,8.52,FLOOR))
    anchor("counter_customer",(0,6.88,FLOOR))
    door("door_store",(2.24,9.01,0),False,yaw=pi)
    if not far:
        slab("Clerk counter front",counter,(-2.25,1.39,0,.62,0,1.06),wood)
        slab("Clerk counter lip",counter,(-2.32,1.46,-.103,.70,1.06,1.19),paint)
        collider("Clerk counter",(-2.32,1.46,7.407,8.21,FLOOR,1.234),attachment="counter")
        if old:
            for x in (-2.06,-1.63,-1.20,-.77,-.34,.49,.92,1.35):
                slab("Brass cage upright",counter,(x-.025,x+.025,.34,.401,1.19,2.50),brass)
            for z in (1.50,2.10,2.46):
                slab("Cage horizontal rail",counter,(-2.13,1.39,.305,.437,z,z+.064),brass)
        elif modern:
            for x in (-2.19,-.41,.52,1.39):
                slab("Security window mullion",counter,(x-.041,x+.041,.21,.36,1.19,2.39),metal)
            pane("Fixed armored glass",counter,
                 ((-2.19,.29,1.23),(1.39,.29,1.23),(1.39,.29,2.36),(-2.19,.29,2.36)),clear_glass)
            slab("Transfer drawer on customer side",counter,(-.34,.45,.023,.34,1.17,1.25),brass)
            slab("Glass header",counter,(-2.24,1.43,.225,.35,2.34,2.44),metal)
        wall("Clerk office partition",interior,-3.63,3.63,9.18,9.34,3.98,stone,
             (opening(1.63,2.84),opening(-2.56,-1.46)))
        wall("Office and stock divider",interior,9.34,13.77,.10,.24,3.98,stone,axis="y")
        graphic("Private office lettering","service",-1.80,9.167,2.62,parent=interior)
        safe=marker("safe",(-2.65,12.90,FLOOR),interior)
        slab("Safe body",safe,(-.53,.53,-.38,.37,0,1.32),metal)
        slab("Safe door seam",safe,(-.49,.49,-.402,-.377,.07,1.25),paint)
        if near:
            oval("Safe dial",safe,[(0,0,z,.125,.125) for z in (.0,.06)],
                 brass,8,lambda x,y,z:(x-.10,-.39-z,y+.76))
            slab("Safe handle",safe,(.18,.33,-.51,-.397,.70,.78),brass)
        collider("Safe",(-3.18,-2.12,12.498,13.27,FLOOR,1.364),attachment="safe")
        features["safe"]={"node":"safe","position_gltf_m":cast(-2.65,12.90,FLOOR)}

    # Named fixed shelving and display wells. Only the near model includes merchandise.
    def shelf(name,x,y,length,h=2.03):
        shelf_=marker(name,(x,y,FLOOR),interior)
        levels=(.18,.72,1.25,1.78) if h>1.5 else (.24,.74)
        back=(-.23,-.18) if x<0 else (.18,.23)
        slab("Shelf back",shelf_,(*back,-length/2,length/2,.05,h),paint)
        for z in levels:
            slab("Shelf board",shelf_,(-.24,.23,-length/2,length/2,z,z+.052),wood)
        for dy in (-length/2,length/2):
            slab("Shelf upright",shelf_,(-.251,.245,dy-.031,dy+.031,0,h),wood)
        collider(name,(x-.251,x+.245,y-length/2-.031,y+length/2+.031,FLOOR,FLOOR+h),attachment=name)
        features[name]={"node":name,"position_gltf_m":cast(x,y,FLOOR),
                         "stock_surfaces_gltf_m":[cast(x,y,FLOOR+z+.052) for z in levels]}
        return levels

    if not far:
        main_shelves=[("display_west", -3.36, 3.2, 3.88),
                      ("display_east", 3.34, 4.48, 3.80),
                      ("display_stockroom",3.30,11.8,2.91)]
        for name,x,y,length in main_shelves:
            shelf(name,x,y,length)
        jewel=marker("display_jewellery",(1.49,3.71,FLOOR),interior)
        slab("Jewellery cabinet base",jewel,(-.47,.47,-1.10,1.10,0,.67),paint)
        for x in (-.47,.47):
            pane("Jewellery glass",jewel,((x,-1.10,.67),(x,1.10,.67),
                 (x,1.10,1.08),(x,-1.10,1.08)),clear_glass)
        for y in (-1.10,1.10):
            pane("Jewellery end glass",jewel,((-.47,y,.67),(.47,y,.67),(.47,y,1.08),(-.47,y,1.08)),clear_glass)
        pane("Jewellery top glass",jewel,((-.47,-1.10,1.08),(.47,-1.10,1.08),(.47,1.10,1.08),(-.47,1.10,1.08)),clear_glass)
        for x in (-.47,.47):
            slab("Jewellery case brass channel",jewel,(x-.026,x+.026,-1.10,1.10,1.065,1.109),brass)
        collider("Jewellery cabinet",(1.0,1.98,2.61,4.81,FLOOR,1.153),attachment=jewel.name)
        features[jewel.name]={"node":jewel.name,"position_gltf_m":cast(1.49,3.71,FLOOR),
                              "stock_surfaces_gltf_m":[cast(1.49,3.71,.714)]}
        podium=marker("display_feature",(-1.46,3.44,FLOOR),interior)
        slab("Fixed central display base",podium,(-.47,.47,-.72,.72,0,.79),paint)
        slab("Central display top",podium,(-.52,.52,-.79,.79,.79,.86),wood)
        collider("Central display",(-1.98,-.94,2.65,4.23,FLOOR,.904),attachment=podium.name)
        features[podium.name]={"node":podium.name,"position_gltf_m":cast(-1.46,3.44,FLOOR),
                               "stock_surfaces_gltf_m":[cast(-1.46,3.44,.904)]}
        # Different lighting and wall finishes communicate the age of each refit.
        if modern:
            for a,b in ((-3.625,-3.600),(3.600,3.625)):
                slab("Modern plaster liner",interior,(a,b,1.12,8.92,FLOOR,3.92),stone)
        for y in (2.16,5.44,8.31):
            length=.45 if old else .72 if modern else .60
            slab("Fixed light ceiling pan",interior,(-.27,.27,y-length,y+length,3.85,4),metal)
            slab("Glass light shade",interior,(-.24,.24,y-length+.044,y+length-.044,3.79,3.85),
                 lamp if y==5.44 else stone)

    if near:
        # Forms are exaggerated slightly so silhouettes survive the game's pixel scale.
        def item(label,x,y,z,w,d,h,mat):
            slab(label,stock,(x-w/2,x+w/2,y-d/2,y+d/2,z,z+h),mat)

        def radio(x,y,z,era):
            item("Valve radio" if era=="1920" else "Solid-state radio",x,y,z,.36,.46,.24,wood)
            direction=1 if x<0 else -1
            xx=x+direction*.185
            item("Radio tuning face",xx,y,z+.09,.014,.35,.092,brass)
            item("Radio speaker panel",xx,y,z+.025,.018,.3,.059,metal)

        def screen(x,y,z,deep=False):
            item("Television cabinet" if deep else "Flat-screen display",x,y,z,
                 .355 if deep else .09,.50,.34,wood if deep else metal)
            xx=x+(1 if x<0 else -1)*(.18 if deep else .05)
            item("Dark television glass",xx,y,z+.048,.016,.414,.245,metal)
            if not deep:
                item("Monitor foot",x,y,z,.30,.34,.05,metal)

        if old:
            for y in (1.66,2.25,2.78,3.36,3.95,4.48):
                radio(-3.36,y,.816 if y<3.0 else 1.346,era)
            for i,y in enumerate((3.03,3.57,4.11,4.64,5.16)):
                item("Tool case",3.34,y,.278,.37,.40,.17,metal if i%2 else brass)
                item("Plane iron",3.33,y,.817,.28,.19,.09,brass)
                item("Plane grip",3.33,y, .90,.124,.115,.115,wood)
            for x,y in ((-2.19,1.0),(-2.78,1.0),(2.77,1.0)):
                # Each instrument leans against the front of a permanent display shelf.
                oval("Acoustic instrument body",stock,[(x,y,z,.132*s,.082*s) for z,s in
                        ((.46,.43),(.55,1),(.74,.89),(.85,.63),(.92,.73),(.99,.39))],wood,8)
                beam("Instrument neck",stock,(x,y,.87),(x,y+.06,1.44),(.034,.042),(.026,.027),brass,4)
                item("Instrument headstock",x,y+.06,1.39,.109,.09,.17,wood)
            for x,y in ((-2.77,.69),(-2.21,.69),(2.54,.69)):
                radio(x,y,.46,era)
            radio(-1.47,3.79,.904,era)
            for x,y in ((-1.69,3.36),(-1.25,3.06)):
                oval("Showcase instrument belly",stock,[(x,y,.904+z,.15*s,.075*s) for z,s in
                     ((0,.54),(.084,.93),(.21,1),(.30,.73),(.39,.86),(.49,.47))],wood,8)
                beam("Showcase instrument neck",stock,(x,y,1.37),(x,y+.08,1.92),
                     (.037,.039),(.028,.027),brass,4)
                item("Showcase headstock",x,y+.08,1.88,.12,.09,.142,paint)
                item("Sound hole",x,y-.074,1.17,.08,.012,.078,metal)
        elif not modern:
            for y in (1.70,2.34,3.02,3.68,4.25):
                screen(-3.36,y,.816,deep=True)
            for y in (3.04,3.63,4.21,4.77,5.37):
                radio(3.34,y,1.346,era)
                item("Toolbox",3.34,y,.278,.38,.43,.18,brass)
            screen(-1.46,3.29,.904,deep=True)
            radio(-1.46,3.91,.904,era)
        else:
            for y in (1.75,2.33,2.96,3.59,4.17):
                screen(-3.35,y,1.346)
                item("Console", -3.36,y,.816,.31,.37,.10,metal)
                item("Console face",-3.176,y,.841,.016,.29,.045,brass)
            for y in (3.04,3.61,4.20,4.75,5.34,5.9):
                item("Handheld",3.31,y,.816,.25,.32,.059,metal)
                item("Component box",3.34,y,1.346,.31,.40,.179,paint)
            screen(-1.46,3.34,.904)
            item("Demo games console",-1.45,3.88,.904,.45,.33,.12,metal)
            item("Console status strip",-1.21,3.88,.953,.023,.25,.031,brass)
            for x,y,z in ((-.95,.72,2.85),(1.19,7.34,3.17),(2.81,14.04,2.82)):
                camera=marker(f"cctv_{len([k for k in features if k.startswith('cctv')])+1}",(x,y,z),shell)
                if y>14:
                    camera.rotation_euler.z=pi
                beam("Camera bracket",camera,(0,0,0),(.20,-.12,-.12),(.028,.028),(.029,.029),metal,4)
                slab("Camera housing",camera,(.08,.34,-.30,-.07,-.17,-.031),stone)
                slab("Camera lens",camera,(.149,.269,-.33,-.29,-.134,-.062),metal)
                features[camera.name]={"node":camera.name,"position_gltf_m":cast(x,y,z)}
        for y in (10.78,11.31,11.84,12.37,12.9):
            for z in (.278,.817,1.346):
                item("Tagged storage carton",3.29,y,z,.34,.39,.25,wood if old else paint)
                if not old:
                    item("Paper inventory tag",3.109,y+.08,z+.054,.019,.11,.133,stone)
        for i,y in enumerate((2.86,3.25,3.65,4.05,4.45)):
            item("Jewellery velvet pad",1.49,y,.714,.54,.31,.044,metal)
            for dx in (-.14,0,.14) if i%2 else (-.09,.09):
                oval("Rolled jewellery mount",stock,[(1.49+dx,y,z,r,r) for z,r in
                     ((.757,.044),(.80,.049),(.814,.036))],brass,6)
    if modern:
        features["bulletproof_window"]={"node":"counter","position_gltf_m":cast(0,7.80,1.9)}

    info={"era":ERAS[era],"footprint_m":[7.8,14.0],"height_m":{"1920":4.92,"1960":5.07,"2000":5.22}[era],
          "lot_bounds_gltf_m":[[-5.25,0,-16.65],[5.25,.052,2.90]],
          "doors":doors,"anchors":anchors,"features":features,"collision_boxes":collision,
          "signs":[{"id":"sign_name","node":"sign_name","texture":"signs.png","rect":REGIONS["name-"+era],
                    "lines":1,"maxChars":12,"face":"small-town-block","glow":False,
                    "ink":{"1920":"#efd8a1","1960":"#ffe1ae","2000":"#f2dbb9"}[era],
                    "paper":{"1920":"#2d514d","1960":"#694a34","2000":"#363c40"}[era]}]}
    return info


def export(era):
    info=None
    for lod in ("near","mid","far"):
        current=build(era,lod)
        stem=f"latch-and-lyre-{era}"
        if lod=="near":
            info=current
            save_source(ROOT/(stem+".blend"))
        if lod=="far":
            for part in list(bpy.data.objects):
                if part.type=="MESH" and any(k in part.name for k in
                        ("plinth","pilaster","sash rail","Glazing stile","Bin lid","Downspout")):
                    bpy.data.objects.remove(part,do_unlink=True)
        for parent in [ob for ob in bpy.data.objects if ob.type=="EMPTY" and ob.name!="collision"]:
            batch(parent)
        name=stem+("" if lod=="near" else f"-{lod}")+".glb"
        export_glb(ROOT/name)
        geometries=[o for o in bpy.data.objects if o.type=="MESH" and not o.get("collision_only")]
        info.setdefault("models",{})[lod]={
            "file":name,"triangles":sum(sum(len(p.vertices)-2 for p in o.data.polygons) for o in geometries),
            "materials":sorted({mat.name for ob in geometries for mat in ob.data.materials})}
    return info


if __name__=="__main__":
    model={"name":"Latch & Lyre","naming":"owner","meters_per_texel":T,"texels_per_art_pixel":2,
           "front_gltf":"+Z","origin":"ground at midpoint of front brick piers",
           "shared_swatches":{"wall_brick":"brick_red","floor_plank":"plank_horizontal","ground_paving":"pavement"},
           "states":{era:export(era) for era in ERAS}}
    (ROOT/"model-info.json").write_text(json.dumps(model,indent=2)+"\n")
