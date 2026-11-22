"""Build Marlow's four states and their matching mid/far LODs."""
from math import pi
from pathlib import Path
import json
import sys

import bpy

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from sculpture_tools import group, material, mesh, box, plaque
from revision_common import batch, emitter, export_glb, save_source

STATES={"1925":("Marlow Dime",1925,1969),"1970":("Marlow Variety",1970,1999),
        "2000":("Dollar Cove",2000,None),"closed":("For Lease",None,None)}
REGIONS=json.loads((ROOT/"textures"/"sign-regions.json").read_text())

def slab(name,parent,x0,x1,y0,y1,z0,z1,mat):
    return box(name,parent,((x0+x1)/2,(y0+y1)/2,(z0+z1)/2),(x1-x0,y1-y0,z1-z0),mat)

def build(state,lod):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    near,far=lod=="near",lod=="far"
    old=state=="1925"
    closed=state=="closed"
    root=group("Marlow Five and Dime")
    shell=group("building_shell",root)
    top=group("roof_and_parapet",root)
    shop=group("fixed_shop_fixtures",root)
    lot=group("lot",root)
    collision=group("collision",root)
    brick=material("wall_brick","brick")
    stone=material("wall_limestone","limestone")
    red=material("detail_oxblood","oxblood")
    iron=material("detail_frames","iron")
    roof=material("roof_built_up","tar")
    floor=material("floor_shop","wood" if state in ("1925","closed") else "tile")
    paving=material("ground_pavement","walk")
    block=material("wall_receiving_block","block")
    glow=material("glow_fixtures","warm" if old else "fluoro",.05)
    glass=material("glass_storefront","glass-"+state)
    glass.surface_render_method="BLENDED"
    shader=glass.node_tree.nodes.get("Principled BSDF")
    shader.inputs["Roughness"].default_value=.42
    tex=next(n for n in glass.node_tree.nodes if n.type=="TEX_IMAGE")
    glass.node_tree.links.new(tex.outputs["Alpha"],shader.inputs["Alpha"])
    emitter(glass,"pane-mask-"+state,.08)
    signmat=material("sign_name","signs")
    emitter(signmat,"sign-mask",.07)
    invisible=bpy.data.materials.new("collision_invisible")
    invisible.use_nodes=True
    invisible.node_tree.nodes.get("Principled BSDF").inputs["Alpha"].default_value=0
    invisible.surface_render_method="BLENDED"

    boxes=[]
    doors={}
    painted=None

    def collide(name,extents,attachment=""):
        a,b,c,d,e,f=extents
        ob=slab(f"collider_{len(boxes):02}",collision,*extents,stone)
        ob.data.materials.clear()
        ob.data.materials.append(invisible)
        ob["collision_only"]=True
        boxes.append({"name":name,"center_gltf_m":[(a+b)/2,(e+f)/2,-(c+d)/2],
                      "size_gltf_m":[b-a,f-e,d-c],"group":"lot" if attachment else "building",
                      "attachment":attachment})

    def wall(name,parent,extents,mat,blocking=True):
        slab(name,parent,*extents,mat)
        if blocking:
            collide(name,extents)

    def door(name,x,y,level,angle):
        ob=group(name,root)
        ob.location=(x,y,level)
        ob.rotation_euler.z=angle
        doors[name]=[x,level,-y]

    def sign(region,name,x,y,z,reverse=False):
        nonlocal painted
        row,w,h=REGIONS[region]
        width,height=w*.0776,h*.0776
        a,b=(x+width/2,x-width/2) if reverse else (x-width/2,x+width/2)
        ob=plaque(name,shell,[(a,y,z),(b,y,z),(b,y,z+height),(a,y,z+height)],
                   signmat,(0,row*2,w*2,h*2),(256,512))
        ob["keep"]=True
        if name=="sign_name":
            painted={"node":"sign_name","texture":"signs.png","rect_texels":[0,row*2,w*2,h*2],
                     "atlas_size_texels":[256,512],"bottom_left_gltf_m":[a,z,-y],
                     "width_m":width,"height_m":height}

    def window(label,x0,x1,y,z0,z1,back=False,rails=True):
        verts=[(x0,y,z0),(x1,y,z0),(x1,y,z1),(x0,y,z1)]
        mesh(label,shell,verts,[(3,2,1,0) if back else (0,1,2,3)],glass)
        mat=iron if state in ("1970","2000") else red
        if not far:
            for x in ([x0,x1,(x0+x1)/2] if rails else [x0,x1]):
                slab("Glazing mullion",shell,x-.0425,x+.0425,y-.087,y+.087,z0-.035,z1+.04,mat)
            for z in (z0-.03,z1):
                slab("Glazing sash",shell,x0-.041,x1+.041,y-.08,y+.08,z,z+.045,mat)
        collide(label,(x0,x1,y-.049,y+.049,z0,z1))

    # Party walls, load-bearing piers and fixed entrances share one footprint.
    slab("Ground-level timber floor",shell,-5,5,.03,28,.035,.12,floor)
    slab("Shared street sidewalk",root,-5,5,-2.35,.04,0,.069,paving)
    slab("Rear receiving pad",root,1.98,4.63,27.80,29.01,0,.12,paving)
    slab("Recessed entry terrazzo",shell,-1.16,1.16,-.31,1.03,.066,.12,floor)
    for x0,x1 in ((-5,-4.72),(4.72,5)):
        wall("Solid brick party wall",shell,(x0,x1,.00,28,.12,7.76),brick)
    for a,b in ((-5,-4.53),(-1.24,-.98),(.98,1.24),(4.53,5)):
        wall("Street-level masonry pier",shell,(a,b,.00,.32,.12,3.57),brick)
    for a,b in ((-4.53,-1.24),(1.24,4.53)):
        wall("Street display base",shell,(a,b,.00,.27,.12,.57),red)
        slab("Stone display footing",shell,a,b,-.09,.28,.55,.65,stone)
        window("Street display pane",a,b,.148,.64,3.20)
    for a,b in ((-1.24,-.96),(.96,1.24)):
        wall("Recessed shop entry return",shell,(a,b,.27,.96,.12,3.16),iron)
    slab("Entry transom head",shell,-.98,.98,.70,.95,2.48,3.18,red)
    for x in (-.992,.992):
        slab("Front door jamb",shell,x-.049,x+.049,.70,.93,.12,2.49,iron)
    door("door_main",0,.57,.12,0)
    door("door_back",3.28,28.22,.12,pi)
    slab("Street facade belt",shell,-5,5,-.007,.27,3.19,4.79,brick)
    slab("Fixed red-and-gold sign backing",shell,-4.65,4.65,-.105,.009,3.25,4.76,red)
    if not old:
        slab("Thin replacement sign case",shell,-4.55,4.55,-.199,-.073,3.39,4.68,iron)
    sign("name-"+state,"sign_name",0,-.112 if old else -.205,3.26 if old else 3.41)
    if state=="2000":
        sign("value","sign_value",-3.25,.107,.81)
        sign("sale","sign_sale",3.28,.105,.80)
    if closed:
        sign("lease","sign_for_lease",2.82,.108,1.10)
        slab("Vacant left board over lower glass",shell,-4.44,-1.34,.208,.27,.66,1.23,floor)

    # Second floor window bays split the brick, leaving clear panes and true recesses.
    for y,back in ((.151,False),(27.86,True)):
        for a,b in ((-5,-4.05),(-2.96,-.59),(.59,2.96),(4.05,5)):
            slab("Upper masonry wall between openings",shell,a,b,y-.133,y+.133,4.77,7.74,brick)
        for a,b in ((-4.05,-2.96),(-.59,.59),(2.96,4.05)):
            slab("Upper wall sill block",shell,a,b,y-.133,y+.133,4.77,5.06,brick)
            slab("Upper lintel infill",shell,a,b,y-.133,y+.133,6.79,7.75,brick)
            window("Upper sash window",a,b,y,5.06,6.79,back,rails=False)
            direction=1 if back else -1
            yy=y+direction*.18
            slab("Projecting hood",shell,a-.13,b+.13,yy-.09,yy+.09,6.78,6.92,stone)
            slab("Upper stone sill",shell,a-.11,b+.11,yy-.095,yy+.095,4.99,5.105,stone)
            if not far:
                z=5.98
                slab("Sash dividing rail",shell,a+.018,b-.018,y-.046,y+.046,z,z+.058,iron)
    for a,b in ((-5,2.57),(3.99,5)):
        wall("Receiving brick wall",shell,(a,b,27.70,28,.12,3.78),brick)
    slab("Receiving door lintel",shell,2.57,3.99,27.70,28,2.47,3.80,stone)
    slab("Receiving upper rear wall",shell,-5,5,27.70,28,3.78,4.79,brick)
    for x in (2.55,4.01):
        slab("Back door steel casing",shell,x-.047,x+.047,27.77,28.057,.12,2.47,iron)
    sign("receiving","sign_receiving",.05,28.012,2.85,True)
    slab("Receiving protective foundation",shell,-4.69,2.50,28.002,28.12,.12,.50,block)
    for x in (-4.70,4.76):
        slab("Rear drain pipe",shell,x-.058,x+.058,28.007,28.208,.14,7.15,iron)
        if near:
            for z in (.9,3.44,5.6):
                slab("Drain bracket",shell,x-.096,x+.096,28.0,28.21,z,z+.09,stone)

    # Roof, ceiling and detailing.
    slab("Shared second floor deck",shell,-4.72,4.72,.25,27.70,4.15,4.36,floor)
    slab("Continuous roof deck",top,-4.95,4.95,.08,27.94,7.59,7.75,roof)
    for a,b,c,d in ((-5,-4.75,.0,28),(4.75,5,.0,28),(-4.75,4.75,.0,.28),(-4.75,4.75,27.72,28)):
        slab("Brick roof parapet",top,a,b,c,d,7.60,8.14,brick)
        slab("Parapet stone coping",top,a-.024,b+.024,c-.028,d+.028,8.12,8.24,stone)
    slab("Front projecting cornice",top,-5.01,5.01,-.285,.30,7.68,7.87,stone)
    slab("Top facade stripe",top,-4.96,4.96,-.13,.02,7.50,7.63,stone)
    slab("Limestone stringcourse",shell,-4.98,4.98,-.100,.23,4.77,4.90,stone)
    if not far:
        for i in range(18 if near else 8):
            x=-4.69+i*9.38/(17 if near else 7)
            slab("Cornice brick dentil",top,x-.093,x+.093,-.18,.014,7.43,7.54,brick)
        for a,b in ((-4.88,-4.50),(4.50,4.88)):
            for z in (4.95,6.95):
                slab("Front pier quoin stone",shell,a,b,-.036,.073,z,z+.154,stone)
        slab("Rear roof scuttle",top,-3.64,-2.37,22.89,24.18,7.75,8.05,iron)
        slab("Rear scuttle cap",top,-3.67,-2.34,22.84,24.22,8.04,8.14,stone)
    slab("Older chimney",top,2.86,3.55,20.60,21.44,7.70,8.98,brick)
    slab("Chimney coping",top,2.79,3.63,20.52,21.52,8.96,9.06,stone)

    if not far and state in ("1970","2000"):
        slab("Dropped acoustic ceiling",shop,-4.71,4.71,1.07,27.67,3.19,3.29,floor)
    if not closed and not far:
        for a,b,c,d,label,mat in (
            (-3.4,-2.2,4.4,16.9,"West sales counter",red if old else iron),
            (.57,1.61,5.1,18.3,"East sales counter",red if old else iron),
            (-4.52,-3.52,18.72,25.8,"Fixed soda counter" if old else "Rear service counter",red if old else iron),
        ):
            wall(label,shop,(a,b,c,d,.12,.98),mat)
            slab("Solid counter work top",shop,a-.065,b+.065,c-.067,d+.067,.98,1.074,stone if not old else floor)
            if near:
                slab("Recessed kick plate",shop,a-.025,b+.025,c-.012,d+.012,.121,.286,iron)
                for y in range(int(c)+1,int(d),2):
                    slab("Counter front raised stile",shop,b+.003,b+.042,y-.046,y+.046,.36,.95,mat)
        if old:
            sign("candy","sign_soda_candy",-2.03,26.69,2.6)
            slab("Soda counter backboard",shop,-4.62,-.1,26.72,27.16,2.53,3.53,red)
    if near:
        for a,b in ((-4.43,-1.38),(1.38,4.43)):
            if not closed:
                slab("Fixed display platform",shop,a,b,.36,1.29,.122,.719,red)
                slab("Display platform timber lip",shop,a-.015,b+.015,.34,1.32,.718,.766,floor)
                for x in (a+.14,b-.14):
                    slab("Display backboard upright",shop,x-.032,x+.032,1.22,1.32,.78,1.8,iron)
                slab("Display backboard rail",shop,a+.10,b-.10,1.21,1.3,1.15,1.23,floor)
        for x in (-4.06,-3.14,-2.22,-1.30,-.38,.54,1.46,2.38,3.30,4.22):
            slab("Set-in upper brick row trim",shell,x-.015,x+.015,-.027,.011,7.0,7.24,brick)
        for i in range(7):
            yy=3.8+i*3.05
            for x in (-2.79,2.79):
                if old:
                    slab("Suspended pendant fixture stem",shop,x-.043,x+.043,yy-.042,yy+.042,3.57,4.16,iron)
                    slab("Square milk glass pendant",shop,x-.197,x+.197,yy-.182,yy+.182,3.43,3.61,stone if closed else glow)
                elif not closed:
                    slab("Fluorescent strip fixture",shop,x-.095,x+.095,yy-.71,yy+.71,3.11,3.205,glow)
                    slab("Ceiling seam band",shop,-4.70,4.70,yy-.021,yy+.021,3.167,3.195,stone)
        # A pair of receiving bollards and a refuse box are removable lot dressing.
        for x in (2.26,4.38):
            slab("Loading bollard",lot,x-.095,x+.095,28.64,28.82,.12,.98,iron)
            collide("Loading bollard",(x-.095,x+.095,28.64,28.82,.12,.98),"lot")
        slab("Rear refuse box",lot,-3.93,-2.71,28.15,28.89,.03,1.18,iron)
        slab("Rear refuse box lid",lot,-3.99,-2.64,28.11,28.93,1.15,1.27,stone)
        collide("Rear refuse box",(-3.99,-2.64,28.11,28.93,.03,1.27),"lot")
    if not near:
        # Keep an explicit optional lot group even when the LOD has no individual bins.
        slab("Rear path extension",lot,-4.80,-1.80,28.02,28.87,.02,.05,paving)
    for group_obj in (shell,top,shop,lot):
        batch(group_obj)
    bpy.context.view_layer.update()
    draw=[ob for ob in bpy.context.scene.objects if ob.type=="MESH" and ob.parent!=collision]
    for ob in draw:
        ob.data.calc_loop_triangles()
    tris=sum(len(ob.data.loop_triangles) for ob in draw)
    name=f"marlow-{state}"+("" if near else f"-{lod}")
    export_glb(ROOT/f"{name}.glb")
    if near:
        save_source(ROOT/f"{name}.blend")
    return {"file":f"{name}.glb","level":lod,"triangles_visible":tris,"anchors":doors,
            "sign_name":painted,"collision_boxes_gltf_m":boxes,
            "materials":sorted({m.name for ob in draw for m in ob.data.materials})}

out=[]
for key,(title,start,end) in STATES.items():
    versions=[build(key,lod) for lod in ("near","mid","far")]
    out.append({"id":key,"name":title,"from":start,"to":end,"models":versions})
manifest={"task":"C001","name":"Marlow Five and Dime","footprint_m":[10,28],
          "height_m":9.06,"axes":"glTF metres. +Y up, +Z to the street. The front centre is the shared grade origin.",
          "swatches":["brick_red","plank_horizontal","roof_tar_brown","pavement","block_courses"],
          "emission":"Glass UVs use their pane-mask textures. The closed condition has a black mask.",
          "lod_preview_distances_m":{"mid":45,"far":120},
          "states":out}
(ROOT/"model-info.json").write_text(json.dumps(manifest,indent=2)+"\n")
for state in out:
    print(state["id"],[(m["level"],m["triangles_visible"]) for m in state["models"]])
