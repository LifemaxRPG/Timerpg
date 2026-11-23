"""Assemble C011 production meshes and metadata with physical-scale UVs."""
from math import cos, pi, sin
from pathlib import Path
import json
import sys

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from designs import DESIGNS
from revision_common import batch, emitter, export_glb, save_source

T=.0388
ATLAS=json.loads((ROOT/"textures/regions.json").read_text())
PBR="Principled BSDF"


def bl(point):
    """Convert glTF-oriented working coordinates to Blender coordinates."""
    x,y,z=point
    return Vector((x,-z,y))


def node(name,parent=None,point=(0,0,0)):
    obj=bpy.data.objects.new(name,None)
    bpy.context.collection.objects.link(obj)
    obj.parent=parent
    obj.location=bl(point)
    return obj


def material(name,texture,alpha=False):
    m=bpy.data.materials.new(name)
    m.use_nodes=True
    shader=m.node_tree.nodes[PBR]
    shader.inputs["Roughness"].default_value=.88 if alpha else .79
    shader.inputs["Specular IOR Level"].default_value=.19
    tex=m.node_tree.nodes.new("ShaderNodeTexImage")
    tex.image=bpy.data.images.load(str(ROOT/"textures"/f"{texture}.png"),check_existing=True)
    tex.image.pack()
    tex.interpolation="Closest"
    tex.extension="REPEAT"
    m.node_tree.links.new(tex.outputs["Color"],shader.inputs["Base Color"])
    if alpha:
        m.node_tree.links.new(tex.outputs["Alpha"],shader.inputs["Alpha"])
        m.surface_render_method="BLENDED"
    return m


def surface(name,parent,points,faces,mats,zone=None):
    """Keep every planar UV orthonormal, splitting bent quads at the fold."""
    coords=[bl(p) for p in points]
    if not isinstance(mats,(list,tuple)):
        mats=[mats]*len(faces)
    if not isinstance(zone,(list,tuple)):
        zone=[zone]*len(faces)
    polys,materials,regions=[],[],[]
    for face,mat,region in zip(faces,mats,zone):
        p,q,r=[coords[i] for i in face[:3]]
        n=(q-p).cross(r-p).normalized()
        pieces=[face]
        if len(face)>3 and any(abs(n.dot(coords[i]-p))>3.e-6 for i in face[3:]):
            pieces=[(face[0],face[i],face[i+1]) for i in range(1,len(face)-1)]
        for piece in pieces:
            polys.append(piece)
            materials.append(mat)
            regions.append(region)
    data=bpy.data.meshes.new(name)
    data.from_pydata(coords,[],polys)
    data.update()
    obj=bpy.data.objects.new(name,data)
    bpy.context.collection.objects.link(obj)
    obj.parent=parent
    slots=list(dict.fromkeys(materials))
    for slot in slots:
        data.materials.append(slot)
    uv=data.uv_layers.new(name="One texel per 0.0388 m")
    center=sum(coords,Vector((0,0,0)))/len(coords)
    for poly,mat,region in zip(data.polygons,materials,regions):
        poly.material_index=slots.index(mat)
        image=next(n.image for n in mat.node_tree.nodes if n.type=="TEX_IMAGE")
        if region and mat.name=="glow_lamps":
            x,y=ATLAS["lamps"][region]
        else:
            x,y=ATLAS["finish"].get(region,(0,0))
        n=poly.normal
        u=(Vector((0,0,1)).cross(n) if abs(n.z)<.95 else Vector((0,1,0)).cross(n)).normalized()
        v=n.cross(u)
        if region in ("dash","badge","grille") and u.x<-.9:
            u=-u
            v=-v
        for i in poly.loop_indices:
            p=coords[data.loops[i].vertex_index]-(center if region else Vector((0,0,0)))
            uv.data[i].uv=((p.dot(u)/T+x)/image.size[0],
                           (p.dot(v)/T+(image.size[1]-y if region else 0))/image.size[1])
    return obj


def loft(name,parent,rings,mat,zone=None,cap=True,skip=()):
    n=len(rings[0])
    faces=[]
    for k in range(len(rings)-1):
        for i in range(n):
            if i not in skip:
                j=(i+1)%n
                faces.append((k*n+i,k*n+j,(k+1)*n+j,(k+1)*n+i))
    if cap:
        faces.extend((tuple(range(n-1,-1,-1)),tuple(range((len(rings)-1)*n,len(rings)*n))))
    return surface(name,parent,sum(rings,[]),faces,mat,zone)


def box(name,parent,center,size,mat,zone=None):
    cx,cy,cz=center
    w,h,l=[dim/2 for dim in size]
    return loft(name,parent,[[(cx+x*w,cy+y*h,cz+s*l) for x,y in ((-1,-1),(1,-1),(1,1),(-1,1))]
                             for s in (-1,1)],mat,zone)


def rod(name,parent,a,b,r,mat,zone=None,n=6):
    a,b=Vector(a),Vector(b)
    axis=(b-a).normalized()
    ref=Vector((0,1,0)) if abs(axis.y)<.92 else Vector((0,0,1))
    u=ref.cross(axis).normalized()
    v=axis.cross(u)
    rings=[[tuple(p+r*(cos(i*2*pi/n)*u+sin(i*2*pi/n)*v)) for i in range(n)] for p in (a,b)]
    return loft(name,parent,rings,mat,zone)


def car_ring(section,low=None):
    z,w,y,shoulder,crown=section
    bottom=y if low is None else low
    flare=bottom+(shoulder-bottom)*.27
    return [(x,h,z) for x,h in ((-.84*w,bottom),(.84*w,bottom),(w,flare),(w,shoulder),
                               (.82*w,crown),(-.82*w,crown),(-w,shoulder),(-w,flare))]


def build(design,lod):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    near=lod=="near"
    far=lod=="far"
    era=DESIGNS.index(design)
    root=node(design.slug)
    shell=node("body",root)
    slug=design.slug
    paint=material("paint_primary",slug+"-primary")
    accent=material("paint_secondary",slug+"-secondary")
    cabin=material("interior_and_rubber",slug+"-finish")
    silver=material("hardware_and_trim",slug+"-finish")
    glass=material("glass_vehicle",slug+"-glass",True) if not far else None
    lamps=material("glow_lamps",slug+"-lamps") if not far else None
    if lamps:
        emitter(lamps,slug+"-emission",.04)
    w=design.width
    radius=design.radius
    half=design.wheelbase/2
    axle=design.track/2
    sill=design.sill
    belt=design.belt
    roof=design.height
    front_foot,front_top,rear_top,rear_foot=design.glass
    top_y=roof-.10
    side_top=w*(.80 if era==0 else .76)
    side_foot=w*.956
    pivots,lamp_specs,door_sweeps={},{},{}
    paint_fender=accent if era==0 else paint

    def anchor(name,parent,point,axis=None):
        p=node(name,parent,point)
        bpy.context.view_layer.update()
        q=p.matrix_world.translation
        pivots[name]={"center_gltf_m":[round(q.x,6),round(q.z,6),round(-q.y,6)],
                      "parent":parent.name}
        if axis:
            pivots[name]["axis_local_gltf"]=axis
            p["axis_local_gltf"]=axis
        return p

    def badge_plate(parent,x,y,z):
        width,height=.171,.096
        obj=surface("badge_plate",parent,[(x-width/2,y-height/2,z),(x+width/2,y-height/2,z),
                    (x+width/2,y+height/2,z),(x-width/2,y+height/2,z)],
                    [(0,1,2,3)],silver,"badge")
        obj["keep"]=True

    def lens(name,point,kind,facing,big=False):
        holder=anchor(name,root,point)
        lamp_specs[name]={"role":kind,"lens_material":"glow_lamps","facing_gltf":[0,0,facing]}
        if far:
            return
        rx=(.143 if big else .078) if kind=="head" else (.085 if era==2 else .059)
        ry=rx if kind=="head" else (.049 if era==2 else .055)
        n=8 if near else 4
        ring=[(rx*cos(2*i*pi/n+pi/n),ry*sin(2*i*pi/n+pi/n),0) for i in range(n)]
        face=tuple(range(n)) if facing>0 else tuple(range(n-1,-1,-1))
        surface("Lens",holder,ring,[face],lamps,kind)
        if near and kind in ("head","tail"):
            rim1=[(x*1.3,y*1.3,-facing*.100) for x,y,_ in ring]
            rim2=[(x*1.13,y*1.13,-facing*.013) for x,y,_ in ring]
            rim3=[(x,y,-facing*.005) for x,y,_ in ring]
            rings=(rim1,rim2,rim3) if facing>0 else (rim3,rim2,rim1)
            loft("Lamp housing",holder,list(rings),silver,"metal",False)

    rear_deck=design.rear
    if era>0:
        before,after=next((a,b) for a,b in zip(design.rear,design.rear[1:]) if a[0]<=rear_foot<b[0])
        frac=(rear_foot-before[0])/(after[0]-before[0])
        part=tuple(a+frac*(b-a) for a,b in zip(before,after))
        rear_deck=tuple(s for s in design.rear if s[0]<rear_foot)+(part,)

    # The continuous floor stops at sill height. The visible cabin has real door apertures.
    if far:
        bodysections=[(design.rear[0][0],.79*w,.36,.83*belt,.83*belt),
                      (-.75,w,.25,belt,belt),(.83,w,.25,belt,belt),
                      (design.front[-1][0],.79*w,.39,.88*belt,.88*belt)]
        rings=[[(x,y,z) for x,y in ((-s,lo),(s,lo),(s,hi),(-s,hi))]
               for z,s,lo,hi,_ in bodysections]
        loft("Distance body",shell,rings,paint)
        plates=[
            (-side_foot,belt,rear_foot),(side_foot,belt,rear_foot),
            (side_top,roof-.04,rear_top),(-side_top,roof-.04,rear_top),
            (-side_foot,belt,front_foot),(side_foot,belt,front_foot),
            (side_top,roof,front_top),(-side_top,roof,front_top),
        ]
        faces=[(0,4,5,1),(1,5,6,2),(2,6,7,3),(3,7,4,0),(3,2,1,0),(4,5,6,7)]
        surface("Distance roof and dark safety glazing",shell,plates,faces,
                [paint,cabin,accent if era==2 else paint,cabin,cabin,cabin],
                [None,"dark",None,"dark","dark","dark"])
        if era==2:
            for side in (-1,1):
                fin=[(side*x,y,z) for x in (.795,.925) for y,z in
                     ((.89,-2.513),(1.128,-2.43),(.92,-1.91))]
                surface("Distance fins",shell,fin,[(0,1,2),(5,4,3),(0,3,4,1),
                        (1,4,5,2),(2,5,3,0)],paint)
        if era==1:
            for side in (-1,1):
                # A shallow wedge keeps each front pontoon in the far silhouette.
                pts=[(side*x,y,half+d) for x in (.65,.925) for y,d in
                     ((.64,-.47),(.84,0),(.63,.47))]
                surface("Distance fender shoulders",shell,pts,
                        [(0,1,2),(5,4,3),(0,3,4,1),(1,4,5,2),(2,5,3,0)],paint)
    else:
        pan=((-half-.29,.48 if era<2 else .55,.26,sill-.025,sill-.025),
             (-half+.26,w*.94,.268,sill,sill),(.56,w*.96,.26,sill,sill),
             (half-.29,w*.87,.27,sill,sill),(half+.44,.43 if era<2 else .55,.30,sill-.04,sill-.04))
        loft("Pressed rocker pan",shell,[car_ring(s) for s in pan],paint,cap=True)
        for sign in (-1,1):
            box("Black rocker reveal",shell,(sign*w*.954,sill-.072,-.373),(.038,.062,2.01),cabin,"dark")
        for section,name,pivot,enabled in ((design.front,"hood",design.front[0][0],True),
                                            (design.rear,"trunk",rear_foot,era>0)):
            rings=[car_ring(s) for s in section]
            loft(name.title()+" fixed body sides",shell,rings,paint,skip=(4,) if enabled else ())
            if enabled:
                moving=section if name=="hood" else rear_deck
                hinge=anchor(name,root,(0,moving[0 if name=="hood" else -1][-1],pivot),[1,0,0])
                hinge_h=pivots[name]["center_gltf_m"][1]
                pv=[(x,y-hinge_h,z-pivot) for q in moving for x,y,z in car_ring(q)[4:6]]
                faces=[(2*i,2*i+2,2*i+3,2*i+1) for i in range(len(moving)-1)]
                surface(name.title()+" opening panel",hinge,pv,faces,paint)
                if near:
                    # A separate thin rolled edge follows the deck and moves with it.
                    for side in (-1,1):
                        for a,b in zip(pv[::2] if side==1 else pv[1::2],pv[2::2] if side==1 else pv[3::2]):
                            rod("Rolled panel edge",hinge,a,b,.009,paint,n=4)
                if near and name=="hood":
                    box("Cast engine block",shell,(0,sill+.14,1.21),(.44,.32,.66),cabin,"dark")
                    box("Valve cover",shell,(0,sill+.315,1.17),(.39,.075,.49),silver,"metal")
                if near and name=="trunk":
                    box("Luggage well",shell,(0,sill-.037,-1.88),(.94,.035,.49),cabin,"panel")
        # Roof ring. The roof curvature and the window slope are unique to each era.
        roof_rings=[]
        for i,(z,k) in enumerate(((rear_top-.04,.90),(rear_top+.19,1),(front_top-.12,1),(front_top+.022,.89))):
            crown=roof-(.071 if era==1 and i==0 else .032 if era==1 and i==3 else .0)
            rim=side_top*k
            roof_rings.append([(-rim,top_y,z),(rim,top_y,z),(rim*.965,crown-.042,z),
                               (rim*.721,crown,z),(-rim*.721,crown,z),(-rim*.965,crown-.042,z)])
        loft("Stepped roof cap",shell,roof_rings,accent if era==2 else paint)
        for front,bottom_z,top_z in ((True,front_foot,front_top),(False,rear_foot,rear_top)):
            sign=1 if front else -1
            if era==2:
                # The windshield and rear window bend around the outer pillar.
                edge=[
                    ((-side_foot,belt+.015,bottom_z-sign*.197),(-side_top*.97,top_y,top_z-sign*.11)),
                    ((-side_foot*.745,belt+.025,bottom_z),(-side_top*.79,top_y,top_z)),
                    ((side_foot*.745,belt+.025,bottom_z),(side_top*.79,top_y,top_z)),
                    ((side_foot,belt+.015,bottom_z-sign*.197),(side_top*.97,top_y,top_z-sign*.11)),
                ]
            else:
                edge=[((-side_foot*.91,belt+.015,bottom_z),(-side_top*.94,top_y,top_z)),
                      ((side_foot*.91,belt+.015,bottom_z),(side_top*.94,top_y,top_z))]
            for (a,b),(c,d) in zip(edge,edge[1:]):
                surface("Panoramic pane" if era==2 else "Flat safety pane",shell,[a,c,d,b],
                        [(0,1,2,3) if front else (3,2,1,0)],glass)
                rod("Window top seal",shell,b,d,.026,silver,"metal" if era==2 else "dark",4)
                rod("Window bottom seal",shell,a,c,.026,silver,"metal" if era==2 else "dark",4)
            for a,b in (edge[0],edge[-1]):
                rod("Fixed pillar",shell,a,b,.039,accent if era==2 else paint,n=4)
            if near and era==0 and front:
                rod("Split windscreen divider",shell,(0,belt+.013,front_foot+.009),
                    (0,top_y,front_top+.022),.016,silver,"metal",4)

    for side,suffix in ((1,"l"),(-1,"r")):
        for i,(start,end) in enumerate(design.doors):
            name=f"door_{'front' if i==0 else 'rear'}_{suffix}"
            base=(side*w*.984,sill+.023,start)
            door=anchor(name,root,base,[0,1,0])
            door_sweeps[name]=-66*side
            if far:
                continue
            span=start-end
            hh=belt-base[1]
            pts=[(.0,0,-.016),(.0,0,-span+.015),(side*.015,hh,-span+.012),(side*.015,hh,-.012)]
            polys=[(0,1,2,3) if side==1 else (3,2,1,0)]
            surface("Shaped hinged outer door",door,pts,polys,paint)
            liner=[(x-side*.048,y,z) for x,y,z in pts]
            surface("Padded inside door panel",door,liner,[polys[0][::-1]],cabin,"panel")
            for j in range(4):
                k=(j+1)%4
                surface("Door edge",door,[pts[j],pts[k],liner[k],liner[j]],[(0,1,2,3)],paint)
            zA=-.048
            zB=-span+.054
            # The coupe's trailing door glass meets its fixed quarter panel.
            left_top=min(front_top-.012,start-.071)-start if i==0 else zA
            right_top=max(rear_top+.055,end+.06)-start if i==len(design.doors)-1 else zB
            tx=side*(side_top-w*.984)
            top=top_y-base[1]
            pane=[(side*.008,hh,zA),(side*.008,hh,zB),(tx,top,right_top),(tx,top,left_top)]
            surface("Hinged side glass",door,pane,polys,glass)
            for a,b in (((0,3),(3,2),(2,1),(1,0)) if near else ((3,2),)):
                rod("Door sash",door,pane[a],pane[b],.022 if near else .020,
                    silver if era==2 else paint,"metal" if era==2 else None,4)
            if near:
                rod("Exterior door handle",door,(side*.025,hh-.072,zB),(side*.025,hh-.072,zB+.122),
                    .017,silver,"bright",4)
                box("Cabin window crank",door,(-side*.058,hh-.19,zA-.21),(.032,.070,.071),silver,"metal")
            if era==2:
                for a,b in ((.115,.238),(.087,.115)):
                    surface("Chrome-capped two-tone insert",door,
                        [(side*.017,hh-a,-.035),(side*.017,hh-a,-span+.02),
                         (side*.017,hh-b,-span+.02),(side*.017,hh-b,-.028)],polys,
                        accent if a>.10 else silver,None if a>.10 else "metal")
        if far:
            continue
        # B/C pillars remain fixed. Door shells take their upper sashes with them.
        if len(design.doors)==2:
            split=design.doors[0][1]-.013
            rod("Body B-pillar",shell,(side*side_foot,sill,split),(side*side_top,top_y,split),
                .037,accent if era==2 else paint,n=4)
        if era==1:
            z0=design.doors[0][1]-.02
            pts=[(side*side_foot,belt,z0),(side*side_foot,belt,rear_foot+.18),
                 (side*side_top,top_y,rear_top),(side*side_top,top_y,z0)]
            surface("Coupe side quarter glass",shell,pts,[(0,1,2,3) if side==1 else (3,2,1,0)],glass)
            rod("Quarter window edge",shell,pts[1],pts[2],.047,paint,n=4)
        if era==0:
            box("Separate running board",shell,(side*.761,.343,-.10),(.242,.081,1.87),cabin,"rubber")
            if near:
                rod("Board bright edge",shell,(side*.878,.356,-1.018),(side*.878,.356,.82),.018,silver,"bright",4)
        if era==2:
            for one,two in ((1.07,2.16),(-1.45,-2.34)):
                points=[(side*(w-.050),belt-.128,one),(side*(w-.069),belt-.17,two),
                        (side*(w-.069),belt-.28,two),(side*(w-.050),belt-.238,one)]
                surface("Continuation of the pale side spear",shell,points,[(0,1,2,3) if side==1 else (3,2,1,0)],accent)
                if near:
                    rod("Spear chrome cap",shell,points[0],points[1],.013,silver,"bright",4)
            fin=[(side*.863,.995,-1.76),(side*.946,.973,-2.45),(side*.907,1.144,-2.40),
                 (side*.923,.946,-1.78)]
            fin+= [(x-side*.115,y,z) for x,y,z in fin]
            surface("Pressed rear fin",shell,fin,[(0,1,2),(0,2,3),(4,6,5),(4,7,6),
                    (0,4,5,1),(1,5,6,2),(2,6,7,3),(3,7,4,0)],paint)

    # Wheel hubs survive every LOD. The front spin pivots ride below steering pivots.
    def wheel_mesh(parent,n,spokes=False,whitewall=False):
        r=radius
        t=design.tyre
        offset=1 if parent.name.endswith(("fl","rl")) else -1
        circles=((-t/2,.84),(-t/2,1.0),(t/2,1.0),(t/2,.84))
        rings=[[(x,r*k*cos(i*2*pi/n),r*k*sin(i*2*pi/n)) for i in range(n)] for x,k in circles]
        if not near:
            loft("Distance tyre",parent,[rings[1],rings[2]],cabin,"rubber")
            if not far:
                x=offset*(t/2+.004)
                for scale,zone in ((.77,"whitewall" if whitewall else "metal"),(.40,"bright")):
                    cap=[(x,r*scale*cos(i*2*pi/n),r*scale*sin(i*2*pi/n)) for i in range(n)]
                    surface("Distance hubcap",parent,cap,[tuple(range(n)) if offset==1 else tuple(range(n-1,-1,-1))],
                            silver,zone)
                    x+=offset*.004
            return
        loft("Tyre tread and scalloped shoulders",parent,rings,cabin,"rubber")
        def wheel_band(k1,k2,zone):
            out=offset*(t/2+.003)
            p=[(out,r*k*cos(i*2*pi/n),r*k*sin(i*2*pi/n)) for k in (k1,k2) for i in range(n)]
            f=[(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)]
            surface("Wheel side annulus",parent,p,f if offset>0 else [a[::-1] for a in f],silver,zone)
        if whitewall:
            wheel_band(.85,.63,"whitewall")
        if spokes:
            x=offset*(t/2+.013)
            for i in range(n):
                a=i*2*pi/n
                width=.031
                points=[(x,u*cos(a)-v*sin(a),u*sin(a)+v*cos(a))
                        for u,v in ((.048,-width/2),(.309,-width/2),(.309,width/2),(.048,width/2))]
                surface("Lacquered wire spoke",parent,points,[(0,1,2,3)],silver,"bright")
            wheel_band(.86,.775,"metal")
        else:
            wheel_band(.74,.38,"metal")
        x=offset*(t/2+.017)
        rod("Pressed wheel hub",parent,(x-offset*.028,0,0),(x+offset*.027,0,0),
            r*(.17 if spokes else .38),silver,"bright",n if near else 6)

    for index,z in enumerate((half,-half)):
        for side,suffix in ((1,"l"),(-1,"r")):
            axle_point=(side*axle,radius,z)
            if index==0:
                turn=anchor("steer_f"+suffix,root,axle_point,[0,1,0])
                wheel=anchor("wheel_f"+suffix,turn,(0,0,0),[1,0,0])
            else:
                wheel=anchor("wheel_r"+suffix,root,axle_point,[1,0,0])
            wheel_mesh(wheel,(10 if era==2 else 12) if near else 6,era==0 and near,era==2 and not far)
            if far:
                continue
            # A low-sided saddle wraps the wheel, leaving the open arch visible.
            rings=[]
            n=(9 if era==2 else 10) if near else 4
            for k in range(n+1):
                a=pi*k/n
                length=radius+(.205 if era==1 else .145)
                y=radius+sin(a)*(radius+.097)
                forward=z+cos(a)*length
                x_outer=axle+design.tyre/2+.109*sin(a)+.029
                x_inner=axle-design.tyre/2-.061
                if era==1:
                    x_outer+=.046*sin(a)
                    y+=.048*sin(a)
                if era==2:
                    x_outer=max(x_outer,w*.994)
                ring=[(side*x_inner,y+.009,forward),(side*((x_outer+x_inner)/2),y+.119,forward),
                      (side*x_outer,y+.037,forward),(side*(x_outer+.004),y-.064,forward),
                      (side*(x_outer-.057),y-.069,forward),(side*x_inner,y-.021,forward)]
                if not near:
                    ring=[ring[j] for j in (0,1,2,5)]
                rings.append(ring if side>0 else ring[::-1])
            loft("Separate arch fender" if era==0 else "Pressed pontoon fender",shell,rings,paint_fender,cap=True)

    if not far:
        # Open coachwork is furnished with period-specific seats, pedals, dash and column.
        if near:
            box("Textured cabin floor",shell,(0,sill+.018,-.38),(w*1.75,.033,2.14),cabin,"rubber")
        driver_height=sill+.132
        for i,(x,z) in enumerate(((.346,-.204),(-.346,-.204),(.336,-1.147),(-.336,-1.147))):
            name=("seat_driver","seat_passenger","seat_rear_left","seat_rear_right")[i]
            anchor(name,root,(x,driver_height+.059,z))
        if not near:
            box("Simplified bench",shell,(0,driver_height,-.22),(w*1.65,.18,.61),cabin,"fabric")
        elif era==1:
            for x in (-.35,.35):
                box("Coupe front bucket",shell,(x,driver_height,-.18),(.57,.168,.552),cabin,"fabric")
                box("Coupe tuck-roll backrest",shell,(x,driver_height+.245,-.443),(.54,.45,.13),cabin,"fabric")
        else:
            box("Front bench cushion",shell,(0,driver_height,-.215),(w*1.70,.157,.53),cabin,"fabric")
            box("Front bench back",shell,(0,driver_height+.235,-.47),(w*1.67,.482,.137),cabin,"fabric")
        if near:
            box("Rear bench cushion",shell,(0,driver_height,-1.153),(w*1.61,.156,.44),cabin,"fabric")
            box("Rear bench back",shell,(0,driver_height+.186,-1.391),(w*1.59,.39,.126),cabin,"fabric")
        box("Period dash",shell,(0,belt-.080,front_foot-.064),(w*1.74,.148,.178),cabin,"wood" if era==0 else "panel")
        box("Pixel gauge cluster",shell,(.311,belt-.035,front_foot-.176),(.463,.152,.036),cabin,"dash")
        hub=(.34,belt-.033,front_foot-.364)
        steering=anchor("steering_wheel",root,hub,[0,0,1])
        n=12 if near else 5
        r=.179 if era!=1 else .166
        ring=[[((r+d*cos(k*pi/2))*cos(i*2*pi/n),(r+d*cos(k*pi/2))*sin(i*2*pi/n),d*sin(k*pi/2))
               for k in range(4)] for i in range(n) for d in (.018,)]
        p=sum(ring,[])
        f=[(4*i+k,4*((i+1)%n)+k,4*((i+1)%n)+(k+1)%4,4*i+(k+1)%4)
           for i in range(n) for k in range(4)]
        surface("Steering wheel rim",steering,p,f,cabin,"dark")
        if near:
            for a in (pi/2,7*pi/6,11*pi/6):
                rod("Wheel spoke",steering,(0,0,0),(r*cos(a),r*sin(a),0),.014,silver,"metal",4)
            rod("Steering column",shell,hub,(hub[0],hub[1]-.17,front_foot-.041),.027,cabin,"dark",6)
            rod("Gearshift",shell,(.035,sill+.05,.175),(.045,sill+.30,.092),.015,silver,"metal",4)
        for x,z in ((.33,.38),(.46,.33)):
            if near:
                box("Pedal",shell,(x,sill+.044,z),(.084,.031,.128),cabin,"rubber")
    else:
        for i,(x,z) in enumerate(((.346,-.204),(-.346,-.204),(.336,-1.147),(-.336,-1.147))):
            name=("seat_driver","seat_passenger","seat_rear_left","seat_rear_right")[i]
            anchor(name,root,(x,sill+.191,z))
        anchor("steering_wheel",root,(.34,belt-.033,front_foot-.364),[0,0,1])
        for name,z,height in (("hood",design.front[0][0],design.front[0][-1]),
                              ("trunk",rear_foot,rear_deck[-1][-1])):
            if name!="trunk" or era>0:
                anchor(name,root,(0,height,z),[1,0,0])
    anchor("exit_left",root,(w+.57,0,.014))
    anchor("exit_right",root,(-w-.57,0,.014))

    # A face-specific lamp material and an individually addressable lens on every node.
    fz=design.front[-1][0]+.017
    rz=(-2.019,-2.325,-2.578)[era]
    if not far:
        gx=(.32,.25,.59)[era]
        y0,y1=((.65,1.123),(.52,1.031),(.57,.846))[era]
        surface("Period grille",shell,[(-gx,y0,fz),(gx,y0,fz),(gx*.95,y1,fz-.005),(-gx*.95,y1,fz-.005)],
                [(0,1,2,3)],silver,"grille")
        if near:
            for x in (-gx,gx):
                rod("Grille surround",shell,(x,y0,fz),(x*.95,y1,fz-.004),.025,
                    silver,"bright",4)
            if era==0:
                rod("Small radiator mascot",shell,(0,1.112,1.78),(0,1.235,1.78),.020,silver,"bright",6)
            badge_plate(shell,0,y1-.057,fz+.012)
        for front,fleet in ((True,1), (False,-1)):
            z=(fz if front else rz)+fleet*(.073 if era==0 else .061)
            if era==2:
                box("Broad chrome bumper",shell,(0,.471,z),(1.614,.143,.176),silver,"metal")
                for x in (-.70,.70):
                    box("Capped bumper wrap",shell,(x,.47,z-fleet*.047),(.221,.131,.205),silver,"bright")
            else:
                box("Period bumper",shell,(0,.453,z),(w*1.56,.087,.13),silver,"metal")
                if near:
                    for x in (-.367,.367):
                        box("Bumper bracket",shell,(x,.497,z-fleet*.025),(.071,.181,.092),silver,"bright")
    for side,suffix in ((1,"l"),(-1,"r")):
        hx=(.609,.662,.704)[era]*side
        hy=(.93,.86,.856)[era]
        if near and era<2:
            rod("Headlamp mounting arm",shell,(hx,hy-.23,fz-.14),(hx,hy,fz-.027),.039,silver,"metal",4)
        if near and era==2:
            box("Tail fin lamp socket",shell,(.805*side,.900,rz+.121),(.225,.173,.22),silver,"metal")
        lens("light_head_"+suffix,(hx,hy,fz+.033),"head",1,True)
        tx=(.578,.698,.805)[era]*side
        ty=(.734,.722,.939)[era]
        lens("light_tail_"+suffix,(tx,ty,rz-.016),"tail",-1)
        lens("light_brake_"+suffix,(tx,ty-.109,rz-.021),"brake",-1)
        if era>0:
            lens("light_turn_front_"+suffix,(hx,hy-.295,fz-.004),"turn",1)
            lens("light_turn_rear_"+suffix,(tx+side*.083,ty-.078,rz-.02),"turn",-1)
        if era==2:
            lens("light_reverse_"+suffix,(side*.48,.681,rz-.013),"reverse",-1)
    if era==0:
        spare=node("rear_spare",shell,(0,.891,-2.121))
        spare.rotation_euler.z=-pi/2
        wheel_mesh(spare,12 if near else 6,near)
        if near:
            box("Spare bracket",shell,(0,.736,-2.072),(.35,.182,.253),cabin,"dark")

    batch(shell)
    scene=bpy.context.scene
    bpy.context.view_layer.update()
    draw=[o for o in scene.objects if o.type=="MESH"]
    triangles=sum(len(p.vertices)-2 for o in draw for p in o.data.polygons)
    minmax=[[],[],[]]
    for o in draw:
        for v in o.data.vertices:
            p=o.matrix_world@v.co
            for i,val in enumerate((p.x,p.z,-p.y)):
                minmax[i].append(val)
    file=slug+("" if near else "-"+lod)
    export_glb(ROOT/(file+".glb"))
    if near:
        save_source(ROOT/(file+".blend"))
    return {"file":file+".glb","lod":lod,"triangles":triangles,
            "bounds_gltf_m":[[round(min(a),6),round(max(a),6)] for a in minmax],
            "materials":sorted({m.name for o in draw for m in o.data.materials}),
            "nodes":pivots,"door_preview_degrees":door_sweeps,"lamps":lamp_specs}


meta={"task":"C011","format":"glTF metres, +Y up, +Z forward, +X vehicle left",
      "origin":"Ground below the axle midpoint","texel_m":T,"vehicles":[]}
for design in DESIGNS:
    assets=[build(design,lod) for lod in ("near","mid","far")]
    print(design.slug,[a["triangles"] for a in assets],flush=True)
    meta["vehicles"].append({"name":design.name,"key":design.slug,"years":design.years,
        "estimated_mass_kg":design.mass_kg,"estimated_top_speed_kmh":design.speed_kmh,
        "wheel_radius_m":design.radius,"wheelbase_m":design.wheelbase,"track_m":design.track,
        "suggested_lod_m":{"mid":38,"far":95},
        "collision_boxes":[{"center_gltf_m":[0,(.30+design.belt)/2,0],
                            "size_gltf_m":[design.width*1.70,design.belt-.30,design.length*.84]},
                           {"center_gltf_m":[0,(design.belt+design.height)/2,-.51],
                            "size_gltf_m":[design.width*1.52,design.height-design.belt,1.65]}],
        "paint":{"preset_body_colours":design.colours,"stock_secondary":design.accent,
                 "masks":{role:f"textures/{design.slug}-{role}-mask.png" for role in ("primary","secondary")},
                 "badge":{"node":"badge_plate","material":"hardware_and_trim","atlas_texels":256,
                          "center_texels":ATLAS["finish"]["badge"]}},
        "lods":assets})
(ROOT/"model-info.json").write_text(json.dumps(meta,indent=2)+"\n")
