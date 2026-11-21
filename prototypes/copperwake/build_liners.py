"""Build the 1952 liner, its 1979 laid-up state, and four distance models."""
import json
import sys
from pathlib import Path
from math import pi

import bpy

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from modern_details import ink
from revision_common import batch, block, collider, emitter, export_glb, marker, save_source, share_translated
from sculpture_tools import beam, group, loft, material, oval

stats={}


def liner(classic,state,detail):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    key="ss-copperwake" if classic else "copperwake"
    if state=="laid_up":
        key+="-laid-up"
    if detail!="near":
        key+="-"+detail
    root=group(key)
    hull=group("hull",root)
    deckhouse=group("superstructure",root)
    boat_group=group("boat_deck",root)
    stack_group=group("funnels_and_rigging",root)
    teak=material("ground_teak","teak")
    ivory=material("wall_superstructure","white")
    copper=material("painted_funnel","painted_steel")
    rubber=material("detail_rubber","rubber")
    gold=material("detail_brass","brass")
    type_name="laid_up_hull" if state=="laid_up" else "classic_hull" if classic else "plating"
    navy=material("wall_hull",type_name)
    if state!="laid_up" and detail!="far":
        emitter(navy,"plating_emission",.50)
    glass=material("glass_passenger","classic_windows" if classic else "glazing")
    if classic and state!="laid_up" and detail!="far":
        emitter(glass,"classic_windows_emission",.85)
    windows=material("wall_balconies","balconies")
    if state!="laid_up" and detail!="far":
        emitter(windows,"balcony_emission",.8)
    water=material("detail_pool_water","water")
    verde=material("detail_teal","teal")
    length=260 if classic else 309
    w=16.7 if classic else 19.5
    waterline=8.3 if classic else 9.35
    floor=17.4 if classic else 21.26
    bow=-length/2 if classic else -154
    stern=bow+length
    anchors=[]
    boxes=[]

    def outline(z, factor, pitch=0):
        shape=[(.09,bow+pitch),(.30*w,bow+length*.057+pitch*.65),
               (.73*w,bow+length*.147+pitch*.17),(w,bow+length*.30),
               (w,stern-length*.20),(.88*w,stern-length*.07),
               (.54*w,stern-length*.016-pitch*.34),(.09,stern-pitch*.60)]
        if detail=="far":
            shape=[shape[i] for i in (0,2,3,4,6,7)]
        return [(x*factor,y,z) for x,y in shape]+[(-x*factor,y,z) for x,y in reversed(shape)]

    if detail=="far":
        lower=outline(0,.60,18)
        middle=outline(waterline,.98,5)
        upper=outline(floor,1)
        loft("Fast hull silhouette",hull,[lower,middle,upper],
             lambda i,j:copper if i==0 else navy)
    else:
        rings=[outline(0,.55,18),outline(2.2,.76,13),
               outline(waterline+1.15,.98,7),outline(waterline+2,.991,5),
               outline(floor-.33,1),outline(floor,1)]
        loft("Keel, plating and sheer",hull,rings,
             lambda i,j:(copper,copper,rubber,navy,gold)[i])
        loft("Teak weather deck",hull,[outline(floor,1),outline(floor+.09,1)],teak)
    for y0,y1,ww in [(bow+11,bow+54,w*.72),(bow+54,stern-20,w),(stern-20,stern-5,w*.62)]:
        boxes.append(collider("Hull section","hull",(0,(y0+y1)/2,floor/2),(ww*2,y1-y0,floor)))

    def building(name,z,h,w,y0,y1,corner,skin):
        points=[(-w+corner,y0,z),(w-corner,y0,z),(w,y0+corner,z),
                (w,y1-corner,z),(w-corner,y1,z),(-w+corner,y1,z),
                (-w,y1-corner,z),(-w,y0+corner,z)]
        if detail=="far":
            block(name,deckhouse,(0,(y0+y1)/2,z),(w*2,y1-y0,h),skin)
        else:
            loft(name,deckhouse,[points,[(x,y,zz+h) for x,y,zz in points]],skin)
    if classic:
        courses=[(floor+.09,3.7,13.4,-92,98),(floor+3.8,3.45,12.7,-86,90),
                 (floor+7.26,3.18,11.52,-79,76),(floor+10.45,3.0,10.7,-72,48),
                 (floor+13.48,2.7,8.5,-71,-42)]
    else:
        courses=[(floor+i*3.414,3.414,16-i*.38,-111+4*i,126-11*i) for i in range(6)]
    for level,(z,h,half,y0,y1) in enumerate(courses):
        open_gallery=classic and detail=="near" and level in (0,1)
        core=half-2.22 if open_gallery else half
        building("Stepped deck band",z,h if detail=="far" else h-.24,core,y0,y1,3.3,
                 ivory if detail=="far" else windows if not classic else glass)
        if open_gallery:
            for side in (-1,1):
                for y in range(y0+5,y1-4,6):
                    block("Gallery column",deckhouse,(side*(half-.4),y,z),(.19,.19,h-.18),ivory)
                x=side*(half-.32)
                beam("Gallery handrail",deckhouse,(x,y0+4,z+1.07),(x,y1-4,z+1.07),
                     (.078,.078),(.078,.078),ivory,n=4)
                boxes.append(collider("Promenade rail","rail",(x,(y0+y1)/2,z+.56),
                                      (.16,y1-y0-8,1.12)))
        if detail!="far":
            building("Ivory fascia",z+h-.24,.24,half+.32,y0-.20,y1+.20,3.5,ivory)
    if not classic:
        building("Bridge wings",floor+20.48,3.84,17,-102,-70,5,ivory)
    top=courses[-1][0]+courses[-1][1]
    stack_base=floor+13.42 if classic else floor+20.48
    ys=(-43,-14,15) if classic else (-44,-37)
    for i,y in enumerate(ys):
        x=0 if classic else (-5.8 if i==0 else 5.8)
        r=3.4 if classic else 3
        if detail=="far":
            oval("Raked copper funnel",stack_group,[(x,y,stack_base,r,r*1.24),
                  (x,y+1.7,stack_base+11.4,r*.83,r)],copper,n=5)
            oval("Dark funnel crown",stack_group,[(x,y+1.7,stack_base+11.35,r*.86,r),
                  (x,y+1.9,stack_base+12.8,r*.80,r*.93)],rubber,n=5)
        else:
            count=14 if detail=="near" else 10
            oval("Funnel collar",stack_group,[(x,y,stack_base,r*1.17,r*1.42),
                 (x,y,stack_base+1.4,r*1.07,r*1.24)],ivory,n=count)
            oval("Copper rake",stack_group,[(x,y,stack_base+1.39,r,r*1.2),
                 (x,y+1.55,stack_base+11.15,r*.88,r*1.14)],copper,n=count)
            oval("Funnel cowl",stack_group,[(x,y+1.49,stack_base+10.95,r*.94,r*1.18),
                 (x,y+1.87,stack_base+12.62,r*.87,r*1.02)],rubber,n=count)
            if state=="laid_up":
                oval("Secured canvas cover",stack_group,[(x,y+1.91,stack_base+12.59,r*.95,r*1.10),
                     (x,y+2.0,stack_base+12.98,r*.87,r)],teak,n=count)
        boxes.append(collider("Funnel","superstructure",(x,y,stack_base+6.1),(2*r,2.6*r,12.3)))

    for y,z,mh in ((-68,top,13),(63,floor+10.4,13)) if classic else ((-80,top+3.7,8),):
        beam("Mast",stack_group,(0,y,z),(0,y+1.6,z+mh),(.31,.31),(.087,.087),rubber,n=4)
        if detail!="far":
            beam("Yard and signal arm",stack_group,(-4,y+1,z+mh*.71),(4,y+1,z+mh*.71),
                 (.11,.11),(.11,.11),ivory,n=4)
        boxes.append(collider("Mast","superstructure",(0,y+.8,z+mh/2),(.57,1.7,mh)))
    if detail!="far":
        for side in (-1,1):
            for y in range(-64,78,17):
                x=side*(14.35 if classic else 18.05)
                z=floor+3.55 if classic else floor+2.87
                g=group(f"boat_{side}_{y}",boat_group)
                sides=12 if detail=="near" else 10
                oval("Carvel boat",g,[(x,y,z,.8,4.47),(x,y,z+.78,1.34,5.6),
                     (x,y,z+1.57,1.29,5.46)],ivory,n=sides)
                oval("Boat cover",g,[(x,y,z+1.55,1.23,5.2),(x,y,z+1.87,1.01,4.95)],
                     teak if classic else copper,n=sides)
                for d in (-4.14,4.14):
                    beam("Boat davit",g,(x-side*2.02,y+d,z-.42),(x,y+d,z+2.24),
                         (.19,.19),(.131,.131),ivory,n=4)
    if detail=="mid":
        for side in (-1,1):
            for z,y0,y1 in [(floor,-88,102),(floor+3.5,-78,81)]:
                if not classic and z>floor:
                    continue
                x=side*(w-1.18)
                beam("Promenade rail",deckhouse,(x,y0,z+1.08),(x,y1,z+1.08),
                     (.087,.087),(.087,.087),ivory,n=4)
                for y in range(y0,y1,6):
                    block("Rail post",deckhouse,(x,y,z),(.11,.11,1.1),ivory)
    if detail=="near":
        for side in (-1,1):
            x=side*(w-.51)
            for z,y0,y1,bx in [(floor,-84,-32,x),(floor,-26,102,x),
                               (floor+3.79,-72,77,side*13.4)]:
                for h in (.55,1.10):
                    beam("Open promenade rail",deckhouse,(bx,y0,z+h),(bx,y1,z+h),
                         (.072,.072),(.072,.072),ivory,n=4)
                    boxes.append(collider("Rail line","rail",(bx,(y0+y1)/2,z+1.06/2),
                                          (.14,y1-y0,1.06)))
                for y in range(y0,y1,3):
                    block("Guardrail post",deckhouse,(bx,y,z),(.115,.115,1.11),ivory)
            for y in (-81,-71,-61,83,93,103):
                block("Boat deck port",hull,(side*16.55,y,floor-4.84),(.22,1.9,.63),glass)
            for y in (-106,113):
                for xx in (side*2.7,side*5.9):
                    oval("Mooring head",deckhouse,[(xx,y,floor,.52,.52),
                         (xx,y,floor+.71,.36,.36),(xx,y,floor+.76,.62,.61)],rubber,n=8)
            direction=(side*.43,.902)
            out=(side*.902,-.43)
            if side<0:
                direction=tuple(-v for v in direction)
            ink(hull,"Bow name","COPPERWAKE",(side*9.95,-103,13.9),direction,out,.1552,ivory,.045)
        block("Aft pool deck",deckhouse,(0,83,floor+7.26),(11.5,17.8,.09),teak)
        block("Aft pool coaming",deckhouse,(0,83,floor+7.35),(7.5,10.3,.41),verde)
        block("Aft pool water",deckhouse,(0,83,floor+7.76),(6.8,9.6,.04),water)
        for x in (-3.55,3.55):
            block("Pool rail",deckhouse,(x,83,floor+7.76),(.29,10.1,.18),ivory)
        for y in (78,88):
            block("Pool rail",deckhouse,(0,y,floor+7.76),(6.82,.29,.18),ivory)
        for x in (-14.8,14.8):
            name="gangway_port" if x<0 else "gangway_starboard"
            anchors.append(marker(root,name,(x,-29,floor+.09),(-1 if x<0 else 1,0,0)))
        anchors.extend([marker(root,"mooring_bow",(3,-106,floor+.09)),
                        marker(root,"mooring_stern",(-3,113,floor+.09)),
                        marker(root,"captain_spawn",(0,-59,top-2.4)),
                        marker(root,"arriving_in_port",(58,-95,25))])
        for room_key, y in (("ss-grand-staircase",-24),("ss-first-class-dining",72),("ss-smoking-room",12)):
            anchors.append(marker(root,"visit_"+room_key.replace("-","_"),(-14.65,y,floor+.09)))
        for x in (-3.55,3.55):
            boxes.append(collider("Aft pool long edge","pool",(x,83,floor+7.7),(.29,10.1,.63)))
        for y in (78,88):
            boxes.append(collider("Aft pool end edge","pool",(0,y,floor+7.7),(6.82,.29,.63)))
        boxes.append(collider("Aft pool water surface","pool",(0,83,floor+7.78),(6.8,9.6,.04)))
    if detail!="far":
        share_translated([ob for ob in root.children_recursive if ob.type=="MESH" and
            (ob.name.startswith(("Rail post","Guardrail post","Gallery column")) or
             (ob.parent and ob.parent.name.startswith("boat_")))])
    for parent in (hull,deckhouse,stack_group,*boat_group.children):
        batch(parent)
    triangles=sum(len(f.vertices)-2 for ob in root.children_recursive if ob.type=="MESH" for f in ob.data.polygons)
    count=len({m.name for ob in root.children_recursive if ob.type=="MESH" for m in ob.data.materials})
    assert count<=12
    assert (triangles<400 if detail=="far" else 3000<=triangles<=5000 if detail=="mid"
            else triangles<=30000),(key,triangles)
    export_glb(ROOT/f"{key}.glb")
    if detail=="near":
        save_source(ROOT/f"{key}.blend")
    stats[key]={"triangles":triangles,"materials":count,"length_m":length,"waterline_above_keel_m":waterline,
        "era":"1979 onward, laid up" if state=="laid_up" else "1952–1978" if classic else "1996 onward",
        "anchors":anchors,"collision_boxes_gltf_m":boxes if detail=="near" else [],
        "axes":"Y up, bow +Z, origin amidships at keel"}

for classic,state,detail in [(True,"active","near"),(True,"laid_up","near"),
                             (False,"active","mid"),(False,"active","far"),
                             (True,"active","mid"),(True,"active","far"),(True,"laid_up","mid"),(True,"laid_up","far")]:
    liner(classic,state,detail)
(ROOT/"liner-model-info.json").write_text(json.dumps(stats,indent=2)+"\n")
