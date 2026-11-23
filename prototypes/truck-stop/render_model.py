"""Render day-to-night views of the exported truck-stop models in Blender Cycles."""
import json
import os
from pathlib import Path

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
year=os.environ.get("RELAY_YEAR","1960")
modern=year=="1990"
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT/f"cedar-relay-{year}.glb"))
for obj in bpy.data.objects:
    if obj.get("collision_only"):
        obj.hide_render=True

scene=bpy.context.scene
scene.render.engine="CYCLES"
scene.cycles.samples=9
scene.cycles.use_denoising=False
scene.cycles.max_bounces=3
scene.render.resolution_x=940
scene.render.resolution_y=660
scene.view_settings.view_transform="AgX"
scene.view_settings.look="AgX - Medium High Contrast"
scene.world=bpy.data.worlds.new("Highway sky")
scene.world.use_nodes=True
nodes=scene.world.node_tree.nodes
links=scene.world.node_tree.links
sky=nodes.get("Background")
back=nodes.new("ShaderNodeBackground")
ray=nodes.new("ShaderNodeLightPath")
mix=nodes.new("ShaderNodeMixShader")
links.new(ray.outputs["Is Camera Ray"],mix.inputs[0])
links.new(sky.outputs[0],mix.inputs[1])
links.new(back.outputs[0],mix.inputs[2])
links.new(mix.outputs[0],nodes.get("World Output").inputs[0])

def lamp(name,kind,at,target,size=0):
    data=bpy.data.lights.new(name,kind)
    obj=bpy.data.objects.new(name,data)
    scene.collection.objects.link(obj)
    obj.location=at
    obj.rotation_euler=(Vector(target)-obj.location).to_track_quat("-Z","Y").to_euler()
    if size:
        data.size=size
    return data

sun=lamp("Preview sunlight","SUN",(0,0,45),(23,29,0))
sun.angle=.11
front=lamp("Preview frontage wash","AREA",(4.8,-8.3,7.14),(0,0,2.1),6.4)
front.color=(.85,.95,1) if modern else (1,.77,.49)
manifest=json.loads((ROOT/"model-info.json").read_text())
asset=next(state["models"][0] for state in manifest["states"] if state["id"]==year)
yard=[]
for source in asset["lights"]:
    x,z,inverted_y=source["position_gltf_m"]
    y=-inverted_y
    is_yard=source["role"]=="yard"
    target_x=x*.68 if is_yard else x
    light=lamp(source["node"] or "Preview canopy downlight","AREA",(x,y,z),
               (target_x,y,.04),3.8 if is_yard else 2.3)
    light.color=(.84,.94,1) if modern else (1,.81,.54)
    yard.append((light,9400 if is_yard else 900))
house=[]
for x,y in ((-17.2,3.14),(-13,5.2),(-15,9.8),(-8,10.5),(0,3.6),(6,5.6),(13,7.6),(16,15.6)):
    light=lamp("Preview diner room bounce","AREA",(x,y,3.31),(x,y+.2,.66),2.6)
    light.color=(1,.85,.64) if not modern else (.93,.98,1)
    house.append(light)

bay_lamp=lamp("Preview service bay fill","AREA",(1.05,34.7,4.92),(1.05,36.9,.33),3.4)
bay_lamp.color=(.88,.96,1) if modern else (1,.83,.65)

def ground(name,xy,size,colour):
    bpy.ops.mesh.primitive_plane_add(size=1,location=xy)
    obj=bpy.context.object
    obj.name=name
    obj.scale=(*size,1)
    m=bpy.data.materials.new(name)
    m.diffuse_color=(*colour,1)
    obj.data.materials.append(m)

ground("Preview roadside terrain",(0,46,-.028),(515,510),(.202,.235,.156))
ground("Preview highway",(0,-37.20,-.013),(470,18),(.100,.115,.106))
bpy.ops.object.camera_add()
camera=bpy.context.object
camera.data.clip_end=650
scene.camera=camera
views={
    "day":((90,-130,96),(-6,27.5,3.2),148),
    "night":((77,-128,86),(-5,27.5,4.15),142),
    "rear":((-116,203,111),(1,47,3.7),193),
    "above":((0,44,200),(0,44,0),224),
    "eye":((10,-27.2,1.72),(-3,0,3.13),0),
    "diesel":((-43,-.9,1.78),(-43,36,3.38),0),
    "pole":((43.1,-38.2,1.72),(43.45,-19.5,10.7),0),
    "service":((5.6,22.2,4.10),(0.7,36.0,1.55),0),
    "yard_night":((20,105,3.2),(-19,58,4.25),0),
    "interior":((-11.1,2.39,1.72),(-17.31,7.62,1.25),0),
}
only=os.environ.get("RELAY_VIEWS","").split(",")
(ROOT/"renders").mkdir(exist_ok=True)
for label,(at,target,width) in views.items():
    if only!=[""] and label not in only:
        continue
    night=label in ("night","yard_night")
    sun.energy=.205 if night else 2.7
    sun.color=(.58,.73,1) if night else (1,.915,.76)
    sky.inputs[0].default_value=(.25,.39,.65,1) if night else (.73,.83,.97,1)
    sky.inputs[1].default_value=.5 if night else .84
    back.inputs[0].default_value=(.062,.105,.163,1) if night else (.405,.568,.644,1)
    back.inputs[1].default_value=.84
    front.energy=1250 if night else 0
    bay_lamp.energy=700 if label=="service" else 0
    for light,energy in yard:
        light.energy=energy if night else 0
    for light in house:
        light.energy=340 if label=="interior" else 245 if night else 63 if label=="eye" else 0
    for name,strength in (("glass_storefront",.37),("sign_faces",1.6)):
        mat=bpy.data.materials[name]
        mat.node_tree.nodes["Principled BSDF"].inputs["Emission Strength"].default_value=strength if night else .025
    camera.location=at
    camera.rotation_euler=(0,0,0) if label=="above" else (Vector(target)-camera.location).to_track_quat("-Z","Y").to_euler()
    camera.data.type="ORTHO" if width else "PERSP"
    camera.data.ortho_scale=width or 1
    camera.data.lens=22 if label=="interior" else 24
    scene.cycles.samples=20 if night else 15 if label=="interior" else 9
    scene.render.filepath=str(ROOT/"renders"/f"{year}-{label}.png")
    bpy.ops.render.render(write_still=True)
