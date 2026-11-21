"""Render the exported era states with a desktop-only ocean and lantern light rig."""
from math import cos, sin, pi
from pathlib import Path
import json
import os

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
state=os.environ.get("NEEDLE_STATE","historic")
info=json.loads((ROOT/f"model-info-{state}.json").read_text())
modern=state=="modern"
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT/info["file"]))
scene=bpy.context.scene
scene.render.engine="CYCLES"
scene.cycles.samples=12
scene.cycles.use_denoising=False
scene.cycles.max_bounces=3
scene.render.resolution_x=1240
scene.render.resolution_y=1000
scene.view_settings.view_transform="AgX"
scene.view_settings.look="AgX - Medium High Contrast"

world=bpy.data.worlds.new("Maritime sky")
world.use_nodes=True
scene.world=world
n,l=world.node_tree.nodes,world.node_tree.links
ambient=n.get("Background")
visible=n.new("ShaderNodeBackground")
path=n.new("ShaderNodeLightPath")
switch=n.new("ShaderNodeMixShader")
l.new(path.outputs["Is Camera Ray"],switch.inputs[0])
l.new(ambient.outputs[0],switch.inputs[1])
l.new(visible.outputs[0],switch.inputs[2])
l.new(switch.outputs[0],n.get("World Output").inputs[0])

def flat(name,color,rough=.90):
    m=bpy.data.materials.new(name)
    m.use_nodes=True
    shader=m.node_tree.nodes.get("Principled BSDF")
    shader.inputs["Base Color"].default_value=(*color,1)
    shader.inputs["Roughness"].default_value=rough
    return m

def poly(name,verts,faces,mat):
    geo=bpy.data.meshes.new(name)
    geo.from_pydata(verts,[],faces)
    geo.update()
    obj=bpy.data.objects.new(name,geo)
    scene.collection.objects.link(obj)
    geo.materials.append(mat)
    return obj

def surface(name,colors,scales):
    mat=flat(name,colors[0])
    nodes,links=mat.node_tree.nodes,mat.node_tree.links
    coord=nodes.new("ShaderNodeNewGeometry")
    map_node=nodes.new("ShaderNodeVectorMath");map_node.operation="MULTIPLY"
    map_node.inputs[1].default_value=scales
    grid=nodes.new("ShaderNodeVectorMath");grid.operation="FLOOR"
    noise=nodes.new("ShaderNodeTexWhiteNoise")
    ramp=nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.interpolation="CONSTANT"
    es=ramp.color_ramp.elements
    es[0].color=(*colors[0],1)
    es[1].position=.80
    es[1].color=(*colors[-1],1)
    for i,color in enumerate(colors[1:-1],1):
        es.new(.26*i).color=(*color,1)
    links.new(coord.outputs["Position"],map_node.inputs[0])
    links.new(map_node.outputs[0],grid.inputs[0])
    links.new(grid.outputs[0],noise.inputs[0])
    links.new(noise.outputs["Value"],ramp.inputs[0])
    links.new(ramp.outputs[0],nodes.get("Principled BSDF").inputs["Base Color"])
    return mat

water_z=info["recommended_waterline_m"]
sea=surface("Review blue water",[(.045,.140,.159),(.055,.164,.178),(.059,.179,.192),(.068,.196,.204)],(1.5,4.8,1))
poly("Desktop sea",[(-1200,-1200,water_z),(1200,-1200,water_z),(1200,1200,water_z),(-1200,1200,water_z)],
     [(0,1,2,3)],sea)
sod=surface("Review mainland grass",[(.146,.168,.102),(.177,.198,.125),(.191,.210,.142),(.205,.218,.154)],(12.8,12.8,1))
mainland=[(-130,-180,-.023),(140,-180,-.023),(29,-29,-.023),(21,-18,-.023),
          (17,-8,-.023),(11,.52,-.023),(-13.07,.24,-.023),(-17.5,-6.96,-.023),
          (-23.5,-17.14,-.023),(-31,-31,-.023)]
poly("Desktop connected mainland",mainland,[tuple(range(len(mainland)))],sod)
mainland_bottom=[(x*1.12,y*.992,-7.35+.49*sin(i*1.2)) for i,(x,y,z) in enumerate(mainland)]
rock=surface("Review mainland basalt",[(.103,.119,.114),(.123,.136,.125),(.143,.151,.137),(.158,.164,.143)],(8.6,12,11))
n=len(mainland)
poly("Desktop rocky mainland below grade",mainland_bottom+mainland,
     [(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n) if i!=5],rock)
pavement=surface("Review path extension",[(.273,.277,.217),(.298,.295,.235),(.308,.305,.244)],(13,13,1))
poly("Desktop path continuation",[(-1.10,-27,.039),(1.10,-27,.039),(1.10,.02,.039),(-1.10,.02,.039)],[(0,1,2,3)],pavement)
surf=surface("Desktop wave foam",[(.109,.226,.219),(.156,.284,.251),(.201,.344,.308),(.27,.392,.355)],(6.2,11,1))
outline=[(15.7,2),(17.20,8),(17.50,14),(14.55,22.3),(12.75,27.4),(8.2,33.65),
         (1.3,37),(-4.2,37.22),(-10,34.1),(-15.4,30.5),(-17.8,25.3),(-18.4,17.4),(-18.5,9.25),(-17.1,3.0)]
for tier in (1,2,3):
    verts=[]
    for i,(x,y) in enumerate(outline):
        center=Vector((x,y-15.8))
        d=center.normalized()
        offset=tier*.43+.11*sin(i*2.9+tier)
        v=center+d*offset
        for shift in (0,.078+.13*(.5+.5*sin(i*1.55+tier))):
            q=v+d*shift
            verts.append((q.x,q.y+15.8,water_z+.014+tier*.002))
    faces=[(i*2,i*2+1,i*2+3,i*2+2) for i in range(len(outline)-1) if (i+tier*3)%5!=0]
    poly("Broken stepped surf lines",verts,faces,surf)

bpy.ops.object.light_add(type="SUN")
sun=bpy.context.object
sun.rotation_euler=Vector((-.54,.44,-.72)).to_track_quat("-Z","Y").to_euler()
sun.data.angle=.18

def lamp(name,pos,energy,color,radius):
    data=bpy.data.lights.new(name,"POINT")
    data.color=color
    data.shadow_soft_size=radius
    obj=bpy.data.objects.new(name,data)
    obj.location=pos
    scene.collection.objects.link(obj)
    return data,energy

locator=bpy.data.objects["beacon_beam"]
bpy.context.view_layer.update()
start=locator.matrix_world.translation
x,y,z=info["beacon_beam"]["direction"]
aim=Vector((x,-z,y)).normalized()
tone=(1,.84,.55) if modern else (1,.65,.29)
lamps=[lamp("Optical glow",start,410,tone,.55),
       lamp("Keeper entrance warm spill",(-8.14,8.63,1.9) if not modern else (5.45,11.18,2.24),125,(1,.74,.43),.26),
       lamp("Entry doorstep",(.12,21.55,1.95),135,(1,.76,.5),.27)]
# The projected light is review context. Its axis and start come from the exported marker.
right=aim.cross(Vector((0,0,1))).normalized()
up=right.cross(aim)
vertices=[]
for dist,r in ((.54,.21),(7.9,.51),(20,1.18),(34,2.10)):
    for i in range(8):
        t=2*pi*i/8
        vertices.append(start+aim*dist+r*(cos(t)*right+sin(t)*up))
faces=[(i*8+j,i*8+(j+1)%8,(i+1)*8+(j+1)%8,(i+1)*8+j) for i in range(3) for j in range(8)]
m=bpy.data.materials.new("Desktop atmospheric beacon")
m.use_nodes=True
n,l=m.node_tree.nodes,m.node_tree.links
n.clear()
out=n.new("ShaderNodeOutputMaterial")
mix=n.new("ShaderNodeMixShader")
trans=n.new("ShaderNodeBsdfTransparent")
em=n.new("ShaderNodeEmission")
em.inputs["Color"].default_value=(*tone,1)
em.inputs["Strength"].default_value=.90
uv=n.new("ShaderNodeUVMap")
sep=n.new("ShaderNodeSeparateXYZ")
fade=n.new("ShaderNodeValToRGB")
fade.color_ramp.elements[0].color=(.105,.105,.105,1)
fade.color_ramp.elements[1].color=(0,0,0,1)
fade.color_ramp.elements.new(.45).color=(.04,.04,.04,1)
l.new(uv.outputs[0],sep.inputs[0])
l.new(sep.outputs["Y"],fade.inputs[0])
l.new(fade.outputs[0],mix.inputs[0])
l.new(trans.outputs[0],mix.inputs[1])
l.new(em.outputs[0],mix.inputs[2])
l.new(mix.outputs[0],out.inputs[0])
beam=poly("Desktop swept beam",vertices,faces,m)
layer=beam.data.uv_layers.new()
for face in beam.data.polygons:
    for index in face.loop_indices:
        vertex=beam.data.loops[index].vertex_index
        layer.data[index].uv=((vertex%8)/8,(0,.21,.60,1)[vertex//8])

bpy.ops.object.camera_add()
cam=bpy.context.object
cam.data.clip_end=1900
scene.camera=cam
views={
 "day":((40,-40,36),(0,15.7,11.25),53,False),
 "night":((40,-40,36),(6,17,12.05),60,True),
 "rear":((37,49,31),(-1,20,11.40),49,False),
 "eye":((8,-11,1.83),(-.5,19.3,9.7),0,False),
}
selection=os.environ.get("NEEDLE_VIEWS")
for name,(eye,target,width,night) in views.items():
    if selection and name not in selection.split(","):
        continue
    cam.location=eye
    cam.rotation_euler=(Vector(target)-cam.location).to_track_quat("-Z","Y").to_euler()
    cam.data.type="ORTHO" if width else "PERSP"
    cam.data.ortho_scale=width or 1
    cam.data.lens=27
    visible.inputs[0].default_value=(.047,.086,.13,1) if night else (.42,.61,.68,1)
    visible.inputs[1].default_value=.72
    ambient.inputs[0].default_value=(.23,.39,.64,1) if night else (.67,.77,.87,1)
    ambient.inputs[1].default_value=.63 if night else .73
    sun.data.energy=.48 if night else 2.35
    sun.data.color=(.57,.71,1) if night else (1,.88,.71)
    beam.hide_render=not night
    for light,energy in lamps:
        light.energy=energy if night else 0
    optic=bpy.data.materials["glow_lens"].node_tree.nodes.get("Principled BSDF")
    optic.inputs["Emission Strength"].default_value=2.4 if night else .18
    scene.render.filepath=str(ROOT/"renders"/f"{state}-{name}.png")
    bpy.ops.render.render(write_still=True)
