"""Test actual supporting mesh intersections and reload the packed source."""
from pathlib import Path
import bpy,json
from mathutils.bvhtree import BVHTree
from mathutils import Vector
r=Path(__file__).resolve().parent
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(r/'inland-sea.glb'))
def index(objects):
    verts=[];faces=[]
    for o in objects:
        if o.type!='MESH':continue
        off=len(verts)
        verts.extend(o.matrix_world@v.co for v in o.data.vertices)
        faces.extend([[v+off for v in p.vertices] for p in o.data.polygons])
    return BVHTree.FromPolygons(verts,faces)
body=index(bpy.data.objects['leviathan'].children_recursive)
ground=index([bpy.data.objects['Travertine coping']])
for i in (1,2,3):
    ob=bpy.data.objects['Breaker %d'%i];pier=index([ob])
    assert pier.overlap(body), 'Missing bearing at hull '+str(i)
    foot=[ob.matrix_world@v.co for v in ob.data.vertices if abs((ob.matrix_world@v.co).z-2.54)<.0001]
    assert foot
    for p in foot:
        hit,normal,_,_=ground.ray_cast(p+Vector((0,0,.13)),Vector((0,0,-1)),.2)
        assert hit and abs(hit.z-2.55)<.001, (i,p)
bpy.ops.wm.open_mainfile(filepath=str(r/'inland-sea.blend'))
images=[bpy.data.images[p.name] for p in (r/'textures').glob('*.png')]
assert len(images)==10 and all(i.packed_file for i in images)
assert bpy.context.scene.camera
print("All three piers bear on the island and intersect the hull. All ten source images packed.")
