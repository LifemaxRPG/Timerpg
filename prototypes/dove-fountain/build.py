"""Build the three fountain detail levels and the Blender review sheet views."""
import json
import math
import sys
from pathlib import Path
from random import Random

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
texel=.0388
bpy.ops.wm.read_factory_settings(use_empty=True)
materials={}

def material(name, color, noise, seed, emission=0):
    mat=bpy.data.materials.new(name)
    mat.use_nodes=True
    shader=mat.node_tree.nodes.get('Principled BSDF')
    shader.inputs['Roughness'].default_value=.82
    rng=Random(seed)
    blocks=[]
    for y in range(16):
        for x in range(16):
            shift=rng.choices((-noise,-noise//2,0,noise//2,noise),(1,3,12,3,1))[0]
            blocks.append(tuple(max(0,min(255,c+shift))/255 for c in color)+(1,))
    image=bpy.data.images.new(name,32,32,alpha=False)
    image.pixels.foreach_set([value for y in range(32) for x in range(32)
                              for value in blocks[(y//2)*16+x//2]])
    image.pack()
    tex=mat.node_tree.nodes.new('ShaderNodeTexImage')
    tex.image=image
    tex.interpolation='Closest'
    mat.node_tree.links.new(tex.outputs['Color'],shader.inputs['Base Color'])
    if emission:
        mat.node_tree.links.new(tex.outputs['Color'],shader.inputs['Emission Color'])
        shader.inputs['Emission Strength'].default_value=emission
    mat.diffuse_color=(*(c/255 for c in color),1)
    materials[name]=mat
    return mat

paving=material('ground_shell_pavers',(132,119,99),17,1)
stone=material('wall_shell_limestone',(180,161,126),16,2)
edge=material('wall_stone_shadow',(118,97,77),15,3)
bronze=material('bronze_patina',(71,110,94),15,4)
high=material('bronze_rubbed_edge',(111,137,110),12,5)
shadow=material('bronze_shadow',(47,66,56),13,6)
water=material('water_basin',(66,123,124),13,7)
jet=material('water_jet',(133,184,172),12,8)
wood=material('bench_wood',(110,78,56),13,9)
iron=material('ironwork',(47,55,47),8,10)
glow=material('glow_lantern',(246,184,103),9,11,2.4)
dark=material('bronze_eye',(33,49,41),4,12)

def mesh(name,verts,faces,mat):
    data=bpy.data.meshes.new(name)
    data.from_pydata(verts,[],faces)
    data.update()
    obj=bpy.data.objects.new(name,data)
    bpy.context.collection.objects.link(obj)
    data.materials.append(mat)
    uv=data.uv_layers.new(name='Nearest texels')
    for poly in data.polygons:
        n=poly.normal
        ref=Vector((0,1,0)) if abs(n.x)>.75 else Vector((1,0,0))
        u=(ref-n*ref.dot(n)).normalized()
        v=n.cross(u)
        for i in poly.loop_indices:
            point=data.vertices[data.loops[i].vertex_index].co
            uv.data[i].uv=(point.dot(u)/(32*texel),point.dot(v)/(32*texel))
    return obj

def shell(name,rings,mat,close=True,cap=True):
    sides=len(rings[0])
    faces=[(a*sides+b,a*sides+(b+1)%sides,(a+1)*sides+(b+1)%sides,(a+1)*sides+b)
           for a in range(len(rings)-1) for b in range(sides if close else sides-1)]
    if cap:
        faces.extend((tuple(range(sides-1,-1,-1)),tuple(range((len(rings)-1)*sides,len(rings)*sides))))
    return mesh(name,sum(rings,[]),faces,mat)

def levels(name, rings, mat, sides=12, center=(0,0), lobes=False, cap=True):
    cx,cy=center
    def point(a,r,z):
        if lobes:
            r*=1+.075*math.cos(8*a)
        return (cx+r*math.cos(a),cy+r*math.sin(a),z)
    return shell(name,[[point(i*math.tau/sides,r,z) for i in range(sides)]
                       for r,z in rings],mat,cap=cap)

def cube(name,center,size,mat,yaw=0):
    cos,sin=math.cos(yaw),math.sin(yaw)
    cx,cy,cz=center
    sx,sy,sz=[d*.5 for d in size]
    rings=[[(cx+cos*x-sin*y,cy+sin*x+cos*y,cz+z)
            for x,y in ((-sx,-sy),(sx,-sy),(sx,sy),(-sx,sy))]
           for z in (-sz,sz)]
    return shell(name,rings,mat)

def volume(name, stations, mat, n):
    return shell(name,[[(cx+rx*math.cos(i*math.tau/n),cy+depth,
                         z+rz*math.sin(i*math.tau/n))
                        for i in range(n)] for cy,cx,depth,z,rx,rz in stations],mat)

def blade(name, outline, side, mat, thickness):
    points=[(side*x,y,z) for x,y,z in outline]
    return shell(name,[[(x,y,z-thickness) for x,y,z in points],points],mat)

def stream(side,detail):
    rings=[]
    for i in range(detail+1):
        t=i/detail
        x=side*(.82+2.05*t)
        y=-.40-1.26*t
        z=3.90+.24*math.sin(math.pi*t)-3.28*t*t
        r=.054-.023*t
        rings.append([(x+r*math.cos(k*math.tau/5),y,z+r*math.sin(k*math.tau/5)) for k in range(5)])
    return shell('water_jet_left' if side<0 else 'water_jet_right',rings,jet)

def fountain(lod):
    n=(32,24,16)[lod]
    levels('Raised circular paving',[(5.95,0),(5.95,.095),(5.79,.15)],paving,n)
    levels('Lower stone course',[(3.8,.15),(3.8,.285),(3.69,.32)],edge,n,lobes=True)
    levels('Shell basin wall',[(3.69,.29),(3.68,.46),(3.89,.70),(3.87,.79),
                              (3.57,.79),(3.49,.69),(3.46,.55)],stone,n,lobes=True,cap=False)
    levels('water_basin_surface',[(3.54,.61),(3.54,.615)],water,n,lobes=True)
    # Curl the support into the dove's lower breast, like a wave lifting it.
    wave_sides=10 if lod==0 else 8
    wave=[(.89,.60,0),(.71,.87,.03),(.48,1.42,.17),(.52,1.80,.45),
          (.43,2.24,.62),(.33,2.69,.54),(.24,3.04,.39),(.14,3.16,.29)]
    shell('Bronze wave supporting the dove',[
        [(r*math.cos(i*math.tau/wave_sides),cy+r*math.sin(i*math.tau/wave_sides),z)
         for i in range(wave_sides)] for r,z,cy in wave],bronze)
    levels('Stone wave collar',[(1.04,.58),(.94,.72),(.84,.80)],stone,12 if lod==0 else 8)
    # The body follows the upward pitch from the tail to the breast.
    body=[(1.26,0,0,2.61,.15,.12),(.82,0,0,2.90,.39,.30),(.30,0,0,3.22,.55,.46),
          (-.17,0,0,3.51,.56,.52),(-.54,0,0,3.73,.41,.45),(-.75,0,0,3.94,.245,.32)]
    volume('Rising dove breast',body,bronze,10 if lod==0 else 8)
    head=[(-.49,0,0,4.14,.18,.23),(-.67,0,0,4.28,.29,.315),
          (-.94,0,0,4.33,.285,.27),(-1.15,0,0,4.26,.21,.179),(-1.26,0,0,4.21,.136,.104)]
    volume('Dove head and neck',head,high,10 if lod==0 else 8)
    mesh('Short bronze bill',[(-.144,-1.24,4.22),(.144,-1.24,4.22),(0,-1.61,4.172),
                             (0,-1.26,4.315),(0,-1.25,4.125)],
         [(0,2,3),(3,2,1),(1,2,4),(4,2,0),(0,3,1,4)],shadow)
    if lod<2:
        for s in (-1,1):
            mesh('Inlaid eye',[(s*.284,-.96,4.365),(s*.279,-1.04,4.365),
                               (s*.27,-1.05,4.291),(s*.288,-.965,4.29)],[(0,1,2,3)],dark)
    for side in (-1,1):
        leading=[(.25,-.46,3.55),(1.00,-.60,3.96),(1.78,-.57,4.67),
                 (2.55,-.36,5.31),(3.30,-.02,5.94),(3.76,.27,6.12)]
        trailing=[(3.51,.62,5.78),(2.95,.89,5.28),(2.27,1.15,4.76),
                  (1.45,1.21,4.12),(.77,1.07,3.70),(.28,.60,3.42)]
        blade('Upturned wing',leading+trailing,side,bronze,.15)
        blade('Leading edge',leading+[(3.04,.13,5.72),(2.23,.02,5.04),
                                     (1.48,-.015,4.45),(.49,.08,3.77)],side,high,.062)
        feathers=7 if lod==0 else 4 if lod==1 else 2
        tip=[(1.24,1.35,3.94),(1.66,1.41,4.16),(2.10,1.39,4.45),
             (2.54,1.31,4.79),(2.97,1.16,5.10),(3.42,.95,5.47),(3.82,.64,5.94)]
        for index in range(feathers):
            i=round(index*6/(feathers-1))
            x,y,z=tip[i]
            blade('Scalloped flight feather',[(x-.72,y-.67,z-.47),(x-.31,y-.69,z-.21),
                                             (x+.075,y-.12,z+.07),(x,y,z),
                                             (x-.22,y+.035,z-.19)],side,
                  bronze if i%2==0 else high,.072)
        outline=[(.018,.82,2.98),(.25,.99,2.94),(.69,1.81,2.77),(.57,2.09,2.65),
                 (.28,2.20,2.63),(.025,2.16,2.66)]
        blade('Fanned tail',outline,side,high,.10)
        if lod<2:
            stream(side,12 if lod==0 else 6)
    if lod<2:
        for x in (-3.20,3.20):
            for y in (-3.18,3.18):
                cube('Bench seat',(x,y,.62),(1.42,.48,.13),wood)
                cube('Bench back',(x,y+(.23 if y<0 else -.23),.94),(1.42,.09,.33),wood)
                for dx in (-.51,.51):
                    cube('Bench stone foot',(x+dx,y,.385),(.22,.43,.47),edge)
        for x in (-4.88,4.88):
            for y in (-1.31,1.31):
                levels('Iron lantern shaft',[(.19,.15),(.12,.38),(.075,.57),(.065,2.40),(.15,2.53)],
                       iron,8,center=(x,y))
                levels('glow_lantern_glass',[(.15,2.51),(.19,2.84)],glow,6,center=(x,y))
                levels('Lantern cap',[(.23,2.84),(.09,3.03),(.03,3.09)],iron,6,center=(x,y))

scene=bpy.context.scene
exports=[]
for lod,suffix in enumerate(('', '-mid', '-far')):
    if lod:
        bpy.ops.object.select_all(action='SELECT')
        bpy.ops.object.delete(use_global=False)
    fountain(lod)
    file=f'dove-fountain{suffix}.glb'
    count=sum(len(p.vertices)-2 for obj in scene.objects if obj.type=='MESH' for p in obj.data.polygons)
    exports.append({'file':file,'triangles':count})
    bpy.ops.export_scene.gltf(filepath=str(ROOT/file),export_format='GLB')
    if lod==0:
        bpy.context.preferences.filepaths.save_version=0
        bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/'dove-fountain.blend'))

# The collision boxes use glTF coordinates and leave pedestrian access to the rim.
boxes=[]
for a in range(0,360,30):
    angle=math.radians(a)
    boxes.append({'center':[round(3.68*math.sin(angle),4),.438,round(3.68*math.cos(angle),4)],
                  'size':[2.02,.68,.43],'shape':'box','yaw_deg':a})
boxes.append({'center':[0,1.73,-.20],'size':[1.75,3.16,1.75],'shape':'box','yaw_deg':0})
for x in (-3.2,3.2):
    for y in (-3.18,3.18):
        boxes.append({'center':[x,.64,y],'size':[1.42,.98,.50],'shape':'box','yaw_deg':0})
for x in (-4.88,4.88):
    for y in (-1.31,1.31):
        boxes.append({'center':[x,1.62,y],'size':[.38,2.94,.38],'shape':'box','yaw_deg':0})

manifest={'name':'Dove Fountain','naming':'fixed','site':'Fountain Circus',
          'built_year':1912,'plot_m':[12.4,12.4],'height_m':6.12,
          'texel_m':texel,'art_pixel_texels':[2,2],'forward':'+Z','up':'+Y',
          'lods':exports,'collision_boxes':boxes,
          'water_nodes':['water_basin_surface','water_jet_left','water_jet_right'],
          'night_points':[
            {'center':[x,2.68,y],'role':'lantern'} for x in (-4.88,4.88) for y in (-1.31,1.31)
          ]+[{'center':[x,.83,2.10],'aim_at':[0,4.0,0],'role':'uplight'} for x in (-1.7,1.7)]}
(ROOT/'model-info.json').write_text(json.dumps(manifest,indent=2)+'\n')
if '--export-only' in sys.argv:
    sys.exit()

bpy.ops.object.select_all(action='SELECT')
bpy.ops.object.delete(use_global=False)
fountain(0)
ground=material('preview_ground',(117,121,105),8,25)
cube('Context ground',(0,0,-.08),(1800,1800,.12),ground)
shirt=material('scale_coat',(107,124,134),6,33)
pants=material('scale_trousers',(50,55,60),8,34)
skin=material('scale_face',(161,129,105),6,35)
sx,sy=4.35,-3.8
cube('Player head',(sx,sy,1.73),(.245,.226,.26),skin)
cube('Player torso',(sx,sy,1.32),(.39,.238,.50),shirt)
for s in (-1,1):
    cube('Player arm',(sx+s*.265,sy,1.295),(.12,.155,.49),shirt)
    cube('Player leg',(sx+s*.107,sy,.71),(.171,.195,.72),pants)
    cube('Player boot',(sx+s*.108,sy-.054,.243),(.18,.30,.18),iron)

scene.world=bpy.data.worlds.new('Review sky')
scene.world.use_nodes=True
background=scene.world.node_tree.nodes['Background']
def light(name,pos,target,power,color,size):
    lamp=bpy.data.lights.new(name,'AREA')
    lamp.energy=power
    lamp.color=color
    lamp.shape='DISK'
    lamp.size=size
    obj=bpy.data.objects.new(name,lamp)
    bpy.context.collection.objects.link(obj)
    obj.location=pos
    obj.rotation_euler=(Vector(target)-obj.location).to_track_quat('-Z','Y').to_euler()
    return lamp

key=light('Daylight',(-7,-9,12),(0,0,1.5),3300,(1,.91,.74),8)
rim=light('Bay bounce',(7,4,10),(0,0,3),1750,(.70,.81,1),6)
spots=[light('Pool uplight',(x,-2.10,.84),(0,0,4),0,(1,.69,.36),1)
       for x in (-1.7,1.7)]
for x in (-4.88,4.88):
    for y in (-1.31,1.31):
        spots.append(light('Warm lamplight',(x,y,2.71),(x,y,0),0,(1,.62,.29),1))
cam_data=bpy.data.cameras.new('Review camera')
cam=bpy.data.objects.new('Review camera',cam_data)
bpy.context.collection.objects.link(cam)
scene.camera=cam
cam_data.type='ORTHO'
cam_data.clip_end=2200
scene.render.engine='CYCLES'
scene.cycles.samples=14
scene.cycles.use_denoising=False
scene.render.resolution_x=940
scene.render.resolution_y=640
scene.render.image_settings.file_format='JPEG'
for name,pos,aim,scale in (
    ('street',(8,-18,3.6),(0,0,2.4),13.1),('overhead',(4,-8,15),(0,0,1.85),14.1),
    ('dusk',(-10,-15,5),(0,0,2.35),13.5),('night',(7,-17,3.6),(0,0,2.35),13.5),
):
    dusk=name=='dusk'
    night=name=='night'
    background.inputs[0].default_value=(.27,.37,.59,1) if night else (.65,.70,.85,1)
    background.inputs[1].default_value=.18 if night else .38 if dusk else .79
    key.energy=95 if night else 2100 if dusk else 3300
    key.color=(1,.48,.25) if dusk else (1,.91,.74)
    rim.energy=380 if night else 1300 if dusk else 1750
    for i,spot in enumerate(spots):
        spot.energy=(155 if i<2 else 61) if night else (85 if i<2 else 25) if dusk else 0
    cam.location=pos
    cam.rotation_euler=(Vector(aim)-cam.location).to_track_quat('-Z','Y').to_euler()
    cam_data.ortho_scale=scale
    scene.render.filepath=str(ROOT/(name+'.jpg'))
    bpy.ops.render.render(write_still=True)
