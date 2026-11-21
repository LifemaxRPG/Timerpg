"""Render Alder at the design waterline, with a separate full-hull review."""
from pathlib import Path
import os

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT/"alder-sloop.glb"))
scene=bpy.context.scene
scene.render.engine="CYCLES"
scene.cycles.samples=16
scene.cycles.use_denoising=False
scene.cycles.max_bounces=2
scene.render.resolution_x=1080
scene.render.resolution_y=850
scene.view_settings.view_transform="AgX"
scene.view_settings.look="AgX - Medium High Contrast"

world=bpy.data.worlds.new("Coastal daylight")
world.use_nodes=True
scene.world=world
sky=world.node_tree.nodes.get("Background")
sky.inputs[0].default_value=(.63,.77,.87,1)
sky.inputs[1].default_value=.66
links=world.node_tree.links
nodes=world.node_tree.nodes
camera_sky=nodes.new("ShaderNodeBackground")
camera_sky.inputs[0].default_value=(.36,.57,.64,1)
camera_sky.inputs[1].default_value=.85
path=nodes.new("ShaderNodeLightPath")
mix=nodes.new("ShaderNodeMixShader")
links.new(path.outputs["Is Camera Ray"],mix.inputs[0])
links.new(sky.outputs[0],mix.inputs[1])
links.new(camera_sky.outputs[0],mix.inputs[2])
links.new(mix.outputs[0],nodes.get("World Output").inputs[0])

bpy.ops.mesh.primitive_plane_add(size=3300,location=(0,0,1.43))
sea=bpy.context.object
sea.name="Desktop review sea"
mat=bpy.data.materials.new("Pixel water context")
mat.use_nodes=True
sea.data.materials.append(mat)
nodes,links=mat.node_tree.nodes,mat.node_tree.links
bsdf=nodes.get("Principled BSDF")
bsdf.inputs["Roughness"].default_value=.53
tex=nodes.new("ShaderNodeTexCoord")
scale=nodes.new("ShaderNodeVectorMath")
scale.operation="MULTIPLY"
scale.inputs[1].default_value=(.74,1.95,.8)
noise=nodes.new("ShaderNodeTexNoise")
noise.inputs["Scale"].default_value=1.4
noise.inputs["Detail"].default_value=2
ramp=nodes.new("ShaderNodeValToRGB")
ramp.color_ramp.interpolation="CONSTANT"
ramp.color_ramp.elements[0].color=(.056,.142,.153,1)
ramp.color_ramp.elements[1].color=(.125,.243,.237,1)
ramp.color_ramp.elements[1].position=.79
ramp.color_ramp.elements.new(.35).color=(.065,.164,.172,1)
ramp.color_ramp.elements.new(.57).color=(.074,.188,.191,1)
links.new(tex.outputs["Object"],scale.inputs[0])
links.new(scale.outputs[0],noise.inputs["Vector"])
links.new(noise.outputs["Fac"],ramp.inputs[0])
links.new(ramp.outputs[0],bsdf.inputs["Base Color"])

# Small broken curves describe the desktop wake. Simulation and motion live elsewhere.
wake_verts=[]
wake_faces=[]
for i in range(23):
    y=-3.83+i*.68
    width=min(1.50,.25+.25*i)+max(0,i-11)*.20
    for side in (-1,1):
        x=side*width
        w=.055+max(0,i-7)*.009
        length=.50-(i%4)*.07
        n=len(wake_verts)
        wake_verts.extend([(x-w,y,1.439),(x+w,y+.035,1.439),(x+w*1.6+side*.075,y+length,1.439),
                           (x+side*.075,y+length*.94,1.439)])
        wake_faces.append(tuple(range(n,n+4)))
wake_geo=bpy.data.meshes.new("Broken wash")
wake_geo.from_pydata(wake_verts,[],wake_faces)
wake=bpy.data.objects.new("Desktop surface wake",wake_geo)
scene.collection.objects.link(wake)
for col in ((.47,.65,.60),(.20,.38,.36),(.28,.48,.46)):
    paint=bpy.data.materials.new("Wake colour")
    paint.diffuse_color=(*col,1)
    wake_geo.materials.append(paint)
for i,face in enumerate(wake_geo.polygons):
    face.material_index=(i//2)%3

bpy.ops.object.light_add(type="SUN")
sun=bpy.context.object
sun.rotation_euler=Vector((-.53,.37,-.67)).to_track_quat("-Z","Y").to_euler()
sun.data.angle=.22
sun.data.energy=2.36
sun.data.color=(1,.88,.70)

bpy.ops.object.camera_add()
cam=bpy.context.object
cam.data.clip_end=4700
scene.camera=cam
views=[
    ("day",(14.25,-9.7,10.0),(0,-.05,5.58),39,True),
    ("stern",(9.7,12.65,10.35),(0,.4,5.49),36,True),
    ("profile",(18.5,-.25,4.85),(0,0,5.54),37,True),
    ("cockpit",(2.55,4.88,4.65),(0,1.44,2.22),25,True),
    ("deck",(-1.33,-3.94,4.65),(.07,-.86,2.53),23,True),
    ("keel",(12.4,9.2,5.2),(0,.38,4.97),34,False),
    ("above",(3.4,4.7,19.6),(0,.10,2.79),32,False)
]
for name,eye,target,lens,afloat in views:
    selection=os.environ.get("ALDER_VIEWS")
    if selection and name not in selection.split(","):
        continue
    cam.location=eye
    cam.rotation_euler=(Vector(target)-cam.location).to_track_quat("-Z","Y").to_euler()
    cam.data.lens=lens
    sea.hide_render=not afloat
    wake.hide_render=not afloat
    camera_sky.inputs[0].default_value=(.36,.57,.64,1) if afloat else (.44,.53,.51,1)
    scene.render.filepath=str(ROOT/"renders"/f"{name}.png")
    bpy.ops.render.render(write_still=True)
