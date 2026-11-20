"""Open the self-contained source and verify contact on the separately imported GLB."""
from pathlib import Path
import json,bpy
from mathutils.bvhtree import BVHTree
r=Path(__file__).resolve().parent
info=json.loads((r/'model-info.json').read_text())
bpy.ops.wm.open_mainfile(filepath=str(r/'night-keeper.blend'))
assert all(bpy.data.images[p.name].packed_file for p in (r/'textures').glob('*.png'))
assert bpy.context.scene.camera
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(r/'night-keeper.glb'))
def nearby(group, bounds,material=None):
    vs=[];fs=[]
    for ob in bpy.data.objects[group].children_recursive:
        if ob.type!='MESH':continue
        for p in ob.data.polygons:
            if material and ob.data.materials[p.material_index].name!=material:continue
            verts=[ob.matrix_world@ob.data.vertices[j].co for j in p.vertices]
            if all(any(bounds[i][0]<=v[i]<=bounds[i][1] for v in verts) for i in range(3)):
                off=len(vs);vs.extend(verts);fs.append(tuple(range(off,off+len(verts))))
    assert fs,(group,bounds)
    return BVHTree.FromPolygons(vs,fs)
b=[(8,14),(14,19),(12,16.4)]
hand=nearby('keeper_figure',b,'wall_limestone');bail=nearby('lantern_pavilion',b,'detail_cast_iron')
assert hand.overlap(bail),'The carrying hand must meet the iron bail'
print('Packed images, camera and exported carrying-hand contact verified.')
