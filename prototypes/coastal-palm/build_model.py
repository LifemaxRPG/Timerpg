"""Grow a curved coastal palm with a feathered crown and a smaller distance mesh."""
import json
from pathlib import Path
from math import cos,pi,sin
import sys

import bpy
from mathutils import Vector as V

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from revision_common import batch,export_glb,save_source
from sculpture_tools import T,material,mesh,group,oval
from random import Random


def build(detail):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    root=group("coastal_palm" if detail else "coastal_palm_mid")
    trunk=group("trunk",root)
    crown=group("crown",root)
    bark=material("wall_palm_bark","ringed-bark")
    leaves=material("detail_palm_fronds","frond-atlas")
    profile=[
        (0,0,0,.44,.405),(.010,-.007,.22,.369,.340),
        (.086,-.041,.88,.281,.272),(.204,-.083,2.10,.246,.237),
        (.411,-.101,3.66,.224,.219),(.706,-.056,5.18,.202,.199),
        (.983,.050,6.55,.198,.185),(1.15,.155,7.48,.201,.192),
        (1.196,.212,7.89,.251,.236),(1.209,.231,8.12,.232,.215),
        (1.222,.248,8.30,.134,.138)
    ]
    levels=profile if detail else [profile[i] for i in (0,2,4,6,8,10)]
    oval("Curved ringed trunk",trunk,levels,bark,n=9 if detail else 5)
    if detail:
        for i in range(7):
            a=2*pi*i/7+.19
            u,v=cos(a),sin(a)
            root_points=[(u*.58,v*.58,.008),(u*.30-v*.14,v*.30+u*.14,.025),
                         (u*.36,v*.36,.27),(u*.30+v*.14,v*.30-u*.14,.025)]
            mesh("Root flare",trunk,root_points,[(0,1,2),(0,2,3)],bark)

    def tint(ob,variant):
        # Keep each face at the shared physical texel scale inside its atlas quadrant.
        layer=ob.data.uv_layers.active.data
        cx,cy=((variant%2)*.5+.25,(1-variant//2)*.5+.25)
        for face in ob.data.polygons:
            p=[ob.data.vertices[ob.data.loops[i].vertex_index].co for i in face.loop_indices]
            center=sum(p,V((0,0,0)))/len(p)
            longest=max((p[(i+1)%len(p)]-a for i,a in enumerate(p)),key=lambda e:e.length)
            u=longest.normalized()
            v=face.normal.cross(u).normalized()
            for offset,co in zip(face.loop_indices,p):
                delta=co-center
                layer[offset].uv=(cx+delta.dot(u)/(256*T),cy+delta.dot(v)/(256*T))

    def surface(label,points,faces,variant):
        ob=mesh(label,crown,points,faces,leaves)
        tint(ob,variant)

    # Frond tips sit on three staggered growth tiers. Low fronds hang down,
    # exposed leaves arch outward, and new growth lifts above the crown.
    layouts=[
        (5,3.85,.48,-1.64,7.83,.10),
        (6,4.21,.90,-.64,8.01,.64),
        (4,3.31,1.35,.66,8.17,1.04)
    ]
    for tier,(number,length,rise,fall,z,phase) in enumerate(layouts):
        for j in range(number):
            rng=Random(1406+tier*103+j*41)
            direction=phase+2*pi*j/number+rng.uniform(-.15,.15)
            radial=V((cos(direction),sin(direction),0))
            sideways=V((-sin(direction),cos(direction),0))
            root_pos=V((1.205,.225,z))+radial*.162
            l=length*rng.uniform(.856,1.08)
            lift=rise*rng.uniform(.81,1.15)
            drop=fall*rng.uniform(.81,1.12)
            sway=rng.uniform(-.47,.44)
            shade=2 if tier==0 else 1 if tier==2 or j%3==0 else 0

            def spine(t):
                return root_pos+radial*(l*t*.995)+sideways*(sway*t*t)+V(
                    (0,0,lift*sin(pi*t*.82)+drop*t*t-.20*t**4))

            if not detail:
                if tier==0 and j==4:
                    continue
                # Each distance frond keeps the arch and droop with a single
                # lightly folded ribbon. It shares the near model's silhouette.
                ts=(0,.24,.52,.76,1)
                widths=(.033,.365,.50,.31,.013)
                points=[spine(t)+sideways*(sign*w) for t,w in zip(ts,widths) for sign in (-1,1)]
                surface("Distance frond",points,[(2*i,2*i+2,2*i+3,2*i+1) for i in range(4)],shade)
                continue

            # Trapezoid rachis, visibly tapering in both silhouette and thickness.
            ts=(0,.18,.4,.63,.81,1)
            points=[spine(t)+sideways*(s*(.062*(1-t)+.009)) for t in ts for s in (-1,1)]
            surface("Tapered rachis",points,[(2*i,2*i+2,2*i+3,2*i+1) for i in range(5)],
                    1 if tier==2 else shade)

            # Each pair of bladelets folds toward the midrib before falling away.
            count=8
            for i in range(count):
                t=.18+i*(.74/count)
                profile=sin(pi*(.1+.75*t))*(1.0-.20*t)
                width=(.87 if tier<2 else .72)*profile*rng.uniform(.87,1.12)
                for side in (-1,1):
                    ti=t+(.013 if side<0 else 0)
                    a=spine(ti-.036)
                    b=spine(ti+.043)+radial*.028
                    tip=spine(ti+.092)+sideways*(side*width)+V((0,0,-.22-.10*t if tier<2 else -.10))
                    fold=spine(ti+.051)+sideways*(side*width*.58)+V((0,0,.041))
                    surface("Folded palm leaflet",[a,b,tip,fold],[(0,1,3),(1,2,3),(2,0,3)],
                            2 if tier==0 and i%3==0 else shade)

    if detail:
        for i in range(4):
            a=i*pi/2+.36
            u,v=cos(a),sin(a)
            root_pos=V((1.21,.22,7.85))
            p1=root_pos+V((u*.44,v*.44,-.32))
            p2=root_pos+V((u*.61,v*.61,-.95))
            p3=root_pos+V((u*.71,v*.71,-1.47))
            side=V((-v,u,0))
            surface("Old bronze skirt",[root_pos,p1+side*.101,p1-side*.101,p2+side*.095,
                       p2-side*.095,p3],[(0,1,2),(1,3,4,2),(3,5,4)],3)
        for i in range(3):
            a=i*(2*pi/3)+.36
            x,y=1.208+.208*cos(a),.226+.207*sin(a)
            fruit=oval("Immature coconut",crown,[(x,y,7.77,.064,.07),
                 (x,y,7.91,.135,.118),(x-.028,y,8.026,.097,.087)],leaves,n=6)
            tint(fruit,1)

    for group_node in (trunk,crown):
        batch(group_node)
    meshes=[o for o in root.children_recursive if o.type=="MESH"]
    count=sum(len(f.vertices)-2 for o in meshes for f in o.data.polygons)
    mins=[round(min(v.co[i] for o in meshes for v in o.data.vertices),4) for i in range(3)]
    maxs=[round(max(v.co[i] for o in meshes for v in o.data.vertices),4) for i in range(3)]
    assert count<=(1200 if detail else 190),count
    key="coastal-palm" if detail else "coastal-palm-mid"
    export_glb(ROOT/f"{key}.glb")
    if detail:
        save_source(ROOT/"coastal-palm.blend")
    return {"file":f"{key}.glb","triangles":count,
            "min_gltf_m":[mins[0],mins[2],-maxs[1]],"max_gltf_m":[maxs[0],maxs[2],-mins[1]]}


near=build(True)
mid=build(False)
info={"name":"Coastal Palm","archetype":"Mature feather palm with a curved trunk","near":near,"mid":mid,
      "units":"glTF metres, Y up, base center at terrain level",
      "collision":{"boxes_blender_z_up":[{"name":"Trunk lower reach","group":"tree","attachment":"trunk",
                  "center":[.075,-.04,1.04],"size":[.92,.86,2.08]}]},
      "texel_size_m":T,"texels_per_art_pixel":2}
(ROOT/"model-info.json").write_text(json.dumps(info,indent=2)+"\n")
