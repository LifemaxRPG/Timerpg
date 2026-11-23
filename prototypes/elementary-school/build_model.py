"""Generate two school families, each with two states and three distance models."""
import json
from math import cos, pi, sin
from pathlib import Path
from random import Random
import sys

import bpy

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from sculpture_tools import beam, box, group, material, mesh, oval, plaque
from revision_common import batch, emitter, export_glb, save_source

T=.0388
PANE=32*T
REGIONS=json.loads((ROOT/"textures/sign-regions.json").read_text())
STATES={
    "mill-branch-active":("Mill Branch School","1890–1950 active",[-11,11, -5,25],9.30),
    "mill-branch-abandoned":("Mill Branch School","Abandoned, after closing",[-11,11,-5,25],9.30),
    "westmeadow-1955":("Westmeadow Elementary","1955–1989",[-32,53,-23,43],7.13),
    "westmeadow-1990":("Westmeadow Elementary","1990 onward",[-32,53,-23,43],7.13),
}


def slab(parent,label,bounds,mat):
    a,b,c,d,e,f=bounds
    return box(label,parent,((a+b)/2,(c+d)/2,(e+f)/2),(b-a,d-c,f-e),mat)


def gable(parent,label,x0,x1,y0,y1,eave,ridge,mat):
    mid=(x0+x1)/2
    verts=[(x,y,z) for y in (y0,y1) for x,z in ((x0,eave),(x1,eave),(mid,ridge))]
    faces=[(0,1,2),(5,4,3),(3,4,1,0),(1,4,5,2),(3,0,2,5)]
    return mesh(label,parent,verts,faces,mat)


def roof_cover(parent,x0,x1,y0,y1,eave,ridge,mat):
    middle=(x0+x1)/2
    for (a,z),(b,w) in (((x0,eave),(middle,ridge)),((middle,ridge),(x1,eave))):
        top=[(a,y0,z),(b,y0,w),(b,y1,w),(a,y1,z)]
        mesh("Capped pitched roof",parent,top+[(x,y,z-.25) for x,y,z in top],
             [(0,1,2,3),(7,6,5,4),(0,4,5,1),(1,5,6,2),(2,6,7,3),(3,7,4,0)],mat)


def build(state,lod):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    near,far=lod=="near",lod=="far"
    rural=state.startswith("mill")
    closed=state.endswith("abandoned")
    modern=state.endswith("1990")
    root=group(STATES[state][0])
    shell,inside,lot,addition,collision_parent=[group(n,root) for n in
        ("building_shell","fixed_interior","lot","portables","collision")]
    wood=material("wall_clapboard","faded" if closed else "clapboard")
    brick=material("wall_brick","brick")
    floor=material("floor_school","wood" if rural else "tile")
    slate=material("trim_and_slate","metal")
    ground=material("ground_drive","gravel" if rural else "asphalt")
    turf=material("ground_turf","grass-neglected" if closed else "grass")
    roof=material("roof_finish","roof_slate" if rural else "roof" if modern else "roof-tar")
    light=material("detail_light_trim","chalk")
    oxide=material("detail_painted_timber","terracotta")
    # The modern campus trades its clapboard material for the cutout wire fence.
    fence_mat=None
    if modern:
        wood=light
        fence_mat=material("detail_chain_link","chain-link")
        nodes=fence_mat.node_tree.nodes
        image=next(n for n in nodes if n.type=="TEX_IMAGE")
        fence_mat.node_tree.links.new(image.outputs["Alpha"],nodes["Principled BSDF"].inputs["Alpha"])
    sign_mat=material("sign_paint","signs")
    glass=material("glass_school","glass-dark" if closed else "glass")
    emitter(glass,"pane-mask-dark" if closed else "pane-mask",.25)
    hidden=bpy.data.materials.new("collision_invisible")
    hidden.use_nodes=True
    hidden.surface_render_method="BLENDED"
    hidden.node_tree.nodes.get("Principled BSDF").inputs["Alpha"].default_value=0
    boxes,doors,signs=[],{},{}

    def collide(label,bounds,attachment=""):
        a,b,c,d,e,f=bounds
        obj=slab(collision_parent,f"collider_{len(boxes):02d}",bounds,light)
        obj.data.materials.clear()
        obj.data.materials.append(hidden)
        obj["collision_only"]=True
        boxes.append({"name":label,"center_gltf_m":[(a+b)/2,(e+f)/2,-(c+d)/2],
                      "size_gltf_m":[b-a,f-e,d-c],"group":"lot" if attachment=="lot" else "building",
                      "attachment":attachment})

    def box_at(parent,label,bounds,mat,blocks=False):
        slab(parent,label,bounds,mat)
        if blocks:
            collide(label,bounds,parent.name if parent in (lot,addition) else "")

    def wall_bounds(u0,u1,v0,v1,z0,z1,axis):
        return (u0,u1,v0,v1,z0,z1) if axis=="x" else (v0,v1,u0,u1,z0,z1)

    def marker(name,x,y,z,yaw=0):
        ob=group(name,root)
        ob.location=(x,y,z)
        ob.rotation_euler.z=yaw
        doors[name]=[x,z,-y]

    def face(u,v,z,start,end,axis,reverse):
        coords=[(u+start,v,z),(u+end,v,z)]
        if axis=="y":
            coords=[(y,x,w) for x,y,w in coords]
        return coords[::-1] if reverse else coords

    def panes(parent,label,u,v,z,count,axis="x",reverse=False):
        if near and rural:
            for i in range(count):
                a,b=face(u,v,z,i*PANE,(i+1)*PANE,axis,reverse)
                plaque(label,parent,[a,b,(b[0],b[1],z+PANE),(a[0],a[1],z+PANE)],glass,(0,0,32,32),(32,32))
        else:
            a,b=face(u,v,z,0,count*PANE,axis,reverse)
            plaque(label,parent,[a,b,(b[0],b[1],z+PANE),(a[0],a[1],z+PANE)],glass,(0,0,32*count,32),(32,32))

    def lettering(parent,label,key,x,y,z,reverse=False):
        rect=REGIONS[key]
        w,h=rect[2]*T,rect[3]*T
        a,b=face(x,y,z,-w/2,w/2,"x",reverse)
        node=plaque(label,parent,[a,b,(b[0],b[1],z+h),(a[0],a[1],z+h)],sign_mat,rect,(512,512))
        node["keep"]=True
        signs[label]={"texture":"signs.png","atlas_size_texels":[512,512],"rect_texels":rect,
                      "bottom_left_gltf_m":[a[0],z,-y],"width_m":w,"height_m":h}

    def facade(parent,label,edge,plane,height,grade,mat,windows=(),doorways=(),axis="x",reverse=False,thick=.24):
        openings=[(s,s+n*PANE,z,z+PANE,n) for s,n,z in windows]
        openings.extend((a,b,grade,h,0) for a,b,h in doorways)
        # Full wall collision between the door openings, including the fixed sash bays.
        spans=[(a,b) for a,b,_ in sorted(doorways)]+[(edge[1],edge[1])]
        prev=edge[0]
        for a,b in spans:
            if a>prev+.001:
                collide(label,wall_bounds(prev,a,plane,plane+thick,grade,height,axis),
                        parent.name if parent in (lot,addition) else "")
            prev=b
        if not rural:
            # Skin the facade with its actual aperture returns. Adjacent masonry
            # rectangles share seams without exporting box faces inside the wall.
            vertices,polygons=[],[]
            def quad(points):
                offset=len(vertices)
                vertices.extend((u,v,z) if axis=="x" else (v,u,z) for u,v,z in points)
                indices=tuple(range(offset,offset+4))
                polygons.append(indices if axis=="x" else indices[::-1])
            def skin(a,b,z0,z1):
                if b-a>.001 and z1-z0>.001:
                    quad(((a,plane,z0),(b,plane,z0),(b,plane,z1),(a,plane,z1)))
                    q=plane+thick
                    quad(((b,q,z0),(a,q,z0),(a,q,z1),(b,q,z1)))
            def across(a,b,z,up):
                q=plane+thick
                points=((a,plane,z),(b,plane,z),(b,q,z),(a,q,z))
                quad(points if up else points[::-1])
            def reveal(u,low,high,left):
                q=plane+thick
                points=((u,plane,low),(u,q,low),(u,q,high),(u,plane,high))
                quad(points if left else points[::-1])
            prev=edge[0]
            for a,b,z0,z1,n in sorted(openings):
                skin(prev,a,grade,height)
                skin(a,b,grade,z0)
                skin(a,b,z1,height)
                reveal(a,z0,z1,True)
                reveal(b,z0,z1,False)
                if n:
                    across(a,b,z0,True)
                across(a,b,z1,False)
                prev=b
            skin(prev,edge[1],grade,height)
            across(*edge,height,True)
            reveal(edge[0],grade,height,False)
            reveal(edge[1],grade,height,True)
            mesh(label+" continuous wall",parent,vertices,polygons,mat)
        prev=edge[0]
        for a,b,z0,z1,n in sorted(openings):
            if rural and a>prev+.001:
                box_at(parent,label+" pier",wall_bounds(prev,a,plane,plane+thick,grade,height,axis),mat)
            if rural and z0>grade+.001:
                box_at(parent,label+" plinth",wall_bounds(a,b,plane,plane+thick,grade,z0,axis),mat)
            if rural:
                box_at(parent,label+" lintel",wall_bounds(a,b,plane,plane+thick,z1,height,axis),mat)
            if n:
                panes(parent,label+" sash",a,plane+thick*.5,z0,n,axis,reverse)
                if not far and (rural or label.startswith("Classroom ribbon")):
                    box_at(parent,label+" sill",wall_bounds(a-.045,b+.045,plane-.055,plane+thick+.055,z0-.096,z0-.013,axis),light)
                if closed and not far and (label=="Gabled front" and a<0 or label=="East classroom wall" and a>5):
                    exterior=plane+thick+.026 if reverse==(axis=="x") else plane-.026
                    def pos(u,z):
                        return (u,exterior,z) if axis=="x" else (exterior,u,z)
                    beam("Weathered sash board",parent,pos(a-.06,z0+.25),pos(b+.06,z1-.22),(.085,.12),(.085,.12),oxide,4)
                    if near:
                        beam("Weathered sash board",parent,pos(a-.04,z1-.19),pos(b+.04,z0+.25),(.085,.09),(.085,.09),oxide,4)
            else:
                for u in (a,b) if not far else ():
                    box_at(parent,label+" door casing",wall_bounds(u-.041,u+.041,plane-.023,plane+thick+.023,grade,z1,axis),slate)
            prev=b
        if rural and prev<edge[1]-.001:
            box_at(parent,label+" pier",wall_bounds(prev,edge[1],plane,plane+thick,grade,height,axis),mat)

    def divider(label,bounds,mat=wood):
        if not far:
            box_at(inside,label,bounds,mat)
        collide(label,bounds)

    if rural:
        grade=.17
        slab(lot,"Schoolyard",(-11,11,-5,25,0,.024),turf)
        slab(lot,"Gravel lane",(-2.0,2.0,-5,1.0,.024,.064),ground)
        for depth,z in ((1.17,.061),(.76,.118),(.43,.17)):
            if closed and depth>.5:
                verts=[(-1.66,-depth,.024),(1.66,-depth,.024),(1.66,.012,.024),(-1.66,.012,.024),
                       (-1.66,-depth,z+.059),(1.66,-depth,max(.031,z-.047)),(1.66,.012,max(.031,z-.047)),(-1.66,.012,z+.059)]
                mesh("Sunk porch riser",shell,verts,[(0,3,2,1),(4,5,6,7),(0,1,5,4),(1,2,6,5),(3,7,6,2),(0,4,7,3)],wood)
            else:
                slab(shell,"Entry stair",(-1.66,1.66,-depth,.04,.024,z),light)
        slab(shell,"Stone plinth and floor",(-5,5,0,14,.03,grade),floor)
        facade(shell,"Gabled front",(-5,5),0,4.24,grade,wood,
               [(-3.70,1,1.42),(2.46,1,1.42)],[(-.84,.84,2.61)])
        facade(shell,"Rear clapboard",(-5,5),13.76,4.24,grade,wood,
               [(2.46,1,1.50)],[(-.74,.74,2.56)],reverse=True)
        facade(shell,"West classroom wall",(0,14),-5,4.24,grade,wood,
               [(2.03,1,1.46),(5.8,1,1.46),(9.54,1,1.46)],axis="y",reverse=True)
        facade(shell,"East classroom wall",(0,14),4.76,4.24,grade,wood,
               [(2.03,1,1.46),(5.8,1,1.46),(9.54,1,1.46)],axis="y")
        slab(shell,"Back landing",(-1.05,1.05,13.97,14.58,.024,grade),floor)
        marker("door_main",0,-.34,grade)
        marker("door_back",0,14.68,grade,pi)
        if closed:
            # Keep the end gables; use strips either side of real holes in the roof plane.
            verts=[(x,y,z) for y in (0,14) for x,z in ((-5,4.24),(5,4.24),(0,6.92))]
            mesh("Weathered end gables",shell,verts,[(0,1,2),(5,4,3)],wood)
            pitch=lambda x: 7.12-abs(x)*2.84/5.38
            holes=[(1.72,3.54,3.55,5.12),(2.62,4.12,7.61,9.74),(-3.6,-1.51,10.1,12.23)]
            for a,b in ((-5.38,0),(0,5.38)):
                damage=[h for h in holes if a<=h[0] and h[1]<=b]
                cuts=[]
                for lo,hi,c,d in damage:
                    cuts.extend(((lo+.31,hi-.20,c,c+.31),(lo,hi,d-.37,d-.15),
                                 (lo+.12,hi-.45,d-.15,d),(lo,hi,c+.31,d-.37)))
                ys=sorted({-.36,14.37,*(edge for h in cuts for edge in h[2:])})
                for c,d in zip(ys,ys[1:]):
                    bad=next((h for h in cuts if h[2]<=c and d<=h[3]),None)
                    spans=((a,b),) if bad is None else ((a,bad[0]),(bad[1],b))
                    for lo,hi in spans:
                        mesh("Remaining worn slate",shell,[(lo,c,pitch(lo)),(hi,c,pitch(hi)),
                             (hi,d,pitch(hi)),(lo,d,pitch(lo))],[(0,1,2,3)],roof)
                if a:
                    slab(shell,"Broken roof eave trim",(a-.06,a+.06,-.39,14.37,4.18,4.30),wood)
            slab(shell,"East weathered fascia",(5.30,5.40,-.39,14.37,4.18,4.31),wood)
            for a,b,c,d in holes:
                below=min(pitch(a),pitch(b))-.41
                slab(shell,"Dark recess under missing slate",(a-.03,b+.03,c-.03,d+.03,below-.1,below),slate)
                for yy in (c+.32,d-.34):
                    beam("Exposed rafter",shell,(a-.13,yy,pitch(a)-.135),(b+.13,yy,pitch(b)-.135),
                         (.065,.073),(.065,.073),wood,4)
                if not far:
                    mesh("Slipped shingle cluster",shell,
                         [(a-.19,c-.21,pitch(a-.19)+.098),(a+.53,c-.27,pitch(a+.53)+.065),
                          (a+.78,c+.14,pitch(a+.78)+.085),(a-.05,c+.26,pitch(a-.05)+.10)],
                         [(0,1,2),(0,2,3)],slate)
        else:
            gable(shell,"Closed clapboard gables",-5,5,0,14,4.24,6.92,wood)
            roof_cover(shell,-5.38,5.38,-.36,14.37,4.28,7.12,roof)
        slab(inside,"Boarded ceiling",(-4.74,4.74,.26,13.74,4.08,4.23),wood)
        for x in (-4.8,4.8):
            slab(shell,"Front corner casing",(x-.11,x+.11,-.055,.07,grade,4.30),slate)
        lettering(shell,"sign_name","rural_faded" if closed else "rural",0,-.017,2.97)
        # Open belfry, round low-poly bell, and mechanically attached roof.
        slab(shell,"Bell cupola base",(-1.18,1.18,1.13,3.49,6.37,7.41),wood)
        for x in (-.88,.88):
            for y in (1.42,3.18):
                slab(shell,"Belfry post",(x-.115,x+.115,y-.115,y+.115,7.39,8.54),wood)
        if not closed:
            bell=oval("belfry_bell",shell,[(0,2.3,z,r,r) for z,r in
                      ((7.63,.42),(7.77,.38),(8.16,.23),(8.23,.15))],oxide,12 if near else 8 if not far else 6)
            bell["keep"]=True
        else:
            beam("Empty bell hanger",shell,(0,2.3,8.09),(0,2.3,8.23),(.064,.064),(.064,.064),slate,4)
        slab(shell,"Belfry cross timber",(-1.03,1.03,2.20,2.40,8.21,8.39),slate)
        roof_cover(shell,-1.24,1.24,1.03,3.57,8.48,9.30,roof)
        slab(shell,"Brick flue",(3.12,3.86,10.80,11.57,5.10,7.47),brick)
        slab(shell,"Flue cap",(3.04,3.94,10.71,11.64,7.38,7.51),light)
        slab(inside,"Slate chalkboard",(-4.50,-1.08,13.59,13.76,1.25,2.83),slate)
        slab(inside,"Fixed chalk tray",(-4.59,-1.00,13.52,13.76,1.19,1.29),oxide)
        x,y=3.47,11.18
        slab(inside,"Stove hearth",(x-.58,x+.58,y-.60,y+.60,grade,.28),brick)
        oval("Cast iron woodstove",inside,[(x,y,z,r,r) for z,r in ((.28,.33),(.44,.42),(1.08,.42),(1.18,.32))],slate,12 if near else 8 if not far else 6)
        beam("Stove pipe",inside,(x,y,1.15),(x,y,4.13),(.073,.073),(.073,.073),slate,8)
        collide("Woodstove",(x-.58,x+.58,y-.60,y+.60,grade,1.18))
        for i,xx in enumerate((-6.8,-8.88)):
            if closed and i==1:
                shed=group("Leaning outhouse",lot)
                shed.location=(xx,17.60,.13)
                shed.rotation_euler.y=.145
                for left,right in ((-.70,-.53),(.53,.70)):
                    slab(shed,"Racked side wall",(left,right,0,1.74,0,2.55),wood)
                slab(shed,"Racked rear wall",(-.53,.53,1.56,1.74,0,2.55),wood)
                slab(shed,"Racked front lintel",(-.53,.53,0,.17,2.20,2.55),wood)
                gable(shed,"Racked gable",-.70,.70,0,1.74,2.55,2.93,wood)
                roof_cover(shed,-.8,.8,-.14,1.93,2.58,2.98,roof)
                hinge=group("Hanging door hinge",shed)
                hinge.location=(.49,-.064,.055)
                hinge.rotation_euler.z=.63
                hinge.rotation_euler.y=-.04
                slab(hinge,"Hanging door leaf",(-.81,.02,-.058,.019,0,2.19),oxide)
                slab(lot,"Outhouse settled foundation",(xx-.75,xx+.75,17.50,19.28,.024,.17),light)
                collide("Leaning outhouse and overhanging door",(xx-.76,xx+1.11,16.96,19.47,.024,3.0),"lot")
                batch(shed)
            else:
                slab(lot,"Outhouse wood shell",(xx-.70,xx+.70,17.6,19.34,.024,2.55),wood)
                roof_cover(lot,xx-.82,xx+.82,17.44,19.53,2.55,2.94,roof)
                slab(lot,"Outhouse door",(xx-.44,xx+.44,17.55,17.625,.065,2.25),oxide)
                slab(lot,"Outhouse vent",(xx-.21,xx+.21,17.525,17.56,2.17,2.36),slate)
                collide("Outhouse",(xx-.70,xx+.70,17.6,19.34,.024,2.55),"lot")
        slab(lot,"Hand pump apron",(6.45,7.9,1.98,3.42,.024,.13),light)
        beam("Hand pump body",lot,(7.17,2.7,.13),(7.17,2.7,1.23),(.11,.11),(.09,.09),oxide if closed else slate,8)
        beam("Hand pump spout",lot,(7.17,2.7,.92),(7.57,2.7,.86),(.06,.06),(.05,.05),oxide if closed else slate,8)
        beam("Hand pump lever",lot,(7.17,2.71,1.20),(6.59,2.71,1.09),(.043,.047),(.043,.047),oxide,4)
        collide("Hand pump",(6.56,7.61,2.30,3.02,.024,1.24),"lot")
        if closed:
            slab(shell,"Boarded door transom",(-.78,.78,-.05,.06,2.55,2.73),oxide)
            weed_points=((-4.9,-.6),(-4.3,-.54),(-3.0,-.34),(-2.14,-.32),(2.66,-.36),
                         (4.14,-.42),(5.18,1.7),(5.31,2.4),(5.3,3.2),(5.25,6.1),
                         (5.2,8.87),(5.22,12.5),(4.72,14.44),(-4.6,14.49),(-5.16,11.1),
                         (-5.22,8.8),(-6.21,17.45),(-7.8,17.38),(-9.49,16.88),(6.8,2.09))
            rng=Random(1936)
            for x,y in weed_points[::1 if near else 2 if not far else 4]:
                verts=[]
                for leaf in range(8):
                    bend=rng.uniform(.20,.49)
                    ang=leaf*pi/4
                    verts.extend(((x-.038*sin(ang),y+.038*cos(ang),.024),
                                  (x+.038*sin(ang),y-.038*cos(ang),.024),
                                  (x+bend*cos(ang),y+bend*sin(ang),rng.uniform(.36,.91))))
                mesh("Tall grass card cluster",lot,verts,[tuple(range(j,j+3)) for j in range(0,24,3)],turf)
    else:
        grade=.13
        slab(lot,"Campus turf lot",(-32,53,-23,43,0,.027),turf)
        # A two-lane elliptical driveway. Rim and island use physical tile scale.
        n=36 if near else 24 if not far else 16
        rings=[[(rx*cos(i*2*pi/n),-11.8+ry*sin(i*2*pi/n),.039) for i in range(n)]
               for rx,ry in ((24.5,8.6),(16.8,3.9))]
        mesh("Bus loop pavement",lot,rings[0]+rings[1],
             [(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)],ground)
        slab(lot,"Bus loop street opening",(-5.1,5.1,-23,-19.0,.027,.035),ground)
        slab(lot,"Main walk",(-3.1,3.1,-7.7,.04,.038,grade),light)
        for x in (-14,-8,8,14):
            slab(lot,"Bus stop curb stripe",(x-1.95,x+1.95,-4.42,-4.25,.043,.048),light)
        slab(shell,"Main wing floor",(-27,27,6.0,24.0,.029,grade),floor)
        slab(shell,"Gym floor",(12,30,23.97,40.1,.029,grade),floor)
        slab(shell,"Entrance floor",(-8.93,8.93,0,6.01,.029,grade),floor)
        facade(shell,"Classroom ribbon west",(-27,-8.93),6.0,4.26,grade,brick,
               [(-25.6,5,1.55),(-18.37,6,1.55)])
        facade(shell,"Classroom ribbon east",(8.93,27),6.0,4.26,grade,brick,
               [(10.31,6,1.55),(18.96,5,1.55)])
        facade(shell,"Back classroom ribbon",(-27,12),23.76,4.26,grade,brick,
               [(-25,6,1.53),(-15.8,7,1.53),(-5.45,5,1.53),(2.24,5,1.53)],reverse=True)
        facade(shell,"West end wall",(6.0,24.0),-27,4.26,grade,brick,
               [(8.2,4,1.52),(17.1,4,1.52)],axis="y",reverse=True)
        facade(shell,"East end wall",(6.0,24.0),26.76,4.26,grade,brick,
               [(8.2,4,1.52),(18.2,3,1.52)],axis="y")
        facade(shell,"Canopied main entry",(-8.93,8.93),0,4.32,grade,slate if modern else brick,
               [(-7.45,5,1.15),(1.25,5,1.15)] if modern else [(-6.8,3,1.44),(3.1,3,1.44)],
               [(-1.06,1.06,2.63)])
        for xx,rev in ((-8.93,True),(8.69,False)):
            facade(shell,"Vestibule return",(0,6.0),xx,4.28,grade,slate if modern else brick,
                   [(1.0,3,1.18)] if modern else [(2.1,2,1.44)],axis="y",reverse=rev)
        if modern:
            slab(shell,"Projecting metal entrance canopy",(-8.74,8.74,-3.35,.12,3.29,3.56),roof)
            slab(shell,"Modern green canopy edge",(-8.78,8.78,-3.4,-3.19,3.22,3.68),slate)
            for x in (-7.77,7.77):
                box_at(shell,"Canopy support column",(x-.12,x+.12,-2.99,-2.73,.027,3.31),slate,True)
            slab(shell,"Modern sign panel backing",(-8.99,8.99,-.26,.26,3.68,6.29),slate)
            lettering(shell,"sign_name","campus_modern",0,-.276,4.33)
            for x in (-10.52,-7.46):
                box_at(lot,"Marquee support post",(x-.07,x+.07,-5.3,-5.14,.027,2.25),slate,True)
            slab(lot,"Enclosed school marquee",(-12.43,-5.56,-5.34,-5.08,1.53,2.71),slate)
            lettering(lot,"sign_marquee","marquee",-9,-5.358,1.57)
        else:
            slab(shell,"Entry canopy slab",(-9.39,9.39,-1.53,.41,3.09,3.38),slate)
            slab(shell,"Green entry fascia",(-8.98,8.98,-.06,.22,3.34,5.20),slate)
            lettering(shell,"sign_name","campus",0,-.074,3.34)
        marker("door_main",0,-.36,grade)
        if modern:
            facade(inside,"Secure inner control line",(-8.69,8.69),3.38,3.65,grade,slate,
                   [(-6.22,3,1.10),(2.10,3,1.10)],[(-1.06,1.06,2.60)])
            slab(inside,"Fixed reception desk",(-6.55,-3.55,4.29,5.12,grade,1.08),floor)
            collide("Reception desk",(-6.55,-3.55,4.29,5.12,grade,1.08))
        # Classrooms open off the central corridor. The gym connects at the end.
        for a,b in ((-26.76,-23.2),(-21.55,-13.1),(-11.45,-2.58),(2.58,10.6),(12.28,20.5),(22.1,26.76)):
            divider("Classroom corridor wall",(a,b,15.18,15.37,grade,3.27))
        for a,b in ((-18.30,-18.12),(-8.30,-8.12),(8.08,8.26),(18.15,18.33)):
            divider("Classroom party wall",(a,b,6.24,15.18,grade,3.27))
        for x in (-22.4,-13.3,13.5,22.4):
            slab(inside,"Classroom slate",(x-1.60,x+1.60,6.28,6.37,1.12,2.30),slate)
        if modern:
            # A lit circulation spine. Leave a full corridor route and a wide opening at every door.
            for a,b in ((-22.6,-10.5),(-7.9,8.8),(11.0,17.4)):
                divider("Hall rear wall",(a,b,21.15,21.32,grade,3.27),wood)
            for a,b,y,reverse in ((-21.09,-16.13,15.56,True),(-10.83,-5.87,15.56,True),
                                  (-18,-13.04,20.93,False),(-6.9,-1.94,20.93,False)):
                depth=(y-.20,y) if reverse else (y,y+.20)
                collide("Hall built-in locker bank",(a,b,*depth,grade,2.19))
                if not far:
                    slab(inside,"Locker casing",(a,b,*depth,grade,2.19),slate)
                    plane=y+(.006 if reverse else -.006)
                    span=32*T
                    for tile in range(4):
                        x0=a+tile*span
                        x1=x0+span
                        coords=[(x0,plane,.15),(x1,plane,.15),(x1,plane,.15+52*T),(x0,plane,.15+52*T)]
                        plaque("Painted locker doors",inside,[coords[1],coords[0],coords[3],coords[2]] if reverse else coords,sign_mat,REGIONS["lockers"],(512,512))
            for x in (-15.5,-7.5,1.8,11.5):
                slab(inside,"Hall ceiling light cover",(x-.52,x+.52,17.75,18.75,3.17,3.26),light)
            lettering(inside,"sign_bulletin","bulletin",.65,21.137,1.21)
            for x in (-23.12,-13.0,10.69,20.59):
                # Fixed open poses leave at least 0.9 m of aisle beside each leaf.
                slab(inside,"Classroom half-open door bottom",(x,x+.62,15.11,15.18,grade,1.47),slate)
                slab(inside,"Classroom door top",(x,x+.62,15.11,15.18,2.26,2.52),slate)
                for u,v in ((x,x+.21),(x+.43,x+.62)):
                    slab(inside,"Classroom door stile",(u,v,15.11,15.18,1.47,2.26),slate)
                mesh("Classroom narrow vision pane",inside,
                     [(x+.21,15.145,1.47),(x+.43,15.145,1.47),(x+.43,15.145,2.26),(x+.21,15.145,2.26)],
                     [(0,1,2,3)],glass)
                collide("Fixed classroom door leaf",(x,x+.62,15.11,15.18,grade,2.52))
        # Gym is a tall separate volume with clerestories and a real east service exit.
        facade(shell,"Gym west side",(24,40.1),12.0,6.63,grade,brick,
               [(27,6,4.25)],axis="y",reverse=True)
        facade(shell,"Gym east side",(23.98,40.1),29.76,6.63,grade,brick,
               [(28.5,5,4.25)],[(36.72,38.72,2.63)],axis="y")
        facade(shell,"Gym south shoulder",(27,30),23.76,6.63,grade,brick)
        facade(shell,"Gym back elevation",(12,30),39.86,6.63,grade,brick,
               [(13.65,4,4.30),(21.1,6,4.30)],reverse=True)
        slab(shell,"Gym upper wall above connector",(12,27,23.92,24.15,4.24,6.63),brick)
        lettering(shell,"sign_gym","gym",21.0,40.117,3.00,True)
        marker("door_service",30.36,37.72,grade,pi/2)
        slab(shell,"Gym service landing",(29.99,31.45,36.3,39.0,.027,grade),light)
        slab(inside,"Fixed lunch service counter",(13.11,20.55,38.41,39.04,grade,1.04),floor)
        collide("Lunch counter",(13.11,20.55,38.41,39.04,grade,1.04))
        # Fixed recreation equipment and half-court paint in the multipurpose hall.
        for x in (15.1,27.0):
            slab(inside,"Gym sideline",(x,x+.0776,25.9,38.55,grade+.002,grade+.011),oxide)
        for y in (25.9,38.48):
            slab(inside,"Gym baseline",(15.1,27.078,y,y+.0776,grade+.002,grade+.011),oxide)
        for x in (21.7,26.3):
            slab(inside,"Gym lane stripe",(x,x+.0776,34.5,38.53,grade+.002,grade+.011),oxide)
        slab(inside,"Gym free-throw stripe",(21.7,26.378,34.5,34.578,grade+.002,grade+.011),oxide)
        slab(inside,"Gym hoop support",(23.88,24.10,39.25,39.88,3.43,3.64),slate)
        slab(inside,"Wall-mounted gym backboard",(23.11,24.87,39.23,39.32,3.10,4.13),light)
        hoop=[(24+r*cos(i*pi/6),38.98+r*sin(i*pi/6),3.05) for r in (.248,.205) for i in range(12)]
        mesh("School basket rim",inside,hoop,[(i,(i+1)%12,(i+1)%12+12,i+12) for i in range(12)],oxide)
        slab(inside,"Gym rim bracket",(23.95,24.05,39.12,39.24,3.02,3.10),oxide)
        for a,b,c,d,z in ((-27.18,27.17,5.88,24.14,4.26),(-9.05,9.05,-.10,6.08,5.20),
                           (11.81,30.17,23.85,40.26,6.70)):
            slab(shell,"Flat roof membrane",(a,b,c,d,z-.06,z+.12),roof)
            for x0,x1,y0,y1 in ((a,a+.18,c,d),(b-.18,b,c,d),(a,b,c,c+.16),(a,b,d-.16,d)):
                slab(shell,"Painted roof coping",(x0,x1,y0,y1,z+.08,z+.33),slate)
        slab(inside,"Classroom ceiling",(-26.76,26.76,6.23,23.83,3.25,3.40),wood)
        slab(inside,"Vestibule ceiling",(-8.69,8.69,.22,6.23,3.65,3.78),wood)
        # Four mechanically distinct units and two flat drains around the low coping.
        for index,(x,y,z) in enumerate(((-19.3,16.4,4.38),(11.6,13.2,4.38),(-5.7,20.8,4.38),(25,32.5,6.82))):
            a,b=(.82,.58) if modern else (.54,.41)
            h=(.43 if z<6 else .28) if modern else (.31 if z<6 else .29)
            slab(shell,"Roof HVAC casing",(x-a,x+a,y-b,y+b,z,z+h),slate)
            if not far:
                slab(shell,"HVAC top grille",(x-a+.14,x+a-.14,y-b+.13,y+b-.13,z+h,z+h+.045),light)
            if near and index%2==0:
                slab(shell,"HVAC supply curb",(x+a-.07,x+a+.43,y-.24,y+.24,z,z+.16),slate)
        for x,y,z in ((-23.43,22.63,4.382),(26.41,38.32,6.822)):
            mesh("Roof drain grate",shell,[(x-.29,y-.30,z),(x+.29,y-.30,z),(x+.29,y+.30,z),
                 (x-.29,y+.30,z)],[(0,1,2,3)],slate)
        if modern:
            for a,b,c,d in ((-22.97,-14.43,13.61,20.3),(-2.6,8.91,8.92,14.9)):
                mesh("Patched roof membrane",shell,[(a,c,4.386),(b,c,4.386),(b,d,4.386),(a,d,4.386)],[(0,1,2,3)],slate)
        # Flagpole, side bicycle loop rack, court and three permanently installed play structures.
        slab(lot,"Flagpole base",(10.42,11.34,-2.45,-1.54,.027,.17),light)
        beam("Front flagpole",lot,(10.88,-1.99,.17),(10.88,-1.99,7.05),(.075,.075),(.043,.043),slate,8)
        pennant=[(10.90,-1.99,6.90),(11.86,-1.91,6.79),(12.35,-1.72,6.42),(10.90,-1.99,6.33)]
        mesh("School pennant",lot,pennant,[(0,1,3),(1,2,3)],oxide)
        collide("Flagpole",(10.42,11.34,-2.45,-1.54,.027,7.05),"lot")
        for x in ((-13.8,-13,-12.2,-11.4,-10.6) if near else (-13.8,-12.2,-10.6)):
            for y in (2.8,3.3):
                slab(lot,"Bike rack upright",(x-.035,x+.035,y-.04,y+.04,.027,.70),slate)
            slab(lot,"Bike rack top",(x-.035,x+.035,2.77,3.34,.68,.75),slate)
        collide("Bicycle rack",(-13.9,-10.50,2.73,3.38,.027,.75),"lot")
        sand=light
        slab(lot,"Playground landing bed",(31.4,51.3,11.86,34.8,.028,.062),sand)
        ox,oy=41.3,18.52
        for xx in (ox-4.20,ox+4.20):
            for dy in (-1,1):
                beam("Swing A-frame",lot,(xx,oy+dy*1.42,.062),(xx,oy,2.91),(.085,.085),(.085,.085),oxide,4)
        beam("Swing top beam",lot,(ox-4.36,oy,2.91),(ox+4.36,oy,2.91),(.093,.093),(.093,.093),oxide,8)
        for xx in (ox-2.63,ox,ox+2.63):
            for dx in (-.25,.25):
                beam("Suspended swing chain",lot,(xx+dx,oy,.69),(xx+dx,oy,2.88),(.023,.023),(.023,.023),slate,4)
            slab(lot,"Swing seat",(xx-.31,xx+.31,oy-.16,oy+.16,.64,.70),oxide)
        collide("Swing frame", (ox-4.4,ox+4.4,oy-1.48,oy+1.48,.062,3.0),"lot")
        for xx in (34.2,36.38):
            for yy in (26.3,29.8):
                slab(lot,"Monkey bar upright",(xx-.068,xx+.068,yy-.068,yy+.068,.062,2.55),oxide)
            slab(lot,"Monkey bar side rail",(xx-.061,xx+.061,26.26,29.88,2.48,2.59),oxide)
        for i in range(9 if near else 5):
            yy=26.33+i*3.4/(8 if near else 4)
            slab(lot,"Overhead rung",(34.2,36.38,yy-.039,yy+.039,2.49,2.56),slate)
        collide("Monkey bars",(34.11,36.49,26.17,29.91,.062,2.60),"lot")
        for xx in (44.1,45.19):
            beam("Slide rail",lot,(xx,27.08,2.66),(xx,31.7,.22),(.063,.061),(.063,.061),oxide,4)
            slab(lot,"Slide ladder upright",(xx-.062,xx+.062,26.35,26.51,.062,2.74),oxide)
            beam("Slide ladder support",lot,(xx,28.85,.07),(xx,27.09,2.63),(.061,.061),(.061,.061),oxide,4)
        mesh("Slide chute",lot,[(44.1,27.06,2.62),(45.19,27.06,2.62),(45.19,31.7,.21),(44.1,31.7,.21)],[(0,1,2,3)],light)
        for r in range(7):
            z=.38+r*.33
            slab(lot,"Slide ladder rung",(44.09,45.20,26.38,26.47,z,z+.072),slate)
        collide("Slide",(44.0,45.28,26.29,31.78,.062,2.78),"lot")
        slab(lot,"Four-square paving",(33.4,45.3,35.62,42.08,.027,.059),ground)
        for x in (36.01,39.31,42.61):
            slab(lot,"Four-square line",(x,x+.0776,35.94,41.74,.059,.065),light)
        for y in (35.94,38.84,41.74):
            slab(lot,"Four-square line",(36.01,42.69,y,y+.0776,.059,.065),light)
        if not modern:
            diamond=[(-24,27.3,.031),(-11,27.3,.031),(-6.5,34.4,.031),(-11.3,40.7,.031),
                     (-24.3,40.7,.031),(-28.3,34.4,.031)]
            mesh("Bare dirt play field",lot,diamond,[(0,1,2,3,4,5)],oxide)
            for x,y in ((-17.2,28.7),(-11.95,34.1),(-17.2,39.3),(-22.45,34.1)):
                slab(lot,"Sandlot base marker",(x-.20,x+.20,y-.20,y+.20,.031,.051),light)
        else:
            # Low-profile molded play tower. The original steel swings stay nearby.
            platform=(45.8,49.1,22.6,25.7)
            slab(lot,"Molded play deck",(*platform,1.34,1.53),oxide)
            for x in (45.92,48.97):
                for y in (22.72,25.57):
                    slab(lot,"Play tower post",(x-.09,x+.09,y-.09,y+.09,.062,3.6),slate)
            gable(lot,"Play tower molded cap",45.52,49.4,22.32,25.96,3.45,4.04,oxide)
            for y in (22.63,25.64):
                slab(lot,"Molded safety guard",(45.82,49.11,y-.07,y+.07,1.54,2.33),light)
            slide=[(48.99,23.35,1.49),(48.99,24.45,1.49),(51.8,24.35,.165),(51.8,23.45,.165)]
            mesh("New curved play slide",lot,slide,[(0,1,2,3)],oxide)
            for y0,y1 in ((23.35,23.45),(24.45,24.35)):
                beam("Molded slide lip",lot,(49.,y0,1.54),(51.8,y1,.25),(.078,.073),(.078,.073),oxide,4)
            for i in range(4 if not far else 2):
                z=.35+i*.30
                slab(lot,"Molded adventurer access rung",(45.62,45.80,23.72,24.66,z,z+.08),light)
            collide("Molded play tower",(45.52,51.85,22.32,25.96,.062,4.04),"lot")
            # Wire panels are tile-mapped cutouts. Leave an entry gap facing the bus loop.
            for a,b,c,d in ((31.1,38,11.31,11.31),(41.2,52.0,11.31,11.31),
                            (52.,52.,11.31,42.42),(31.1,52.,42.42,42.42),(31.1,31.1,11.31,42.42)):
                coords=[(a,c,.035),(b,d,.035),(b,d,1.53),(a,c,1.53)]
                mesh("Galvanized chain-link panel",lot,coords,[(0,1,2,3)],fence_mat)
                collide("Chain-link perimeter",(a-.07,b+.07,c-.07,d+.07,.027,1.60),"lot")
                count=3 if near and b-a+d-c>17 else 2
                for i in range(count):
                    t=i/(count-1)
                    x,y=a+(b-a)*t,c+(d-c)*t
                    slab(lot,"Fence tension post",(x-.045,x+.045,y-.045,y+.045,.027,1.62),slate)
        if modern:
            slab(lot,"Portable classroom walk",(-26.55,.65,28.42,30.1,.028,.087),light)
            slab(lot,"Portable walk from west end",(-28.8,-26.96,9.0,30.09,.028,.08),light)
            slab(lot,"Portable walk western return",(-28.8,-26.48,28.42,30.10,.028,.082),light)
            for x,key in ((-21.0,"portable_a"),(-6.9,"portable_b")):
                a,b=x-5.58,x+5.58
                parent=group(key,addition)
                slab(parent,"Portable floor and skirting",(a,b,30.12,37.39,.03,.43),floor)
                facade(parent,"Portable front",(a,b),30.12,3.7,.43,wood,
                       [(a+1.1,4,1.45)],[(x+2.77,x+4.16,2.98)])
                facade(parent,"Portable back",(a,b),37.18,3.7,.43,wood,
                       [(a+1.02,5,1.45)],reverse=True)
                for xx in (a,b-.21):
                    box_at(parent,"Portable end",(xx,xx+.21,30.35,37.17,.43,3.70),wood,True)
                roof_cover(parent,a-.25,b+.25,29.93,37.59,3.71,4.32,roof)
                gable(parent,"Portable gable closure",a,b,30.12,37.39,3.69,4.21,wood)
                for off,z in ((1.06,.16),(.69,.29),(.33,.43)):
                    slab(parent,"Portable steps",(x+2.50,x+4.42,30.12-off,30.12,.027,z),light)
                lettering(parent,"sign_"+key,key,x-.20,30.097,2.67)
                marker("door_"+key,x+3.46,29.54,.43)
                batch(parent)
    for parent in (shell,inside,lot):
        batch(parent)
    name=state+("" if near else f"-{lod}")
    draw=[o for o in bpy.data.objects if o.type=="MESH" and not o.get("collision_only")]
    triangles=sum(len(p.vertices)-2 for o in draw for p in o.data.polygons)
    export_glb(ROOT/f"{name}.glb")
    if near:
        save_source(ROOT/f"{name}.blend")
    return {"file":f"{name}.glb","lod":lod,"triangles_visible":triangles,
            "door_nodes_gltf_m":doors,"collision_boxes_gltf_m":boxes,"sign_panels":signs,
            "materials":sorted({m.name for o in draw for m in o.data.materials})}


metadata={"task":"C004","units":"glTF metres, +Y upwards, +Z street-facing, grade origin at front midpoint.",
          "swatches":["brick_red","plank_horizontal","ballast","pavement","asphalt","roof_slate","roof_membrane_grey","roof_membrane_green","roof_membrane_tan"],
          "lod_distances_suggested_m":{"mid":65,"far":140},"states":[]}
for state,(name,era,bounds,height) in STATES.items():
    models=[build(state,lod) for lod in ("near","mid","far")]
    print(state,[(e["lod"],e["triangles_visible"]) for e in models])
    a,b,c,d=bounds
    metadata["states"].append({"id":state,"name":name,"era":era,"footprint_m":[b-a,d-c],"height_m":height,
                              "lot_bounds_gltf_m":{"x":[a,b],"y":[0,height],"z":[-d,-c]},"models":models})
(ROOT/"model-info.json").write_text(json.dumps(metadata,indent=2)+"\n")
