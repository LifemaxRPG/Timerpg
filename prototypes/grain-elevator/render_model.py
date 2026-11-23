"""Render six views of one exported near GLB using Blender Cycles."""
import os
from pathlib import Path

import bpy
from mathutils import Vector

ROOT = Path(__file__).resolve().parent
state = os.environ.get("LARK_STATE", "1950")
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT/f"lark-bend-{state}.glb"))
for obj in bpy.data.objects:
    if obj.get("collision_only"):
        obj.hide_render = True

scene = bpy.context.scene
scene.render.engine = "CYCLES"
scene.cycles.samples = 9
scene.cycles.use_denoising = False
scene.cycles.max_bounces = 3
scene.render.resolution_x = 860
scene.render.resolution_y = 635
scene.view_settings.view_transform = "AgX"
scene.view_settings.look = "AgX - Medium High Contrast"
scene.world = bpy.data.worlds.new("Open prairie sky")
scene.world.use_nodes = True
nodes, links = scene.world.node_tree.nodes, scene.world.node_tree.links
sky = nodes.get("Background")
screen = nodes.new("ShaderNodeBackground")
path = nodes.new("ShaderNodeLightPath")
mix = nodes.new("ShaderNodeMixShader")
links.new(path.outputs["Is Camera Ray"], mix.inputs[0])
links.new(sky.outputs[0], mix.inputs[1])
links.new(screen.outputs[0], mix.inputs[2])
links.new(mix.outputs[0], nodes.get("World Output").inputs[0])

def lights(name, kind, xyz, toward):
    data = bpy.data.lights.new(name, kind)
    obj = bpy.data.objects.new(name, data)
    scene.collection.objects.link(obj)
    obj.location = xyz
    obj.rotation_euler = (Vector(toward)-obj.location).to_track_quat("-Z", "Y").to_euler()
    return data

sun = lights("Low open-country sun", "SUN", (0,0,20), (15,22,0))
sun.angle = .11
office = lights("Preview office fill", "AREA", (0,4.4,3.17), (-.4,3.1,.1))
office.size = 2.2
office.color = (1,.8,.59)
walk = lights("Preview yard light", "AREA", (3.4,-2.2,6), (2,3,0))
walk.size = 1.8
walk.color = (1,.74,.43)

bpy.ops.mesh.primitive_plane_add(size=460, location=(10,12,-.015))
ground = bpy.context.object
ground.name = "Preview terrain only"
m = bpy.data.materials.new("Preview prairie ground")
m.diffuse_color = (.24,.263,.18,1)
ground.data.materials.append(m)

bpy.ops.object.camera_add()
camera = bpy.context.object
scene.camera = camera
camera.data.clip_end = 620
old = state == "1925"
views = {
    "day": (((35,-53,32),(-6,12,15.2),59) if old else ((53,-78,56),(8.2,14.2,19),83)),
    "night": (((27,-51,29),(-6,12,15.2),59) if old else ((43,-76,54),(8.2,14.5,19),83)),
    "rear": (((19,48,25),(-8,15,15.2),57) if old else ((62,76,44),(9,16,19),83)),
    "above": (((2,15,82),(-1,11,8.7),56) if old else ((22,17,110),(10,12,13),83)),
    "paint": (((-16,-29,18.5),(-16,11,18.5),12) if old else ((21,-37,24.6),(21,12.0,24.6),20)),
    "eye": ((7.3,-11.9,1.72),(-1.0,5.7,3.7),0),
    "interior": ((2.96,1.10,1.72),(-.68,5.41,1.74),0),
}
only = os.environ.get("LARK_VIEWS", "").split(",")
(ROOT/"renders").mkdir(exist_ok=True)
for label, (eye, target, width) in views.items():
    if only != [""] and label not in only:
        continue
    night = label == "night"
    sun.energy = .25 if night else 2.42
    sun.color = (.65,.75,1) if night else (1,.915,.755)
    sky.inputs[0].default_value = (.26,.40,.74,1) if night else (.73,.82,.95,1)
    sky.inputs[1].default_value = .47 if night else .85
    screen.inputs[0].default_value = (.077,.115,.168,1) if night else (.42,.59,.70,1)
    screen.inputs[1].default_value = .82
    office.energy = 200 if label == "interior" else 75 if night else 0
    walk.energy = 450 if night else 0
    panes = bpy.data.materials.get("glass_panes")
    panes.node_tree.nodes.get("Principled BSDF").inputs["Emission Strength"].default_value = 1.15 if night else .06
    camera.location = eye
    if label == "above":
        camera.location = (target[0],target[1],eye[2])
        camera.rotation_euler = (0,0,0)
    else:
        camera.rotation_euler = (Vector(target)-camera.location).to_track_quat("-Z","Y").to_euler()
    camera.data.type = "ORTHO" if width else "PERSP"
    camera.data.ortho_scale = width or 1
    camera.data.lens = 22 if label == "interior" else 28
    scene.render.filepath = str(ROOT/"renders"/f"{state}-{label}.png")
    bpy.ops.render.render(write_still=True)
