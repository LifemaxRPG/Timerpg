"""Render seven Blender Cycles review views from one exported near GLB."""
import os
from pathlib import Path

import bpy
from mathutils import Vector

ROOT = Path(__file__).resolve().parent
year = os.environ.get("ALDER_YEAR","1960")
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT/f"alder-bend-{year}.glb"))
for ob in bpy.data.objects:
    if ob.get("collision_only"):
        ob.hide_render = True
scene = bpy.context.scene
scene.render.engine = "CYCLES"
scene.cycles.samples = 8
scene.cycles.use_denoising = False
scene.cycles.max_bounces = 3
scene.render.resolution_x = 800
scene.render.resolution_y = 620
scene.view_settings.view_transform = "AgX"
scene.view_settings.look = "AgX - Medium High Contrast"

scene.world = bpy.data.worlds.new("Open sky")
scene.world.use_nodes=True
nodes = scene.world.node_tree.nodes
links = scene.world.node_tree.links
sky = nodes.get("Background")
backdrop = nodes.new("ShaderNodeBackground")
switch = nodes.new("ShaderNodeLightPath")
mix = nodes.new("ShaderNodeMixShader")
links.new(switch.outputs["Is Camera Ray"],mix.inputs[0])
links.new(sky.outputs[0],mix.inputs[1])
links.new(backdrop.outputs[0],mix.inputs[2])
links.new(mix.outputs[0],nodes.get("World Output").inputs[0])

def lamp(name,kind,place,target):
    data = bpy.data.lights.new(name,kind)
    ob = bpy.data.objects.new(name,data)
    scene.collection.objects.link(ob)
    ob.location = place
    ob.rotation_euler = (Vector(target)-ob.location).to_track_quat("-Z","Y").to_euler()
    return data

sun = lamp("Preview sun","SUN",(0,0,40),(17,24,0))
sun.angle = .145
walk = lamp("Preview gate wash","AREA",(0,-.38,3.10),(0,3,.1))
walk.size = 3.5
walk.color = (1,.77,.53)
room = lamp("Preview concession wash","AREA",(12.4,5.84,2.86),(11.3,4.89,.88))
room.size = 2.0
room.color = (1,.84,.61)
floods = []
if year!="1935":
    for x,y in ((-43,50),(43,50),(-62,110),(62,110)):
        source = (x*.95,y+(2.1 if y<80 else -2.1),17.2)
        data = lamp("Preview flood wash","SPOT",source,(x*.26,65 if y<80 else 105,0))
        data.spot_size = 1.85
        data.spot_blend = .86
        data.color = (1,.88,.70) if year!="2000" else (.84,.92,1)
        floods.append(data)

bpy.ops.mesh.primitive_plane_add(size=480,location=(0,68,-.021))
ground = bpy.context.object
ground.name="Render ground only"
mat = bpy.data.materials.new("Muted park surroundings")
mat.diffuse_color=(.196,.242,.142,1)
ground.data.materials.append(mat)

bpy.ops.object.camera_add()
camera=bpy.context.object
camera.data.clip_end=650
scene.camera=camera
views={
    "day":((-101,-81,123),(0,68,1.5),197),
    "night":((79,-111,120),(0,67,0),196),
    "rear":((106,203,94),(0,72,1),194),
    "above":((0,67,220),(0,67,0),211),
    "eye":((-5.2,-12.45,1.72),(0,3.9,2.89),0),
    "field":((12.2,37.8,1.73),(0,16,2.72),0),
    "interior":((14.75,7.55,1.71),(9.48,2.65,1.68),0),
}
only=os.environ.get("ALDER_VIEWS","").split(",")
(ROOT/"renders").mkdir(exist_ok=True)
for label,(at,target,width) in views.items():
    if only!=[""] and label not in only:
        continue
    night=label=="night"
    sun.energy=.22 if night else 2.54
    sun.color=(.58,.72,1) if night else (1,.91,.75)
    sky.inputs[0].default_value=(.22,.39,.70,1) if night else (.73,.81,.95,1)
    sky.inputs[1].default_value=.53 if night else .85
    backdrop.inputs[0].default_value=(.057,.092,.159,1) if night else (.405,.562,.64,1)
    backdrop.inputs[1].default_value=.83
    for light in floods:
        light.energy=165000 if night else 0
    walk.energy=400 if night else 0
    room.energy=240 if label=="interior" else 95 if night else 0
    for material_name in ("glass_booth","glow_floodlights"):
        material=bpy.data.materials.get(material_name)
        if material:
            bsdf=material.node_tree.nodes.get("Principled BSDF")
            bsdf.inputs["Emission Strength"].default_value=(3.0 if material_name=="glow_floodlights" else 1.15) if night else .06
    camera.location=at
    camera.rotation_euler=(0,0,0) if label=="above" else (Vector(target)-camera.location).to_track_quat("-Z","Y").to_euler()
    camera.data.type="ORTHO" if width else "PERSP"
    camera.data.ortho_scale=width or 1
    camera.data.lens=20 if label in ("field","interior") else 25
    scene.render.filepath=str(ROOT/"renders"/f"{year}-{label}.png")
    bpy.ops.render.render(write_still=True)
