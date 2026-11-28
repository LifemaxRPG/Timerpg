"""Build both tractor generations and their review views with Blender."""
import bpy
import json
import math

import random
import sys
from pathlib import Path
from mathutils import Vector

ROOT = Path(__file__).resolve().parent
TEXEL = .0388
VARIANTS = [
    ('wren-rowcrop-1952', False, (.46, .28, .15), 2.15, .42, 1.55, .28, .68, .17, .29, 1750, 24),
    ('wren-utility-1978', True, (.29, .37, .26), 2.60, 1.68, 1.92, .42, .79, .27, .40, 3750, 32),
]
PALETTE = ['#855A39', '#687351', '#826A78', '#536F73', '#A58E69']
CAMERAS = [('front', (7, -9, 4)), ('rear', (-7, 8, 4)), ('side', (9, 0, 2)),
           ('top', (.01, 0, 11)), ('night', (7, -9, 3.8)), ('access', (-8, -3, 3))]


def material(name, color, texture=False, glass=False, lamp=False):
    mat = bpy.data.materials.new(name)
    mat.diffuse_color = (*color, .16 if glass else 1)
    mat.use_nodes = True
    shader = mat.node_tree.nodes.get('Principled BSDF')
    shader.inputs['Base Color'].default_value = (*color, 1)
    shader.inputs['Roughness'].default_value = .86 if not glass else .28
    if glass:
        shader.inputs['Alpha'].default_value = .16
        if hasattr(mat, 'surface_render_method'):
            mat.surface_render_method = 'BLENDED'
        else:
            mat.blend_method = 'BLEND'
    if lamp:
        shader.inputs['Emission Color'].default_value = (*color, 1)
        shader.inputs['Emission Strength'].default_value = 1.4
    if texture:
        rng = random.Random(name)
        image = bpy.data.images.new(name + '_pixels', width=32, height=32, alpha=True)
        patches = [[rng.choice((.76, .89, 1, 1, 1.08)) for _ in range(16)] for _ in range(16)]
        image.pixels = [channel for y in range(32) for x in range(32)
                        for channel in (*[min(1, c * patches[y//2][x//2]) for c in color], 1)]
        image.pack()
        node = mat.node_tree.nodes.new('ShaderNodeTexImage')
        node.image = image
        node.interpolation = 'Closest'
        node.extension = 'REPEAT'
        mat.node_tree.links.new(node.outputs['Color'], shader.inputs['Base Color'])
    return mat


def finish(obj, name, mat, parent=None):
    obj.name = name
    obj.data.materials.append(mat)
    if parent is not None:
        transform = obj.matrix_world.copy()
        obj.parent = parent
        obj.matrix_world = transform
    return obj


def box(name, xyz, dimensions, mat, parent=None):
    bpy.ops.mesh.primitive_cube_add(size=1, location=xyz)
    obj = bpy.context.object
    obj.dimensions = dimensions
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    layer = obj.data.uv_layers.active.data
    for face in obj.data.polygons:
        normal = face.normal
        axes = sorted(range(3), key=lambda axis: abs(normal[axis]))[:2]
        for index in face.loop_indices:
            point = obj.data.vertices[obj.data.loops[index].vertex_index].co
            layer[index].uv = [point[axis] / (32 * TEXEL) for axis in axes]
    return finish(obj, name, mat, parent)


def cylinder(name, xyz, radius, length, mat, sides=12, axis='z', parent=None):
    rotation = (0, math.pi/2, 0) if axis == 'x' else (math.pi/2, 0, 0) if axis == 'y' else (0, 0, 0)
    bpy.ops.mesh.primitive_cylinder_add(vertices=sides, radius=radius, depth=length, location=xyz, rotation=rotation)
    obj = bpy.context.object
    layer = obj.data.uv_layers.active.data
    for face in obj.data.polygons:
        side = abs(face.normal.z) < .5
        for corner, index in enumerate(face.loop_indices):
            point = obj.data.vertices[obj.data.loops[index].vertex_index].co
            u = (corner in (1, 2)) * 2 * radius * math.sin(math.pi/sides) if side else point.x
            v = point.z if side else point.y
            layer[index].uv = (u/(32*TEXEL), v/(32*TEXEL))
    return finish(obj, name, mat, parent)


def anchor(name, xyz, parent=None):
    obj = bpy.data.objects.new(name, None)
    bpy.context.collection.objects.link(obj)
    obj.parent = parent
    obj.location = xyz
    return obj


def export(path):
    bpy.ops.export_scene.gltf(filepath=str(path), export_format='GLB', export_cameras=False, export_lights=False)


def aim(obj, target):
    obj.rotation_euler = (Vector(target)-obj.location).to_track_quat('-Z', 'Y').to_euler()


def tractor(spec, lod):
    name, cab, color, wb, ft, rt, fr, rr, fw, rw, mass, speed = spec
    paint = material('body_paint', color, True)
    iron = material('cast_metal', (.115,.135,.135), True)
    rubber = material('rubber', (.055,.063,.06), True)
    trim = material('rim_and_trim', (.51,.46,.34), True)
    glass = material('cab_glass', (.29,.43,.45), glass=True) if cab and lod != 'far' else iron
    lamp = material('lamp_faces', (.93,.65,.27), lamp=True) if lod == 'full' else trim
    full = lod == 'full'
    far = lod == 'far'
    for end, axle, track, radius, width in [('f',-wb/2,ft,fr,fw), ('r',wb/2,rt,rr,rw)]:
        for side, sign in [('l',-1), ('r',1)]:
            position = (sign*track/2, axle, radius)
            tag = end + side
            steer = anchor('steer_'+tag, position) if end == 'f' else None
            wheel = cylinder('wheel_'+tag, position, radius, width, rubber, 6 if far else 10 if not full else 14, 'x', steer)
            if not far:
                hub = (position[0]+sign*width*.52, axle, radius)
                cylinder('rim_'+tag, hub, radius*.58, .05, trim, 8, 'x', wheel)
                if full:
                    cylinder('hub_'+tag, (hub[0]+sign*.037, axle, radius), radius*.21, .07, iron, 8, 'x', wheel)
                    for i in range(12 if end == 'r' else 8):
                        a = i*math.tau/(12 if end == 'r' else 8)
                        t = box('tread_'+tag, (position[0], axle+math.sin(a)*(radius-.03),
                                radius+math.cos(a)*(radius-.03)), (width*.95,.085,.06), rubber, wheel)
                        t.rotation_euler.x = -a
    box('chassis', (0,0,.63), (.65,wb+ .55,.28), iron)
    if far:
        box('hood', (0,-.66,1.02), (.69,1.55,.50), paint)
        box('cab' if cab else 'seat', (0,.59,1.82 if cab else 1.15),
            (1.36,1.28,1.6) if cab else (.45,.41,.26), paint)
        return
    hood = box('hood', (0,-.64,1.21 if cab else 1.13), (.82 if cab else .66,1.6,.32), paint)
    box('engine', (0,-.55,.88), (.68 if cab else .48,1.36,.40), iron)
    box('radiator', (0,-1.45,1.03), (.70 if cab else .54,.13,.58), trim)
    for side in (-1,1):
        box('fender', (side*rt/2,wb/2,rr*2+.1), (.47,.97,.11), paint)
        if full:
            box('step', (side*.58,.56,.62), (.35,.39,.08), trim)
            for i in range(6):
                box('engine_fin', (side*(.35 if cab else .25),-.98+i*.14,.88), (.045,.045,.27), trim)
    box('rear_axle', (0,wb/2,rr), (rt,.18,.18), iron)
    box('front_axle', (0,-wb/2,fr), (ft,.15,.13), iron)
    box('platform', (0,.64,.95), (1.23 if cab else .70,1.02,.13), trim)
    box('seat_cushion', (0,.94,1.23), (.43,.4,.12), rubber)
    box('seat_back', (0,1.12,1.43), (.43,.10,.36), rubber)
    cylinder('exhaust_stack', (.24,-.68,1.54), .052,.81,iron, 8)
    cylinder('exhaust_cap', (.24,-.68,1.96), .074,.045,trim, 8)
    if cab:
        box('cab_roof', (0,.76,2.64), (1.48,1.45,.16), paint)
        for x in (-.65,.65):
            for y in (.11,1.34):
                box('cab_pillar', (x,y,1.94), (.08,.10,1.25), paint)
        for y in (.11,1.34):
            box('windscreen', (0,y,1.99), (1.24,.032,1.02), glass)
        box('side_glass', (.65,.73,2.02), (.03,1.13,1.0), glass)
        door = anchor('door_left', (-.66,.13,1.64))
        box('door_glass', (-.66,.71,2.03), (.032,1.12,.97), glass,door)
        for z in (1.51,2.53):
            box('door_rail', (-.66,.71,z), (.07,1.18,.07), paint,door)
        box('door_stile', (-.66,1.28,2.0), (.07,.07,1.05), paint,door)
        box('door_handle', (-.71,1.14,1.59), (.06,.16,.05), trim,door)
    if full:
        box('dashboard', (0,.12,1.33), (.5,.19,.28), iron)
        for x in (-.12,0,.12):
            cylinder('gauge', (x,.221,1.39), .035,.012,trim,8,'y')
        shaft = cylinder('steering_column', (0,.22,1.28), .034,.57,trim,8)
        shaft.rotation_euler.x = -.38
        bpy.ops.mesh.primitive_torus_add(major_segments=12, minor_segments=4, location=(0,.34,1.57),
            major_radius=.17, minor_radius=.022, rotation=(.40,0,0))
        steering = finish(bpy.context.object,'steering_wheel',rubber)
        box('steering_spoke', (0,.34,1.57), (.31,.035,.025), iron,steering)
        for side, sign in [('l',-1),('r',1)]:
            for tag, y, z in [('head',-1.53,1.09), ('tail',1.53,1.26)]:
                x=sign*(.28 if tag=='head' else rt/2)
                cylinder('lamp_housing', (x,y,z), .095,.12,iron,8,'y')
                cylinder('light_'+tag+'_'+side,(x,y+(-.068 if tag=='head' else .068),z),.073,.019,lamp,8,'y')
            if cab:
                for tag, y in [('work_front',.03), ('work_rear',1.49)]:
                    box('light_'+tag+'_'+side,(sign*.55,y,2.61),(.16,.035,.10),lamp)
                for tag,z in [('brake',1.38),('turn',1.50)]:
                    box('light_'+tag+'_'+side,(sign*.87,1.57,z),(.12,.026,.065),lamp)
        for x in (-.26,.26):
            arm = box('lower_hitch_arm', (x,1.58,.62), (.08,.63,.08), iron)
            arm.rotation_euler.x = -.28
        box('top_hitch_link', (0,1.51,.94), (.07,.45,.07), trim)
        box('drawbar', (0,1.62,.44), (.17,.44,.07), iron)
    anchor('seat_driver',(0,.94,1.29))
    anchor('exit_left',(-1.3,.56,0))
    anchor('exit_right',(1.3,.56,0))


def render_views(name, cab, car_path):
    scene = bpy.context.scene
    scene.render.engine = 'CYCLES'
    scene.cycles.samples = 32
    scene.cycles.use_denoising = False
    scene.render.resolution_x = 420
    scene.render.resolution_y = 300
    scene.render.resolution_percentage = 100
    scene.world = bpy.data.worlds.new('Studio')
    scene.world.use_nodes = True
    background = scene.world.node_tree.nodes.get('Background')
    background.inputs[0].default_value = (.49,.56,.65,1)
    ground = material('preview_ground', (.18,.17,.145))
    box('preview_floor', (0,0,-.08), (200,200,.10), ground)
    bpy.ops.object.camera_add()
    camera = bpy.context.object
    camera.data.type = 'ORTHO'
    scene.camera = camera
    bpy.ops.object.light_add(type='AREA', location=(3,-4,7))
    key = bpy.context.object
    key.data.energy = 1150
    key.data.shape = 'DISK'
    key.data.size = 5
    aim(key,(0,0,1))
    for view, location in CAMERAS:
        opening = bpy.data.objects.get('door_left')
        if opening:
            opening.rotation_euler.z = -1.14 if view == 'access' else 0
        night = view == 'night'
        background.inputs[1].default_value = .085 if night else .65
        key.data.energy = 90 if night else 1150
        camera.location = location
        camera.data.ortho_scale = (5.7 if cab else 5) if view != 'access' else 4.3
        aim(camera,(0,0,1.34 if cab else .95))
        scene.render.filepath = str(ROOT / (name + '-' + view + '.png'))
        bpy.ops.render.render(write_still=True)

    # Keep the reference vehicle in the review scene only.
    if car_path:
        bpy.ops.import_scene.gltf(filepath=str(car_path))
        reference = bpy.context.selected_objects[:]
        for obj in reference:
            if obj.parent is None:
                obj.location.x -= 3.2
    human = material('preview_person', (.37,.26,.22))
    x, y = 1.55, -.35
    cylinder('reference_head', (x,y,1.635), .115,.23,human,8)
    box('reference_torso', (x,y,1.20), (.34,.21,.54),human)
    for offset in (-.094,.094):
        box('reference_leg',(x+offset,y,.47),(.14,.17,.94),human)
    for offset in (-.22,.22):
        box('reference_arm',(x+offset,y,1.14),(.09,.12,.54),human)
    camera.location = (6,-10,4)
    camera.data.ortho_scale = 8.4
    aim(camera,(-1.15,0,1))
    scene.render.filepath = str(ROOT / (name + '-scale.png'))
    bpy.ops.render.render(write_still=True)


def count_mesh():
    meshes = [obj for obj in bpy.context.scene.objects if obj.type == 'MESH']
    for obj in meshes:
        obj.data.calc_loop_triangles()
    return sum(len(obj.data.loop_triangles) for obj in meshes)


def main():
    args = sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else []
    car_path = args[0] if args else None
    stats = []
    for spec in VARIANTS:
        name, cab, color, wb, ft, rt, fr, rr, fw, rw, mass, speed = spec
        levels = {}
        for lod in ('far','mid','full'):
            bpy.ops.wm.read_factory_settings(use_empty=True)
            bpy.context.scene.unit_settings.system = 'METRIC'
            tractor(spec,lod)
            triangles = count_mesh()
            levels[lod] = triangles
            export(ROOT / (name + ('' if lod == 'full' else '-' + lod) + '.glb'))
        bpy.context.preferences.filepaths.save_version = 0
        bpy.ops.wm.save_as_mainfile(filepath=str(ROOT / (name + '.blend')))
        assert levels['full'] <= 3000 and levels['mid'] < levels['full']*.4 and levels['far'] < 150, levels
        stats.append({
            'name': name, 'era': [1970,1989] if cab else [1940,1959],
            'mass_kg': mass, 'wheelbase_m': wb, 'track_m': {'front':ft,'rear':rt},
            'tyres_m': {'front':{'radius':fr,'width':fw},'rear':{'radius':rr,'width':rw}},
            'top_speed_kph': speed, 'triangles': levels, 'paint_colors': PALETTE,
            'faded_paint': PALETTE[-1], 'paint_mask_material':'body_paint', 'emission_mask_material':'lamp_faces',
            'collision': [
                {'center':[0,.76,.32], 'size':[.68,.72,2.18]},
                {'center':[0,1.80,-.76], 'size':[1.30,1.76,1.32]} if cab else
                {'center':[0,1.16,-.75], 'size':[.70,.50,1.05]},
            ],
        })
        render_views(name,cab,car_path)
    (ROOT / 'model-info.json').write_text(json.dumps({'texel_m':TEXEL,'forward':'+Z','up':'+Y',
        'origin':'ground between axles','vehicles':stats},indent=2)+'\n')


if __name__ == '__main__':
    main()
