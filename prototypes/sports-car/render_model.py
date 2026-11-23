"""Render the exported GLB, with articulated doors and a 1.75 m scale figure."""
import json
import os
from pathlib import Path
from math import radians, sin, cos, pi
from random import Random

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
slug=os.environ["CAR_MODEL"]
meta=next(v for v in json.loads((ROOT/"model-info.json").read_text())["vehicles"] if v["key"]==slug)
asset=meta["lods"][0]

def bl(p):
    x,y,z=p
    return Vector((x,-z,y))

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT/asset["file"]))
scene=bpy.context.scene
scene.render.engine="CYCLES"
scene.cycles.samples=14
scene.cycles.use_denoising=False
scene.cycles.max_bounces=4
scene.render.resolution_x=770
scene.render.resolution_y=540
scene.view_settings.view_transform="AgX"
scene.view_settings.look="AgX - Medium High Contrast"

scene.world=bpy.data.worlds.new("Roadside air")
scene.world.use_nodes=True
nodes=scene.world.node_tree.nodes
links=scene.world.node_tree.links
sky=nodes["Background"]
back=nodes.new("ShaderNodeBackground")
ray=nodes.new("ShaderNodeLightPath")
mix=nodes.new("ShaderNodeMixShader")
links.new(ray.outputs["Is Camera Ray"],mix.inputs[0])
links.new(sky.outputs[0],mix.inputs[1])
links.new(back.outputs[0],mix.inputs[2])
links.new(mix.outputs[0],nodes["World Output"].inputs[0])

def area(name,kind,at,target,size=None):
    light=bpy.data.lights.new(name,kind)
    obj=bpy.data.objects.new(name,light)
    scene.collection.objects.link(obj)
    obj.location=bl(at)
    obj.rotation_euler=(bl(target)-obj.location).to_track_quat("-Z","Y").to_euler()
    if size is not None:
        light.size=size
    return light


key=area("Afternoon sun","SUN",(4.5,8,5),(0,0,0))
key.angle=.20
rim=area("Preview rim","AREA",(-4,4,-4),(0,.6,0),5)
fill=area("Open-door bounce","AREA",(2.8,2.3,-1.2),(.34,.89,.13),2.3)
beam=area("Preview headlight spill","AREA",(0,.53,2.8),(0,0,6),1)
beam.color=(1,.83,.53)

def preview_mat(name,color):
    m=bpy.data.materials.new(name)
    m.diffuse_color=(*color,1)
    return m


def cube(name,point,size,mat,parent=None):
    bpy.ops.mesh.primitive_cube_add(size=1,location=bl(point))
    obj=bpy.context.object
    obj.name=name
    obj.scale=size[0],size[2],size[1]
    obj.data.materials.append(mat)
    if parent:
        obj.parent=parent
    return obj

ground=preview_mat("Muted roadside pavement",(.174,.191,.181))
studio_ground=cube("Preview ground",(0,-.038,0),(340,.07,340),ground)
# The road is preview scenery. Its mesh and props never enter a car GLB.
road_objects=[]
grass=preview_mat("Olive mountain verge",(.23,.278,.124))
asphalt=preview_mat("Blue-charcoal asphalt",(.121,.144,.143))
white=preview_mat("Road edge stone paint",(.68,.66,.48))
gold=preview_mat("Tarnished gold paint",(.71,.47,.16))
stone=preview_mat("Weathered rock",(.31,.302,.25))
bark=preview_mat("Fir bark",(.228,.157,.112))
leaf=preview_mat("Fir crown",(.118,.208,.133))
rail=preview_mat("Galvanized railing",(.44,.48,.44))


def roadway_x(z):
    return -1.7+8.6*sin(z*.085)**3


def ribbon(name,lo,hi,offset,w,y,mat):
    points=[]
    count=max(2,round((hi-lo)*1.5))
    for i in range(count+1):
        z=lo+(hi-lo)*i/count
        x=roadway_x(z)+offset
        points.extend((bl((x-w/2,y,z)),bl((x+w/2,y,z))))
    faces=[(2*i,2*i+1,2*i+3,2*i+2) for i in range(count)]
    data=bpy.data.meshes.new(name)
    data.from_pydata(points,[],faces)
    obj=bpy.data.objects.new(name,data)
    scene.collection.objects.link(obj)
    data.materials.append(mat)
    road_objects.append(obj)
    return obj


road_objects.append(cube("Grass ground",(0,-.047,0),(136,.084,136),grass))
ribbon("Shoulders",-54,63,0,7.90,-.003,stone)
ribbon("Twisty road",-54,63,0,6.80,.000,asphalt)
for shift in (-3.20,3.20):
    ribbon("Edge line",-54,63,shift,.095,.004,white)
for z in range(-52,60,4):
    ribbon("Centre dash",z,z+2.45,0,.105,.0042,gold)
ribbon("Outside guard rail",2,36,3.77,.10,.58,rail)
for z in range(2,36,3):
    road_objects.append(cube("Barrier post",(roadway_x(z)+3.77,.29,z),(.11,.58,.14),rail))


def cone(name,p,height,radius,sides,mat,upper=0):
    bpy.ops.mesh.primitive_cone_add(vertices=sides,radius1=radius,radius2=upper,depth=height,location=bl(p))
    obj=bpy.context.object
    obj.name=name
    obj.data.materials.append(mat)
    road_objects.append(obj)


rng=Random(1701)
for side in (-1,1):
    for i in range(15):
        z=rng.uniform(8,54)
        x=roadway_x(z)+side*rng.uniform(5.0,16)
        h=rng.uniform(2.0,4.4)
        cone("Fir trunk",(x,.53,z),1.06,.109,6,bark)
        for f in (.36,.55,.72):
            cone("Stepped fir crown",(x,h*f,z),h*.40,h*.31*(1.2-f),7,leaf)
    for j in range(7):
        z=7+j*3.8
        x=roadway_x(z)+side*rng.uniform(5.8,8.1)
        h=rng.uniform(.24,.85)
        cone("Faceted roadside rock",(x,h*.29,z),h*.58,h*.93,6,stone,h*.49)

# A human-height reference lives solely in the preview scene.
dummy=bpy.data.objects.new("Render reference 1.75 m",None)
scene.collection.objects.link(dummy)
body=preview_mat("Olive work shirt",(.279,.31,.219))
pants=preview_mat("Faded brown trousers",(.19,.146,.114))
skin=preview_mat("Skin",(.59,.35,.231))
boot=preview_mat("Brown boots",(.085,.064,.046))
hair=preview_mat("Dark brown hair",(.12,.087,.065))
for name,x,y,z,sx,sy,sz,mat in (
    ("torso",0,1.165,0,.397,.375,.24,body),("pelvis",0,.914,0,.315,.17,.235,pants),
    ("neck",0,1.382,0,.105,.116,.104,skin),("head",0,1.558,.018,.186,.253,.196,skin),
    ("nose",0,1.545,.128,.047,.057,.034,skin),("hair",0,1.716,.010,.202,.068,.205,hair),
    ("left leg",.091,.501,0,.121,.736,.153,pants),("right leg",-.087,.502,0,.124,.738,.153,pants),
    ("left shoe",.092,.068,.057,.134,.136,.255,boot),("right shoe",-.088,.069,.049,.131,.138,.254,boot),
    ("left sleeve",.258,1.168,0,.118,.279,.155,body),("right sleeve",-.254,1.169,0,.117,.30,.155,body),
    ("left hand",.271,.937,0,.092,.22,.11,skin),("right hand",-.267,.927,0,.091,.201,.115,skin),
):
    cube("Scale "+name,(x,y,z),(sx,sy,sz),mat,dummy)
dummy.location=bl((1.81,0,-.17))
dummy.rotation_euler.z=.44

bpy.ops.object.camera_add()
camera=bpy.context.object
camera.data.clip_end=180
scene.camera=camera

lamp_material=bpy.data.materials["glow_lamps"]
dark_lamps=lamp_material.copy()
dark_lamps.name="Preview idle signals"
dark_lamps.node_tree.nodes["Principled BSDF"].inputs["Emission Strength"].default_value=0
for name,node in asset["lamps"].items():
    if node["role"] in ("brake","turn","reverse"):
        for child in bpy.data.objects[name].children:
            if child.type=="MESH":
                for i,m in enumerate(child.data.materials):
                    if m.name=="glow_lamps":
                        child.data.materials[i]=dark_lamps

views={
    "front":((4.45,2.76,5.09),(0,.54,.02),5.57),
    "rear":((4.26,2.49,-5.00),(0,.58,-.06),5.60),
    "side":((8.2,2.05,.00),(0,.62,0),5.12),
    "top":((0,8.0,0),(0,.32,0),6.43),
    "night":((4.62,2.34,5.23),(0,.50,.42),5.90),
    "open":((4.42,2.88,4.69),(0,.66,.02),6.07),
    "interior":((1.26,1.16,-.34),(.29,.75,.23),0),
    "scale":((4.28,2.56,4.57),(.27,.78,.01),6.01),
    "cargo":((4.90,2.58,-5.37),(0,.62,-.12),5.99),
    "hero":((3.64,.70,4.99),(-.04,.52,.27),0),
    "road":((3.88,2.55,-6.3),(.36,.63,3.1),0),
}
for name in (*asset["door_preview_degrees"],*asset["cargo_preview_degrees"]):
    bpy.data.objects[name].rotation_mode="XYZ"
only=os.environ.get("CAR_VIEWS","").split(",")
folder=ROOT/"renders"
folder.mkdir(exist_ok=True)
for tag,(position,target,width) in views.items():
    if only!=[""] and tag not in only:
        continue
    night=tag=="night"
    road=tag=="road"
    studio_ground.hide_render=road
    for ob in road_objects:
        ob.hide_render=not road
    key.energy=.25 if night else 2.40
    key.color=(.54,.70,1) if night else (1,.93,.80)
    sky.inputs[0].default_value=(.27,.42,.72,1) if night else (.76,.85,.94,1)
    sky.inputs[1].default_value=.57 if night else .87
    back.inputs[0].default_value=(.071,.118,.178,1) if night else (.40,.55,.61,1)
    rim.energy=250 if night else 400
    rim.color=(.46,.64,1) if night else (.79,.92,1)
    fill.energy=135 if tag=="interior" else 74 if tag=="open" else 0
    beam.energy=155 if night else 0
    lamp_material.node_tree.nodes["Principled BSDF"].inputs["Emission Strength"].default_value=2.5 if night else .02
    for name,angle in asset["door_preview_degrees"].items():
        shown=tag=="open" or tag=="interior" and name=="door_l"
        bpy.data.objects[name].rotation_euler.z=radians(angle) if shown else 0
    for name,angle in asset["cargo_preview_degrees"].items():
        bpy.data.objects[name].rotation_euler.x=radians(angle) if tag=="cargo" else 0
    roof=bpy.data.objects["roof_panel"]
    home=meta["roof_panel"]["mount_gltf_m"]
    roof.location=bl((home[0]+1.48,.055,-1.48) if tag=="open" else home)
    for child in dummy.children:
        child.hide_render=tag!="scale"
    camera.location=bl(position)
    camera.rotation_euler=(0,0,0) if tag=="top" else (bl(target)-camera.location).to_track_quat("-Z","Y").to_euler()
    camera.data.type="ORTHO" if width else "PERSP"
    camera.data.ortho_scale=width if width else 6
    camera.data.lens={"hero":41,"road":25,"interior":18}.get(tag,24)
    scene.cycles.samples=13 if tag in ("interior","night","road") else 9
    scene.render.filepath=str(folder/f"{slug}-{tag}.png")
    bpy.ops.render.render(write_still=True)
