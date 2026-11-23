"""Render the released near GLBs under desktop review lighting."""
import os
from pathlib import Path

import bpy
from mathutils import Vector

ROOT = Path(__file__).resolve().parent
ERA = os.environ.get("STATION_ERA", "1900")
ONLY = os.environ.get("STATION_VIEWS", "").split(",")
modern = ERA == "1980"
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT / f"briarford-{ERA}.glb"))
for ob in bpy.data.objects:
    if ob.get("collision_only"):
        ob.hide_render = True

scene = bpy.context.scene
scene.render.engine = "CYCLES"
scene.cycles.samples = 10
scene.cycles.use_denoising = False
scene.cycles.max_bounces = 4
scene.render.resolution_x = 740
scene.render.resolution_y = 532
scene.view_settings.view_transform = "AgX"
scene.view_settings.look = "AgX - Medium High Contrast"
scene.world = bpy.data.worlds.new("Briarford street light")
scene.world.use_nodes = True
sky = scene.world.node_tree.nodes["Background"]


def lamp(name, kind, at, aim, color, size=1):
    data = bpy.data.lights.new(name, kind)
    ob = bpy.data.objects.new(name, data)
    scene.collection.objects.link(ob)
    ob.location = at
    ob.rotation_euler = (Vector(aim) - ob.location).to_track_quat("-Z", "Y").to_euler()
    data.color = color
    if kind == "AREA":
        data.size = size
    return data


sun = lamp("Sun", "SUN", (-11,-14,21), (0,5,0), (1,.94,.82))
sun.angle = .13
entry_y = -2.86 if modern else -1.00 if ERA == "1940" else -.57
front = lamp("Porch light", "AREA", (0,entry_y-.8,3.34), (0,entry_y-.25,1.0), (1,.66,.30), 1.3)
front_fill = lamp("Lobby bounce", "AREA", (1,2.6,3.40), (0,5,1.1), (1,.85,.64), 2.1)
desk_fill = lamp("Desk light", "AREA", (-1.1,4.9,3.42), (0,3.5,.7), (1,.81,.55), 2.0)
booking_fill = lamp("Booking ceiling light", "AREA", (4.35,7.76,3.36), (5.0,9.5,1.0), (.89,.92,1), 1.4)
cell_fill = lamp("Cell ceiling light", "AREA", (-4.26,16.7,3.22), (-4.20,16.0,.6), (1,.81,.57), 1.5)
transfer = lamp("Transfer passage light", "AREA", (-3.7,12.11,3.38), (-4,14,.6), (.88,.95,1), 1.5)

bpy.ops.mesh.primitive_plane_add(size=160, location=(0,9,-.018))
terrain = bpy.context.object
ground = bpy.data.materials.new("Context paving")
ground.diffuse_color = (.23,.24,.19,1)
terrain.data.materials.append(ground)
bpy.ops.object.camera_add()
camera = bpy.context.object
scene.camera = camera
modern_target = -.85 if modern else 0
views = {
    "day": ((24,-27,21),(1,8,3.15),33.5),
    "night": ((-20,-23,14),(0,6.5,3),30.5),
    "rear": ((25,36,18),(1,12.5,3.7),34),
    "above": ((1.55,10.2,55),(1.55,10.2,0),41.5),
    "entrance": ((3.9,-9.45,1.67),(0,modern_target,3.12),0),
    "desk": ((.31,1.25,1.67),(-.13,4.8,1.37),0),
    "cell": ((-3.0,14.05,1.67),(-4.26,17.45,1.18),0),
    "booking": ((3.36,7.08,1.67),(5.0,9.22,1.45),0),
}
if ERA == "1900":
    views.pop("booking")
(ROOT / "renders").mkdir(exist_ok=True)
for tag, (at, aim, scale) in views.items():
    if ONLY != [""] and tag not in ONLY:
        continue
    night, inside = tag == "night", tag in ("desk", "cell", "booking")
    sky.inputs[0].default_value = (.17,.27,.46,1) if night else (.72,.80,.92,1)
    sky.inputs[1].default_value = .30 if night else .73
    sun.energy = .32 if night else 2.7
    sun.color = (.62,.74,1) if night else (1,.94,.82)
    front.energy = 310 if night else 0
    front_fill.energy = 170 if night or tag == "desk" else 0
    desk_fill.energy = 140 if tag == "desk" else 0
    booking_fill.energy = 155 if tag == "booking" else 0
    cell_fill.energy = 31 if tag == "cell" else 0
    transfer.energy = 54 if tag == "cell" else 0
    for name, strength in (("glass_station", 1.1 if night else .035),
                           ("glass_restricted", .23 if night else .018),
                           ("glow_entrance", 3.8 if night else .07)):
        p = bpy.data.materials[name].node_tree.nodes["Principled BSDF"]
        p.inputs["Emission Strength"].default_value = strength
    camera.location = at
    camera.rotation_euler = (0,0,0) if tag == "above" else (Vector(aim)-camera.location).to_track_quat("-Z","Y").to_euler()
    camera.data.type = "ORTHO" if scale else "PERSP"
    camera.data.ortho_scale = scale or 1
    camera.data.lens = 19 if inside else 24
    scene.render.filepath = str(ROOT / "renders" / f"briarford-{ERA}-{tag}.png")
    bpy.ops.render.render(write_still=True)
