"""Build both Needle Point era states, with common siting and independent GLBs."""
from math import cos, sin, pi
from pathlib import Path
from random import Random
import json
import os
import sys

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from sculpture_tools import group, material, mesh, loft, oval, box, beam, plaque
from revision_common import batch, export_glb, save_source

HERE=24.2
args=os.environ.get("NEEDLE_STATE")
states=(args,) if args else ("historic","modern")

def slab(name,parent,x0,x1,y0,y1,z0,z1,mat):
    return box(name,parent,((x0+x1)/2,(y0+y1)/2,(z0+z1)/2),
               (x1-x0,y1-y0,z1-z0),mat)

def post(name,parent,x,y,z0,z1,r,mat,n=8):
    return oval(name,parent,[(x,y,z0,r,r),(x,y,z1,r,r)],mat,n=n)

def roof_panel(name,parent,x0,x1,y0,y1,z0,z1,thickness,mat):
    return loft(name,parent,[[(x0,y0,z0-q),(x1,y0,z1-q),(x1,y1,z1-q),(x0,y1,z0-q)]
                            for q in (thickness,0)],mat)

def wrap(parent,points,low,high,material):
    for i,(a,b) in enumerate(zip(points,points[1:])):
        beam("Continuous guard bar",parent,(*a,high),(*b,high),(.045,.050),(.045,.050),material,n=4)
        beam("Lower guard bar",parent,(*a,low+.42),(*b,low+.42),(.031,.038),(.031,.038),material,n=4)
        post("Railing post",parent,*a,low,high,.037,material,n=4)
    post("Railing post",parent,*points[-1],low,high,.037,material,n=4)

def fit_ring(cx,cy,r,y,n=12):
    return [(cx+r*cos(-pi/2-pi/n+i*2*pi/n),cy+r*sin(-pi/2-pi/n+i*2*pi/n),y) for i in range(n)]

def annular_slab(name,parent,cx,cy,outer,inner,z0,z1,mat,n=12):
    rings=[fit_ring(cx,cy,r,z,n) for r,z in ((outer,z0),(outer,z1),(inner,z1),(inner,z0))]
    loft(name,parent,rings+[rings[0]],mat,ends=False)

for state in states:
    if state not in {"historic","modern"}:
        raise ValueError(state)
    modern=state=="modern"
    bpy.ops.wm.read_factory_settings(use_empty=True)
    root=group("Needle Point Light")
    tower=group("light_tower",root)
    annex=group("service_annex" if modern else "keepers_cottage",root)
    lantern=group("lantern_room",root)
    stairs=group("maintenance_stairs",root)
    lot=group("lot",root)
    rocksite=group("optional_headland",lot)
    walls=material("wall_tower","concrete" if modern else "whitewash")
    siding=material("wall_annex","cladding" if modern else "siding")
    roof=material("roof_lantern","roof-blue" if modern else "red")
    cottage_roof=roof if modern else material("roof_cottage","cottage-roof")
    iron=material("detail_iron","iron")
    glass=material("glass_lantern","glass")
    glow=material("glow_lens","ivory-lens" if modern else "amber",.52)
    stone=material("wall_headland","rock")
    path=material("ground_walk","path")
    grass=material("ground_grass","grass")
    timber=material("floor_timber","timber")
    lettering=material("sign_name","sign")
    anchors={}
    boxes=[]
    steps=[]

    def mark(name,x,y,z,**props):
        ob=group(name,root)
        ob.location=(x,y,z)
        anchors[name]={"gltf_m":[x,z,-y],**props}
        return ob

    def bounds(name,x0,x1,y0,y1,z0,z1,attachment):
        boxes.append({"name":name,"center_gltf_m":[(x0+x1)/2,(z0+z1)/2,-(y0+y1)/2],
                      "size_gltf_m":[x1-x0,z1-z0,y1-y0],"attachment":attachment})

    def wall(name,parent,x0,x1,y0,y1,z0,z1,mat):
        slab(name,parent,x0,x1,y0,y1,z0,z1,mat)
        bounds(name,x0,x1,y0,y1,z0,z1,parent.name)

    def sign(name,parent,x,y,z,row,w,h):
        width,height=w*.0776,h*.0776
        ob=plaque(name,parent,[(x-width/2,y,z),(x+width/2,y,z),
                   (x+width/2,y,z+height),(x-width/2,y,z+height)],lettering,(0,row*2,w*2,h*2),(256,128))
        ob["keep"]=True

    # Shared top grade and ground contacts let the two versions swap in place.
    outline=[(-13,-.02),(-5,-.02),(3,-.02),(11,.43),(14,5),(14.9,12),(12.6,19.3),
             (10.3,25.7),(5.35,31.6),(-2.4,34.4),(-9,31.0),(-13.4,25.7),
             (-15,18),(-15.4,9),(-14.7,3)]
    rng=Random(890)
    rings=[]
    for level,scale in [(-7.6,1.18),(-4.4,1.09),(-1.45,1.04),(-.06,1)]:
        ring=[]
        for x,y in outline:
            h=level+(rng.uniform(-.78,.78) if level<-.07 else 0)
            ring.append((x*scale,16+(y-16)*scale,h))
        rings.append(ring)
    loft("Layered sea-cut headland",rocksite,rings,stone)
    turf=[(x*.969,16+(y-16)*.989,.002) for x,y in outline]
    mesh("Flat grass cap",rocksite,turf,[tuple(range(len(turf)))],grass)
    for i in range(37):
        edge=rng.randrange(3,len(outline)-1)
        a,b=outline[edge],outline[(edge+1)%len(outline)]
        t=rng.uniform(.13,.86)
        x=a[0]+(b[0]-a[0])*t
        y=a[1]+(b[1]-a[1])*t
        h=rng.uniform(-6.45,-3.2)
        x*=1.12
        y=16+(y-16)*1.12
        rx,ry=rng.uniform(.56,1.8),rng.uniform(.60,2.0)
        oval("Basalt talus rock",rocksite,[(x,y,h-1.17,rx,ry),(x,y,h-.15,rx*1.04,ry*1.08),
             (x+.17,y-.20,h+.42,rx*.68,ry*.66)],stone,n=5)
    slab("Path from town",root,-1.10,1.1,0,22.55,.008,.05,path)
    slab("Tower landing threshold",tower,-1.12,1.12,20.9,23.1,.04,.16,path)
    slab("Annex crosswalk",root,-12.3,10.9,8.05,9.53,.011,.059,path)
    slab("Headland overlook pavement",lot,-3.58,4.78,27.5,30.42,.011,.04,path)
    sign_x=7.39 if modern else -6.37
    slab("Low site sign backing",lot,sign_x-4.37,sign_x+4.37,3.28,3.46,.99,2.22,iron)
    sign("sign_name",lot,sign_x,3.265,1.04,0,111,15)
    for x in (sign_x-3.22,sign_x+3.22):
        post("Sign leg",lot,x,3.38,.02,1.04,.071,iron,4)
        bounds("Sign leg",x-.073,x+.073,3.30,3.46,.02,1.04,lot.name)
    for a in [[(-11.7,23.4),(-10.1,26.7),(-6.5,29.0),(-2.8,30.75)],
              [(1.1,31.2),(4.63,30.6),(7.7,28.15),(9.1,25.6),(10.58,22.05)]]:
        points=[]
        for p,q in zip(a,a[1:]):
            points.extend([p,((p[0]+q[0])/2,(p[1]+q[1])/2)])
        points.append(a[-1])
        wrap(lot,points,.016,1.02,iron)
    for x,y in ((-4.6,7.86),(4.7,7.88),(-3.0,29.2),(3.68,29.15)):
        slab("Slatted lookout bench",lot,x-.81,x+.81,y-.27,y+.27,.48,.58,timber)
        for dx in (-.60,.60):
            slab("Bench support",lot,x+dx-.068,x+dx+.068,y-.22,y+.22,.042,.49,iron)
        bounds("Fixed bench",x-.81,x+.81,y-.27,y+.27,.04,.58,lot.name)

    def facade(parent, axis, at, start, end, openings, zbase, height, mat):
        """Openings are horizontal intervals with bottom/top levels."""
        thickness=.26
        def section(lo,hi,bot,top,name,material,clipping=True):
            if hi-lo<.005 or top-bot<.005:
                return
            coords=(lo,hi,at-thickness/2,at+thickness/2) if axis=="y" else (at-thickness/2,at+thickness/2,lo,hi)
            if clipping:
                wall(name,parent,*coords,bot,top,material)
            else:
                slab(name,parent,*coords,bot,top,material)
        last=start
        for lo,hi,l,h,kind in sorted(openings):
            section(last,lo,zbase,height,"Exterior wall",mat)
            section(lo,hi,zbase,l,"Opening sill",mat)
            section(lo,hi,h,height,"Opening lintel",mat)
            if kind=="window":
                section(lo,hi,l,h,"Window glazing",glass)
                m=(lo+hi)/2
                section(m-.033,m+.033,l,h,"Window center mullion",iron,False)
            last=hi
        section(last,end,zbase,height,"Exterior wall",mat)

    if not modern:
        x0,x1,y0,y1=-12.1,-4.55,9.05,18.6
        h=3.08
        slab("Keeper floor",annex,x0,x1,y0,y1,.01,.13,timber)
        facade(annex,"y",y0,x0,x1,[(-11.3,-10,1.03,2.40,"window"),(-8.83,-7.61,.13,2.37,"door"),
               (-6.54,-5.28,1.03,2.40,"window")],.13,h,siding)
        facade(annex,"y",y1,x0,x1,[(-11.11,-9.99,1.0,2.26,"window"),(-7.28,-6.15,1.0,2.26,"window")],.13,h,siding)
        for x in (x0,x1):
            facade(annex,"x",x,y0+.16,y1-.16,[(12.6,14.05,.96,2.26,"window")],.13,h,siding)
        for yy in (y0,y1):
            v=[(x0,yy,h-.05),(x1,yy,h-.05),((x0+x1)/2,yy,5.43)]
            mesh("Clapboard gable",annex,v,[(0,1,2) if yy==y0 else (2,1,0)],siding)
        mid=(x0+x1)/2
        for a,b,ha,hb in ((x0-.47,mid,3.1,5.52),(mid,x1+.47,5.52,3.1)):
            roof_panel("Standing seam cottage roof",annex,a,b,y0-.46,y1+.45,ha,hb,.20,cottage_roof)
            for yy in (y0-.47,y1+.46):
                beam("Gable verge board",annex,(a,yy,ha-.055),(b,yy,hb-.055),(.082,.095),(.082,.095),walls,4)
        slab("Cottage flashing and chimney",annex,-6.3,-5.53,15.12,16.0,3.97,6.01,roof)
        slab("Cottage chimney cap",annex,-6.41,-5.42,15.03,16.11,5.98,6.13,iron)
        slab("Cottage porch",annex,-10.94,-5.61,7.61,9.10,.041,.13,timber)
        roof_panel("Low keeper porch roof",annex,-11.2,-5.38,7.48,9.21,2.71,2.79,.19,roof)
        for x in (-10.72,-5.81):
            post("Porch post",annex,x,7.75,.13,2.57,.081,walls,4)
        mark("door_keeper",-8.22,8.65,.13,clear_width_m=1.22)
        slab("Keeper wall lantern",annex,-7.28,-7.11,8.78,8.94,1.91,2.15,glow)
        sign("sign_keeper",annex,-8.22,8.826,2.42,17,83,13)
    else:
        x0,x1,y0,y1=3.68,11.91,11.57,20.10
        slab("Equipment room finished floor",annex,x0,x1,y0,y1,.016,.12,path)
        slab("Modern annexe approach",root,3.68,9.72,9.45,11.7,.011,.12,path)
        facade(annex,"y",y0,x0,x1,[(4.75,6.10,.12,2.41,"door"),(6.71,11.52,.86,2.86,"window")],.12,3.45,siding)
        facade(annex,"y",y1,x0,x1,[(7.75,10.55,1.31,2.57,"window")],.12,3.45,siding)
        for x in (x0,x1):
            facade(annex,"x",x,y0+.15,y1-.15,[(14.5,18.43,1.19,2.70,"window")],.12,3.46,siding)
        roof_panel("Wing-shaped roof",annex,3.24,12.35,10.75,20.65,3.67,4.10,.27,roof)
        for x in (3.60,12.02):
            post("Annex canopy pin",annex,x,10.95,.12,3.48,.08,iron,4)
        sign("sign_service",annex,5.40,11.425,2.53,47,79,13)
        mark("door_service",5.43,11.18,.12,clear_width_m=1.35)
        slab("Service door wall lamp",annex,6.35,6.57,11.39,11.45,2.38,2.57,glow)

    if not modern:
        profile=[(.15,2.37),(.45,2.36),(2.40,2.33),(3.10,2.27),(5.15,2.14),
                 (5.93,2.08),(8.85,1.97),(9.64,1.93),(12.45,1.83),(13.30,1.80),
                 (16.70,1.71),(17.12,1.72)]
        windows={(4,0),(6,4),(8,8)}
        for j,((z0,r0),(z1,r1)) in enumerate(zip(profile,profile[1:])):
            oa,ob=fit_ring(0,HERE,r0,z0),fit_ring(0,HERE,r1,z1)
            ia,ib=fit_ring(0,HERE,r0-.29,z0),fit_ring(0,HERE,r1-.29,z1)
            for i in range(12):
                k=(i+1)%12
                def wall_slice(aa,bb,lo,hi,mat):
                    v=[Vector(aa[i]).lerp(aa[k],t) for t in (lo,hi)]+[Vector(bb[i]).lerp(bb[k],t) for t in (hi,lo)]
                    if aa is ia:
                        v.reverse()
                    mesh("Tapered tower wall",tower,v,[(0,1,2,3)],mat)
                if (j,i)==(1,0):
                    for o,inset in ((0,1),(1,0)):
                        a=(i,k)[o]
                        mesh("Masonry entry reveal",tower,[oa[a],ob[a],ib[a],ia[a]],[(0,1,2,3) if inset else (3,2,1,0)],walls)
                    continue
                if (j,i) in windows:
                    for low,high in ((0,.23),(.77,1)):
                        wall_slice(oa,ob,low,high,walls)
                        wall_slice(ia,ib,low,high,walls)
                    wall_slice(ia,ib,.22,.78,glass)
                    # Stone trims cast a one-pixel border around the inset panes.
                    for t in (.21,.79):
                        a=Vector(oa[i]).lerp(oa[k],t)
                        b=Vector(ob[i]).lerp(ob[k],t)
                        beam("Slot window jamb",tower,a,b,(.045,.05),(.043,.05),roof,4)
                else:
                    wall_slice(oa,ob,0,1,walls)
                    wall_slice(ia,ib,0,1,walls)
        oval("Historic tower entrance floor",tower,[(0,HERE,.053,2.31,2.31),(0,HERE,.15,2.31,2.31)],path,n=12)
        # Door frame intentionally surrounds a real mesh opening.
        for x in (-.60,.60):
            slab("Tower stone entry jamb",tower,x-.073,x+.073,21.82,22.06,.15,2.38,roof)
        slab("Historic entry lintel",tower,-.68,.68,21.81,22.08,2.35,2.51,roof)
        mark("door_main",0,21.50,.16,clear_width_m=1.16)
        for z,r,mat in ((.18,2.40,walls),(3.14,2.35,walls),(16.6,1.85,walls),(17.25,2.03,walls)):
            annular_slab("Historic masonry band",tower,0,HERE,r,r-.23,z,z+.16,mat)
        # Eight short corbels express the load path from watch gallery to tower.
        for i in range(12):
            a=(i+.5)*2*pi/12
            beam("Masonry lookout corbel",tower,(1.66*cos(a),HERE+1.66*sin(a),16.80),
                 (2.16*cos(a),HERE+2.16*sin(a),17.58),(.12,.18),(.12,.18),walls,4)
        annular_slab("Historic watch gallery slab",lantern,0,HERE,2.50,.76,17.54,17.72,path,n=16)
        rail_ring=fit_ring(0,HERE,2.41,17.72,16)
        wrap(lantern,[p[:2] for p in rail_ring+rail_ring[:1]],17.72,18.72,iron)
        annular_slab("Historic lantern plinth",lantern,0,HERE,1.48,.62,17.70,18.42,roof)
        # Faceted Fresnel lens on its plinth, visible through the revolving lantern's panes.
        oval("Fresnel pedestal",lantern,[(0,HERE,18.38,.46,.46),(0,HERE,19.13,.38,.38),
             (0,HERE,19.32,.31,.31)],iron,n=12)
        oval("Fresnel lens ribs",lantern,[(0,HERE,19.30,.40,.40),(0,HERE,19.44,.61,.61),
             (0,HERE,19.92,.65,.65),(0,HERE,20.38,.59,.59),(0,HERE,20.56,.34,.34)],glow,n=16)
        rings=[fit_ring(0,HERE,r,z) for r,z in ((1.43,18.42),(1.43,20.91))]
        for i in range(12):
            k=(i+1)%12
            a,b=rings[0][i],rings[1][i]
            mesh("Historic lantern sash pane",lantern,[a,rings[0][k],rings[1][k],b],[(0,1,2,3)],glass)
            beam("Lantern cast iron sash",lantern,a,b,(.052,.054),(.051,.054),iron,n=4)
        oval("Red domed lantern cap",lantern,[(0,HERE,20.88,1.78,1.78),(0,HERE,21.03,1.74,1.74),
             (0,HERE,21.83,1.31,1.31),(0,HERE,22.48,.68,.68),(0,HERE,22.61,.38,.38)],roof,n=16)
        post("Vent cap",lantern,0,HERE,22.62,22.83,.24,iron,8)
        post("Lightning rod",lantern,0,HERE,22.81,23.22,.048,iron,6)
        # Historic winding stair. Solid wedges are independently checkable and touch at each riser.
        turn=pi/10
        count=96
        for i in range(count):
            low=.15+17.57*i/count
            z=.15+17.57*(i+1)/count
            a=-.74+i*turn
            r=1.90-.60*i/(count-1)
            coords=[(.41*cos(a),HERE+.41*sin(a)),(r*cos(a),HERE+r*sin(a)),
                    (r*cos(a+turn),HERE+r*sin(a+turn)),(.41*cos(a+turn),HERE+.41*sin(a+turn))]
            loft("Spiral stone tread",stairs,[[(x,y,h) for x,y in coords] for h in (low,z)],path)
            t=a+turn/2
            steps.append([round(.94*cos(t),6),round(z,6),round(-HERE-.94*sin(t),6)])
            if i%4==0:
                post("Spiral handrail post",stairs,.44*cos(a),HERE+.44*sin(a),z,z+.92,.034,iron,4)
            if i+1<count:
                b=a+turn
                beam("Spiral inner rail",stairs,(.44*cos(a),HERE+.44*sin(a),z+.92),
                     (.44*cos(b),HERE+.44*sin(b),z+.92+17.57/count),(.031,.032),(.031,.032),iron,4)
        post("Stair central support",stairs,0,HERE,.15,17.60,.19,iron,8)
        mark("historic_stair_lower",steps[0][0],-steps[0][2],steps[0][1])
        mark("historic_gallery_access",steps[-1][0],-steps[-1][2],17.72)
        focal=19.88
        era="1892–2007"
    else:
        # Splayed reinforced concrete wings brace the enclosed maintenance stair core.
        def pier(sign):
            outlines=[]
            for z,x,width,d in ((.13,2.29,1.05,5.16),(5.48,2.10,.94,4.85),(14.75,1.93,.76,4.25),
                                (19.58,2.18,.73,3.78),(24.04,3.07,.57,2.90)):
                center=sign*x
                outlines.append([(center-width/2,HERE-d/2,z),(center+width/2,HERE-d/2,z),
                                 (center+width/2,HERE+d/2,z),(center-width/2,HERE+d/2,z)])
            loft("Splayed concrete flanking pier",tower,outlines,walls)
            bearing=sign*2.2
            slab("Reinforced pier footing",tower,bearing-.67,bearing+.67,HERE-2.69,HERE+2.69,.007,.30,path)
            bounds("Fixed concrete pier base",bearing-.66,bearing+.66,HERE-2.60,HERE+2.60,.01,5.5,tower.name)
            beam("Pier inset accent strip",tower,(sign*2.28,HERE-2.595,.25),(sign*1.94,HERE-2.14,14.65),
                 (.23,.064),(.15,.071),roof,4)
        for side in (-1,1):
            pier(side)
        x0,x1=-1.66,1.66
        y0,y1=21.73,27.43
        slab("Modern threshold and lobby floor",tower,x0,x1,y0,y1,.02,.16,path)
        # Keep the door below the narrow glazed spine. Both are true cutouts.
        for ax,bx in ((x0,-.59),(.59,x1)):
            wall("Stair spine front jamb",tower,ax,bx,y0-.14,y0+.14,.16,24.14,siding)
        slab("Service door header",tower,-.59,.59,y0-.14,y0+.14,2.37,3.18,siding)
        slab("Glazed maintenance stair spine",tower,-.59,.59,y0-.086,y0+.034,3.18,24.0,glass)
        for z in (3.12,7.64,12.28,16.85,21.20,24.06):
            slab("Core facade floor divider",tower,-.64,.64,y0-.192,y0-.08,z,z+.13,iron)
        for x in (x0,x1):
            facade(tower,"x",x,y0+.14,y1-.12,[(23.05,26.31,7.91,20.73,"window")],.16,24.14,siding)
        facade(tower,"y",y1,x0,x1,[(-.75,.75,6.41,22.01,"window")],.16,24.14,siding)
        mark("door_main",0,21.48,.16,clear_width_m=1.18)
        slab("Modern tower entrance lamp",tower,-.24,.24,y0-.24,y0-.145,2.54,2.71,glow)
        # Six switchbacks reach the service gallery. Landings alternate at each end.
        per_flight=12
        start=.16
        upper=24.10
        flights=12
        y_near,y_far=22.78,26.39
        for i in range(flights):
            x0,x1=(-1.52,-.15) if i%2==0 else (.15,1.52)
            z0=start+(upper-start)*i/flights
            z1=start+(upper-start)*(i+1)/flights
            a,b=(y_near,y_far) if i%2==0 else (y_far,y_near)
            for t in range(per_flight):
                yy0=a+(b-a)*t/per_flight
                yy1=a+(b-a)*(t+1)/per_flight
                z=z0+(z1-z0)*(t+1)/per_flight
                slab("Modern maintenance tread",stairs,x0,x1,min(yy0,yy1),max(yy0,yy1),z-.173,z,path)
                steps.append([round((x0+x1)/2,6),round(z,6),round(-(yy0+yy1)/2,6)])
            u,v=(b,27.28) if b==y_far else (21.89,b)
            slab("Stair intermediate landing",stairs,-1.57,1.57,u,v,z1-.20,z1,path)
            for x in (x0+.068,x1-.068):
                beam("Modern ramp stair handrail",stairs,(x,a,z0+.98),(x,b,z1+.98),
                     (.036,.037),(.036,.037),iron,n=4)
            if i==0:
                mark("modern_stair_lower",(x0+x1)/2,a,z0)
        mark("modern_gallery_access",0,22.13,upper)
        # Upper platform admits the final stair landing through a real floor opening.
        for a,b,u,v in ((-3.98,-1.57,21.10,27.94),(1.57,3.98,21.10,27.94),
                        (-1.57,1.57,21.10,21.88),(-1.57,.10,22.78,27.94),
                        (.10,1.57,26.41,27.94)):
            slab("Cantilevered upper observation floor",lantern,a,b,u,v,23.85,24.11,walls)
        wrap(lantern,[(.10,22.82),(.10,24.6),(.10,26.41),(1.57,26.41)],24.11,25.13,iron)
        for y in (21.73,27.18):
            slab("Deep flanking bearing beam",lantern,-3.79,3.79,y-.32,y+.32,23.58,23.92,roof)
        wrap(lantern,[(-3.89,21.2),(-1.93,21.2),(0,21.2),(1.93,21.2),(3.89,21.2),(3.89,24.3),
                      (3.89,27.85),(1.93,27.85),(0,27.85),(-1.93,27.85),(-3.89,27.85),(-3.89,24.3),
                      (-3.89,21.2)],24.12,25.20,iron)
        support_y=25.51
        oval("Modern lantern aluminium base",lantern,[(0,support_y,24.11,1.48,1.48),(0,support_y,25.16,1.40,1.40)],roof,n=12)
        lens_ring=[fit_ring(0,support_y,r,z) for r,z in ((1.36,25.15),(1.36,27.34))]
        for i in range(12):
            a=lens_ring[0][i]; b=lens_ring[1][i]; k=(i+1)%12
            mesh("Modern lantern glazing",lantern,[a,lens_ring[0][k],lens_ring[1][k],b],[(0,1,2,3)],glass)
            if i%2==0:
                beam("Modern sash mullion",lantern,a,b,(.043,.045),(.043,.045),roof,4)
        oval("LED Fresnel lens barrel",lantern,[(0,support_y,25.60,.51,.51),(0,support_y,25.93,.69,.69),
              (0,support_y,26.25,.69,.69),(0,support_y,26.42,.47,.47)],glow,n=12)
        oval("Modern hardtop overhang",lantern,[(0,support_y,27.32,1.74,1.74),(0,support_y,27.53,1.62,1.62),
              (0,support_y,27.78,1.08,1.08)],roof,n=12)
        post("Contemporary lightning mast",lantern,0,support_y,27.75,28.73,.048,iron,8)
        for axis in (-1,1):
            beam("VHF aerial",lantern,(0,support_y,28.25),(axis*.68,support_y+.08,28.25),(.024,.025),(.024,.025),iron,4)
        for x in (-2.99,3.0):
            roof_panel("Integrated beacon solar array",lantern,x-.55,x+.55,25.0,26.8,24.29,24.70,.064,glass)
            for y in (25.04,25.86,26.76):
                beam("Solar frame crossbar",lantern,(x-.55,y,24.29),(x+.55,y,24.70),(.027,.024),(.027,.024),iron,4)
        focal=26.1
        era="2008 onward"

    gltf_axis=[0.884,-.043,-.465]
    locator=mark("beacon_beam",0,25.51 if modern else HERE,focal,direction_gltf=gltf_axis)
    locator["rotation_period_s"]=8 if modern else 12
    locator["direction"]=gltf_axis
    locator["active"]=True
    glass.node_tree.nodes.get("Principled BSDF").inputs["Alpha"].default_value=.32 if modern else .40
    glass.surface_render_method="BLENDED"
    # Let scene lighting identify the beacon hardware. The rotating cone is an empty node.
    for parent in (tower,annex,lantern,stairs,lot,rocksite):
        batch(parent)
    bpy.context.view_layer.update()
    all_meshes=[ob for ob in bpy.context.scene.objects if ob.type=="MESH"]
    for ob in all_meshes:
        ob.data.calc_loop_triangles()
    triangles=sum(len(ob.data.loop_triangles) for ob in all_meshes)
    info={"name":"Needle Point Light","state":state,"era":era,"file":f"needle-point-{state}.glb",
          "axes":"glTF metres, Y up and +Z toward town. Both versions share the front path origin at grade.",
          "recommended_waterline_m":-5.8,"height_above_grade_m":28.73 if modern else 23.22,
          "site_footprint_top_m":[30.3,34.4],"focal_height_above_grade_m":focal,
          "triangles":triangles,"materials":sorted({mat.name for ob in all_meshes for mat in ob.data.materials}),
          "swatches":["plank_horizontal"] if modern else ["plank_horizontal","roof_tin_red"],
          "beacon_beam":{"node":"beacon_beam","rotation_period_s":8 if modern else 12,
                         "direction":gltf_axis,"color":"#F6E4BD" if modern else "#F5C276","active":True},
          "anchors":anchors,"stair_tread_samples_gltf_m":steps,"wall_collision_boxes":boxes,
          "collision":"Use the authored tower walls and stair surfaces for walkable access. The lot and its optional headland can be omitted on custom terrain.",
          "optional_nodes":["lot","optional_headland"]}
    (ROOT/f"model-info-{state}.json").write_text(json.dumps(info,indent=2)+"\n")
    export_glb(ROOT/f"needle-point-{state}.glb")
    save_source(ROOT/f"needle-point-{state}.blend")
    print(f"{state}: {triangles} triangles and {len(info['materials'])} materials")
