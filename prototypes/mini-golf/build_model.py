"""Build Thimble Trails in three conditions and three LODs from shared survey coordinates."""
import json
from math import cos, pi, sin, atan2, hypot
from pathlib import Path
from random import Random
import sys

import bpy
from mathutils import Vector
from mathutils.geometry import delaunay_2d_cdt

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from sculpture_tools import beam, box, group, loft, mesh, material, oval, plaque
from revision_common import batch, emitter, export_glb, save_source

from course_layout import BLUE_HOLES, BOUNDS, CREEK, PAD, POND, ROCK_GREENS, ROUTES, SPLIT_ISLAND
from course_layout import heading, outline, surface_height, track_sections

T=.0388
FLOOR=.13
REGIONS=json.loads((ROOT/"textures/sign-regions.json").read_text())
STATES={"1962":("Open",1962,1989),"1990":("Faded",1990,2005),"2006":("Closed and overgrown",2006,None)}


def block(parent,label,bounds,mat):
    a,b,c,d,e,f=bounds
    return box(label,parent,((a+b)/2,(c+d)/2,(e+f)/2),(b-a,d-c,f-e),mat)


def flat(parent,label,corners,z,mat):
    signed=sum(a*d-b*c for (a,b),(c,d) in zip(corners,corners[1:]+corners[:1]))
    verts=[(x,y,z) for x,y in (corners if signed>0 else corners[::-1])]
    return mesh(label,parent,verts,[tuple(range(len(verts)))],mat)


def gable(parent,label,x0,x1,y0,y1,eave,ridge,mat):
    mid=(x0+x1)/2
    verts=[(x,y,z) for y in (y0,y1) for x,z in ((x0,eave),(x1,eave),(mid,ridge))]
    return mesh(label,parent,verts,[(0,1,2),(5,4,3),(3,4,1,0),(1,4,5,2),(3,0,2,5)],mat)


def build(year,lod):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    closed=year=="2006"
    faded=year=="1990"
    near,far=lod=="near",lod=="far"
    root=group("Thimble Trails Mini Golf")
    hut,course,obstacles,lot,col=[group(n,root) for n in ("ticket_hut","putting_lanes","course_obstacles","lot","collision")]
    yard=material("ground_yard","yard-"+year)
    turf=material("ground_putting_turf","course-"+year)
    path=material("ground_concrete","path-"+year)
    cream=material("wall_cream","cream-"+year)
    red=material("roof_and_sculpture","red-"+year)
    blue=material("ground_blue_carpet","blue-"+year)
    water=material("detail_pond","water-"+year) if not closed else None
    ball=material("detail_ball_dimple","ball-"+year)
    trim=material("detail_dark_trim","trim-"+year)
    glass=material("glass_ticket_hut","glass-dark" if closed else "glass")
    nodes=glass.node_tree.nodes
    glass.node_tree.links.new(next(n for n in nodes if n.type=="TEX_IMAGE").outputs["Alpha"],
                               nodes["Principled BSDF"].inputs["Alpha"])
    emitter(glass,"pane-mask-dark" if closed else "pane-mask",.16)
    ink=material("sign_faces","signs")
    emitter(ink,"sign-mask",.14)
    chain=None
    if closed:
        chain=material("detail_chain_link","chain")
        nodes=chain.node_tree.nodes
        chain.node_tree.links.new(next(n for n in nodes if n.type=="TEX_IMAGE").outputs["Alpha"],
                                  nodes["Principled BSDF"].inputs["Alpha"])
    hidden=bpy.data.materials.new("collision_invisible")
    hidden.use_nodes=True
    hidden.surface_render_method="BLENDED"
    hidden.node_tree.nodes["Principled BSDF"].inputs["Alpha"].default_value=0
    boxes,doors,signs,holes=[],{},{},[]
    lighting=[]

    def collider(label,bounds,parent):
        a,b,c,d,e,f=bounds
        box=block(col,f"collider_{len(boxes):02d}",bounds,path)
        box.data.materials.clear()
        box.data.materials.append(hidden)
        box["collision_only"]=True
        boxes.append({"name":label,"center_gltf_m":[(a+b)/2,(e+f)/2,-(c+d)/2],
                      "size_gltf_m":[b-a,f-e,d-c],"group":"lot" if parent==lot else "building",
                      "attachment":parent.name if parent==lot else ""})

    def barrier(parent,label,bounds,mat):
        block(parent,label,bounds,mat)
        collider(label,bounds,parent)

    def door(label,x,y,yaw=0):
        node=group(label,root)
        node.location=(x,y,FLOOR)
        node.rotation_euler.z=yaw
        doors[label]=[x,FLOOR,-y]

    def sign(parent,label,key,x,y,z,reverse=False,record=True):
        rect=REGIONS[key]
        w,h=rect[2]*T,rect[3]*T
        a,b=(x+w/2,x-w/2) if reverse else (x-w/2,x+w/2)
        panel=plaque(label,parent,[(a,y,z),(b,y,z),(b,y,z+h),(a,y,z+h)],ink,rect,(512,512))
        if record:
            panel["keep"]=True
            signs[label]={"texture":"signs.png","mask":"sign-mask.png","atlas_size_texels":[512,512],
                          "rect_texels":rect,"bottom_left_gltf_m":[a,z,-y],"width_m":w,"height_m":h}

    def front_back(edge,plane,openings,reverse=False):
        previous=edge[0]
        for a,b,lo,hi,window in openings:
            barrier(hut,"Hut structural wall",(previous,a,plane,plane+.19,FLOOR,3.16),cream)
            if window:
                block(hut,"Sash wall under",(a,b,plane,plane+.19,FLOOR,lo),cream)
                block(hut,"Painted sash sill",(a-.10,b+.10,plane-.08,plane+.23,lo-.09,lo-.008),trim)
                collider("Window opening and fixed glass",(a,b,plane,plane+.19,FLOOR,hi),hut)
                glass_y=plane+.098
                coords=[(a,glass_y,lo),(b,glass_y,lo),(b,glass_y,hi),(a,glass_y,hi)]
                mesh("Clubhouse window pane",hut,coords,[(3,2,1,0) if reverse else (0,1,2,3)],glass)
                if not far:
                    x=(a+b)/2
                    block(hut,"Sash mullion",(x-.035,x+.035,plane+.07,plane+.13,lo,hi),trim)
            else:
                for x in (a,b):
                    block(hut,"Entrance casing",(x-.045,x+.045,plane-.03,plane+.22,FLOOR,hi),trim)
            block(hut,"Opening lintel",(a,b,plane,plane+.19,hi,3.16),cream)
            previous=b
        barrier(hut,"Hut structural end",(previous,edge[1],plane,plane+.19,FLOOR,3.16),cream)

    # Grade-level lot, broad circulation paths, and a kept-clear entrance pad.
    block(lot,"Grassy parcel",(*BOUNDS,0,.026),yard)
    pad=oval("statue_pad",root,[(PAD[0],PAD[1],z,rx,ry) for z,rx,ry in
             ((.026,4.99,5.98),(.083,4.99,5.98),(.096,4.88,5.86))],path,24 if near else 16)
    pad["intentionally_clear"]=True
    socket=group("statue_anchor",root)
    socket.location=(*PAD,.096)
    # Break the path at the hut floor to leave the original front and service apertures clear.
    block(hut,"Ticket hut plank floor",(-4.4,4.4,0,6.38,.028,FLOOR),path)
    block(hut,"Street threshold",(-1.05,1.05,-.47,0,.037,FLOOR),path)
    block(hut,"Service threshold",(1.08,3.26,6.38,6.92,.037,FLOOR),path)
    front_back((-4.4,4.4),0,[(-3.83,-1.36,1.05,2.46,True),(-.91,.91,FLOOR,2.58,False),
                              (1.46,3.82,1.05,2.46,True)])
    front_back((-4.4,4.4),6.19,[(1.14,2.94,FLOOR,2.58,False)],True)
    for a,b in ((-4.40,-4.21),(4.21,4.40)):
        barrier(hut,"Clapboard return",(a,b,.19,6.19,FLOOR,3.16),cream)
    block(hut,"Hut ceiling",(-4.21,4.21,.19,6.19,3.0,3.15),cream)
    gable(hut,"Closed front and rear gables",-4.4,4.4,0,6.38,3.16,5.26,cream)
    gable(hut,"Painted shingled hood",-4.69,4.69,-.58,6.75,3.25,5.50,red)
    # The broad case leaves the entrance unobstructed. All lettering is on its own pixel plate.
    block(hut,"Name fascia",(-4.16,4.16,-.66,.06,2.89,5.19),cream)
    sign(hut,"sign_name","name_"+year,0,-.674,2.90)
    sign(hut,"sign_staff","exit",2.06,6.412,2.60,True)
    door("door_main",0,-.41)
    door("door_back",2.04,6.76,pi)
    barrier(hut,"Fixed ticket sales counter",(-3.91,-1.49,3.50,4.80,FLOOR,1.1),cream)
    block(hut,"Counter top",(-4.0,-1.42,3.43,4.87,1.09,1.19),red)
    sign(hut,"sign_tickets","tickets",-1.31,6.177,1.73)
    # Back utility details unobtrusive at the entry.
    if not far:
        block(hut,"Rear downspout",(-4.13,-4.02,6.35,6.55,.13,3.27),trim)
        block(hut,"Rear vent casing",(-2.97,-1.71,6.35,6.53,1.94,2.56),trim)
    if closed:
        # Retain real openings. Board one frontage sash instead of sealing the doorway.
        for zz in (1.36,1.76,2.13):
            block(hut,"Closed window board",(1.31,3.96,-.065,-.029,zz,zz+.19),cream)

    def polygon(x,y,rx,ry,n):
        return [(x+rx*cos(2*pi*i/n),y+ry*sin(2*pi*i/n)) for i in range(n)]

    def contains(point,ring):
        x,y=point
        return sum((a[1]>y)!=(b[1]>y) and
                   x<(b[0]-a[0])*(y-a[1])/(b[1]-a[1])+a[0]
                   for a,b in zip(ring,ring[1:]+ring[:1]))%2

    def skin(name,parent,border,voids,level,mat,controls=()):
        """Constrained tessellation keeps cup rims and divided lanes in the surface."""
        rings=[[p[:2] for p in ring] for ring in (border,*voids)]
        edges,points=[],[]
        for ring in rings:
            start=len(points)
            edges.extend((start+i,start+(i+1)%len(ring)) for i in range(len(ring)))
            points.extend(ring)
        points.extend(controls)
        vertices,_,triangles,*_=delaunay_2d_cdt([Vector(p) for p in points],edges,[],0,1.e-6)
        faces=[]
        for face in triangles:
            center=tuple(sum(vertices[i][j] for i in face)/3 for j in range(2))
            if contains(center,rings[0]) and not any(contains(center,ring) for ring in rings[1:]):
                a,b,c=(vertices[i] for i in face)
                faces.append(face if (b-a).cross(c-a)>0 else face[::-1])
        return mesh(name,parent,[(x,y,level((x,y))) for x,y in vertices],faces,mat)

    def band(name,parent,inner,outer,low,high,mat,edge_skip=(),bottom=None):
        n=len(inner)
        verts=[(x,y,fn((x,y))) for ring,fn in ((outer,high),(inner,high),(outer,low)) for x,y,*_ in ring]
        if bottom is not None:
            verts.extend((x,y,bottom((x,y))) for x,y,*_ in inner)
        faces=[]
        for i in range(n):
            if i in edge_skip:
                continue
            j=(i+1)%n
            faces.extend(((i,j,j+n,i+n),(i+2*n,j+2*n,j,i)))
            if bottom is not None:
                faces.append((i+n,j+n,j+3*n,i+3*n))
        mesh(name,parent,verts,faces,mat)

    def ribbon(name,parent,route,mat,caps=(.42,.42)):
        surface=outline(route,0,caps)
        return skin(name,parent,surface,[],lambda p:surface_height(route,p),mat,[p[:2] for p in route])

    def lamp_face(parent,x,y,z,key,light,watts=0):
        rect=REGIONS[key]
        r=T*rect[2]/2
        if closed:
            flat(parent,"Dark bulb lens",[(x-r,y-r),(x+r,y-r),(x+r,y+r),(x-r,y+r)],z,trim)
            return
        plaque("Glazed bulb lens",parent,[(x-r,y+r,z),(x+r,y+r,z),(x+r,y-r,z),(x-r,y-r,z)],
               ink,rect,(512,512))
        if not far or light.startswith(("lighthouse","windmill")):
            lighting.append({"node":parent.name,"position_gltf_m":[x,z,-y],"colour":light.split("_")[-1],
                             "preview_watts":watts})

    # A continuous path runs around the gardens. Spur landings meet the tees.
    walks=(
        ((-7.9,-7.0,.042,1.28),(0,-4,.042,1.28),(5,-1.65,.039,1.40),(8,4,.043,1.26),(6,10,.044,1.05),
         (8,16,.044,1.25),(10,20,.044,1.14),(15.3,26,.044,1.1),(15.1,33.2,.044,1.24),
         (14,39.3,.044,1.24),(16.5,42.0,.32,1.16),(16.4,47.7,.31,1.09),(19,50,.17,1.09),(23,51,.07,1.16),
         (25.1,55.2,.044,1.09),(19.4,58.1,.044,1.21),(10.1,59.2,.044,1.21),(-1.1,58.8,.044,1.18),
         (-9.9,57.9,.27,1.19),(-16.2,57.4,1.35,1.08),(-20.9,56.9,1.58,1.12),(-26.5,55.4,1.08,1.10),
         (-27.7,50.1,.36,1.08),(-27.8,42.1,.045,1.08),(-27.2,35.6,.045,1.08),(-27.1,28,.045,1.08),
         (-25,23,.045,1.06),(-23.5,19.5,.045,1.12),(-23.1,4.6,.045,1.1),(-21.1,-.44,.042,1.06)),
        ((0,-4,.043,1.12),(0,-1.6,.042,1.10),(0,-.55,.042,1.03)),
        ((2.08,6.88,.045,1.0),(6.13,6.94,.045,1.06),(8.0,8.1,.045,1.06)),
        ((8,16,.047,1.08),(4.4,19.1,.047,1.04),(-3.4,18.75,.045,1.0),(-8.0,20.4,.044,1.04),
         (-14.4,23.35,.047,1.12),(-20,24.05,.043,1.14),(-25,23,.046,1.06)),
        ((-14.4,23.35,.053,1.06),(-14.7,27,.056,1.04),(-10.7,31.1,.060,.99),
         (-8.2,36.9,.15,1.0),(-8.4,43.5,.166,.91)),
        ((10.0,20.7,.054,1.0),(15.9,18.3,.054,1.0),(20.1,16.28,.053,1.04),(25.4,15.75,.051,1.03)),
        ((-9.1,46.4,.16,.93),(-7.2,47.7,.26,.91),(-4.2,48.89,.34,1.04)),
        ((16.0,42.2,.33,1.02),(22.6,38.3,.25,1.00),(24.9,37.65,.18,1.07)),
    )
    for route in walks:
        if max(p[2] for p in route)>.20:
            apron=outline(route,0,(.42,.42))
            toes=outline(route,.42,(.42,.42))
            band("Graded path shoulder",lot,apron,toes,lambda q:.026,
                 lambda q:surface_height(route,q)-.005,yard)
        ribbon("Sweeping pedestrian walkway",lot,route,path)
    bank=((-16,56.4,1.20,2.12),(-20,56.1,1.56,2.30),(-24.7,55.8,1.26,1.88),(-27.4,53.1,.60,1.4))
    crest=outline(bank,.18,(.6,.7))
    toe=outline(bank,1.2,(.6,.7))
    grade=lambda q:surface_height(bank,q)
    band("Northern hill slopes",lot,crest,toe,lambda q:.026,grade,yard)
    skin("North hill crest",lot,crest,[],lambda q:grade(q)-.14,yard,[p[:2] for p in bank])

    bows=[polygon(x,y,.53,.38,6) for x,y in ((-7.83,50.35),(-12.20,53.44))]
    # Each tee and real cup remains at the same transform in every era and LOD.
    for number,(theme,route) in enumerate(ROUTES,1):
        tx,ty,tz,_=route[0]
        cx,cy,cz,_=route[-1]
        rim=polygon(cx,cy,.105,.105,8)
        tracks=track_sections(number,route)
        paint=red if number==7 else blue if number in BLUE_HOLES else turf
        for index,(section,caps) in enumerate(tracks):
            last=index==len(tracks)-1
            border=outline(section,0,caps)
            lip=outline(section,.155,caps)
            level=lambda q:surface_height(section,q,number==6)
            voids=[rim] if last else []
            if number==10:
                voids.extend(bows)
            if number==13:
                voids.append(SPLIT_ISLAND)
            controls=[p[:2] for p in section[1:-1]]
            skin("Hole %02d playing carpet"%number,course,border,voids,level,paint,controls)
            # Colors distinguish the warm painted lip from taller exposed stone cuts.
            bands=trim if number in ROCK_GREENS else red if number in (2,7,16,18) else cream
            band("Painted curb and retaining face",course,border,lip,lambda q:.026,
                 lambda q:level(q)+.091,bands,
                 (len(border)//2-1,) if number==14 and not last else
                 (len(border)-1,) if number==14 else (),bottom=level)
            if last:
                flat(course,"Cup well bottom",rim,cz-.019,trim)
            if closed:
                x,y,z,w=section[len(section)//2]
                dx,dy=heading(section[0],section[-1])
                p=[(x-.77*dx,y-.77*dy),(x-.21*dx-.26*dy,y-.21*dy+.26*dx),(x+.64*dx-.40*dy,y+.64*dy+.40*dx),
                   (x+.91*dx,y+.91*dy),(x+.36*dx-.21*dy,y+.36*dy+.21*dx),(x-.22*dx-.16*dy,y-.22*dy+.16*dx)]
                skin("Broken seam in carpet",course,p,[],lambda q:level(q)+.006,trim)
        dx,dy=heading(route[0],route[1])
        nx,ny=-dy,dx
        corners=[(tx+u*dx+v*nx,ty+u*dy+v*ny) for u,v in ((-.052,-.43),(.052,-.43),(.052,.43),(-.052,.43))]
        flat(course,"Tee mark",corners,tz+.009,cream)
        alt=-1 if number in (4,5,11,14,18) else 1
        sx,sy=tx+alt*(route[0][3]+.66)*nx,ty+alt*(route[0][3]+.66)*ny
        sign(course,f"hole_marker_{number:02d}",f"hole_{number}",sx,sy-.127,tz+.37,False,True)
        block(course,"Number plate back",(sx-.65,sx+.65,sy-.122,sy-.032,tz+.36,tz+1.28),trim)
        block(course,"Number plate leg",(sx-.055,sx+.055,sy-.10,sy+.08,.03,tz+.73),cream)
        holes.append({"hole":number,"tee_gltf_m":[tx,tz+.009,-ty],"cup_gltf_m":[cx,cz-.019,-cy],
                      "obstacle":theme,"route_gltf_m":[[x,z,-y] for x,y,z,_ in route]})
    for number,islands in ((10,bows),(13,[SPLIT_ISLAND])):
        route=ROUTES[number-1][1]
        for boundary in islands:
            floor=lambda p:surface_height(route,p)
            fill=lambda p:floor(p)+.19
            vertices=[(x,y,fn((x,y))) for fn in (floor,fill) for x,y in boundary]
            n=len(boundary)
            faces=[(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)]
            mesh("Island masonry sides",course,vertices,faces,trim)
            skin("Planted raised island",course,boundary,[],fill,yard)

    # Pavers mark the short walks between putting surfaces and the main loop.
    course_footprints=[(i,outline(section,0,caps))
                       for i,(_,route) in enumerate(ROUTES,1) for section,caps in track_sections(i,route)]
    for number,(_,route) in enumerate(ROUTES,1):
        for at,neighbor,reach in ((route[0],route[1],.81),(route[-1],route[-2],1.04)):
            dx,dy=heading(neighbor,at)
            x,y,z,_=at
            if z>.5 or number==14:
                continue
            for i in range(2 if near else 1):
                t=reach+.30+i*.55
                px,py=x+t*dx,y+t*dy
                if any(
                    contains((px,py),other)
                    for index,other in course_footprints if index!=number
                ):
                    break
                paver=[(px+a*dx-b*dy,py+a*dy+b*dx) for a,b in ((-.22,-.48),(.22,-.48),(.22,.48),(-.22,.48))]
                flat(lot,"Paver by the tee or cup",paver,.066-i*.010,path)

    # Creek, pond and footbridge. The same basin stands dry in the closed state.
    ribbon("Dry creek bed" if closed else "Running creek",lot,CREEK,path if closed else water,(.03,.70))
    px,py,rx,ry=POND
    pond_edge=polygon(px,py,rx,ry,16 if near else 12)
    pond_bank=polygon(px,py,rx+.63,ry+.46,16 if near else 12)
    band("Rock pond banks",lot,pond_edge,pond_bank,lambda q:.028,lambda q:.17,trim)
    flat(lot,"Dry silt basin" if closed else "Pond water",pond_edge,.055 if closed else .106,path if closed else water)
    for x,y,z in ((-15.74,47.71,.88),(-15.62,47.56,.60),(-15.47,47.40,.33)):
        block(lot,"Overflow rock step",(x-.81,x+.84,y-.19,y+.25,.027,z),trim)
        if not closed:
            block(lot,"Stepped spillway water",(x-.49,x+.49,y-.212,y+.11,z+.008,z+.054),water)
    for name,bounds in (("Pond catchment",(4.46,13.67,51.4,57.2,.026,.27)),
                        ("Upper spillway",(-16.56,-14.9,47.18,47.99,.026,.88))):
        collider(name,bounds,lot)
    x,y=-8.40,45.12
    bridge=((x-.14,y-1.77,.166,.80),(x-.12,y-.83,.38,.80),(x+.13,y+.91,.38,.80),(x+.14,y+1.62,.18,.80))
    ribbon("Creek footbridge timber deck",lot,bridge,cream)
    for side in (-1,1):
        a,b=x+side*.82-.12,x+side*.82+.13
        for yy in (y-.85,y+.85):
            block(lot,"Footbridge upright",(a,b,yy-.07,yy+.07,.24,1.01),trim)
        beam("Footbridge handrail",lot,(x+side*.82-.12,y-.94,.96),(x+side*.82+.12,y+.94,.96),(.072,.072),(.072,.072),cream,4)
        collider("Footbridge rail",(a-.13,b+.13,y-.95,y+.95,.027,1.02),lot)
    for a,b in zip(CREEK[1:6],CREEK[2:7]):
        # Water is shallow decor. The footbridge and the pond have explicit bounds.
        if not far and not closed:
            x,y,z,_=a
            flat(lot,"Water ripple lip",[(x-.33,y+.19),(x+.26,y+.27),(x+.32,y+.34),(x-.29,y+.29)],z+.02,blue)

    # Hole 14 has a hollow diagonal pipe and different elevations at the mouths.
    a,b=ROUTES[13][1][2:4]
    ax,ay,az,_=a
    bx,by,bz,_=b
    dx,dy=heading(a,b)
    direction=Vector((bx-ax,by-ay,bz-az)).normalized()
    side=Vector((-dy,dx,0))
    up=direction.cross(side)
    outer_radius,inner_radius=.365,.261
    # Bring the inner lip down to the painted curb so an engine ball can enter the mouth.
    start=Vector((ax-.14*dx,ay-.14*dy,az+.308))
    finish=Vector((bx+.14*dx,by+.14*dy,bz+.308))
    n=10 if near else 8
    rings=[[tuple(center+(side*cos(2*pi*i/n)+up*sin(2*pi*i/n))*radius) for i in range(n)]
           for radius in (outer_radius,inner_radius) for center in (start,finish)]
    faces=[]
    for i in range(n):
        j=(i+1)%n
        faces.extend(((i,j,j+n,i+n),(i+2*n,i+3*n,j+3*n,j+2*n),(j,i,i+2*n,j+2*n),(i+n,j+n,j+3*n,i+3*n)))
    pipe=mesh("putt_pipe",obstacles,sum(rings,[]),faces,trim)
    pipe["keep"]=True
    for name,point in (("pipe_entry",start),("pipe_exit",finish)):
        node=group(name,root)
        node.location=point
    holes[13]["pipe_gltf_m"]={"entry":[start.x,start.z,-start.y],"exit":[finish.x,finish.z,-finish.y],
                             "bore_diameter_m":inner_radius*2}

    # The windmill keeps a local +Z-facing shaft. Era changes the blade count.
    x,y=-15.45,16.48
    for a,b in ((x-1.29,x-.69),(x+.69,x+1.29)):
        barrier(obstacles,"Windmill tunnel abutment",(a,b,y-1.03,y+1.02,.027,1.17),cream)
    oval("Tapered windmill millhouse",obstacles,[(x,y,z,r,r) for z,r in
         ((1.16,1.49),(3.00,.92),(3.84,.79))],cream,12 if near else 8)
    oval("Rolled mill bonnet",obstacles,[(x,y,z,r,r) for z,r in
         ((3.72,.90),(3.85,.92),(4.57,.12))],red,12 if near else 8)
    axle=3.06
    beam("Stationary rotor hub",obstacles,(x,y-.70,axle),(x,y-1.01,axle),(.136,.136),(.136,.136),trim,8)
    rotor=group("windmill_blades",root)
    rotor.location=(x,y-1.02,axle)
    rotor["animation_axis_gltf"]="local +Z, facing the driveway"
    for k in range(3 if closed else 4):
        blade=group(f"blade_{k+1}",rotor)
        angle=pi*.25+k*pi*.5
        vx,vz=cos(angle),sin(angle)
        ux,uz=-vz,vx
        verts=[(vx*r+ux*w,yy,vz*r+uz*w) for yy in (-.05,.012) for r,w in
               ((.14,-.09),(.14,.07),(1.92,.32),(1.98,-.12))]
        mesh("Windmill blade fiberglass",blade,verts,[(0,1,2,3),(7,6,5,4),(0,4,5,1),(1,5,6,2),
              (2,6,7,3),(3,7,4,0)],cream)
        if near:
            beam("Blade scarlet spar",blade,(vx*.28,-.065,vz*.28),(vx*1.80,-.065,vz*1.80),(.034,.031),(.034,.031),red,4)
    batch(rotor)
    spotlight=group("windmill_spot_amber",obstacles)
    spotlight.location=(x-.28,y-2.78,.29)
    block(spotlight,"Windmill spot housing",(-.24,.24,-.19,.19,-.16,.012),trim)
    lamp_face(spotlight,0,0,.018,"lamp_amber",spotlight.name,345)
    if not closed:
        lighting[-1]["position_gltf_m"]=[x-.28,.37,-(y-2.78)]
        lighting[-1]["target_gltf_m"]=[x,2.0,-y]

    # Lighthouse lantern, off the putting line so the shot can skirt the base.
    x,y=18.5,10.94
    for a,b,r,s,mat in ((.13,1.48,.77,.66,cream),(1.48,2.18,.66,.57,red),
                       (2.18,3.05,.57,.48,cream),(3.05,3.68,.48,.45,red),
                       (3.68,4.24,.55,.55,trim),(4.24,4.90,.65,.15,red)):
        oval("Lighthouse band",obstacles,[(x,y,a,r,r),(x,y,b,s,s)],mat,10 if not far else 8)
    collider("Lighthouse tower",(x-.79,x+.79,y-.79,y+.79,.027,4.9),obstacles)
    lantern=group("lighthouse_beacon_white",obstacles)
    lantern.location=(x,y,3.91)
    for k in range(8):
        angle=2*pi*k/8
        u,v=cos(angle),sin(angle)
        r=.556
        key=REGIONS["lamp_white"]
        half=key[2]*T/2
        if closed:
            continue
        plaque("Lighthouse lantern lens",lantern,[(r*u+half*v,r*v-half*u,-.24),
             (r*u-half*v,r*v+half*u,-.24),(r*u-half*v,r*v+half*u,-.24+2*half),
             (r*u+half*v,r*v-half*u,-.24+2*half)],ink,key,(512,512))
    if not closed:
        lighting.append({"node":lantern.name,"position_gltf_m":[x,3.91,-y],"colour":"white","preview_watts":195})

    # Peak and open crater. An unbroken red seam traces one continuous flow down the flank.
    x,y=22.65,32.65
    rings=[]
    count=10 if near else 8 if not far else 6
    for z,r in ((.11,2.31),(1.20,2.50),(3.02,1.71),(4.82,.94),(5.98,.73),(5.85,.49)):
        ring=[]
        for i in range(count):
            angle=2*pi*i/count-pi*.48
            radius=r*(1+.125*cos(3*angle))
            ring.append((x+radius*cos(angle),y+radius*sin(angle),z+(.08*cos(2*angle) if z>.2 else -.082)))
        rings.append(ring)
    loft("Volcano cone with lava fissure",obstacles,rings,lambda k,j:red if j in (0,count-1) else trim,False)
    flat(obstacles,"Recessed crater",[(a,b) for a,b,_ in rings[-1]],5.72,red)
    collider("Volcano core",(21.46,23.78,31.47,33.85,.027,5.98),obstacles)

    # The covered bridge actually spans the longitudinal slope of hole 12.
    x,y=-23.95,34.40
    dx,dy=heading(ROUTES[11][1][1],ROUTES[11][1][2])
    bridge_hall=group("covered_bridge",obstacles)
    bridge_hall.location=(x,y,.175)
    bridge_hall.rotation_euler.z=atan2(-dx,dy)
    for side in (-1,1):
        xx=side*1.06
        block(bridge_hall,"Covered bridge low sill",(xx-.11,xx+.11,-1.48,1.48,.03,.69),cream)
        for yy in (-1.31,1.31):
            block(bridge_hall,"Bridge load-bearing posts",(xx-.079,xx+.079,yy-.096,yy+.096,.68,2.11),trim)
    gable(bridge_hall,"Covered bridge roof",-1.43,1.43,-1.65,1.65,2.10,3.01,red)
    for side in (-1,1):
        k=x+side*1.11*(-dy)
        n=y+side*1.11*dx
        collider("Covered bridge side rail",(k-.31,k+.31,n-1.13,n+1.13,.027,.89),obstacles)

    # Raised drawbridge leaves a putt-size gate, framed by towers and a genuine segmented arch.
    x,y=-6.03,24.20
    gate=group("castle_drawbridge",obstacles)
    gate.location=(x,y,.141)
    gate.rotation_euler.z=-pi*.39
    for xx in (-1.52,1.52):
        oval("Gate turret",gate,[(xx,0,z,r,r) for z,r in
             ((.0,.87),(2.62,.76),(2.85,.94),(3.15,.91))],trim,10 if near else 8)
        for i in range(6 if not far else 4):
            angle=i*pi/(3 if not far else 2)
            px,py=xx+.73*cos(angle),.73*sin(angle)
            block(gate,"Castle crenellation",(px-.15,px+.15,py-.15,py+.15,3.13,3.54),cream)
    for i in range(7):
        # Vaulted sections with an open aperture from turf through the drawbridge.
        a,b=i*pi/7,(i+1)*pi/7
        ends=[[(r*cos(t),yy,1.02+r*sin(t)) for r,t in ((.92,a),(1.27,a),(1.27,b),(.92,b))]
              for yy in (-.68,.68)]
        loft("Arch stone",gate,ends,cream if i%2 else trim)
    flat(gate,"Painted drawbridge deck",[(-.87,-1.69),(.87,-1.69),(.87,.74),(-.87,.74)],.031,cream)
    for xx in (-1.52,1.52):
        cx,cy=x+xx*cos(-pi*.39),y+xx*sin(-pi*.39)
        collider("Castle turret",(cx-.78,cx+.78,cy-.78,cy+.78,.028,3.69),obstacles)
        beam("Drawbridge tie bar",gate,(xx*.57,-1.43,.054),(xx*.64,-.63,1.63),(.045,.043),(.045,.043),trim,4)
    block(gate,"Upper gatehouse parapet",(-2.13,2.13,-.83,-.43,2.37,3.34),trim)
    if closed:
        sign(gate,"castlegraf","graffiti",0,-.839,2.39,False,False)

    # A low red twin-pier gantry carries the giant dimpled finale ball over the putt.
    x,y=12.5,27.22
    for xx in (x-1.81,x+1.81):
        barrier(obstacles,"Finale pillar",(xx-.34,xx+.34,y-.54,y+.54,.035,3.38),red)
    block(obstacles,"Finale arched lintel",(x-2.39,x+2.39,y-.567,y+.43,2.62,3.58),cream)
    block(obstacles,"Finale trophy cup plinth",(x-1.24,x+1.24,y-.92,y+.92,3.39,3.86),trim)
    r=2.11
    center=5.90
    ball_rings=[(x,y,center+factor*r,r*(1-factor*factor)**.5,r*(1-factor*factor)**.5)
                for factor in (-.985,-.9,-.62,-.30,0,.30,.62,.9,.985)]
    oval("Giant dimple-pixel golf ball",obstacles,ball_rings,ball,16 if near else 12 if not far else 10)
    sign(obstacles,"sign_finale","last",x,y-.577,2.64)

    # Group the planting by theme so it reads as garden rooms from above.
    rock_sites=((-21.1,12.4,1.34),(-10.4,9.7,1.15),(-6.3,15.9,1.04),
                (10.3,13.25,1.34),(21.9,15.2,.93),(23.2,24.7,1.16),
                (19.6,33.3,1.01),(26.6,38.1,.96),(21.1,43.8,1.03),
                (16.1,51.7,1.48),(10.7,57.95,1.40),(5.4,57.3,1.18),
                (-2.7,46.2,.94),(-17.3,49.8,1.37),(-17.5,39.6,1.04),
                (-18.1,33.5,1.24),(-12.9,28.4,1.08),(-1.2,29.7,1.16),
                (8.1,26.1,.92),(10.0,22.0,1.05),(18.1,55.6,1.37))
    rng=Random(720)
    for i,(x,y,r) in enumerate(rock_sites):
        if far and i%3==1 or lod=="mid" and i%5==1:
            continue
        for j in range(2 if near else 1):
            px,py=x+(j-.28)*.42*r,y-j*1.35*r
            size=r*(.60 if j else 1)
            rings=[(px,py,.035+z*size,size*w,size*.72*w) for z,w in ((0,.71),(.36,1.0),(.87,.61))]
            oval("Faceted garden boulder",lot,rings,trim,5)
    tree_sites=((-26.5,1.0),(-20.2,2.35),(10.3,2.3),(26.1,7.0),
                (-27.2,20.34),(-21.0,40.2),(-16.6,46.2),(-3.55,55.95),
                (17.2,56.1),(25.9,51.5),(27.4,28.6),(7.6,19.9))
    for i,(x,y) in enumerate(tree_sites,1):
        # Even the far asset keeps the tree sockets for optional replacement.
        root_tree=group(f"tree_{i:02d}",lot)
        root_tree.location=(x,y,.027)
        h=.90+.17*(i%4)
        oval("Octagonal planter",root_tree,[(0,0,0,.61,.61),(0,0,.48,.72,.72),(0,0,.58,.71,.71)],
             cream if i%3 else red,8 if near else 6)
        beam("Tree trunk",root_tree,(0,0,.51),(0,0,2.49*h),(.088,.088),(.082,.082),cream,5)
        if i%3 in (0,1):
            for tier in range(2 if far else 3):
                z=(1.54+tier*.62)*h
                w=(1.16-tier*.24)*h
                oval("Tiered pine crown",root_tree,[(0,0,z,w,w),(0,0,z+.98*h,.17*w,.17*w)],yard,5)
        else:
            for j in range(5 if near else 4):
                angle=j*2*pi/(5 if near else 4)+(i%3)*.24
                u,v=cos(angle),sin(angle)
                center=2.32*h
                frond=[(0,0,center),(u*.63-v*.23,v*.63+u*.23,center+.23),(u*1.53,v*1.53,center-.29),
                       (u*.63+v*.23,v*.63-u*.23,center+.23),(u*.62,v*.62,center+.29)]
                mesh("Palm frond",root_tree,frond,[(0,1,4),(1,2,4),(2,3,4),(3,0,4)],yard)
        if i in (1,4,6,8,10,11):
            collider("Tree planter",(x-.68,x+.68,y-.68,y+.68,.027,.61),lot)
    gardens=((-18.6,10.9,1.67,.71),(-10.5,19.45,1.53,.67),(2.6,8.35,1.35,.65),
             (15.9,12.7,1.30,.61),(26.85,24.0,1.45,.76),(17.25,32.3,1.12,.71),
             (14.55,52.8,1.03,.69),(-10.35,45.7,.91,.58),(-22.1,26.99,1.15,.56),
             (-.1,34.75,.95,.58),(-27.6,33.2,.87,.61))
    for i,(x,y,rx,ry) in enumerate(gardens):
        oval("Bed edging",lot,[(x,y,.035,rx,ry),(x,y,.177,rx,ry)],red if i%2 else cream,8)
        oval("Plant bed soil",lot,[(x,y,.18,rx*.87,ry*.84),(x,y,.21,rx*.85,ry*.82)],trim,8)
        if not far:
            for j in range(4 if near else 3):
                px=x+rx*(j/2.6-.55)
                py=y+ry*(.23 if j%2 else -.18)
                z=.39+(.15 if closed else .07)*(j%2)
                mesh("Low shrub",lot,[(px-.20,py,.21),(px,py-.17,.22),(px+.19,py,.21),(px,py+.21,.22),(px,py,z)],
                     [(0,1,4),(1,2,4),(2,3,4),(3,0,4)],yard)
                if not closed:
                    flat(lot,"Flower cluster",polygon(px,py,.12,.104,5),z+.006,red if (j+i)%3 else cream)

    for i,(x,y) in enumerate(((5.7,2.4),(8.7,18.1),(16.4,31.1),(22.9,55.6),(-17.9,58.7),
                             (-27.4,40.6),(-23.9,25.3),(-9.4,21.55),(-8.15,44.05),(-23.0,3.45))):
        tone=("rose","mint","amber")[i%3]
        anchor=group(f"path_bulb_{i+1:02d}_{tone}",lot)
        anchor.location=(x,y,.027)
        block(anchor,"Painted pathway post",(-.062,.062,-.061,.061,0,2.25),trim)
        block(anchor,"Lamp cap",(-.33,.33,-.32,.32,2.21,2.37),cream)
        lamp_face(anchor,0,0,2.205,"lamp_"+tone,anchor.name,182 if year=="1962" else 52)
        if not closed and not far:
            lighting[-1]["position_gltf_m"]=[x,2.18,-y]
        if i in (0,1,2,4,6,7):
            collider("Path lamp",(x-.078,x+.078,y-.081,y+.081,.027,2.40),lot)
    for x,y,z in ((8.5,5.4,.036),(17.7,39.1,.038),(18.45,56.52,.038),(-26.14,20.75,.038)):
        for dx in (-.75,.75):
            block(lot,"Bench support",(x+dx-.09,x+dx+.09,y-.25,y+.24,z,z+.48),trim)
        block(lot,"Bench seat",(x-1.03,x+1.03,y-.27,y+.28,z+.43,z+.56),cream)
        if not far:
            block(lot,"Bench back",(x-1.03,x+1.03,y+.16,y+.27,z+.58,z+1.02),red)
        if x>10 and y>50:
            collider("Bench and planter",(16.5,19.48,55.40,56.81,.027,1.02),lot)
        elif x<-10:
            collider("Bench and planter",(-27.92,-25.11,19.64,21.04,.027,1.02),lot)
        elif x>10:
            collider("Garden bench",(x-1.03,x+1.03,y-.27,y+.28,z,z+1.02),lot)

    if closed:
        # Bowed top rail, a torn opening near the old entrance, and opaque vegetation at the edges.
        segments=(
            ((-29.8,-8.86),(-13.7,-8.86),(-4,-8.86)),
            ((-.60,-8.86),(13,-8.86),(29.8,-8.86)),
            ((-29.8,60.81),(0,60.81),(29.8,60.81)),
            ((-29.8,-8.86),(-29.8,23),(-29.8,60.81)),
            ((29.8,-8.86),(29.8,27),(29.8,60.81)),
        )
        for j,(start,mid,finish) in enumerate(segments):
            profiles=(1.82,1.25 if j in (0,1) else 1.53,1.71)
            for index,(a,b) in enumerate(zip((start,mid),(mid,finish))):
                v0,v1=profiles[index:index+2]
                verts=[(*a,.027),(*b,.027),(*b,v1),(*a,v0)]
                mesh("Sagging galvanized mesh",lot,verts,[(0,1,2,3)],chain)
                beam("Galvanized slack top wire",lot,(*a,v0+.02),(*b,v1+.02),(.024,.022),(.024,.022),trim,4)
                count=max(2,int(hypot(a[0]-b[0],a[1]-b[1])/(6 if near else 10)))
                for k in range(count):
                    t=k/count
                    x,y=(a[d]+t*(b[d]-a[d]) for d in range(2))
                    height=v0+t*(v1-v0)+.09
                    block(lot,"Fence post",(x-.045,x+.045,y-.045,y+.045,.027,height),trim)
            x0,x1=sorted((start[0],finish[0]))
            y0,y1=sorted((start[1],finish[1]))
            collider("Closed fence segment",(x0-.085,x1+.085,y0-.085,y1+.085,.027,1.86),lot)
        gate_x=3.58
        block(lot,"Closed gate sign backing",(gate_x-2.34,gate_x+2.34,-8.968,-8.884,.56,1.84),cream)
        sign(lot,"sign_closed","closed",gate_x,-8.984,.585)
        weeds=[]
        for n,(_,route) in enumerate(ROUTES,1):
            for i,(x,y,z,w) in enumerate(route[1:-1]):
                if (n+i)%3==0 or near:
                    weeds.append((x+.39*w,y-.35*w,z+.012,.73))
        for j in range(62 if near else 32 if not far else 19):
            side=j%4
            x=rng.uniform(-28.7,28.7) if side<2 else (28.65 if side==2 else -28.65)
            y=rng.uniform(-5.4,59.65) if side>=2 else (-8.03 if side==0 else 59.75)
            if (-18.45<x<-8.55 and -8.75<y<3.1):
                continue
            weeds.append((x,y,.036,1.4))
        for i,(x,y,z,strength) in enumerate(weeds):
            if not near and i%2==1:
                continue
            verts=[]
            for j in range(6 if not far else 4):
                angle=j*2*pi/6+rng.uniform(-.3,.3)
                r=rng.uniform(.15,.45)*strength
                verts.extend(((x-.045*sin(angle),y+.045*cos(angle),z),
                              (x+.048*sin(angle),y-.048*cos(angle),z),
                              (x+r*cos(angle),y+r*sin(angle),z+rng.uniform(.34,1.07)*strength)))
            mesh("Overgrown carpet and boundary grass",lot,verts,[tuple(range(k,k+3)) for k in range(0,len(verts),3)],yard)

    for parent in (hut,course,obstacles,lot):
        batch(parent)
    file="thimble-trails-"+year+("" if near else "-"+lod)
    drawn=[ob for ob in bpy.data.objects if ob.type=="MESH" and not ob.get("collision_only")]
    tris=sum(len(p.vertices)-2 for ob in drawn for p in ob.data.polygons)
    export_glb(ROOT/f"{file}.glb")
    if near:
        save_source(ROOT/f"{file}.blend")
    return {"file":f"{file}.glb","lod":lod,"triangles_visible":tris,"door_nodes_gltf_m":doors,
            "collision_boxes_gltf_m":boxes,"sign_panels":signs,"holes":holes,
            "lights":lighting,"materials":sorted({m.name for ob in drawn for m in ob.data.materials})}


meta={"task":"C007","name":"Thimble Trails Mini Golf","footprint_m":[60,70],"height_m":8.05,
      "coordinates":"Metres. +Y up, entrance facing +Z, grade origin on front wall midpoint.",
      "lot_bounds_gltf_m":{"x":[-30,30],"y":[0,8.05],"z":[-61,9.0]},
      "statue_pad":{"node":"statue_pad","anchor_node":"statue_anchor","usable_size_m":[9.76,11.72],
                    "center_gltf_m":[PAD[0],.096,-PAD[1]]},
      "windmill":{"node":"windmill_blades","axis_local_gltf":[0,0,1],
                  "pivot_gltf_m":[-15.45,3.06,-15.46],"suggested_period_s":6.0,
                  "closed_state":"Leave stationary"},
      "course_note":"Stable tee and cup positions across eras. Independent engine routing, ball traversal and closure gating.",
      "swatches":["roof_membrane_green","roof_membrane_grey","pavement","plank_horizontal","roof_tin_red"],
      "lod_distances_suggested_m":{"mid":55,"far":135},"states":[]}
for year,(name,start,end) in STATES.items():
    models=[build(year,lod) for lod in ("near","mid","far")]
    print(year,[m["triangles_visible"] for m in models])
    meta["states"].append({"id":year,"title":name,"era":[start,end],"models":models})
(ROOT/"model-info.json").write_text(json.dumps(meta,indent=2)+"\n")
