"""Render the exported model with separate, desktop-only lighting and scale figures."""
from pathlib import Path

import bpy
from mathutils import Vector as V

ROOT = Path(__file__).resolve().parent
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT/"union-arch.glb"))
scene = bpy.context.scene
scene.render.engine = "CYCLES"
scene.cycles.samples = 18
scene.cycles.use_denoising = False
scene.cycles.max_bounces = 3
scene.render.resolution_x = 1050
scene.render.resolution_y = 770
scene.render.resolution_percentage = 100
scene.view_settings.view_transform = "AgX"

stage = bpy.data.objects.new("Desktop review", None)
scene.collection.objects.link(stage)
world = bpy.data.worlds.new("Sky")
world.use_nodes = True
scene.world = world
bg = world.node_tree.nodes.get("Background")


def at(position):
    return V(position) + V((0,18,0))


bpy.ops.mesh.primitive_plane_add(size=1600, location=at((0,0,-.02)))
m = bpy.data.materials.new("Review ground")
bpy.context.object.data.materials.append(m)
exec(compile((ROOT/"scene.py").read_text(), "scene.py", "exec"))

bpy.ops.object.light_add(type="SUN", location=at((-26,-35,32)))
sun = bpy.context.object
sun.rotation_euler = V((.46,.67,-.66)).to_track_quat("-Z","Y").to_euler()
sun.data.angle = .12


def area(name, position, target, power, color, size):
    data = bpy.data.lights.new(name, "AREA")
    ob = bpy.data.objects.new(name, data)
    scene.collection.objects.link(ob)
    ob.location = at(position)
    ob.rotation_euler = (at(target)-ob.location).to_track_quat("-Z","Y").to_euler()
    data.color = color
    data.size = size
    return data, power


lamps = []
for x in (-11.8,-6.15,6.15,11.8):
    for face in (-1,1):
        lamps.append(area("Sconce glow", (x,face*3.99,3), (x,face*2.5,5), 220, (1,.65,.31), .40))
for x in (-7,0,7):
    lamps.append(area("Warm architectural wash", (x,-9,1.2), (x,-2,12), 1800, (1,.77,.48), 3))
    lamps.append(area("Cool reflected light", (x,11,2), (x,0,8), 1000, (.47,.66,1), 4))

bpy.ops.object.camera_add()
cam = bpy.context.object
cam.data.clip_end = 950
scene.camera = cam
p = bpy.data.materials["glow_lantern"].node_tree.nodes.get("Principled BSDF")
views = [
    ("day", (29,-52,22), (0,0,8), 44, False),
    ("night", (-26,-48,16), (0,0,8), 43, True),
    ("walking", (7,-24,1.7), (.2,0,9), 24, False),
    ("rear", (-29,42,23), (0,0,8), 42, False),
    ("above", (28,-32,56), (0,0,3), 42, False),
    ("passage", (-3.4,-9,1.7), (.6,1,4.4), 23, True)
]
(ROOT/"renders").mkdir(exist_ok=True)
for name, position, target, lens, night in views:
    cam.location = at(position)
    cam.rotation_euler = (at(target)-cam.location).to_track_quat("-Z","Y").to_euler()
    cam.data.lens = lens
    bg.inputs[0].default_value = (.25,.34,.53,1) if night else (.73,.81,.89,1)
    bg.inputs[1].default_value = .41 if night else .61
    sun.data.energy = .23 if night else 2.40
    sun.data.color = (.67,.76,1) if night else (1,.91,.77)
    for light, power in lamps:
        light.energy = power if night else 0
    sky_colors(night)
    p.inputs["Emission Strength"].default_value = 2.4 if night else .25
    scene.render.filepath = str(ROOT/"renders"/f"{name}.png")
    bpy.ops.render.render(write_still=True)
