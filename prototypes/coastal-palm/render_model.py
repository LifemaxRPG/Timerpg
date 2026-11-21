"""Review the exported palms under desktop lighting in a simple street setting."""
from pathlib import Path

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT/"coastal-palm.glb"))
scene=bpy.context.scene
scene.render.engine="CYCLES"
scene.cycles.samples=16
scene.cycles.use_denoising=False
scene.cycles.max_bounces=2
scene.render.resolution_x=1050
scene.render.resolution_y=950
scene.view_settings.view_transform="AgX"
scene.view_settings.look="AgX - Medium High Contrast"

world=bpy.data.worlds.new("Coastal sky")
world.use_nodes=True
scene.world=world
nodes=world.node_tree.nodes
links=world.node_tree.links
ambient=nodes.get("Background")
ambient.inputs[0].default_value=(.77,.85,.94,1)
ambient.inputs[1].default_value=.76
camera=nodes.new("ShaderNodeBackground")
camera.inputs[0].default_value=(.44,.63,.69,1)
camera.inputs[1].default_value=.86
mix=nodes.new("ShaderNodeMixShader")
ray=nodes.new("ShaderNodeLightPath")
links.new(ray.outputs["Is Camera Ray"],mix.inputs[0])
links.new(ambient.outputs[0],mix.inputs[1])
links.new(camera.outputs[0],mix.inputs[2])
links.new(mix.outputs[0],nodes.get("World Output").inputs[0])


def flat(name,rgb):
    mat=bpy.data.materials.new(name)
    mat.diffuse_color=(*rgb,1)
    return mat


soil=flat("Coastal sandy soil",(.322,.280,.192))
walk=flat("Pale coastal paving",(.44,.44,.37))
street=flat("Street asphalt",(.16,.177,.171))
navy=flat("Scale figure trousers",(.084,.146,.169))
shirt=flat("Scale figure coat",(.40,.20,.105))
skin=flat("Scale figure skin",(.53,.34,.18))

bpy.ops.mesh.primitive_plane_add(size=1800,location=(0,0,-.019))
bpy.context.object.data.materials.append(soil)


def block(name,p,d,mat):
    bpy.ops.mesh.primitive_cube_add(size=1,location=p)
    ob=bpy.context.object
    ob.name=name
    ob.dimensions=d
    ob.data.materials.append(mat)
    return ob


block("Desktop sidewalk",(0,-2.2,.015),(220,2.25,.065),walk)
block("Desktop road",(0,-10.3,-.003),(220,14,.016),street)
person=[]
for x,z,w,d,h,mat in ((-2.25,.455,.155,.194,.82,navy),(-2.43,.455,.155,.194,.82,navy),
                     (-2.34,1.065,.36,.23,.47,shirt),(-2.34,1.47,.24,.23,.34,skin)):
    person.append(block("Scale figure", (x,-2.13,z),(w,d,h),mat))

bpy.ops.object.light_add(type="SUN")
sun=bpy.context.object
sun.rotation_euler=Vector((.56,.47,-.72)).to_track_quat("-Z","Y").to_euler()
sun.data.energy=2.46
sun.data.angle=.11
sun.data.color=(1,.90,.75)
bpy.ops.object.camera_add()
cam=bpy.context.object
scene.camera=cam
cam.data.clip_end=1400

base_objects=set(bpy.data.objects)
bpy.ops.import_scene.gltf(filepath=str(ROOT/"coastal-palm-mid.glb"))
mid=[ob for ob in bpy.data.objects if ob not in base_objects]
near=[ob for ob in base_objects if ob.name=="coastal_palm" or
      any(p.name=="coastal_palm" for p in [ob.parent,ob.parent.parent if ob.parent else None] if p)]
views=[
    ("day",(-12.7,-15.8,9.1),(.6,.3,4.7),42,False),
    ("street",(6.5,-12.25,1.62),(.57,.05,5.12),33,False),
    ("crown",(7.6,-10,11.7),(1.23,.19,8.23),48,False),
    ("rear",(12.4,15.0,7.1),(.48,.25,4.63),41,False),
    ("above",(0.8,-.20,21.2),(1.00,.29,6.31),39,False),
    ("mid",(-12.7,-15.8,9.1),(.6,.3,4.7),42,True)
]
for name,p,target,lens,simpler in views:
    for ob in near:
        ob.hide_render=simpler
    for ob in mid:
        ob.hide_render=not simpler
    for ob in person:
        ob.hide_render=name not in ("day","street","mid")
    cam.location=p
    cam.rotation_euler=(Vector(target)-cam.location).to_track_quat("-Z","Y").to_euler()
    cam.data.lens=lens
    scene.render.filepath=str(ROOT/"renders"/f"{name}.png")
    bpy.ops.render.render(write_still=True)
