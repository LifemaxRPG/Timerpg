"""Generate the Dove Beach shoreline models and Blender review views."""
import bpy
import json
import math
import random
import sys
from pathlib import Path
from mathutils import Vector

ROOT = Path(__file__).resolve().parent
TEXEL = .0388

# Each spine lists x, y, height and radius, in Blender metres.
OAK = [
    [(.0,0,.14,.68),(-.27,.14,1.45,.55),(-.13,.19,2.75,.44),(.32,.20,3.78,.26),(.12,.33,5,.12),(-.54,.46,5.79,.04)],
    [(-.2,.1,2.53,.39),(-1.30,.17,3.20,.32),(-2.46,.08,3.29,.23),(-3.52,-.29,4.06,.13),(-4.46,-.55,4.43,.045)],
    [(.15,.18,3.26,.39),(1.42,.13,3.32,.30),(2.47,-.12,4.08,.23),(3.58,-.49,4.28,.12),(4.66,-.42,4.93,.035)],
    [(-.22,.11,2.72,.31),(-.35,1.32,3.24,.22),(-.72,2.33,4.22,.10),(-.35,3.39,4.41,.035)],
    [(.19,.03,3.08,.28),(.73,-1.02,3.31,.17),(.32,-2.26,4.04,.09),(-.41,-3.02,4.28,.035)],
]
CEDAR = [
    [(0,0,.1,.52),(-.21,.13,1.63,.41),(-.18,.13,3.75,.33),(.08,.29,5.70,.25),
     (.48,.51,7.95,.16),(.24,.70,9.38,.10),(.55,.67,10.47,.065)],
    [(.12,.32,5.62,.22),(1.17,.31,6.44,.15),(1.76,.48,7.58,.065),(1.60,.62,8.14,.039)],
    [(-.12,.15,3.98,.23),(-1.13,-.14,4.15,.12),(-1.81,-.55,4.87,.035)],
    [(.45,.52,7.90,.115),(-.24,.89,8.91,.076),(-.66,1.08,9.25,.039)],
]
PINE = [
    [(0,0,.08,.53),(.18,.06,1.34,.44),(.74,.18,2.69,.34),(1.64,.22,4.31,.28),
     (2.76,.30,5.81,.20),(3.90,.30,7.26,.135),(4.75,.49,8.11,.055)],
    [(1.52,.19,4.12,.20),(2.03,-.58,4.77,.13),(2.79,-1.32,5.12,.065),(3.88,-1.37,5.14,.029)],
    [(2.63,.27,5.65,.18),(2.94,.89,6.41,.13),(3.19,1.56,7.01,.061),(4.02,1.88,7.56,.028)],
    [(3.65,.30,7.02,.128),(4.67,-.13,7.31,.09),(5.26,-.65,7.88,.035)],
]
FALLEN = [
    [(-4.65,0,.48,.64),(-3.06,.08,.55,.56),(-1.3,.01,.66,.44),(.81,-.13,.56,.36),
     (2.74,-.11,.56,.22),(4.33,.28,.46,.135),(4.98,.45,.40,.072)],
    [(-.39,-.08,.61,.32),(.30,-1.04,1.05,.22),(.78,-2.16,1.49,.147),(1.74,-2.79,1.33,.051)],
    [(1.57,-.13,.55,.24),(2.19,1.02,1.03,.15),(3.11,1.52,1.20,.052)],
]
BURIED = [
    [(-3.82,-.22,.15,.43),(-2.05,-.11,.15,.46),(-.71,.12,.14,.41),(1.32,.43,.22,.29),
     (2.95,.65,.20,.18),(3.41,.58,.16,.083)],
    [(-1.52,-.02,.20,.25),(-.79,-.76,.37,.16),(.16,-1.71,.47,.09),(.91,-1.93,.31,.036)],
    [(1.37,.44,.28,.19),(1.92,1.48,.73,.12),(2.51,1.72,.99,.041)],
]
LONG = [
    [(-5.39,.03,.42,.46),(-4.11,-.13,.52,.45),(-1.92,-.13,.51,.40),(.64,.04,.53,.28),
     (2.92,.15,.49,.23),(4.75,.19,.37,.11),(5.31,.38,.33,.059)],
    [(-2.69,-.11,.52,.25),(-2.32,.21,1.16,.167),(-1.87,.29,1.51,.093)],
    [(.5,.04,.51,.19),(1.06,-.72,.87,.116),(1.41,-1.19,1.32,.062)],
    [(2.84,.15,.48,.15),(3.12,.88,.68,.09),(3.71,1.21,1.05,.036)],
]
STUMP = [[(0,0,.07,.65),(-.11,.05,.64,.49),(.09,.02,1.16,.41),(.22,.10,1.42,.26)],
         [(-.09,.05,.57,.25),(-.44,.13,.84,.16),(-.76,.30,.91,.062)]]
PLATE = [[(-.26,0,.16,.60),(.18,-.12,.48,.52),(.89,-.16,.58,.30),(1.55,-.10,.65,.10)]]

SPECS = [
    ('spreading-oak', OAK, 'dry sand', 'ground', 144),
    ('cedar-snag', CEDAR, 'wet sand', 'ground', 213),
    ('leaning-pine', PINE, 'wet sand', 'ground', 792),
    ('upturned-oak', FALLEN, 'wet sand', 'plate', 897),
    ('buried-trunk', BURIED, 'in the surf', 'sand', 55),
    ('long-bole', LONG, 'wet sand', 'sand', 638),
    ('splinter-stump', STUMP, 'dry sand', 'ground', 271),
    ('loose-root-plate', PLATE, 'wet sand', 'plate', 235),
    ('scatter-log', [[(-.9,0,.17,.18),(-.12,.06,.18,.17),(.81,.16,.17,.115)]], 'dry sand', 'none', 16),
    ('scatter-branch', [[(-.61,.05,.069,.075),(0,0,.09,.071),(.61,.23,.16,.028)],
                        [(0,0,.086,.055),(.31,-.35,.22,.023)]], 'dry sand', 'none', 31),
    ('shell-line', [], 'wet sand', 'shell', 18),
]


def texel_material(name, base, mode):
    rng = random.Random(name)
    img = bpy.data.images.new(name + '_tile', width=64, height=64, alpha=True)
    grain = [rng.uniform(-.14,.14) for _ in range(32)]
    patches = [[0 for _ in range(32)] for _ in range(32)]
    for _ in range(80):
        x, y = rng.randrange(32), rng.randrange(32)
        value = rng.choice((-.11,-.075,.06,.09))
        for k in range(rng.randrange(2,12) if mode == 'wood' else rng.randrange(1,4)):
            patches[(y+k)%32][x] = value
    pixels = []
    for y in range(64):
        for x in range(64):
            delta = patches[y//2][x//2] + (grain[x//2] if mode=='wood' else grain[x//2]*.17)
            pixels.extend((*[max(0,min(1,v*(1+delta))) for v in base],1))
    img.pixels = pixels
    img.pack()
    mat = bpy.data.materials.new(name)
    mat.diffuse_color = (*base,1)
    mat.use_nodes = True
    node = mat.node_tree.nodes.new('ShaderNodeTexImage')
    node.image, node.interpolation, node.extension = img, 'Closest', 'REPEAT'
    shader = mat.node_tree.nodes.get('Principled BSDF')
    shader.inputs['Roughness'].default_value = .93
    mat.node_tree.links.new(node.outputs['Color'],shader.inputs['Base Color'])
    return mat


def branches(spines, roots, rng):
    paths = [(spine, 0 if i==0 else 1) for i,spine in enumerate(spines)]
    if roots in ('ground','plate','sand'):
        x,y,z,r = spines[0][0]
        for i in range(9 if roots!='sand' else 4):
            angle = math.tau*i/(9 if roots!='sand' else 4) + rng.uniform(-.15,.15)
            cx, sy = math.cos(angle), math.sin(angle)
            length = rng.uniform(.75,1.95)
            if roots=='plate':
                # The broken root fan lies mostly in the vertical transverse plane.
                def radial(distance, offset, width):
                    return (x+offset,y+cx*distance,max(-.19,z+.56+sy*distance),width)
                path = [radial(.04,0,.30),radial(length*.46,-.25,.16),
                        radial(length*.8,-.43,.09),radial(length,-.63,.026)]
                if i%2==0:
                    fork = [path[1],radial(length*.69,-.68,.072),radial(length*.91,-.97,.024)]
                    paths.append((fork,2))
            else:
                path = [(x,y,max(.18,z+.13),r*.43),
                        (x+cx*length*.40,y+sy*length*.42,rng.uniform(.14,.32),r*.28),
                        (x+cx*length*.77-sy*.13,y+sy*length*.77+cx*.13,.105,r*.14),
                        (x+cx*length,y+sy*length,-.086,.022)]
            paths.append((path,1 if i%3==0 else 2))
    # Forks start on structural limbs so the smaller branches follow the growth.
    for i, spine in enumerate(spines):
        if len(spine)<4 or roots=='none':
            continue
        for k in range(1,len(spine)-1):
            x,y,z,r = spine[k]
            if r<.075 or (i==0 and k<2) or z<1.15:
                continue
            side = rng.choice((-1,1))
            nx,ny,_,_=spine[k+1]
            heading=math.atan2(ny-y,nx-x)+side*rng.uniform(.55,1.21)
            length = min(2.10, .56+r*3.5)*rng.uniform(.75,1.20)
            dx,dy=math.cos(heading)*length,math.sin(heading)*length
            rise=length*rng.uniform(.28,.83)
            branch=[(x,y,z,r*.57),(x+dx*.44,y+dy*.43,z+rise*.49,r*.40),
                    (x+dx*.82,y+dy*.72,z+rise*.65,r*.22),(x+dx,y+dy,z+rise,.018+r*.045)]
            paths.append((branch,2))
            if i%2==0:
                bx,by,bz,br=branch[1]
                paths.append(([(bx,by,bz,br*.65),(bx+dx*.25-dy*.23,by+dy*.25+dx*.23,bz+.42,.028),
                               (bx+dx*.38-dy*.33,by+dy*.38+dx*.33,bz+.72,.012)],3))
    return paths


def geometry(name, paths, mode, seed, lod, materials):
    rng=random.Random(seed)
    vertices, faces, uv, slots = [],[],[],[]
    def face(indices, coords, slot):
        faces.append(indices)
        uv.append(coords)
        slots.append(slot)
    for path, rank in paths:
        sides = (6 if rank<2 else 4) if lod=='near' else 4 if lod=='mid' else 3
        if mode in ('none','shell'):
            sides = 7 if lod=='near' else 4 if lod=='mid' else 3
        if lod=='mid' and name=='long-bole' and rank==1 and path[-1][2]<0:
            continue
        if lod=='mid' and rank>1 or lod=='far' and rank>1:
            continue
        if lod=='far' or (lod=='mid' and mode=='none'):
            path=[path[0],path[-1]] if mode=='none' else [path[0],path[len(path)//2],path[-1]]
        elif lod=='mid' and rank==1 and len(path)>3:
            path=[path[0],path[len(path)//2],path[-1]]
        points = [Vector(row[:3]) for row in path]
        distance = 0
        offsets = [rng.uniform(.81,1.16) for _ in range(sides)]
        start = len(vertices)
        roll = rng.uniform(-.28,.28)
        for j,(point,row) in enumerate(zip(points,path)):
            direction=(points[min(j+1,len(points)-1)]-points[max(j-1,0)]).normalized()
            reference=Vector((0,0,1)) if abs(direction.z)<.82 else Vector((0,1,0))
            u=direction.cross(reference).normalized()
            v=direction.cross(u).normalized()
            for k in range(sides):
                theta=k*math.tau/sides+roll
                adjusted=point+(u*math.cos(theta)+v*math.sin(theta))*row[3]*offsets[k]
                if j==len(points)-1:
                    adjusted+=direction*row[3]*rng.uniform(-.55,.38)
                vertices.append(tuple(adjusted))
            if j==0:
                continue
            prior=distance
            distance+=(point-points[j-1]).length/(64*TEXEL)
            ring0=start+(j-1)*sides
            ring1=ring0+sides
            width=(row[3]+path[j-1][3])*math.pi/(64*TEXEL)
            for k in range(sides):
                n=(k+1)%sides
                surface_height=sum(vertices[i][2] for i in (ring0+k,ring0+n,ring1+n,ring1+k))/4
                material=1 if mode!='none' and surface_height<.31 else 0
                if mode=='sand' and seed==55 and k in (1,2) and material==1:
                    material=3
                face((ring0+k,ring0+n,ring1+n,ring1+k),
                     ((k*width/sides,prior),((k+1)*width/sides,prior),
                      ((k+1)*width/sides,distance),(k*width/sides,distance)),material)
        end=start+(len(points)-1)*sides
        for ring, reverse in ((start,True),(end,False)):
            order=list(range(sides))
            if reverse:
                order.reverse()
            radius=path[0 if reverse else -1][3]
            face(tuple(ring+k for k in order),tuple((.5+math.cos(k*math.tau/sides)*radius/(64*TEXEL),
                  .5+math.sin(k*math.tau/sides)*radius/(64*TEXEL)) for k in order),2)
    if mode in ('ground','sand','plate') and lod=='near':
        px,py,_,_=paths[0][0][0]
        start=len(vertices)
        for scale,z in ((1, .024),(.56,.11)):
            for k in range(9):
                angle=k*math.tau/9
                vertices.append((px+math.cos(angle)*scale*1.53,py+math.sin(angle)*scale*.98,z+rng.random()*.035))
        for k in range(9):
            n=(k+1)%9
            face((start+k,start+n,start+9+n,start+9+k),tuple((vertices[i][0]/2.4832,vertices[i][1]/2.4832)
                 for i in (start+k,start+n,start+9+n,start+9+k)),4)
        top=tuple(range(start+9,start+18))
        face(top,tuple((vertices[i][0]/2.4832,vertices[i][1]/2.4832) for i in top),4)
    if mode=='shell':
        for k in range(11 if lod=='near' else 4 if lod=='mid' else 2):
            x=(k-5)*.235 if lod=='near' else (k-(2 if lod=='mid' else .5))*.44
            y=rng.uniform(-.22,.22)
            r=rng.uniform(.038,.079)
            a=len(vertices)
            vertices.extend((x+math.cos(i*math.tau/5)*r,y+math.sin(i*math.tau/5)*r*.73,.019) for i in range(5))
            vertices.append((x,y,.052))
            for i in range(5):
                face((a+i,a+(i+1)%5,a+5),((0,0),(.04,0),(.02,.04)),2)
    mesh=bpy.data.meshes.new(name)
    mesh.from_pydata(vertices,[],faces)
    mesh.update()
    for mat in materials:
        mesh.materials.append(mat)
    layer=mesh.uv_layers.new()
    for poly,coords,slot in zip(mesh.polygons,uv,slots):
        poly.material_index=slot
        for i,value in zip(poly.loop_indices,coords):
            layer.data[i].uv=value
    obj=bpy.data.objects.new(name,mesh)
    bpy.context.collection.objects.link(obj)
    mesh.calc_loop_triangles()
    return obj,len(mesh.loop_triangles)


def collision(paths):
    boxes=[]
    for path,rank in paths:
        if rank>1:
            continue
        for a,b in zip(path,path[1:]):
            if min(a[3],b[3])<.14:
                continue
            width=(a[3]+b[3])*.84
            a,b=Vector(a[:3]),Vector(b[:3])
            center=(a+b)*.5
            # Convert to glTF basis (+Y up, +Z forward). Local collision length is Z.
            direction=b-a
            target=Vector((direction.x,direction.z,-direction.y))
            rotation=target.to_track_quat('Z','Y')
            boxes.append({'center':[round(x,4) for x in (center.x,center.z,-center.y)],
                          'size':[width,width,round(direction.length+.16,4)],
                          'rotation_xyzw':[round(x,6) for x in (rotation.x,rotation.y,rotation.z,rotation.w)]})
    return boxes


def prep_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    return [
        texel_material('bleached_grain', (.55,.53,.49),'wood'),
        texel_material('wet_grain', (.23,.20,.17),'wood'),
        texel_material('splinter_crossgrain', (.66,.57,.45),'grain'),
        texel_material('tide_stain', (.27,.31,.19),'wood'),
        texel_material('sand_drift', (.63,.52,.36),'grain'),
    ]


def export_all():
    materials = prep_scene()
    records=[]
    source_objects=[]
    for name,spines,placement,mode,seed in SPECS:
        paths=branches(spines,mode,random.Random(seed))
        stats={}
        for level in ('near','mid','far'):
            obj,count=geometry(name,paths,mode,seed,level,materials)
            obj.select_set(True)
            bpy.context.view_layer.objects.active=obj
            filename=f'{name}{"" if level=="near" else "-"+level}.glb'
            bpy.ops.export_scene.gltf(filepath=str(ROOT/filename),export_format='GLB',use_selection=True)
            obj.select_set(False)
            stats[level]={'file':filename,'triangles':count}
            if level=='near':
                dims=[[min(v.co[i] for v in obj.data.vertices),max(v.co[i] for v in obj.data.vertices)] for i in range(3)]
                size=[round(dims[i][1]-dims[i][0],3) for i in (0,2,1)]
                source_objects.append(obj)
            else:
                bpy.data.objects.remove(obj,do_unlink=True)
        records.append({'id':name,'suggested_placement':placement,'size_m':size,'lods':stats,
                        'collision_boxes':collision(paths) if mode not in ('none','shell') else []})
    for i,obj in enumerate(source_objects):
        obj.location=(i%4*13,i//4*8,0)
    bpy.context.preferences.filepaths.save_version=0
    bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/'boneyard-source.blend'), compress=True)
    (ROOT/'model-info.json').write_text(json.dumps({'texel_m':TEXEL,'art_pixel_texels':2,'units':'metres',
        'axes':'glTF +Y up','assets':records},indent=2)+'\n')


def material(name,color):
    mat=bpy.data.materials.new(name)
    mat.diffuse_color=(*color,1)
    return mat


def cube(name,xyz,dims,mat):
    bpy.ops.mesh.primitive_cube_add(size=1,location=xyz)
    obj=bpy.context.object
    obj.name=name
    obj.dimensions=dims
    obj.data.materials.append(mat)


def aim(obj,target):
    obj.rotation_euler=(Vector(target)-obj.location).to_track_quat('-Z','Y').to_euler()


def render(title, placements, camera_pos,target,scale,warm=False,portrait=False):
    materials=prep_scene()
    scene=bpy.context.scene
    scene.render.engine='CYCLES'
    scene.cycles.samples=20
    scene.cycles.use_denoising=False
    scene.render.resolution_x=1200
    scene.render.resolution_y=450 if title in ('standing-lineup','fallen-lineup','roots-and-scatter') else 650
    scene.render.resolution_percentage=100
    scene.world=bpy.data.worlds.new('Beach sky')
    scene.world.use_nodes=True
    bg=scene.world.node_tree.nodes.get('Background')
    bg.inputs[0].default_value=(.47,.55,.67,1) if warm else (.68,.78,.90,1)
    bg.inputs[1].default_value=.38 if warm else .62
    sand=material('sand',(.68,.54,.35))
    water=material('tide',(.10,.32,.39))
    wet=material('wet sand',(.41,.34,.245))
    cube('beach',(0,0,-.09),(260,190,.17),sand)
    if warm:
        cube('wet shoreline',(0,20,-.055),(260,30,.105),wet)
        cube('sea',(0,55,-.048),(260,92,.092),water)
        foam=material('foam',(.60,.72,.66))
        for i in range(14):
            cube('tide wake',(i*5-34,9.8+math.sin(i)*.31,.008),(4.55,.13,.011),foam)
    for index,x,y,turn in placements:
        name,spines,_,mode,seed=SPECS[index]
        obj,_=geometry(name,branches(spines,mode,random.Random(seed)),mode,seed,'near',materials)
        obj.location=(x,y,0)
        obj.rotation_euler.z=turn
    person=material('scale figure',(.13,.19,.27))
    x,y=(-14,-.45) if portrait else (1,-3.5)
    for dx in (-.09,.09):
        cube('leg',(x+dx,y,.44),(.13,.18,.88),person)
    cube('body',(x,y,1.19),(.33,.21,.62),person)
    cube('head',(x,y,1.625),(.22,.21,.25),person)
    for dx in (-.213,.213):
        cube('arm',(x+dx,y,1.19),(.08,.16,.58),person)
    bpy.ops.object.light_add(type='SUN',location=(-8,-9,12))
    sun=bpy.context.object
    sun.data.energy=2.8 if warm else 2.0
    sun.data.color=(1,.64,.39) if warm else (1,.93,.82)
    aim(sun,(0,0,7 if warm else 0))
    bpy.ops.object.camera_add(location=camera_pos)
    camera=bpy.context.object
    camera.data.type='ORTHO'
    camera.data.ortho_scale=scale
    aim(camera,target)
    scene.camera=camera
    scene.render.filepath=str(ROOT/(title+'.jpg'))
    scene.render.image_settings.file_format='JPEG'
    scene.render.image_settings.quality=90
    bpy.ops.render.render(write_still=True)


if __name__=='__main__':
    export_all()
    if '--export-only' in sys.argv:
        sys.exit(0)
    render('standing-lineup',[(0,-8,0,-.12),(1,.3,0,.09),(2,6.1,0,-.30)],
           (0,-24,7),(0,0,4.8),33,portrait=True)
    render('fallen-lineup',[(3,-9.5,0,.20),(4,0,0,-.19),(5,10.1,0,.26)],
           (0,-23,12),(0,0,1.25),34,portrait=True)
    render('roots-and-scatter',[(6,-7,0,.2),(7,-2.7,.45,-.20),(8,1.6,0,-.08),
           (9,5,.35,.40),(10,8.4,0,0)],(0,-17,8),(0,0,.75),29,portrait=True)
    render('sunset-beach',[(3,-2.2,1.5,-.43),(0,4.3,7.4,.30),(1,12,7,.1),
           (6,-7.2,1.6,0),(8,1,-3,-.15),(10,3,-2.7,.15)],
           (-13,-18,3.3),(2,3,3.25),24,True)
    render('boneyard-stretch',[(0,-6,3,.20),(1,-.45,6.8,.12),(2,3.9,8.4,-.4),
           (3,-2.4,1,-.46),(4,5.5,5.8,.25),(5,9.1,-1.0,-.38),
           (6,-10,-.9,0),(7,2.7,-2.5,.2),(8,-6,-4,.75),(9,-9,0,1.2),
           (10,4,3,.35)],(16,-26,16),(0,3,2.9),30,True)
