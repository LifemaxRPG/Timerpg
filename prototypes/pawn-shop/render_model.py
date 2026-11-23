"""Render seven views of a delivered near GLB for visual review."""
import os
from pathlib import Path

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
ERA=os.environ.get("PAWN_ERA","1920")
ONLY=os.environ.get("PAWN_VIEWS","").split(",")
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT/f"latch-and-lyre-{ERA}.glb"))
for ob in bpy.data.objects:
    if ob.get("collision_only"):
        ob.hide_render=True

scene=bpy.context.scene
scene.render.engine="CYCLES"
scene.cycles.samples=11
scene.cycles.use_denoising=False
scene.cycles.max_bounces=4
scene.cycles.transparent_max_bounces=8
scene.render.resolution_x=760
scene.render.resolution_y=546
scene.view_settings.view_transform="AgX"
scene.view_settings.look="AgX - Medium High Contrast"
scene.world=bpy.data.worlds.new("Alley ambient")
scene.world.use_nodes=True
sky=scene.world.node_tree.nodes["Background"]


def lamp(name,kind,at,aim,color,size=1):
    data=bpy.data.lights.new(name,kind)
    ob=bpy.data.objects.new(name,data)
    scene.collection.objects.link(ob)
    ob.location=at
    ob.rotation_euler=(Vector(aim)-ob.location).to_track_quat("-Z","Y").to_euler()
    data.color=color
    if kind=="AREA":
        data.size=size
    return data


sun=lamp("Sun","SUN",(-10,-13,24),(0,8,0),(1,.93,.80))
sun.angle=.14
door_fill=lamp("Warm street reflector","AREA",(0,-2.9,4.65),(0,0,2.65),(1,.80,.58),2.45)
sign_fill=lamp("Fascia wash","AREA",(0,-1.30,4.84),(0,0,3.92),(1,.73,.40),2.1)
entry=lamp("Entrance ceiling bounce","AREA",(0,1.14,3.75),(0,4.15,1.2),(1,.91,.74),2.0)
aisle=lamp("Aisle security light","AREA",(0,5.44,3.75),(-.26,4.15,1.35),(1,.80,.49),1.4)
staff=lamp("Clerk light","AREA",(.83,8.35,3.68),(0,6.5,1.14),(.90,.93,1),1.8)
stockroom=lamp("Stockroom light","AREA",(2.1,11.24,3.74),(2.45,12.31,1.1),(1,.88,.58),1.4)

bpy.ops.mesh.primitive_plane_add(size=160,location=(0,7,-.022))
base=bpy.context.object
mat=bpy.data.materials.new("Neutral neighboring street")
mat.diffuse_color=(.19,.21,.18,1)
base.data.materials.append(mat)
bpy.ops.object.camera_add()
camera=bpy.context.object
scene.camera=camera
views={
    "day":((13,-15,11.8),(0,6.0,2.20),18.5),
    "night":((-12,-13.5,8.1),(0,5.4,2.1),18.1),
    "rear":((12.8,22.85,10.4),(0,8.0,1.93),18.65),
    "above":((0,6.77,38),(0,6.77,0),28.4),
    "street":((3.1,-6.95,1.67),(-.5,.22,2.55),0),
    "from-door":((.27,1.18,1.65),(-.18,6.15,1.52),0),
    "behind-counter":((-.95,8.77,1.67),(.8,3.42,1.36),0),
}
(ROOT/"renders").mkdir(exist_ok=True)
for tag,(at,aim,width) in views.items():
    if ONLY!=[""] and tag not in ONLY:
        continue
    night=tag=="night"
    inside=tag in ("from-door","behind-counter")
    sun.energy=.17 if night else 2.6
    sun.color=(.64,.75,1.0) if night else (1,.92,.79)
    sky.inputs[0].default_value=(.14,.24,.41,1) if night else (.73,.82,.92,1)
    sky.inputs[1].default_value=.42 if night else .69
    door_fill.energy=170 if night else 0
    sign_fill.energy=240 if night else 0
    entry.energy=190 if inside else 34 if night else 0
    staff.energy=160 if inside else 0
    aisle.energy=220 if inside else 47 if night else 0
    stockroom.energy=47 if inside else 0
    for name,strength in (("sign_paint",2.8 if night else .13),("glow_lamp",2.8 if night else .29),
                          ("glass_display",.52 if night else .021)):
        shader=bpy.data.materials[name].node_tree.nodes["Principled BSDF"]
        shader.inputs["Emission Strength"].default_value=strength
    camera.location=at
    camera.rotation_euler=(0,0,0) if tag=="above" else (Vector(aim)-camera.location).to_track_quat("-Z","Y").to_euler()
    camera.data.type="ORTHO" if width else "PERSP"
    camera.data.ortho_scale=width or 1
    camera.data.lens=17 if inside else 24
    scene.render.filepath=str(ROOT/"renders"/f"pawn-{ERA}-{tag}.png")
    bpy.ops.render.render(write_still=True)
