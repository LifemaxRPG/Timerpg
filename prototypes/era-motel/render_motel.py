"""Render the near GLBs at roadside and walk-up scales."""
import os
from pathlib import Path

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
ERA=os.environ.get("MOTEL_ERA","1935")
ONLY=os.environ.get("MOTEL_VIEWS","").split(",")
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT/f"birchbend-{ERA}.glb"))
for ob in bpy.data.objects:
    if ob.get("collision_only"):
        ob.hide_render=True
scene=bpy.context.scene
scene.render.engine="CYCLES"
scene.cycles.use_denoising=False
scene.cycles.max_bounces=3
scene.render.resolution_x=810
scene.render.resolution_y=570
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
porch=light("Office entrance","AREA",(0,-1.12,2.57),(0,-1.1,0),(1,.77,.42),2.45)
office=light("Office light","AREA",(-.34,2.47,2.79),(-.98,4.34,1),(1,.83,.56),2.15)
counter=light("Counter bounce","AREA",(-.10,4.79,2.78),(-.82,1.89,.8),(.89,.95,1),1.62)
sign=light("Roadside sign pool","AREA",(-10.44,-2.90,6.45),(-10.5,-1.72,.6),(1,.67,.35),2.0)
walk=[]
for x in (-8.9,-2.23,5.9,10.0):
    walk.append(light("Exterior room walk","AREA",(x,15.65,2.42),(x,16.0,.26),(1,.77,.49),2.35))
for y in (9.45,14.07):
    walk.append(light("Wing room walk", "AREA", (10.68,y,2.36), (11.51,y,.21), (1,.75,.47), 1.8))
if ERA=="1985":
    for y in (9.45,14.07):
        walk.append(light("Wing balcony", "AREA", (10.68,y,5.41), (11.51,y,3.26), (.81,.91,1), 1.8))
    for x in (-9,-2.3,5.9,9.8):
        walk.append(light("Balcony light","AREA",(x,15.71,5.47),(x,16.0,3.29),(.81,.91,1),2.04))

mat=bpy.data.materials.new("Ground context only")
mat.diffuse_color=(.19,.231,.171,1)
bpy.ops.mesh.primitive_plane_add(size=170,location=(0,8,-.016))
bpy.context.object.data.materials.append(mat)
paving=bpy.data.materials.new("Road context only")
paving.diffuse_color=(.158,.169,.171,1)
bpy.ops.mesh.primitive_cube_add(size=1,location=(0,-8.38,-.018))
road=bpy.context.object
road.scale=(135,7,.032)
road.data.materials.append(paving)
bpy.ops.object.camera_add()
camera=bpy.context.object
scene.camera=camera
views={
    "day":((24,-29,23),(1.0,10.2,2.6),44.5),
    "night":((-23,-26,16),(-.4,8.9,2.7),44.5),
    "back":((-30,41,17),(0,12.5,2.7),43.9),
    "above":((1.15,10.60,49),(1.15,10.60,0),50.2),
    "road":((8.55,-17.0,1.62),(0,9.5,2.45),0),
    "entrance":((5.24,-8.45,1.68),(0,.7,2.03),0),
    "office-door":((.26,.64,1.67),(-.37,4.02,1.39),0),
    "office-counter":((-.32,4.86,1.67),(.25,.25,1.17),0),
}
OUT=ROOT/"renders"
OUT.mkdir(exist_ok=True)
for tag,(at,aim,width) in views.items():
    if ONLY!=[""] and tag not in ONLY:
        continue
    dim=tag in ("night","road")
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
        l.energy=184 if dim else 0
    for name,energy in (("glass_motel",.95 if dim else .025),("sign_motel",2.1 if dim else .07),
                        ("glow_vacancy",2.54 if dim else .11),("glow_no",0),
                        ("glow_arrow",2.6 if dim else .08)):
        m=bpy.data.materials.get(name)
        if m:
            m.node_tree.nodes["Principled BSDF"].inputs["Emission Strength"].default_value=energy
    camera.location=at
    camera.rotation_euler=(0,0,0) if tag=="above" else (Vector(aim)-camera.location).to_track_quat("-Z","Y").to_euler()
    camera.data.type="ORTHO" if width else "PERSP"
    camera.data.ortho_scale=width or 1
    camera.data.lens=18 if inside else 22 if tag=="road" else 27
    scene.render.filepath=str(OUT/f"birchbend-{ERA}-{tag}.png")
    bpy.ops.render.render(write_still=True)
