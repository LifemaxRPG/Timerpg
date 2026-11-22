"""Render a single state's exported near GLB in Blender Cycles."""
from pathlib import Path
import os

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
state=os.environ.get("MARLOW_STATE","1925")
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT/f"marlow-{state}.glb"))
for obj in bpy.data.objects:
    if obj.get("collision_only"):
        obj.hide_render=True

scene=bpy.context.scene
scene.render.engine="CYCLES"
scene.cycles.samples=8
scene.cycles.use_denoising=False
scene.cycles.max_bounces=3
scene.render.resolution_x=790
scene.render.resolution_y=590
scene.view_settings.view_transform="AgX"
scene.view_settings.look="AgX - Medium High Contrast"
world=bpy.data.worlds.new("Main Street twilight")
world.use_nodes=True
scene.world=world
nodes,links=world.node_tree.nodes,world.node_tree.links
sky=nodes.get("Background")
screen=nodes.new("ShaderNodeBackground")
camera_ray=nodes.new("ShaderNodeLightPath")
mix=nodes.new("ShaderNodeMixShader")
links.new(camera_ray.outputs["Is Camera Ray"],mix.inputs[0])
links.new(sky.outputs[0],mix.inputs[1])
links.new(screen.outputs[0],mix.inputs[2])
links.new(mix.outputs[0],nodes.get("World Output").inputs[0])

def ground(name,xy,size,color):
    bpy.ops.mesh.primitive_plane_add(size=1,location=xy)
    ob=bpy.context.object
    ob.name=name
    ob.scale=(*size,1)
    m=bpy.data.materials.new(name)
    m.use_nodes=True
    m.node_tree.nodes.get("Principled BSDF").inputs["Base Color"].default_value=(*color,1)
    m.node_tree.nodes.get("Principled BSDF").inputs["Roughness"].default_value=.94
    ob.data.materials.append(m)

ground("Desktop ground", (0,7,-.018),(110,100),(.255,.24,.204))
ground("Desktop street",(0,-9,-.006),(110,13.35),(.127,.139,.135))
bpy.ops.object.light_add(type="SUN")
sun=bpy.context.object
sun.rotation_euler=Vector((.41,.49,-.73)).to_track_quat("-Z","Y").to_euler()
sun.data.angle=.18

def area(label,xyz,aim,w,size,color):
    data=bpy.data.lights.new(label,"AREA")
    ob=bpy.data.objects.new(label,data)
    scene.collection.objects.link(ob)
    ob.location=xyz
    ob.rotation_euler=(Vector(aim)-ob.location).to_track_quat("-Z","Y").to_euler()
    data.size=size
    data.color=color
    return data,w

warm=(1,.76,.48) if state=="1925" else (.94,.93,.74)
shop_lights=[area("Desktop display wash",(x,.63,2.95),(x,1.92,.9),170,1.5,warm) for x in (-2.9,2.9)]
shop_lights += [area("Desktop sales floor wash",(x,y,3.02),(x,y,.12),305,2.2,warm)
                for x in (-2.6,2.6) for y in (5.7,12.7,19.9,24.2)]
front_light=area("Desktop street lamp",(2.8,-5,5.28),(0,0,2.3),310,2.6,(1,.77,.47))
room_fill=area("Desktop review fill",(-1.3,14,2.7),(0,16,.6),240,3.8,(.76,.82,.86))

bpy.ops.object.camera_add()
cam=bpy.context.object
scene.camera=cam
cam.data.clip_end=280
views={
 "day":((16,-18,20.5),(0,12,3.1),36,False),
 "night":((14,-21,17),(0,10.4,3.4),34,True),
 "rear":((19,43,17),(0,18.7,3.9),35,False),
 "above":((8,15,55),(0,13,2.2),41,False),
 "eye":((3.35,-10.7,1.72),(0,.3,2.91),0,False),
 "room":((-.28,3.22,1.71),(-.70,15.95,1.80),0,False),
}
only=os.environ.get("MARLOW_VIEWS")
for label,(eye,target,width,night) in views.items():
    if only and label not in only.split(","):
        continue
    cam.location=eye
    cam.rotation_euler=(Vector(target)-cam.location).to_track_quat("-Z","Y").to_euler()
    cam.data.type="ORTHO" if width else "PERSP"
    cam.data.ortho_scale=width or 1
    cam.data.lens=19 if label=="room" else 28
    sun.data.energy=.22 if night else 2.65
    sun.data.color=(.59,.71,1) if night else (1,.89,.73)
    sky.inputs[0].default_value=(.24,.40,.70,1) if night else (.65,.76,.87,1)
    sky.inputs[1].default_value=.55 if night else .74
    screen.inputs[0].default_value=(.065,.11,.19,1) if night else (.39,.55,.61,1)
    screen.inputs[1].default_value=.72
    for light,power in shop_lights:
        light.energy=0 if state=="closed" else (power if night or label=="room" else power*.35)
    front_light[0].energy=front_light[1] if night else 0
    room_fill[0].energy=room_fill[1]*(.20 if state=="closed" else .55) if label=="room" else 0
    for material_name in ("glow_fixtures","sign_name","glass_storefront"):
        mat=bpy.data.materials.get(material_name)
        shader=mat.node_tree.nodes.get("Principled BSDF") if mat else None
        if shader:
            shader.inputs["Emission Strength"].default_value=(
                .7 if material_name=="glow_fixtures" else .21 if material_name=="sign_name" else .15
            ) if night and state!="closed" else .045
    scene.render.filepath=str(ROOT/"renders"/f"{state}-{label}.png")
    bpy.ops.render.render(write_still=True)
