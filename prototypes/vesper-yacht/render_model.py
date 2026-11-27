"""Render the yacht at its design waterline, with additional deck and hull reviews."""
from pathlib import Path
import os

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT/"vesper-yacht.glb"))
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

bpy.ops.mesh.primitive_plane_add(size=3300,location=(0,0,2.10))
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
for i in range(28):
    y=-18.8+i*2.67
    width=min(4.13,.59+.54*i)+max(0,i-12)*.39
    for side in (-1,1):
        x=side*width
        w=.13+max(0,i-7)*.018
        length=1.95-(i%4)*.27
        n=len(wake_verts)
        wake_verts.extend([(x-w,y,2.109),(x+w,y+.035,2.109),(x+w*1.6+side*.075,y+length,2.109),
                           (x+side*.075,y+length*.94,2.109)])
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

def material(name,color):
    m=bpy.data.materials.new(name)
    m.diffuse_color=(*color,1)
    return m


shirt=material("Preview navy coat",(.125,.20,.24))
pants=material("Preview trousers",(.079,.12,.13))
skin=material("Preview skin",(.60,.40,.23))
people=[]
for x,y,z in ((-1.66,11.76,4.46),(.92,8.6,4.46),(.60,19.6,2.72),(0.75,4.80,4.46),(-.38,-1.7,9.81)):
    for dx,zz,w,d,h,mat in ((-.095,.38,.15,.17,.76,pants),(.095,.38,.15,.17,.76,pants),
                            (0,1.04,.38,.26,.51,shirt),(0,1.51,.235,.215,.32,skin)):
        bpy.ops.mesh.primitive_cube_add(size=1,location=(x+dx,y,z+zz))
        ob=bpy.context.object
        ob.dimensions=(w,d,h)
        ob.data.materials.append(mat)
        people.append(ob)


def warm(name,position,target,power,size):
    data=bpy.data.lights.new(name,"AREA")
    data.color=(1,.77,.43)
    data.size=size
    ob=bpy.data.objects.new(name,data)
    scene.collection.objects.link(ob)
    ob.location=position
    ob.rotation_euler=(Vector(target)-ob.location).to_track_quat("-Z","Y").to_euler()
    return data,power


lamps=[
    warm("Salon warm wash",(-.94,-2,6.68),(.1,-2,4.5),110,1.3),
    warm("Salon rear light",(1.0,3.72,6.79),(.2,4.4,4.46),135,1.2),
    warm("Terrace warm spill",(-.15,7.44,6.8),(0,11.25,4.46),180,1.6),
    warm("Flybridge warm rail",(.34,-1.6,11.7),(0,.4,9.81),125,1.0),
    warm("Beach platform wash",(0,15.89,4.3),(0,19,2.74),145,.85)]
bpy.ops.object.camera_add()
cam=bpy.context.object
cam.data.clip_end=4700
scene.camera=cam
views=[
    ("day",(39,-43,25),(0,.25,6.0),48,True,False),
    ("stern",(29,44,21),(0,1.9,5.2),47,True,False),
    ("dusk",(-27,41,16),(0,3.0,5.45),37,True,True),
    ("beach",(7.8,25.0,4.34),(.1,15.58,3.85),24,True,False),
    ("flybridge",(1.66,2.46,11.37),(.18,-5.96,10.32),24,True,False),
    ("salon",(.98,4.77,6.0),(-1.10,-2.55,5.35),23,True,True),
    ("above",(8,-4,74),(0,.0,6.4),40,False,False),
    ("underbody",(10.5,28,.61),(0,14.8,1.65),48,False,False)
]
for name,eye,target,lens,afloat,night in views:
    selection=os.environ.get("VESPER_VIEWS")
    if selection and name not in selection.split(","):
        continue
    cam.location=eye
    cam.rotation_euler=(Vector(target)-cam.location).to_track_quat("-Z","Y").to_euler()
    cam.data.lens=lens
    sea.hide_render=not afloat
    wake.hide_render=not afloat or name=="beach"
    for ob in people:
        ob.hide_render=name in ("above","underbody")
    camera_sky.inputs[0].default_value=(.08,.15,.23,1) if night else (.36,.57,.64,1) if afloat else (.44,.53,.51,1)
    sky.inputs[0].default_value=(.24,.40,.66,1) if night else (.63,.77,.87,1)
    sky.inputs[1].default_value=.45 if night else .66
    sun.data.energy=.22 if night else 2.36
    sun.data.color=(.59,.73,1) if night else (1,.88,.70)
    for data,power in lamps:
        data.energy=power if night else power*.035
    for key,energy in (("glass_tinted",.27 if night else .08),("glow_deck_lamps",1.18 if night else .23)):
        bpy.data.materials[key].node_tree.nodes.get("Principled BSDF").inputs["Emission Strength"].default_value=energy
    scene.render.filepath=str(ROOT/"renders"/f"{name}.png")
    bpy.ops.render.render(write_still=True)
