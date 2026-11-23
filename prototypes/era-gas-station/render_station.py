"""Render the near GLBs at roadside and walk-up scales."""
import os
from pathlib import Path

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
ERA=os.environ.get("STATION_ERA","1925")
ONLY=os.environ.get("STATION_VIEWS","").split(",")
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT/f"vale-lantern-{ERA}.glb"))
for ob in bpy.data.objects:
    if ob.get("collision_only"):
        ob.hide_render=True
scene=bpy.context.scene
scene.render.engine="CYCLES"
scene.cycles.use_denoising=False
scene.cycles.max_bounces=3
scene.render.resolution_x=780
scene.render.resolution_y=548
scene.view_settings.view_transform="AgX"
scene.view_settings.look="AgX - Medium High Contrast"
scene.world=bpy.data.worlds.new("Highway sky")
scene.world.use_nodes=True
sky=scene.world.node_tree.nodes["Background"]


def light(name,kind,at,aim,color,size=1):
    data=bpy.data.lights.new(name,kind)
    ob=bpy.data.objects.new(name,data)
    scene.collection.objects.link(ob)
    ob.location=at
    ob.rotation_euler=(Vector(aim)-ob.location).to_track_quat("-Z","Y").to_euler()
    data.color=color
    if kind=="AREA":
        data.size=size
    return data


sun=light("Late afternoon","SUN",(-17,-24,32),(0,6,0),(1,.92,.77))
sun.angle=.14
porch=light("Cottage entrance","AREA",(0,-.65,2.43),(0,-.68,.12),(1,.81,.51),2.25)
office=light("Shop light","AREA",(-.63,2.36,2.63),(-.80,4.35,1),(1,.83,.56),2.15)
counter=light("Counter bounce","AREA",(-.28,4.93,2.64),(-.91,1.68,.78),(.89,.95,1),1.70)
sign=light("Pole sign wash","AREA",(-12.14,-13.2,4.00 if ERA=="1925" else 6.62),(-11.91,-11.85,4.1 if ERA=="1925" else 6.75),(1,.72,.44),2.5)
walk=[]
sites={"1925":[(-1.78,-3.33,2.54),(1.78,-3.33,2.54)],
       "1945":[(-5.44,-6.52,3.66),(.15,-6.52,3.66)],
       "1975":[(-6.08,-8.43,4.28),(.07,-8.43,4.28),(6.18,-8.43,4.28)]}
for x,y,z in sites[ERA]:
    walk.append(light("Pump island pool","AREA",(x,y,z),(x,y,.63),(1,.79,.51) if ERA!="1975" else (.83,.96,1),2.15))
shop=[]
if ERA=="1945":
    for x in (6.43,10.42):
        shop.append(light("Service bay ceiling","AREA",(x,2.42,3.72),(x,1.14,.55),(1,.84,.62),2.15))

mat=bpy.data.materials.new("Ground context only")
mat.diffuse_color=(.19,.231,.171,1)
bpy.ops.mesh.primitive_plane_add(size=170,location=(0,-1,-.016))
bpy.context.object.data.materials.append(mat)
paving=bpy.data.materials.new("Road context only")
paving.diffuse_color=(.158,.169,.171,1)
bpy.ops.mesh.primitive_cube_add(size=1,location=(0,-20.55,-.018))
road=bpy.context.object
road.scale=(135,7,.032)
road.data.materials.append(paving)
bpy.ops.object.camera_add()
camera=bpy.context.object
scene.camera=camera
views={
    "day":((23,-33,21),(-.2,-2.25,2.68),39.2),
    "night":((-23,-30,13),(-.3,-3.3,2.71),38.5),
    "back":((-23,25,17),(0,-2.6,2.53),38.9),
    "above":((-.16,-3.12,45),(-.16,-3.12,0),42.4),
    "entrance":((5.10,-9.85,1.66),(-.57,-2.62,1.85),0) if ERA=="1925" else
               ((4.82,-12.86,1.66),(-2.32,-6.01,2.11),0) if ERA=="1945" else
               ((9.62,-15.81,1.66),(1.00,-8.64,2.18),0),
    "office-door":((.25,.66,1.67),(-.47,4.56,1.34),0),
    "office-counter":((-.53,4.86,1.67),(.27,.17,1.18),0),
}
OUT=ROOT/"renders"
OUT.mkdir(exist_ok=True)
for tag,(at,aim,width) in views.items():
    if ONLY!=[""] and tag not in ONLY:
        continue
    dim=tag=="night"
    inside=tag in ("office-door","office-counter")
    scene.cycles.samples=11 if dim or inside else 9
    sun.energy=.24 if dim else 2.54
    sun.color=(.60,.74,1) if dim else (1,.92,.77)
    sky.inputs[0].default_value=(.19,.29,.51,1) if dim else (.74,.83,.92,1)
    sky.inputs[1].default_value=.38 if dim else .74
    porch.energy=205 if dim else 0
    office.energy=225 if inside else 102 if dim else 0
    counter.energy=177 if inside else 0
    sign.energy=198 if dim else 0
    for l in walk:
        l.energy=220 if dim else 0
    for l in shop:
        l.energy=183 if dim else 0
    for name,energy in (("glass_shop",.95 if dim else .025),("sign_station",2.1 if dim else .07),
                        ("glow_price",2.45 if dim else .12)):
        m=bpy.data.materials.get(name)
        if m:
            m.node_tree.nodes["Principled BSDF"].inputs["Emission Strength"].default_value=energy
    camera.location=at
    camera.rotation_euler=(0,0,0) if tag=="above" else (Vector(aim)-camera.location).to_track_quat("-Z","Y").to_euler()
    camera.data.type="ORTHO" if width else "PERSP"
    camera.data.ortho_scale=width or 1
    camera.data.lens=18 if inside else 22
    scene.render.filepath=str(OUT/f"vale-lantern-{ERA}-{tag}.png")
    bpy.ops.render.render(write_still=True)
