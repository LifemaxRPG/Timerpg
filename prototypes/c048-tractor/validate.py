"""Validate exported GLB geometry counts, sampler settings and vehicle nodes."""
import json
import struct
from pathlib import Path

root = Path(__file__).resolve().parent
info = json.loads((root / 'model-info.json').read_text())
for vehicle in info['vehicles']:
    for lod, triangles in vehicle['triangles'].items():
        suffix = '' if lod == 'full' else '-' + lod
        path = root / (vehicle['name'] + suffix + '.glb')
        with path.open('rb') as stream:
            magic, version, total, length, kind = struct.unpack('<4sIII4s', stream.read(20))
            assert (magic, version, total, kind) == (b'glTF', 2, path.stat().st_size, b'JSON')
            data = json.loads(stream.read(length))
        actual = sum(data['accessors'][primitive['indices']]['count'] // 3
                     for mesh in data['meshes'] for primitive in mesh['primitives'])
        assert triangles == actual, (path.name, triangles, actual)
        assert len(data['materials']) <= 6
        nodes = {node['name']: node for node in data['nodes']}
        for tag in ('fl', 'fr', 'rl', 'rr'):
            assert 'wheel_' + tag in nodes
        for tag in ('fl', 'fr'):
            assert any(data['nodes'][child]['name'] == 'wheel_' + tag
                       for child in nodes['steer_' + tag]['children'])
        for sampler in data.get('samplers', []):
            assert sampler['magFilter'] == 9728 and sampler['minFilter'] in (9728, 9984)
            assert sampler.get('wrapS', 10497) == sampler.get('wrapT', 10497) == 10497
        if lod == 'full':
            assert {'seat_driver', 'exit_left', 'exit_right', 'hood', 'steering_wheel',
                    'light_head_l', 'light_head_r', 'light_tail_l', 'light_tail_r'} <= set(nodes)
            if vehicle['era'][0] == 1970:
                assert {'door_left', 'light_work_front_l', 'light_work_rear_r'} <= set(nodes)
            for name in ('body_paint', 'lamp_faces'):
                assert any(mat['name'] == name for mat in data['materials'])
        print(path.name, actual, 'triangles,', len(data['materials']), 'materials')
    levels = vehicle['triangles']
    assert levels['full'] <= 3000 and levels['mid'] < .4 * levels['full'] and levels['far'] < 150
