"""Render six review views from one of the exported near GLBs."""
import os
from pathlib import Path

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
state=os.environ.get("SHOP_STATE","barber-1940")
barber=state.startswith("barber")
modern=state.endswith("2010")
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT/f"{state}.glb"))
for ob in bpy.data.objects:
    if ob.get("collision_only"):
        ob.hide_render=True

scene=bpy.context.scene
scene.render.engine="CYCLES"
scene.cycles.samples=8
scene.cycles.use_denoising=False
scene.cycles.max_bounces=3
scene.render.resolution_x=760
scene.render.resolution_y=560
scene.view_settings.view_transform="AgX"
scene.view_settings.look="AgX - Medium High Contrast"
scene.world=bpy.data.worlds.new("Main Street")
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

def lamp(name,type,at,target,size=0):
    data=bpy.data.lights.new(name,type)
    ob=bpy.data.objects.new(name,data)
    scene.collection.objects.link(ob)
    ob.location=at
    ob.rotation_euler=(Vector(target)-ob.location).to_track_quat("-Z","Y").to_euler()
    if size:
        data.size=size
    return data

sun=lamp("Render sun","SUN",(0,0,19),(11,18,0))
sun.angle=.115
front=lamp("Render street lamp","AREA",(-2.9,-4.7,4.68),(0,0,2.18),3)
front.color=(1,.80,.57)
fills=[]
for y in (2.6,5.7,9.4,12.6):
    light=lamp("Render interior bounce","AREA",(0,y,3.19),(0,y+.1,.56),2.1)
    light.color=(1,.87,.69) if not modern else (1,.94,.83)
    fills.append(light)

def ground(name,xy,size,colour):
    bpy.ops.mesh.primitive_plane_add(size=1,location=xy)
    ob=bpy.context.object
    ob.name=name
    ob.scale=(*size,1)
    m=bpy.data.materials.new(name)
    m.diffuse_color=(*colour,1)
    ob.data.materials.append(m)
ground("Review ground",(0,6,-.019),(120,110),(.243,.231,.192))
ground("Review street",(0,-6.4,-.011),(112,8.12),(.148,.169,.161))
ground("Review rear alley",(0,21.8,-.012),(110,4.2),(.186,.180,.156))
bpy.ops.object.camera_add()
camera=bpy.context.object
camera.data.clip_end=260
scene.camera=camera
views={
    "day":((14,-19,14.7),(0,6.2,2.06),24.4),
    "night":((11,-20,13.5),(0,6.1,2.17),23.0),
    "rear":((12,34,12.5),(0,12.8,2.14),23.7),
    "above":((0,8.9,42),(0,8.9,0),29.8),
    "eye":((-.31,-8.52,1.70),(0,.05,2.63),0),
    "interior":(((-1.90,2.06,1.72),(1.31,9.12,1.35),0) if barber or modern else
                ((1.75,2.38,1.71),(-1.52,9.10,1.31),0)),
}
only=os.environ.get("SHOP_VIEWS","").split(",")
(ROOT/"renders").mkdir(exist_ok=True)
for label,(at,target,width) in views.items():
    if only!=[""] and label not in only:
        continue
    night=label=="night"
    sun.energy=.24 if night else 2.64
    sun.color=(.58,.73,1) if night else (1,.90,.76)
    sky.inputs[0].default_value=(.24,.40,.68,1) if night else (.69,.80,.94,1)
    sky.inputs[1].default_value=.58 if night else .89
    back.inputs[0].default_value=(.065,.108,.165,1) if night else (.408,.562,.625,1)
    back.inputs[1].default_value=.83
    front.energy=560 if night else 0
    for lamp_data in fills:
        lamp_data.energy=280 if label=="interior" else 155 if night else 75 if label=="eye" else 0
    glass=bpy.data.materials["glass_storefront"].node_tree.nodes.get("Principled BSDF")
    glass.inputs["Emission Strength"].default_value=.31 if night else .018
    camera.location=at
    camera.rotation_euler=(0,0,0) if label=="above" else (Vector(target)-camera.location).to_track_quat("-Z","Y").to_euler()
    camera.data.type="ORTHO" if width else "PERSP"
    camera.data.ortho_scale=width or 1
    camera.data.lens=21 if label=="interior" else 31
    scene.render.filepath=str(ROOT/"renders"/f"{state}-{label}.png")
    bpy.ops.render.render(write_still=True)
