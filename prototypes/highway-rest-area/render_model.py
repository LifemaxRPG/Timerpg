"""Render the exported model with separate, desktop-only lighting and scale figures."""
import os
from pathlib import Path

import bpy
from mathutils import Vector as V

ROOT = Path(__file__).resolve().parent
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT/"highway-rest-area.glb"))
for ob in bpy.data.objects:
    if ob.get("collision_only") or ob.name.startswith("collider"):
        ob.hide_render=True
scene = bpy.context.scene
scene.render.engine = "CYCLES"
scene.cycles.samples = 15
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
    return V(position) + V((0,33,0))


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


# Vehicles indicate parking capacity in the render scene only.
vehicle_white=preview_mat("Preview delivery cream",(.56,.53,.42))
vehicle_glass=preview_mat("Preview glass",(.16,.24,.27))
vehicle_navy=preview_mat("Preview navy",(.11,.21,.26))
vehicle_wheel=preview_mat("Preview tire",(.032,.036,.035))
vehicle_tan=preview_mat("Preview brown",(.34,.18,.10))
vehicle_teal=preview_mat("Preview green",(.19,.30,.26))

def preview_wheels(x,y,width,offsets,radius):
    for yy in offsets:
        for side in (-1,1):
            bpy.ops.mesh.primitive_cylinder_add(vertices=8,radius=radius,depth=.22,
                location=at((x+side*width/2,y+yy,.15+radius)),rotation=(0,1.5708,0))
            ob=bpy.context.object
            ob.parent=stage
            ob.data.materials.append(vehicle_wheel)

def car(x,y,m):
    propblock("Passenger car body",(x,y,.71),(1.78,4.28,.64),m)
    propblock("Passenger cabin glazing",(x,y+.37,1.15),(1.59,2.33,.57),vehicle_glass)
    propblock("Passenger painted roof",(x,y+.48,1.42),(1.43,1.49,.11),m)
    preview_wheels(x,y,1.62,(-1.28,1.30),.32)

def truck(x,y,m):
    propblock("Tractor nose",(x,y-8.5,1.30),(2.53,4.0,1.65),m)
    propblock("Tractor windshield", (x,y-7.77,2.46),(2.45,1.35,.69),vehicle_glass)
    propblock("Tractor roof",(x,y-7.75,2.88),(2.53,1.67,.28),m)
    propblock("Box trailer", (x,y+2.37,2.65),(2.61,15.15,2.81),vehicle_white)
    propblock("Trailer edge trim", (x,y+2.37,1.28),(2.67,15.17,.16),vehicle_navy)
    preview_wheels(x,y,2.32,(-9,-6.1,-4.97,7.81,8.85),.53)

for x,y,m in ((-31.7,-10.4,vehicle_tan),(-25.9,-10.4,vehicle_navy),
              (-17.55,-20.22,vehicle_teal),(-8.99,-20.22,vehicle_white),
              (-20.39,-10.4,vehicle_white),(-3.38,-20.22,vehicle_tan)):
    car(x,y,m)
truck(25.31,7,vehicle_navy)
truck(33.84,7,vehicle_tan)

lamps=[]
for x,y in ((-29,-15.2),(-10,-15.1),(6.7,-15.2),(15.0,-14.8),
            (15.4,14.0),(38.5,7.2),(-22.3,24.9)):
    lamps.append(area("Parking lamp",(x,y-.55,5.37),(x,y-.65,0),450,(1,.78,.48),.31))
for x,y in ((-9,-1),(-5.2,-1),(-7.15,10.7),(-27.2,18.05),(-14.3,22.22),(-14,7.4),(-.2,7.4)):
    lamps.append(area("Covered area lamp",(x,y,3.0 if y<15 else 2.49),(x,y,.23),245,(1,.77,.44),.39))
lamps.append(area("Warm breezeway spill",(-7.10,-3.6,4.0),(-7.10,2.0,1.8),490,(1,.78,.50),2.4))
bpy.ops.object.camera_add()
cam = bpy.context.object
cam.data.clip_end = 950
scene.camera = cam
p = bpy.data.materials["glow_lamps"].node_tree.nodes.get("Principled BSDF")
views = [
    ("day", (43,-73,48), (0,0,2.2), 34, False),
    ("night", (-34,-68,39), (0,0,2.5), 31, True),
    ("walking", (4.1,-17,1.78), (-8,3.1,2.65), 28, False),
    ("rear", (42,51,36), (-4,3,1.75), 34, False),
    ("above", (9,-25,98), (0,0,1), 38, False),
    ("vending", (-7.65,7.65,1.84), (-7.18,11.9,1.55), 25, True),
    ("truck", (19.7,-18,2.2), (24.8,9.6,2.4), 27, False),
    ("restroom", (-12.77,5.21,1.86), (-14.04,11.48,1.44), 22, True)
]
(ROOT/"renders").mkdir(exist_ok=True)
for name, position, target, lens, night in views:
    requested = os.environ.get("REST_VIEW")
    if requested and name != requested:
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
