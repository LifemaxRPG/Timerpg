"""Render the exported model with separate, desktop-only lighting and scale figures."""
import os
from pathlib import Path

import bpy
from mathutils import Vector as V

ROOT = Path(__file__).resolve().parent
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT/"meridian-memorial.glb"))
scene = bpy.context.scene
scene.render.engine = "CYCLES"
scene.cycles.samples = 18
scene.cycles.use_denoising = False
scene.cycles.max_bounces = 3
scene.render.resolution_x = 1200
scene.render.resolution_y = 900
scene.render.resolution_percentage = 100
scene.view_settings.view_transform = "AgX"

stage = bpy.data.objects.new("Desktop review", None)
scene.collection.objects.link(stage)
world = bpy.data.worlds.new("Sky")
world.use_nodes = True
scene.world = world
bg = world.node_tree.nodes.get("Background")


def at(position):
    return V(position) + V((0,118,0))


bpy.ops.mesh.primitive_plane_add(size=3800, location=at((0,0,-.02)))
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
for side in (-1,1):
    x = side*42
    for y in (-1,1):
        lamps.append(area("Tower floodlight", (x,y*34,4), (x,y*4,108), 64000, (1,.77,.51), 12))
        lamps.append(area("Crown floodlight", (x,y*27,140), (x,0,210), 34000, (.68,.76,1), 7))
        lamps.append(area("Meridian ring wash", (side*16,y*13,122), (side*13,0,164), 31000, (1,.68,.40), 6))
        lamps.append(area("Memorial floor wash", (side*22,y*27,6), (0,0,1), 6900, (1,.76,.50), 6))
        for dy in (38,56,74,92):
            lamps.append(area("Walkway lantern", (side*30,y*dy,4.7), (side*29,y*dy,0), 420, (1,.64,.35), .42))

bpy.ops.object.camera_add()
cam = bpy.context.object
cam.data.clip_end = 2500
scene.camera = cam
p = bpy.data.materials["glow_amber"].node_tree.nodes.get("Principled BSDF")
views = [
    ("day", (158,-497,153), (0,0,109), 45, False),
    ("night", (-150,-470,132), (0,0,108), 42, True),
    ("walking", (14,-111,1.7), (0,0,101), 20, False),
    ("rear", (-223,389,185), (0,5,108), 39, False),
    ("above", (198,-253,460), (0,0,98), 43, False),
    ("court", (-7,-15,3.25), (3,2,2.55), 23, True)
]
(ROOT/"renders").mkdir(exist_ok=True)
for name, position, target, lens, night in views:
    if os.environ.get("MERIDIAN_VIEW") not in (None, name):
        continue
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

# Existing Timerpg assets are staged at native metre scale in a separate context view.
reference_root = os.environ.get("MERIDIAN_REFERENCE_ROOT")
if reference_root:
    imports = [
        ("gazette-office","gazette-office",(-117,-79,0),-90),
        ("public-library","public-library-1912",(111,-70,0),90),
        ("small-town-bank","small-town-bank-1920s",(111,-12,0),90),
        ("riverside-grand-hotel","riverside-grand-hotel",(-117,38,0),-90),
        ("gazette-office","gazette-office",(111,55,0),90),
        ("small-town-bank","small-town-bank-1920s",(-117,-28,0),-90),
    ]
    from math import radians
    for folder, filename, position, rotation in imports:
        src = Path(reference_root)/f"timerpg-{folder}"/"prototypes"/folder/f"{filename}.glb"
        previous = set(bpy.data.objects)
        bpy.ops.import_scene.gltf(filepath=str(src))
        for ob in set(bpy.data.objects)-previous:
            if ob.name.startswith("collision") or ob.get("collision_only"):
                ob.hide_render = True
            if ob.parent is None:
                ob.rotation_euler.z += radians(rotation)
                ob.location += at(position)
    bg.inputs[0].default_value = (.73,.81,.89,1)
    bg.inputs[1].default_value = .61
    sun.data.energy = 2.4
    sun.data.color = (1,.91,.77)
    for lamp, _power in lamps:
        lamp.energy = 0
    p.inputs["Emission Strength"].default_value = .25
    sky_colors(False)
    cam.location = at((-155,-543,137))
    cam.rotation_euler = (at((0,0,101))-cam.location).to_track_quat("-Z","Y").to_euler()
    cam.data.lens = 44
    scene.render.filepath = str(ROOT/"renders"/"city.png")
    bpy.ops.render.render(write_still=True)
