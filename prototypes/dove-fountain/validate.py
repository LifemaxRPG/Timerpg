"""Check the embedded textures, geometry counts, and engine-facing node names."""
import json
import struct
from pathlib import Path

root=Path(__file__).resolve().parent
manifest=json.loads((root/'model-info.json').read_text())
counts=[]
for i,lod in enumerate(manifest['lods']):
    data=(root/lod['file']).read_bytes()
    magic,version,total,length,chunk=struct.unpack_from('<4sIIII',data)
    assert (magic,version,total,chunk)==(b'glTF',2,len(data),0x4e4f534a)
    doc=json.loads(data[20:20+length])
    triangles=sum(doc['accessors'][p['indices']]['count']//3
                  for m in doc['meshes'] for p in m['primitives'])
    assert triangles == lod['triangles']
    assert all(s['magFilter']==9728 and s['minFilter'] in (9728,9984)
               for s in doc['samplers'])
    assert all(img['mimeType']=='image/png' and 'bufferView' in img for img in doc['images'])
    nodes={node['name'] for node in doc['nodes']}
    assert 'Upturned wing' in nodes and 'Dove head and neck' in nodes
    assert 'water_basin_surface' in nodes
    if i<2:
        assert set(manifest['water_nodes']) <= nodes
        assert any(m['name']=='glow_lantern' and 'emissiveTexture' in m for m in doc['materials'])
    counts.append(triangles)
    print(lod['file'], triangles, 'triangles')
assert counts[0]>counts[1]>counts[2]>0
assert len(manifest['collision_boxes'])==21
assert all(min(box['size'])>0 for box in manifest['collision_boxes'])
assert manifest['built_year']==1912
