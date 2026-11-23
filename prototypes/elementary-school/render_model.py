"""Capture desktop Cycles review views from the delivered near GLBs."""
import os
from pathlib import Path

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
state=os.environ.get("SCHOOL_STATE","westmeadow-1955")
rural=state.startswith("mill")
closed=state.endswith("abandoned")
modern=state.endswith("1990")
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT/f"{state}.glb"))
for ob in bpy.data.objects:
    if ob.get("collision_only"):
        ob.hide_render=True

scene=bpy.context.scene
scene.render.engine="CYCLES"
scene.cycles.samples=9
scene.cycles.use_denoising=False
scene.cycles.max_bounces=3
scene.render.resolution_x=790
scene.render.resolution_y=590
scene.view_settings.view_transform="AgX"
scene.view_settings.look="AgX - Medium High Contrast"
scene.world=bpy.data.worlds.new("Schoolyard air")
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

def lamp(name,type,at,target,size=0):
    data=bpy.data.lights.new(name,type)
    ob=bpy.data.objects.new(name,data)
    scene.collection.objects.link(ob)
    ob.location=at
    ob.rotation_euler=(Vector(target)-ob.location).to_track_quat("-Z","Y").to_euler()
    if size:
        data.size=size
    return data

sun=lamp("Preview sunlight","SUN",(0,0,25),(14,10,0))
sun.angle=.12
fill=lamp("Preview room bounce","AREA",(0,7.8,3.76) if rural else ((1,4.75,3.58) if modern else (21,29,5.25)),
          (1,10.7,1.01) if rural else ((0,1.02,.51) if modern else (20,36,1.1)),3.8 if rural else 5.0)
fill.color=(1,.83,.64)
entry=lamp("Preview porch wash","AREA",(2.1,-3.1,4.48),(0,0,1.75),3.0)
entry.color=(1,.76,.44)
vestibule=lamp("Preview vestibule ceiling wash","AREA",(0,1.65,3.52),(.2,2.6,.9),2.6)
vestibule.color=(.89,.94,1)

bpy.ops.mesh.primitive_plane_add(size=360,location=(0,7,-.02))
base=bpy.context.object
base.name="Preview surrounding terrain"
m=bpy.data.materials.new("Preview muted grass")
m.diffuse_color=(.20,.24,.15,1)
base.data.materials.append(m)
bpy.ops.object.camera_add()
camera=bpy.context.object
camera.data.clip_end=520
scene.camera=camera
if rural:
    views={
        "day":((20,-23,16),(0,8.3,3.16),27),
        "night":((-17,-21,14),(0,7.8,3.07),27),
        "rear":((-17,32,14),(0,10.2,3.2),28),
        "above":((0,9.5,47),(0,9.5,0),42),
        "eye":((1.83,-8.8,1.71),(0,0,3.21),0),
        "yard":((-11.75,15.7,1.78),(-4,17.7,2.1),0),
        "interior":((0,4.25,1.71),(0,11.93,1.62),0),
    }
else:
    views={
        "day":((60,-74,62),(9,8,2.25),100),
        "night":((46,-79,59),(8.3,8.2,2.55),98),
        "rear":((-65,83,55),(9,14,2.5),102),
        "above":((10.5,10,125),(10.5,10,0),103),
        "eye":((10,-14,1.71),(0,1.0,3.23),0),
        "yard":((48,9.45,1.76),(40,23.7,1.83),0),
        "interior":(((1.20,.67,1.71),(-.32,4.38,1.72),0) if modern else ((15.0,27.0,1.72),(20.9,38.7,2.53),0)),
    }
only=os.environ.get("SCHOOL_VIEWS","").split(",")
(ROOT/"renders").mkdir(exist_ok=True)
for label,(at,target,width) in views.items():
    if only!=[""] and label not in only:
        continue
    night=label=="night"
    sun.energy=.24 if night else 2.60
    sun.color=(.56,.72,1) if night else (1,.93,.78)
    sky.inputs[0].default_value=(.22,.39,.67,1) if night else (.73,.82,.95,1)
    sky.inputs[1].default_value=.52 if night else .82
    back.inputs[0].default_value=(.06,.105,.17,1) if night else (.397,.561,.64,1)
    back.inputs[1].default_value=.85
    fill.energy=(310 if rural else 910) if label=="interior" else 0
    entry.energy=460 if night and not closed else 0
    vestibule.energy=345 if modern and label=="interior" else 0
    panes=bpy.data.materials["glass_school"]
    panes.node_tree.nodes.get("Principled BSDF").inputs["Emission Strength"].default_value=.95 if night else .04
    camera.location=at
    camera.rotation_euler=(0,0,0) if label=="above" else (Vector(target)-camera.location).to_track_quat("-Z","Y").to_euler()
    camera.data.type="ORTHO" if width else "PERSP"
    camera.data.ortho_scale=width or 1
    camera.data.lens=22 if label in ("interior","yard") else 26
    scene.render.filepath=str(ROOT/"renders"/f"{state}-{label}.png")
    bpy.ops.render.render(write_still=True)
