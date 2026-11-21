"""Generate the Eyrie's faceted bronze wings and granite public terraces."""
import json
import sys
from pathlib import Path
from math import sin, cos, pi

import bpy
from mathutils import Vector
ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
from sculpture_tools import T, beam, box, group, loft, material, mesh, oval, plaque

bpy.ops.wm.read_factory_settings(use_empty=True)
root = group("the_eyrie")
court = group("terraces",root)
sculpture = group("bronze_wings",root)
lot = group("lot",root)
granite = material("wall_granite","granite")
limestone = material("wall_limestone","carving")
ashlar = material("wall_ashlar","ashlar")
verdigris = material("detail_patina","copper")
raised = material("detail_burnished_bronze","copper_light")
bronze = material("detail_shadowed_bronze","bronze")
paving = material("ground_paving","pavement")
grass = material("ground_grass","grass")
leaf = material("detail_foliage","leaf")
wood = material("detail_timber","plank_horizontal")
sign = material("sign_name","name")
glow = material("glow_amber","lamp",.35)


def box_at(name,parent,x,y,z,w,d,h,mat):
    return box(name,parent,(x,y,z+h/2),(w,d,h),mat)


def bevelled_course(name,parent,x,y,rings,mat):
    points = []
    for z,w,d in rings:
        a,b=w/2,d/2
        k=min(a,b)*.34
        points.append([(x+u,y+v,z) for u,v in
            [(-a+k,-b),(a-k,-b),(a,-b+k),(a,b-k),(a-k,b),(-a+k,b),(-a,b-k),(-a,-b+k)]])
    return loft(name,parent,points,mat)


box_at("Esplanade",court,0,0,0,198,160,.16,paving)
for s in (-1,1):
    for y in (-48,45):
        box_at("Lawn perimeter",court,s*65,y,.16,49,45,.44,granite)
        box_at("Lawn",court,s*65,y,.6,47,43,.04,grass)
        for dy in (-15,0,15):
            x=s*(64+abs(dy)*.68)
            tree=group(f"tree_{s}_{y+dy}",lot)
            oval("Trunk",tree,[(x,y+dy,.64,.25,.25),(x,y+dy,5,.19,.19)],wood,n=8)
            oval("Leaf canopy",tree,[(x,y+dy,3,1.4,1.35),(x,y+dy,5,3,2.74),
                 (x+.3,y+dy+.3,8.8,2.4,2.45),(x+.55,y+dy+.36,10.35,.7,.8)],leaf,n=9)
        for dy in (-13,13):
            x=s*33
            lamp=group(f"lamp_{s}_{y+dy}",lot)
            oval("Lamp post",lamp,[(x,y+dy,.16,.34,.34),(x,y+dy,.72,.25,.25),
                 (x,y+dy,3.8,.105,.105)],bronze,n=8)
            oval("Amber lantern",lamp,[(x,y+dy,3.8,.24,.24),(x,y+dy,4.3,.38,.38)],glow,n=8)
            oval("Lantern hood",lamp,[(x,y+dy,4.28,.43,.43),(x,y+dy,4.7,.12,.12)],bronze,n=8)

# Contrasting granite rays draw the walking approach toward the gap between the wings.
for face in (-1,1):
    for side in (-1,1):
        for i in range(4):
            far, near = 15+5*i, 6+3.8*i
            corners=[(side*far,face*75,.173),(side*(far+.55),face*75,.173),
                     (side*(near+.64),face*40,.173),(side*near,face*40,.173)]
            if side*face>0:
                corners.reverse()
            mesh("Inlaid approach ray",court,corners,[(0,1,2,3)],granite)
    for x in (-49,49):
        bevelled_course("Processional lantern footing",court,x,face*32,
            [(.16,4.4,4.4),(.54,4.4,4.4),(1.1,3.55,3.55),(3.57,2.1,2.1)],ashlar)
        oval("Stone pillar lantern",court,[(x,face*32,3.57,.63,.63),
             (x,face*32,4.55,.66,.66)],glow,n=8)
        oval("Lantern roof",court,[(x,face*32,4.53,.87,.87),
             (x,face*32,5.03,.34,.34)],bronze,n=8)

# Twenty shallow treads rise to the open overlook from either end.
bevelled_course("Main terrace",court,0,0,[(.16,71,46),(3.66,71,46)],granite)
bevelled_course("Stone coping",court,0,0,[(3.64,71.28,46.28),(3.68,71.28,46.28)],limestone)
for s in (-1,1):
    for i in range(20):
        y=s*(38.10-i*.8)
        box_at("Ceremonial stair",court,0,y,.16,46,.801,(i+1)*.176,ashlar)
    for x in (-27,27):
        bevelled_course("Processional stair cheek",court,x,s*30,[(.16,4.6,17.3),
             (1.33,4.6,17.3),(3.66,4.15,5.1)],granite)
    # The broad outer ledges lead to matching basalt screen walls.
    for x in (-32,32):
        box_at("Balustrade plinth",court,x,s*17.6,3.68,3.6,8,.55,granite)
        for y in range(15,22):
            box_at("Square baluster",court,x,s*y,4.22,1.2,.46,.98,ashlar)
        box_at("Balustrade cap",court,x,s*17.8,5.18,1.78,8.1,.25,limestone)
    y=s*11.15
    box_at("Dedication stone",court,0,y,3.68,11.2,1.3,2.05,granite)
    box_at("Dedication coping",court,0,y,5.7,11.5,1.6,.22,limestone)
    w,h=256*T,32*T
    pts=[(-w/2,y+s*.666,4.76-h/2),(w/2,y+s*.666,4.76-h/2),
         (w/2,y+s*.666,4.76+h/2),(-w/2,y+s*.666,4.76+h/2)]
    plaque("sign_name" if s<0 else "sign_name_reverse",court,
           pts if s<0 else [pts[i] for i in (1,0,3,2)],sign,(0,0,256,32),(256,32))

# Each hull is held by its own granite abutment. One wing is canted forward.
for side,y_shift in [(-1,-2.8),(1,3.9)]:
    bevelled_course("Granite wing abutment",court,side*20,y_shift,
        [(3.67,22,30),(5.9,22,30),(11,17.4,21.8),(16.2,13.6,17.7)],granite)
    bevelled_course("Bronze root collar",sculpture,side*20,y_shift,
        [(15.95,13.45,17),(18.8,11.2,12.8),(20.2,9.2,10.8)],bronze)
    def pt(x,y,z):
        return Vector((side*x,y+y_shift+(max(z-20,0)*.13 if side>0 else 0),
                       z+max(z-20,0)*(.095 if side>0 else -.024)))
    def arm(name,stations,skin):
        rings=[]
        for i,(x,y,z,width,thickness) in enumerate(stations):
            prev=stations[max(0,i-1)]
            nxt=stations[min(i+1,len(stations)-1)]
            dx,dz=nxt[0]-prev[0],nxt[2]-prev[2]
            norm=(dx*dx+dz*dz)**.5
            xp,zp=dz/norm,-dx/norm
            rings.append([pt(x+u*width*xp,y+v*thickness,z+u*width*zp)
                 for u,v in [(-1,0),(-.44,-.82),(.22,-1),(1,0),(.55,.77),(-.31,1)]])
        if side<0:
            rings=[list(reversed(ring)) for ring in rings]
        return loft(name,sculpture,rings,skin)

    # The deep central spine runs from the abutment to the high leading edge.
    spine=[(20,0,17.8,5.2,5.2),(21,.3,30,6.8,6),(26,1.65,43,8.8,6.9),
           (34,3.4,57,9.1,6.8),(44,6.3,69.5,7.8,5.7),(55,9.2,81.4,6.0,4.4),
           (67,13.4,91.2,4.2,3.02),(76,17.5,99.4,2.2,1.56),(82,21,105,0.48,.5)]
    arm("Sculpted leading spar",spine,lambda tier,face: raised if face in (0,1) else verdigris if face<4 else bronze)

    # Each long primary rises with the great spar, then sweeps down to a free cast tip.
    feathers=[
        ((20,1,29),(37,5,25),(44,6,18),4.0),
        ((22,1.7,37),(42,7,29),(51,9,21),4.6),
        ((26,3,46),(49,8,36),(60,12,27),5.1),
        ((30,4.8,54),(54,11,45),(68,16,34),5.35),
        ((36,6.5,63),(62,15,53),(75,20,42),5.55),
        ((43,8.9,71),(68,19,61),(82,25,51),5.6),
        ((50,11,78),(75,23,69),(89,30,62),5.58),
        ((57,13.3,84.3),(79,25,77.3),(94,34,73),5.25),
        ((64,16,90),(82,28,85),(96,35,84),4.7),
        ((71,18.2,95.6),(85,27,92),(96,33,94),3.65),
        ((76,20,100.2),(86,26,98),(94,29,103),2.85)
    ]
    for i,(a,b,c,w) in enumerate(feathers):
        midpoint=tuple((u+v)*.5 for u,v in zip(a,b))
        end=tuple(.52*u+.48*v for u,v in zip(b,c))
        stations=[(*a,w*.63,2.2),(*midpoint,w,2.7),(*b,w*.78,2.07),
                  (*end,w*.53,1.37),(*c,.36,.42)]
        arm("Swept bronze primary",stations,lambda tier,k: (raised if k==0 or (k==1 and i%3==0)
            else verdigris if k in (1,2,5) else bronze))
        # Narrow fore- and aft-face ridges form the quills.
        for face in (-1,1):
            ridges=[pt(x,y+face*t*.945,z) for x,y,z,_,t in stations[1:-1]]
            for u,v in zip(ridges,ridges[1:]):
                beam("Raised quill",sculpture,u,v,(.32,.32),(.24,.24),
                     raised if face<0 else bronze,n=6)

    # A second register of shorter coverts overlaps the roots of the long feathers.
    for i,(x,y,z,w,depth) in enumerate(spine[1:-1]):
        for face in (-1,1):
            stations=[(x,y+face*(depth-.48),z+1.8,1.3,.85),
                (x+5.2,y+face*(depth+.36),z-1.9,2.7,1.15),
                (x+11.2,y+face*(depth-.12),z-3.65,1.75,.87),
                (x+14.5,y+face*(depth-.95),z-5.35,.28,.25)]
            arm("Layered bronze covert",stations,
                lambda tier,k: raised if k<2 else verdigris if k<4 else bronze)

for parent in (court,sculpture,*lot.children):
    groups={}
    for ob in parent.children:
        if ob.type=="MESH" and not ob.name.startswith("sign_name"):
            groups.setdefault(tuple(mat.name for mat in ob.data.materials),[]).append(ob)
    for i,objects in enumerate(groups.values()):
        bpy.ops.object.select_all(action="DESELECT")
        for ob in objects:
            ob.select_set(True)
        bpy.context.view_layer.objects.active=objects[0]
        if len(objects)>1:
            bpy.ops.object.join()
        objects[0].name=f"{parent.name}__{i}"

objects=[ob for ob in root.children_recursive if ob.type=="MESH"]
height=round(max(v.co.z for ob in objects for v in ob.data.vertices),4)
triangles=sum(len(p.vertices)-2 for ob in objects for p in ob.data.polygons)
materials=sorted({mat.name for ob in objects for mat in ob.data.materials})
assert triangles<=12000 and len(materials)<=12
info={"name":"The Eyrie","era":"1938 national sculptural commission, suitable from the 1940s onward",
      "height_m":height,"footprint_m":[198,160],"triangles_visible":triangles,
      "materials":materials,"units":"glTF metres, Y up, +Z south, origin on front edge",
      "doors":[],"collision":{"use_the_walls":True,"notes":"Open-air terraces. Use the granite geometry for abutments and screen walls. Verify stair traversal in game."},
      "texel_size_m":T,"texels_per_art_pixel":2,"swatches_used":["pavement","plank_horizontal"]}
(ROOT/"model-info.json").write_text(json.dumps(info,indent=2)+"\n")
bpy.ops.export_scene.gltf(filepath=str(ROOT/"the-eyrie.glb"),export_format="GLB",export_extras=True)
bpy.ops.file.pack_all()
bpy.context.preferences.filepaths.save_version=0
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/"the-eyrie.blend"))
