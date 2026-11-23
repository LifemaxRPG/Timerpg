"""Render the exported GLB, with articulated doors and a 1.75 m scale figure."""
import json
import os
from pathlib import Path
from math import radians

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
scene.render.resolution_x=850
scene.render.resolution_y=600
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

ground=preview_mat("Muted roadside pavement",(.211,.229,.203))
cube("Preview ground",(0,-.038,0),(110,.07,110),ground)
line=preview_mat("Worn ochre paint",(.35,.25,.12))
for z in (-20,-12,-4,4,12,20):
    cube("Preview road stripe",(-2.3,.0005,z),(.086,.002,3.3),line)

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
    "front":((5.06,3.13,5.32),(0,.77,.10),6.55),
    "rear":((5.05,2.73,-5.73),(0,.78,-.10),6.55),
    "side":((7.7,2.47,.02),(0,.93,0),6.02),
    "top":((0,9.4,0),(0,.35,0),8.38),
    "night":((5.15,2.75,5.6),(0,.65,.65),6.55),
    "open":((4.9,2.86,4.71),(0,.84,.04),6.7),
    "interior":((1.73,1.41,-.30),(.22,1.015,.20),0),
    "scale":((5.36,2.62,4.91),(.25,.91,.06),6.65),
    "cargo":((3.85,3.61,-5.36),(0,.83,-1.15),6.18),
}
if slug=="stoneweir-2015":
    views["rear_night"]=((4.6,2.52,-6.26),(0,.82,-.19),6.24)
for name in (*asset["door_preview_degrees"],*asset["cargo_preview_degrees"]):
    bpy.data.objects[name].rotation_mode="XYZ"
only=os.environ.get("CAR_VIEWS","").split(",")
folder=ROOT/"renders"
folder.mkdir(exist_ok=True)
for tag,(position,target,width) in views.items():
    if only!=[""] and tag not in only:
        continue
    night=tag in ("night","rear_night")
    key.energy=.25 if night else 2.40
    key.color=(.54,.70,1) if night else (1,.93,.80)
    sky.inputs[0].default_value=(.27,.42,.72,1) if night else (.76,.85,.94,1)
    sky.inputs[1].default_value=.57 if night else .87
    back.inputs[0].default_value=(.071,.118,.178,1) if night else (.40,.55,.61,1)
    rim.energy=250 if night else 400
    rim.color=(.46,.64,1) if night else (.79,.92,1)
    fill.energy=110 if tag=="interior" else 65 if tag=="open" else 0
    beam.energy=155 if night else 0
    lamp_material.node_tree.nodes["Principled BSDF"].inputs["Emission Strength"].default_value=2.5 if night else .02
    for name,angle in asset["door_preview_degrees"].items():
        shown=tag=="open" and name.endswith("_l") or tag=="interior" and name=="door_front_l"
        bpy.data.objects[name].rotation_euler.z=radians(angle) if shown else 0
    for name,angle in asset["cargo_preview_degrees"].items():
        bpy.data.objects[name].rotation_euler.x=radians(angle) if tag=="cargo" else 0
    for child in dummy.children:
        child.hide_render=tag!="scale"
    camera.location=bl(position)
    camera.rotation_euler=(0,0,0) if tag=="top" else (bl(target)-camera.location).to_track_quat("-Z","Y").to_euler()
    camera.data.type="ORTHO" if width else "PERSP"
    camera.data.ortho_scale=width if width else 6
    camera.data.lens=20
    scene.cycles.samples=12 if tag in ("interior","night","rear_night") else 8
    scene.render.filepath=str(folder/f"{slug}-{tag}.png")
    bpy.ops.render.render(write_still=True)
