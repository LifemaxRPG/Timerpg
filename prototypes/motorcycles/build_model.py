"""Assemble C013 production meshes and metadata with physical-scale UVs."""
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


def section(z,w,low,shoulder,top):
    return [(x,y,z) for x,y in ((-.72*w,low),(.72*w,low),(w,shoulder),
                                (.62*w,top),(-.62*w,top),(-w,shoulder))]


def build(bike,lod):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    near=lod=="near"
    far=lod=="far"
    classic,standard,sport=(bike==spec for spec in DESIGNS)
    slug=bike.slug
    root=node(slug)
    shell=node("body",root)
    paint=material("paint_primary",slug+"-primary")
    accent=material("paint_secondary",slug+"-secondary")
    rubber=material("saddle_and_rubber",slug+"-finish")
    metal=material("hardware_and_trim",slug+"-finish")
    lamps=material("glow_lamps",slug+"-lamps") if not far else None
    glass=material("glass_windscreen",slug+"-glass",True) if sport and not far else None
    if lamps:
        emitter(lamps,slug+"-emission",.04)
    a=bike.wheelbase/2
    fr,rr=bike.front_radius,bike.rear_radius
    h=bike.head
    swivel=(Vector(h)-Vector((0,fr,a))).normalized()
    nodes={}
    light_specs={}

    def anchor(name,parent,point,axis=None):
        joint=node(name,parent,point)
        bpy.context.view_layer.update()
        p=joint.matrix_world.translation
        nodes[name]={"center_gltf_m":[round(p.x,6),round(p.z,6),round(-p.y,6)],"parent":parent.name}
        if axis is not None:
            nodes[name]["axis_local_gltf"]=[round(v,8) for v in axis]
            joint["axis_local_gltf"]=list(axis)
        return joint

    def local(point):
        return tuple(v-o for v,o in zip(point,h))

    steer=anchor("handlebar",root,h,swivel)
    front=anchor("wheel_f",steer,local((0,fr,a)),(1,0,0))
    rear=anchor("wheel_r",root,(0,rr,-a),(1,0,0))
    for label,point in zip(("seat_rider","seat_pillion"),bike.seats):
        anchor(label,root,point)
    for side,label in ((1,"left"),(-1,"right")):
        grip=tuple([bike.grip[0]*side,*bike.grip[1:]])
        anchor("grip_"+label,steer,local(grip))
        anchor("peg_"+label,root,(side*(.245 if classic else .232),.32 if classic else .38,.20 if classic else -.24))
        anchor("exit_"+label,root,(side*.73,0,-.279))

    def side_decal(profile,z,height,side=1):
        left,right=next((p,q) for p,q in zip(profile,profile[1:]) if p[0]<=z<=q[0])
        t=(z-left[0])/(right[0]-left[0])
        _,w,low,shoulder,top=(x+t*(y-x) for x,y in zip(left,right))
        if height<shoulder:
            x=w*(.72+.28*(height-low)/(shoulder-low))
        else:
            x=w*(1-.38*(height-shoulder)/(top-shoulder))
        return side*(x+.0045),height,z

    def tyre(parent,r,width):
        n=12 if near else 6
        angles=[2*pi*i/n for i in range(n)]
        def circle(x,f):
            return [(x,f*r*cos(t),f*r*sin(t)) for t in angles]
        if not near:
            loft("Low detail tyre",parent,[circle(-width/2,1),circle(width/2,1)],rubber,"rubber")
            if not far:
                for side in (-1,1):
                    c=circle(side*(width/2+.003),.72)
                    surface("Distance hub",parent,c,[tuple(range(n))],metal,"metal")
            return
        rings=[circle(x,f) for x,f in ((-width*.43,.73),(-width/2,.875),
                (-width*.36,1),(width*.36,1),(width/2,.875),(width*.43,.73))]
        loft("Tread, tyre shoulders and inner lips",parent,rings,rubber,"rubber",False)
        rod("Brake drum or axle hub",parent,(-width*.62,0,0),(width*.62,0,0),r*.26,metal,"metal",8)
        # Thin, truly open spokes span the gap between the hub and rim.
        for side in (-1,1):
            x=side*width*.43
            p=circle(x,.80)+circle(x,.705)
            faces=[(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)]
            surface("Rim lip",parent,p,faces if side>0 else [f[::-1] for f in faces],metal,"metal")
        count=10 if classic else 7 if standard else 5
        for i in range(count):
            t=i*2*pi/count
            c,s=cos(t),sin(t)
            width_spoke=.016 if classic else .028 if standard else .044
            p=[(x,u*c-v*s,u*s+v*c) for x,u,v in ((-.006,r*.24,-width_spoke/2),
                 (width*.24,r*.74,-width_spoke/2),(width*.24,r*.74,width_spoke/2),
                 (-.006,r*.24,width_spoke/2))]
            surface("Wire or cast spokes",parent,p,[(0,1,2,3)],metal,"bright")
        if not classic:
            ring=circle(width*.59,.63)+circle(width*.59,.46)
            f=[(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)]
            surface("Disc brake rotor",parent,ring,f,metal,"grille")
    tyre(front,fr,bike.front_tyre)
    tyre(rear,rr,bike.rear_tyre)

    # Fork, bars and headlight turn together. The front axle remains a child hub.
    if far:
        rod("Distance fork",steer,local((0,fr,a)),(0,0,0),.047,metal,"metal",4)
    else:
        for side in (-1,1):
            x=side*(.105 if classic else .093)
            base=local((x,fr,a))
            high=local((x,h[1]+.018,h[2]-.008))
            rod("Fork leg",steer,base,high,.032 if sport else .027,metal,"bright",6 if near else 4)
            if near:
                rod("Fork lower slider",steer,base,local((x,fr+.33,a-.124)),.042,metal,
                    "dark" if sport else "metal",6)
            if not sport:
                rise_end=(side*(.191 if classic else .123),bike.grip[1]-.069,bike.grip[2]+.06)
                rod("Handlebar riser",steer,high,local(rise_end),.025,metal,"metal",5 if near else 4)
            gx,gy,gz=(side*bike.grip[0],bike.grip[1],bike.grip[2])
            elbow=(side*(.191 if classic else .123),gy-(.069 if not sport else .027),gz+(.06 if not sport else .03))
            handle_end=(gx-side*.091,gy,gz+.013)
            rod("Swept handlebar" if not sport else "Clip-on bar",steer,local(elbow),local(handle_end),
                .021,metal,"metal",5 if near else 4)
            if near:
                rod("Ribbed rubber grip",steer,local(handle_end),local((gx,gy,gz)),.025,rubber,"rubber",6)
                rod("Brake or clutch lever",steer,local((gx-side*.085,gy-.019,gz+.046)),
                    local((gx-side*.013,gy-.028,gz+.065)),.008,metal,"bright",4)
        if near:
            box("Fork crown",steer,(0,-.02,.001),(.277,.053,.132),metal,"metal")
            box("Pixel instrument faces",steer,local((0,h[1]+.085,h[2]+.062)),(.181 if sport else .134,.037,.107),
                metal,"dash")

    # Keep the far skin recognisable without carrying the high-detail engine or suspension.
    seat_top=bike.seats[0][1]-.045
    if classic:
        tank=[(-.257,.10,.658,.753,.806),(-.154,.147,.654,.776,.868),
              (.115,.176,.686,.865,.946),(.345,.099,.773,.873,.924)]
    elif standard:
        tank=[(-.234,.102,.701,.786,.830),(-.094,.155,.702,.844,.930),
              (.179,.177,.742,.909,.994),(.373,.102,.795,.918,.974)]
    else:
        tank=[(-.205,.114,.720,.800,.855),(-.075,.175,.722,.878,1.003),
              (.192,.198,.761,.938,1.063),(.378,.143,.807,.947,1.012)]
    if far:
        rings=[[(x,y,z) for x,y in ((-w,lo),(w,lo),(w,hi),(-w,hi))] for z,w,lo,_,hi in tank[::2]+tank[-1:]]
        loft("Distance tank",shell,rings,paint)
        box("Distance saddle",shell,(0,seat_top-.041,-.47),(.301,.114,.593),rubber,"fabric")
        if sport:
            pts=[(s*x,y,z) for s in (-1,1) for x,y,z in
                 ((.18,.36,.025),(.245,.44,.48),(.173,.994,.759),(.228,.805,.345))]
            surface("Distance fairing",shell,pts,[(3,2,1,0),(4,5,6,7),(0,1,5,4),
                    (1,2,6,5),(2,3,7,6),(3,0,4,7)],paint)
        else:
            box("Distance crankcase",shell,(0,.481,.037),(.346,.342,.435),metal,"metal")
        if classic:
            # Wide valance and a small pad preserve the upright silhouette.
            box("Distance rear valance",shell,(0,rr+.24,-a),(.215,.19,.44),paint)
        elif standard:
            box("Distance round lamp housing",steer,local((0,1.00,.676)),(.151,.156,.136),metal,"bright")
    else:
        loft("Faceted fuel tank",shell,[section(*s) for s in tank],paint)
        if near:
            rod("Filler cap",shell,(0,tank[2][-1]-.006,tank[2][0]),(0,tank[2][-1]+.015,tank[2][0]),
                .047,metal,"bright",8)
        # Cradle and rear swingarm remain readable through both sides.
        frame_runs=((h,(0,.685,.256)),((0,.685,.256),(0,.345,.205)),((0,.345,.205),(0,.329,-.313)),
                    ((0,.329,-.313),(0,.687,-.343)),((0,.687,-.343),h),
                    ((0,.687,-.343),(0,rr,-a)))
        for i,(p,q) in enumerate(frame_runs):
            if near or i in ((1,2,5) if sport else (1,2,4,5)):
                rod("Steel cradle frame",shell,p,q,.036 if classic else .031,
                    metal,"dark" if not classic else "panel",5 if near else 4)
        for side in (-1,1):
            x=side*.107
            rod("Rear swingarm",shell,(x,.403,-.241),(x,rr,-a),.030,metal,"dark",5 if near else 4)
            if not sport and near:
                if standard:
                    up=(x*1.28,.723,-.512)
                    down=(x*1.28,rr+.066,-a+.056)
                    rod("Twin shock shaft",shell,down,up,.021,metal,"bright",6)
                    rod("Exposed spring sleeve",shell,down,tuple((v+w)*.5 for v,w in zip(up,down)),.037,
                        metal,"fins",7)
                else:
                    rod("Rear rigid frame strut",shell,(x,.69,-.403),(x,rr,-a),.032,metal,"dark",5)
        if classic:
            loft("Solo sprung leather rider saddle",shell,[section(*s) for s in
                 ((-.541,.126,seat_top-.03,seat_top,seat_top+.031),(-.356,.206,seat_top-.091,seat_top-.012,seat_top+.036),
                  (-.208,.16,seat_top-.077,seat_top-.027,seat_top+.009),(-.098,.07,seat_top-.035,seat_top,seat_top+.003))],
                 rubber,"fabric")
            box("Short pillion pad",shell,(0,.723,-.689),(.259,.074,.279),rubber,"fabric")
            if near:
                for x in (-.104,.104):
                    rod("Rider saddle spring",shell,(x,.580,-.421),(x,.689,-.441),.039,metal,"fins",6)
        else:
            rise=.101 if sport else .014
            segments=[(-.799,.105,seat_top-.055,seat_top-.024,seat_top+rise),
                       (-.526,.182,seat_top-.05,seat_top,seat_top+rise),
                       (-.357,.184,seat_top-.055,seat_top-.025,seat_top+.009),
                       (-.159,.12,seat_top-.05,seat_top-.023,seat_top+.016)]
            loft("Stepped or bench saddle",shell,[section(*s) for s in segments],rubber,"fabric")
        if classic or standard:
            # Air-cooled engine cylinders are visibly separate from the crankcase.
            rod("Round crankcase",shell,(-.176,.500,.026),(.176,.500,.026),.142 if classic else .109,
                metal,"metal",8 if near else 6)
            sets=(((0,.510,.032),(0,.724,.222)),((0,.510,.032),(0,.719,-.171))) if classic else (
                ((-.104,.511,.061),(-.101,.730,.074)),((.104,.511,.061),(.101,.730,.074)))
            for p,q in sets:
                rod("Air-cooled finned cylinder",shell,p,q,.106 if classic else .088,metal,"fins",8 if near else 5)
                if near:
                    rod("Cylinder head cap",shell,tuple(.24*v+.76*w for v,w in zip(p,q)),q,.117 if classic else .100,
                        metal,"metal",6)
        if sport:
            # The lower fairing forms a broad, punctuated wedge with an upswept nose.
            form=((-.387,.109,.36,.577,.615),(-.175,.234,.291,.662,.758),
                  (.215,.284,.283,.744,.949),(.475,.264,.423,.812,1.006),
                  (.642,.225,.565,.854,1.023),(.759,.178,.729,.875,.981))
            loft("Full faired shell",shell,[section(*s) for s in form],paint)
            for side in (-1,1):
                cut=[(-.175,.567,.613),(.215,.547,.700),(.475,.698,.783),(.642,.793,.834)]
                graphic=[side_decal(form,z,h,side) for z,a,b in cut for h in (a,b)]
                surface("Angled warm racing graphics",shell,graphic,
                        [(2*i,2*i+2,2*i+3,2*i+1) for i in range(len(cut)-1)],accent)
                if near:
                    vents=[(.215,.413,.496),(.475,.467,.529)]
                    shape=[side_decal(form,z,h,side) for z,a,b in vents for h in (a,b)]
                    surface("Matte cooling vent",shell,shape,[(0,2,3,1)],metal,"grille")
            screen=[(-.142,1.00,.715),(.142,1.00,.715),(.146,1.109,.567),
                    (-.146,1.109,.567),(.095,1.175,.494),(-.095,1.175,.494)]
            surface("Raked windscreen",shell,screen,[(0,1,2,3),(3,2,4,5)],glass)
            if near:
                for start,end in ((0,3),(1,2),(3,5),(2,4),(4,5)):
                    rod("Dark windscreen edge",shell,screen[start],screen[end],.0085,metal,"dark",4)
            tail=[(-.929,.098,.531,.748,.872),(-.768,.148,.591,.783,.907),
                  (-.476,.181,.676,.796,.826)]
            loft("Upswept tail cowl",shell,[section(*s) for s in tail],paint)
        else:
            # End the tank stripe before the saddle and mark the paint as an independent color.
            for side in (-1,1):
                p=[]
                for z,w,lo,shoulder,top in tank:
                    p.extend(((side*(w*.949+.005),shoulder+(top-shoulder)*.17,z),
                              (side*(w*.891+.005),shoulder+(top-shoulder)*.31,z)))
                surface("Tank pinstripe" if classic else "Tank belt stripe",shell,p,
                        [(2*i,2*i+2,2*i+3,2*i+1) for i in range(len(tank)-1)],accent)
        # Distinct exhaust routes are part of the side silhouettes.
        for side in ((1,) if sport else (-1,1)):
            x=side*(.243 if not sport else .289)
            tube=((x,.465,.071),(x,.412,-.175),(x,.431 if standard else .387,-.634),
                  (x,.490 if standard else .411,-.947 if not sport else -.741))
            for i,(p,q) in enumerate(zip(tube,tube[1:])):
                rod("Header or silencer",shell,p,q,(.039 if i<2 else .063 if sport else .050),
                    metal,"metal" if sport else "bright",6 if near else 4)
        for side in (-1,1):
            if classic:
                box("Rubber-lined footboard",shell,(side*.253,.320,.205),(.165,.064,.333),rubber,"rubber")
            elif near:
                rod("Folding foot peg",shell,(side*.121,.380,-.240),(side*.246,.380,-.240),
                    .020,metal,"rubber",5)
        if near:
            # A replaceable badge sits on the broad side of the tank or fairing.
            profile=form if sport else tank
            z0,z1=(.254,.390) if sport else (.106,.214)
            y=.708 if sport else .848 if standard else .804
            mark=surface("badge_plate",shell,
                    [side_decal(profile,z,q) for z,q in ((z0,y-.024),(z1,y-.024),
                        (z1,y+.024),(z0,y+.024))],[(0,1,2,3)],metal,"badge")
            mark["keep"]=True

    def mudguard(name,parent,center,r,width,valanced=False):
        n=9 if near else 4
        extent=1.20 if valanced else .955
        stations=[]
        for i in range(n+1):
            t=pi/2-extent+2*extent*i/n
            ux,uz=sin(t),cos(t)
            x,y,z=center
            outer=r+.043
            inner=(r*.765 if valanced else r+.017)
            stations.append([(x+dx,y+rad*ux,z+rad*uz) for dx,rad in
                             ((-width,inner),(-width,outer),(-width*.67,outer+.022),
                              (width*.67,outer+.022),(width,outer),(width,inner))])
        loft(name,parent,stations,paint if valanced or sport else metal,None if valanced or sport else "metal",False)

    if not far:
        mudguard("Front valanced or bobbed mudguard",steer,local((0,fr,a)),fr,bike.front_tyre*.63,classic)
        if not sport:
            mudguard("Rear motorcycle mudguard",shell,(0,rr,-a),rr,bike.rear_tyre*.66,classic)

    kick=anchor("kickstand",root,(.164,.337,-.071),(1,0,0))
    stand_angle=-72.0
    reach=.322/sin(72*pi/180)
    if not far:
        rod("Welded stand support",shell,(0,.338,-.071),(.168,.337,-.071),.027,metal,"dark",4)
        rod("Folding stand leg",kick,(0,0,0),(.138,.002,-reach),.017,metal,"dark",5 if near else 4)
        box("Kickstand foot",kick,(.142,.003,-reach),(.089,.026,.038),metal,"dark")

    def lamp(name,kind,parent,point,facing,sizes,round_lens=False):
        holder=anchor(name,parent,local(point) if parent==steer else point)
        light_specs[name]={"role":kind,"lens_material":"glow_lamps","facing_gltf":[0,0,facing]}
        if far:
            return
        rx,ry=sizes
        n=10 if round_lens and near else 4
        if round_lens:
            p=[(rx*cos(2*pi*i/n+pi/n),ry*sin(2*pi*i/n+pi/n),0) for i in range(n)]
        else:
            p=[(rx*x,ry*y,0) for x,y in ((-1,-.73),(.91,-1),(1,.77),(-.95,1))]
        face=tuple(range(n)) if facing>0 else tuple(range(n-1,-1,-1))
        surface("Lamp lens",holder,p,[face],lamps,kind)
        if kind in ("head","tail"):
            base=[(x*1.20,y*1.20,-facing*(.106 if kind=="head" else .073)) for x,y,_ in p]
            lip=[(x,y,-facing*.006) for x,y,_ in p]
            loft("Stove enamel lamp bucket",holder,[base,lip] if facing>0 else [lip,base],metal,
                 "metal" if not sport else "dark",False)
    y=1.017 if classic else .977 if standard else .920
    if sport:
        for side,suffix in ((1,"l"),(-1,"r")):
            lamp("light_head_"+suffix,"head",root,(side*.108,y,.784),1,(.091,.041))
    else:
        lamp_z=.714 if classic else .690
        if not far:
            box("Lamp mounting yoke",steer,local((0,(h[1]+y)/2,(h[2]+lamp_z-.071)/2)),
                (.176,.086,lamp_z-h[2]-.049),metal,"dark")
        lamp("light_head_main","head",steer,(0,y,lamp_z),1,(.101,.101),True)
    tail_z=-.983 if sport else -a-.183
    tail_y=.816 if sport else rr+.252
    # Mount the lamp and plate on a short hanger under the rear tail.
    if near:
        box("Rear grouped lamp carrier",shell,(0,tail_y-.046,tail_z+.046),(.253,.145,.094),metal,"dark")
        box("Generic plate blank",shell,(0,tail_y-.146,tail_z+.021),(.181,.078,.014),metal,"panel")
    lamp("light_tail_main","tail",root,(0,tail_y,tail_z),-1,(.112,.035 if sport else .056))
    lamp("light_brake_main","brake",root,(0,tail_y-.074,tail_z-.005),-1,(.058,.032))
    if not classic:
        for side,suffix in ((1,"l"),(-1,"r")):
            if near:
                rod("Rear indicator stalk",shell,(side*.093,tail_y-.019,tail_z+.052),
                    (side*.202,tail_y-.018,tail_z+.027),.012,metal,"dark",4)
                if standard:
                    rod("Fork-mounted front indicator stalk",steer,local((side*.067,.936,.505)),
                        local((side*.184,y-.079,.654)),.013,metal,"dark",4)
            lamp("light_turn_rear_"+suffix,"turn",root,(side*.206,tail_y-.018,tail_z+.005),-1,(.045,.030))
            lamp("light_turn_front_"+suffix,"turn",root if sport else steer,
                 (side*(.202 if sport else .184),y-.079,.737 if sport else .667),1,(.046,.034))

    batch(shell)
    bpy.context.view_layer.update()
    draw=[o for o in bpy.context.scene.objects if o.type=="MESH"]
    tris=sum(len(p.vertices)-2 for o in draw for p in o.data.polygons)
    mins=[float("inf")]*3
    maxs=[-float("inf")]*3
    for ob in draw:
        for v in ob.data.vertices:
            p=ob.matrix_world@v.co
            for i,component in enumerate((p.x,p.z,-p.y)):
                mins[i]=min(mins[i],component)
                maxs[i]=max(maxs[i],component)
    file=slug+("" if near else "-"+lod)
    export_glb(ROOT/(file+".glb"))
    if near:
        save_source(ROOT/(file+".blend"))
    return {"file":file+".glb","lod":lod,"triangles":tris,
            "bounds_gltf_m":[[round(l,6),round(h,6)] for l,h in zip(mins,maxs)],
            "materials":sorted({m.name for o in draw for m in o.data.materials}),
            "nodes":nodes,"lamps":light_specs,"kickstand_deploy_degrees":stand_angle}


meta={"task":"C013","format":"glTF metres, +Y up, +Z forward, +X vehicle left",
      "origin":"Ground below the axle midpoint","texel_m":T,"vehicles":[]}
for bike in DESIGNS:
    models=[build(bike,lod) for lod in ("near","mid","far")]
    print(bike.slug,[m["triangles"] for m in models],flush=True)
    meta["vehicles"].append({"name":bike.name,"key":bike.slug,"years":bike.years,
        "wheelbase_m":bike.wheelbase,"wheel_radius_m":{"front":bike.front_radius,"rear":bike.rear_radius},
        "tyre_width_m":{"front":bike.front_tyre,"rear":bike.rear_tyre},"track_m":0,
        "estimated_mass_kg":bike.mass_kg,"estimated_top_speed_kmh":bike.speed_kmh,
        "suggested_lod_m":{"mid":34,"far":85},
        "collision_boxes":[{"center_gltf_m":[0,.550,.035],"size_gltf_m":[.434,.56,.88]},
                           {"center_gltf_m":[0,.724,-.448],"size_gltf_m":[.344,.26,.60]}],
        "paint":{"preset_body_colours":bike.colours,"stock_secondary":bike.accent,
                 "masks":{role:f"textures/{bike.slug}-{role}-mask.png" for role in ("primary","secondary")},
                 "badge":{"node":"badge_plate","material":"hardware_and_trim","atlas_texels":256,
                          "center_texels":ATLAS["finish"]["badge"]}},
        "lods":models})
(ROOT/"model-info.json").write_text(json.dumps(meta,indent=2)+"\n")
