"""Render the exported junction of terminal, quay and separately authored cruise ship."""
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

import_glb(ROOT/"port-meridian.glb")
ship_source=os.environ.get("MERIDIAN_COPPERWAKE")
if ship_source:
    pieces=import_glb(Path(ship_source))
    ship=bpy.data.objects.new("Desktop context MV Copperwake",None)
    bpy.context.collection.objects.link(ship)
    for obj in pieces:
        if obj.parent is None:
            obj.parent=ship
        if obj.name.startswith("collider_"):
            obj.hide_render=True
    ship.location=(22.4,0,-11.75)

scene = bpy.context.scene
scene.render.engine = "CYCLES"
scene.cycles.samples = 12
scene.cycles.use_denoising = False
scene.cycles.max_bounces = 3
scene.render.resolution_x = 1280
scene.render.resolution_y = 810
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

sea,mat=plane("Review water",(0,0,-2.4),(3000,3000))
nodes,links=mat.node_tree.nodes,mat.node_tree.links
bsdf=nodes.get("Principled BSDF")
bsdf.inputs["Roughness"].default_value=.72
tex=nodes.new("ShaderNodeNewGeometry")
scale=nodes.new("ShaderNodeVectorMath")
scale.operation="MULTIPLY"
scale.inputs[1].default_value=(.15,.68,1)
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

_,mat=plane("Review shoreline continuation",(-570,0,-.035),(898.8,1350))
principled=mat.node_tree.nodes.get("Principled BSDF")
principled.inputs["Base Color"].default_value=(.143,.16,.125,1)
principled.inputs["Roughness"].default_value=.98
bpy.ops.object.light_add(type="SUN")
sun=bpy.context.object
sun.rotation_euler=Vector((.63,.33,-.77)).to_track_quat("-Z","Y").to_euler()
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

lamps=[point("Quay lamp",(-6.71,y,8.18,870),(1,.74,.46),.52)
       for y in (-185,-125,-69,-12,43,99,154,185)]
lamps += [point("Hall light",(x,y,16.65,3250),(1,.77,.48),1.24)
          for x in (-74,-54,-34) for y in (-49,-6.5,36.5)]
lamps += [point("Forecourt canopy",(-87,y,4.64,530),(1,.76,.49),.45)
          for y in (-66,-51,-33,-14,4,24,42,54)]
lamps += [point("Bridge light",(x,-29,z,270),(1,.8,.58),.30)
          for x,z in [(-22,11.1),(-13.8,11.36),(-6.5,11.65),(1.15,12.08)]]
lamps += [point("Terminal check-in",(-65,y,7.2,2400),(1,.81,.61),1.2)
          for y in (-56,-32,-4,22,47)]
lamps += [point("Service yard",(x,y,6.1,550),(1,.76,.49),.65)
          for x,y in [(-70,-88),(-70,72),(-39,75),(-43,-79),(-39,-159),(-37,163)]]

if ship_source:
    # In the desktop scene only, lines join the ship's fore and aft points to quay bollards.
    rope=bpy.data.materials.new("Desktop mooring line")
    rope.diffuse_color=(.55,.46,.32,1)
    for a,b in [((18.1,-137,9.55),(-1.85,-150,1)),((18.1,-137,9.55),(-1.85,-118,1)),
                ((26.7,143,9.55),(-1.85,150,1)),((26.7,143,9.55),(-1.85,170,1))]:
        curve=bpy.data.curves.new("Mooring line","CURVE")
        curve.dimensions="3D"
        curve.bevel_depth=.09
        curve.bevel_resolution=0
        spline=curve.splines.new("POLY")
        spline.points.add(5)
        for i,p in enumerate(spline.points):
            f=i/5
            xyz=Vector(a).lerp(Vector(b),f)
            xyz.z-=1.08*f*(1-f)
            p.co=(*xyz,1)
        obj=bpy.data.objects.new("Desktop mooring line",curve)
        scene.collection.objects.link(obj)
        curve.materials.append(rope)

# A broad desktop-only bounce source keeps the roof and stairs legible in the interior review.
fill=bpy.data.lights.new("Interior review bounce","AREA")
fill.shape="DISK"
fill.size=44
fill_obj=bpy.data.objects.new(fill.name,fill)
scene.collection.objects.link(fill_obj)
fill_obj.location=(-54,18,16.7)
fill.color=(.91,.89,.76)
bpy.ops.object.camera_add()
cam=bpy.context.object
cam.data.clip_end=4300
scene.camera=cam

views=[
    ("day",(-306,-485,267),(-26,-6,11),399,False),
    ("dusk",(282,-451,238),(-25,2,13),444,True),
    ("above",(-39,-.02,490),(-39,0,0),650,False),
    ("front",(-201,-96,38),(-61,-10,11),0,False),
    ("boarding",(-8,-51,4.4),(-11,-28,10.4),0,False),
    ("transfer",(-10.8,-29.08,10.22),(4.8,-30.04,10.40),0,False),
    ("street",(-105,-57,1.81),(-80,-37,5.4),0,False),
    ("hall",(-74,39,9.86),(-43,16,11.6),0,False),
    ("service",(-180,224,57),(-46,93,3.8),0,False),
]
selection=os.environ.get("MERIDIAN_VIEWS")
for name,eye,target,ortho,night in views:
    if selection and name not in selection.split(","):
        continue
    interior=name in ("hall","transfer")
    fill_obj.location=(-6,-29,11.85) if name=="transfer" else (-54,18,16.7)
    fill.size=3 if name=="transfer" else 44
    fill.energy={"hall":29000,"transfer":260}.get(name,0)
    scene.cycles.samples=8 if interior else 12
    scene.render.resolution_percentage=68 if interior else 100
    scene.cycles.max_bounces=2 if interior else 3
    cam.location=eye
    cam.rotation_euler=(Vector(target)-cam.location).to_track_quat("-Z","Y").to_euler()
    cam.data.type="ORTHO" if ortho else "PERSP"
    cam.data.ortho_scale=ortho or 1
    cam.data.lens={"boarding":27,"front":38,"street":31,"service":35}.get(name,26)
    back.inputs[0].default_value=(.075,.135,.21,1) if night else (.42,.61,.69,1)
    sky.inputs[0].default_value=(.31,.46,.74,1) if night else (.69,.78,.87,1)
    sky.inputs[1].default_value=.55 if night else .62
    sun.data.energy=.43 if night else 2.5
    sun.data.color=(.59,.73,1) if night else (1,.88,.71)
    for data,power in lamps:
        if name=="hall":
            data.energy=power*.65 if data.name.startswith("Hall light") else 0
        elif name=="transfer":
            data.energy=power*.65 if data.name.startswith("Bridge light") else 0
        else:
            data.energy=power if night else power*.045
    scene.render.filepath=str(ROOT/"renders"/f"{name}.png")
    bpy.ops.render.render(write_still=True)
