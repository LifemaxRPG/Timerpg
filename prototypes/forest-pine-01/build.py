from pathlib import Path
import math
import random
import bpy
from mathutils import Vector

OUT = Path(__file__).resolve().parent
rng = random.Random(129)
bpy.ops.object.select_all(action='SELECT')
bpy.ops.object.delete()
scene = bpy.context.scene

def material(name, colors, bark=False):
    m = bpy.data.materials.new(name)
    m.diffuse_color = (*[c/255 for c in colors[1]],1)
    m.use_nodes = True
    image = bpy.data.images.new(name,64,64,alpha=False)
    pixels = []
    phase = [rng.randrange(19) for _ in range(32)]
    for y in range(64):
        for x in range(64):
            ax, ay = x//2, y//2
            if bark:
                f = (ax*7+phase[(ay//4)%8]+(ay//11))%17
            else:
                f = (ax*7+ay*11+(ax//3)*ay*3)%19
            rgb = colors[0 if f < 3 else 1 if f < 13 else 2]
            pixels.extend([v/255 for v in rgb]+[1])
    image.pixels.foreach_set(pixels)
    image.pack()
    node = m.node_tree.nodes.new('ShaderNodeTexImage')
    node.image = image
    node.interpolation = 'Closest'
    node.extension = 'REPEAT'
    m.node_tree.links.new(node.outputs['Color'],m.node_tree.nodes['Principled BSDF'].inputs['Base Color'])
    m.node_tree.nodes['Principled BSDF'].inputs['Roughness'].default_value = .92
    return m

bark = material('bark_pine',[(67,49,40),(94,69,50),(124,91,61)],True)
shade = material('needles_shadow',[(39,61,47),(53,80,57),(63,91,62)])
light = material('needles_light',[(55,79,48),(70,99,61),(92,117,68)])

def mesh(name, vertices, faces, roles, mats):
    data = bpy.data.meshes.new(name)
    data.from_pydata(vertices,[],faces)
    data.update()
    obj = bpy.data.objects.new(name,data)
    scene.collection.objects.link(obj)
    for m in mats:
        data.materials.append(m)
    uv = data.uv_layers.new()
    for poly,role in zip(data.polygons,roles):
        poly.material_index = role
        pts = [data.vertices[i].co for i in poly.vertices]
        u = (pts[1]-pts[0]).normalized()
        v = poly.normal.cross(u)
        for li,p in zip(poly.loop_indices,pts):
            uv.data[li].uv = ((p-pts[0]).dot(u)/(64*.0388),(p-pts[0]).dot(v)/(64*.0388))
    return obj

def stem(z):
    return (.28*(z/17)**1.8,-.20*(z/17)**1.35)

stem_rings = [(0,.40),(.54,.27),(4.0,.22),(8.0,.163),(12.0,.103),(16.8,.027)]
n = 8
vertices = [(stem(z)[0]+r*math.cos(i*math.tau/n),stem(z)[1]+r*math.sin(i*math.tau/n),z)
            for z,r in stem_rings for i in range(n)]
faces = [(j*n+i,j*n+(i+1)%n,(j+1)*n+(i+1)%n,(j+1)*n+i)
         for j in range(len(stem_rings)-1) for i in range(n)]
faces += [tuple(reversed(range(n))),tuple(range((len(stem_rings)-1)*n,len(stem_rings)*n))]
mesh('Pine trunk',vertices,faces,[0]*len(faces),[bark])

# A different outline at each crown tier keeps the whorls readable from any azimuth.
vertices,faces,roles = [],[],[]
tiers = [(6.35,2.28,4.13),(8.08,2.15,3.80),(9.81,1.82,3.42),
         (11.42,1.53,3.10),(12.98,1.17,2.73),(14.36,.83,2.84)]
for index,(z,r,height) in enumerate(tiers):
    start = len(vertices)
    n = 12
    xy = stem(z)
    angle = index*1.47
    for kind in range(2):
        for i in range(n):
            t = angle+i*math.tau/n
            scale = ((1 if i%2==0 else .72)*rng.uniform(.84,1.10) if kind==0 else .43)
            zz = z+rng.uniform(-.29,.31) if kind==0 else z+height*.55+rng.uniform(-.14,.14)
            vertices.append((xy[0]+r*scale*math.cos(t),xy[1]+r*scale*math.sin(t),zz))
    vertices.extend([(*stem(z+height),z+height),(*xy,z+.26)])
    for i in range(n):
        j = (i+1)%n
        faces.extend(((start+i,start+j,start+n+j,start+n+i),
                      (start+n+i,start+n+j,start+2*n),
                      (start+j,start+i,start+2*n+1)))
        roles.extend((1 if (i+index)%5 in (0,1) else 0,1,0))
mesh('Layered pine crown',vertices,faces,roles,[shade,light])
bpy.ops.export_scene.gltf(filepath=str(OUT/'pine-01.glb'),export_yup=True)
bpy.context.preferences.filepaths.save_version = 0
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'pine-01.blend'))

bpy.ops.mesh.primitive_plane_add(size=180)
ground = bpy.data.materials.new('Preview earth')
ground.diffuse_color = (.24,.27,.17,1)
bpy.context.object.data.materials.append(ground)
scene.world.use_nodes = True
background = scene.world.node_tree.nodes['Background']
background.inputs[0].default_value = (.42,.56,.68,1)
background.inputs[1].default_value = .65
bpy.ops.object.light_add(type='SUN')
bpy.context.object.rotation_euler = (.48,-.37,.07)
bpy.context.object.data.energy = 3.0
bpy.context.object.data.angle = .20
bpy.ops.object.camera_add(location=(19,-29,11))
scene.camera = bpy.context.object
scene.camera.rotation_euler = (Vector((0,0,8.1))-scene.camera.location).to_track_quat('-Z','Y').to_euler()
scene.camera.data.type = 'ORTHO'
scene.camera.data.ortho_scale = 19.7
scene.render.engine = 'CYCLES'
scene.cycles.samples = 24
scene.cycles.use_denoising = False
scene.render.resolution_percentage = 100
scene.render.resolution_x = 640
scene.render.resolution_y = 840
scene.render.image_settings.file_format = 'JPEG'
scene.render.filepath = str(OUT/'pine-01.jpg')
bpy.ops.render.render(write_still=True)
