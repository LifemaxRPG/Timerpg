"""Check the exported models against the placement manifest."""
import json
import math
import struct
from pathlib import Path

root=Path(__file__).resolve().parent
info=json.loads((root/'model-info.json').read_text())
assert len(info['assets'])==11 and info['texel_m']==.0388
for item in info['assets']:
    counts={}
    for lod,entry in item['lods'].items():
        file=root/entry['file']
        with file.open('rb') as stream:
            magic,version,size,length,chunk=struct.unpack('<4sIII4s',stream.read(20))
            assert (magic,version,size,chunk)==(b'glTF',2,file.stat().st_size,b'JSON')
            model=json.loads(stream.read(length))
        counts[lod]=sum(model['accessors'][prim['indices']]['count']//3
                        for mesh in model['meshes'] for prim in mesh['primitives'])
        assert counts[lod]==entry['triangles']
        for sample in model['samplers']:
            assert sample['magFilter']==9728 and sample['minFilter'] in (9728,9984)
            assert sample.get('wrapS',10497)==sample.get('wrapT',10497)==10497
    assert counts['mid']<counts['near']*.4 and counts['far']<150, (item['id'], counts)
    for box in item['collision_boxes']:
        assert all(side>0 for side in box['size'])
        assert math.isclose(math.hypot(*box['rotation_xyzw']),1,abs_tol=2e-6)
    print(item['id'],counts)
assert 8 <= info['assets'][0]['size_m'][0] <= 10
assert 9 <= info['assets'][1]['size_m'][1] <= 12
assert all(6 <= item['size_m'][0] <= 12.2 for item in info['assets'][3:6])
