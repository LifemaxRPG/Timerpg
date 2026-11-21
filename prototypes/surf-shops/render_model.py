"""Render the shipped storefront GLBs with a desktop-only boardwalk and lighting rig."""
from pathlib import Path
import os

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
style=os.environ.get("SURF_SHOP","driftline")
modern=style=="tideglass"
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT/f"{style}.glb"))
scene=bpy.context.scene
scene.render.engine="CYCLES"
scene.cycles.samples=13
scene.cycles.max_bounces=4
scene.cycles.use_denoising=False
scene.render.resolution_x=1140
scene.render.resolution_y=850
scene.view_settings.view_transform="AgX"
scene.view_settings.look="AgX - Medium High Contrast"
for ob in bpy.data.objects:
    if ob.get("collision_only") or ob.name.startswith("collider_"):
        ob.hide_render=True

world=bpy.data.worlds.new("Coastal sky")
world.use_nodes=True
scene.world=world
nodes,links=world.node_tree.nodes,world.node_tree.links
sky=nodes.get("Background")
background=nodes.new("ShaderNodeBackground")
ray=nodes.new("ShaderNodeLightPath")
mix=nodes.new("ShaderNodeMixShader")
links.new(ray.outputs["Is Camera Ray"],mix.inputs[0])
links.new(sky.outputs[0],mix.inputs[1])
links.new(background.outputs[0],mix.inputs[2])
links.new(mix.outputs[0],nodes.get("World Output").inputs[0])

def mat(name,colors):
    m=bpy.data.materials.new(name)
    m.use_nodes=True
    n,l=m.node_tree.nodes,m.node_tree.links
    texture=n.new("ShaderNodeNewGeometry")
    scale=n.new("ShaderNodeVectorMath")
    scale.operation="MULTIPLY"
    scale.inputs[1].default_value=(12.88,12.88,12.88)
    grid=n.new("ShaderNodeVectorMath")
    grid.operation="FLOOR"
    noise=n.new("ShaderNodeTexWhiteNoise")
    lookup=n.new("ShaderNodeValToRGB")
    lookup.color_ramp.interpolation="CONSTANT"
    lookup.color_ramp.elements[0].color=(*colors[0],1)
    lookup.color_ramp.elements[1].position=.78
    lookup.color_ramp.elements[1].color=(*colors[-1],1)
    lookup.color_ramp.elements.new(.33).color=(*colors[1],1)
    shader=n.get("Principled BSDF")
    shader.inputs["Roughness"].default_value=.94
    l.new(texture.outputs["Position"],scale.inputs[0])
    l.new(scale.outputs[0],grid.inputs[0])
    l.new(grid.outputs[0],noise.inputs[0])
    l.new(noise.outputs["Value"],lookup.inputs[0])
    l.new(lookup.outputs[0],shader.inputs["Base Color"])
    return m

def flat_box(name,center,scale,material):
    bpy.ops.mesh.primitive_cube_add(size=1,location=center)
    ob=bpy.context.object
    ob.name=name
    ob.scale=scale
    ob.data.materials.append(material)

sand=mat("Preview dune colors",[(.286,.25,.177),(.306,.271,.197),(.325,.290,.216)])
road=mat("Preview boardwalk street",[(.125,.139,.129),(.139,.149,.138),(.149,.161,.147)])
curb=mat("Preview stone curb",[(.32,.32,.265),(.35,.35,.295),(.37,.37,.317)])
flat_box("Coastal display ground",(0,3,-.30),(95,110,.56),sand)
flat_box("Boardwalk road backdrop",(0,-9.57,-.021),(95,13.93,.03),road)
flat_box("Walkway curb course",(0,-2.62,.016),(95,.19,.071),curb)

bpy.ops.object.light_add(type="SUN")
sun=bpy.context.object
sun.rotation_euler=Vector((.45,.53,-.72)).to_track_quat("-Z","Y").to_euler()
sun.data.angle=.19

def lamp(name,xyz,hit,power,size,tint):
    data=bpy.data.lights.new(name,"AREA")
    ob=bpy.data.objects.new(name,data)
    scene.collection.objects.link(ob)
    ob.location=xyz
    ob.rotation_euler=(Vector(hit)-ob.location).to_track_quat("-Z","Y").to_euler()
    data.shape="DISK"
    data.size=size
    data.color=tint
    return data,power

display=[lamp("Window merchandising wash",(x,.38,z),(x,1.34,1.34),230 if modern else 184,1.03,(1,.82,.58))
         for x,z in ([(-4.98,3.46),(.29,3.48),(2.99,3.48),(5.18,3.55)] if modern else
                      [(-4.48,2.98),(-2.67,2.98),(2.5,2.98),(4.42,2.98)])]
if modern:
    display+= [lamp("Wrap window wash",(6.10,y,3.18),(5.35,y,1.35),216,.83,(1,.86,.64)) for y in (2.43,3.95)]
interior=[lamp("Retail ceiling wash",(x,3.75,3.35),(x,4.5,0),220,1.88,(1,.81,.57)) for x in (-3.4,0,3.4)]
outdoors=[lamp("Under-awning sidewalk wash",(x,-.94,2.53 if not modern else 3.89),(x,-1.22,.05),95,1.22,(1,.78,.46))
          for x in (-4,0,4)]

bpy.ops.object.camera_add()
cam=bpy.context.object
scene.camera=cam
cam.data.clip_end=500
views={
 "day":((14.6,-17.7,11.3),(0,4.02 if modern else 3.40,1.94),20.2 if modern else 18.8,False),
 "street":((5.3,-11.4,1.72),(0,.55,2.44),0,False),
 "night":((13.4,-19,8.7),(0,3.38,2.0),18.7,True),
 "rear":((16.4,20.7,10.4),(0,5.23,1.85),18.6,False),
}
only=os.environ.get("SURF_VIEWS")
for name,(eye,target,ortho,night) in views.items():
    if only and name not in only.split(","):
        continue
    cam.location=eye
    cam.rotation_euler=(Vector(target)-cam.location).to_track_quat("-Z","Y").to_euler()
    cam.data.type="ORTHO" if ortho else "PERSP"
    cam.data.ortho_scale=ortho or 1
    cam.data.lens=26
    sun.data.energy=.29 if night else 2.65
    sun.data.color=(.58,.74,1) if night else (1,.89,.73)
    sky.inputs[0].default_value=(.28,.44,.75,1) if night else (.64,.78,.88,1)
    sky.inputs[1].default_value=.59 if night else .73
    background.inputs[0].default_value=(.066,.128,.20,1) if night else (.38,.60,.68,1)
    background.inputs[1].default_value=.71
    for data,power in display:
        data.energy=power if night else power*.74
    for data,power in interior:
        data.energy=power if night else power*.43
    for data,power in outdoors:
        data.energy=power if night else 2
    for mat_name in ("glow_shop","sign_name"):
        emitter=bpy.data.materials[mat_name].node_tree.nodes.get("Principled BSDF").inputs["Emission Strength"]
        emitter.default_value=(1.9 if mat_name=="glow_shop" else .62) if night else .10
    scene.render.filepath=str(ROOT/"renders"/f"{style}-{name}.png")
    bpy.ops.render.render(write_still=True)
