"""Render sample faces on a reconstruction of WorldPropKit's panel and supports."""
from pathlib import Path

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
bpy.ops.wm.read_factory_settings(use_empty=True)
scene=bpy.context.scene

def material(name,color):
    mat=bpy.data.materials.new(name)
    mat.diffuse_color=(*color,1)
    mat.use_nodes=True
    node=mat.node_tree.nodes['Principled BSDF']
    node.inputs['Base Color'].default_value=(*color,1)
    node.inputs['Roughness'].default_value=.92
    return mat

def box(name,location,size,mat):
    bpy.ops.mesh.primitive_cube_add(size=1,location=location)
    obj=bpy.context.object
    obj.name=name
    obj.dimensions=size
    obj.data.materials.append(mat)
    return obj

grass=material('Grass',(.18,.26,.135))
dry=material('Dry shoulder',(.35,.33,.25))
road=material('Country road',(.105,.10,.088))
line=material('Road paint',(.61,.48,.21))
wood=material('Painted wood',(.23,.165,.104))
steel=material('Greyscale steel',(.15,.167,.178))
wood_back=material('Backing boards',(.25,.211,.153))
leaves=material('Tree leaves',(.12,.21,.098))
box('Grassy verge',(0,0,-.13),(210,160,.25),grass)
box('Road shoulder',(0,-8,-.035),(180,9.3,.075),dry)
box('Two lane highway',(0,-8,-.012),(180,7.4,.054),road)
for x in range(-56,57,6):
    box('Paint dash',(x,-8,.021),(2.8,.083,.013),line)
for y in (-11.36,-4.65):
    box('Edge line',(0,y,.020),(150,.085,.012),line)
for x,y,h in ((-13,7,4.6),(-8,14,5.4),(13,11,5.7),(6,16,4.8),(19,7,5),(22,14,5.5),(-22,15,6)):
    box('Tree trunk',(x,y,h*.43),(.29,.32,h*.86),wood)
    bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=1,radius=1,location=(x,y,h))
    tree=bpy.context.object
    tree.name='Faceted crown'
    tree.scale=(2.2,1.8,2.45)
    tree.data.materials.append(leaves)

panel=box('Board backing',(0,.064,4.32),(10,.12,3.44),wood_back)
posts=[box('Board post',(x,.15,2.77),(.24,.24,6.14),wood) for x in (-3.4,3.4)]
rail=box('Steel lower rail',(0,-.30,2.5),(9,.08,.08),steel)
img_mat=material('Nearest board face',(1,1,1))
image=img_mat.node_tree.nodes.new('ShaderNodeTexImage')
image.interpolation='Closest'
shader=img_mat.node_tree.nodes['Principled BSDF']
img_mat.node_tree.links.new(image.outputs['Color'],shader.inputs['Base Color'])
mesh=bpy.data.meshes.new('10 by 3.44 metre face')
mesh.from_pydata([(-5,-.001,2.6),(5,-.001,2.6),(5,-.001,6.04),(-5,-.001,6.04)],[],[(0,1,2,3)])
mesh.update()
uv=mesh.uv_layers.new()
for i,coords in enumerate(((0,0),(1,0),(1,1),(0,1))):
    uv.data[i].uv=coords
obj=bpy.data.objects.new('Billboard face',mesh)
bpy.context.collection.objects.link(obj)
mesh.materials.append(img_mat)
scene.world=bpy.data.worlds.new('Hazy coast sky')
scene.world.use_nodes=True
background=scene.world.node_tree.nodes['Background']
background.inputs['Color'].default_value=(.61,.73,.81,1)
background.inputs['Strength'].default_value=.69
bpy.ops.object.light_add(type='SUN',rotation=(.45,-.48,-.55))
bpy.context.object.data.energy=2.3
bpy.context.object.data.angle=.11
bpy.ops.object.camera_add()
cam=bpy.context.object
cam.data.lens=45
scene.camera=cam
scene.render.engine='CYCLES'
scene.cycles.samples=16
scene.cycles.use_denoising=False
scene.render.resolution_x=940
scene.render.resolution_y=570
scene.render.image_settings.file_format='JPEG'
for key,loc,target,old in (
    ('head-light-weathered',(10,-22,4.25),(0,-2,3.2),True),
    ('family-drive',(-10,-23,3.6),(0,-1,3.2),False),
    ('surf-break',(7,-22,3.95),(0,-1,3.1),False),
    ('modern-next-exit',(-8,-23,3.45),(0,-2,3.0),False),
):
    for post in posts:
        post.data.materials[0]=wood if old else steel
    panel.data.materials[0]=wood_back if old else steel
    rail.hide_render=old
    image.image=bpy.data.images.load(str(ROOT/'faces'/(key+'.png')))
    cam.location=loc
    cam.rotation_euler=(Vector(target)-cam.location).to_track_quat('-Z','Y').to_euler()
    scene.render.filepath=str(ROOT/('road-'+key+'.jpg'))
    bpy.ops.render.render(write_still=True)
