"""Render the exported harbor, with optional separately authored vessel context."""
from math import pi
from pathlib import Path
import os

import bpy
from mathutils import Vector

ROOT = Path(__file__).resolve().parent
bpy.ops.wm.read_factory_settings(use_empty=True)

def import_glb(path):
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=str(path))
    return set(bpy.data.objects)-before

import_glb(ROOT/"breakwater-harbor.glb")

def boat(env, places, draft):
    source = os.environ.get(env)
    if not source:
        return
    for x,y,heading in places:
        pieces = import_glb(Path(source))
        parent = bpy.data.objects.new("Desktop context vessel",None)
        bpy.context.collection.objects.link(parent)
        for obj in pieces:
            if obj.parent is None:
                obj.parent = parent
            if obj.name.startswith("collider_"):
                obj.hide_render = True
        parent.location = (x,y,-.85-draft)
        parent.rotation_euler.z = heading

boat("HARBOR_SLOOP",[(-30.9,-26.8,pi),(-12.9,-27,pi),(14.1,-25.5,pi)],1.43)
boat("HARBOR_YACHT",[(26.6,-29.5,pi)],2.10)

scene = bpy.context.scene
scene.render.engine = "CYCLES"
scene.cycles.samples = 14
scene.cycles.use_denoising = False
scene.cycles.max_bounces = 3
scene.render.resolution_x = 1160
scene.render.resolution_y = 860
scene.view_settings.view_transform = "AgX"
scene.view_settings.look = "AgX - Medium High Contrast"
world = bpy.data.worlds.new("Coastal sky")
world.use_nodes = True
scene.world = world
nodes,links=world.node_tree.nodes,world.node_tree.links
sky=nodes.get("Background")
back=nodes.new("ShaderNodeBackground")
back.inputs[1].default_value=.85
path=nodes.new("ShaderNodeLightPath")
mix=nodes.new("ShaderNodeMixShader")
links.new(path.outputs["Is Camera Ray"],mix.inputs[0])
links.new(sky.outputs[0],mix.inputs[1])
links.new(back.outputs[0],mix.inputs[2])
links.new(mix.outputs[0],nodes.get("World Output").inputs[0])

def plane(name,xyz,dimensions):
    bpy.ops.mesh.primitive_plane_add(size=1,location=xyz)
    obj=bpy.context.object
    obj.name=name
    obj.dimensions=(*dimensions,0)
    mat=bpy.data.materials.new(name)
    mat.use_nodes=True
    obj.data.materials.append(mat)
    return obj,mat

sea,mat=plane("Review water",(0,0,-.85),(2200,2200))
nodes,links=mat.node_tree.nodes,mat.node_tree.links
bsdf=nodes.get("Principled BSDF")
bsdf.inputs["Roughness"].default_value=.72
tex=nodes.new("ShaderNodeNewGeometry")
scale=nodes.new("ShaderNodeVectorMath")
scale.operation="MULTIPLY"
scale.inputs[1].default_value=(.45,1.9,1)
noise=nodes.new("ShaderNodeTexNoise")
noise.inputs["Scale"].default_value=1.3
noise.inputs["Detail"].default_value=2
ramp=nodes.new("ShaderNodeValToRGB")
ramp.color_ramp.interpolation="CONSTANT"
ramp.color_ramp.elements[0].color=(.047,.133,.145,1)
ramp.color_ramp.elements[1].color=(.112,.232,.224,1)
ramp.color_ramp.elements[1].position=.78
ramp.color_ramp.elements.new(.36).color=(.053,.154,.173,1)
ramp.color_ramp.elements.new(.59).color=(.069,.185,.196,1)
links.new(tex.outputs["Position"],scale.inputs[0])
links.new(scale.outputs[0],noise.inputs[0])
links.new(noise.outputs["Fac"],ramp.inputs[0])
links.new(ramp.outputs[0],bsdf.inputs["Base Color"])

_,mat=plane("Review shoreline continuation",(0,112,-.035),(198,138))
principled=mat.node_tree.nodes.get("Principled BSDF")
principled.inputs["Base Color"].default_value=(.143,.16,.125,1)
principled.inputs["Roughness"].default_value=.98
bpy.ops.object.light_add(type="SUN")
sun=bpy.context.object
sun.rotation_euler=Vector((-.52,.39,-.77)).to_track_quat("-Z","Y").to_euler()
sun.data.angle=.16

def point(name,xyzw,color,radius):
    *xyz,w=xyzw
    data=bpy.data.lights.new(name,"POINT")
    obj=bpy.data.objects.new(name,data)
    scene.collection.objects.link(obj)
    obj.location=xyz
    data.color=color
    data.shadow_soft_size=radius
    return data,w

lamps=[point("Quayside lamp",(x,y,z,480),(1,.73,.42),.56)
       for x,y,z in [(-14,2,4),(15,4,4),(37.1,-48,4.07),(37.1,-24,4.07),(37.1,-8,4.07),(-44.2,11,4),(-6.5,16,4)]]
lamps += [point("Warm rack light",(-25,y,10.5,1360),(1,.8,.55),1.25) for y in (13,21,31,37)]
lamps += [point("Warm office porch",(x,4,3.15,190),(1,.67,.39),.52) for x in (0,11)]
lamps += [point("Entrance daymark lens",(x,-82,3.11,35),c,.27)
          for x,c in [(-18,(.34,1,.7)),(37,(1,.3,.18))]]
roof=bpy.data.objects["warehouse_roof"]
bpy.ops.object.camera_add()
cam=bpy.context.object
cam.data.clip_end=2800
scene.camera=cam

views=[
    ("day",(99,-133,100),(0,-19,2),152,False,False),
    ("dusk",(-96,-128,83),(0,-19,2),148,True,False),
    ("above",(0,-18.025,174),(0,-18,0),190,False,False),
    ("warehouse",(-37,-14.5,2.78),(-25,12,6.4),0,False,False),
    ("rack-layout",(-58,-17,38),(-25,25,4.55),66,False,True),
    ("dockside",(-8,-17,1.18),(-14,-27,1.2),0,False,False),
    ("quay",(48.6,11,24),(26,-27,2.5),65,False,False),
    ("rear",(55,68,38),(-2,22,3.4),112,False,False),
]
selection=os.environ.get("HARBOR_VIEWS")
for name,eye,target,ortho,night,cutaway in views:
    if selection and name not in selection.split(","):
        continue
    cam.location=eye
    cam.rotation_euler=(Vector(target)-cam.location).to_track_quat("-Z","Y").to_euler()
    cam.data.type="ORTHO" if ortho else "PERSP"
    cam.data.ortho_scale=ortho or 1
    cam.data.lens=30 if name=="warehouse" else 25
    back.inputs[0].default_value=(.075,.135,.21,1) if night else (.42,.61,.69,1)
    sky.inputs[0].default_value=(.26,.42,.75,1) if night else (.69,.78,.87,1)
    sky.inputs[1].default_value=.55 if night else .62
    sun.data.energy=.2 if night else 2.5
    sun.data.color=(.59,.73,1) if night else (1,.88,.71)
    for data,power in lamps:
        data.energy=power if night else power*(.82 if data.name.startswith("Warm rack") else .025)
    for obj in roof.children_recursive:
        obj.hide_render=cutaway
    scene.render.filepath=str(ROOT/"renders"/f"{name}.png")
    bpy.ops.render.render(write_still=True)
