"""Build Thimble Trails in three conditions and three LODs from shared survey coordinates."""
import json
from math import cos, pi, sin, atan2
from pathlib import Path
from random import Random
import sys

import bpy

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from sculpture_tools import beam, box, group, mesh, material, oval, plaque
from revision_common import batch, emitter, export_glb, save_source

T=.0388
FLOOR=.13
PAD=(-13.5,-2.85)
REGIONS=json.loads((ROOT/"textures/sign-regions.json").read_text())
STATES={"1962":("Open",1962,1989),"1990":("Faded",1990,2005),"2006":("Closed and overgrown",2006,None)}
ROWS=((8.30,16.90),(19.90,28.50),(31.60,40.20))
COLS=(-16.25,-10.25,-4.25,1.75,7.75,13.75)


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
    gold=material("detail_mustard","gold-"+year)
    water=material("detail_pond","water-"+year)
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
    block(lot,"Grassy parcel",(-20,20,-9,41,0,.026),yard)
    for a,b,c,d in ((-7.7,18.5,-7.2,-2.12),(-3,3,-2.12,7.01),
                    (-19.9,18.6,7.01,8.16),(-18.99,17.0,17.30,19.50),
                    (-18.99,17.0,28.90,31.19),(17.0,18.6,8.16,40.7),
                    (-19.9,-18.99,8.16,40.7)):
        flat(lot,"Walking path",[(a,c),(b,c),(b,d),(a,d)],.037,path)
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
    block(hut,"Name fascia",(-4.16,4.16,-.66,.06,2.89,5.19),gold)
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
            block(hut,"Closed window board",(1.31,3.96,-.065,-.029,zz,zz+.19),gold)

    # Eighteen playable footprints. A ring of triangles around each cup forms a real recess.
    def corners(x,y0,y1,half,clip):
        return [(x-half+clip,y0),(x+half-clip,y0),(x+half,y0+clip),(x+half,y1-clip),
                (x+half-clip,y1),(x-half+clip,y1),(x-half,y1-clip),(x-half,y0+clip)]
    decorations={3:"windmill",5:"lighthouse",7:"volcano",9:"pond bridge",12:"covered bridge",16:"castle"}
    for row,(y0,y1) in enumerate(ROWS):
        cols=COLS if row!=1 else COLS[::-1]
        for index,x in enumerate(cols):
            n=row*6+index+1
            reverse=row==1
            tee_y=y1-.95 if reverse else y0+.95
            cup_y=y0+1.15 if reverse else y1-1.15
            cup_x=x+.35 if n%2 else x-.35
            half=(1.98,2.19,2.27)[(n+row)%3]
            outline=corners(x,y0,y1,half,.62)
            mid=(y0+y1)/2
            if n not in decorations:
                # Make the runs alternate between tapered, dogleg and standard courses.
                shape=n%4
                transformed=[]
                for px,py in outline:
                    t=(py-y0)/(y1-y0)
                    lean=(t-.5)*.94 if shape==0 else (t-.5)*-.59 if shape==1 else 0
                    taper=1-.35*t if shape==2 else .75+.25*t if shape==3 else 1
                    transformed.append((x+(px-x)*taper+lean,py))
                outline=transformed
            curb=[(x+(px-x)*1.075,mid+(py-mid)*1.025) for px,py in outline]
            # A light retaining lip around each green, at its own elevation.
            rim=[(px,py,z) for polygon,z in ((curb,.139),(outline,.139),(curb,.026),(outline,.096))
                 for px,py in polygon]
            faces=[]
            for i in range(8):
                j=(i+1)%8
                faces.extend(((i,j,j+8,i+8),(j,i,i+16,j+16),(i+8,j+8,j+24,i+24)))
            mesh("Shallow curb with exposed edges",course,rim,faces,path)
            turf_nodes=[(a,b,.096) for a,b in outline]
            for px,py in outline:
                a=atan2(py-cup_y,px-cup_x)
                turf_nodes.append((cup_x+.105*cos(a),cup_y+.105*sin(a),.096))
            turf_faces=[(i,(i+1)%8,(i+1)%8+8,i+8) for i in range(8)]
            mesh(f"Hole {n:02d} cup cutout",course,turf_nodes,turf_faces,turf)
            if not far:
                flat(course,"Dark cup well",[(p[0],p[1]) for p in turf_nodes[8:]],.073,trim)
            flat(course,"Tee mark",[(x-.43,tee_y-.055),(x+.43,tee_y-.055),(x+.43,tee_y+.055),(x-.43,tee_y+.055)],.101,cream)
            marker_y=y1+.055 if reverse else y0-.063
            sign(course,f"hole_marker_{n:02d}",f"hole_{n}",x+1.24,marker_y,.15,reverse,False)
            a,b=(marker_y-.11,marker_y-.008) if reverse else (marker_y+.008,marker_y+.11)
            block(course,"Number plate leg",(x+1.18,x+1.30,a,b,.04,1.14),trim)
            holes.append({"hole":n,"tee_gltf_m":[x,.101,-tee_y],"cup_gltf_m":[cup_x,.073,-cup_y],
                          "obstacle":decorations.get(n,"banks or open run")})
            if n not in decorations and n not in (1,2,18):
                px=x+(.61 if n%2 else -.61)
                y=(y0+y1)/2
                bounds=(px-.64,px+.64,y-.21,y+.21,.096,.28)
                barrier(obstacles,"Low fiberglass bank",bounds,cream if n%2 else gold)
                if n%3==1 and not far:
                    block(obstacles,"Secondary paired bank",(x-.67,x+.06,y+1.22,y+1.58,.096,.26),red)

    # Hole 3, octagonal windmill tower with a clear ground-level tunnel and a rotating blade hub.
    x,y=-4.25,12.19
    for a,b in ((x-1.26,x-.69),(x+.69,x+1.26)):
        barrier(obstacles,"Windmill tunnel abutment",(a,b,y-1.28,y+1.28,.096,1.06),cream)
    oval("Windmill tapered mill body",obstacles,[(x,y,z,r,r) for z,r in
         ((1.04,1.39),(2.83,.81),(3.55,.78))],cream,12 if near else 8)
    oval("Windmill red bonnet",obstacles,[(x,y,z,r,r) for z,r in ((3.47,.86),(3.54,.89),(4.25,.14))],red,12 if near else 8)
    axle_z=2.85
    beam("Stationary blade hub",obstacles,(x,y-.72,axle_z),(x,y-1.05,axle_z),(.138,.138),(.138,.138),trim,8)
    rotor=group("windmill_blades",root)
    rotor.location=(x,y-1.066,axle_z)
    rotor["animation_axis_gltf"]="local +Z, facing the driveway"
    for k in range(4):
        ang=pi*.25+k*pi*.5
        vx,vz=cos(ang),sin(ang)
        ux,uz=-vz,vx
        verts=[(vx*r+ux*w,yy,vz*r+uz*w) for yy in (-.05,.012) for r,w in
               ((.14,-.066),(.14,.065),(1.76,.298),(1.83,-.096))]
        mesh("Windmill blade",rotor,verts,[(0,1,2,3),(7,6,5,4),(0,4,5,1),(1,5,6,2),
                                               (2,6,7,3),(3,7,4,0)],gold)
        if near:
            beam("Blade painted long spar",rotor,(vx*.28,-.064,vz*.28),(vx*1.75,-.064,vz*1.75),(.028,.024),(.028,.024),trim,4)
    batch(rotor)

    # Hole 5, a staggered striped lighthouse with a lantern ring.
    x,y=8.92,13.37
    for a,b,r,s,mat in ((.096,1.18,.73,.63,cream),(1.18,1.88,.63,.56,red),
                       (1.88,2.57,.56,.48,cream),(2.57,3.16,.48,.40,red),
                       (3.16,3.68,.42,.42,water),(3.68,4.20,.49,.12,red)):
        oval("Lighthouse band",obstacles,[(x,y,a,r,r),(x,y,b,s,s)],mat,12 if not far else 8)
    collider("Lighthouse",(x-.74,x+.74,y-.74,y+.74,.096,4.2),obstacles)
    if not far:
        for ang in (0,pi/2,pi,3*pi/2):
            px,py=x+.39*cos(ang),y+.39*sin(ang)
            block(obstacles,"Lantern mullion",(px-.041,px+.041,py-.041,py+.041,3.15,3.67),trim)

    # Hole 7, a faceted volcano off the play line, with a dark recessed crater.
    x,y=15.04,24.18
    count=10 if near else 8 if not far else 6
    rim=[(x+r*(1+.11*cos(i*6*pi/count))*cos(i*2*pi/count),
          y+r*(1+.11*cos(i*6*pi/count))*sin(i*2*pi/count),z+(.16*cos(i*8*pi/count) if z>3 else 0))
          for z,r in ((.096,1.52),(1.17,1.14),(2.68,.63),(3.32,.37),(3.17,.19)) for i in range(count)]
    faces=[(j*count+i,j*count+(i+1)%count,(j+1)*count+(i+1)%count,(j+1)*count+i)
           for j in range(4) for i in range(count)]
    faces.append(tuple(range(4*count,5*count)))
    mats=[(gold if j==0 else red if (i+j)%3 else trim) for j in range(4) for i in range(count)]+[trim]
    mesh("Volcano with molded irregular strata",obstacles,rim,faces,mats)
    collider("Volcano perimeter",(13.44,16.73,22.55,25.81,.096,3.48),obstacles)

    # Hole 9, pond to either side of a real walk-over deck.
    x,y=1.75,24.13
    oval("Pond molded rim",obstacles,[(x,y,z,rx,ry) for z,rx,ry in ((.096,2.34,1.91),(.145,2.34,1.91))],water,16 if near else 12)
    block(obstacles,"Pond bridge walk",(x-.64,x+.64,y-1.92,y+1.92,.141,.247),gold)
    for side in (-1,1):
        ya,yb=y+side*1.91,y+side*2.56
        mesh("Pond footbridge approach",obstacles,
             [(x-.64,ya,.247),(x+.64,ya,.247),(x+.64,yb,.106),(x-.64,yb,.106)],
             [(0,1,2,3) if side==1 else (3,2,1,0)],gold)
    for xx in (x-.68,x+.68):
        beam("Pond bridge side rail",obstacles,(xx,y-1.88,.48),(xx,y+1.88,.48),(.051,.053),(.051,.053),cream,4)
    for a,b in ((x-2.35,x-.67),(x+.67,x+2.35)):
        collider("Pond pool side",(a,b,y-1.55,y+1.55,.096,.22),obstacles)

    # Hole 12, the covered bridge with open ends and thick red roof.
    x,y=-16.25,24.13
    block(obstacles,"Covered bridge deck",(x-1.27,x+1.27,y-1.77,y+1.77,.096,.171),path)
    for side in (-1,1):
        xx=x+side*1.14
        barrier(obstacles,"Covered bridge side",(xx-.12,xx+.12,y-1.76,y+1.76,.171,.91),cream)
        for yy in (y-1.60,y+1.6):
            block(obstacles,"Covered bridge corner post",(xx-.078,xx+.078,yy-.091,yy+.091,.89,2.22),trim)
    gable(obstacles,"Covered bridge gable roof",x-1.46,x+1.46,y-1.91,y+1.91,2.18,2.92,red)

    # Hole 16, miniature castle gate. The two towers stand off the open center line.
    x,y=1.75,36.28
    for xx in (x-1.01,x+1.01):
        oval("Castle round tower",obstacles,[(xx,y,z,r,r) for z,r in ((.096,.55),(2.08,.52),(2.23,.61))],cream,12 if near else 8)
        collider("Castle tower",(xx-.61,xx+.61,y-.61,y+.61,.096,2.43),obstacles)
        for i in range(6 if not far else 4):
            a=i*pi/(3 if not far else 2)
            px,py=xx+.47*cos(a),y+.47*sin(a)
            block(obstacles,"Castle tour crenel",(px-.099,px+.099,py-.099,py+.099,2.2,2.48),gold)
    block(obstacles,"Castle open arch lintel",(x-.88,x+.88,y-.39,y+.33,1.18,1.91),cream)
    for i in (-1,0,1):
        xx=x+i*.51
        block(obstacles,"Gatehouse crenel",(xx-.14,xx+.14,y-.42,y-.16,1.89,2.18),gold)

    if closed:
        # Fence the facility after closure. Behind it, the original hut apertures still exist.
        spans=((-19.82,19.82,-8.93,-8.93),(-19.82,19.82,40.78,40.78),
               (-19.82,-19.82,-8.93,40.78),(19.82,19.82,-8.93,40.78))
        for a,b,c,d in spans:
            coords=[(a,c,.028),(b,d,.028),(b,d,1.76),(a,c,1.76)]
            mesh("Closed galvanized chain-link",lot,coords,[(0,1,2,3)],chain)
            collider("Closed chain-link perimeter",(a-.085,b+.085,c-.085,d+.085,.026,1.82),lot)
            n=10 if near else 6 if not far else 4
            for i in range(n):
                f=i/(n-1)
                x,y=a+(b-a)*f,c+(d-c)*f
                block(lot,"Fence tension post",(x-.047,x+.047,y-.047,y+.047,.026,1.81),trim)
        block(lot,"Closed gate sign blank",(-2.42,2.42,-8.987,-8.916,.83,2.07),gold)
        sign(lot,"sign_closed","closed",0,-8.999,.86)
        rng=Random(2006)
        patches=[(-17,6.35),(-9.8,6.71),(5,4.45),(13,5.30),(-4.7,5),(-8.2,18.0),
                 (-14.2,18),(17.3,20.9),(-18.2,28.2),(4.9,29.3),(11.2,29.5),(18.9,35),
                 (-13.1,30.7),(0,30.84),(14.5,40.74),(-16.9,38.9),(8.8,40.48),(-6,40.53)]
        for x,y in patches[::1 if near else 2 if not far else 3]:
            verts=[]
            for i in range(9):
                angle=i*.698
                r=rng.uniform(.14,.40)
                verts.extend(((x-.047*sin(angle),y+.047*cos(angle),.045),
                              (x+.047*sin(angle),y-.047*cos(angle),.045),
                              (x+r*cos(angle),y+r*sin(angle),rng.uniform(.28,.80))))
            mesh("Unmown grass tussock",lot,verts,[tuple(range(i,i+3)) for i in range(0,len(verts),3)],yard)
    elif faded:
        for x,y in ((-18.9,7.23),(-12.18,18.82),(16.72,29.3),(4.51,37.36)):
            flat(lot,"Dry faded turf tuft",[(x-.41,y-.13),(x+.34,y-.19),(x+.51,y+.12),(x-.23,y+.19)],.041,gold)

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
            "materials":sorted({m.name for ob in drawn for m in ob.data.materials})}


meta={"task":"C007","name":"Thimble Trails Mini Golf","footprint_m":[40,50],"height_m":5.5,
      "coordinates":"Metres. +Y up, entrance facing +Z, grade origin on front wall midpoint.",
      "lot_bounds_gltf_m":{"x":[-20,20],"y":[0,5.5],"z":[-41,9.0]},
      "statue_pad":{"node":"statue_pad","anchor_node":"statue_anchor","usable_size_m":[9.76,11.72],
                    "center_gltf_m":[PAD[0],.096,-PAD[1]]},
      "windmill":{"node":"windmill_blades","axis_local_gltf":[0,0,1],
                  "pivot_gltf_m":[-4.25,2.85,-11.124],"suggested_period_s":6.0,
                  "closed_state":"Leave stationary"},
      "course_note":"Stable tee and cup positions across eras. Independent engine routing, ball traversal and closure gating.",
      "swatches":["roof_membrane_green","roof_membrane_grey","roof_membrane_ochre","pavement","plank_horizontal","roof_tin_red"],
      "lod_distances_suggested_m":{"mid":55,"far":135},"states":[]}
for year,(name,start,end) in STATES.items():
    models=[build(year,lod) for lod in ("near","mid","far")]
    print(year,[m["triangles_visible"] for m in models])
    meta["states"].append({"id":year,"title":name,"era":[start,end],"models":models})
(ROOT/"model-info.json").write_text(json.dumps(meta,indent=2)+"\n")
