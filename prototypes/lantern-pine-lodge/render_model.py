"""Render the lodge from the exported GLB with a desktop-only woodland setting."""
import os
from math import radians
from pathlib import Path

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT/"lantern-pine-lodge.glb"))
scene=bpy.context.scene
scene.render.engine="CYCLES"
scene.cycles.samples=15
scene.cycles.use_denoising=False
scene.cycles.max_bounces=3
scene.render.resolution_x=1200
scene.render.resolution_y=840
scene.render.resolution_percentage=100
scene.view_settings.view_transform="AgX"
scene.view_settings.look="AgX - Medium High Contrast"
for ob in bpy.data.objects:
    if ob.get("collision_only") or ob.name.startswith("collider_"):
        ob.hide_render=True


def pos(p):
    return Vector(p)+Vector((0,14.5,0))


def plain(name,color):
    mat=bpy.data.materials.new(name)
    mat.diffuse_color=(*color,1)
    return mat


world=bpy.data.worlds.new("Woodland sky")
world.use_nodes=True
scene.world=world
bg=world.node_tree.nodes.get("Background")
sky=world.node_tree.nodes.new("ShaderNodeBackground")
ramp=world.node_tree.nodes.new("ShaderNodeValToRGB")
ramp.color_ramp.elements[1].position=.54
tex=world.node_tree.nodes.new("ShaderNodeTexCoord")
sep=world.node_tree.nodes.new("ShaderNodeSeparateXYZ")
absolute=world.node_tree.nodes.new("ShaderNodeMath")
absolute.operation="ABSOLUTE"
mix=world.node_tree.nodes.new("ShaderNodeMixShader")
rays=world.node_tree.nodes.new("ShaderNodeLightPath")
for outlet,inlet in ((tex.outputs["Normal"],sep.inputs[0]),(sep.outputs["Z"],absolute.inputs[0]),
                      (absolute.outputs[0],ramp.inputs[0]),(ramp.outputs[0],sky.inputs[0]),
                      (rays.outputs["Is Camera Ray"],mix.inputs[0]),(bg.outputs[0],mix.inputs[1]),
                      (sky.outputs[0],mix.inputs[2]),(mix.outputs[0],world.node_tree.nodes.get("World Output").inputs[0])):
    world.node_tree.links.new(outlet,inlet)

bpy.ops.mesh.primitive_plane_add(size=1200,location=pos((0,0,.045)))
bpy.context.object.name="Woodland continuation"
bpy.context.object.data.materials.append(plain("Desktop forest soil",(.153,.156,.099)))

# Clone a few of the asset's own pines into the desktop background at metre scale.
# The GLB beneath the render still contains precisely the documented parcel.
base_trees=[bpy.data.objects[f"tree_{i:02d}"] for i in (2,4,8,10)]
context=[]
for i,(x,y,s) in enumerate([(-23,-5,1.18),(-23,7,1.13),(-20,15,1.38),(-23,24,1.2),
        (-12,21,1.24),(-5,25,1.24),(2,22,1.21),(9,22,1.28),(16,19,1.38),
        (22,18,1.56),(25,9,1.20),(22,1,1.27),(19,29,1.2),(-26,32,1.45),
        (2,32,1.35),(-11,33,1.4),(13,33,1.43),(28,24,1.43)]):
    template=base_trees[i%4]
    points=[v.co for o in template.children if o.type=="MESH" for v in o.data.vertices]
    center=Vector(((min(v.x for v in points)+max(v.x for v in points))/2,
                  (min(v.y for v in points)+max(v.y for v in points))/2,0))
    root=bpy.data.objects.new("Desktop background pine",None)
    scene.collection.objects.link(root)
    root.location=pos((x,y,0))
    root.rotation_euler.z=radians(i*59%360)
    root.scale=(s,s,s)
    context.append(root)
    for ob in template.children:
        if ob.type=="MESH":
            clone=ob.copy()
            scene.collection.objects.link(clone)
            clone.parent=root
            clone.location=ob.location-center
            context.append(clone)

bpy.ops.object.light_add(type="SUN")
sun=bpy.context.object
sun.rotation_euler=Vector((.44,.55,-.52)).to_track_quat("-Z","Y").to_euler()
sun.data.angle=.19


def light(name,p,target,power,color,size):
    data=bpy.data.lights.new(name,"AREA")
    data.color=color
    data.size=size
    ob=bpy.data.objects.new(name,data)
    scene.collection.objects.link(ob)
    ob.location=pos(p)
    ob.rotation_euler=(pos(target)-ob.location).to_track_quat("-Z","Y").to_euler()
    return data,power


lights=[
    light("Porch amber",(-3.65,-5.49,3.22),(-2.5,-7.8,.43),166,(1,.70,.39),2.3),
    light("Window spill",(3.3,-3.80,2.44),(3.3,-6.2,.65),145,(1,.72,.38),2.1),
    light("Door lantern",(-2.55,-4.47,2.44),(-1.49,-3.58,1.85),104,(1,.69,.34),1.2),
    light("Hearth fire",(-4.33,3.51,1.45),(-1.94,3.48,1.44),330,(1,.57,.26),1.45),
    light("Great room lantern",(.11,1.28,4.98),(0,0,.69),225,(1,.77,.45),1.30),
    light("Interior lantern",(.02,-1.42,4.84),(2,-2.3,.69),130,(1,.82,.54),1),
    light("Rear stoop soup lamp",(4.04,6.20,2.72),(3.09,6.0,.70),90,(1,.72,.37),.72),
    light("Moonlit forest rim",(11,7,12),(0,4,1),550,(.49,.66,1),7)
]
bpy.ops.object.camera_add()
cam=bpy.context.object
cam.data.clip_end=1200
scene.camera=cam
for name,where,target,lens,night in [
    ("dusk",(-12.0,-21.7,6.45),(.20,-1.75,3.31),39,True),
    ("day",(-18.5,-27,13.6),(0,-.4,3.0),38,False),
    ("rear",(9.1,9.7,7.7),(2.2,3.4,2.85),27,False),
    ("above",(7,-8,49),(0,0,1.4),38,False),
    ("porch",(-3.9,-13.48,1.71),(-.3,-4.89,2.42),23,True),
    ("hearth",(3.72,.75,2.31),(-4.74,3.53,1.98),26,True)
]:
    if os.environ.get("LODGE_VIEW") not in (None,name):
        continue
    cam.location=pos(where)
    cam.rotation_euler=(pos(target)-cam.location).to_track_quat("-Z","Y").to_euler()
    cam.data.lens=lens
    sky_colors=((.110,.161,.211,1),(.028,.064,.126,1)) if night else ((.60,.72,.74,1),(.24,.45,.66,1))
    for element,color in zip(ramp.color_ramp.elements,sky_colors):
        element.color=color
    bg.inputs[0].default_value=(.32,.43,.67,1) if night else (.78,.88,.98,1)
    bg.inputs[1].default_value=.52 if night else .76
    sun.data.energy=.19 if night else 2.12
    sun.data.color=(.50,.65,1) if night else (1,.88,.69)
    for dtype,power in lights:
        dtype.energy=power if night else power*.06
    for ob in context:
        ob.hide_render=name=="above"
    for key,strength in (("glass_lodge",1.10 if night else .09),("glow_lantern",1.65 if night else .22)):
        bpy.data.materials[key].node_tree.nodes.get("Principled BSDF").inputs["Emission Strength"].default_value=strength
    scene.render.filepath=str(ROOT/"renders"/f"{name}.png")
    bpy.ops.render.render(write_still=True)
