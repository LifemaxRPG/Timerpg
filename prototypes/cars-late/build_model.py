"""Assemble C012 production meshes and metadata with physical-scale UVs."""
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
WAYFARER_ATLAS=json.loads((ROOT/"textures/stoneweir-2015-regions.json").read_text())


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
        atlas=WAYFARER_ATLAS if image.name.startswith("stoneweir-2015") else ATLAS
        if region and mat.name=="glow_lamps":
            x,y=atlas["lamps"][region]
        else:
            x,y=atlas["finish"].get(region,(0,0))
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
    pickup,coupe,hatch,crossover=(era==i for i in range(4))
    liftback=hatch or crossover
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
    side_top=w*(.815 if pickup or hatch else .75)
    side_foot=w*.956
    cushion_offset=.078 if coupe else .091 if hatch else .132
    hip_offset=.047 if coupe or hatch else .059
    pivots,lamp_specs,door_sweeps={},{},{}
    cargo_pose={}
    cargo=None

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
        sizes={
            "head":((.113,.113),(.100,.100),(.132,.063),(.206,.054)),
            "tail":((.064,.087),(.109,.049),(.077,.091),(.179,.053)),
            "brake":((.064,.042),(.108,.04),(.074,.037),(.102,.042)),
            "turn":((.065,.036),(.068,.034),(.064,.035),(.118,.030)),
            "reverse":((.054,.037),(.061,.039),(.071,.037),(.071,.034)),
        }
        rx,ry=sizes[kind][era]
        offsets=(-.111,.111) if coupe and kind=="head" and near else (0,)
        for off in offsets:
            if (pickup or coupe) and kind=="head":
                n=8 if near else 4
                ring=[(off+rx*cos(2*i*pi/n+pi/n),ry*sin(2*i*pi/n+pi/n),0) for i in range(n)]
            else:
                ring=[(off+rx*x,ry*y,0) for x,y in ((-1,-1),(.88,-1),(1,1),(-.97,1))]
                n=4
            face=tuple(range(n)) if facing>0 else tuple(range(n-1,-1,-1))
            surface("Lens",holder,ring,[face],lamps,kind)
            if near and kind in ("head","tail"):
                rim=[(off+(x-off)*1.12,y*1.17,-facing*.088) for x,y,_ in ring]
                front=[(x,y,-facing*.006) for x,y,_ in ring]
                rings=[rim,front] if facing>0 else [front,rim]
                loft("Light bezel",holder,rings,silver,"metal" if era<2 else "dark",False)

    def wayfarer_belt(z):
        return belt+max(-.054,min(.074,-.075*z))

    def wayfarer_arch_floor(z,axle_z,low):
        # Cut the moving doors and the fixed quarter panels around the same arch.
        angle_x=(z-axle_z)/(radius+.119)
        if abs(angle_x)>=1:
            return low
        arch=radius+(radius+.120)*(1-angle_x**2)**.5-.035
        return max(low,arch)

    def wayfarer_quarter_panels(sections,axle_z):
        start,end=sections[0][0],sections[-1][0]
        offsets=[(radius+.119)*cos(i*pi/10) for i in range(11)]
        stations=sorted({q[0] for q in sections}|{axle_z+x for x in offsets if start<axle_z+x<end})
        rings=[]
        for z in stations:
            a,b=next((a,b) for a,b in zip(sections,sections[1:]) if a[0]<=z<=b[0])
            t=(z-a[0])/(b[0]-a[0])
            _,width,low,high,_=(u+t*(v-u) for u,v in zip(a,b))
            floor=wayfarer_arch_floor(z,axle_z,low+.068)
            x=width*(.91+.09*(floor-low)/(high-low))
            rings.append((z,x,floor,width,high))
        for side in (-1,1):
            pts=[(side*x,y,z) for z,x0,y0,x1,y1 in rings for x,y in ((x0,y0),(x1,y1))]
            faces=[(2*i,2*i+2,2*i+3,2*i+1) for i in range(len(rings)-1)]
            surface("Sculpted wheel-opening quarter skin",shell,pts,
                    faces if side>0 else [q[::-1] for q in faces],paint)

    def shaped_lens(name,parent,origin,role,faces,zone,facing):
        holder=anchor(name,parent,origin)
        lamp_specs[name]={"role":role,"lens_material":"glow_lamps","facing_gltf":[0,0,facing]}
        if not far:
            for points in faces:
                surface("Inset "+role+" lens",holder,points,[(0,1,2,3)],lamps,zone)

    rear_deck=design.rear
    if coupe:
        before,after=next((a,b) for a,b in zip(design.rear,design.rear[1:]) if a[0]<=rear_foot<b[0])
        frac=(rear_foot-before[0])/(after[0]-before[0])
        part=tuple(a+frac*(b-a) for a,b in zip(before,after))
        rear_deck=tuple(s for s in design.rear if s[0]<rear_foot)+(part,)

    # The continuous floor stops at sill height. The visible cabin has real door apertures.
    if far:
        rearmost=design.rear[0][0]
        forwardmost=design.front[-1][0]
        bodysections=[(rearmost,.79*w,.35,.83*belt,.83*belt),
                      (-.76,w,.25,belt,belt),(.83,w,.26,belt,belt),
                      (forwardmost,.79*w,.36,.87*belt,.87*belt)]
        if crossover:
            bodysections=[(-2.30,.714,.32,1.06,1.06),(-2.04,.937,.27,1.16,1.16),
                          (-.72,w,.205,1.17,1.17),(.93,w,.205,1.06,1.06),
                          (2.06,.91,.29,1.05,1.05),(forwardmost,.67,.43,1.02,1.02)]
        if pickup:
            bodysections[0]=(rearmost,w*.95,.39,sill,sill)
            bodysections[1]=(-.76,w,.36,sill,sill)
        rings=[[(x,y,z) for x,y in ((-s,lo),(s,lo),(s,hi),(-s,hi))]
               for z,s,lo,hi,_ in bodysections]
        loft("Distance body",shell,rings,paint)
        plates=[
            (-side_foot,belt,rear_foot),(side_foot,belt,rear_foot),
            (side_top,roof-.04,rear_top),(-side_top,roof-.04,rear_top),
            (-side_foot,belt,front_foot),(side_foot,belt,front_foot),
            (side_top,roof,front_top),(-side_top,roof,front_top),
        ]
        if crossover:
            plates=[(x,wayfarer_belt(z) if y==belt else y,z) for x,y,z in plates]
        faces=[(0,4,5,1),(1,5,6,2),(2,6,7,3),(3,7,4,0),(3,2,1,0),(4,5,6,7)]
        surface("Distance roof and glazing",shell,plates,faces,
                [paint,cabin,accent if pickup or crossover else paint,cabin,cabin,cabin],
                [None,"dark",None,"dark","dark","dark"])
        if pickup:
            for side in (-1,1):
                x0,x1=side*.95,side*.83
                pts=[(x,y,z) for z in (-2.64,-.79) for x,y in ((x0,.61),(x0,1.03),(x1,1.03),(x1,.61))]
                surface("Distance bed rails",shell,pts,[(0,1,5,4),(1,2,6,5),(2,3,7,6)],paint)
            surface("Distance shut tailgate",shell,[(-.94,.62,-2.65),(.94,.62,-2.65),
                    (.94,1.021,-2.65),(-.94,1.021,-2.65)],[(3,2,1,0)],paint)
    else:
        pan=((-half-.29,.48 if era<2 else .55,.26,sill-.025,sill-.025),
             (-half+.26,w*.94,.268,sill,sill),(.56,w*.96,.26,sill,sill),
             (half-.29,w*.87,.27,sill,sill),(half+.44,.43 if era<2 else .55,.30,sill-.04,sill-.04))
        if crossover:
            pan=((-1.78,.57,.278,sill-.02,sill-.02),(-1.09,w*.83,.205,sill,sill),
                 (.72,w*.85,.205,sill,sill),(1.07,w*.79,.235,sill,sill),(1.77,.55,.28,sill-.03,sill-.03))
        loft("Pressed rocker pan",shell,[car_ring(s) for s in pan],paint,cap=True)
        for sign in (-1,1):
            box("Black rocker reveal",shell,(sign*w*.954,sill-.072,-.373),(.038,.062,2.01),cabin,"dark")
        decks=[(design.front,"hood",design.front[0][0],True)]
        if not pickup:
            decks.append((design.rear,"trunk",rear_foot,coupe))
        for section,name,pivot,moves in decks:
            rings=[car_ring(s) for s in section]
            if crossover:
                axle_z=half if name=="hood" else -half
                loft(name.title()+" inner bay",shell,rings,paint,skip=(1,2,4,6,7),cap=False)
                wayfarer_quarter_panels(section,axle_z)
            else:
                loft(name.title()+" fixed body sides",shell,rings,paint,
                     skip=(4,) if moves or liftback else (),cap=name=="hood" or not liftback)
            if moves:
                moving=section if name=="hood" else rear_deck
                hinge=anchor(name,root,(0,moving[0 if name=="hood" else -1][-1],pivot),[1,0,0])
                hinge_h=pivots[name]["center_gltf_m"][1]
                pv=[(x,y-hinge_h,z-pivot) for q in moving for x,y,z in car_ring(q)[4:6]]
                faces=[(2*i,2*i+2,2*i+3,2*i+1) for i in range(len(moving)-1)]
                surface(name.title()+" opening panel",hinge,pv,faces,paint)
                if name=="trunk":
                    cargo_pose[name]=59
                if coupe and name in ("hood","trunk"):
                    stripe=node("stripe_"+name,hinge)
                    stripe["optional_livery"]=True
                    for center in (-.164,.164):
                        points=[(center+side*.115,q[-1]-hinge_h+.003,q[0]-pivot)
                                for q in moving for side in (-1,1)]
                        stripe_faces=[(2*i,2*i+1,2*i+3,2*i+2) for i in range(len(moving)-1)]
                        surface("Optional painted racing stripe",stripe,points,stripe_faces,accent)
                if near and not crossover:
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
            crown=roof-(.055 if (coupe or crossover) and i in (0,3) else .0)
            rim=side_top*k
            roof_rings.append([(-rim,top_y,z),(rim,top_y,z),(rim*.965,crown-.042,z),
                               (rim*.721,crown,z),(-rim*.721,crown,z),(-rim*.965,crown-.042,z)])
        if crossover:
            roof_rings=[]
            for z,half_w,level in ((-1.22,.617,1.600),(-.99,.724,1.662),(-.43,.753,1.674),
                                   (.048,.733,1.674),(.326,.683,1.633),(.426,.604,1.598)):
                roof_rings.append([(-half_w,top_y,z),(half_w,top_y,z),(half_w*.983,level-.029,z),
                                   (half_w*.73,level,z),(-half_w*.73,level,z),(-half_w*.983,level-.029,z)])
        loft("Stepped roof cap",shell,roof_rings,accent if pickup or crossover else paint)
        if liftback:
            cargo=anchor("trunk",root,(0,top_y,rear_top),[1,0,0])
            cargo_pose["trunk"]=77
            rear_z=design.rear[0][0]-.009
            outer=w*.87
            deck=[(-side_foot,belt+.014,rear_foot),(side_foot,belt+.014,rear_foot),
                  (-outer,.55,rear_z),(outer,.55,rear_z)]
            if crossover:
                deck=[(x,y,z) for y,z,width in ((1.190,rear_foot,.848),(1.051,-2.340,.781),
                               (.735,-2.373,.719),(.579,-2.269,.687)) for x in (-width,width)]
                panels=[(2*i,2*i+1,2*i+3,2*i+2) for i in range(3)]
                surface("Sculpted liftgate skin",cargo,[(x,y-top_y,z-rear_top) for x,y,z in deck],panels,paint)
                lip=[(x,y-top_y,z-rear_top) for y,z,width in ((1.594,-1.16,.58),(1.578,-1.422,.546),
                     (1.523,-1.438,.493)) for x in (-width,width)]
                surface("Liftgate roof spoiler",cargo,lip,[(0,1,3,2),(2,3,5,4)],accent)
            else:
                surface("Opening hatch panel",cargo,[(x,y-top_y,z-rear_top) for x,y,z in deck],
                        [(0,1,3,2)],paint)
            box("Cargo load floor",shell,(0,sill-.012,(rear_foot+rear_z)/2),
                (w*1.65,.032,abs(rear_z-rear_foot)+.17),cabin,"rubber")
        elif pickup:
            box("Back of cab",shell,(0,(belt+sill)/2,-.79),(w*1.88,belt-sill,.095),paint)
        for front,bottom_z,top_z in ((True,front_foot,front_top),(False,rear_foot,rear_top)):
            sign=1 if front else -1
            if crossover:
                y=1.105 if front else 1.190
                edge=[
                    ((-.885,y-.015,bottom_z-sign*.236),(-.695,top_y,top_z-sign*.060)),
                    ((-.649,y,bottom_z),(-.556,top_y,top_z)),
                    ((.649,y,bottom_z),(.556,top_y,top_z)),
                    ((.885,y-.015,bottom_z-sign*.236),(.695,top_y,top_z-sign*.060)),
                ]
            else:
                edge=[((-side_foot*.925,belt+.015,bottom_z),(-side_top*.94,top_y,top_z)),
                      ((side_foot*.925,belt+.015,bottom_z),(side_top*.94,top_y,top_z))]
            parent=cargo if not front and liftback else shell
            def p(point):
                return (point[0],point[1]-top_y,point[2]-rear_top) if parent==cargo else point
            for (a,b),(c,d) in zip(edge,edge[1:]):
                surface("Windscreen" if front else "Rear safety glass",parent,[p(q) for q in (a,c,d,b)],
                        [(0,1,2,3) if front else (3,2,1,0)],glass)
                if near or not crossover:
                    rod("Window top seal",parent,p(b),p(d),.023,silver,"bright" if pickup else "dark",4)
                    rod("Window bottom seal",parent,p(a),p(c),.023,silver,"bright" if pickup else "dark",4)
            for a,b in (edge[0],edge[-1]):
                rod("Fixed outer pillar",parent,p(a),p(b),.036,accent if pickup else cabin if crossover else paint,"dark" if crossover else None,n=4)

    for side,suffix in ((1,"l"),(-1,"r")):
        for i,(start,end) in enumerate(design.doors):
            name=f"door_{'front' if i==0 else 'rear'}_{suffix}"
            base=(side*w*.984,sill+.023,start)
            door=anchor(name,root,base,[0,1,0])
            door_sweeps[name]=-66*side
            if far:
                continue
            span=start-end
            hh=(wayfarer_belt(start) if crossover else belt)-base[1]
            back_h=(wayfarer_belt(end)-base[1]) if crossover else hh
            inset=.103 if crossover else 0
            pts=[(-side*inset,0,-.016),(-side*inset,0,-span+.015),
                 (side*.015,back_h,-span+.012),(side*.015,hh,-.012)]
            polys=[(0,1,2,3) if side==1 else (3,2,1,0)]
            if crossover and i==1:
                rear_edge=end+.015
                pts=[(side*.015,hh,-.012),(-side*inset,0,-.016)]
                for z in (-.827,-.876,-.939,-1.030,-1.147,-1.247,rear_edge):
                    y=wayfarer_arch_floor(z,-half,base[1])-base[1]
                    pts.append((side*(-inset+min(.096,y*.37)),y,z-start))
                pts.append((side*.015,back_h,-span+.012))
            shape=tuple(range(len(pts)))
            if side<0:
                shape=(shape[0],*shape[:0:-1]) if crossover and i==1 else shape[::-1]
            surface("Shaped hinged outer door",door,pts,[shape],paint)
            liner=[(x-side*.048,y,z) for x,y,z in pts]
            inside=(shape[0],*shape[:0:-1]) if crossover and i==1 else shape[::-1]
            surface("Padded inside door panel",door,liner,[inside],cabin,"panel")
            for j in range(len(pts)):
                k=(j+1)%len(pts)
                surface("Door edge",door,[pts[j],pts[k],liner[k],liner[j]],[(0,1,2,3)],paint)
            zA=-.048
            zB=-span+.054
            # The coupe's trailing door glass meets its fixed quarter panel.
            left_top=min(front_top-.012,start-.071)-start if i==0 else zA
            right_top=max(rear_top+.055,end+.06)-start if i==len(design.doors)-1 else zB
            tx=side*(side_top-w*.984)
            top=top_y-base[1]
            pane=[(side*.008,hh,zA),(side*.008,back_h,zB),(tx,top,right_top),(tx,top,left_top)]
            if crossover:
                pane[2]=(tx-side*.030,top-.014,right_top-.078)
                pane[3]=(tx,top,left_top)
                # Narrow dark trim follows the convex sill on each moving door.
                trim_end=max(-span+.026,-.55) if i==1 else -span+.026
                pts=[(-side*.114,.012,-.024),(-side*.114,.012,trim_end),
                     (-side*.078,.174,trim_end),(-side*.075,.136,-.025)]
                surface("Formed lower door cladding",door,pts,polys,cabin,"rubber")
            surface("Hinged side glass",door,pane,polys,glass)
            for a,b in (((0,3),(3,2),(2,1),(1,0)) if near else ((3,2),)):
                rod("Door sash",door,pane[a],pane[b],.022 if near else .020,
                    silver if pickup else cabin if crossover else paint,"metal" if pickup else "dark" if crossover else None,4)
            if near:
                rod("Exterior door handle",door,(side*.025,hh-.072,zB),(side*.025,hh-.072,zB+.122),
                    .017,silver,"bright",4)
                box("Power window switch" if crossover else "Cabin window crank",door,(-side*.058,hh-.19,zA-.21),(.032,.070,.071),silver,"metal")
            if pickup:
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
                .037,cabin if crossover else paint,"dark" if crossover else None,n=4)
        if coupe or hatch or (crossover and near):
            z0=design.doors[-1][1]-.02
            pts=[(side*side_foot,belt,z0),(side*side_foot,belt,rear_foot+.18),
                 (side*side_top,top_y,rear_top),(side*side_top,top_y,z0)]
            if crossover:
                pts=[(side*.962,wayfarer_belt(z0),z0),(side*.876,1.218,-1.782),
                     (side*.682,1.512,-1.225),(side*.696,1.552,-1.312)]
            surface("Coupe side quarter glass",shell,pts,[(0,1,2,3) if side==1 else (3,2,1,0)],glass)
            rod("Quarter window edge",shell,pts[1],pts[2],.047,
                cabin if crossover else paint,"dark" if crossover else None,n=4)
        if crossover and near:
            x=side*.644
            rod("Roof rail",shell,(x,1.647,-1.012),(x,1.664,.264),.026,silver,"dark",4)
        if hatch:
            length=1.99 if hatch else 2.26
            box("Molded side impact moulding",shell,(side*w*.997,sill+(.052 if crossover else .108),-.216),
                (.031,.171 if crossover else .057,length),accent,None)
        if crossover:
            # The rocker moulding sits under the moving panels and bridges the arch trim.
            box("Lower rocker cladding",shell,(side*.873,.440,-.007),(.085,.139,1.875),cabin,"rubber")
            surface("Rising rear quarter shoulder",shell,[(side*x,y,z) for x,y,z in
                ((.960,1.165,-1.345),(.952,.870,-1.345),(.919,.865,-1.972),
                 (.899,1.139,-2.042),(.875,1.214,-1.787))],[(0,1,2,3,4)],paint)
        if pickup and near:
            rod("Bodyside chrome sweep",shell,(side*.973,1.028,-2.59),(side*.973,1.028,-.83),
                .016,silver,"bright",4)

    # An open fleetside bed with a real deck, wheel tubs and bottom-hinged tailgate.
    if pickup and not far:
        tail_z=-2.661
        tail=anchor("tailgate",root,(0,.665,tail_z),[1,0,0])
        cargo_pose["tailgate"]=-88
        box("Pressed tailgate",tail,(0,.180,.014),(1.768,.36,.105),paint)
        box("Embossed tailgate stripe",tail,(0,.199,-.044),(1.60,.155,.023),accent)
        if near:
            box("Tailgate pull",tail,(0,.255,-.060),(.282,.052,.035),silver,"bright")
        box("Bed load floor",shell,(0,.626,-1.748),(1.826,.085,1.820),cabin,"panel")
        box("Bed underframe",shell,(0,.510,-1.743),(1.25,.196,1.94),paint)
        box("Cab-side bed bulkhead",shell,(0,.830,-.836),(1.853,.391,.09),paint)
        box("Rear frame and bumper support",shell,(0,.503,-2.612),(1.83,.261,.14),paint)
        for side in (-1,1):
            x=side*.919
            box("Fleetside box wall",shell,(x,.793,-1.751),(.143,.454,1.815),paint)
            box("Folded bed cap",shell,(x,1.021,-1.75),(.168,.056,1.843),accent)
            if near:
                x=side*.68
                box("Bed wheel tub",shell,(x,.736,-1.615),(.305,.202,.707),paint)
        if near:
            for x in (-.64,-.43,-.22,0,.22,.43,.64):
                box("Stamped bed rib",shell,(x,.674,-1.67),(.025,.028,1.60),cabin,"dark")

    # Wheel hubs survive every LOD. The front spin pivots ride below steering pivots.
    def wheel_mesh(parent,n):
        r=radius
        t=design.tyre
        offset=1 if parent.name.endswith(("fl","rl")) else -1
        circles=((-t/2,.84),(-t/2,1.0),(t/2,1.0),(t/2,.84))
        rings=[[(x,r*k*cos(i*2*pi/n),r*k*sin(i*2*pi/n)) for i in range(n)] for x,k in circles]
        if not near:
            loft("Distance tyre",parent,[rings[1],rings[2]],cabin,"rubber")
            if not far:
                x=offset*(t/2+.004)
                for scale,zone in ((.77,"metal"),(.40,"bright")):
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
        if crossover:
            wheel_band(.81,.73,"metal")
            wheel_band(.73,.24,"dark")
            for i in range(5):
                theta=2*pi*i/5
                vs=[(offset*(t/2+.012),r*(u*cos(theta)-v*sin(theta)),r*(u*sin(theta)+v*cos(theta)))
                    for u,v in ((.24,-.091),(.73,-.050),(.752,.072),(.24,.057))]
                surface("Angled alloy spoke",parent,vs,[(0,1,2,3)],silver,"metal")
        else:
            wheel_band(.74,.38,"metal")
        x=offset*(t/2+.017)
        rod("Pressed wheel hub",parent,(x-offset*.028,0,0),(x+offset*.027,0,0),
            r*(.245 if crossover else .38),silver,"bright",n)

    for index,z in enumerate((half,-half)):
        for side,suffix in ((1,"l"),(-1,"r")):
            axle_point=(side*axle,radius,z)
            if index==0:
                turn=anchor("steer_f"+suffix,root,axle_point,[0,1,0])
                wheel=anchor("wheel_f"+suffix,turn,(0,0,0),[1,0,0])
            else:
                wheel=anchor("wheel_r"+suffix,root,axle_point,[1,0,0])
            wheel_mesh(wheel,(12 if crossover else 10) if near else 6)
            if far:
                continue
            # A low-sided saddle wraps the wheel, leaving the open arch visible.
            rings=[]
            n=(10 if crossover else 9) if near else 4
            for k in range(n+1):
                a=pi*k/n
                length=radius+(.143 if coupe else .119)
                y=radius+sin(a)*(radius+.097)
                forward=z+cos(a)*length
                x_outer=axle+design.tyre/2+.109*sin(a)+.029
                x_inner=axle-design.tyre/2-.061
                x_outer=max(x_outer,w*(.962 if hatch else .997))
                if pickup:
                    y+=.086*sin(a)
                if crossover:
                    y+=.023*sin(a)
                ring=[(side*x_inner,y+.009,forward),(side*((x_outer+x_inner)/2),y+.119,forward),
                      (side*x_outer,y+.037,forward),(side*(x_outer+.004),y-.064,forward),
                      (side*(x_outer-.057),y-.069,forward),(side*x_inner,y-.021,forward)]
                if crossover:
                    ring=[(side*x_inner,y-.031,forward),(side*(x_outer-.064),y+.060,forward),
                          (side*x_outer,y+.040,forward),(side*(x_outer+.009),y-.051,forward),
                          (side*(x_outer-.037),y-.076,forward),(side*x_inner,y-.042,forward)]
                if not near:
                    ring=[ring[j] for j in (0,1,2,5)]
                rings.append(ring if side>0 else ring[::-1])
            loft("Stamped wheel arch",shell,rings,cabin if crossover else paint,"rubber" if crossover else None,cap=True)

    if not far:
        # Open coachwork is furnished with period-specific seats, pedals, dash and column.
        if near:
            span=1.44 if pickup else 2.14
            box("Textured cabin floor",shell,(0,sill+.018,-.12 if pickup else -.38),
                (w*1.75,.033,span),cabin,"rubber")
        driver_height=sill+cushion_offset
        seat_spots=((.346,-.078),(-.346,-.078)) if pickup else (
            (.346,-.204),(-.346,-.204),(.336,-1.027),(-.336,-1.027))
        for i,(x,z) in enumerate(seat_spots):
            name=("seat_driver","seat_passenger","seat_rear_left","seat_rear_right")[i]
            anchor(name,root,(x,driver_height+hip_offset,z))
        if not near:
            box("Simplified bench",shell,(0,driver_height,-.22),(w*1.65,.18,.61),cabin,"fabric")
        elif not pickup:
            for x in (-.35,.35):
                box("Front bucket cushion",shell,(x,driver_height,-.18),(.57,.118 if coupe else .145,.552),cabin,"fabric")
                box("Bucket backrest",shell,(x,driver_height+.211,-.443),(.54,.393,.13),cabin,"fabric")
            if crossover:
                box("Infotainment binnacle",shell,(0,1.111,.850),(.374,.203,.064),cabin,"dark")
                surface("Pixel navigation screen",shell,[(-.157,1.038,.814),(.157,1.038,.814),
                        (.157,1.168,.814),(-.157,1.168,.814)],[(3,2,1,0)],silver,"screen")
                box("Centre shifter console",shell,(0,sill+.139,.090),(.23,.238,.613),cabin,"dark")
                box("Console brushed insert",shell,(0,sill+.262,.095),(.122,.019,.226),silver,"metal")
        else:
            box("Front bench cushion",shell,(0,driver_height,-.215),(w*1.70,.157,.53),cabin,"fabric")
            box("Front bench back",shell,(0,driver_height+.235,-.47),(w*1.67,.482,.137),cabin,"fabric")
        if near and not pickup:
            box("Rear bench cushion",shell,(0,driver_height,-1.047),(w*1.61,.156,.44),cabin,"fabric")
            box("Rear bench back",shell,(0,driver_height+.186,-1.285),(w*1.59,.39,.126),cabin,"fabric")
        if crossover:
            box("Dark dashboard and knee bolster",shell,(0,.945,.970),(1.661,.265,.193),cabin,"panel")
        else:
            box("Period dash",shell,(0,belt-.080,front_foot-.064),(w*1.74,.148,.178),cabin,"panel")
        box("Pixel gauge cluster",shell,(.311,belt-.035,front_foot-.176),(.463,.152,.036),cabin,"dash")
        hub=(.34,belt-.033,front_foot-.364)
        steering=anchor("steering_wheel",root,hub,[0,0,1])
        n=12 if near else 5
        r=.187 if pickup else .166 if coupe else .174
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
        seat_spots=((.346,-.078),(-.346,-.078)) if pickup else (
            (.346,-.204),(-.346,-.204),(.336,-1.027),(-.336,-1.027))
        for i,(x,z) in enumerate(seat_spots):
            name=("seat_driver","seat_passenger","seat_rear_left","seat_rear_right")[i]
            anchor(name,root,(x,sill+cushion_offset+hip_offset,z))
        anchor("steering_wheel",root,(.34,belt-.033,front_foot-.364),[0,0,1])
        anchor("hood",root,(0,design.front[0][-1],design.front[0][0]),[1,0,0])
        if pickup:
            anchor("tailgate",root,(0,.665,-2.661),[1,0,0])
            cargo_pose["tailgate"]=-88
        else:
            anchor("trunk",root,(0,top_y,rear_top) if liftback else (0,rear_deck[-1][-1],rear_foot),[1,0,0])
            cargo_pose["trunk"]=77 if liftback else 59
    anchor("exit_left",root,(w+.57,0,.014))
    anchor("exit_right",root,(-w-.57,0,.014))

    # Lamp faces sit ahead of the coachwork caps and bumper skins.
    fz=design.front[-1][0]+.017
    rz=design.rear[0][0]-.037
    bumper_y=(.560,.485,.426,.464)[era]
    bright=era<2
    if not far:
        gx=(.76,.705,.694,.685)[era]
        if near and not crossover:
            frame_h=(.904,.741,.677,.836)[era]
            for off in (-1,1):
                x=(.735,.667,.598,.653)[era]*off
                box("Lamp pocket behind nose fascia",shell,(x,frame_h,fz-.061),
                    ((.270,.371,.281,.423)[era],(.299,.236,.208,.142)[era],.145),silver,
                    "metal" if bright else "dark")
            if liftback:
                # Quarter housings stay on the body while the liftgate swings up.
                for off in (-1,1):
                    box("Fixed quarter lamp pocket",shell,(off*(.655 if hatch else .719),
                        .765 if hatch else .854,rz+.086),(.172 if hatch else .302,.226 if hatch else .154,.172),
                        accent if crossover else paint)
        y0,y1=((.686,1.083),(.602,.816),(.575,.751),(.585,.845))[era]
        if crossover:
            grille=[(-.49,.624,2.355),(.49,.624,2.355),(.643,.771,2.321),
                    (.579,1.022,2.302),(-.579,1.022,2.302),(-.643,.771,2.321)]
            surface("Tapered shadow grille",shell,grille,[(0,1,2,3,4,5)],silver,"grille")
            if near:
                for a,b in zip(grille,grille[1:]+grille[:1]):
                    rod("Satin grille surround",shell,a,b,.019,silver,"metal",4)
                badge_plate(shell,0,.960,2.321)
        else:
            bottom_gx=gx
            surface("Distinctive radiator grille",shell,[(-bottom_gx,y0,fz),(bottom_gx,y0,fz),(gx*.98,y1,fz-.005),(-gx*.98,y1,fz-.005)],
                [(0,1,2,3)],silver,"grille")
            if near:
                for y,extent in ((y0,bottom_gx),(y1,gx)):
                    rod("Grille horizontal brightwork",shell,(-extent,y,fz+.011),(extent,y,fz+.011),
                        .019,silver,"metal" if bright else "dark",4)
                badge_plate(shell,0,y1-.052,fz+.023)
        for sign,base in ((1,fz),(-1,rz)):
            mode="metal" if bright else "rubber"
            if crossover and near:
                lengths=((-.245,w*.986),(-.085,w*.951),(.042,w*.818),(.108,w*.657))
                sections=[[(x,y,base+sign*z) for x,y in
                           ((-width,.402),(width,.402),(width,.649),(-width,.649))]
                          for z,width in (lengths if sign>0 else lengths[::-1])]
                loft("Contoured dark lower valance",shell,sections,silver,"rubber")
            else:
                box("Chrome bumper" if bright else "Dark molded bumper",shell,
                    (0,bumper_y,base+sign*.062),(w*1.79,.143 if bright else .186,.171),silver,mode)
                if near:
                    for x in (-w*.773,w*.773):
                        box("Wraparound bumper corner",shell,(x,bumper_y,base-sign*.021),
                            (.243,.145 if bright else .184,.258),silver,mode)
        if crossover and near:
            box("Lower front skid plate",shell,(0,.433,fz+.112),(.881,.083,.029),silver,"metal")
    if crossover:
        pivot=(0,top_y,rear_top)
        origin=(0,1.023,-2.353)
        target=bpy.data.objects["trunk"]
        local=tuple(v-o for v,o in zip(origin,pivot))
        shaped_lens("light_tail_bar",target,local,"tail",
                    [[(-.703,-.015,0),(-.703,.015,0),(.703,.015,0),(.703,-.015,0)]],"tail_bar",-1)

    for side,suffix in ((1,"l"),(-1,"r")):
        hx=(.710,.645,.561,.650)[era]*side
        hy=(.938,.747,.701,.879)[era]
        tx=(.861,.705,.630,.676)[era]*side
        ty=(.818,.714,.790,.878)[era]
        if crossover:
            def wrap(name,origin,role,segments,zone,forward):
                local_faces=[[(side*x-origin[0],y-origin[1],z-origin[2]) for x,y,z in facet]
                             for facet in segments]
                if side<0:
                    local_faces=[face[::-1] for face in local_faces]
                shaped_lens(name+"_"+suffix,root,origin,role,local_faces,zone,forward)
            def ribbon(stations):
                faces=[]
                for a,b in zip(stations,stations[1:]):
                    x,y,z,t=a
                    u,v,w,h=b
                    faces.append(((x,y-t,z),(u,v-h,w),(u,v+h,w),(x,y+t,z)))
                return faces
            front_line=((.526,1.017,2.328,.026),(.756,1.033,2.268,.027),(.936,1.069,2.041,.018))
            day_line=tuple((x,y+.044,z+.008,.012) for x,y,z,_ in front_line)
            tail_line=((.703,1.023,-2.354,.038),(.861,1.067,-2.273,.043),(.951,1.128,-2.046,.032))
            wrap("light_head",(side*.719,1.034,2.22),"head",ribbon(front_line),"head",1)
            wrap("light_drl",(side*.719,1.077,2.226),"drl",ribbon(day_line),"drl",1)
            wrap("light_tail",(side*.835,1.067,-2.253),"tail",
                 [face[::-1] for face in ribbon(tail_line)],"tail",-1)
            lens("light_brake_"+suffix,(side*.62,.875,-2.386),"brake",-1)
            lens("light_turn_front_"+suffix,(side*.704,.788,2.300),"turn",1)
            lens("light_turn_rear_"+suffix,(side*.815,.917,-2.284),"turn",-1)
            lens("light_reverse_"+suffix,(side*.480,.802,-2.386),"reverse",-1)
        else:
            lens("light_head_"+suffix,(hx,hy,fz+.038),"head",1)
            lens("light_tail_"+suffix,(tx,ty,rz-.014),"tail",-1)
            lens("light_brake_"+suffix,(tx,ty-.117,rz-.018),"brake",-1)
            lens("light_turn_front_"+suffix,(hx,hy-(.213 if pickup or coupe else .14),fz+.019),"turn",1)
            lens("light_turn_rear_"+suffix,(tx*.72,ty-.146,rz-.02),"turn",-1)
            lens("light_reverse_"+suffix,(tx*.54,ty-.123,rz-.024),"reverse",-1)

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
            "nodes":pivots,"door_preview_degrees":door_sweeps,"cargo_preview_degrees":cargo_pose,"lamps":lamp_specs}


def collision_boxes(design):
    x=design.width
    if design==DESIGNS[0]:
        return [
            {"center_gltf_m":[0,.71,.83],"size_gltf_m":[x*1.78,.82,3.09]},
            {"center_gltf_m":[0,1.482,-.042],"size_gltf_m":[x*1.61,.674,1.50]},
            {"center_gltf_m":[0,.486,-1.748],"size_gltf_m":[x*1.78,.398,1.82]},
            *({"center_gltf_m":[side*.919,.833,-1.751],"size_gltf_m":[.143,.44,1.815]} for side in (-1,1)),
        ]
    if design==DESIGNS[3]:
        return [{"center_gltf_m":[0,.654,-.04],"size_gltf_m":[1.80,.898,4.45]},
                {"center_gltf_m":[0,1.366,-.375],"size_gltf_m":[1.425,.526,1.81]}]
    return [{"center_gltf_m":[0,(.28+design.belt)/2,0],
             "size_gltf_m":[x*1.74,design.belt-.28,design.length*.84]},
            {"center_gltf_m":[0,(design.belt+design.height)/2,-.51],
             "size_gltf_m":[x*1.52,design.height-design.belt,1.65]}]


meta={"task":"C012","format":"glTF metres, +Y up, +Z forward, +X vehicle left",
      "origin":"Ground below the axle midpoint","texel_m":T,"vehicles":[]}
for design in DESIGNS:
    assets=[build(design,lod) for lod in ("near","mid","far")]
    print(design.slug,[a["triangles"] for a in assets],flush=True)
    meta["vehicles"].append({"name":design.name,"key":design.slug,"years":design.years,
        "estimated_mass_kg":design.mass_kg,"estimated_top_speed_kmh":design.speed_kmh,
        "wheel_radius_m":design.radius,"wheelbase_m":design.wheelbase,"track_m":design.track,
        "suggested_lod_m":{"mid":38,"far":95},
        "optional_livery_nodes":["stripe_hood","stripe_trunk"] if design==DESIGNS[1] else [],
        "collision_boxes":collision_boxes(design),
        "paint":{"preset_body_colours":design.colours,"stock_secondary":design.accent,
                 "masks":{role:f"textures/{design.slug}-{role}-mask.png" for role in ("primary","secondary")},
                 "badge":{"node":"badge_plate","material":"hardware_and_trim","atlas_texels":256,
                          "center_texels":ATLAS["finish"]["badge"]}},
        "lods":assets})
(ROOT/"model-info.json").write_text(json.dumps(meta,indent=2)+"\n")
