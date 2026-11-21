"""Render the exported passenger ship with a desktop-only ocean and lighting rig."""
import os
from pathlib import Path

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
bpy.ops.wm.read_factory_settings(use_empty=True)
ship_file=os.environ.get("COPPERWAKE_SHIP","copperwake")
classic=ship_file.startswith("ss-")
bpy.ops.import_scene.gltf(filepath=str(ROOT/f"{ship_file}.glb"))
scene=bpy.context.scene
scene.render.engine="CYCLES"
scene.cycles.samples=12
scene.cycles.use_denoising=False
scene.cycles.max_bounces=3
scene.render.resolution_x=1050
scene.render.resolution_y=630
scene.render.resolution_percentage=100
scene.view_settings.view_transform="AgX"
scene.camera=None

world=bpy.data.worlds.new("Maritime sky")
scene.world=world
world.use_nodes=True
nodes=world.node_tree.nodes
links=world.node_tree.links
ambient=nodes.get("Background")
visible=nodes.new("ShaderNodeBackground")
facing=nodes.new("ShaderNodeLightPath")
blend=nodes.new("ShaderNodeMixShader")
links.new(facing.outputs["Is Camera Ray"],blend.inputs[0])
links.new(ambient.outputs[0],blend.inputs[1])
links.new(visible.outputs[0],blend.inputs[2])
links.new(blend.outputs[0],nodes.get("World Output").inputs[0])
coord=nodes.new("ShaderNodeTexCoord")
split=nodes.new("ShaderNodeSeparateXYZ")
absolute=nodes.new("ShaderNodeMath")
absolute.operation="ABSOLUTE"
sky=nodes.new("ShaderNodeValToRGB")
links.new(coord.outputs["Normal"],split.inputs[0])
links.new(split.outputs["Z"],absolute.inputs[0])
links.new(absolute.outputs[0],sky.inputs[0])
links.new(sky.outputs[0],visible.inputs[0])
sky.color_ramp.elements[1].position=.48

bpy.ops.mesh.primitive_plane_add(size=16000,location=(0,0,8.3 if classic else 9.35))
sea=bpy.context.object
sea.name="Desktop ocean"
m=bpy.data.materials.new("Desktop slate sea")
m.use_nodes=True
sea.data.materials.append(m)
n=m.node_tree.nodes
l=m.node_tree.links
bsdf=n.get("Principled BSDF")
bsdf.inputs["Roughness"].default_value=.56
bsdf.inputs["Specular IOR Level"].default_value=.32
tex=n.new("ShaderNodeTexCoord")
mapping=n.new("ShaderNodeVectorMath")
mapping.operation="MULTIPLY"
mapping.inputs[1].default_value=(.085,.32,.16)
wave=n.new("ShaderNodeTexNoise")
wave.inputs["Scale"].default_value=1
wave.inputs["Detail"].default_value=2
sea_palette=n.new("ShaderNodeValToRGB")
sea_palette.color_ramp.interpolation="CONSTANT"
sea_palette.color_ramp.elements[0].color=(.035,.098,.117,1)
sea_palette.color_ramp.elements[1].position=.74
sea_palette.color_ramp.elements[1].color=(.085,.205,.228,1)
sea_palette.color_ramp.elements.new(.34).color=(.045,.129,.156,1)
sea_palette.color_ramp.elements.new(.56).color=(.055,.153,.18,1)
l.new(tex.outputs["Object"],mapping.inputs[0])
l.new(mapping.outputs[0],wave.inputs["Vector"])
l.new(wave.outputs["Fac"],sea_palette.inputs[0])
l.new(sea_palette.outputs[0],bsdf.inputs["Base Color"])

# Wake marks are context geometry, kept outside the ship export.
def matte(name,color):
    mat=bpy.data.materials.new(name)
    mat.diffuse_color=(*color,1)
    return mat

foam=matte("Desktop wake foam",(.49,.69,.67))
soft=matte("Desktop wake trail",(.22,.39,.40))
wake_mesh=bpy.data.meshes.new("Broken wake")
polys=[]
vertices=[]
for s in (-1,1):
    for i in range(20):
        y=148+i*10.7
        x=s*(7+i*1.92)
        w=max(.65,4.8-i*.165)
        start=len(vertices)
        vertices.extend([(x-w,y,8.34 if classic else 9.39),(x+w,y+2,8.34 if classic else 9.39),(x+w*.55,y+8.6,8.34 if classic else 9.39),(x-w*.53,y+7.1,8.34 if classic else 9.39)])
        polys.append(tuple(range(start,start+4)))
wake_mesh.from_pydata(vertices,[],polys)
wake=bpy.data.objects.new("Wake preview",wake_mesh)
scene.collection.objects.link(wake)
wake_mesh.materials.append(foam)
wake_mesh.materials.append(soft)
wake.hide_render="laid-up" in ship_file
for i,poly in enumerate(wake_mesh.polygons):
    poly.material_index=1 if i%4 in (1,3) else 0

bpy.ops.object.light_add(type="SUN")
sun=bpy.context.object
sun.rotation_euler=Vector((-.52,.64,-.79)).to_track_quat("-Z","Y").to_euler()
sun.data.angle=.12


def area(name,position,target,energy,color,size):
    data=bpy.data.lights.new(name,"AREA")
    ob=bpy.data.objects.new(name,data)
    scene.collection.objects.link(ob)
    ob.location=position
    ob.rotation_euler=(Vector(target)-ob.location).to_track_quat("-Z","Y").to_euler()
    data.color=color
    data.size=size
    return data,energy


lights=[]
top=41.744
for s in (-1,1):
    for y in (-94,-57,-19,20,55,88,126):
        lights.append(area("Evening promenade light",(s*18.15,y,29),(s*17.9,y,21.3),
                           2100,(1,.71,.38),3.2))
    for y in (-55,-9,16,33,64):
        lights.append(area("Evening deck light",(s*13.1,y,47),(s*8,y,top+.3),
                           1550,(1,.73,.44),3.0))
lights.append(area("Observation room lamp",(0,48,top+3.16),(0,48,top+.3),650,(1,.75,.47),2.8))
lights.append(area("Bridge wash",(0,-106,48),(0,-96,45),3950,(.71,.82,1),7))

# These block figures are scale cues in the desktop review.
coats=[matte("Scale navy",(.07,.12,.16)),matte("Scale copper",(.38,.15,.08))]
skin=matte("Scale face",(.58,.40,.23))
for x,y,z,h,i in ([] if classic else [(-17.8,-73,21.26,1.7,0),(17.7,91,21.26,1.63,1),(5,-7,top+.11,1.75,0),
                   (5.3,17,top+.11,1.65,1),(-3,42,top+.225,1.72,0),(9,64,top+.11,1.68,1),
                   (-3,50,top+.225,1.64,1),(-5,-124,21.26,1.76,0)]):
    for dx,zz,dims,material in [
        (-.081,.24,(.15,.19,h*.48),coats[0]),(.081,.24,(.15,.19,h*.48),coats[0]),
        (0,.64,(.39,.25,h*.37),coats[i]),(0,.91,(.22,.21,h*.18),skin)
    ]:
        bpy.ops.mesh.primitive_cube_add(size=1,location=(x+dx,y,z+h*zz))
        bpy.context.object.dimensions=dims
        bpy.context.object.data.materials.append(material)

bpy.ops.object.camera_add()
cam=bpy.context.object
cam.data.clip_end=13000
scene.camera=cam
scene_light={m.name:m.node_tree.nodes.get("Principled BSDF") for m in bpy.data.materials
             if m.use_nodes and m.name in ("glow_amber","glow_signals","wall_balcony_bays",
                                           "wall_balconies","wall_hull","glass_passenger")}
bpy.ops.mesh.primitive_cube_add(size=1,location=(37,-39,7.75 if classic else 8.65))
dock=bpy.context.object
dock.name="Desktop only pier"
dock.dimensions=(26,135,8)
dock.data.materials.append(matte("Pier concrete",(.30,.30,.26)))
pier=[dock]
for x,y in ((24.1,-80),(24.1,-62),(24.1,-44),(24.1,-26),(24.1,-8)):
    bpy.ops.mesh.primitive_cylinder_add(vertices=8,radius=.37,depth=.88,
                     location=(x,y,12.12 if classic else 13.03))
    ob=bpy.context.object
    ob.data.materials.append(matte("Cast dock bollard",(.13,.16,.16)))
    pier.append(ob)
views=[
    ("day",(230,-248,122),(0,1,24),39,False),
    ("side",(396,-22,11.1),(0,0,23.1),42,False),
    ("night",(396,-22,11.1),(0,0,23.1),42,True),
    ("stern",(149,306,147),(0,9,25),40,False),
    ("above",(85,-73,340),(0,0,24),35,False),
    ("pier",(29,-104,14.5),(0,-62,21),23,False),
    ("bow",(61,-153,18.3),(8,-119,18.4),45,False),
    ("funnels",(24,-49,52),(0,-40,48),39,False),
    ("promenade",(17.82,-76,22.96),(17.34,-33,24.2),21,False),
    ("pool",(10.5,33,43.55),(-2,-4,47.1),24,False),
    ("lounge",(1.6,55.2,43.67),(-1.6,43.5,43.27),22,True)
]
if classic:
    views=[
        ("day",(201,-224,95),(0,0,20),38,False),
        ("side",(342,-17,9.95),(0,0,19),43,False),
        ("night",(342,-17,9.95),(0,0,19),43,True),
        ("pier",(31,-104,13.4),(0,-61,17.5),23,False),
        ("bow",(58,-138,17.3),(7,-103,14.5),39,False),
        ("funnels",(25,-47,48),(0,-15,35),26,False),
        ("pool",(4,91,26.53),(0,78,27.0),24,False),
    ]
    for s in (-1,1):
        for y in (-82,-51,-17,15,44,76):
            lights.append(area("Liner promenade wash",(s*17,y,26),(s*14.5,y,17.66),
                               1650,(1,.73,.46),3.2))
selected=os.environ.get("COPPERWAKE_VIEWS","").split(",")
for name,position,target,lens,night in views:
    if selected!=[""] and name not in selected:
        continue
    cam.location=position
    cam.rotation_euler=(Vector(target)-cam.location).to_track_quat("-Z","Y").to_euler()
    cam.data.lens=lens
    ambient.inputs[0].default_value=(.19,.28,.45,1) if night else (.75,.84,.94,1)
    ambient.inputs[1].default_value=.46 if night else .74
    sky.color_ramp.elements[0].color=(.075,.12,.22,1) if night else (.59,.72,.75,1)
    sky.color_ramp.elements[1].color=(.012,.033,.087,1) if night else (.17,.37,.57,1)
    sun.data.energy=.31 if night else 2.5
    sun.data.color=(.48,.62,1) if night else (1,.91,.76)
    for material,shader in scene_light.items():
        strengths={"glow_amber":(2.0,.25),"glow_signals":(1.15,.30),"wall_hull":(.95,0),
                   "wall_balcony_bays":(1.4,0),"wall_balconies":(1.4,0),"glass_passenger":(1.45,0)}
        shader.inputs["Emission Strength"].default_value=strengths[material][0 if night else 1]
    for ob in pier:
        ob.hide_render=name!="pier" and "laid-up" not in ship_file
    for data,energy in lights:
        data.energy=energy if night else 0
    scene.render.filepath=str(ROOT/"renders"/f"{'laid-up-' if 'laid-up' in ship_file else '1952-' if classic else ''}{name}.png")
    bpy.ops.render.render(write_still=True)
