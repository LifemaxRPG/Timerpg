"""Create 13 palm silhouettes with shared physical-scale bark and foliage atlases."""
import json
import sys
from math import cos,pi,sin,sqrt,ceil
from pathlib import Path
from random import Random

import bpy
from mathutils import Vector as V

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from revision_common import batch,export_glb,save_source
from sculpture_tools import T,loft,material,mesh,group
from species import COCONUT,PALMS

bpy.ops.wm.read_factory_settings(use_empty=True)
bark=material("wall_palm_bark","palm-bark")
green=material("detail_palm_leaves","palm-leaves")
up=V((0,0,1))
near_objects=[]
records=[]


def tint(ob,variant,vertical=False):
    layer=ob.data.uv_layers.active.data
    ox,oy=(variant%2)*.5+.25,(1-variant//2)*.5+.25
    for face in ob.data.polygons:
        points=[ob.data.vertices[ob.data.loops[i].vertex_index].co for i in face.loop_indices]
        center=sum(points,V((0,0,0)))/len(points)
        u=up.cross(face.normal) if vertical else points[1]-points[0]
        if u.length<.0001:
            u=points[1]-points[0]
        u.normalize()
        v=face.normal.cross(u).normalized()
        extent=max((abs((p-center).dot(v)) for p in points),default=0)/(256*T)
        shift=0
        if vertical:
            # Align the palm's horizontal scars in metre bands along each trunk.
            shift=((center.z/(256*T)+.25)%.5)-.25
            shift=max(-.238+extent,min(.238-extent,shift))
        for k,p in zip(face.loop_indices,points):
            delta=p-center
            layer[k].uv=(ox+delta.dot(u)/(256*T),oy+shift+delta.dot(v)/(256*T))


def sheet(name,parent,points,faces,variant,skin=green):
    ob=mesh(name,parent,points,faces,skin)
    tint(ob,variant,skin==bark)
    return ob


def tube(name,parent,rings,sides,variant,skin):
    points=[[(x+cos(2*pi*i/sides)*r,y+sin(2*pi*i/sides)*r,z)
             for i in range(sides)] for x,y,z,r in rings]
    ob=loft(name,parent,points,skin)
    tint(ob,variant,skin==bark)
    return ob


def stem(spec,parent,base,scale,direction,detail):
    h=spec.height*scale
    r=spec.radius*sqrt(scale)
    x,y=base
    sway=V((cos(direction),sin(direction),0))*spec.lean*sqrt(scale)
    def center(t):
        if spec.trunk=="creeping":
            lean=t*.72
        else:
            lean=t*t
        return V((x,y,h*t))+sway*lean

    # Short bark segments preserve the physical UV repeat even on the tallest forms.
    sections=max(2 if len(spec.stems)>1 and not detail else 3,ceil(h/(1.7 if detail else 2.90)))
    fs=[0,.034]+[i/sections for i in range(1,sections)]+[.94,1]
    if not detail:
        fs=[0]+[i/sections for i in range(1,sections)]+[1]
    rings=[]
    for t in fs:
        p=center(t)
        flare=1+.26*max(0,1-t/.085)
        if spec.trunk=="crownshaft":
            flare+=.20*sin(pi*t)*sin(pi*t)
        diameter=r*(1-.26*t)*flare
        rings.append((*p,diameter))
    tube("Faceted trunk",parent,rings,8 if detail else 5,spec.bark,bark)
    head=center(1)
    if spec.trunk=="crownshaft":
        height=h*.115
        tube("Smooth green crownshaft",parent,[(*head,r*.80),
             (head.x+sway.x*.035,head.y+sway.y*.035,head.z+height*.53,r*.71),
             (head.x+sway.x*.06,head.y+sway.y*.06,head.z+height,r*.53)],
             8 if detail else 5,spec.color,green)
        head+=V((sway.x*.06,sway.y*.06,height))
    elif detail and spec.trunk in ("pineapple","boots","heavy-skirt","fiber","date","skirt"):
        tier_height=min(1.06,h*.23)*(1.25 if spec.trunk in ("pineapple","heavy-skirt") else .75)
        tier_width=r*(1.50 if spec.trunk=="pineapple" else 1.13)
        top_tube=[(head.x,head.y,h-tier_height,r*.73),
                  (head.x,head.y,h-tier_height*.44,tier_width),
                  (head.x,head.y,h+.06,tier_width*.64)]
        tube("Old leaf bases",parent,top_tube,8 if detail else 5,spec.bark,bark)
        if detail and spec.trunk in ("heavy-skirt","skirt","pineapple"):
            count=14 if spec.trunk=="heavy-skirt" else 9
            for i in range(count):
                a=i*2*pi/count+.20
                d=V((cos(a),sin(a),0))
                normal=up.cross(d)
                length=tier_height*(.82+i%3*.26)*(1.42 if spec.trunk=="heavy-skirt" else 1)
                p=head+d*r*.58
                side=normal*r*.24
                tip=p+d*r*.48-up*length
                sheet("Persistent dry leaf bases",parent,
                      [p-side,p+side,tip+side*.16,tip-side*.12],[(0,1,2,3)],3)
    if detail:
        for i in range(5):
            a=i*2*pi/5+.30
            d=V((cos(a),sin(a),0))
            lateral=up.cross(d)
            p=V((x,y,0))
            sheet("Trunk root flare",parent,[p+d*(r*1.48),p+d*r*.77+lateral*r*.36,
                                            p+d*r*.91+up*r*.62,p+d*r*.77-lateral*r*.36],
                  [(0,1,2),(0,2,3)],spec.bark,bark)
    return head


def feather(spec,parent,start,l,angle,rise,drop,variant,detail,compact,rng):
    d=V((cos(angle),sin(angle),0))
    cross=up.cross(d)
    sway=rng.uniform(-.32,.32)
    def arc(t):
        return start+d*(l*t)+cross*(sway*l*t*t*.31)+up*l*(rise*sin(pi*.79*t)+drop*t*t)
    ts=(0,.28,.55,.79,1) if detail else (0,.33,.68,1)
    rs=(.035,.060,.044,.022,.007) if detail else (.041,l*.132,l*.115,.008)
    points=[arc(t)+cross*(s*w) for t,w in zip(ts,rs) for s in (-1,1)]
    sheet("Living rachis" if detail else "Distance feather frond",parent,points,
          [(2*i,2*i+2,2*i+3,2*i+1) for i in range(len(ts)-1)],variant)
    if not detail:
        return
    count=6 if compact else 9
    for j in range(count):
        t=.16+j*.74/count
        width=l*(.19 if spec.kind!="foxtail" else .225)*sin(pi*(.17+.66*t))*(1-.20*t)
        for s in (-1,1):
            k=t+(.013 if s<0 else 0)
            a=arc(k-.037)
            b=arc(k+.037)
            c=arc(k+.087)+cross*(s*width)+up*(-width*(.25 if spec.kind!="foxtail" else .47))
            fold=arc(k+.043)+cross*(s*width*.57)+up*(width*(.05 if spec.kind!="foxtail" else .32*(-1)**j))
            sheet("Folded leaflet",parent,[a,b,c,fold],[(0,1,3),(1,2,3),(2,0,3)],
                  2 if spec.color==2 else variant)


def fan(spec,parent,start,l,angle,rise,drop,variant,detail,compact,rng):
    d=V((cos(angle),sin(angle),0))
    cross=up.cross(d)
    stalk=start+d*l*.40+up*l*(rise*.64+drop*.20)
    mid=start.lerp(stalk,.52)-up*l*.058
    centers,widths=((start,mid,stalk),(.037,.032,.021)) if detail else ((start,stalk),(.037,.021))
    point=[p+cross*(s*w) for p,w in zip(centers,widths) for s in (-1,1)]
    sheet("Leaf stalk",parent,point,[(0,2,3,1),(2,4,5,3)] if detail else [(0,2,3,1)],variant)
    direction=(d+up*(rise+drop*1.35)).normalized()
    cross=(cross+up*rng.uniform(-.40,.40)).normalized()
    normal=direction.cross(cross)
    rays=12 if detail and not compact else 9 if detail else 3
    radius=l*.67
    for i in range(rays):
        a=(i+.5)/rays
        sweep=(a-.5)*(2.65 if spec.kind=="sabal" else 2.90)
        f=(direction*cos(sweep)+cross*sin(sweep)).normalized()
        k=(cross*cos(sweep)-direction*sin(sweep)).normalized()
        tip=stalk+f*radius*(.87+.13*cos(sweep))
        if spec.kind=="sabal":
            tip-=up*l*(.14+.09*abs(2*a-1))
        w=l*(.046 if detail else .185)*(1.23 if compact else 1)
        shoulder=stalk.lerp(tip,.70)
        fold=shoulder+normal*w*.45
        sheet("Pleated fan segment",parent,[stalk,shoulder-k*w,tip,shoulder+k*w,fold],
              [(0,1,4),(1,2,4),(2,3,4),(3,0,4)] if detail else [(0,1,2),(0,2,3)],
              variant)


def shape(spec,detail):
    name=spec.key if detail else spec.key+"-mid"
    root=group(name)
    trunks=group("trunk_"+name,root)
    foliage=group("crown_"+name,root)
    multi=len(spec.stems)>1
    for n,(x,y,scale,direction) in enumerate(spec.stems):
        head=stem(spec,trunks,(x,y),scale,direction,detail)
        fan_type=spec.kind in ("fan","sabal")
        factory=fan if fan_type else feather
        count= (8 if n==0 else 5) if multi and fan_type else (6 if n==0 else 4) if multi else 14 if not fan_type else 13
        for j in range(count):
            if not detail and (j%4==3 or multi and j%2==1):
                continue
            rng=Random(409+len(spec.key)*23+n*503+j*73)
            angle=direction+j*2.39996323+rng.uniform(-.13,.13)
            tier=j%3
            span=spec.span*sqrt(scale)*rng.uniform(.86,1.09)
            rise=(.10,.27,.56)[tier]
            drop=(-.53,-.22,.07)[tier]
            if spec.key=="queen":
                drop-=.15
            if spec.key in ("foxtail","royal","areca"):
                rise+=.095
            if spec.key=="windmill":
                drop+=.19
            if spec.key=="saw-palmetto":
                rise+=.10
                drop+=.18
            if tier==2:
                span*=.87
            start=head+V((cos(angle)*spec.radius*.42,sin(angle)*spec.radius*.42,(tier-1)*spec.radius*.48))
            tone=spec.color
            if spec.color!=2 and tier==2:
                tone=1
            factory(spec,foliage,start,span,angle,rise,drop,tone,detail,multi,rng)
    for ob in (trunks,foliage):
        batch(ob)
    meshes=[ob for ob in root.children_recursive if ob.type=="MESH"]
    coords=[v.co for ob in meshes for v in ob.data.vertices]
    low=[min(v[i] for v in coords) for i in range(3)]
    high=[max(v[i] for v in coords) for i in range(3)]
    triangles=sum(len(f.vertices)-2 for ob in meshes for f in ob.data.polygons)
    assert triangles<=(1350 if detail else 190),(name,triangles)
    out=ROOT/"glb"/(name+".glb")
    export_glb(out,[root,*root.children_recursive])
    record={"file":f"glb/{name}.glb","triangles":triangles,
            "min_gltf_m":[round(low[0],4),round(low[2],4),round(-high[1],4)],
            "max_gltf_m":[round(high[0],4),round(high[2],4),round(-low[1],4)]}
    if detail:
        near_objects.append(root)
    else:
        for obj in reversed(root.children_recursive):
            bpy.data.objects.remove(obj,do_unlink=True)
        bpy.data.objects.remove(root,do_unlink=True)
    return record


(ROOT/"glb").mkdir(exist_ok=True)
for spec in PALMS:
    records.append({"id":spec.key,"name":spec.name,"near":shape(spec,True),"mid":shape(spec,False),
         "collision":{"anchor":"trunk base","radius_m":max(.18,spec.radius*1.26),
                      "height_m":round(min(2.08,spec.height),2)}})
for i,obj in enumerate(near_objects):
    obj.location=(i%5*12,i//5*12,0)
save_source(ROOT/"palm-collection.blend")
(ROOT/"model-info.json").write_text(json.dumps({
    "name":"Timerpg landscape palm collection","scope":"Common mainland US landscape palms",
    "units":"glTF metres, Y up, root on terrain at the planting point",
    "texel_size_m":T,"texels_per_art_pixel":2,"types":records,"existing_coconut":COCONUT
},indent=2)+"\n")
