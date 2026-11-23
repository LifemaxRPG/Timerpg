"""Render the actual near exports from park overview and player-height cameras."""
import json
import os
from pathlib import Path

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
year=os.environ.get("THIMBLE_YEAR","1962")
closed=year=="2006"
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT/f"thimble-trails-{year}.glb"))
for obj in bpy.data.objects:
    if obj.get("collision_only"):
        obj.hide_render=True
scene=bpy.context.scene
scene.render.engine="CYCLES"
scene.cycles.samples=9
scene.cycles.use_denoising=False
scene.cycles.max_bounces=3
scene.render.resolution_x=820
scene.render.resolution_y=620
scene.view_settings.view_transform="AgX"
scene.view_settings.look="AgX - Medium High Contrast"
scene.world=bpy.data.worlds.new("Roadside preview")
scene.world.use_nodes=True
nodes=scene.world.node_tree.nodes
links=scene.world.node_tree.links
sky=nodes.get("Background")
back=nodes.new("ShaderNodeBackground")
camera_ray=nodes.new("ShaderNodeLightPath")
mix=nodes.new("ShaderNodeMixShader")
links.new(camera_ray.outputs["Is Camera Ray"],mix.inputs[0])
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

sun=lamp("Preview sunlight","SUN",(0,0,25),(12,21,0))
sun.angle=.16
counter=lamp("Preview counter fill","AREA",(-.75,4.26,2.85),(-1.12,3.5,.43),2.1)
counter.color=(1,.87,.68)
awning=lamp("Preview entry wash","AREA",(4.4,-3.3,4.44),(0,0,2.05),3.1)
awning.color=(1,.79,.51)
asset=json.loads((ROOT/"model-info.json").read_text())
asset=next(state["models"][0] for state in asset["states"] if state["id"]==year)
greens=[]
tints={"rose":(1,.42,.30),"mint":(.48,1,.58),"amber":(1,.71,.28),"white":(.66,.88,1)}
for bulb in asset["lights"]:
    x,z,forward=bulb["position_gltf_m"]
    if "target_gltf_m" in bulb:
        tx,tz,tf=bulb["target_gltf_m"]
        light=lamp(bulb["node"],"AREA",(x,-forward,z),(tx,-tf,tz),.44)
    else:
        light=lamp(bulb["node"],"POINT",(x,-forward,z),(x,-forward,0))
        light.shadow_soft_size=.22
    light.color=tints[bulb["colour"]]
    greens.append((light,bulb["preview_watts"]))

def ground(name,xy,size,colour):
    bpy.ops.mesh.primitive_plane_add(size=1,location=xy)
    ob=bpy.context.object
    ob.name=name
    ob.scale=(*size,1)
    material=bpy.data.materials.new(name)
    material.diffuse_color=(*colour,1)
    ob.data.materials.append(material)

ground("Preview bank",(0,13,-.02),(210,180),(.194,.227,.149))
ground("Preview road",(0,-12.93,-.012),(180,7.9),(.116,.132,.124))
bpy.ops.object.camera_add()
camera=bpy.context.object
camera.data.clip_end=420
scene.camera=camera
views={
    "day":((41,-39,51),(0,26,1.6),90),
    "night":((45,-35,51),(0,26.3,1.1),89),
    "rear":((-54,88,50),(0,25.7,1.45),88),
    "above":((0,26,110),(0,26,0),99),
    "eye":((-8.85,-23.4,1.72),(-7.45,.62,2.23),0),
    "course":((8.6,19.4,1.84),(15.7,33.7,3.07),0),
    "wetland":((-18.3,43.0,3.06),(-7.2,47.4,.73),0),
    "mill":((-22.8,11.2,2.02),(-14.1,16.6,2.82),0),
    "interior":((.42,.86,1.72),(-1.04,4.9,1.46),0),
}
only=os.environ.get("THIMBLE_VIEWS","").split(",")
(ROOT/"renders").mkdir(exist_ok=True)
for label,(at,target,width) in views.items():
    if only!=[""] and label not in only:
        continue
    night=label=="night"
    sun.energy=.18 if night else 2.58
    sun.color=(.58,.71,1) if night else (1,.91,.74)
    sky.inputs[0].default_value=(.25,.39,.66,1) if night else (.72,.82,.96,1)
    sky.inputs[1].default_value=.49 if night else .85
    back.inputs[0].default_value=(.057,.098,.153,1) if night else (.397,.554,.637,1)
    back.inputs[1].default_value=.85
    counter.energy=(105 if closed else 465) if label=="interior" else 190 if night and not closed else 0
    awning.energy=390 if night and not closed else 0
    for light,watts in greens:
        light.energy=watts if night and not closed else 0
    for name,strength in (("glass_ticket_hut",.45),("sign_faces",2.0)):
        mat=bpy.data.materials[name]
        mat.node_tree.nodes["Principled BSDF"].inputs["Emission Strength"].default_value=strength if night else .018
    camera.location=at
    camera.rotation_euler=(0,0,0) if label=="above" else (Vector(target)-camera.location).to_track_quat("-Z","Y").to_euler()
    camera.data.type="ORTHO" if width else "PERSP"
    camera.data.ortho_scale=width or 1
    camera.data.lens=21 if label in ("interior","course","wetland","mill") else 26
    scene.cycles.samples=14 if label in ("interior","night") else 8
    scene.render.filepath=str(ROOT/"renders"/f"{year}-{label}.png")
    bpy.ops.render.render(write_still=True)
