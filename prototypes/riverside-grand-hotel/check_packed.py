"""Check an opened packed source against its exported-model measurements."""
import bpy,json
from pathlib import Path
r=Path(__file__).resolve().parent
bpy.ops.wm.open_mainfile(filepath=str(r/'riverside-grand-hotel.blend'))
root=bpy.data.objects['riverside_grand']
triangles=sum(len(p.vertices)-2 for o in root.children_recursive if o.type=='MESH' and o.name!='collision' for p in o.data.polygons)
assert triangles==json.loads((r/'model-info.json').read_text())['triangles_visible']
images=[i for i in bpy.data.images if i.source=='FILE']
assert len(images)==13 and all(i.packed_file for i in images)
print('Packed hotel verified',triangles,len(images))
