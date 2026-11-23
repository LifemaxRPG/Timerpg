"""Build Mercer Barbers and Lilac Beauty in six states with three LODs."""
import json
from math import cos, pi, sin
from pathlib import Path
import sys

import bpy

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from sculpture_tools import beam, box, group, material, mesh, oval, plaque
from revision_common import batch, emitter, export_glb, save_source

T=.0388
REGIONS=json.loads((ROOT/"textures/sign-regions.json").read_text())
ERAS={"1940":[1940,1979],"1980":[1980,2009],"2010":[2010,None]}
Y_BACK=18
Y_ENTRANCE=.52
Z_FLOOR=.10
SHOP_TITLES={
    "barber-1940":"Mercer Barber","barber-1980":"Mercer Hair Co.","barber-2010":"Mercer Barber Co.",
    "salon-1940":"Lilac Beauty","salon-1980":"Lilac Styling","salon-2010":"Lilac Nail Room",
}


def block(parent,label,bounds,mat):
    a,b,c,d,e,f=bounds
    return box(label,parent,((a+b)/2,(c+d)/2,(e+f)/2),(b-a,d-c,f-e),mat)


def sloping_canopy(parent,label,width,depth,z,mat):
    points=[(-width/2,-depth,z-.45),(width/2,-depth,z-.45),(width/2,0,z),(-width/2,0,z)]
    verts=points+[(x,y,h-.125) for x,y,h in points]
    return mesh(label,parent,verts,[(0,1,2,3),(7,6,5,4),(0,4,5,1),(1,5,6,2),(2,6,7,3),(3,7,4,0)],mat)


def build(key,lod):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    family,year=key.split("-")
    barber=family=="barber"
    old=year=="1940"
    middle=year=="1980"
    modern=year=="2010"
    near,far=lod=="near",lod=="far"
    root=group(SHOP_TITLES[key])
    shell,inside,lot,col=[group(n,root) for n in ("shell","fixed_interior","lot","collision")]
    brick=material("wall_brick","brick")
    face=material("detail_painted_front","front-"+key)
    floor=material("floor_shop","wood" if barber and not middle else "tile" if middle or old else "terrazzo")
    metal=material("trim_painted_steel","matte_steel")
    roof=material("roof_membrane","roof")
    signmat=material("sign_name_panel","signs")
    paving=material("ground_paving","paving")
    glass=material("glass_storefront","glass")
    gl=glass.node_tree
    gl.links.new(next(n for n in gl.nodes if n.type=="TEX_IMAGE").outputs["Alpha"],
                 gl.nodes.get("Principled BSDF").inputs["Alpha"])
    emitter(glass,"pane-mask",.095)
    mirror=material("detail_mirror","mirror")
    seat=material("detail_upholstery","seat-"+key)
    stripe=material("detail_pole_stripes","pole") if barber else None
    hidden=bpy.data.materials.new("collision_invisible")
    hidden.use_nodes=True
    hidden.surface_render_method="BLENDED"
    hidden.node_tree.nodes["Principled BSDF"].inputs["Alpha"].default_value=0
    doors,boxes,signs={},[],{}

    def collider(label,bounds,attachment=""):
        a,b,c,d,e,f=bounds
        obj=block(col,f"collider_{len(boxes):02}",bounds,paving)
        obj.data.materials.clear()
        obj.data.materials.append(hidden)
        obj["collision_only"]=True
        boxes.append({"name":label,"center_gltf_m":[(a+b)/2,(e+f)/2,-(c+d)/2],
                      "size_gltf_m":[b-a,f-e,d-c],"group":"lot" if attachment else "building","attachment":attachment})

    def wall(label,bounds,mat):
        block(shell,label,bounds,mat)
        collider(label,bounds)

    def door(name,x,y,yaw):
        node=group(name,root)
        node.location=(x,y,Z_FLOOR)
        node.rotation_euler.z=yaw
        doors[name]=[x,Z_FLOOR,-y]

    def plate(label,region,x,y,z,reverse=False):
        rect=REGIONS[region]
        w,h=rect[2]*T,rect[3]*T
        a,b=(x+w/2,x-w/2) if reverse else (x-w/2,x+w/2)
        ob=plaque(label,shell,[(a,y,z),(b,y,z),(b,y,z+h),(a,y,z+h)],signmat,rect,(256,512))
        ob["keep"]=True
        signs[label]={"texture":"signs.png","atlas_size_texels":[256,512],"rect_texels":rect,
                      "bottom_left_gltf_m":[a,z,-y],"width_m":w,"height_m":h}

    def glazing(label,a,b,y,z0,z1,back=False):
        verts=[(a,y,z0),(b,y,z0),(b,y,z1),(a,y,z1)]
        mesh(label,shell,verts,[(3,2,1,0) if back else (0,1,2,3)],glass)
        collider(label,(a,b,y-.055,y+.055,z0,z1))
        if not far:
            for x in (a,b,(a+b)/2) if not modern else (a,b):
                block(shell,"Window glazing rail",(x-.031,x+.031,y-.041,y+.043,z0,z1),metal)
            for z in (z0,z1):
                block(shell,"Window glazing rail",(a,b,y-.04,y+.04,z-.036,z+.036),metal)

    # Both stores use a 6 × 18 m, no-side-window party-wall shell.
    block(lot,"Front public sidewalk",(-3,3,-2.3,.04,0,.072),paving)
    block(lot,"Rear service alley pad",(-3,3,18,19.8,0,.066),paving)
    block(shell,"Open entry threshold",(-.90,.90,-.26,.72,.049,Z_FLOOR),paving)
    block(inside,"Continuous shop floor",(-3,3,.72,18,.019,Z_FLOOR),floor)
    for a,b in ((-3,-2.76),(2.76,3)):
        wall("Brick party wall",(a,b,0,Y_BACK,Z_FLOOR,4.51),brick)
    for a,b in ((-3,-2.76),(-.90,-.72),(.72,.90),(2.76,3)):
        wall("Front masonry pier",(a,b,.001,.244,Z_FLOOR,3.16),face)
    z_lite=.62 if modern else .86
    for a,b in ((-2.76,-.90),(.90,2.76)):
        wall("Display base",(a,b,.022,.252,Z_FLOOR,z_lite),face)
        block(shell,"Display sill",(a,b,-.015,.274,z_lite-.065,z_lite),metal)
        glazing("Front display pane",a,b,.11,z_lite,3.12)
    for a,b in ((-.90,-.72),(.72,.90)):
        wall("Recessed entrance return",(a,b,.24,.81,Z_FLOOR,3.12),face)
    block(shell,"Door head below transom",(-.72,.72,.47,.78,2.48,2.60),metal)
    block(shell,"Door head above transom",(-.72,.72,.47,.78,3.05,3.12),metal)
    glazing("Entry transom",-.68,.68,.481,2.60,3.05)
    door("door_main",0,Y_ENTRANCE-.12,0)
    block(shell,"Brick street parapet",(-3,3,.03,.29,3.1,4.58),brick)
    block(shell,"Repaintable facade panel backing",(-2.86,2.86,-.157,.038,3.105,4.543),face)
    plate("sign_name",key,0,-.169,3.121)
    if middle:
        sloping_canopy(shell,"Aluminium refacing canopy",5.95,1.02,3.11,face)
        block(shell,"Dark front canopy edge",(-2.975,2.975,-1.032,-.952,2.558,2.673),metal)
    elif modern:
        block(shell,"Modern folded lintel shade",(-2.98,2.98,-.63,.035,3.05,3.167),metal)
    else:
        block(shell,"Painted upper cornice",(-3.025,3.025,-.25,.34,4.48,4.65),metal)
        block(shell,"Historic trim belt",(-2.79,2.79,-.021,.053,3.085,3.18),metal)
    # Back wall, real service aperture, vent and meter casing visible from the alley.
    for a,b in ((-2.76,.78),(2.12,2.76)):
        wall("Rear brick return",(a,b,17.74,18,Z_FLOOR,4.44),brick)
    block(shell,"Rear door lintel",(.78,2.12,17.74,18,2.54,4.51),brick)
    for x in (.78,2.12):
        block(shell,"Back steel jamb",(x-.041,x+.041,17.72,18.077,Z_FLOOR,2.54),metal)
    block(shell,"Service landing",(.65,2.26,17.99,18.73,.066,Z_FLOOR),paving)
    door("door_back",1.45,18.39,pi)
    plate("sign_staff","rear",-.85,18.016,2.55,True)
    block(shell,"Downspout",(-2.50,-2.37,17.98,18.13,.14,4.41),metal)
    block(shell,"Service meter",(-2.22,-1.80,17.98,18.17,1.20,1.68),metal)
    for x in (-2.49,-2.38):
        if near:
            for z in (1.4,2.61,3.87):
                block(shell,"Downspout strap",(x-.05,x+.05,17.973,18.136,z,z+.058),face)
    block(lot,"Rear waste box",(-2.10,-1.34,18.18,18.92,.066,.95),face)
    collider("Rear waste box",(-2.10,-1.34,18.18,18.92,.066,.95),"lot")
    # Low built-up membrane with coping, scuttle, vent, and a matte HVAC box.
    block(shell,"Roof deck",(-2.91,2.91,.22,17.92,4.19,4.35),roof)
    for a,b,c,d in ((-3,-2.77,0,18),(2.77,3,0,18),(-2.77,2.77,0,.24),(-2.77,2.77,17.76,18)):
        block(shell,"Parapet shell",(a,b,c,d,4.32,4.64),brick)
        block(shell,"Parapet cap",(a-.024,b+.024,c-.024,d+.024,4.62,4.73),metal)
    block(shell,"Rooftop access scuttle",(-2.23,-1.16,13.23,14.36,4.35,4.61),metal)
    block(shell,"Raised vent housing",(1.32,1.92,12.2,12.79,4.35,4.99),metal)
    if not far:
        block(shell,"Rooftop vent cap",(1.27,1.98,12.14,12.85,4.97,5.06),paving)
    if modern:
        block(shell,"Boxed rooftop air unit",(-.91,.74,10.38,11.74,4.35,4.92),metal)
        for y in (10.58,10.87,11.15,11.43):
            block(shell,"Vent louver",(-.78,.60,y,y+.11,4.917,4.95),face)
    block(inside,"Shop ceiling",(-2.76,2.76,.79,17.74,3.41,3.56),paving if not old else floor)
    # Both front and back rooms remain connected by a real gap.
    for a,b in ((-2.76,-.63),(.96,2.76)):
        wall("Staff room partition",(a,b,14.85,15.02,Z_FLOOR,3.41),face)
    block(inside,"Staff counter",(-2.61,-1.93,15.62,17.42,Z_FLOOR,.99),face)
    collider("Staff counter",(-2.61,-1.93,15.62,17.42,Z_FLOOR,.99))
    if near:
        for y in (3.35,6.8,10.28,13.72,16.46):
            block(inside,"Flush ceiling fixture",(-.58,.58,y-.22,y+.22,3.34,3.42),paving)

    def base(x,y,label):
        n=12 if near else 8 if not far else 6
        oval(label+" base",inside,[(x,y,z,r,r) for z,r in ((Z_FLOOR,.34),(.18,.34),(.21,.21),(.59,.13))],metal,n)

    if barber:
        # A physically scaled striped drum on its own +Y-up glTF pivot. The bracket is static.
        px,py,pz=-2.78,-.34,2.16
        sides=12
        chord=16*2*T/sides
        radius=chord/(2*sin(pi/sides))
        pole_height=16*2*T
        vertices=[(radius*cos(2*pi*i/sides),radius*sin(2*pi*i/sides),z) for z in
                  (-pole_height/2,pole_height/2) for i in range(sides)]
        polygons=[(i,(i+1)%sides,(i+1)%sides+sides,i+sides) for i in range(sides)]
        drum=mesh("barber_pole",root,vertices,polygons,stripe)
        for p in drum.data.polygons:
            for idx,uv in zip(p.loop_indices,((p.index/sides,0),((p.index+1)/sides,0),
                                ((p.index+1)/sides,.5),(p.index/sides,.5))):
                drum.data.uv_layers.active.data[idx].uv=uv
        drum.location=(px,py,pz)
        drum["animation_axis"]="local +Y in glTF"
        bracket=(px-.16,px+.16,-.35,.07)
        block(shell,"Pole socket upper",(*bracket,pz+.60,pz+.74),metal)
        block(shell,"Pole socket lower",(*bracket,pz-.74,pz-.60),metal)
        for z in (pz+pole_height/2,pz-pole_height/2):
            oval("Barber pole metal collar",shell,[(px,py,z-h,r,r) for h,r in
                 ((.045,.225),(-.045,.225))],metal,12)
        collider("Barber pole mount",(px-.23,px+.23,py-.23,py+.23,Z_FLOOR,pz+.74))
        for y in (4.65,7.94,11.22):
            # Three bolted styling chairs, toe rests towards the mirror wall.
            x=1.18
            base(x,y,"Chair")
            block(inside,"Barber saddle",(x-.37,x+.36,y-.36,y+.36,.60,.80),seat)
            block(inside,"Barber back cushion",(x-.42,x-.25,y-.36,y+.36,.73,1.39),seat)
            if not far:
                block(inside,"Barber foot plate",(x+.31,x+.67,y-.26,y+.26,.30,.40),metal)
                for yy in (y-.35,y+.35):
                    block(inside,"Chair arm",(x-.21,x+.26,yy-.07,yy+.07,.89,.99),metal)
            collider("Barber chair",(x-.43,x+.67,y-.42,y+.42,Z_FLOOR,1.39))
            block(inside,"Fixed mirrored workstation",(2.22,2.76,y-.72,y+.72,Z_FLOOR,1.00),face)
            collider("Mirrored workstation",(2.22,2.76,y-.72,y+.72,Z_FLOOR,1.00))
            w,h=32*T,48*T
            plaque("Barber framed mirror",inside,[(2.735,y+w/2,1.18),(2.735,y-w/2,1.18),
                   (2.735,y-w/2,1.18+h),(2.735,y+w/2,1.18+h)],mirror,(0,0,32,48),(32,48))
    elif not modern:
        # Three fixed hood-dryer positions, with a separate plenum and open hoods.
        for y in (4.90,8.28,11.67):
            x=-1.69
            base(x,y,"Dryer chair")
            block(inside,"Dryer chair cushion",(x-.34,x+.34,y-.36,y+.36,.61,.80),seat)
            block(inside,"Dryer chair back",(x-.36,x-.21,y-.36,y+.36,.76,1.29),seat)
            beam("Dryer mast",inside,(x-.53,y,.13),(x-.53,y,1.84),(.065,.065),(.065,.065),metal,8)
            beam("Dryer arm",inside,(x-.54,y,1.84),(x,y,1.84),(.063,.063),(.063,.063),metal,4)
            oval("Rounded hood dryer",inside,[(x,y,z,r,r) for z,r in
                 ((1.43,.31),(1.54,.40),(1.77,.36),(1.94,.22))],face,12 if near else 8)
            block(inside,"Recessed hood gasket",(x-.285,x+.285,y-.28,y+.28,1.417,1.459),metal)
            collider("Hood dryer station",(x-.61,x+.40,y-.42,y+.42,Z_FLOOR,1.94))
        block(inside,"Fixed salon product counter",(2.17,2.76,4.31,12.84,Z_FLOOR,1.03),face)
        collider("Salon product counter",(2.17,2.76,4.31,12.84,Z_FLOOR,1.03))
    else:
        for y in (4.5,7.57,10.64):
            # Service bars fixed to the east wall, with one upholstered guest seat per station.
            block(inside,"Manicure counter",(1.04,2.76,y-.54,y+.54,.82,1.00),floor)
            block(inside,"Manicure counter end support",(1.07,1.24,y-.51,y+.51,Z_FLOOR,.84),face)
            collider("Manicure counter",(1.04,2.76,y-.54,y+.54,Z_FLOOR,1.00))
            x=.09
            base(x,y,"Guest chair")
            block(inside,"Guest chair cushion",(x-.33,x+.33,y-.33,y+.33,.59,.73),seat)
            block(inside,"Guest chair back",(x-.36,x-.23,y-.33,y+.33,.70,1.21),seat)
            collider("Guest chair",(x-.36,x+.34,y-.35,y+.35,Z_FLOOR,1.21))
        block(inside,"Fixed wall polish ledge",(2.55,2.73,4.0,11.20,1.33,1.45),face)
    # One street-facing detail panel fits each display bay without intersecting its mullions.
    if modern:
        plate("sign_shop_window","barber_window" if barber else "salon_window",1.83,.059,.94)
    for parent in (shell,inside,lot):
        batch(parent)
    name=key+("" if near else f"-{lod}")
    visible=[o for o in bpy.data.objects if o.type=="MESH" and not o.get("collision_only")]
    triangles=sum(len(p.vertices)-2 for o in visible for p in o.data.polygons)
    export_glb(ROOT/f"{name}.glb")
    if near:
        save_source(ROOT/f"{name}.blend")
    return {"file":f"{name}.glb","lod":lod,"triangles_visible":triangles,"door_nodes_gltf_m":doors,
            "collision_boxes_gltf_m":boxes,"sign_panels":signs,
            "barber_pole":None if not barber else {"node":"barber_pole","axis_local_gltf":[0,1,0],
                                                  "pivot_gltf_m":[px,pz,-py],"suggested_period_s":3.5},
            "materials":sorted({m.name for ob in visible for m in ob.data.materials})}


meta={"task":"C005","footprint_m":[6,18],"height_m":5.06,
      "units":"Metres, +Y up, street-facing +Z. Shared grade origin at midpoint of street wall.",
      "swatches":["brick_red","plank_horizontal","pavement","roof_membrane_grey","roof_membrane_tan"],
      "lot_bounds_gltf_m":{"x":[-3.025,3.025],"y":[0,5.06],"z":[-19.8,2.3]},
      "lod_distances_suggested_m":{"mid":52,"far":116},"states":[]}
for state,title in SHOP_TITLES.items():
    models=[build(state,lod) for lod in ("near","mid","far")]
    print(state,[(m["lod"],m["triangles_visible"]) for m in models])
    meta["states"].append({"id":state,"title":title,"era":ERAS[state.split("-")[1]],"models":models})
(ROOT/"model-info.json").write_text(json.dumps(meta,indent=2)+"\n")
